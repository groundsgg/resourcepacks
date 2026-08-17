package gg.grounds.resourcepacks.client

import java.nio.file.Path
import java.time.Duration

data class PackSetClientConfig(
    val source: PackSetSource,
    val cacheDirectory: Path,
    val refreshInterval: Duration = Duration.ofSeconds(60),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(5),
    val maxChannelBytes: Int = 65_536,
    val maxManifestBytes: Int = 1_048_576,
) {
    init {
        require(!refreshInterval.isZero && !refreshInterval.isNegative) {
            "Refresh interval must be positive."
        }
        require(!connectTimeout.isZero && !connectTimeout.isNegative) {
            "Connect timeout must be positive."
        }
        require(!requestTimeout.isZero && !requestTimeout.isNegative) {
            "Request timeout must be positive."
        }
        require(maxChannelBytes > 0) { "Maximum channel bytes must be positive." }
        require(maxManifestBytes > 0) { "Maximum manifest bytes must be positive." }
    }
}
