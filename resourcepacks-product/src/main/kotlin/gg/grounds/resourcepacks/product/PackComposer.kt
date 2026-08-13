package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.builder.ResourcePackComposer
import gg.grounds.resourcepack.builder.ZipPackWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class BuiltPhysicalPack(
    val pack: PhysicalPack,
    val file: Path,
    val sha1: String,
    val sha256: String,
    val size: Long,
)

/**
 * Legacy test-facing composer retained for deterministic pack coverage.
 *
 * Every invocation receives a unique child below the caller-owned staging root. Neither failures
 * nor successful pending names are automatically removed: transaction paths are safe leaks owned by
 * the ephemeral workspace or an explicit operator. The release path uses [ReleasePackComposer] and
 * [SecureOwnedDirectory].
 */
internal object PackComposer {
    fun build(graph: ProductGraph, stagingRoot: Path): List<BuiltPhysicalPack> =
        build(graph.packs, stagingRoot)

    internal fun build(packs: List<PhysicalPack>, stagingRoot: Path): List<BuiltPhysicalPack> =
        build(packs, stagingRoot, PackComposerHooks())

    @JvmSynthetic
    internal fun build(
        packs: List<PhysicalPack>,
        stagingRoot: Path,
        hooks: PackComposerHooks,
    ): List<BuiltPhysicalPack> {
        val orderedPacks = validatedProductPacks(packs)
        require(Files.isDirectory(stagingRoot)) { "stagingRoot must be an existing directory." }
        val child = Files.createTempDirectory(stagingRoot, ".pack-composer-")
        val composer = ResourcePackComposer()
        val writer = ZipPackWriter()
        val composed =
            orderedPacks.map { pack ->
                pack to composer.compose(pack.definition, pack.contributions)
            }
        return composed.map { (pack, composedPack) ->
            val pending = child.resolve(".${pack.id}.pending.zip")
            hooks.afterComposeBeforeWrite(pack, pending)
            val artifact = writer.write(composedPack, pending)
            val digests = ArtifactDigests.from(artifact)
            val finalFile = child.resolve("${digests.sha1}.zip")
            hooks.beforePublication(pending, finalFile)
            Files.createLink(finalFile, pending)
            if (!Files.isSameFile(pending, finalFile)) {
                throw IOException("Published artifact is not the pending artifact: $finalFile")
            }
            hooks.afterPublicationBeforeVerify(pack, pending, finalFile)
            val published = ArtifactDigests.readRegularFile(finalFile)
            if (published != digests) {
                throw IOException(
                    "Published artifact digest differs from writer result: $finalFile"
                )
            }
            hooks.afterPackPublicationVerified(pack, finalFile)
            BuiltPhysicalPack(pack, finalFile, published.sha1, published.sha256, published.size)
        }
    }
}

/** Test-only phase hook; production callers always receive the no-op default. */
internal data class PackComposerHooks(
    val beforePublication: (pending: Path, finalFile: Path) -> Unit = { _, _ -> },
    val afterComposeBeforeWrite: (pack: PhysicalPack, pending: Path) -> Unit = { _, _ -> },
    val afterPublicationBeforeVerify: (pack: PhysicalPack, pending: Path, finalFile: Path) -> Unit =
        { _, _, _ ->
        },
    val afterPackPublicationVerified: (pack: PhysicalPack, finalFile: Path) -> Unit = { _, _ -> },
)

internal class ProductValidationException(val result: ProductValidationResult) :
    IllegalStateException(result.problems.joinToString(prefix = "Product validation failed: "))
