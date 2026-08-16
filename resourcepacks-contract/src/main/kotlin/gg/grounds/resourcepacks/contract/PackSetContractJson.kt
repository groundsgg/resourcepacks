package gg.grounds.resourcepacks.contract

import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory

/** Strict, canonical schema-v2 manifest decoder. JSON implementation types never escape it. */
object PackSetContractJson {
    fun decodeManifest(bytes: ByteArray): ManifestDecodeResult {
        if (bytes.size > ManifestParserLimits.MAX_DOCUMENT)
            return failure(
                "/",
                ManifestDiagnosticCode.MALFORMED_JSON,
                "Input exceeds ${ManifestParserLimits.MAX_DOCUMENT} bytes.",
            )
        val text =
            try {
                utf8(bytes)
            } catch (x: Throwable) {
                return failure(
                    "/",
                    ManifestDiagnosticCode.MALFORMED_JSON,
                    x.message ?: "Malformed JSON.",
                )
            }
        val root =
            try {
                parse(text)
            } catch (x: Duplicate) {
                return failure(
                    x.pointer.ifEmpty { "/" },
                    ManifestDiagnosticCode.DUPLICATE_KEY,
                    "Duplicate key.",
                )
            } catch (x: Throwable) {
                return failure(
                    "/",
                    ManifestDiagnosticCode.MALFORMED_JSON,
                    x.message ?: "Malformed JSON.",
                )
            }
        val diagnostics = mutableListOf<ManifestDiagnostic>()
        val manifest = decode(root, diagnostics)
        if (manifest != null) validate(manifest, diagnostics)
        if (manifest != null && diagnostics.isEmpty() && !bytes.contentEquals(encode(manifest)))
            diagnostics +=
                ManifestDiagnostic(
                    "/",
                    ManifestDiagnosticCode.NON_CANONICAL_JSON,
                    "JSON is not canonical.",
                )
        return if (diagnostics.isEmpty() && manifest != null) ManifestDecodeResult.Success(manifest)
        else
            ManifestDecodeResult.Failure(
                diagnostics
                    .distinct()
                    .sortedWith(
                        compareBy(
                            ManifestDiagnostic::pointer,
                            ManifestDiagnostic::code,
                            ManifestDiagnostic::message,
                        )
                    )
            )
    }

    private fun encode(manifest: PackSetManifest): ByteArray =
        json(root(manifest), 0).plus('\n').toByteArray(StandardCharsets.UTF_8)

    private fun decode(root: J, d: MutableList<ManifestDiagnostic>): PackSetManifest? {
        val o = root.obj("", ROOT, d) ?: return null
        fun string(name: String, base: String) = o.string(name, base, d)
        val publication =
            o.objectValue("publication", PUBLICATION, "", d)?.let { p ->
                val type =
                    p.string("type", "/publication", d)?.let {
                        if (it == "release") PublicationType.RELEASE
                        else if (it == "build") PublicationType.BUILD
                        else {
                            bad(
                                d,
                                "/publication/type",
                                "Publication type must be release or build.",
                            )
                            null
                        }
                    }
                val id = p.string("id", "/publication", d)
                if (type != null && id != null) ManifestPublication(type, id) else null
            }
        val minecraft =
            o.objectValue("minecraft", MINECRAFT, "", d)?.let { x ->
                ManifestMinecraft(
                    x.string("version", "/minecraft", d) ?: "",
                    x.integer("resourcePackFormat", "/minecraft", d) ?: 0,
                )
            }
        val catalog =
            o.objectValue("catalog", CATALOG, "", d)?.let { x ->
                ManifestCatalog(
                    x.string("id", "/catalog", d) ?: "",
                    x.string("version", "/catalog", d) ?: "",
                    x.string("coordinate", "/catalog", d) ?: "",
                    x.string("file", "/catalog", d) ?: "",
                    x.string("sha256", "/catalog", d) ?: "",
                    x.long("size", "/catalog", d) ?: 0,
                )
            }
        val packs =
            o.array("packs", "", d)?.mapIndexedNotNull { i, value ->
                value.obj("/packs/$i", PACK, d)?.let { x ->
                    val uuid =
                        x.string("uuid", "/packs/$i", d)?.let {
                            runCatching { UUID.fromString(it) }
                                .getOrElse {
                                    bad(d, "/packs/$i/uuid", "Invalid UUID.")
                                    UUID(0, 0)
                                }
                        } ?: UUID(0, 0)
                    ManifestPack(
                        x.integer("order", "/packs/$i", d) ?: 0,
                        x.string("role", "/packs/$i", d) ?: "",
                        x.string("id", "/packs/$i", d) ?: "",
                        uuid,
                        x.boolean("required", "/packs/$i", d) ?: false,
                        x.string("url", "/packs/$i", d) ?: "",
                        x.string("sha1", "/packs/$i", d) ?: "",
                        x.string("sha256", "/packs/$i", d) ?: "",
                        x.long("size", "/packs/$i", d) ?: 0,
                        x.integer("resourcePackFormat", "/packs/$i", d) ?: 0,
                    )
                }
            } ?: emptyList()
        val provenance =
            o.objectValue("provenance", PROVENANCE, "", d)?.let { x ->
                ManifestProvenance(
                    x.string("repository", "/provenance", d) ?: "",
                    x.string("commit", "/provenance", d) ?: "",
                )
            }
        return if (
            publication != null &&
                minecraft != null &&
                catalog != null &&
                provenance != null &&
                packs.size == (o.array("packs", "", d)?.size ?: -1)
        )
            PackSetManifest(
                o.integer("schemaVersion", "", d) ?: 0,
                string("packSet", "") ?: "",
                publication,
                string("version", "") ?: "",
                minecraft,
                catalog,
                packs,
                provenance,
            )
        else null
    }

