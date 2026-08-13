package gg.grounds.resourcepacks.product

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * One Linux directory created and addressed through a retained parent descriptor. The display path
 * is diagnostics-only; all owned reads and publication are relative to retained native handles.
 * Failed directories are deliberately never unlinked: an observable name may have been replaced by
 * another same-UID process and Linux has no compare-and-unlink-by-inode primitive.
 */
internal class SecureOwnedDirectory
private constructor(
    val path: Path,
    private val nativeParent: LinuxDirectoryHandle,
    private val parent: SecureDirectoryStream<Path>,
    private val nativeStage: LinuxDirectoryHandle,
    private var name: Path,
    private val identity: Identity,
) : AutoCloseable {
    val stablePath: Path = nativeStage.anchor
    private var ownedEntries: Map<String, Identity> = emptyMap()

    private fun verifyName(candidate: Path): Boolean =
        attributes(parent, candidate)?.let {
            it.isDirectory && !it.isSymbolicLink && identity.matches(it)
        } == true

    /** Creates one final file with `O_EXCL` and records the identity returned by its held fd. */
    fun writeRegularFile(relativePath: Path, bytes: ByteArray) {
        requireRelativeName(relativePath)
        val entryName = relativePath.toString()
        check(entryName !in ownedEntries) { "Release entry is already written: $entryName" }
        val created = nativeStage.createExclusiveRegularFile(relativePath)
        val captured =
            created.use { file ->
                file.write(bytes)
                val snapshot = file.snapshot()
                if (!snapshot.bytes.contentEquals(bytes)) {
                    throw IOException(
                        "Staged release entry differs from supplied bytes: $entryName"
                    )
                }
                snapshot.identity
            }
        ownedEntries = ownedEntries + (entryName to captured)
    }

    /** Reads a composer output beneath the held scratch fd without following any component. */
    fun readRelativeRegularFile(
        relativePath: Path,
        afterFirstChunk: () -> Unit = {},
    ): SecureFileSnapshot {
        requireSafeRelativePath(relativePath)
        return nativeStage.openRegularFile(relativePath).use { it.snapshot(afterFirstChunk) }
    }

    fun relativeStablePath(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        val root = stablePath.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path is outside the held directory: $path" }
        return root.relativize(normalized).also(::requireSafeRelativePath)
    }

    fun snapshot(expectedNames: Set<String>, afterOpen: () -> Unit = {}): DirectorySnapshot =
        openStageStream().use { opened ->
            val before = readAttributes(opened)
            if (before == null || !before.isDirectory || !identity.matches(before)) {
                throw IOException("Held release directory identity changed.")
            }
            afterOpen()
            val stillOpened = readAttributes(opened)
            if (stillOpened == null || !identity.matches(stillOpened)) {
                throw IOException("Owned release directory identity changed after opening it.")
            }
            val names = opened.names().map(Path::toString)
            val ledger = ownedEntries
            if (
                expectedNames != ledger.keys ||
                    names.toSet() != expectedNames ||
                    names.size != expectedNames.size
            ) {
                throw IOException("Release directory does not contain exactly the expected files.")
            }
            val files =
                names.sorted().associateWith { entryName ->
                    val file = readRegularFile(opened, Path.of(entryName))
                    if (ledger.getValue(entryName) != file.identity) {
                        throw IOException("Owned release entry identity changed: $entryName")
                    }
                    file
                }
            val after = readAttributes(opened)
            if (after == null || !identity.matches(after)) {
                throw IOException("Owned release directory identity changed.")
            }
            DirectorySnapshot(files)
        }

    fun publish(
        outputName: Path,
        expected: DirectorySnapshot,
        hooks: PackSetBuilderHooks,
        rename: SecureRename,
        immediatelyBeforeCommit: () -> Unit,
    ) {
        require(outputName.nameCount == 1) { "Release output must be one parent-relative name." }
        hooks.immediatelyBeforeRename(path)
        if (
            !verifyName(name) ||
                !expected.sameBytesDigestsAndIdentities(snapshot(expected.files.keys))
        ) {
            throw IOException("Owned staging directory changed immediately before publication.")
        }
        hooks.afterPreRenameIdentityVerified(path)
        if (
            !verifyName(name) ||
                !expected.sameBytesDigestsAndIdentities(snapshot(expected.files.keys))
        ) {
            throw IOException("Owned staging directory changed before native publication.")
        }
        immediatelyBeforeCommit()
        if (
            !verifyName(name) ||
                !expected.sameBytesDigestsAndIdentities(snapshot(expected.files.keys))
        ) {
            throw IOException("Owned staging directory changed during final input verification.")
        }
        rename.rename(nativeParent, name, outputName)
        // This successful syscall is the irreversible commit point. Nothing after it may turn the
        // committed publication into a reported transaction failure.
        name = outputName
    }

    fun displayParentStillHeldIdentity(): Boolean = nativeParent.sameDirectoryNoFollow(path.parent)

    override fun close() {
        var failure: Throwable? = null
        try {
            nativeStage.close()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            parent.close()
        } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        try {
            nativeParent.close()
        } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        failure?.let { throw it }
    }

    private fun openStageStream(): SecureDirectoryStream<Path> {
        val raw: DirectoryStream<Path> = Files.newDirectoryStream(nativeStage.anchor)
        return raw as? SecureDirectoryStream<Path>
            ?: run {
                raw.close()
                throw IOException("Secure stage directory handles are required.")
            }
    }

    private fun readRegularFile(
        directory: SecureDirectoryStream<Path>,
        entry: Path,
    ): SecureFileSnapshot {
        val before =
            attributes(directory, entry) ?: throw IOException("Release entry vanished: $entry")
        if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null) {
            throw IOException("Release entry is not a no-follow regular file: $entry")
        }
        val identity = Identity.from(before)
        val bytes =
            directory
                .newByteChannel(entry, setOf<OpenOption>(READ, NOFOLLOW_LINKS))
                .use(::readAllBytes)
        val after =
            attributes(directory, entry)
                ?: throw IOException("Release entry vanished while reading: $entry")
        if (
            !after.isRegularFile || !identity.matches(after) || after.size() != bytes.size.toLong()
        ) {
            throw IOException("Release entry changed while reading: $entry")
        }
        return SecureFileSnapshot(
            bytes,
            ArtifactDigests(bytes.sha1(), bytes.sha256(), bytes.size.toLong()),
            identity,
        )
    }

    companion object {
        fun create(
            parentPath: Path,
            prefix: String,
            afterIdentityCapturedBeforeParentValidation: (Path) -> Unit = {},
            requiredAbsentName: Path? = null,
        ): SecureOwnedDirectory {
            require(parentPath.isAbsolute && parentPath == parentPath.normalize()) {
                "Release output parent must be an absolute normalized path."
            }
            val normalizedParent = parentPath
            val nativeParent = LinuxDirectoryHandle.open(normalizedParent)
            var parent: SecureDirectoryStream<Path>? = null
            var nativeStage: LinuxDirectoryHandle? = null
            var childName: Path? = null
            try {
                val rawParent: DirectoryStream<Path> = Files.newDirectoryStream(nativeParent.anchor)
                parent =
                    rawParent as? SecureDirectoryStream<Path>
                        ?: run {
                            rawParent.close()
                            throw IOException("Secure staging directory handles are required.")
                        }
                if (!nativeParent.sameDirectoryNoFollow(normalizedParent)) {
                    throw IOException("Release output parent identity changed while opening it.")
                }
                if (requiredAbsentName != null) {
                    requireRelativeName(requiredAbsentName)
                    if (attributes(parent, requiredAbsentName) != null) {
                        throw IllegalArgumentException(
                            "Release output must be absent (including an empty directory)."
                        )
                    }
                }
                childName = Path.of("$prefix${UUID.randomUUID()}")
                nativeParent.createDirectory(childName)
                nativeStage = nativeParent.openDirectory(childName)
                val rawStage: DirectoryStream<Path> = Files.newDirectoryStream(nativeStage.anchor)
                val stage =
                    rawStage as? SecureDirectoryStream<Path>
                        ?: run {
                            rawStage.close()
                            throw IOException("Secure stage directory handles are required.")
                        }
                val stageAttrs =
                    stage.use { opened -> readAttributes(opened) }
                        ?: throw IOException("Held staging directory vanished.")
                val identity = Identity.from(stageAttrs)
                val displayPath = normalizedParent.resolve(childName)
                afterIdentityCapturedBeforeParentValidation(displayPath)
                val parentAttrs =
                    attributes(parent, childName)
                        ?: throw IOException("Owned staging directory vanished.")
                if (
                    !parentAttrs.isDirectory ||
                        parentAttrs.isSymbolicLink ||
                        parentAttrs.fileKey() == null ||
                        !stageAttrs.isDirectory ||
                        !identity.matches(parentAttrs)
                ) {
                    throw IOException("Owned staging directory is unsafe.")
                }
                return SecureOwnedDirectory(
                    displayPath,
                    nativeParent,
                    parent,
                    nativeStage,
                    childName,
                    identity,
                )
            } catch (failure: Throwable) {
                try {
                    nativeStage?.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
                // Never unlink a failed initialization name. A same-UID process may have replaced
                // it between creation and this catch block; the unique directory is a safe leak.
                try {
                    parent?.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
                try {
                    nativeParent.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
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
            } catch (_: NoSuchFileException) {
                null
            }

        private fun readAttributes(directory: SecureDirectoryStream<Path>): BasicFileAttributes? =
            try {
                directory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
            } catch (_: NoSuchFileException) {
                null
            }

        private fun SecureDirectoryStream<Path>.names(): List<Path> =
            mapNotNull(Path::getFileName).toList()

        private fun readAllBytes(channel: SeekableByteChannel): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
            return output.toByteArray()
        }

        private fun requireRelativeName(name: Path) {
            requireSafeRelativePath(name)
            require(name.nameCount == 1) {
                "Release entries must be one safe directory-relative name."
            }
        }
    }
}

internal class DirectorySnapshot(files: Map<String, SecureFileSnapshot>) {
    val files: Map<String, SecureFileSnapshot> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(files))

    fun sameBytesDigestsAndIdentities(other: DirectorySnapshot): Boolean =
        files.keys == other.files.keys &&
            files.all { (name, file) ->
                val compared = other.files.getValue(name)
                file.identity == compared.identity &&
                    file.digests == compared.digests &&
                    file.contentEquals(compared)
            }
}

internal class SecureFileSnapshot(
    bytes: ByteArray,
    val digests: ArtifactDigests,
    val identity: Identity,
) {
    private val storedBytes: ByteArray = bytes.copyOf()
    val bytes: ByteArray
        get() = storedBytes.copyOf()

    fun contentEquals(other: SecureFileSnapshot): Boolean =
        storedBytes.contentEquals(other.storedBytes)
}

internal data class Identity(
    val fileKey: Any?,
    val creationTime: java.nio.file.attribute.FileTime,
) {
    fun matches(attributes: BasicFileAttributes): Boolean =
        fileKey != null &&
            fileKey == attributes.fileKey() &&
            creationTime == attributes.creationTime()

    companion object {
        fun from(attributes: BasicFileAttributes): Identity =
            Identity(attributes.fileKey(), attributes.creationTime())
    }
}

internal fun interface SecureRename {
    fun rename(parent: LinuxDirectoryHandle, from: Path, to: Path)
}

/** Linux parent descriptor used for mkdirat/renameat2 and a stable /proc/self/fd anchor. */
internal class LinuxDirectoryHandle private constructor(private val descriptor: Int) :
    AutoCloseable {
    val anchor: Path = Path.of("/proc/self/fd/$descriptor")
    private var closed = false

    fun createDirectory(name: Path) {
        requireRelativeName(name)
        invokePath("mkdirat", "mkdirat", descriptor, name, 0x1c0)
    }

    fun openDirectory(name: Path): LinuxDirectoryHandle {
        requireRelativeName(name)
        try {
            Arena.ofConfined().use { arena ->
                val childDescriptor =
                    handle(
                            "openat",
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                            ),
                            false,
                        )
                        .invoke(
                            descriptor,
                            arena.allocateFrom(name.toString()),
                            O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC,
                        ) as Int
                if (childDescriptor < 0) {
                    throw IOException("Owned staging directory cannot be opened securely.")
                }
                return LinuxDirectoryHandle(childDescriptor)
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Secure staging directory handle is unavailable.", failure)
        }
    }

    fun openRegularFile(path: Path): LinuxRegularFileHandle {
        requireSafeRelativePath(path)
        var current: LinuxDirectoryHandle = this
        val openedDirectories = mutableListOf<LinuxDirectoryHandle>()
        try {
            for (index in 0 until path.nameCount - 1) {
                val next = current.openDirectory(path.getName(index))
                openedDirectories += next
                current = next
            }
            return current.openRegularName(path.fileName)
        } finally {
            openedDirectories.asReversed().forEach { directory ->
                try {
                    directory.close()
                } catch (_: Throwable) {
                    // The returned regular descriptor is independent of traversal descriptors.
                }
            }
        }
    }

    fun createExclusiveRegularFile(name: Path): LinuxRegularFileHandle {
        requireRelativeName(name)
        return openRegularName(name, O_WRONLY or O_CREAT or O_EXCL, 0x180)
    }

    fun renameNoReplace(from: Path, to: Path) {
        requireRelativeName(from)
        requireRelativeName(to)
        val arena = Arena.ofConfined()
        var nativeFailure: Throwable? = null
        try {
            val errno = arena.allocate(errnoLayout())
            val result =
                handle(
                        "renameat2",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                        ),
                        true,
                    )
                    .invoke(
                        errno,
                        descriptor,
                        arena.allocateFrom(from.toString()),
                        descriptor,
                        arena.allocateFrom(to.toString()),
                        1,
                    ) as Int
            if (result != 0) {
                val error = errno(errno)
                if (error == 17) throw IOException("Release output already exists.")
                throw IOException("Atomic no-replace directory publication failed (errno $error).")
            }
        } catch (caught: IOException) {
            nativeFailure = caught
            throw caught
        } catch (caught: Throwable) {
            val wrapped =
                IOException("Atomic no-replace directory publication unavailable.", caught)
            nativeFailure = wrapped
            throw wrapped
        } finally {
            try {
                arena.close()
            } catch (close: Throwable) {
                // A close error cannot roll back a successful rename. Before commit it is retained
                // as diagnostic context on the primary native failure.
                nativeFailure?.addSuppressed(close)
            }
        }
    }

    fun sameDirectoryNoFollow(path: Path): Boolean =
        try {
            open(path).use { candidate -> Files.isSameFile(anchor, candidate.anchor) }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val result =
                handle(
                        "close",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                        false,
                    )
                    .invoke(descriptor) as Int
            if (result != 0) throw IOException("Cannot close release parent directory handle.")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Cannot close release parent directory handle.", failure)
        }
    }

    companion object {
        private const val O_RDONLY = 0
        private const val O_WRONLY = 1
        private const val O_CREAT = 0x40
        private const val O_EXCL = 0x80
        private const val O_DIRECTORY = 0x10000
        private const val O_NOFOLLOW = 0x20000
        private const val O_CLOEXEC = 0x80000

        fun open(path: Path): LinuxDirectoryHandle {
            if (System.getProperty("os.name").lowercase() != "linux") {
                throw IOException("Secure release directory handles are supported only on Linux.")
            }
            require(path.isAbsolute && path == path.normalize()) {
                "Secure directory paths must be absolute and normalized."
            }
            var current = openAbsoluteRoot()
            try {
                path.forEach { component ->
                    val next = current.openDirectory(component)
                    current.close()
                    current = next
                }
                return current
            } catch (failure: Throwable) {
                try {
                    current.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
                if (failure is IOException) throw failure
                throw IOException("Secure release directory handles are unavailable.", failure)
            }
        }

        fun openRegularFile(path: Path): LinuxRegularFileHandle {
            require(path.isAbsolute && path == path.normalize() && path.parent != null) {
                "Secure source paths must be absolute and normalized."
            }
            return open(path.parent).use { parent -> parent.openRegularFile(path.fileName) }
        }

        private fun openAbsoluteRoot(): LinuxDirectoryHandle {
            try {
                Arena.ofConfined().use { arena ->
                    val fd =
                        handle(
                                "open",
                                FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                ),
                                false,
                            )
                            .invoke(
                                arena.allocateFrom("/"),
                                O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC,
                            ) as Int
                    if (fd < 0) throw IOException("Filesystem root cannot be opened securely.")
                    return LinuxDirectoryHandle(fd)
                }
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Throwable) {
                throw IOException("Secure root directory handle is unavailable.", failure)
            }
        }

        private fun LinuxDirectoryHandle.openRegularName(
            name: Path,
            flags: Int = O_RDONLY,
            mode: Int? = null,
        ): LinuxRegularFileHandle {
            requireRelativeName(name)
            try {
                Arena.ofConfined().use { arena ->
                    val descriptor =
                        if (mode == null) {
                            handle(
                                    "openat",
                                    FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                    ),
                                    false,
                                )
                                .invoke(
                                    this.descriptor,
                                    arena.allocateFrom(name.toString()),
                                    flags or O_NOFOLLOW or O_CLOEXEC,
                                ) as Int
                        } else {
                            handle(
                                    "openat",
                                    FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                    ),
                                    false,
                                )
                                .invoke(
                                    this.descriptor,
                                    arena.allocateFrom(name.toString()),
                                    flags or O_NOFOLLOW or O_CLOEXEC,
                                    mode,
                                ) as Int
                        }
                    if (descriptor < 0) {
                        throw IOException("Regular file cannot be opened securely: $name")
                    }
                    return LinuxRegularFileHandle(descriptor)
                }
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Throwable) {
                throw IOException("Secure regular file handle is unavailable: $name", failure)
            }
        }

        private fun invokePath(
            operation: String,
            symbol: String,
            descriptor: Int,
            name: Path,
            mode: Int,
        ) {
            try {
                Arena.ofConfined().use { arena ->
                    val errno = arena.allocate(errnoLayout())
                    val result =
                        handle(
                                symbol,
                                FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                ),
                                true,
                            )
                            .invoke(errno, descriptor, arena.allocateFrom(name.toString()), mode)
                            as Int
                    if (result != 0) {
                        throw IOException("$operation failed (errno ${errno(errno)}).")
                    }
                }
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Throwable) {
                throw IOException("$operation is unavailable.", failure)
            }
        }

        private fun handle(
            symbol: String,
            descriptor: FunctionDescriptor,
            captureErrno: Boolean,
        ): java.lang.invoke.MethodHandle {
            val linker = Linker.nativeLinker()
            val address = linker.defaultLookup().find(symbol).orElseThrow()
            return if (captureErrno) {
                linker.downcallHandle(address, descriptor, Linker.Option.captureCallState("errno"))
            } else {
                linker.downcallHandle(address, descriptor)
            }
        }

        private fun errnoLayout(): MemoryLayout = Linker.Option.captureStateLayout()

        private fun errno(state: java.lang.foreign.MemorySegment): Int =
            state.get(
                ValueLayout.JAVA_INT,
                errnoLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno")),
            )

        private fun requireRelativeName(name: Path) {
            require(
                !name.isAbsolute &&
                    name.nameCount == 1 &&
                    name.toString() != "." &&
                    name.toString() != ".."
            ) {
                "Native release operations require one safe parent-relative name."
            }
        }
    }
}

