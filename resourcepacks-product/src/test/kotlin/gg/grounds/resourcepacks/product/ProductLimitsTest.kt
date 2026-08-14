package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackBuildException
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import gg.grounds.resourcepack.api.PackLimits
import gg.grounds.resourcepack.builder.ResourcePackComposer
import gg.grounds.resourcepack.builder.ZipPackWriter
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductLimitsTest {
    @Test
    fun `physical pack limits match the product specification`() {
        assertEquals(50_000, ProductGraph.packs[0].definition.policy.limits.maxEntries)
        assertEquals(
            512L * 1024 * 1024,
            ProductGraph.packs[0].definition.policy.limits.maxUncompressedBytes,
        )
        assertEquals(
            128L * 1024 * 1024,
            ProductGraph.packs[0].definition.policy.limits.maxArtifactBytes,
        )
        assertEquals(4_096, ProductGraph.packs[1].definition.policy.limits.maxEntries)
        assertEquals(
            64L * 1024 * 1024,
            ProductGraph.packs[1].definition.policy.limits.maxUncompressedBytes,
        )
        assertEquals(
            16L * 1024 * 1024,
            ProductGraph.packs[1].definition.policy.limits.maxArtifactBytes,
        )
    }

    @Test
    fun `both packs accept exact physical entry and byte boundaries`() {
        ProductGraph.packs.forEach { pack ->
            val limits = pack.definition.policy.limits
            val roots = upstreamGeneratedRoots(pack)
            withContribution(
                requireNotNull(limits.maxEntries) - roots.entries,
                requireNotNull(limits.maxUncompressedBytes) - roots.bytes,
            ) { contribution ->
                val atBoundary = pack.copy(contributions = listOf(contribution))

                assertTrue(
                    ProductGraphValidator.validate(listOf(atBoundary, otherPack(pack))).isValid,
                    pack.id,
                )
                val composed =
                    ResourcePackComposer().compose(atBoundary.definition, atBoundary.contributions)
                assertEquals(limits.maxEntries, composed.entryPaths.size, pack.id)
            }
        }
    }

    @Test
    fun `both packs reject one physical root-aware entry and byte over their limits`() {
        ProductGraph.packs.forEach { pack ->
            val limits = pack.definition.policy.limits
            val roots = upstreamGeneratedRoots(pack)
            withContribution(requireNotNull(limits.maxEntries) - roots.entries + 1, 0) {
                contribution ->
                val entriesOver = pack.copy(contributions = listOf(contribution))
                assertEquals(
                    listOf(ProductProblem(ProductProblemCode.ENTRY_LIMIT_EXCEEDED, pack.id)),
                    ProductGraphValidator.validate(listOf(entriesOver, otherPack(pack))).problems,
                    "${pack.id} entries",
                )
                assertFailsWith<PackBuildException> {
                    ResourcePackComposer()
                        .compose(entriesOver.definition, entriesOver.contributions)
                }
            }
            withContribution(1, requireNotNull(limits.maxUncompressedBytes) - roots.bytes + 1) {
                contribution ->
                val bytesOver = pack.copy(contributions = listOf(contribution))
                assertEquals(
                    listOf(
                        ProductProblem(ProductProblemCode.UNCOMPRESSED_SIZE_LIMIT_EXCEEDED, pack.id)
                    ),
                    ProductGraphValidator.validate(listOf(bytesOver, otherPack(pack))).problems,
                    "${pack.id} bytes",
                )
                assertFailsWith<PackBuildException> {
                    ResourcePackComposer().compose(bytesOver.definition, bytesOver.contributions)
                }
            }
        }
    }

    @Test
    fun `raw contribution maxima are rejected because generated roots consume limits`() {
        ProductGraph.packs.forEach { pack ->
            val limits = pack.definition.policy.limits
            withContribution(
                requireNotNull(limits.maxEntries),
                requireNotNull(limits.maxUncompressedBytes),
            ) { contribution ->
                val rawMaxima = pack.copy(contributions = listOf(contribution))

                assertEquals(
                    listOf(
                        ProductProblem(ProductProblemCode.ENTRY_LIMIT_EXCEEDED, pack.id),
                        ProductProblem(ProductProblemCode.UNCOMPRESSED_SIZE_LIMIT_EXCEEDED, pack.id),
                    ),
                    ProductGraphValidator.validate(listOf(rawMaxima, otherPack(pack))).problems,
                    pack.id,
                )
                assertTrue(
                    !ResourcePackComposer()
                        .validate(rawMaxima.definition, rawMaxima.contributions)
                        .isValid
                )
            }
        }
    }

    @Test
    fun `optional icon absence counts only the exact generated metadata root`() {
        val platform = ProductGraph.packs.last()
        val withoutIcon = platform.copy(definition = platform.definition.copy(icon = null))
        val limits = withoutIcon.definition.policy.limits
        val roots = upstreamGeneratedRoots(withoutIcon)
        assertEquals(1, roots.entries)
        withContribution(
            requireNotNull(limits.maxEntries) - 1,
            requireNotNull(limits.maxUncompressedBytes) - roots.bytes,
        ) { contribution ->
            val atBoundary = withoutIcon.copy(contributions = listOf(contribution))
            assertTrue(
                ProductGraphValidator.validate(listOf(ProductGraph.packs.first(), atBoundary))
                    .isValid
            )
            ResourcePackComposer().compose(atBoundary.definition, atBoundary.contributions)
        }
    }

    private fun upstreamGeneratedRoots(pack: PhysicalPack): RootOverhead {
        val root = Files.createTempDirectory("resourcepack-generated-roots")
        try {
            val unbounded =
                pack.definition.copy(policy = pack.definition.policy.copy(limits = PackLimits()))
            val composed = ResourcePackComposer().compose(unbounded, emptyList())
            val zip = root.resolve("roots.zip")
            ZipPackWriter().write(composed, zip)
            return ZipFile(zip.toFile()).use { archive ->
                val entries = archive.entries().asSequence().toList()
                RootOverhead(entries.size, entries.sumOf { it.size })
            }
        } finally {
            deleteTree(root)
        }
    }

    private fun otherPack(pack: PhysicalPack): PhysicalPack =
        if (pack.role == PackRole.CONTENT) ProductGraph.packs.last() else ProductGraph.packs.first()

    private fun withContribution(entryCount: Int, bytes: Long, block: (PackContribution) -> Unit) {
        val root = Files.createTempDirectory("resourcepack-limit")
        try {
            val fixture = root.resolve("sized.bin")
            val emptyFixture = root.resolve("empty.bin")
            java.io.RandomAccessFile(fixture.toFile(), "rw").use { it.setLength(bytes) }
            Files.createFile(emptyFixture)
            block(
                PackContribution(
                    ContributionId.of("grounds:limit-fixture"),
                    PackFormatRange(88, 88),
                    List(entryCount) { index ->
                        PackEntry.file(
                            "assets/grounds/limit-$index.bin",
                            if (index == 0) fixture else emptyFixture,
                        )
                    },
                    emptySet(),
                    emptySet(),
                    emptySet(),
                )
            )
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: java.nio.file.Path) {
        Files.walk(root).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private data class RootOverhead(val entries: Int, val bytes: Long)
}
