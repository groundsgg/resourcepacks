package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.builder.ResourcePackComposer
import gg.grounds.resourcepack.builder.ZipPackWriter
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal data class BuiltPhysicalPack(
    val pack: PhysicalPack,
    val file: Path,
    val sha1: String,
    val sha256: String,
    val size: Long,
)

/**
 * Creates one private child directory below caller-owned [stagingRoot]. On success the caller owns
 * that child (and the returned files); on failure this object removes only that child, without ever
 * following symbolic links. Cleanup uses relative, handle-based operations when the file-system
 * provider supplies [SecureDirectoryStream]. Otherwise it fails closed: it may remove the root link
 * or an empty identity-matching root, but never opens descendants by path. A safe leak is
 * preferable to traversing a replaced directory. The caller's staging root is never created,
 * modified, or cleaned up.
 */
internal object PackComposer {
    internal fun deleteOwnedStagingDirectory(child: Path) {
        val identity =
            try {
                OwnedChildIdentity.capture(child)
            } catch (_: Throwable) {
                return
            }
        val parent =
            try {
                Files.newDirectoryStream(child.parent)
            } catch (_: Throwable) {
                return
            }
        try {
            deleteOwnedChild(child, identity, parent, PackComposerHooks())
        } finally {
            try {
                parent.close()
            } catch (_: Throwable) {}
        }
    }

    fun build(graph: ProductGraph, stagingRoot: Path): List<BuiltPhysicalPack> {
        return build(graph.packs, stagingRoot)
    }

    /**
     * Internal seam for graph construction tests; production callers use [build] with ProductGraph.
     */
    internal fun build(packs: List<PhysicalPack>, stagingRoot: Path): List<BuiltPhysicalPack> {
        return build(packs, stagingRoot, PackComposerHooks())
    }

    @JvmSynthetic
    internal fun build(
        packs: List<PhysicalPack>,
        stagingRoot: Path,
        hooks: PackComposerHooks,
    ): List<BuiltPhysicalPack> {
        val orderedPacks = packs.sortedBy(PhysicalPack::order)
        ProductGraphValidator.validate(orderedPacks).also { result ->
            if (!result.isValid) throw ProductValidationException(result)
        }
        CatalogParityValidator.validate(
                gg.grounds.resourcepacks.catalog.GroundsAssetCatalog.catalog,
                orderedPacks
                    .first { it.role == PackRole.CONTENT }
                    .contributions
                    .flatMap { it.entries }
                    .map { it.path }
                    .toSet(),
            )
            .also { result -> if (!result.isValid) throw ProductValidationException(result) }

        require(Files.isDirectory(stagingRoot)) { "stagingRoot must be an existing directory." }
        val child = Files.createTempDirectory(stagingRoot, ".pack-composer-")
        var childIdentity: OwnedChildIdentity? = null
        var parentDirectory: DirectoryStream<Path>? = null
        var completed = false
        try {
            hooks.afterOwnedChildCreatedBeforeIdentityCapture(child)
            childIdentity = OwnedChildIdentity.capture(child)
            hooks.beforeCleanupParentHandleOpen(child.parent)
            parentDirectory = Files.newDirectoryStream(child.parent)
            val composer = ResourcePackComposer()
            val writer = ZipPackWriter()
            // Compose every pack first: all validation diagnostics occur before a ZIP is written.
            val composed =
                orderedPacks.map { pack ->
                    pack to composer.compose(pack.definition, pack.contributions)
                }
            val built =
                composed.map { (pack, composedPack) ->
                    val pending = child.resolve(".${pack.id}.pending.zip")
                    hooks.afterComposeBeforeWrite(pack, pending)
                    val artifact = writer.write(composedPack, pending)
                    val digests = ArtifactDigests.from(artifact)
                    val finalFile = child.resolve("${digests.sha1}.zip")
                    hooks.beforePublication(pending, finalFile)
                    Files.createLink(finalFile, pending)
                    if (!Files.isSameFile(pending, finalFile))
                        throw IOException(
                            "Published artifact is not the pending artifact: $finalFile"
                        )
                    hooks.afterPublicationBeforeVerify(pack, pending, finalFile)
                    val published = ArtifactDigests.readRegularFile(finalFile)
                    if (published != digests)
                        throw IOException(
                            "Published artifact digest differs from writer result: $finalFile"
                        )
                    Files.delete(pending)
                    hooks.afterPackPublicationVerified(pack, finalFile)
                    BuiltPhysicalPack(
                        pack,
                        finalFile,
                        published.sha1,
                        published.sha256,
                        published.size,
                    )
                }
            completed = true
            return built
        } finally {
            if (!completed) {
                try {
                    hooks.beforeFailureCleanup(child)
                } catch (_: Throwable) {
                    // A test seam must never replace the original composition failure.
                }
                deleteOwnedChild(child, childIdentity, parentDirectory, hooks)
            }
            try {
                parentDirectory?.close()
            } catch (_: Throwable) {
                // Closing cleanup infrastructure must not replace a composition failure.
            }
        }
    }

