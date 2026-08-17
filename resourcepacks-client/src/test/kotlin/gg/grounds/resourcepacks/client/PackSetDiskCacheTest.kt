package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // Break caught: an interrupted plugin write leaves private staging names but must not hide the
    // already atomically published last-known-good generation.
    @Test
    fun `exact private interrupted write artifacts do not hide published generation`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source =
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
            val disk = PackSetDiskCache(directory)
            disk.store(source, resolverCache())
            val sourceDirectory = directory.resolve(source.cacheKey)
            Files.createFile(sourceDirectory.resolve(".current-${UUID.randomUUID()}"))
            Files.createDirectory(
                sourceDirectory.resolve("generations/.staging-${UUID.randomUUID()}")
            )

            assertEquals("channel", requireNotNull(disk.load(source)).channelEtag)
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: prefix-only matching lets caller-owned or attacker-controlled names masquerade
    // as plugin-private interrupted-write artifacts.
    @Test
    fun `private artifact prefix impostors fail closed`() {
        listOf(
                ".current-interrupted" to false,
                ".current-${UUID.randomUUID()}-suffix" to false,
                ".staging-interrupted" to true,
                ".staging-${UUID.randomUUID()}-suffix" to true,
            )
            .forEach { (name, staging) ->
                val directory = Files.createTempDirectory("pack-cache-test")
                try {
                    val source = source()
                    val disk = PackSetDiskCache(directory)
                    disk.store(source, resolverCache())
                    val sourceDirectory = directory.resolve(source.cacheKey)
                    if (staging) Files.createDirectory(sourceDirectory.resolve("generations/$name"))
                    else Files.createFile(sourceDirectory.resolve(name))

                    assertNull(disk.load(source), name)
                } finally {
                    deleteTree(directory)
                }
            }
    }

    // Break caught: a symlink with an otherwise exact private name must never be treated as a safe
    // interrupted plugin write.
    @Test
    fun `symlinked private artifacts fail closed`() {
        listOf(false, true).forEach { staging ->
            val directory = Files.createTempDirectory("pack-cache-test")
            val target = Files.createTempFile("pack-cache-private-target", ".tmp")
            try {
                val source = source()
                val disk = PackSetDiskCache(directory)
                disk.store(source, resolverCache())
                val sourceDirectory = directory.resolve(source.cacheKey)
                val link =
                    if (staging)
                        sourceDirectory.resolve("generations/.staging-${UUID.randomUUID()}")
                    else sourceDirectory.resolve(".current-${UUID.randomUUID()}")
                Files.createSymbolicLink(link, target)

                assertNull(disk.load(source))
            } finally {
                deleteTree(directory)
                Files.deleteIfExists(target)
            }
        }
    }

    // Break caught: validating every immutable generation lets a corrupt non-current generation
    // hide a valid pointer-selected last-known-good generation.
    @Test
    fun `malformed non-current generation does not hide selected generation`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source = source()
            val disk = PackSetDiskCache(directory)
            disk.store(source, resolverCache())
            val generations = directory.resolve(source.cacheKey).resolve("generations")
            val malformed = generations.resolve("0".repeat(64))
            Files.createDirectory(malformed)
            Files.createFile(malformed.resolve("channel.json"))

            assertEquals("channel", requireNotNull(disk.load(source)).channelEtag)
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: a crash after creating generations but before publishing current must not make
    // the source directory permanently unwritable.
    @Test
    fun `store repairs an interrupted first publication with no current pointer`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source = source()
            val sourceDirectory = directory.resolve(source.cacheKey)
            val generations = sourceDirectory.resolve("generations")
            Files.createDirectories(generations)
            Files.createDirectory(generations.resolve(".staging-${UUID.randomUUID()}"))
            val disk = PackSetDiskCache(directory)

            disk.store(source, resolverCache())

            assertEquals("channel", requireNotNull(disk.load(source)).channelEtag)
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: forcing file contents without forcing staging directory entries before rename
    // can publish a generation whose names disappear after a crash.
    @Test
    fun `store fsyncs staging entries before generation and pointer parent directories`() {
        val directory = Files.createTempDirectory("pack-cache-test")
        try {
            val source = source()
            val synced = mutableListOf<Path>()
            val disk = diskCacheWithDirectorySync(directory) { path -> synced.add(path) }

            disk.store(source, resolverCache())

            val sourceDirectory = directory.resolve(source.cacheKey)
            val generations = sourceDirectory.resolve("generations")
            assertEquals(4, synced.size)
            assertEquals(directory, synced[0])
            assertEquals(generations, synced[1].parent)
            assertTrue(synced[1].fileName.toString().startsWith(".staging-"))
            assertEquals(generations, synced[2])
            assertEquals(sourceDirectory, synced[3])
        } finally {
            deleteTree(directory)
        }
    }

    // Break caught: creating the cache root must durably publish both the root and source-key
    // entries before store reports success.
    @Test
    fun `first store fsyncs created cache and source directory parent entries`() {
        val parent = Files.createTempDirectory("pack-cache-parent")
        try {
            val root = parent.resolve("cache")
            val source = source()
            val synced = mutableListOf<Path>()
            val disk = diskCacheWithDirectorySync(root) { path -> synced.add(path) }

            disk.store(source, resolverCache())

            assertEquals(parent, synced[0])
            assertEquals(root, synced[1])
            assertEquals("channel", requireNotNull(disk.load(source)).channelEtag)
        } finally {
            deleteTree(parent)
        }
    }

    // Break caught: exact private staging names do not authorize unknown or symlinked child
    // entries, even though interrupted partial generation files are otherwise tolerated.
    @Test
    fun `private staging children reject unknown names and symlinks`() {
        listOf(false, true).forEach { symlinkChild ->
            val directory = Files.createTempDirectory("pack-cache-test")
            val target = Files.createTempFile("pack-cache-staging-target", ".tmp")
            try {
                val source = source()
                val disk = PackSetDiskCache(directory)
                disk.store(source, resolverCache())
                val staging =
                    directory
                        .resolve(source.cacheKey)
                        .resolve("generations/.staging-${UUID.randomUUID()}")
                Files.createDirectory(staging)
                if (symlinkChild) Files.createSymbolicLink(staging.resolve("channel.json"), target)
                else Files.createFile(staging.resolve("unknown"))

                assertNull(disk.load(source))
                assertFailsWith<IllegalArgumentException> { disk.store(source, resolverCache()) }
            } finally {
                deleteTree(directory)
                Files.deleteIfExists(target)
            }
        }
    }

    // Break caught: unknown caller-owned entries must not be silently ignored as recovery debris.
    @Test
    fun `unknown source and generation entries fail closed`() {
        listOf(false, true).forEach { generationEntry ->
            val directory = Files.createTempDirectory("pack-cache-test")
            try {
                val source = source()
                val disk = PackSetDiskCache(directory)
                disk.store(source, resolverCache())
                val sourceDirectory = directory.resolve(source.cacheKey)
                val unknown =
                    if (generationEntry) sourceDirectory.resolve("generations/unknown")
                    else sourceDirectory.resolve("unknown")
                Files.createFile(unknown)

                assertNull(disk.load(source))
                assertFailsWith<IllegalArgumentException> { disk.store(source, resolverCache()) }
            } finally {
                deleteTree(directory)
            }
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

    private fun source() =
        PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)

    private fun diskCacheWithDirectorySync(root: Path, sync: (Path) -> Unit): PackSetDiskCache {
        val constructor =
            PackSetDiskCache::class.java.declaredConstructors.single { it.parameterCount == 3 }
        constructor.isAccessible = true
        return constructor.newInstance(root, sync, Unit) as PackSetDiskCache
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
