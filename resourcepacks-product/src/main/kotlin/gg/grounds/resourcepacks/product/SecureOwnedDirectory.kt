package gg.grounds.resourcepacks.product

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** A creation-time identity plus a held secure parent handle; never recaptured during cleanup. */
internal class SecureOwnedDirectory
private constructor(
    val path: Path,
    private val parent: SecureDirectoryStream<Path>,
    private val name: Path,
    private val identity: Identity,
) : AutoCloseable {
    fun verify(): Boolean =
        attributes(parent, name)?.let {
            it.isDirectory && !it.isSymbolicLink && identity.matches(it)
        } == true

    fun entries(): List<Path> {
        if (!verify()) throw IOException("Owned staging directory identity changed.")
        return parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
            child.mapNotNull(Path::getFileName).toList()
        }
    }

    fun deleteOwned() {
        if (!verify()) return
        delete(parent, name, identity)
    }

    override fun close() = parent.close()

    private fun delete(directory: SecureDirectoryStream<Path>, entry: Path, expected: Identity?) {
        val attrs = attributes(directory, entry) ?: return
        if (expected != null && (!attrs.isDirectory || !expected.matches(attrs))) return
        if (!attrs.isDirectory || attrs.isSymbolicLink) {
            try {
                directory.deleteFile(entry)
            } catch (_: Throwable) {}
            return
        }
        val actual = Identity.from(attrs)
        val child =
            try {
                directory.newDirectoryStream(entry, NOFOLLOW_LINKS)
            } catch (_: Throwable) {
                return
            }
        child.use { opened ->
            opened.mapNotNull(Path::getFileName).toList().forEach { delete(opened, it, null) }
        }
        val before = attributes(directory, entry) ?: return
        if (!before.isDirectory || !actual.matches(before)) return
        try {
            directory.deleteDirectory(entry)
        } catch (_: Throwable) {}
    }

    private data class Identity(
        val fileKey: Any?,
        val creationTime: java.nio.file.attribute.FileTime,
    ) {
        fun matches(attrs: BasicFileAttributes) =
            fileKey != null && fileKey == attrs.fileKey() && creationTime == attrs.creationTime()

        companion object {
            fun from(attrs: BasicFileAttributes) = Identity(attrs.fileKey(), attrs.creationTime())
        }
    }

    companion object {
        fun create(parentPath: Path, prefix: String): SecureOwnedDirectory {
            require(
                Files.isDirectory(parentPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parentPath)
            ) {
                "Release output parent must be a real directory."
            }
            val rawParent: DirectoryStream<Path> = Files.newDirectoryStream(parentPath)
            val parent =
                rawParent as? SecureDirectoryStream<Path>
                    ?: run {
                        rawParent.close()
                        throw IOException("Secure staging directory handles are required.")
                    }
            val child =
                try {
                    Files.createTempDirectory(parentPath, prefix)
                } catch (failure: Throwable) {
                    parent.close()
                    throw failure
                }
            try {
                val attrs =
                    attributes(parent, child.fileName)
                        ?: throw IOException("Owned staging directory vanished.")
                if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.fileKey() == null)
                    throw IOException("Owned staging directory is unsafe.")
                return SecureOwnedDirectory(child, parent, child.fileName, Identity.from(attrs))
            } catch (failure: Throwable) {
                try {
                    parent.close()
                } catch (_: Throwable) {}
                throw failure
            }
        }

        private fun attributes(
            parent: SecureDirectoryStream<Path>,
            name: Path,
        ): BasicFileAttributes? =
            try {
                parent
                    .getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                    ?.readAttributes()
            } catch (_: Throwable) {
                null
            }
    }
}
