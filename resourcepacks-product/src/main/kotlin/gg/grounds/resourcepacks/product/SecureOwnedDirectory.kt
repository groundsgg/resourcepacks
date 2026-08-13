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
 * is diagnostics-only; all owned reads, publication, verification, and cleanup are relative to the
 * held parent handles.
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
    private var ownedEntries: Map<String, OwnedEntry> = emptyMap()

    fun verify(): Boolean = verifyName(name)

    private fun verifyName(candidate: Path): Boolean =
        attributes(parent, candidate)?.let {
            it.isDirectory && !it.isSymbolicLink && identity.matches(it)
        } == true

    /** Registers one final root artifact immediately after this transaction creates it. */
    fun registerOwned(relativePath: Path) {
        require(
            !relativePath.isAbsolute &&
                relativePath.nameCount == 1 &&
                relativePath.toString() != "." &&
                relativePath.toString() != ".."
        ) {
            "Owned release entries must be one safe stage-relative name."
        }
        val entryName = relativePath.toString()
        check(entryName !in ownedEntries) { "Release entry is already registered: $entryName" }
        val captured =
            openStageStream().use { opened ->
                val root = readAttributes(opened)
                if (root == null || !root.isDirectory || !identity.matches(root)) {
                    throw IOException("Held release directory identity changed.")
                }
                val attributes =
                    attributes(opened, relativePath)
                        ?: throw IOException("Owned release entry vanished: $relativePath")
                OwnedEntry.capture(attributes, relativePath)
            }
        ownedEntries = ownedEntries + (entryName to captured)
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
                    if (
                        !ledger.getValue(entryName).matches(file.identity, OwnedEntryKind.REGULAR)
                    ) {
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
        rename.rename(nativeParent, name, outputName)
        name = outputName
        if (!verifyName(name)) {
            throw IOException("Published output name does not reference the held stage directory.")
        }
    }

    fun publishedStablePath(): Path = nativeStage.anchor

    fun deleteOwned(hooks: PackSetBuilderHooks = PackSetBuilderHooks()) {
        if (!verifyName(name)) return
        openStageStream().use { heldStage ->
            val held = readAttributes(heldStage) ?: return
            if (!held.isDirectory || !identity.matches(held)) return
            val display = path.parent.resolve(name)
            try {
                hooks.afterCleanupDirectoryClassified(display)
            } catch (_: Throwable) {
                // A cleanup seam cannot obscure the primary build failure.
            }
            val ledger = ownedEntries
            val names = heldStage.names().map(Path::toString)
            if (names.size != names.toSet().size || names.any { it !in ledger }) return
            val present = names.toSet()
            for (entryName in present.sorted()) {
                val attributes = attributes(heldStage, Path.of(entryName)) ?: continue
                if (!ledger.getValue(entryName).matches(attributes)) return
            }
            for (entryName in present.sorted()) {
                val entry = Path.of(entryName)
                val expected = ledger.getValue(entryName)
                val attributes = attributes(heldStage, entry) ?: continue
                if (!expected.matches(attributes)) return
                val deleted =
                    when (expected.kind) {
                        OwnedEntryKind.REGULAR -> deleteFile(heldStage, entry)
                        OwnedEntryKind.DIRECTORY -> deleteEmptyDirectory(heldStage, entry)
                    }
                if (!deleted) return
            }
        }
        if (!verifyName(name)) return
        openStageStream().use { finalStage ->
            val after = readAttributes(finalStage) ?: return
            if (!after.isDirectory || !identity.matches(after)) return
            if (finalStage.names().isNotEmpty()) return
        }
        if (!verifyName(name)) return
        try {
            parent.deleteDirectory(name)
        } catch (_: Throwable) {
            // Cleanup is fail-closed and cannot replace the primary failure.
        }
    }

    fun displayParentStillHeldIdentity(): Boolean = nativeParent.sameDirectory(path.parent)

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
        ): SecureOwnedDirectory {
            val normalizedParent = parentPath.toAbsolutePath().normalize()
            val nativeParent = LinuxDirectoryHandle.open(normalizedParent)
            var parent: SecureDirectoryStream<Path>? = null
            var nativeStage: LinuxDirectoryHandle? = null
            var childName: Path? = null
            var stageIdentity: Identity? = null
            try {
                val rawParent: DirectoryStream<Path> = Files.newDirectoryStream(nativeParent.anchor)
                parent =
                    rawParent as? SecureDirectoryStream<Path>
                        ?: run {
                            rawParent.close()
                            throw IOException("Secure staging directory handles are required.")
                        }
                if (!nativeParent.sameDirectory(normalizedParent)) {
                    throw IOException("Release output parent identity changed while opening it.")
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
                stageIdentity = identity
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
                if (childName != null && parent != null && stageIdentity != null) {
                    try {
                        val parentAttrs = attributes(parent, childName)
                        if (
                            parentAttrs != null &&
                                parentAttrs.isDirectory &&
                                !parentAttrs.isSymbolicLink &&
                                stageIdentity.matches(parentAttrs)
                        ) {
                            parent.deleteDirectory(childName)
                        }
                    } catch (_: Throwable) {}
                }
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

        private fun deleteFile(parent: SecureDirectoryStream<Path>, name: Path): Boolean =
            try {
                parent.deleteFile(name)
                true
            } catch (_: NoSuchFileException) {
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }

        private fun deleteEmptyDirectory(parent: SecureDirectoryStream<Path>, name: Path): Boolean =
            try {
                parent.deleteDirectory(name)
                true
            } catch (_: NoSuchFileException) {
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}

internal enum class OwnedEntryKind {
    REGULAR,
    DIRECTORY,
}

internal data class OwnedEntry(val identity: Identity, val kind: OwnedEntryKind) {
    fun matches(attributes: BasicFileAttributes): Boolean =
        !attributes.isSymbolicLink && kind.matches(attributes) && identity.matches(attributes)

    fun matches(candidate: Identity, candidateKind: OwnedEntryKind): Boolean =
        kind == candidateKind && identity == candidate

    companion object {
        fun capture(attributes: BasicFileAttributes, path: Path): OwnedEntry {
            if (attributes.isSymbolicLink || attributes.fileKey() == null) {
                throw IOException("Owned release entry is unsafe: $path")
            }
            val kind =
                when {
                    attributes.isRegularFile -> OwnedEntryKind.REGULAR
                    attributes.isDirectory -> OwnedEntryKind.DIRECTORY
                    else -> throw IOException("Owned release entry has an unsupported kind: $path")
                }
            return OwnedEntry(Identity.from(attributes), kind)
        }
    }
}

private fun OwnedEntryKind.matches(attributes: BasicFileAttributes): Boolean =
    when (this) {
        OwnedEntryKind.REGULAR -> attributes.isRegularFile
        OwnedEntryKind.DIRECTORY -> attributes.isDirectory
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

    fun renameNoReplace(from: Path, to: Path) {
        requireRelativeName(from)
        requireRelativeName(to)
        try {
            Arena.ofConfined().use { arena ->
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
                    throw IOException(
                        "Atomic no-replace directory publication failed (errno $error)."
                    )
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Atomic no-replace directory publication unavailable.", failure)
        }
    }

    fun sameDirectory(path: Path): Boolean =
        try {
            Files.isSameFile(anchor, path)
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
        private const val O_DIRECTORY = 0x10000
        private const val O_NOFOLLOW = 0x20000
        private const val O_CLOEXEC = 0x80000

        fun open(path: Path): LinuxDirectoryHandle {
            if (System.getProperty("os.name").lowercase() != "linux") {
                throw IOException("Secure release directory handles are supported only on Linux.")
            }
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
                                arena.allocateFrom(path.toString()),
                                O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC,
                            ) as Int
                    if (fd < 0) throw IOException("Release output parent must be a real directory.")
                    return LinuxDirectoryHandle(fd)
                }
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Throwable) {
                throw IOException("Secure release directory handles are unavailable.", failure)
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

private fun ByteArray.sha1(): String = digest("SHA-1")

private fun ByteArray.sha256(): String = digest("SHA-256")

private fun ByteArray.digest(algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }
