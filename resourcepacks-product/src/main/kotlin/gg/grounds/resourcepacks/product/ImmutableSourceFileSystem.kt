package gg.grounds.resourcepacks.product

import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardOpenOption
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Minimal immutable byte-backed filesystem used only while library-gui materializes a theme. */
internal class ImmutableSourceFileSystem
private constructor(sourceFiles: Map<String, ByteArray>, maxFileBytes: Long) : FileSystem() {
    internal val files: Map<String, ByteArray> =
        Collections.unmodifiableMap(
            sourceFiles.entries.associate { (path, bytes) ->
                require(bytes.size.toLong() <= maxFileBytes) {
                    "Immutable source exceeds its byte limit: $path"
                }
                normalize("/$path") to bytes.copyOf()
            }
        )
    internal val directories: Set<String> = buildSet {
        add("/")
        files.keys.forEach { file ->
            var parent = file.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
    }
    private val immutableProvider = ImmutableSourceProvider(this)
    private var open = true

    val root: Path = ImmutableSourcePath(this, "/")

    override fun provider(): FileSystemProvider = immutableProvider

    override fun close() {
        open = false
    }

    override fun isOpen(): Boolean = open

    override fun isReadOnly(): Boolean = true

    override fun getSeparator(): String = "/"

    override fun getRootDirectories(): Iterable<Path> = listOf(root)

    override fun getFileStores(): Iterable<FileStore> = emptyList()

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")

    override fun getPath(first: String, vararg more: String): Path =
        ImmutableSourcePath(this, normalize((listOf(first) + more).joinToString("/")))

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        throw UnsupportedOperationException()

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        throw UnsupportedOperationException()

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    internal fun requireOpen() {
        if (!open) throw java.nio.file.ClosedFileSystemException()
    }

    companion object {
        fun open(files: Map<String, ByteArray>, maxFileBytes: Long): ImmutableSourceFileSystem {
            require(maxFileBytes in 0..Int.MAX_VALUE.toLong()) {
                "Immutable source byte limit must fit in a byte array."
            }
            return ImmutableSourceFileSystem(files, maxFileBytes)
        }

        internal fun normalize(raw: String): String {
            val names = raw.split('/').filter(String::isNotEmpty)
            require(names.none { it == "." || it == ".." }) {
                "Immutable source paths must not contain dot components."
            }
            return if (names.isEmpty()) "/" else "/${names.joinToString("/")}"
        }
    }
}

private class ImmutableSourceProvider(private val fileSystem: ImmutableSourceFileSystem) :
    FileSystemProvider() {
    override fun getScheme(): String = "grounds-immutable"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw FileSystemAlreadyExistsException()

    override fun getFileSystem(uri: URI): FileSystem =
        if (uri.scheme == scheme) fileSystem else throw FileSystemNotFoundException()

    override fun getPath(uri: URI): Path = fileSystem.getPath(uri.path)

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val source = source(path)
        if (options.any { it != StandardOpenOption.READ }) throw ReadOnlyFileSystemException()
        return ImmutableByteChannel(source)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>,
    ): DirectoryStream<Path> = throw UnsupportedOperationException()

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = readOnly()

    override fun delete(path: Path) = readOnly()

    override fun copy(source: Path, target: Path, vararg options: CopyOption) = readOnly()

    override fun move(source: Path, target: Path, vararg options: CopyOption) = readOnly()

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2

    override fun isHidden(path: Path): Boolean = false

    override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        val value = checked(path)
        if (value !in fileSystem.files && value !in fileSystem.directories) {
            throw NoSuchFileException(value)
        }
        if (modes.any { it != AccessMode.READ }) throw ReadOnlyFileSystemException()
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? = null

    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption,
    ): A {
        require(type == BasicFileAttributes::class.java) { "Only basic attributes are supported." }
        @Suppress("UNCHECKED_CAST")
        return attributes(path) as A
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): Map<String, Any> {
        val value = attributes(path)
        return mapOf(
            "isRegularFile" to value.isRegularFile,
            "isDirectory" to value.isDirectory,
            "isSymbolicLink" to false,
            "isOther" to false,
            "size" to value.size(),
            "fileKey" to value.fileKey(),
        )
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption,
    ) = readOnly()

    private fun attributes(path: Path): BasicFileAttributes {
        val value = checked(path)
        val bytes = fileSystem.files[value]
        if (bytes == null && value !in fileSystem.directories) throw NoSuchFileException(value)
        return ImmutableBasicAttributes(value, bytes?.size?.toLong())
    }

    private fun source(path: Path): ByteArray {
        val value = checked(path)
        return fileSystem.files[value]?.copyOf() ?: throw NoSuchFileException(value)
    }

    private fun checked(path: Path): String {
        fileSystem.requireOpen()
        require(path is ImmutableSourcePath && path.fileSystem === fileSystem) {
            "Path belongs to a different filesystem."
        }
        return path.value
    }

    private fun readOnly(): Nothing = throw ReadOnlyFileSystemException()
}

private class ImmutableSourcePath(val fileSystem: ImmutableSourceFileSystem, val value: String) :
    Path {
    private val names: List<String> = value.split('/').filter(String::isNotEmpty)

    override fun getFileSystem(): FileSystem = fileSystem

    override fun isAbsolute(): Boolean = true

    override fun getRoot(): Path = if (value == "/") this else ImmutableSourcePath(fileSystem, "/")

    override fun getFileName(): Path? =
        names.lastOrNull()?.let { ImmutableSourcePath(fileSystem, it) }

    override fun getParent(): Path? =
        when {
            value == "/" -> null
            names.size == 1 -> root
            else -> ImmutableSourcePath(fileSystem, "/${names.dropLast(1).joinToString("/")}")
        }

    override fun getNameCount(): Int = names.size

    override fun getName(index: Int): Path = ImmutableSourcePath(fileSystem, names[index])

    override fun subpath(beginIndex: Int, endIndex: Int): Path =
        ImmutableSourcePath(fileSystem, names.subList(beginIndex, endIndex).joinToString("/"))

    override fun startsWith(other: Path): Boolean =
        other is ImmutableSourcePath &&
            other.fileSystem === fileSystem &&
            value.startsWith(other.value)

    override fun startsWith(other: String): Boolean = startsWith(fileSystem.getPath(other))

    override fun endsWith(other: Path): Boolean =
        other is ImmutableSourcePath &&
            other.fileSystem === fileSystem &&
            (value == other.value || value.endsWith("/${other.value.removePrefix("/")}"))

    override fun endsWith(other: String): Boolean = endsWith(fileSystem.getPath(other))

    override fun normalize(): Path = this

    override fun resolve(other: Path): Path {
        require(other is ImmutableSourcePath && other.fileSystem === fileSystem)
        return if (other.isAbsolute) other else resolve(other.toString())
    }

    override fun resolve(other: String): Path =
        if (other.startsWith('/')) fileSystem.getPath(other)
        else fileSystem.getPath(if (value == "/") "/$other" else "$value/$other")

    override fun resolveSibling(other: Path): Path = parent?.resolve(other) ?: other

    override fun resolveSibling(other: String): Path =
        parent?.resolve(other) ?: fileSystem.getPath(other)

    override fun relativize(other: Path): Path {
        require(other is ImmutableSourcePath && other.fileSystem === fileSystem)
        require(other.value.startsWith(if (value == "/") "/" else "$value/"))
        return ImmutableSourcePath(fileSystem, other.value.removePrefix(value).removePrefix("/"))
    }

    override fun toUri(): URI = URI.create("grounds-immutable:$value")

    override fun toAbsolutePath(): Path = this

    override fun toRealPath(vararg options: LinkOption): Path = this

    override fun toFile(): java.io.File = throw UnsupportedOperationException()

    override fun register(
        watcher: WatchService,
        events: Array<out WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier,
    ): WatchKey = throw UnsupportedOperationException()

    override fun iterator(): MutableIterator<Path> =
        names.map { ImmutableSourcePath(fileSystem, it) as Path }.toMutableList().iterator()

    override fun compareTo(other: Path): Int = value.compareTo(other.toString())

    override fun equals(other: Any?): Boolean =
        other is ImmutableSourcePath && other.fileSystem === fileSystem && other.value == value

    override fun hashCode(): Int = 31 * System.identityHashCode(fileSystem) + value.hashCode()

    override fun toString(): String = value
}

private class ImmutableBasicAttributes(private val key: String, private val fileSize: Long?) :
    BasicFileAttributes {
    private val time = FileTime.from(0, TimeUnit.MILLISECONDS)

    override fun lastModifiedTime(): FileTime = time

    override fun lastAccessTime(): FileTime = time

    override fun creationTime(): FileTime = time

    override fun isRegularFile(): Boolean = fileSize != null

    override fun isDirectory(): Boolean = fileSize == null

    override fun isSymbolicLink(): Boolean = false

    override fun isOther(): Boolean = false

    override fun size(): Long = fileSize ?: 0L

    override fun fileKey(): Any = key
}

private class ImmutableByteChannel(source: ByteArray) : SeekableByteChannel {
    private val bytes = source.copyOf()
    private var cursor = 0
    private var open = true

    override fun read(destination: ByteBuffer): Int {
        requireOpen()
        if (cursor == bytes.size) return -1
        val count = minOf(destination.remaining(), bytes.size - cursor)
        destination.put(bytes, cursor, count)
        cursor += count
        return count
    }

    override fun write(source: ByteBuffer): Int = throw ReadOnlyFileSystemException()

    override fun position(): Long = cursor.toLong()

    override fun position(newPosition: Long): SeekableByteChannel {
        requireOpen()
        require(newPosition in 0..bytes.size.toLong())
        cursor = newPosition.toInt()
        return this
    }

    override fun size(): Long = bytes.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel = throw ReadOnlyFileSystemException()

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    private fun requireOpen() {
        if (!open) throw java.nio.channels.ClosedChannelException()
    }
}
