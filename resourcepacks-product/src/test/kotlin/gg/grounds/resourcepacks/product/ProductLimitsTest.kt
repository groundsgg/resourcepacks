package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `both packs accept entry-count and byte boundaries`() {
        listOf(
                ProductGraph.packs.first() to Triple(50_000, 512L * 1024 * 1024, "content"),
                ProductGraph.packs.last() to Triple(4_096, 64L * 1024 * 1024, "platform"),
            )
            .forEach { (pack, bounds) ->
                withContribution(bounds.first, bounds.second) { contribution ->
                    val atBoundary = pack.copy(contributions = listOf(contribution))
                    assertTrue(
                        ProductGraphValidator.validate(listOf(atBoundary, otherPack(pack))).isValid,
                        bounds.third,
                    )
                }
            }
    }

    @Test
    fun `both packs reject one entry and one byte over their limits`() {
        listOf(
                ProductGraph.packs.first() to Triple(50_000, 512L * 1024 * 1024, "content"),
                ProductGraph.packs.last() to Triple(4_096, 64L * 1024 * 1024, "platform"),
            )
            .forEach { (pack, bounds) ->
                withContribution(bounds.first + 1, 0) { contribution ->
                    val entriesOver = pack.copy(contributions = listOf(contribution))
                    assertEquals(
                        listOf(ProductProblem(ProductProblemCode.ENTRY_LIMIT_EXCEEDED, pack.id)),
                        ProductGraphValidator.validate(listOf(entriesOver, otherPack(pack)))
                            .problems,
                        "${bounds.third} entries",
                    )
                }
                withContribution(1, bounds.second + 1) { contribution ->
                    val bytesOver = pack.copy(contributions = listOf(contribution))
                    assertEquals(
                        listOf(
                            ProductProblem(
                                ProductProblemCode.UNCOMPRESSED_SIZE_LIMIT_EXCEEDED,
                                pack.id,
                            )
                        ),
                        ProductGraphValidator.validate(listOf(bytesOver, otherPack(pack))).problems,
                        "${bounds.third} bytes",
                    )
                }
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
            Files.walk(root).use { paths ->
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
