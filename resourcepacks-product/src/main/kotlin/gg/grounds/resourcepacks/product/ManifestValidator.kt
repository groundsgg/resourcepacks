package gg.grounds.resourcepacks.product

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory

internal object PackSetManifestJson {
    fun encode(manifest: PackSetManifest): ByteArray = CanonicalManifestJson.write(manifest)

    fun decodeAndValidate(
        bytes: ByteArray,
        artifacts: ManifestArtifacts,
    ): ManifestValidationResult {
        if (bytes.size > MAX_DOCUMENT)
            return invalid(
                "",
                ManifestProblemCode.MALFORMED_JSON,
                "Input exceeds $MAX_DOCUMENT bytes.",
            )
        val parsed =
            try {
                parse(bytes)
            } catch (failure: Throwable) {
                return invalid(
                    "",
                    ManifestProblemCode.MALFORMED_JSON,
                    failure.message ?: "Malformed JSON.",
                )
            }
        val problems = mutableListOf<ManifestProblem>()
        val manifest = decode(parsed, problems)
        if (manifest != null) {
            validate(manifest, artifacts, problems)
            return ManifestValidationResult(
                if (problems.isEmpty()) manifest else null,
                problems
                    .distinct()
                    .sortedWith(
                        compareBy(
                            ManifestProblem::pointer,
                            ManifestProblem::code,
                            ManifestProblem::message,
                        )
                    ),
            )
        }
        return ManifestValidationResult(
            null,
            problems
                .distinct()
                .sortedWith(
                    compareBy(
                        ManifestProblem::pointer,
                        ManifestProblem::code,
                        ManifestProblem::message,
                    )
                ),
        )
    }

    private fun parse(bytes: ByteArray): J =
        FACTORY.createParser(bytes).use { parser ->
            val first = parser.nextToken() ?: error("Expected a JSON value.")
            val result = read(parser, first, 0)
            require(parser.nextToken() == null) { "Trailing JSON input." }
            result
        }

