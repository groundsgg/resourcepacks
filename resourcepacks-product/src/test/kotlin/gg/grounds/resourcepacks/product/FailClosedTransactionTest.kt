package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FailClosedTransactionTest {
    @Test
    fun `precommit failure leaves unique stage and scratch without publishing`() {
        withRoot("release-safe-leak") { parent ->
            val output = parent.resolve("release")

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    inputs(output),
                    catalogJar(),
                    PackSetBuilderHooks(
                        rename = SecureRename { _, _, _ -> throw IOException("rename failed") }
                    ),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            val names =
                Files.list(parent).use { paths ->
                    paths.map { it.fileName.toString() }.sorted().toList()
                }
            assertEquals(1, names.count { it.startsWith(".packset-stage-") })
            assertEquals(1, names.count { it.startsWith(".packset-scratch-") })
            val stage =
                Files.list(parent).use { paths ->
                    paths
                        .filter { it.fileName.toString().startsWith(".packset-stage-") }
                        .findFirst()
                        .orElseThrow()
                }
            val scratch =
                Files.list(parent).use { paths ->
                    paths
                        .filter { it.fileName.toString().startsWith(".packset-scratch-") }
                        .findFirst()
                        .orElseThrow()
                }
            assertEquals(4L, Files.list(stage).use { it.count() })
            assertEquals(2L, Files.list(scratch).use { it.count() })
        }
    }

    @Test
    fun `release composition failure leaves unique empty transaction directories`() {
        withRoot("release-composer-leak") { root ->
            val output = root.resolve("release")

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    inputs(output),
                    catalogJar(),
                    PackSetBuilderHooks(
                        afterComposeBeforePackWrite = { pack, _ ->
                            if (pack.role == PackRole.PLATFORM) {
                                throw IOException("composition stopped")
                            }
                        }
                    ),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            val transactionDirectories =
                Files.list(root).use { paths ->
                    paths
                        .filter {
                            val name = it.fileName.toString()
                            name.startsWith(".packset-stage-") ||
                                name.startsWith(".packset-scratch-")
                        }
                        .toList()
                }
            assertEquals(2, transactionDirectories.size)
            transactionDirectories.forEach { directory ->
                assertTrue(Files.isDirectory(directory, NOFOLLOW_LINKS))
                assertEquals(0L, Files.list(directory).use { it.count() })
            }
        }
    }

    @Test
    fun `output path rejects a symbolic link in any ancestor component`() {
        val parent = Files.createTempDirectory("release-output-ancestor-")
        val external = Files.createTempDirectory("release-output-ancestor-external-")
        try {
            val nested = Files.createDirectory(external.resolve("nested"))
            val link = Files.createSymbolicLink(parent.resolve("linked"), external)
            val output = link.resolve(nested.fileName).resolve("release")

            assertFailsWith<Exception> { PackSetBuilder.build(inputs(output), catalogJar()) }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertEquals(emptyList(), Files.list(nested).use { it.toList() })
            assertEquals(
                listOf("linked"),
                Files.list(parent).use { paths ->
                    paths.map { it.fileName.toString() }.sorted().toList()
                },
            )
        } finally {
            deleteTree(parent)
            deleteTree(external)
        }
    }

    @Test
    fun `catalog path rejects symbolic link ancestors and leaves source untouched`() {
        withRoot("release-catalog-ancestor") { parent ->
            val actual = Files.createDirectory(parent.resolve("actual"))
            val source = Files.copy(catalogJar(), actual.resolve(catalogJar().fileName))
            val sourceBytes = Files.readAllBytes(source)
            val linked = Files.createSymbolicLink(parent.resolve("linked"), actual)
            val output = parent.resolve("release")

            assertFailsWith<Exception> {
                PackSetBuilder.build(inputs(output), linked.resolve(source.fileName))
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(linked))
            assertTrue(sourceBytes.contentEquals(Files.readAllBytes(source)))
        }
    }

    @Test
    fun `catalog path rejects a symbolic link leaf and leaves target untouched`() {
        withRoot("release-catalog-leaf") { parent ->
            val actual = Files.copy(catalogJar(), parent.resolve("actual.jar"))
            val linked = Files.createSymbolicLink(parent.resolve(catalogJar().fileName), actual)
            val output = parent.resolve("release")

            assertFailsWith<Exception> { PackSetBuilder.build(inputs(output), linked) }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(linked))
            assertTrue(Files.readAllBytes(actual).contentEquals(Files.readAllBytes(catalogJar())))
        }
    }

    @Test
    fun `catalog identity replacement after capture fails before commit without deleting replacement`() {
        withRoot("release-catalog-race") { parent ->
            val source = Files.copy(catalogJar(), parent.resolve(catalogJar().fileName))
            val displaced = parent.resolve("captured-catalog.jar")
            val output = parent.resolve("release")

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    inputs(output),
                    source,
                    PackSetBuilderHooks(
                        afterCatalogCopy = { catalog, _ ->
                            Files.move(catalog, displaced)
                            Files.copy(catalogJar(), catalog)
                        }
                    ),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(source, NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(displaced, NOFOLLOW_LINKS))
            assertTrue(Files.readAllBytes(source).contentEquals(Files.readAllBytes(displaced)))
        }
    }

    @Test
    fun `pack artwork rejects symbolic link ancestors and leaves target untouched`() {
        withRoot("release-art-ancestor") { parent ->
            val actual = Files.createDirectory(parent.resolve("actual"))
            val source = Files.writeString(actual.resolve("source.bin"), "source")
            val linked = Files.createSymbolicLink(parent.resolve("linked"), actual)
            val platform =
                ProductGraph.packs
                    .last()
                    .copy(
                        contributions =
                            listOf(
                                PackContribution(
                                    ContributionId.of("grounds:secure-source"),
                                    PackFormatRange(88, 88),
                                    listOf(
                                        PackEntry.file(
                                            "assets/grounds/secure/source.bin",
                                            linked.resolve(source.fileName),
                                        )
                                    ),
                                )
                            )
                    )
            val output = parent.resolve("release")

            assertFailsWith<Exception> {
                PackSetBuilder.build(
                    inputs(output),
                    catalogJar(),
                    PackSetBuilderHooks(),
                    listOf(ProductGraph.packs.first(), platform),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(linked))
            assertEquals("source", Files.readString(source))
        }
    }

    @Test
    fun `pack artwork rejects a symbolic link leaf and leaves target untouched`() {
        withRoot("release-art-leaf") { parent ->
            val source = Files.writeString(parent.resolve("source.bin"), "source")
            val linked = Files.createSymbolicLink(parent.resolve("linked.bin"), source)
            val platform = platformWithFileSource(linked)
            val output = parent.resolve("release")

            assertFailsWith<Exception> {
                PackSetBuilder.build(
                    inputs(output),
                    catalogJar(),
                    PackSetBuilderHooks(),
                    listOf(ProductGraph.packs.first(), platform),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(linked))
            assertEquals("source", Files.readString(source))
        }
    }

    @Test
    fun `product theme materialization never rereads mutable artwork paths`() {
        withRoot("release-theme-source-race") { parent ->
            val sourceArt = repositoryRoot().resolve("art/platform")
            val copiedArt = parent.resolve("art")
            Files.walk(sourceArt).use { paths ->
                paths.forEach { source ->
                    val target = copiedArt.resolve(sourceArt.relativize(source).toString())
                    if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
                        Files.createDirectories(target)
                    } else {
                        Files.copy(source, target)
                    }
                }
            }
            val menu = copiedArt.resolve("panels/menu.png")
            val displaced = copiedArt.resolve("panels/captured-menu.png")

            val failure =
                assertFailsWith<IOException> {
                    ProductGraph.secureReleaseInputs(copiedArt) {
                        Files.move(menu, displaced)
                        Files.writeString(menu, "not a PNG")
                    }
                }

            assertNotNull(failure.message)
            assertTrue(
                failure.message!!.contains("Source path identity changed"),
                failure.toString(),
            )
            assertTrue(Files.isRegularFile(menu, NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(displaced, NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `graph validation and pack writing use captured bytes instead of original source paths`() {
        withRoot("release-captured-source") { parent ->
            val source = Files.writeString(parent.resolve("source.bin"), "captured source")
            val secured =
                SecureSourceInputs.capture(
                    listOf(ProductGraph.packs.first(), platformWithFileSource(source))
                )
            try {
                Files.delete(source)

                val built = ReleasePackComposer.build(secured.packs, PackComposerHooks())

                assertEquals(2, built.size)
                assertTrue(built.all { it.bytes.isNotEmpty() })
                val identityFailure = assertFailsWith<IOException> { secured.verifyUnchanged() }
                assertTrue(identityFailure.message.orEmpty().contains("cannot be opened securely"))
            } finally {
                secured.close()
            }
        }
    }

    @Test
    fun `postcommit close failure still returns committed success`() {
        withRoot("release-postcommit-close") { parent ->
            val output = parent.resolve("release")

            val artifacts =
                PackSetBuilder.build(
                    inputs(output),
                    catalogJar(),
                    PackSetBuilderHooks(
                        beforeResourceClose = { committed ->
                            if (committed) throw IOException("injected close failure")
                        }
                    ),
                )

            assertTrue(Files.isDirectory(output, NOFOLLOW_LINKS))
            assertEquals(4L, Files.list(output).use { it.count() })
            assertTrue(Files.isRegularFile(artifacts.manifest.file, NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `output ancestor replacement immediately before commit fails closed`() {
        withRoot("release-output-race") { root ->
            val parent = Files.createDirectory(root.resolve("parent"))
            val output = parent.resolve("release")
            val displaced = root.resolve("captured-parent")

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    inputs(output),
                    catalogJar(),
                    PackSetBuilderHooks(
                        afterPreRenameIdentityVerified = {
                            Files.move(parent, displaced)
                            Files.createDirectory(parent)
                            Files.writeString(parent.resolve("attacker.txt"), "attacker")
                        }
                    ),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertEquals("attacker", Files.readString(parent.resolve("attacker.txt")))
            assertTrue(Files.isDirectory(displaced, NOFOLLOW_LINKS))
        }
    }

    private fun withRoot(prefix: String, block: (Path) -> Unit) {
        val root = Files.createTempDirectory("$prefix-")
        try {
            block(root)
        } finally {
            deleteTree(root)
        }
    }

    private fun platformWithFileSource(source: Path): PhysicalPack =
        ProductGraph.packs
            .last()
            .copy(
                contributions =
                    listOf(
                        PackContribution(
                            ContributionId.of("grounds:secure-source"),
                            PackFormatRange(88, 88),
                            listOf(PackEntry.file("assets/grounds/secure/source.bin", source)),
                        )
                    )
            )
}

private fun inputs(output: Path) = ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output)

private fun catalogJar(): Path =
    generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile }
        .resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")

private fun repositoryRoot(): Path =
    generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile }

private fun deleteTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                failure?.let { throw it }
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
