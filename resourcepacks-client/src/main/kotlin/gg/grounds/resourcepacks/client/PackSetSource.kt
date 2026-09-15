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
    val selection: PackSetSelection,
    private val normalized: Boolean,
) {
    constructor(
        baseUri: URI,
        packSet: String,
        channel: PackSetChannel,
    ) : this(
        PackSetValidationPolicy(baseUri, packSet).baseUri,
        packSet,
        PackSetSelection.Channel(channel),
        true,
    )

    constructor(
        baseUri: URI,
        packSet: String,
        selection: PackSetSelection,
    ) : this(PackSetValidationPolicy(baseUri, packSet).baseUri, packSet, selection, true)

    val policy: PackSetValidationPolicy =
        PackSetValidationPolicy(baseUri, packSet).also {
            require(normalized && it.baseUri == baseUri) { "Base URI must be normalized." }
        }
    @Deprecated("Use selection instead.")
    val channel: PackSetChannel
        get() =
            (selection as? PackSetSelection.Channel)?.channel
                ?: throw IllegalStateException("A release source has no channel.")

    @Deprecated("Use requestUri instead.")
    val channelUri: URI
        get() =
            policy.channelUri(
                (selection as? PackSetSelection.Channel)?.channel
                    ?: throw IllegalStateException("A release source has no channel URI.")
            )

    val requestUri: URI =
        when (val selected = selection) {
            is PackSetSelection.Channel -> policy.channelUri(selected.channel)
            is PackSetSelection.Release ->
                URI(
                    "$baseUri/resourcepacks/packsets/$packSet/releases/${selected.id}/manifest.json"
                )
        }

    val cacheKey: String =
        when (val selected = selection) {
            is PackSetSelection.Channel ->
                sha256("$baseUri\n$packSet\n${selected.channel.name.lowercase()}")
            is PackSetSelection.Release -> sha256("release\n$baseUri\n$packSet\n${selected.id}")
        }

    companion object {
        @JvmStatic
        fun release(baseUri: URI, packSet: String, id: String): PackSetSource =
            PackSetSource(baseUri, packSet, PackSetSelection.Release(id))

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString(
                ""
            ) { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }
}
