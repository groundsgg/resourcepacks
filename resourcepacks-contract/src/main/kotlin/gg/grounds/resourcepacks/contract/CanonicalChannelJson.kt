package gg.grounds.resourcepacks.contract

import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory

object CanonicalChannelJson {
    fun encode(document: ChannelDocument): ByteArray {
        requireValidDocument(document)
        return json(root(document), 0).plus('\n').toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): ChannelDecodeResult {
        if (bytes.size > ManifestParserLimits.MAX_DOCUMENT)
            return fail(
                "/",
                ChannelDiagnosticCode.MALFORMED_JSON,
                "Input exceeds ${ManifestParserLimits.MAX_DOCUMENT} bytes.",
            )
        val text =
            try {
                utf8(bytes)
            } catch (x: Throwable) {
                return fail(
                    "/",
                    ChannelDiagnosticCode.MALFORMED_JSON,
                    x.message ?: "Malformed JSON.",
                )
            }
        val root =
            try {
                parse(text)
            } catch (x: Duplicate) {
                return fail(
                    x.pointer.ifEmpty { "/" },
                    ChannelDiagnosticCode.DUPLICATE_KEY,
                    "Duplicate key.",
                )
            } catch (x: Throwable) {
                return fail(
                    "/",
                    ChannelDiagnosticCode.MALFORMED_JSON,
                    x.message ?: "Malformed JSON.",
                )
            }
        val diagnostics = mutableListOf<ChannelDiagnostic>()
        val doc = readDocument(root, diagnostics)?.let { validate(it, diagnostics) }
        if (doc != null && diagnostics.isEmpty() && !bytes.contentEquals(encode(doc)))
            diagnostics +=
                ChannelDiagnostic(
                    "/",
                    ChannelDiagnosticCode.NON_CANONICAL_JSON,
                    "JSON is not canonical.",
                )
        return if (doc != null && diagnostics.isEmpty()) ChannelDecodeResult.Success(doc)
        else
            ChannelDecodeResult.Failure(
                diagnostics
                    .distinct()
                    .sortedWith(
                        compareBy(
                            ChannelDiagnostic::pointer,
                            ChannelDiagnostic::code,
                            ChannelDiagnostic::message,
                        )
                    )
            )
    }

    private data class Raw(
        val version: Int,
        val packSet: String,
        val channel: String,
        val sequence: Long,
        val targetType: String,
        val targetId: String,
        val url: String,
        val sha256: String,
        val size: Long,
    )

    private fun readDocument(root: J, d: MutableList<ChannelDiagnostic>): Raw? {
        val o = root.obj("", ROOT, d) ?: return null
        val target = o.objectValue("target", TARGET, "", d)
        val manifest = o.objectValue("manifest", MANIFEST, "", d)
        if (target == null || manifest == null) return null
        val schemaVersion = o.int("schemaVersion", "", d)
        val packSet = o.string("packSet", "", d)
        val channel = o.string("channel", "", d)
        val sequence = o.long("sequence", "", d)
        val targetType = target.string("type", "/target", d)
        val targetId = target.string("id", "/target", d)
        val manifestUrl = manifest.string("url", "/manifest", d)
        val manifestSha256 = manifest.string("sha256", "/manifest", d)
        val manifestSize = manifest.long("size", "/manifest", d)
        if (d.isNotEmpty()) return null
        return Raw(
            requireNotNull(schemaVersion),
            requireNotNull(packSet),
            requireNotNull(channel),
            requireNotNull(sequence),
            requireNotNull(targetType),
            requireNotNull(targetId),
            requireNotNull(manifestUrl),
            requireNotNull(manifestSha256),
            requireNotNull(manifestSize),
        )
    }

    private fun validate(r: Raw, d: MutableList<ChannelDiagnostic>): ChannelDocument? {
        fun invalid(p: String, m: String) {
            d += ChannelDiagnostic(p, ChannelDiagnosticCode.INVALID_VALUE, m)
        }
        if (r.version != 2) invalid("/schemaVersion", "schemaVersion must be 2.")
        if (r.packSet != "grounds-global") invalid("/packSet", "PackSet mismatch.")
        val channel =
            when (r.channel) {
                "stable" -> PackSetChannel.STABLE
                "edge" -> PackSetChannel.EDGE
                else -> {
                    invalid("/channel", "Channel must be stable or edge.")
                    null
                }
            }
        if (r.sequence <= 0) invalid("/sequence", "Sequence must be positive.")
        val type =
            when (r.targetType) {
                "release" -> PublicationType.RELEASE
                "build" -> PublicationType.BUILD
                else -> {
                    invalid("/target/type", "Target type must be release or build.")
                    null
                }
            }
        if (type == PublicationType.RELEASE && !isReleaseId(r.targetId))
            invalid("/target/id", "Release target ID must be v<SemVer>.")
        if (type == PublicationType.BUILD && !HEX40.matches(r.targetId))
            invalid("/target/id", "Build target ID must be lowercase 40-hex.")
        if (channel == PackSetChannel.STABLE && type != PublicationType.RELEASE)
            invalid("/target/type", "Stable channels require release targets.")
        if (channel == PackSetChannel.EDGE && type != PublicationType.BUILD)
            invalid("/target/type", "Edge channels require build targets.")
        if (!isStrictUrl(r.url)) invalid("/manifest/url", "Manifest URL is unsafe.")
        if (!HEX64.matches(r.sha256))
            invalid("/manifest/sha256", "Manifest SHA-256 must be lowercase 64-hex.")
        if (r.size !in 1..ManifestParserLimits.MAX_DOCUMENT.toLong())
            invalid("/manifest/size", "Manifest size must be within the parser limit.")
        if (
            type != null &&
                (type != PublicationType.RELEASE || isReleaseId(r.targetId)) &&
                (type != PublicationType.BUILD || HEX40.matches(r.targetId))
        ) {
            if (r.url != manifestUrl(type, r.targetId))
                invalid("/manifest/url", "Manifest URL mismatch.")
        }
        return if (d.isEmpty() && channel != null && type != null)
            ChannelDocument(
                r.version,
                r.packSet,
                channel,
                r.sequence,
                ChannelTarget(type, r.targetId),
                ChannelManifestReference(r.url, r.sha256, r.size),
            )
        else null
    }

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
            JsonToken.VALUE_STRING -> J.Str(p.stringChecked())
            JsonToken.VALUE_NUMBER_INT -> J.Num(p.numberChecked())
            JsonToken.VALUE_NUMBER_FLOAT -> J.Decimal(p.numberChecked())
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

