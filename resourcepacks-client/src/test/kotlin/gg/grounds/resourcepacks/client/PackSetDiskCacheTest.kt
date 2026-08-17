package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackSetDiskCacheTest {
    // Break caught: accepting a truncated current pointer could activate an arbitrary generation.
    @Test
    fun `truncated current pointer fails closed`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val sourceDirectory = directory.resolve(source.cacheKey)
            Files.createDirectories(sourceDirectory)
            Files.writeString(sourceDirectory.resolve("current"), "truncated")

            assertNull(PackSetDiskCache(directory).load(source))
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: publishing a pointer before immutable generation files exist exposes a partial
    // cache.
    @Test
    fun `atomic generation is recoverable only after its current pointer is published`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val cache =
                ResolverCache(
                    "channel",
                    "channel".encodeToByteArray(),
                    "manifest",
                    "manifest".encodeToByteArray(),
                    null,
                )
            val disk = PackSetDiskCache(directory)

            disk.store(source, cache)

            val recovered = requireNotNull(disk.load(source))
            assertEquals("channel", recovered.channelEtag)
            assertTrue(recovered.channelBytes.contentEquals("channel".encodeToByteArray()))
            assertTrue(
                Files.isRegularFile(
                    directory.resolve(source.cacheKey).resolve("current"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
            )
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: truncated metadata must not turn a partially written generation into a cache
    // hit.
    @Test
    fun `truncated metadata fails closed`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val disk = PackSetDiskCache(directory)
            disk.store(source, resolverCache())
            Files.writeString(
                generation(directory, source).resolve("metadata.properties"),
                "sourceKey=",
            )

            assertNull(disk.load(source))
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: a symlinked cache root would make cache writes escape the caller-selected
    // directory.
    @Test
    fun `symlinked cache root is rejected`() {
        val parent = Files.createTempDirectory("pack-cache-test")
        val target = Files.createTempDirectory("pack-cache-target")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val link = parent.resolve("cache")
            Files.createSymbolicLink(link, target)

            assertNull(PackSetDiskCache(link).load(source))
        } finally {
            deleteTree(parent)
            deleteTree(target)
        }
    }

    // Break caught: a symlinked generation document would let a cache entry read outside its
    // generation.
    @Test
    fun `symlinked generation file is rejected`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        val target = Files.createTempFile("pack-cache-target", ".json")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val disk = PackSetDiskCache(directory)
            disk.store(source, resolverCache())
            val channel = generation(directory, source).resolve("channel.json")
            Files.delete(channel)
            Files.createSymbolicLink(channel, target)

            assertNull(disk.load(source))
        } finally {
            deleteTree(directory)
            Files.deleteIfExists(target)
        }
    }

    // Break caught: a generation copied from another source must not be activated under this source
    // key.
    @Test
    fun `mismatched source key fails closed`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val disk = PackSetDiskCache(directory)
            disk.store(source, resolverCache())
            val metadata = generation(directory, source).resolve("metadata.properties")
            Files.writeString(
                metadata,
                Files.readString(metadata)
                    .replace("sourceKey=${source.cacheKey}", "sourceKey=${"0".repeat(64)}"),
            )

            assertNull(disk.load(source))
        } finally {
            deleteTree(directory)
        }
    }

    private fun generation(directory: Path, source: PackSetSource): Path {
        val sourceDirectory = directory.resolve(source.cacheKey)
        val fingerprint = Files.readString(sourceDirectory.resolve("current")).trim()
        return sourceDirectory.resolve("generations").resolve(fingerprint)
    }

    private fun resolverCache() =
        ResolverCache(
            "channel",
            "channel".encodeToByteArray(),
            "manifest",
            "manifest".encodeToByteArray(),
            null,
        )

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
