package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.builder.PackArtifact
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Final-byte digests reported by [gg.grounds.resourcepack.builder.ZipPackWriter]. */
internal data class ArtifactDigests(val sha1: String, val sha256: String, val size: Long) {
    companion object {
        fun from(artifact: PackArtifact): ArtifactDigests =
            ArtifactDigests(artifact.sha1, artifact.sha256, artifact.size)

        @Throws(IOException::class)
        fun readRegularFile(path: Path): ArtifactDigests {
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!before.isRegularFile) throw IOException("Artifact is not a regular file: $path")
            val sha1 = MessageDigest.getInstance("SHA-1")
            val sha256 = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sha1.update(buffer, 0, read)
                    sha256.update(buffer, 0, read)
                    size = Math.addExact(size, read.toLong())
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
