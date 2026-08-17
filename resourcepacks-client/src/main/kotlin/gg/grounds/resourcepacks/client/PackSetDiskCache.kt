package gg.grounds.resourcepacks.client

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** A fail-closed cache whose only mutable item is the source-local current pointer. */
internal class PackSetDiskCache
private constructor(
    private val root: Path,
    private val directorySync: (Path) -> Unit,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    constructor(root: Path) : this(root, ::forceDirectoryDefault, Unit)

    @JvmSynthetic
    fun load(source: PackSetSource): ResolverCache? = load(source, Int.MAX_VALUE, Int.MAX_VALUE)

    @JvmSynthetic
    fun load(source: PackSetSource, maxChannelBytes: Int, maxManifestBytes: Int): ResolverCache? =
        try {
            val sourceDirectory = sourceDirectory(source, create = false) ?: return null
            val hasCurrent = requireSourceEntries(sourceDirectory)
            if (!hasCurrent) return null
            val current = sourceDirectory.resolve("current")
            if (!regularFile(current)) return null
            val fingerprint = String(readFile(current, 65), Charsets.US_ASCII)
            require(
                FINGERPRINT.matches(fingerprint.trimEnd('\n')) &&
                    fingerprint == fingerprint.trimEnd('\n') + "\n"
            )
            val generations = sourceDirectory.resolve("generations")
            require(directory(generations))
            validateGenerationEntries(generations)
            val generation = generations.resolve(fingerprint.trimEnd())
            require(directory(generation))
            requireExactEntries(generation, GENERATION_FILES)
            val channel = readFile(generation.resolve("channel.json"), maxChannelBytes)
            val manifest = readFile(generation.resolve("manifest.json"), maxManifestBytes)
            val metadata = metadata(readFile(generation.resolve("metadata.properties"), 8_192))
            require(metadata["sourceKey"] == source.cacheKey)
            require(metadata["fingerprint"] == fingerprint.trimEnd())
            require(metadata["channelSha256"] == sha256(channel))
            require(metadata["manifestSha256"] == sha256(manifest))
            require(sha256(channel + manifest) == fingerprint.trimEnd())
            ResolverCache(
                decodeNullable(metadata.getValue("channelEtag")),
                channel,
                decodeNullable(metadata.getValue("manifestEtag")),
                manifest,
                null,
            )
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    @JvmSynthetic
    fun store(source: PackSetSource, cache: ResolverCache) {
        val channel = requireNotNull(cache.channelBytes) { "Channel bytes are required." }
        val manifest = requireNotNull(cache.manifestBytes) { "Manifest bytes are required." }
        val fingerprint = sha256(channel + manifest)
        require(FINGERPRINT.matches(fingerprint))
        val sourceDirectory = requireNotNull(sourceDirectory(source, create = true))
        if (Files.list(sourceDirectory).use { it.findAny().isPresent })
            requireSourceEntries(sourceDirectory)
        val generations = sourceDirectory.resolve("generations")
        createDirectory(generations)
        require(directory(generations))
        validateGenerationEntries(generations)
        val target = generations.resolve(fingerprint)
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            val staging = generations.resolve(".staging-${UUID.randomUUID()}")
            Files.createDirectory(staging)
            writeNew(staging.resolve("channel.json"), channel)
            writeNew(staging.resolve("manifest.json"), manifest)
            writeNew(
                staging.resolve("metadata.properties"),
                metadataBytes(source, fingerprint, cache, channel, manifest),
            )
            directorySync(staging)
            moveAtomically(staging, target, replace = false)
            directorySync(generations)
        }
        require(directory(target))
        validateGeneration(source, target, fingerprint, Int.MAX_VALUE, Int.MAX_VALUE)
        val current = sourceDirectory.resolve("current")
        if (Files.exists(current, NOFOLLOW_LINKS)) require(regularFile(current))
        val pointer = sourceDirectory.resolve(".current-${UUID.randomUUID()}")
        writeNew(pointer, "$fingerprint\n".encodeToByteArray())
        moveAtomically(pointer, current, replace = true)
        directorySync(sourceDirectory)
    }

    private fun sourceDirectory(source: PackSetSource, create: Boolean): Path? {
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            require(directory(root))
        } else if (create) {
            Files.createDirectory(root)
            directorySync(root.toAbsolutePath().parent)
        } else return null
        require(directory(root))
        requireRootEntries()
        val sourceDirectory = root.resolve(source.cacheKey)
        if (Files.exists(sourceDirectory, NOFOLLOW_LINKS)) {
            require(directory(sourceDirectory))
        } else if (create) {
            Files.createDirectory(sourceDirectory)
            directorySync(root)
        } else return null
        require(directory(sourceDirectory))
        return sourceDirectory
    }

    private fun requireRootEntries() {
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                require(FINGERPRINT.matches(entry.fileName.toString()) && directory(entry))
            }
        }
    }

    private fun requireExactEntries(directory: Path, expected: Set<String>) {
        val actual = mutableSetOf<String>()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                require(name in expected && !Files.isSymbolicLink(entry))
                actual += name
            }
        }
        require(actual == expected)
    }

    private fun requireSourceEntries(directory: Path): Boolean {
        val actual = mutableSetOf<String>()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (PRIVATE_CURRENT.matches(name)) {
                    require(!Files.isSymbolicLink(entry) && regularFile(entry))
                    continue
                }
                require(name in setOf("current", "generations") && !Files.isSymbolicLink(entry))
                actual += name
            }
        }
        require("generations" in actual)
        require(actual == setOf("generations") || actual == setOf("current", "generations"))
        return "current" in actual
    }

    private fun createDirectory(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(directory(path))
    }

    private fun directory(path: Path): Boolean =
        !Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW_LINKS)

    private fun regularFile(path: Path): Boolean =
        !Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)

    private fun readFile(path: Path, maximumBytes: Int): ByteArray {
        require(regularFile(path))
        FileChannel.open(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() <= maximumBytes)
            val bytes = ByteArray(channel.size().toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) require(channel.read(buffer) >= 0)
            return bytes
        }
    }

    private fun writeNew(path: Path, bytes: ByteArray) {
        FileChannel.open(path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun moveAtomically(from: Path, to: Path, replace: Boolean) {
        try {
            if (replace) Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(from, to, ATOMIC_MOVE)
        } catch (x: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Cache filesystem does not support atomic moves.", x)
        }
    }

    private fun metadataBytes(
        source: PackSetSource,
        fingerprint: String,
        cache: ResolverCache,
        channel: ByteArray,
        manifest: ByteArray,
    ): ByteArray =
        listOf(
                "sourceKey=${source.cacheKey}",
                "fingerprint=$fingerprint",
                "channelEtag=${encodeNullable(cache.channelEtag)}",
                "manifestEtag=${encodeNullable(cache.manifestEtag)}",
                "channelSha256=${sha256(channel)}",
                "manifestSha256=${sha256(manifest)}",
            )
            .joinToString("\n", postfix = "\n")
            .encodeToByteArray()

    private fun validateGeneration(
        source: PackSetSource,
        generation: Path,
        fingerprint: String,
        maxChannelBytes: Int,
        maxManifestBytes: Int,
    ) {
        requireExactEntries(generation, GENERATION_FILES)
        val channel = readFile(generation.resolve("channel.json"), maxChannelBytes)
        val manifest = readFile(generation.resolve("manifest.json"), maxManifestBytes)
        val metadata = metadata(readFile(generation.resolve("metadata.properties"), 8_192))
        require(metadata["sourceKey"] == source.cacheKey)
        require(metadata["fingerprint"] == fingerprint)
        require(metadata["channelSha256"] == sha256(channel))
        require(metadata["manifestSha256"] == sha256(manifest))
        require(sha256(channel + manifest) == fingerprint)
    }

    private fun validateGenerationEntries(generations: Path) {
        Files.newDirectoryStream(generations).use { entries ->
            for (entry in entries) {
                val fingerprint = entry.fileName.toString()
                if (PRIVATE_STAGING.matches(fingerprint)) {
                    require(!Files.isSymbolicLink(entry) && directory(entry))
                    validateStagingEntries(entry)
                    continue
                }
                require(FINGERPRINT.matches(fingerprint) && directory(entry))
            }
        }
    }

    private fun validateStagingEntries(staging: Path) {
        Files.newDirectoryStream(staging).use { entries ->
            for (entry in entries) {
                require(entry.fileName.toString() in GENERATION_FILES && regularFile(entry))
            }
        }
    }

    private fun metadata(bytes: ByteArray): Map<String, String> {
        val lines = String(bytes, Charsets.US_ASCII).split('\n').filter(String::isNotEmpty)
        require(lines.size == METADATA_KEYS.size)
        val values =
            lines.associate { line ->
                val split = line.indexOf('=')
                require(split > 0)
                line.substring(0, split) to line.substring(split + 1)
            }
        require(values.keys == METADATA_KEYS)
        return values
    }

    private fun encodeNullable(value: String?): String =
        value?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray())
        } ?: "~"

    private fun decodeNullable(value: String): String? =
        if (value == "~") null else String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        fun forceDirectoryDefault(directory: Path) {
            FileChannel.open(directory, setOf(READ, NOFOLLOW_LINKS)).use { it.force(true) }
        }

        val FINGERPRINT = Regex("[0-9a-f]{64}")
        val UUID_SUFFIX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val PRIVATE_CURRENT = Regex("\\.current-$UUID_SUFFIX")
        val PRIVATE_STAGING = Regex("\\.staging-$UUID_SUFFIX")
        val GENERATION_FILES = setOf("channel.json", "manifest.json", "metadata.properties")
        val METADATA_KEYS =
            setOf(
                "sourceKey",
                "fingerprint",
                "channelEtag",
                "manifestEtag",
                "channelSha256",
                "manifestSha256",
            )
    }
}
