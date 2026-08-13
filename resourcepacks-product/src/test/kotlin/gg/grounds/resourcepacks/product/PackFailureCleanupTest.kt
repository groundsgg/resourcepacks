package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackBuildException
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import gg.grounds.resourcepack.api.PackProblemCode
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackFailureCleanupTest {
    @Test
    fun `validation failure creates no transaction child and preserves caller state`() {
        withRoot("pack-validation-failure") { root ->
            val caller = Files.writeString(root.resolve("caller.txt"), "keep")
            val missing = root.resolve("missing.bin")
            val platform =
                ProductGraph.packs
                    .last()
                    .copy(contributions = listOf(fileContribution("grounds:broken", missing)))

            val failure =
                assertFailsWith<ProductValidationException> {
                    PackComposer.build(listOf(ProductGraph.packs.first(), platform), root)
                }

            assertEquals(
                listOf(ProductProblemCode.SOURCE_SIZE_FAILURE),
                failure.result.problems.map { it.code },
            )
            assertEquals("keep", Files.readString(caller))
            assertEquals(emptyList(), composerChildren(root))
        }
    }

    @Test
    fun `writer failure safely leaks its unique child and preserves caller state`() {
        withRoot("pack-writer-failure") { root ->
            val source = Files.writeString(root.resolve("source.bin"), "source")
            val caller = Files.writeString(root.resolve("caller.txt"), "keep")
            val platform =
                ProductGraph.packs
                    .last()
                    .copy(contributions = listOf(fileContribution("grounds:late-read", source)))

            val failure =
                assertFailsWith<PackBuildException> {
                    PackComposer.build(
                        listOf(ProductGraph.packs.first(), platform),
                        root,
                        PackComposerHooks(
                            afterComposeBeforeWrite = { pack, _ ->
                                if (pack.role == PackRole.PLATFORM) Files.delete(source)
                            }
                        ),
                    )
                }

            assertEquals(
                listOf(PackProblemCode.SOURCE_READ_FAILED),
                failure.problems.map { it.code },
            )
            assertEquals("keep", Files.readString(caller))
            assertTrue(Files.isDirectory(composerChildren(root).single(), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `publication collision never overwrites caller bytes and safely leaks the transaction`() {
        withRoot("pack-publication-collision") { root ->
            val sentinel = Files.writeString(root.resolve("sentinel.bin"), "keep")

            assertFailsWith<Exception> {
                PackComposer.build(
                    ProductGraph.packs,
                    root,
                    PackComposerHooks(
                        beforePublication = { _, finalFile ->
                            Files.createLink(finalFile, sentinel)
                        }
                    ),
                )
            }

            assertContentEquals("keep".toByteArray(), Files.readAllBytes(sentinel))
            assertTrue(Files.isDirectory(composerChildren(root).single(), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `legacy success retains pending hardlinks instead of unlinking them`() {
        withRoot("pack-success-safe-leak") { root ->
            val built = PackComposer.build(ProductGraph, root)
            val child = composerChildren(root).single()
            val names =
                Files.list(child).use { paths ->
                    paths.toList().map { it.fileName.toString() }.toSet()
                }

            assertEquals(2, built.size)
            assertTrue(names.contains(".grounds-content.pending.zip"))
            assertTrue(names.contains(".grounds-platform.pending.zip"))
            assertTrue(built.all { Files.isRegularFile(it.file, NOFOLLOW_LINKS) })
            assertFalse(names.any { it.contains("caller") })
        }
    }

    private fun fileContribution(id: String, source: Path): PackContribution =
        PackContribution(
            ContributionId.of(id),
            PackFormatRange(88, 88),
            listOf(PackEntry.file("assets/grounds/failure/source.bin", source)),
        )

    private fun composerChildren(root: Path): List<Path> =
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".pack-composer-") }.sorted().toList()
        }

    private fun withRoot(prefix: String, block: (Path) -> Unit) {
        val root = Files.createTempDirectory(prefix)
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
