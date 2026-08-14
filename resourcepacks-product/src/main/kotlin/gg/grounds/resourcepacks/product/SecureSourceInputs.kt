package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ByteArrayEntrySource
import gg.grounds.resourcepack.api.FileEntrySource
import gg.grounds.resourcepack.api.PackBuildException
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackDefinition
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackEntrySource
import gg.grounds.resourcepack.api.PackProblem
import gg.grounds.resourcepack.api.PackProblemCode
import java.io.IOException
import java.nio.file.Path

/**
 * Immutable product inputs captured from descriptor-walked, no-follow regular files. File
 * descriptors remain open until the release commit so namespace identity changes fail closed.
 */
internal class SecureSourceInputs
private constructor(val packs: List<PhysicalPack>, private val heldFiles: List<HeldSourceFile>) :
    AutoCloseable {
    fun verifyUnchanged() {
        heldFiles.forEach(HeldSourceFile::verifyUnchanged)
    }

    override fun close() {
        var failure: Throwable? = null
        heldFiles.asReversed().forEach { source ->
            try {
                source.close()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        fun capture(packs: List<PhysicalPack>): SecureSourceInputs =
            capture(packs, emptyList(), requireAllSeedFiles = false)

        fun captureWithHeld(
            packs: List<PhysicalPack>,
            heldFiles: List<HeldSourceFile>,
        ): SecureSourceInputs = capture(packs, heldFiles, requireAllSeedFiles = true)

        private fun capture(
            packs: List<PhysicalPack>,
            heldFiles: List<HeldSourceFile>,
            requireAllSeedFiles: Boolean,
        ): SecureSourceInputs {
            val opened = linkedMapOf<Path, HeldSourceFile>()
            heldFiles.forEach { source ->
                check(opened.put(source.path, source) == null) {
                    "A secure source path was supplied more than once: ${source.path}"
                }
            }
            try {
                fun immutable(source: PackEntrySource, maxBytes: Long): PackEntrySource =
                    when (source) {
                        is FileEntrySource -> {
                            val normalized = HeldSourceFile.normalize(source.path)
                            val held =
                                if (requireAllSeedFiles) {
                                    opened[normalized]
                                        ?: throw IOException(
                                            "Materialized an unpinned product artwork source: " +
                                                normalized
                                        )
                                } else {
                                    opened.getOrPut(normalized) {
                                        HeldSourceFile.capture(normalized, maxBytes, true)
                                    }
                                }
                            held.requireSizeAtMost(maxBytes)
                            // This procfs anchor names the retained descriptor, not the original
                            // mutable namespace path. The descriptor remains held through commit.
                            FileEntrySource(held.stablePath)
                        }
                        is ByteArrayEntrySource -> {
                            if (source.size() > maxBytes) throw packSizeLimitFailure(maxBytes)
                            source
                        }
                    }

                fun definition(value: PackDefinition, maxBytes: Long): PackDefinition =
                    value.copy(icon = value.icon?.let { immutable(it, maxBytes) })

                fun immutableContribution(
                    value: PackContribution,
                    maxBytes: Long,
                ): PackContribution =
                    PackContribution(
                        value.id,
                        value.supportedFormats,
                        value.entries.map { PackEntry(it.path, immutable(it.source, maxBytes)) },
                        value.vanillaClaims,
                        value.provides,
                        value.requires,
                    )

                val immutablePacks =
                    packs.map { pack ->
                        val maxBytes =
                            requireNotNull(pack.definition.policy.limits.maxUncompressedBytes)
                        pack.copy(
                            definition = definition(pack.definition, maxBytes),
                            contributions =
                                pack.contributions.map { contribution ->
                                    immutableContribution(contribution, maxBytes)
                                },
                        )
                    }
                return SecureSourceInputs(immutablePacks, opened.values.toList())
            } catch (failure: Throwable) {
                opened.values.toList().asReversed().forEach { source ->
                    try {
                        source.close()
                    } catch (close: Throwable) {
                        failure.addSuppressed(close)
                    }
                }
                throw failure
            }
        }
    }
}

/** One immutable byte snapshot tied to a retained Linux file descriptor and display identity. */
internal class HeldSourceFile
private constructor(
    val path: Path,
    private val handle: LinuxRegularFileHandle,
    private val snapshot: SecureFileSnapshot,
) : AutoCloseable {
    val stablePath: Path
        get() = handle.anchor

    val digests: ArtifactDigests
        get() = snapshot.digests

    internal val regularHandle: LinuxRegularFileHandle
        get() = handle

    fun readBytes(maxBytes: Long): ByteArray {
        requireSizeAtMost(maxBytes)
        return handle.readBytes(maxBytes)
    }

    fun requireSizeAtMost(maxBytes: Long) {
        if (snapshot.digests.size > maxBytes) throw packSizeLimitFailure(maxBytes)
    }

    fun verifyUnchanged() {
        val heldNow = handle.snapshot(snapshot.digests.size)
        if (!snapshot.sameFile(heldNow)) {
            throw IOException("Source bytes changed before release commit: $path")
        }
        val reopened = LinuxDirectoryHandle.openRegularFile(path)
        reopened.use { current ->
            if (
                !handle.sameFile(current) ||
                    !snapshot.sameFile(current.snapshot(snapshot.digests.size))
            ) {
                throw IOException("Source path identity changed before release commit: $path")
            }
        }
    }

    override fun close() = handle.close()

    companion object {
        fun normalize(path: Path): Path {
            require(path == path.normalize()) { "Source paths must not contain dot components." }
            val absolute =
                if (path.isAbsolute) path
                else Path.of(System.getProperty("user.dir")).resolve(path).toAbsolutePath()
            require(absolute == absolute.normalize()) {
                "Source paths must resolve to an absolute normalized path."
            }
            return absolute
        }

        fun capture(
            path: Path,
            maxBytes: Long = Long.MAX_VALUE,
            controlledPackLimit: Boolean = false,
        ): HeldSourceFile {
            val normalized = normalize(path)
            val handle = LinuxDirectoryHandle.openRegularFile(normalized)
            try {
                val limitFailure = {
                    if (controlledPackLimit) packSizeLimitFailure(maxBytes)
                    else IOException("Source exceeds the configured limit of $maxBytes bytes.")
                }
                return HeldSourceFile(
                    normalized,
                    handle,
                    handle.snapshot(maxBytes, limitFailure = limitFailure),
                )
            } catch (failure: Throwable) {
                try {
                    handle.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
                throw failure
            }
        }
    }
}

private fun SecureFileSnapshot.sameFile(other: SecureFileSnapshot): Boolean =
    identity == other.identity &&
        digests == other.digests &&
        lastModifiedTime == other.lastModifiedTime

private fun packSizeLimitFailure(limit: Long): PackBuildException =
    PackBuildException(
        listOf(
            PackProblem(
                PackProblemCode.SIZE_LIMIT_EXCEEDED,
                "Source exceeds the configured limit of $limit bytes.",
            )
        )
    )
