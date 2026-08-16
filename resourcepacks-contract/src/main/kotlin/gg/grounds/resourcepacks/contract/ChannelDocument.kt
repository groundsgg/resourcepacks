package gg.grounds.resourcepacks.contract

import java.net.URI
import java.util.Collections

enum class PackSetChannel {
    STABLE,
    EDGE,
}

data class ChannelTarget(val type: PublicationType, val id: String) {
    init {
        ChannelValidation.requireTarget(type, id)
    }
}

data class ChannelManifestReference(val url: String, val sha256: String, val size: Long) {
    init {
        require(ChannelValidation.isStrictUrl(url)) { "Manifest URL is unsafe." }
        require(ChannelValidation.hex64.matches(sha256)) {
            "Manifest SHA-256 must be lowercase 64-hex."
        }
        require(size in 1..ManifestParserLimits.MAX_DOCUMENT.toLong()) {
            "Manifest size must be within the parser limit."
        }
    }
}

data class ChannelDocument(
    val schemaVersion: Int,
    val packSet: String,
    val channel: PackSetChannel,
    val sequence: Long,
    val target: ChannelTarget,
    val manifest: ChannelManifestReference,
) {
    init {
        ChannelValidation.requireDocument(this)
    }
}

enum class ChannelDiagnosticCode {
    MALFORMED_JSON,
    NON_CANONICAL_JSON,
    DUPLICATE_KEY,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    INVALID_VALUE,
}

data class ChannelDiagnostic(
    val pointer: String,
    val code: ChannelDiagnosticCode,
    val message: String,
)

sealed interface ChannelDecodeResult {
    data class Success(val document: ChannelDocument) : ChannelDecodeResult

    class Failure(diagnostics: List<ChannelDiagnostic>) : ChannelDecodeResult {
        val diagnostics: List<ChannelDiagnostic> =
            Collections.unmodifiableList(ArrayList(diagnostics))

        override fun equals(other: Any?): Boolean =
            other is Failure && diagnostics == other.diagnostics

        override fun hashCode(): Int = diagnostics.hashCode()
    }
}

private object ChannelValidation {
    val hex40 = Regex("[0-9a-f]{40}")
    val hex64 = Regex("[0-9a-f]{64}")
    private val semver =
        Regex(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)" +
                "(?:-(?:(?:0|[1-9][0-9]*)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:(?:0|[1-9][0-9]*)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
        )

    fun requireTarget(type: PublicationType, id: String) {
        when (type) {
            PublicationType.RELEASE ->
                require(isReleaseId(id)) { "Release target ID must be v<SemVer>." }
            PublicationType.BUILD ->
                require(hex40.matches(id)) { "Build target ID must be lowercase 40-hex." }
        }
    }

    fun isReleaseId(value: String): Boolean = value.startsWith("v") && semver.matches(value.drop(1))

    fun requireDocument(document: ChannelDocument) {
        require(document.schemaVersion == 2) { "schemaVersion must be 2." }
        require(document.packSet == "grounds-global") { "PackSet mismatch." }
        require(document.sequence > 0) { "Sequence must be positive." }
        when (document.channel) {
            PackSetChannel.STABLE ->
                require(document.target.type == PublicationType.RELEASE) {
                    "Stable channels require release targets."
                }
            PackSetChannel.EDGE ->
                require(document.target.type == PublicationType.BUILD) {
                    "Edge channels require build targets."
                }
        }
        require(document.manifest.url == manifestUrl(document.target)) { "Manifest URL mismatch." }
    }

    fun manifestUrl(target: ChannelTarget): String =
        "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/" +
            when (target.type) {
                PublicationType.RELEASE -> "releases/${target.id}/manifest.json"
                PublicationType.BUILD -> "builds/${target.id}/manifest.json"
            }

    fun isStrictUrl(value: String): Boolean =
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
}