    private fun validate(m: PackSetManifest, d: MutableList<ManifestDiagnostic>) {
        fun invalid(p: String, message: String) = bad(d, p, message)
        if (m.schemaVersion != 2) invalid("/schemaVersion", "schemaVersion must be 2.")
        if (m.packSet != "grounds-global") invalid("/packSet", "PackSet mismatch.")
        if (!SEMVER.matches(m.version)) invalid("/version", "version must be strict SemVer.")
        if (m.minecraft != ManifestMinecraft("26.2", 88))
            invalid("/minecraft", "Minecraft metadata mismatch.")
        if (
            m.catalog.id != "grounds:resourcepacks" ||
                m.catalog.version != m.version ||
                m.catalog.coordinate != "gg.grounds:resourcepacks-catalog:${m.version}"
        )
            invalid("/catalog", "Catalog metadata mismatch.")
        if (!HEX64.matches(m.catalog.sha256) || m.catalog.size <= 0)
            invalid("/catalog", "Catalog digest or size mismatch.")
        if (
            m.provenance.repository != "groundsgg/resourcepacks" ||
                !HEX40.matches(m.provenance.commit)
        )
            invalid("/provenance", "Provenance mismatch.")
        val root = publicationRoot(m, d)
        if (m.catalog.file != "grounds-resourcepack-catalog-${suffix(m, d)}.jar")
            invalid("/catalog/file", "Catalog filename mismatch.")
        if (m.packs.size != 2) invalid("/packs", "Exactly two packs are required.")
        val specs =
            listOf(
                Triple("content", "grounds-content", "44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"),
                Triple("platform", "grounds-platform", "8da7cffe-bb04-55e0-9868-7789ce5de362"),
            )
        m.packs.forEachIndexed { i, p ->
            val spec = specs.getOrNull(i)
            if (
                spec == null ||
                    p.order != i ||
                    p.role != spec.first ||
                    p.id != spec.second ||
                    p.uuid.toString() != spec.third ||
                    !p.required ||
                    p.resourcePackFormat != 88
            )
                invalid("/packs/$i", "Pack metadata mismatch.")
            if (!HEX40.matches(p.sha1) || !HEX64.matches(p.sha256) || p.size <= 0)
                invalid("/packs/$i", "Pack digest or size mismatch.")
            val expected = "$root/grounds-${spec?.first ?: "invalid"}-pack-${suffix(m, d)}.zip"
            if (!strictUrl(p.url) || p.url != expected)
                invalid("/packs/$i/url", "Pack URL mismatch.")
        }
    }

    private fun publicationRoot(m: PackSetManifest, d: MutableList<ManifestDiagnostic>): String {
        val p = m.publication
        return when (p.type) {
            PublicationType.RELEASE -> {
                if (p.id != "v${m.version}") bad(d, "/publication", "Release publication mismatch.")
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/${p.id}"
            }
            PublicationType.BUILD -> {
                if (p.id != m.provenance.commit || !EDGE.matches(m.version))
                    bad(d, "/publication", "Build publication mismatch.")
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/${p.id}"
            }
        }
    }

    private fun suffix(m: PackSetManifest, d: MutableList<ManifestDiagnostic>) =
        when (m.publication.type) {
            PublicationType.RELEASE -> m.publication.id
            PublicationType.BUILD -> {
                if (!m.version.endsWith(".g${m.provenance.commit.take(12)}"))
                    bad(d, "/version", "Build version commit mismatch.")
                "edge-${m.provenance.commit.take(12)}"
            }
        }

