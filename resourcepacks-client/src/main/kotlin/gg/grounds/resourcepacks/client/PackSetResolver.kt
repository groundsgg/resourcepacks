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
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class PackSetResolver(
    private val transport: PackSetHttpTransport,
    private val config: PackSetClientConfig,
) {
    private val resultCaches = IdentityHashMap<RefreshResult, ResolverCache>()

    /** Rebind persisted raw bytes through the same contract checks used for a network response. */
    @JvmSynthetic
    fun revalidate(cache: ResolverCache): RefreshResult {
        if (config.source.selection is PackSetSelection.Release) return revalidateRelease(cache)
        val channelSelection = config.source.selection as PackSetSelection.Channel
        val channelBytes = cache.channelBytes ?: return failed("Channel cache was unavailable.")
        val manifestBytes = cache.manifestBytes ?: return failed("Manifest cache was unavailable.")
        val channel =
            when (
                val result =
                    PackSetContractJson.decodeChannel(
                        channelBytes,
                        config.source.policy,
                        channelSelection.channel,
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
        return cached(RefreshResult.Activated(snapshot), cache.copy(snapshot = snapshot))
    }

    @JvmSynthetic
    fun refresh(cache: ResolverCache): RefreshResult =
        if (config.source.selection is PackSetSelection.Release) refreshRelease(cache)
        else
            try {
                val channelSelection = config.source.selection as PackSetSelection.Channel
                val deadline = deadlineNanos(config.requestTimeout)
                val channelResponse =
                    transport.get(
                        config.source.requestUri,
                        cache.channelEtag,
                        config.requestTimeout,
                    )
                channelResponse.use { response ->
                    when (response.status) {
                        304 -> {
                            val snapshot = cache.snapshot
                            if (
                                cache.channelBytes == null ||
                                    snapshot == null ||
                                    snapshot.source != config.source
                            )
                                failed("Channel cache was unavailable.")
                            else cached(RefreshResult.Unchanged(snapshot), cache)
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

    private fun refreshRelease(cache: ResolverCache): RefreshResult {
        val selected = config.source.selection as PackSetSelection.Release
        val snapshot = cache.snapshot
        if (
            snapshot?.source == config.source &&
                cache.releaseId == selected.id &&
                snapshot.publication.type ==
                    gg.grounds.resourcepacks.contract.PublicationType.RELEASE &&
                snapshot.publication.id == selected.id
        )
            return cached(RefreshResult.Unchanged(snapshot), cache)
        return try {
            val deadline = deadlineNanos(config.requestTimeout)
            transport.get(config.source.requestUri, null, config.requestTimeout).use { response ->
                if (response.status != 200) return failed("Release manifest request failed.")
                resolveRelease(readBounded(response.body, config.maxManifestBytes, deadline), cache)
            }
        } catch (_: Throwable) {
            failed("Release manifest request failed.")
        }
    }

    private fun revalidateRelease(cache: ResolverCache): RefreshResult {
        val selected = config.source.selection as PackSetSelection.Release
        if (cache.releaseId != selected.id || cache.channelBytes != null)
            return failed("Release cache was unavailable.")
        val bytes = cache.manifestBytes ?: return failed("Release cache was unavailable.")
        return resolveRelease(bytes, cache)
    }

    private fun resolveRelease(bytes: ByteArray, cache: ResolverCache): RefreshResult {
        val selected = config.source.selection as PackSetSelection.Release
        val manifest =
            when (val result = PackSetContractJson.decodeManifest(bytes, config.source.policy)) {
                is ManifestDecodeResult.Success -> result.manifest
                is ManifestDecodeResult.Failure ->
                    return failed("Release manifest document was invalid.")
            }
        if (
            manifest.publication.type !=
                gg.grounds.resourcepacks.contract.PublicationType.RELEASE ||
                manifest.publication.id != selected.id
        )
            return failed("Release manifest target did not match selection.")
        val snapshot =
            PackSetSnapshot.fromValidatedReleaseBytes(
                config.source,
                manifest,
                resolvedPacks(manifest),
                bytes,
            )
        return cached(
            RefreshResult.Activated(snapshot),
            ResolverCache(null, null, null, bytes, snapshot, selected.id),
        )
    }

    private fun resolveChannel(
        bytes: ByteArray,
        etag: String?,
        cache: ResolverCache,
    ): RefreshResult {
        val channelSelection = config.source.selection as PackSetSelection.Channel
        val channel =
            when (
                val result =
                    PackSetContractJson.decodeChannel(
                        bytes,
                        config.source.policy,
                        channelSelection.channel,
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
                    cached(
                        RefreshResult.Activated(snapshot),
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

    @JvmSynthetic fun cacheOf(result: RefreshResult): ResolverCache? = resultCaches[result]

    private fun cached(result: RefreshResult, cache: ResolverCache): RefreshResult =
        result.also { resultCaches[it] = cache }

    private fun deadlineNanos(timeout: java.time.Duration): Long =
        Math.addExact(System.nanoTime(), timeout.toNanos())
}
