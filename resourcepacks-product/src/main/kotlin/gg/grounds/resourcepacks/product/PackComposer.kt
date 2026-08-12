package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.builder.ResourcePackComposer
import gg.grounds.resourcepack.builder.ZipPackWriter
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
 * following symbolic links. The caller's staging root is never created, modified, or cleaned up.
 */
internal object PackComposer {
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
        var completed = false
        try {
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
                    val artifact = writer.write(composedPack, pending)
                    val digests = ArtifactDigests.from(artifact)
                    val finalFile = child.resolve("${digests.sha1}.zip")
                    hooks.beforePublication(pending, finalFile)
                    Files.createLink(finalFile, pending)
                    if (!Files.isSameFile(pending, finalFile))
                        throw IOException(
                            "Published artifact is not the pending artifact: $finalFile"
                        )
                    val published = ArtifactDigests.readRegularFile(finalFile)
                    if (published != digests)
                        throw IOException(
                            "Published artifact digest differs from writer result: $finalFile"
                        )
                    Files.delete(pending)
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
            if (!completed) deleteOwnedChild(child)
        }
    }

    private fun deleteOwnedChild(child: Path) {
        try {
            Files.walkFileTree(
                child,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exception: IOException?,
                    ): FileVisitResult {
                        exception?.let { throw it }
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            // Preserve the original composition failure.
        } catch (_: SecurityException) {
            // Preserve the original composition failure.
        }
    }
}

/** Test-only phase hook; production callers always receive the no-op default. */
internal data class PackComposerHooks(
    val beforePublication: (pending: Path, finalFile: Path) -> Unit = { _, _ -> }
)

internal class ProductValidationException(val result: ProductValidationResult) :
    IllegalStateException(result.problems.joinToString(prefix = "Product validation failed: "))