    private fun JsonParser.stringChecked(): String =
        string.also {
            require(it.length <= ManifestParserLimits.MAX_STRING) {
                "String exceeds ${ManifestParserLimits.MAX_STRING} characters."
            }
        }

    private fun JsonParser.numberChecked(): String =
        string.also {
            require(it.length <= ManifestParserLimits.MAX_NUMBER) {
                "Number exceeds ${ManifestParserLimits.MAX_NUMBER} characters."
            }
        }

    private fun requireValidDocument(document: ChannelDocument) {
        require(document.schemaVersion == 2) { "schemaVersion must be 2." }
        require(document.packSet == "grounds-global") { "PackSet mismatch." }
        require(document.sequence > 0) { "Sequence must be positive." }
        require(
            (document.channel == PackSetChannel.STABLE) ==
                (document.target.type == PublicationType.RELEASE)
        ) {
            "Channel target type mismatch."
        }
        require(document.manifest.url == manifestUrl(document.target.type, document.target.id)) {
            "Manifest URL mismatch."
        }
    }

    private fun manifestUrl(type: PublicationType, id: String): String =
        "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/" +
            when (type) {
                PublicationType.RELEASE -> "releases/$id/manifest.json"
                PublicationType.BUILD -> "builds/$id/manifest.json"
            }

    private fun isReleaseId(value: String): Boolean =
        value.startsWith("v") && SEMVER.matches(value.drop(1))

    private fun isStrictUrl(value: String): Boolean =
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

    private sealed interface J {
        data class Obj(val fields: Map<String, J>) : J

        data class Arr(val values: List<J>) : J

        data class Str(val value: String) : J

        data class Num(val value: String) : J

        data class Decimal(val value: String) : J

        data class Bool(val value: Boolean) : J

        data object Null : J
    }

    private fun J.obj(pointer: String, allowed: Set<String>, d: MutableList<ChannelDiagnostic>) =
        (this as? J.Obj)?.also { o ->
            o.fields.keys.filterNot(allowed::contains).forEach {
                bad(
                    d,
                    "$pointer/${escape(it)}",
                    "Unknown field.",
                    ChannelDiagnosticCode.UNKNOWN_FIELD,
                )
            }
            allowed.filterNot(o.fields::containsKey).forEach {
                bad(d, "$pointer/$it", "Missing field.", ChannelDiagnosticCode.MISSING_FIELD)
            }
        }
            ?: run {
                bad(d, pointer, "Expected object.", ChannelDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.objectValue(
        name: String,
        allowed: Set<String>,
        base: String,
        d: MutableList<ChannelDiagnostic>,
    ) = fields[name]?.obj("$base/$name", allowed, d)

    private fun J.Obj.string(name: String, base: String, d: MutableList<ChannelDiagnostic>) =
        (fields[name] as? J.Str)?.value
            ?: run {
                bad(d, "$base/$name", "Expected string.", ChannelDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.int(name: String, base: String, d: MutableList<ChannelDiagnostic>) =
        (fields[name] as? J.Num)
            ?.value
            ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }
            ?.toIntOrNull()
            ?: run {
                bad(d, "$base/$name", "Expected exact integer.", ChannelDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun J.Obj.long(name: String, base: String, d: MutableList<ChannelDiagnostic>) =
        (fields[name] as? J.Num)
            ?.value
            ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }
            ?.toLongOrNull()
            ?: run {
                bad(d, "$base/$name", "Expected exact integer.", ChannelDiagnosticCode.WRONG_TYPE)
                null
            }

    private fun root(d: ChannelDocument): Map<String, Any> =
        mapOf(
            "schemaVersion" to d.schemaVersion,
            "packSet" to d.packSet,
            "channel" to d.channel.name.lowercase(),
            "sequence" to d.sequence,
            "target" to mapOf("type" to d.target.type.name.lowercase(), "id" to d.target.id),
            "manifest" to
                mapOf(
                    "url" to d.manifest.url,
                    "sha256" to d.manifest.sha256,
                    "size" to d.manifest.size,
                ),
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

    private fun fail(pointer: String, code: ChannelDiagnosticCode, message: String) =
        ChannelDecodeResult.Failure(listOf(ChannelDiagnostic(pointer, code, message)))

    private fun bad(
        d: MutableList<ChannelDiagnostic>,
        pointer: String,
        message: String,
        code: ChannelDiagnosticCode = ChannelDiagnosticCode.INVALID_VALUE,
    ) {
        d += ChannelDiagnostic(pointer, code, message)
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
        setOf("schemaVersion", "packSet", "channel", "sequence", "target", "manifest")
    private val TARGET = setOf("type", "id")
    private val MANIFEST = setOf("url", "sha256", "size")
    private val HEX40 = Regex("[0-9a-f]{40}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val SEMVER =
        Regex(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)" +
                "(?:-(?:(?:0|[1-9][0-9]*)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:(?:0|[1-9][0-9]*)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
        )
}
