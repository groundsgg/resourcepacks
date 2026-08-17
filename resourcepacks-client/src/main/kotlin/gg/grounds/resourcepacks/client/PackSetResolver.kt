package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.ChannelDecodeResult
import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.ManifestDecodeResult
import gg.grounds.resourcepacks.contract.PackSetContractJson
import gg.grounds.resourcepacks.contract.PackSetManifest
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

internal class PackSetResolver(
    private val transport: PackSetHttpTransport,
    private val config: PackSetClientConfig,
) {
    fun refresh(cache: ResolverCache): RefreshResult =
        try {
            val channelResponse =
                transport.get(config.source.channelUri, cache.channelEtag, config.requestTimeout)
            channelResponse.use { response ->
                when (response.status) {
                    304 -> {
                        val snapshot = cache.snapshot
                        if (cache.channelBytes == null || snapshot == null)
                            failed("Channel cache was unavailable.")
                        else RefreshResult.Unchanged(snapshot, cache)
                    }
                    200 ->
                        resolveChannel(
                            readBounded(response.body, config.maxChannelBytes),
                            response.etag,
                            cache,
                        )
                    else -> failed("Channel request failed.")
                }
            }
        } catch (_: Throwable) {
            failed("Channel request failed.")
        }

    private fun resolveChannel(
        bytes: ByteArray,
        etag: String?,
        cache: ResolverCache,
    ): RefreshResult {
        val channel =
            when (
                val result =
                    PackSetContractJson.decodeChannel(
                        bytes,
                        config.source.policy,
                        config.source.channel,
                    )
            ) {
                is ChannelDecodeResult.Success -> result.document
                is ChannelDecodeResult.Failure -> return failed("Channel document was invalid.")
            }
        return resolveManifest(channel, bytes, etag, cache)
    }

    private fun resolveManifest(
        channel: ChannelDocument,
        channelBytes: ByteArray,
        channelEtag: String?,
        cache: ResolverCache,
    ): RefreshResult {
        val reusable = cache.manifestBytes?.takeIf { matchesManifestReference(it, channel) }
        return try {
            transport
                .get(
                    URI(channel.manifest.url),
                    if (reusable == null) null else cache.manifestEtag,
                    config.requestTimeout,
                )
                .use { response ->
                    val manifestBytes =
                        when (response.status) {
                            200 -> readBounded(response.body, config.maxManifestBytes)
                            304 -> reusable ?: return failed("Manifest cache was unavailable.")
                            else -> return failed("Manifest request failed.")
                        }
                    val manifestEtag =
                        if (response.status == 304) cache.manifestEtag else response.etag
                    if (!matchesManifestReference(manifestBytes, channel))
                        return failed("Manifest integrity check failed.")
                    val manifest =
                        when (
                            val result =
                                PackSetContractJson.decodeManifest(
                                    manifestBytes,
                                    config.source.policy,
                                )
                        ) {
                            is ManifestDecodeResult.Success -> result.manifest
                            is ManifestDecodeResult.Failure ->
                                return failed("Manifest document was invalid.")
                        }
                    if (
                        manifest.publication.type != channel.target.type ||
                            manifest.publication.id != channel.target.id
                    )
                        return failed("Manifest target did not match channel.")
                    val snapshot =
                        PackSetSnapshot(config.source, channel, manifest, resolvedPacks(manifest))
                    RefreshResult.Activated(
                        snapshot,
                        ResolverCache(
                            channelEtag,
                            channelBytes,
                            manifestEtag,
                            manifestBytes,
                            snapshot,
                        ),
                    )
                }
        } catch (_: Throwable) {
            failed("Manifest request failed.")
        }
    }

    private fun matchesManifestReference(bytes: ByteArray, channel: ChannelDocument): Boolean =
        bytes.size.toLong() == channel.manifest.size && sha256(bytes) == channel.manifest.sha256

    private fun resolvedPacks(manifest: PackSetManifest): List<ResolvedPack> =
        Collections.unmodifiableList(
            manifest.packs
                .sortedBy { it.order }
                .map {
                    ResolvedPack(
                        it.order,
                        it.role,
                        it.id,
                        it.uuid,
                        URI(it.url),
                        it.sha1,
                        it.sha256,
                        it.size,
                        it.required,
                    )
                }
        )

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        val limitPlusOne = Math.addExact(limit, 1)
        var count = 0
        while (true) {
            val permitted = minOf(buffer.size, limitPlusOne - count)
            val read = input.read(buffer, 0, permitted)
            if (read < 0) return output.toByteArray()
            count = Math.addExact(count, read)
            if (count > limit) throw IllegalArgumentException("Response exceeds configured limit.")
            output.write(buffer, 0, read)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun failed(reason: String) = RefreshResult.Failed(reason)

    private companion object {
        const val BUFFER_SIZE = 65_536
    }
}
