package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.builder.PackArtifact
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** SHA-1, SHA-256, and size measured from exact artifact bytes. */
internal data class ArtifactDigests(val sha1: String, val sha256: String, val size: Long) {
    companion object {
        fun from(artifact: PackArtifact): ArtifactDigests =
            ArtifactDigests(artifact.sha1, artifact.sha256, artifact.size)

        fun fromBytes(bytes: ByteArray): ArtifactDigests {
            val sha1 = MessageDigest.getInstance("SHA-1").apply { update(bytes) }
            val sha256 = MessageDigest.getInstance("SHA-256").apply { update(bytes) }
            return ArtifactDigests(sha1.hex(), sha256.hex(), bytes.size.toLong())
        }

        @Throws(IOException::class)
        fun readRegularFile(path: Path): ArtifactDigests = readRegularFile(path, {})

        @JvmSynthetic
        internal fun readRegularFile(path: Path, afterFirstChunk: () -> Unit): ArtifactDigests {
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!before.isRegularFile) throw IOException("Artifact is not a regular file: $path")
            val sha1 = MessageDigest.getInstance("SHA-1")
            val sha256 = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var invoked = false
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sha1.update(buffer, 0, read)
                    sha256.update(buffer, 0, read)
                    size = Math.addExact(size, read.toLong())
                    if (!invoked) {
                        invoked = true
                        try {
                            afterFirstChunk()
                        } catch (_: Throwable) {
                            /* validation stays contained */
                        }
                    }
                }
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (
                !after.isRegularFile ||
                    before.fileKey() != after.fileKey() ||
                    before.size() != after.size()
            )
                throw IOException("Artifact changed while hashing: $path")
            return ArtifactDigests(sha1.hex(), sha256.hex(), size)
        }

        private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
    }
}
