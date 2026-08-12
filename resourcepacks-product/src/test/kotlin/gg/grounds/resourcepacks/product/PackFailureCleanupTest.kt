package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackBuildException
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import gg.grounds.resourcepack.api.PackProblemCode
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackFailureCleanupTest {
    @Test
    fun `graph validation failure creates no child and preserves the caller root`() {
        withRoot("pack-graph-failure") { root ->
            val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
            val existing = existingComposerSibling(root)
            val missing = root.resolve("missing.bin")
            val failingPlatform =
                ProductGraph.packs
                    .last()
                    .copy(contributions = listOf(fileContribution("grounds:broken", missing)))

            val failure =
                assertFailsWith<ProductValidationException> {
                    PackComposer.build(listOf(ProductGraph.packs.first(), failingPlatform), root)
                }

            assertEquals(
                listOf(ProductProblemCode.SOURCE_SIZE_FAILURE),
                failure.result.problems.map { it.code },
            )
            assertCallerState(root, callerFile, existing)
        }
    }

    @Test
    fun `catalog parity failure creates no child and preserves the caller root`() {
        withRoot("pack-parity-failure") { root ->
            val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
            val existing = existingComposerSibling(root)
            val content =
                ProductGraph.packs
                    .first()
                    .copy(
                        contributions =
                            listOf(
                                contribution(
                                    "grounds:unexpected-catalog-asset",
                                    PackEntry.text("assets/grounds/models/unmapped.json", "{}"),
                                )
                            )
                    )

            val failure =
                assertFailsWith<ProductValidationException> {
                    PackComposer.build(listOf(content, ProductGraph.packs.last()), root)
                }

            assertEquals(
                listOf(ProductProblemCode.CATALOG_EXTRA_CONTENT),
                failure.result.problems.map { it.code },
            )
            assertCallerState(root, callerFile, existing)
        }
    }

    @Test
    fun `source removed after composition fails in the real writer and removes the owned child`() {
        withRoot("pack-source-read-failure") { root ->
            val source = Files.writeString(root.resolve("source.bin"), "source")
            val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
            val existing = existingComposerSibling(root)
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
            assertCallerState(root, callerFile, existing)
        }
    }

    @Test
    fun `platform artifact limit failure removes content published earlier in the same child`() {
        withRoot("pack-artifact-limit-failure") { root ->
            val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
            val existing = existingComposerSibling(root)
            val incompressible = ByteArray(16 * 1024 * 1024 + 64 * 1024)
            Random(0x5eed).nextBytes(incompressible)
            val platform =
                ProductGraph.packs
                    .last()
                    .copy(
                        contributions =
                            listOf(
                                contribution(
                                    "grounds:artifact-limit",
                                    PackEntry.bytes(
                                        "assets/grounds/large/incompressible.bin",
                                        incompressible,
                                    ),
                                )
                            )
                    )
            var contentPublicationVerified = false

            val failure =
                assertFailsWith<PackBuildException> {
                    PackComposer.build(
                        listOf(ProductGraph.packs.first(), platform),
                        root,
                        PackComposerHooks(
                            afterPackPublicationVerified = { pack, _ ->
                                if (pack.role == PackRole.CONTENT) {
                                    contentPublicationVerified = true
                                }
                            }
                        ),
                    )
                }

            assertTrue(contentPublicationVerified)
            assertEquals(
                listOf(PackProblemCode.SIZE_LIMIT_EXCEEDED),
                failure.problems.map { it.code },
            )
            assertCallerState(root, callerFile, existing)
        }
    }

    @Test
    fun `published hardlink mutation causes a real digest mismatch and removes the owned child`() {
        withRoot("pack-digest-mismatch") { root ->
            val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
            val existing = existingComposerSibling(root)

            val failure =
                assertFailsWith<IOException> {
                    PackComposer.build(
                        ProductGraph.packs,
                        root,
                        PackComposerHooks(
                            afterPublicationBeforeVerify = { pack, _, finalFile ->
                                if (pack.role == PackRole.CONTENT) {
                                    Files.write(finalFile, byteArrayOf(0x42))
                                }
                            }
                        ),
                    )
                }

            assertTrue(failure.message.orEmpty().contains("digest differs"))
            assertCallerState(root, callerFile, existing)
        }
    }

    @Test
    fun `publication collision never overwrites an external hard linked sentinel`() {
        withRoot("pack-publication-collision") { root ->
            val sentinel = Files.writeString(root.resolve("sentinel.bin"), "keep")
            val existing = existingComposerSibling(root)

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
            assertCallerState(root, sentinel, existing)
        }
    }

    @Test
    fun `cleanup removes an internal symlink without touching its external target`() {
        withRoot("pack-nested-link-failure") { root ->
            withRoot("pack-nested-link-external") { external ->
                val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
                val source = Files.writeString(root.resolve("source.bin"), "source")
                val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
                val existing = existingComposerSibling(root)
                val platform =
                    ProductGraph.packs
                        .last()
                        .copy(contributions = listOf(fileContribution("grounds:link", source)))

                assertFailsWith<PackBuildException> {
                    PackComposer.build(
                        listOf(ProductGraph.packs.first(), platform),
                        root,
                        PackComposerHooks(
                            afterComposeBeforeWrite = { pack, pending ->
                                if (pack.role == PackRole.PLATFORM) {
                                    Files.createSymbolicLink(
                                        pending.parent.resolve("outside-link"),
                                        external,
                                    )
                                    Files.delete(source)
                                }
                            }
                        ),
                    )
                }

                assertEquals("outside", Files.readString(sentinel))
                assertEquals(1L, Files.list(external).use { it.count() })
                assertCallerState(root, callerFile, existing)
            }
        }
    }

    @Test
    fun `cleanup deletes a replacement root symlink only and preserves the external directory`() {
        withRoot("pack-root-link-failure") { root ->
            withRoot("pack-root-link-external") { external ->
                val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
                val source = Files.writeString(root.resolve("source.bin"), "source")
                val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
                val existing = existingComposerSibling(root)
                val platform =
                    ProductGraph.packs
                        .last()
                        .copy(contributions = listOf(fileContribution("grounds:root-link", source)))

                assertFailsWith<PackBuildException> {
                    PackComposer.build(
                        listOf(ProductGraph.packs.first(), platform),
                        root,
                        PackComposerHooks(
                            afterComposeBeforeWrite = { pack, _ ->
                                if (pack.role == PackRole.PLATFORM) Files.delete(source)
                            },
                            beforeFailureCleanup = { child ->
                                deleteTreeNoFollow(child)
                                Files.createSymbolicLink(child, external)
                            },
                        ),
                    )
                }

                assertEquals("outside", Files.readString(sentinel))
                assertEquals(1L, Files.list(external).use { it.count() })
                assertCallerState(root, callerFile, existing)
            }
        }
    }

    @Test
    fun `a cleanup hook failure cannot replace the writer failure or skip cleanup`() {
        withRoot("pack-cleanup-hook-failure") { root ->
            val source = Files.writeString(root.resolve("source.bin"), "source")
            val callerFile = Files.writeString(root.resolve("caller-owned.txt"), "keep")
            val existing = existingComposerSibling(root)
            val platform =
                ProductGraph.packs
                    .last()
                    .copy(contributions = listOf(fileContribution("grounds:hook", source)))

            val failure = assertFails {
                PackComposer.build(
                    listOf(ProductGraph.packs.first(), platform),
                    root,
                    PackComposerHooks(
                        afterComposeBeforeWrite = { pack, _ ->
                            if (pack.role == PackRole.PLATFORM) Files.delete(source)
                        },
                        beforeFailureCleanup = {
                            throw IllegalStateException("cleanup hook must not escape")
                        },
                    ),
                )
            }

            assertTrue(failure is PackBuildException)
            assertFalse(failure.message.orEmpty().contains("cleanup hook must not escape"))
            assertCallerState(root, callerFile, existing)
        }
    }

    private fun fileContribution(id: String, source: Path): PackContribution =
        contribution(id, PackEntry.file("assets/grounds/failure/source.bin", source))

    private fun contribution(id: String, entry: PackEntry): PackContribution =
        PackContribution(ContributionId.of(id), PackFormatRange(88, 88), listOf(entry))

    private fun existingComposerSibling(root: Path): Path {
        val sibling = Files.createDirectory(root.resolve(".pack-composer-existing"))
        Files.writeString(sibling.resolve("nested-marker.txt"), "keep nested")
        return sibling
    }

    private fun assertCallerState(root: Path, callerFile: Path, existing: Path) {
        assertTrue(Files.isRegularFile(callerFile, NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(callerFile))
        assertEquals("keep nested", Files.readString(existing.resolve("nested-marker.txt")))
        assertEquals(
            emptyList(),
            Files.list(root).use { paths ->
                paths
                    .filter {
                        it.fileName.toString().startsWith(".pack-composer-") && it != existing
                    }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            },
        )
    }

    private fun withRoot(prefix: String, block: (Path) -> Unit) {
        val root = Files.createTempDirectory(prefix)
        try {
            block(root)
        } finally {
            deleteTreeNoFollow(root)
        }
    }

    private fun deleteTreeNoFollow(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
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
    }
}
