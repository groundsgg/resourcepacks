package gg.grounds.resourcepacks.product

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
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

    /** Creates one small final file with `O_EXCL` after a binding allocation limit was checked. */
    fun writeRegularFile(relativePath: Path, bytes: ByteArray, maxBytes: Long) {
        require(bytes.size.toLong() <= maxBytes) { "Release entry exceeds its byte limit." }
        writeRegularFile(relativePath, maxBytes) { output -> output.write(bytes) }
    }

    /**
     * Streams one file into this held directory and records the exact resulting identity/digest.
     */
    fun writeRegularFile(
        relativePath: Path,
        maxBytes: Long,
        limitFailure: () -> Throwable = { sizeLimitFailure(maxBytes) },
        writer: (OutputStream) -> Unit,
    ): SecureFileSnapshot {
        requireRelativeName(relativePath)
        val entryName = relativePath.toString()
        check(entryName !in ownedEntries) { "Release entry is already written: $entryName" }
        val created = nativeStage.createExclusiveRegularFile(relativePath)
        val snapshot =
            created.use { file ->
                file.outputStream().use { output ->
                    writer(LimitedOutputStream(output, maxBytes, limitFailure))
                }
                file.snapshot(maxBytes, limitFailure = limitFailure)
            }
        ownedEntries = ownedEntries + (entryName to snapshot.identity)
        return snapshot
    }

    /** Copies exact held bytes into a new file without materializing either artifact in memory. */
    fun copyRegularFile(
        relativePath: Path,
        source: LinuxRegularFileHandle,
        expected: ArtifactDigests,
        maxBytes: Long,
    ): SecureFileSnapshot {
        if (expected.size > maxBytes) throw sizeLimitFailure(maxBytes)
        val snapshot =
            writeRegularFile(relativePath, maxBytes) { output -> source.copyTo(output, maxBytes) }
        if (snapshot.digests != expected) {
            throw IOException("Copied release entry differs from held source: $relativePath")
        }
        return snapshot
    }

    /** Reads a composer output beneath the held scratch fd without following any component. */
    fun readRelativeRegularFile(
        relativePath: Path,
        maxBytes: Long,
        afterFirstChunk: () -> Unit = {},
    ): SecureFileSnapshot {
        requireSafeRelativePath(relativePath)
        return nativeStage.openRegularFile(relativePath).use {
            it.snapshot(maxBytes, afterFirstChunk)
        }
    }

    fun readRelativeRegularFileBytes(relativePath: Path, maxBytes: Long): ByteArray {
        requireSafeRelativePath(relativePath)
        return nativeStage.openRegularFile(relativePath).use { it.readBytes(maxBytes) }
    }

    fun openRelativeRegularFile(relativePath: Path): LinuxRegularFileHandle {
        requireSafeRelativePath(relativePath)
        val entryName = relativePath.toString()
        val expected =
            ownedEntries[entryName] ?: throw IOException("Release entry is not owned: $entryName")
        return nativeStage.openRegularFile(relativePath).also { opened ->
            if (opened.identity() != expected) {
                opened.close()
                throw IOException("Owned release entry identity changed: $entryName")
            }
        }
    }

    fun relativeStablePath(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        val root = stablePath.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path is outside the held directory: $path" }
        return root.relativize(normalized).also(::requireSafeRelativePath)
    }

    fun snapshot(
        expectedFiles: Map<String, ArtifactDigests>,
        afterOpen: () -> Unit = {},
    ): DirectorySnapshot =
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
                expectedFiles.keys != ledger.keys ||
                    names.toSet() != expectedFiles.keys ||
                    names.size != expectedFiles.size
            ) {
                throw IOException("Release directory does not contain exactly the expected files.")
            }
            val files =
                names.sorted().associateWith { entryName ->
                    val expected = expectedFiles.getValue(entryName)
                    val entry = Path.of(entryName)
                    val before =
                        attributes(opened, entry)
                            ?: throw IOException("Release entry vanished: $entry")
                    if (
                        !before.isRegularFile || before.isSymbolicLink || before.fileKey() == null
                    ) {
                        throw IOException("Release entry is not a no-follow regular file: $entry")
                    }
                    val file = nativeStage.openRegularFile(entry).use { it.snapshot(expected.size) }
                    if (ledger.getValue(entryName) != file.identity) {
                        throw IOException("Owned release entry identity changed: $entryName")
                    }
                    if (expected != file.digests) {
                        throw IOException("Release entry bytes changed: $entryName")
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
                !expected.sameDigestsAndIdentities(snapshot(expected.digestsByName()))
        ) {
            throw IOException("Owned staging directory changed immediately before publication.")
        }
        hooks.afterPreRenameIdentityVerified(path)
        if (
            !verifyName(name) ||
                !expected.sameDigestsAndIdentities(snapshot(expected.digestsByName()))
        ) {
            throw IOException("Owned staging directory changed before native publication.")
        }
        immediatelyBeforeCommit()
        if (
            !verifyName(name) ||
                !expected.sameDigestsAndIdentities(snapshot(expected.digestsByName()))
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

    fun sameDigestsAndIdentities(other: DirectorySnapshot): Boolean =
        files.keys == other.files.keys &&
            files.all { (name, file) ->
                val compared = other.files.getValue(name)
                file.identity == compared.identity && file.digests == compared.digests
            }

    fun digestsByName(): Map<String, ArtifactDigests> = files.mapValues { it.value.digests }
}

internal data class SecureFileSnapshot(
    val digests: ArtifactDigests,
    val identity: Identity,
    val lastModifiedTime: java.nio.file.attribute.FileTime,
)

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

/** Held advisory lease for callers that honor the trusted-single-writer builder protocol. */
internal class TrustedSingleWriterLease
private constructor(private val parent: LinuxDirectoryHandle) : AutoCloseable {
    override fun close() = parent.close()

    companion object {
        fun acquire(parentPath: Path): TrustedSingleWriterLease {
            val parent = LinuxDirectoryHandle.open(parentPath)
            try {
                parent.acquireCooperativeBuilderLock()
                return TrustedSingleWriterLease(parent)
            } catch (failure: Throwable) {
                try {
                    parent.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
                throw failure
            }
        }
    }
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

    /**
     * Takes an advisory lock on this held parent fd. This serializes cooperative builders only; an
     * uncooperative same-UID process can ignore flock and is excluded by the trusted-single- writer
     * release namespace precondition.
     */
    fun acquireCooperativeBuilderLock() {
        try {
            Arena.ofConfined().use { arena ->
                val errno = arena.allocate(errnoLayout())
                val result =
                    handle(
                            "flock",
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                            ),
                            true,
                        )
                        .invoke(errno, descriptor, LOCK_EX or LOCK_NB) as Int
                if (result != 0) {
                    throw IOException(
                        "Another cooperative PackSet builder holds the release parent lock " +
                            "(errno ${errno(errno)})."
                    )
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Cooperative PackSet builder locking is unavailable.", failure)
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
        private const val LOCK_EX = 2
        private const val LOCK_NB = 4

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

    fun identity(): Identity {
        val attributes = Files.readAttributes(anchor, BasicFileAttributes::class.java)
        if (!attributes.isRegularFile || attributes.fileKey() == null) {
            throw IOException("Held source is not a stable regular file.")
        }
        return Identity.from(attributes)
    }

    fun snapshot(
        maxBytes: Long = Long.MAX_VALUE,
        afterFirstChunk: () -> Unit = {},
        limitFailure: () -> Throwable = { sizeLimitFailure(maxBytes) },
    ): SecureFileSnapshot {
        require(maxBytes >= 0L) { "Secure streaming limit must not be negative." }
        val before = Files.readAttributes(anchor, BasicFileAttributes::class.java)
        if (!before.isRegularFile || before.fileKey() == null) {
            throw IOException("Held source is not a stable regular file.")
        }
        if (before.size() > maxBytes) throw limitFailure()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(anchor).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var invoked = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size = Math.addExact(size, read.toLong())
                if (size > maxBytes) throw limitFailure()
                sha1.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
                if (!invoked) {
                    invoked = true
                    try {
                        afterFirstChunk()
                    } catch (_: Throwable) {
                        // A test/diagnostic seam cannot replace source verification.
                    }
                }
            }
        }
        val after = Files.readAttributes(anchor, BasicFileAttributes::class.java)
        val identity = Identity.from(before)
        if (
            !after.isRegularFile ||
                !identity.matches(after) ||
                before.lastModifiedTime() != after.lastModifiedTime() ||
                before.size() != after.size() ||
                after.size() != size
        ) {
            throw IOException("Held regular file changed while reading.")
        }
        return SecureFileSnapshot(
            ArtifactDigests(sha1.hex(), sha256.hex(), size),
            identity,
            before.lastModifiedTime(),
        )
    }

    fun readBytes(maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong()) {
            "In-memory source limit must fit in a byte array."
        }
        val expected = snapshot(maxBytes)
        val size = expected.digests.size.toInt()
        val bytes = ByteArray(size)
        Files.newInputStream(anchor).use { input ->
            var cursor = 0
            while (cursor < bytes.size) {
                val read = input.read(bytes, cursor, bytes.size - cursor)
                if (read < 0) throw IOException("Held regular file ended while reading.")
                cursor += read
            }
            if (input.read() >= 0) throw IOException("Held regular file grew while reading.")
        }
        val after = snapshot(maxBytes)
        if (expected != after || ArtifactDigests.fromBytes(bytes) != expected.digests) {
            throw IOException("Held regular file changed while reading bounded bytes.")
        }
        return bytes
    }

    fun outputStream(): OutputStream = Files.newOutputStream(anchor, StandardOpenOption.WRITE)

    fun inputStream(): InputStream = Files.newInputStream(anchor)

    fun copyTo(output: OutputStream, maxBytes: Long): ArtifactDigests {
        require(maxBytes >= 0L) { "Secure streaming limit must not be negative." }
        val expected = snapshot(maxBytes)
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var size = 0L
        inputStream().use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size = Math.addExact(size, read.toLong())
                if (size > maxBytes) throw sizeLimitFailure(maxBytes)
                output.write(buffer, 0, read)
                sha1.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
            }
        }
        val copied = ArtifactDigests(sha1.hex(), sha256.hex(), size)
        val after = snapshot(maxBytes)
        if (expected != after || copied != expected.digests) {
            throw IOException("Held regular file changed while streaming.")
        }
        return copied
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

private const val STREAM_BUFFER_SIZE = 64 * 1024

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

private fun sizeLimitFailure(limit: Long): IOException =
    IOException("Secure streamed file exceeds the configured limit of $limit bytes.")

private class LimitedOutputStream(
    private val output: OutputStream,
    private val limit: Long,
    private val failure: () -> Throwable,
) : OutputStream() {
    private var written = 0L

    override fun write(value: Int) {
        reserve(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        reserve(length)
        output.write(bytes, offset, length)
    }

    override fun flush() = output.flush()

    private fun reserve(count: Int) {
        val next = Math.addExact(written, count.toLong())
        if (next > limit) throw failure()
        written = next
    }
}