internal class LinuxRegularFileHandle internal constructor(private val descriptor: Int) :
    AutoCloseable {
    val anchor: Path = Path.of("/proc/self/fd/$descriptor")
    private var closed = false

    fun snapshot(afterFirstChunk: () -> Unit = {}): SecureFileSnapshot {
        val before = Files.readAttributes(anchor, BasicFileAttributes::class.java)
        if (!before.isRegularFile || before.fileKey() == null) {
            throw IOException("Held source is not a stable regular file.")
        }
        val bytes =
            Files.newInputStream(anchor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var invoked = false
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (!invoked) {
                        invoked = true
                        try {
                            afterFirstChunk()
                        } catch (_: Throwable) {
                            // A test/diagnostic seam cannot replace source verification.
                        }
                    }
                }
                output.toByteArray()
            }
        val after = Files.readAttributes(anchor, BasicFileAttributes::class.java)
        val identity = Identity.from(before)
        if (
            !after.isRegularFile ||
                !identity.matches(after) ||
                before.lastModifiedTime() != after.lastModifiedTime() ||
                before.size() != after.size() ||
                after.size() != bytes.size.toLong()
        ) {
            throw IOException("Held regular file changed while reading.")
        }
        return SecureFileSnapshot(
            bytes,
            ArtifactDigests(bytes.sha1(), bytes.sha256(), bytes.size.toLong()),
            identity,
        )
    }

    fun write(bytes: ByteArray) {
        Files.newOutputStream(anchor, java.nio.file.StandardOpenOption.WRITE).use {
            it.write(bytes)
        }
    }

    fun sameFile(other: LinuxRegularFileHandle): Boolean = Files.isSameFile(anchor, other.anchor)

    override fun close() {
        if (closed) return
        closed = true
        try {
            val result =
                Linker.nativeLinker()
                    .downcallHandle(
                        Linker.nativeLinker().defaultLookup().find("close").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    )
                    .invoke(descriptor) as Int
            if (result != 0) throw IOException("Cannot close regular file handle.")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Cannot close regular file handle.", failure)
        }
    }
}

private fun requireSafeRelativePath(path: Path) {
    require(
        !path.isAbsolute &&
            path.nameCount > 0 &&
            path == path.normalize() &&
            path.none { it.toString() == "." || it.toString() == ".." }
    ) {
        "Native operations require a safe directory-relative path."
    }
}

private fun ByteArray.sha1(): String = digest("SHA-1")

private fun ByteArray.sha256(): String = digest("SHA-256")

private fun ByteArray.digest(algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }
