package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.ChannelDecodeResult
import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.ManifestDecodeResult
import gg.grounds.resourcepacks.contract.PackSetContractJson
import gg.grounds.resourcepacks.contract.PackSetManifest
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class PackSetResolver(
    private val transport: PackSetHttpTransport,
    private val config: PackSetClientConfig,
) {
    /** Rebind persisted raw bytes through the same contract checks used for a network response. */
    fun revalidate(cache: ResolverCache): RefreshResult {
        val channelBytes = cache.channelBytes ?: return failed("Channel cache was unavailable.")
        val manifestBytes = cache.manifestBytes ?: return failed("Manifest cache was unavailable.")
        val channel =
            when (
                val result =
                    PackSetContractJson.decodeChannel(
                        channelBytes,
                        config.source.policy,
                        config.source.channel,
                    )
            ) {
                is ChannelDecodeResult.Success -> result.document
                is ChannelDecodeResult.Failure -> return failed("Channel document was invalid.")
            }
        if (!matchesManifestReference(manifestBytes, channel))
            return failed("Manifest integrity check failed.")
        val manifest =
            when (
                val result = PackSetContractJson.decodeManifest(manifestBytes, config.source.policy)
            ) {
                is ManifestDecodeResult.Success -> result.manifest
                is ManifestDecodeResult.Failure -> return failed("Manifest document was invalid.")
            }
        if (
            manifest.publication.type != channel.target.type ||
                manifest.publication.id != channel.target.id
        )
            return failed("Manifest target did not match channel.")
        val snapshot =
            PackSetSnapshot.fromValidatedBytes(
                config.source,
                channel,
                manifest,
                resolvedPacks(manifest),
                channelBytes,
                manifestBytes,
            )
        return RefreshResult.Activated(snapshot, cache.copy(snapshot = snapshot))
    }

    fun refresh(cache: ResolverCache): RefreshResult =
        try {
            val deadline = deadlineNanos(config.requestTimeout)
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
                            readBounded(response.body, config.maxChannelBytes, deadline),
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
            val deadline = deadlineNanos(config.requestTimeout)
            transport
                .get(
                    URI(channel.manifest.url),
                    if (reusable == null) null else cache.manifestEtag,
                    config.requestTimeout,
                )
                .use { response ->
                    val manifestBytes =
                        when (response.status) {
                            200 -> readBounded(response.body, config.maxManifestBytes, deadline)
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
                        PackSetSnapshot.fromValidatedBytes(
                            config.source,
                            channel,
                            manifest,
                            resolvedPacks(manifest),
                            channelBytes,
                            manifestBytes,
                        )
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

    private fun readBounded(input: InputStream, limit: Int, deadlineNanos: Long): ByteArray =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val read = executor.submit<ByteArray> { readBounded(input, limit) }
            try {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException("Response timed out.")
                read.get(remaining, TimeUnit.NANOSECONDS)
            } catch (x: TimeoutException) {
                input.close()
                read.cancel(true)
                throw x
            }
        }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        return BoundedResponseReader.read(input, limit)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun failed(reason: String) = RefreshResult.Failed(reason)

    private fun deadlineNanos(timeout: java.time.Duration): Long =
        Math.addExact(System.nanoTime(), timeout.toNanos())
}