    private fun read(parser: JsonParser, token: JsonToken, depth: Int): J {
        require(depth <= MAX_DEPTH) { "Maximum nesting depth exceeded." }
        return when (token) {
            JsonToken.START_OBJECT -> {
                val fields = linkedMapOf<String, J>()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    require(parser.currentToken() == JsonToken.PROPERTY_NAME) {
                        "Expected object property."
                    }
                    val name = parser.currentName()
                    require(name.length <= MAX_STRING && !hasUnpairedSurrogate(name)) {
                        "Invalid property name."
                    }
                    require(
                        fields.put(
                            name,
                            read(parser, parser.nextToken() ?: error("Missing value."), depth + 1),
                        ) == null
                    ) {
                        "Duplicate key: $name"
                    }
                }
                J.Obj(fields)
            }
            JsonToken.START_ARRAY -> {
                val values = mutableListOf<J>()
                while (parser.nextToken() != JsonToken.END_ARRAY) values +=
                    read(parser, parser.currentToken(), depth + 1)
                J.Arr(values)
            }
            JsonToken.VALUE_STRING -> J.Str(parser.stringChecked())
            JsonToken.VALUE_NUMBER_INT -> J.Num(parser.stringChecked())
            JsonToken.VALUE_TRUE -> J.Bool(true)
            JsonToken.VALUE_FALSE -> J.Bool(false)
            JsonToken.VALUE_NULL -> J.Null
            else -> error("Unsupported JSON token: $token")
        }
    }

    private fun JsonParser.stringChecked(): String =
        getString().also {
            require(it.length <= MAX_STRING && !hasUnpairedSurrogate(it)) {
                "Malformed or overlong string."
            }
        }

    private fun decode(root: J, p: MutableList<ManifestProblem>): PackSetManifest? {
        val o = root.objectAt("", ROOT, p) ?: return null
        val minecraft =
            o.obj("minecraft", MINECRAFT, p)?.let {
                MinecraftManifest(it.str("version", p) ?: "", it.int("resourcePackFormat", p) ?: 0)
            }
        val catalog =
            o.obj("catalog", CATALOG, p)?.let {
                CatalogManifest(
                    it.str("id", p) ?: "",
                    it.str("version", p) ?: "",
                    it.str("coordinate", p) ?: "",
                    it.str("file", p) ?: "",
                    it.str("sha256", p) ?: "",
                    it.long("size", p) ?: 0,
                )
            }
        val packs =
            o.arr("packs", p)?.mapIndexedNotNull { index, value ->
                value.objectAt("/packs/$index", PACK, p)?.let { x ->
                    PackManifest(
                        x.int("order", p) ?: 0,
                        x.str("role", p) ?: "",
                        x.str("id", p) ?: "",
                        uuid(x.str("uuid", p), "/packs/$index/uuid", p),
                        x.bool("required", p) ?: false,
                        x.str("url", p) ?: "",
                        x.str("sha1", p) ?: "",
                        x.str("sha256", p) ?: "",
                        x.long("size", p) ?: 0,
                        x.int("resourcePackFormat", p) ?: 0,
                    )
                }
            } ?: emptyList()
        val provenance =
            o.obj("provenance", PROVENANCE, p)?.let {
                ProvenanceManifest(
                    it.str("repository", p) ?: "",
                    it.str("commit", p) ?: "",
                    it.str("tag", p) ?: "",
                )
            }
        val schema = o.int("schemaVersion", p) ?: 0
        val id = o.str("id", p) ?: ""
        val version = o.str("version", p) ?: ""
        return if (
            minecraft != null &&
                catalog != null &&
                provenance != null &&
                packs.size == (o.arr("packs", p)?.size ?: -1)
        )
            PackSetManifest(version, minecraft, catalog, packs, provenance, schema, id)
        else null
    }

    private fun validate(
        m: PackSetManifest,
        a: ManifestArtifacts,
        p: MutableList<ManifestProblem>,
    ) {
        fun bad(pointer: String, message: String) {
            p += ManifestProblem(pointer, ManifestProblemCode.INVALID_VALUE, message)
        }
        if (m.schemaVersion != 1) bad("/schemaVersion", "schemaVersion must be 1.")
        if (m.id != "grounds:global") bad("/id", "id must be grounds:global.")
        if (!SEMVER.matches(m.version)) bad("/version", "version must be strict SemVer.")
        if (m.minecraft.version != "26.2")
            bad("/minecraft/version", "Minecraft version must be 26.2.")
        if (m.minecraft.resourcePackFormat != PackSetConstants.FORMAT)
            bad("/minecraft/resourcePackFormat", "Resource pack format must be 88.")
        val c = m.catalog
        if (c.id != "grounds:resourcepacks") bad("/catalog/id", "Catalog id mismatch.")
        if (c.version != m.version) bad("/catalog/version", "Catalog version mismatch.")
        if (c.coordinate != "gg.grounds:resourcepacks-catalog:${m.version}")
            bad("/catalog/coordinate", "Catalog coordinate mismatch.")
        if (c.file != "grounds-resourcepacks-catalog-${m.version}.jar")
            bad("/catalog/file", "Catalog filename mismatch.")
        hash(c.sha256, 64, "/catalog/sha256", p)
        positive(c.size, "/catalog/size", p)
        if (m.packs.size != 2) bad("/packs", "Exactly two packs are required.")
        val expected =
            listOf(
                Triple(PackRole.CONTENT, "grounds-content", PackSetConstants.contentUuid),
                Triple(PackRole.PLATFORM, "grounds-platform", PackSetConstants.platformUuid),
            )
        m.packs.forEachIndexed { index, pack ->
            val prefix = "/packs/$index"
            val spec = expected.getOrNull(index)
            if (spec == null || pack.role != spec.first.name.lowercase())
                bad("$prefix/role", "Pack role/order mismatch.")
            if (pack.order != index) bad("$prefix/order", "Pack order mismatch.")
            if (spec == null || pack.id != spec.second) bad("$prefix/id", "Pack id mismatch.")
            if (spec == null || pack.uuid != spec.third) bad("$prefix/uuid", "Pack UUID mismatch.")
            if (!pack.required) bad("$prefix/required", "Pack must be required.")
            if (pack.resourcePackFormat != PackSetConstants.FORMAT)
                bad("$prefix/resourcePackFormat", "Format must be 88.")
            hash(pack.sha1, 40, "$prefix/sha1", p)
            hash(pack.sha256, 64, "$prefix/sha256", p)
            positive(pack.size, "$prefix/size", p)
            url(pack, prefix, p)
        }
        if (m.packs.map(PackManifest::role).distinct().size != m.packs.size)
            bad("/packs", "Duplicate roles.")
        if (m.packs.map(PackManifest::order).distinct().size != m.packs.size)
            bad("/packs", "Duplicate orders.")
        if (m.packs.map(PackManifest::uuid).distinct().size != m.packs.size)
            bad("/packs", "Duplicate UUIDs.")
        if (m.provenance.repository != "groundsgg/resourcepacks")
            bad("/provenance/repository", "Repository mismatch.")
        if (!LOWER_HEX_40.matches(m.provenance.commit))
            bad("/provenance/commit", "Commit must be lowercase 40-hex.")
        if (m.provenance.tag != "v${m.version}")
            bad("/provenance/tag", "Tag must equal v<version>.")
        artifact(a.catalog, c.file, c.size, null, c.sha256, "/catalog", p)
        m.packs.forEach { pack ->
            PackRole.entries
                .find { it.name.equals(pack.role, true) }
                ?.let { role ->
                    artifact(
                        a.packs[role],
                        "${pack.sha1}.zip",
                        pack.size,
                        pack.sha1,
                        pack.sha256,
                        "/packs/${pack.order}",
                        p,
                    )
                }
        }
    }

    private fun artifact(
        path: java.nio.file.Path?,
        file: String,
        size: Long,
        sha1: String?,
        sha256: String,
        pointer: String,
        p: MutableList<ManifestProblem>,
    ) {
        if (path == null || !Files.exists(path, NOFOLLOW_LINKS)) {
            p += ManifestProblem(pointer, ManifestProblemCode.ARTIFACT_MISSING, "Artifact missing.")
            return
        }
        if (path.fileName.toString() != file)
            p +=
                ManifestProblem(
                    "$pointer/file",
                    ManifestProblemCode.ARTIFACT_MISMATCH,
                    "Artifact filename mismatch.",
                )
        val digest =
            try {
                ArtifactDigests.readRegularFile(path)
            } catch (e: Exception) {
                p +=
                    ManifestProblem(
                        pointer,
                        ManifestProblemCode.ARTIFACT_NOT_REGULAR,
                        e.message ?: "Unreadable artifact.",
                    )
                return
            }
        if (digest.size != size || digest.sha256 != sha256 || (sha1 != null && digest.sha1 != sha1))
            p +=
                ManifestProblem(
                    pointer,
                    ManifestProblemCode.ARTIFACT_MISMATCH,
                    "Artifact bytes, hash, or size mismatch.",
                )
    }

    private fun hash(value: String, length: Int, pointer: String, p: MutableList<ManifestProblem>) {
        if (!Regex("[0-9a-f]{$length}").matches(value))
            p +=
                ManifestProblem(
                    pointer,
                    ManifestProblemCode.INVALID_VALUE,
                    "Hash must be lowercase $length-hex.",
                )
    }

    private fun positive(value: Long, pointer: String, p: MutableList<ManifestProblem>) {
        if (value <= 0)
            p +=
                ManifestProblem(
                    pointer,
                    ManifestProblemCode.INVALID_VALUE,
                    "Size must be positive.",
                )
    }

    private fun url(pack: PackManifest, prefix: String, p: MutableList<ManifestProblem>) {
        try {
            val uri = URI(pack.url)
            if (
                uri.scheme != "https" ||
                    uri.userInfo != null ||
                    uri.port != -1 ||
                    uri.host != "cdn.grounds.gg" ||
                    uri.query != null ||
                    uri.fragment != null ||
                    uri.path != "/resourcepacks/${pack.role}/${pack.sha1}.zip"
            ) {
                p +=
                    ManifestProblem(
                        "$prefix/url",
                        ManifestProblemCode.INVALID_VALUE,
                        "Pack URL mismatch.",
                    )
            }
        } catch (_: Exception) {
            p +=
                ManifestProblem(
                    "$prefix/url",
                    ManifestProblemCode.INVALID_VALUE,
                    "Invalid pack URL.",
                )
        }
    }

    private fun uuid(value: String?, pointer: String, p: MutableList<ManifestProblem>): UUID =
        try {
            UUID.fromString(value)
        } catch (_: Exception) {
            p += ManifestProblem(pointer, ManifestProblemCode.INVALID_VALUE, "Invalid UUID.")
            UUID(0, 0)
        }

    private fun invalid(pointer: String, code: ManifestProblemCode, message: String) =
        ManifestValidationResult(null, listOf(ManifestProblem(pointer, code, message)))

    private sealed interface J {
        data class Obj(val fields: Map<String, J>) : J

        data class Arr(val values: List<J>) : J

        data class Str(val value: String) : J

        data class Num(val value: String) : J

        data class Bool(val value: Boolean) : J

        data object Null : J
    }

    private fun J.objectAt(
        pointer: String,
        allowed: Set<String>,
        p: MutableList<ManifestProblem>,
    ): J.Obj? =
        (this as? J.Obj)?.also { x ->
            x.fields.keys.filterNot(allowed::contains).forEach {
                p +=
                    ManifestProblem(
                        "$pointer/$it",
                        ManifestProblemCode.UNKNOWN_FIELD,
                        "Unknown field.",
                    )
            }
            allowed.filterNot(x.fields::containsKey).forEach {
                p +=
                    ManifestProblem(
                        "$pointer/$it",
                        ManifestProblemCode.MISSING_FIELD,
                        "Missing field.",
                    )
            }
        }
            ?: run {
                p += ManifestProblem(pointer, ManifestProblemCode.WRONG_TYPE, "Expected object.")
                null
            }

    private fun J.Obj.obj(name: String, allowed: Set<String>, p: MutableList<ManifestProblem>) =
        (fields[name] as? J)?.objectAt("/$name", allowed, p)

    private fun J.Obj.arr(name: String, p: MutableList<ManifestProblem>) =
        (fields[name] as? J.Arr)?.values
            ?: run {
                p += ManifestProblem("/$name", ManifestProblemCode.WRONG_TYPE, "Expected array.")
                null
            }

    private fun J.Obj.str(name: String, p: MutableList<ManifestProblem>) =
        (fields[name] as? J.Str)?.value
            ?: run {
                p += ManifestProblem("/$name", ManifestProblemCode.WRONG_TYPE, "Expected string.")
                null
            }

    private fun J.Obj.bool(name: String, p: MutableList<ManifestProblem>) =
        (fields[name] as? J.Bool)?.value
            ?: run {
                p += ManifestProblem("/$name", ManifestProblemCode.WRONG_TYPE, "Expected boolean.")
                null
            }

    private fun J.Obj.int(name: String, p: MutableList<ManifestProblem>) =
        (fields[name] as? J.Num)
            ?.value
            ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }
            ?.toIntOrNull()
            ?: run {
                p +=
                    ManifestProblem(
                        "/$name",
                        ManifestProblemCode.WRONG_TYPE,
                        "Expected exact integer.",
                    )
                null
            }

    private fun J.Obj.long(name: String, p: MutableList<ManifestProblem>) =
        (fields[name] as? J.Num)
            ?.value
            ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }
            ?.toLongOrNull()
            ?: run {
                p +=
                    ManifestProblem(
                        "/$name",
                        ManifestProblemCode.WRONG_TYPE,
                        "Expected exact integer.",
                    )
                null
            }

    private val FACTORY =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .maxStringLength(MAX_STRING)
                    .maxNumberLength(MAX_NUMBER)
                    .maxDocumentLength(MAX_DOCUMENT.toLong())
                    .build()
            )
            .build()
    private val SEMVER =
        Regex(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
        )
    private val LOWER_HEX_40 = Regex("[0-9a-f]{40}")

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate()) {
                if (i + 1 == value.length || !value[i + 1].isLowSurrogate()) return true
                i++
            } else if (c.isLowSurrogate()) return true
            i++
        }
        return false
    }

    private val ROOT =
        setOf("schemaVersion", "id", "version", "minecraft", "catalog", "packs", "provenance")
    private val MINECRAFT = setOf("version", "resourcePackFormat")
    private val CATALOG = setOf("id", "version", "coordinate", "file", "sha256", "size")
    private val PACK =
        setOf(
            "order",
            "role",
            "id",
            "uuid",
            "required",
            "url",
            "sha1",
            "sha256",
            "size",
            "resourcePackFormat",
        )
    private val PROVENANCE = setOf("repository", "commit", "tag")
    private const val MAX_DEPTH = 64
    private const val MAX_STRING = 16_384
    private const val MAX_NUMBER = 128
    private const val MAX_DOCUMENT = 1_048_576
}
