package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackFailureCleanupTest {
    @Test
    fun `validator failure leaves the caller owned staging directory untouched`() {
        val root = Files.createTempDirectory("pack-failure-cleanup")
        val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
        val failingPlatform =
            ProductGraph.packs
                .last()
                .copy(
                    contributions =
                        listOf(
                            PackContribution(
                                ContributionId.of("grounds:broken"),
                                PackFormatRange(88, 88),
                                listOf(
                                    PackEntry.file(
                                        "assets/grounds/broken.bin",
                                        root.resolve("missing.bin"),
                                    )
                                ),
                            )
                        )
                )
        try {
            assertFailsWith<Exception> {
                PackComposer.build(listOf(ProductGraph.packs.first(), failingPlatform), root)
            }

            assertTrue(Files.exists(callerFile))
            assertTrue(Files.list(root).use { files -> files.allMatch { it == callerFile } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
