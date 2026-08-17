package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.PackSetChannel
import gg.grounds.resourcepacks.contract.PackSetValidationPolicy
import java.net.URI
import java.security.MessageDigest
import kotlin.ConsistentCopyVisibility

@ConsistentCopyVisibility
data class PackSetSource
private constructor(
    val baseUri: URI,
    val packSet: String,
    val channel: PackSetChannel,
    private val normalized: Boolean,
) {
    constructor(
        baseUri: URI,
        packSet: String,
        channel: PackSetChannel,
    ) : this(PackSetValidationPolicy(baseUri, packSet).baseUri, packSet, channel, true)

    val policy: PackSetValidationPolicy =
        PackSetValidationPolicy(baseUri, packSet).also {
            require(normalized && it.baseUri == baseUri) { "Base URI must be normalized." }
        }
    val channelUri: URI = policy.channelUri(channel)
    val cacheKey: String = sha256("$baseUri\n$packSet\n${channel.name.lowercase()}")

    private companion object {
        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString(
                ""
            ) { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }
}
