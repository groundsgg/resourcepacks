package gg.grounds.resourcepacks.contract

import java.net.URI
import kotlin.ConsistentCopyVisibility

@ConsistentCopyVisibility
data class PackSetValidationPolicy
private constructor(val baseUri: URI, val packSet: String, private val normalized: Boolean) {
    constructor(baseUri: URI, packSet: String) : this(normalizeBaseUri(baseUri), packSet, true)

    init {
        require(normalized && baseUri == normalizeBaseUri(baseUri)) {
            "Base URI must be normalized."
        }
        require(
            packSet.isNotEmpty() && packSet.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        ) {
            "Pack set must be a safe single path segment."
        }
    }

    fun channelUri(channel: PackSetChannel): URI =
        uri("resourcepacks", "packsets", packSet, "channels", "${channel.name.lowercase()}.json")

    internal fun manifestUri(target: ChannelTarget): URI =
        publicationUri(target.type, target.id).append("manifest.json")

    internal fun publicationUri(type: PublicationType, id: String): URI =
        uri(
            "resourcepacks",
            "packsets",
            packSet,
            if (type == PublicationType.RELEASE) "releases" else "builds",
            id,
        )

    internal fun isSafeArtifactUri(value: String): Boolean =
        runCatching { URI(value) }
            .map { uri ->
                uri.scheme == "https" &&
                    uri.host == baseUri.host &&
                    uri.userInfo == null &&
                    uri.port == -1 &&
                    uri.query == null &&
                    uri.fragment == null &&
                    uri.rawPath != null &&
                    !uri.rawPath.contains('%') &&
                    !uri.rawPath.contains('\\') &&
                    uri.path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }
            }
            .getOrDefault(false)

    private fun uri(vararg segments: String): URI =
        URI("${baseUri}${segments.joinToString(prefix = "/", separator = "/")}")

    private fun URI.append(segment: String): URI = URI("$this/$segment")

    companion object {
        private val GROUNDS =
            PackSetValidationPolicy(URI("https://cdn.grounds.gg"), "grounds-global")

        @JvmStatic fun groundsDefault(): PackSetValidationPolicy = GROUNDS

        private fun normalizeBaseUri(value: URI): URI {
            require(value.isAbsolute && value.scheme == "https") {
                "Base URI must be absolute HTTPS."
            }
            require(value.host != null && value.userInfo == null && value.port == -1) {
                "Base URI must not include user info or an explicit port."
            }
            require(value.query == null && value.fragment == null) {
                "Base URI must not include a query or fragment."
            }
            require(value.rawPath.isNullOrEmpty() || value.rawPath == "/") {
                "Base URI must not include a non-root path."
            }
            return URI("https://${value.host}")
        }
    }
}
