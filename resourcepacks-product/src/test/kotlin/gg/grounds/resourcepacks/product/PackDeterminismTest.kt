package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PackDeterminismTest {
    @Test
    fun `fresh builds produce byte identical physical packs`() {
        val firstRoot = Files.createTempDirectory("pack-determinism-first")
        val secondRoot = Files.createTempDirectory("pack-determinism-second")
        try {
            val first = PackComposer.build(ProductGraph, firstRoot)
            val second = PackComposer.build(ProductGraph, secondRoot)

            first.zip(second).forEach { (left, right) ->
                assertContentEquals(Files.readAllBytes(left.file), Files.readAllBytes(right.file))
                assertEquals(left.sha1, right.sha1)
                assertEquals(left.sha256, right.sha256)
                assertEquals(left.size, right.size)
            }
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reversing multiple platform contributions preserves final pack bytes`() {
        val firstRoot = Files.createTempDirectory("pack-contribution-order-first")
        val secondRoot = Files.createTempDirectory("pack-contribution-order-second")
        val first = contribution("grounds:first", "assets/grounds/order/a.txt", "a")
        val second = contribution("grounds:second", "assets/grounds/order/b.txt", "b")
        val content = ProductGraph.packs.first()
        val platform = ProductGraph.packs.last().copy(contributions = listOf(first, second))
        try {
            val forward = PackComposer.build(listOf(content, platform), firstRoot)
            val reverse =
                PackComposer.build(
                    listOf(content, platform.copy(contributions = listOf(second, first))),
                    secondRoot,
                )

            forward.zip(reverse).forEach { (left, right) ->
                assertContentEquals(Files.readAllBytes(left.file), Files.readAllBytes(right.file))
                assertEquals(left.sha1, right.sha1)
                assertEquals(left.sha256, right.sha256)
                assertEquals(left.size, right.size)
            }
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    private fun contribution(id: String, path: String, value: String): PackContribution =
        PackContribution(
            ContributionId.of(id),
            PackFormatRange(88, 88),
            listOf(PackEntry.text(path, value)),
        )
}