    private fun strictUrl(value: String): Boolean =
        runCatching {
                URI(value).let {
                    it.scheme == "https" &&
                        it.host == "cdn.grounds.gg" &&
                        it.userInfo == null &&
                        it.port == -1 &&
                        it.query == null &&
                        it.fragment == null &&
                        !it.rawPath.contains("%") &&
                        !it.path.contains("..")
                }
            }
            .getOrDefault(false)

    private fun parse(text: String): J =
        FACTORY.createParser(ObjectReadContext.empty(), StringReader(text)).use { p ->
            val token = p.nextToken() ?: error("Expected a JSON value.")
            read(p, token, 0, "").also { require(p.nextToken() == null) { "Trailing JSON input." } }
        }

    private fun read(p: JsonParser, token: JsonToken, depth: Int, pointer: String): J {
        require(depth <= ManifestParserLimits.MAX_DEPTH) { "Maximum nesting depth exceeded." }
        return when (token) {
            JsonToken.START_OBJECT -> {
                val fields = linkedMapOf<String, J>()
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    require(p.currentToken() == JsonToken.PROPERTY_NAME) {
                        "Expected object property."
                    }
                    val key = p.currentName()
                    require(key.length <= ManifestParserLimits.MAX_STRING) {
                        "String exceeds limit."
                    }
                    if (fields.containsKey(key)) throw Duplicate(pointer)
                    fields[key] =
                        read(
                            p,
                            p.nextToken() ?: error("Missing value."),
                            depth + 1,
                            "$pointer/${escape(key)}",
                        )
                }
                J.Obj(fields)
            }
            JsonToken.START_ARRAY -> {
                val values = mutableListOf<J>()
                while (p.nextToken() != JsonToken.END_ARRAY) values +=
                    read(p, p.currentToken(), depth + 1, "$pointer/${values.size}")
                J.Arr(values)
            }
            JsonToken.VALUE_STRING -> J.Str(p.string)
            JsonToken.VALUE_NUMBER_INT -> J.Num(p.string)
            JsonToken.VALUE_TRUE -> J.Bool(true)
            JsonToken.VALUE_FALSE -> J.Bool(false)
            JsonToken.VALUE_NULL -> J.Null
            else -> error("Unsupported JSON token.")
        }
    }

    private fun utf8(bytes: ByteArray): String {
        require(
            !(bytes.size >= 3 &&
                bytes[0] == 0xef.toByte() &&
                bytes[1] == 0xbb.toByte() &&
                bytes[2] == 0xbf.toByte())
        ) {
            "Byte-order marks are not permitted."
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private sealed interface J {
        data class Obj(val fields: Map<String, J>) : J

        data class Arr(val values: List<J>) : J

        data class Str(val value: String) : J

        data class Num(val value: String) : J

        data class Bool(val value: Boolean) : J

        data object Null : J
    }

    private fun J.obj(pointer: String, allowed: Set<String>, d: MutableList<ManifestDiagnostic>) =
        (this as? J.Obj)?.also { o ->
            o.fields.keys.filterNot(allowed::contains).forEach {
                bad(
                    d,
                    "$pointer/${escape(it)}",
                    "Unknown field.",
                    ManifestDiagnosticCode.UNKNOWN_FIELD,
                )
            }
            allowed.filterNot(o.fields::containsKey).forEach {
                bad(d, "$pointer/$it", "Missing field.", ManifestDiagnosticCode.MISSING_FIELD)
            }
        }
            ?: run {
                bad(d, pointer, "Expected object.", ManifestDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.objectValue(
        name: String,
        allowed: Set<String>,
        base: String,
        d: MutableList<ManifestDiagnostic>,
    ) = fields[name]?.obj("$base/$name", allowed, d)

    private fun J.Obj.array(name: String, base: String, d: MutableList<ManifestDiagnostic>) =
        (fields[name] as? J.Arr)?.values
            ?: run {
                bad(d, "$base/$name", "Expected array.", ManifestDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.string(name: String, base: String, d: MutableList<ManifestDiagnostic>) =
        (fields[name] as? J.Str)?.value
            ?: run {
                bad(d, "$base/$name", "Expected string.", ManifestDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.boolean(name: String, base: String, d: MutableList<ManifestDiagnostic>) =
        (fields[name] as? J.Bool)?.value
            ?: run {
                bad(d, "$base/$name", "Expected boolean.", ManifestDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.integer(name: String, base: String, d: MutableList<ManifestDiagnostic>) =
        (fields[name] as? J.Num)
            ?.value
            ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }
            ?.toIntOrNull()
            ?: run {
                bad(d, "$base/$name", "Expected exact integer.", ManifestDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.long(name: String, base: String, d: MutableList<ManifestDiagnostic>) =
        (fields[name] as? J.Num)
            ?.value
            ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }
            ?.toLongOrNull()
            ?: run {
                bad(d, "$base/$name", "Expected exact integer.", ManifestDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun root(m: PackSetManifest): Map<String, Any> =
        mapOf(
            "schemaVersion" to m.schemaVersion,
            "packSet" to m.packSet,
            "publication" to
                mapOf("type" to m.publication.type.name.lowercase(), "id" to m.publication.id),
            "version" to m.version,
            "minecraft" to
                mapOf(
                    "version" to m.minecraft.version,
                    "resourcePackFormat" to m.minecraft.resourcePackFormat,
                ),
            "catalog" to
                mapOf(
                    "id" to m.catalog.id,
                    "version" to m.catalog.version,
                    "coordinate" to m.catalog.coordinate,
                    "file" to m.catalog.file,
                    "sha256" to m.catalog.sha256,
                    "size" to m.catalog.size,
                ),
            "packs" to
                m.packs.map { p ->
                    mapOf(
                        "order" to p.order,
                        "role" to p.role,
                        "id" to p.id,
                        "uuid" to p.uuid.toString(),
                        "required" to p.required,
                        "url" to p.url,
                        "sha1" to p.sha1,
                        "sha256" to p.sha256,
                        "size" to p.size,
                        "resourcePackFormat" to p.resourcePackFormat,
                    )
                },
            "provenance" to
                mapOf("repository" to m.provenance.repository, "commit" to m.provenance.commit),
        )

    private fun json(value: Any, depth: Int): String =
        when (value) {
            is Map<*, *> ->
                value.entries
                    .sortedWith { a, b -> codePoints(a.key as String, b.key as String) }
                    .joinToString(",\n", "{\n", "\n${"  ".repeat(depth)}}") {
                        "${"  ".repeat(depth + 1)}${quote(it.key as String)}: ${json(it.value!!, depth + 1)}"
                    }
            is List<*> ->
                value.joinToString(",\n", "[\n", "\n${"  ".repeat(depth)}]") {
                    "${"  ".repeat(depth + 1)}${json(it!!, depth + 1)}"
                }
            is String -> quote(value)
            else -> value.toString()
        }

    private fun quote(value: String) = buildString {
        append('"')
        value.forEach {
            append(
                when (it) {
                    '"' -> "\\\""
                    '\\' -> "\\\\"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> it.toString()
                }
            )
        }
        append('"')
    }

    private fun codePoints(a: String, b: String): Int =
        a.codePoints()
            .toArray()
            .asList()
            .zip(b.codePoints().toArray().asList())
            .firstOrNull { it.first != it.second }
            ?.let { it.first.compareTo(it.second) } ?: a.length.compareTo(b.length)

    private fun failure(pointer: String, code: ManifestDiagnosticCode, message: String) =
        ManifestDecodeResult.Failure(listOf(ManifestDiagnostic(pointer, code, message)))

    private fun bad(
        d: MutableList<ManifestDiagnostic>,
        pointer: String,
        message: String,
        code: ManifestDiagnosticCode = ManifestDiagnosticCode.INVALID_VALUE,
    ) {
        d += ManifestDiagnostic(pointer, code, message)
    }

    private data class Duplicate(val pointer: String) : RuntimeException()

    private fun escape(value: String) = value.replace("~", "~0").replace("/", "~1")

    private val FACTORY =
        JsonFactory.builder()
            .disable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(ManifestParserLimits.MAX_DEPTH + 1)
                    .maxStringLength(ManifestParserLimits.MAX_STRING + 1)
                    .maxNumberLength(ManifestParserLimits.MAX_NUMBER + 1)
                    .maxDocumentLength(ManifestParserLimits.MAX_DOCUMENT.toLong())
                    .build()
            )
            .build()
    private val ROOT =
        setOf(
            "schemaVersion",
            "packSet",
            "publication",
            "version",
            "minecraft",
            "catalog",
            "packs",
            "provenance",
        )
    private val PUBLICATION = setOf("type", "id")
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
    private val PROVENANCE = setOf("repository", "commit")
    private val HEX40 = Regex("[0-9a-f]{40}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val EDGE = Regex("0\\.0\\.0-edge\\.[1-9][0-9]*\\.g[0-9a-f]{12}")
    private val SEMVER =
        Regex(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:(?:0|[1-9][0-9]*)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:(?:0|[1-9][0-9]*)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
        )
}