    private fun deleteOwnedChild(
        child: Path,
        identity: OwnedChildIdentity?,
        parentDirectory: DirectoryStream<Path>?,
        hooks: PackComposerHooks,
    ) {
        try {
            // Without the creation-time identity there is no safe way to distinguish the owned
            // child from a replacement. A bounded temp leak is preferable to deleting caller data.
            if (identity == null) return
            val secureParent = parentDirectory as? SecureDirectoryStream<Path>
            // Path-based inspect-then-delete has a replacement race. Providers without a secure
            // parent handle therefore fail closed and never traverse or delete the root by path.
            if (secureParent == null) return
            deleteSecureEntry(secureParent, child.fileName, child, identity, hooks)
        } catch (_: Throwable) {
            // Preserve the original composition failure.
        }
    }

    private fun deleteSecureEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        displayPath: Path,
        expectedRootIdentity: OwnedChildIdentity?,
        hooks: PackComposerHooks,
    ): Boolean {
        repeat(CLEANUP_ATTEMPTS) {
            val classified = readAttributes(parent, name) ?: return true
            if (expectedRootIdentity != null) {
                if (classified.isSymbolicLink) {
                    return deleteSecureFile(parent, name)
                }
                if (!classified.isDirectory || !expectedRootIdentity.matches(classified)) {
                    return false
                }
            } else if (!classified.isDirectory) {
                if (deleteSecureFile(parent, name)) return true
                return@repeat
            }

            val classifiedIdentity = OwnedChildIdentity.from(classified)
            if (expectedRootIdentity == null) {
                try {
                    hooks.afterCleanupDirectoryClassified(displayPath)
                } catch (_: Throwable) {
                    // A test seam must not weaken cleanup or replace the original failure.
                }
            }
            val childDirectory =
                try {
                    parent.newDirectoryStream(name, NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    return@repeat
                } catch (_: SecurityException) {
                    return false
                }
            val openedIdentity =
                childDirectory.use { opened ->
                    val openedAttributes = readAttributes(opened) ?: return false
                    if (
                        !openedAttributes.isDirectory ||
                            !classifiedIdentity.matches(openedAttributes) ||
                            (expectedRootIdentity != null &&
                                !expectedRootIdentity.matches(openedAttributes))
                    ) {
                        return false
                    }
                    deleteSecureContents(opened, displayPath, hooks)
                    OwnedChildIdentity.from(openedAttributes)
                }

            val beforeDelete = readAttributes(parent, name) ?: return true
            if (!beforeDelete.isDirectory) return@repeat
            if (!openedIdentity.matches(beforeDelete)) return false
            try {
                parent.deleteDirectory(name)
                return true
            } catch (_: NoSuchFileException) {
                return true
            } catch (_: IOException) {
                // A concurrent addition or replacement is reclassified on the next bounded pass.
            } catch (_: SecurityException) {
                return false
            }
        }
        return false
    }

    private fun deleteSecureContents(
        directory: SecureDirectoryStream<Path>,
        displayPath: Path,
        hooks: PackComposerHooks,
    ) {
        val names = directory.mapNotNull(Path::getFileName).toList()
        names.forEach { name ->
            deleteSecureEntry(directory, name, displayPath.resolve(name), null, hooks)
        }
    }

    private fun deleteSecureFile(parent: SecureDirectoryStream<Path>, name: Path): Boolean =
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

    private fun readAttributes(
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
        directory.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()

    private data class OwnedChildIdentity(
        val fileKey: Any?,
        val creationTime: java.nio.file.attribute.FileTime,
    ) {
        fun matches(attributes: BasicFileAttributes): Boolean =
            fileKey != null &&
                fileKey == attributes.fileKey() &&
                creationTime == attributes.creationTime()

        companion object {
            fun from(attributes: BasicFileAttributes): OwnedChildIdentity =
                OwnedChildIdentity(attributes.fileKey(), attributes.creationTime())

            fun capture(path: Path): OwnedChildIdentity {
                val attributes =
                    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isDirectory)
                    throw IOException("Owned child is not a directory: $path")
                return from(attributes)
            }
        }
    }

    private const val CLEANUP_ATTEMPTS = 4
}

/** Test-only phase hook; production callers always receive the no-op default. */
internal data class PackComposerHooks(
    val beforePublication: (pending: Path, finalFile: Path) -> Unit = { _, _ -> },
    val afterComposeBeforeWrite: (pack: PhysicalPack, pending: Path) -> Unit = { _, _ -> },
    val afterPublicationBeforeVerify: (pack: PhysicalPack, pending: Path, finalFile: Path) -> Unit =
        { _, _, _ ->
        },
    val afterPackPublicationVerified: (pack: PhysicalPack, finalFile: Path) -> Unit = { _, _ -> },
    val beforeFailureCleanup: (child: Path) -> Unit = {},
    val afterCleanupDirectoryClassified: (directory: Path) -> Unit = {},
    val beforeCleanupParentHandleOpen: (parent: Path) -> Unit = {},
    val afterOwnedChildCreatedBeforeIdentityCapture: (child: Path) -> Unit = {},
)

internal class ProductValidationException(val result: ProductValidationResult) :
    IllegalStateException(result.problems.joinToString(prefix = "Product validation failed: "))
