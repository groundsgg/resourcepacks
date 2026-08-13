package gg.grounds.resourcepacks.product

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
import kotlin.test.assertTrue

class ReleaseDirectoryTest {
    @Test
    fun `initialization failure never deletes a stage name whose captured identity was replaced`() {
        listOf("real-directory", "symlink").forEach { replacementKind ->
            withRoots("release-init-stage-$replacementKind") { parent, external ->
                val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
                val externalEmpty = Files.createDirectory(external.resolve("empty"))
                val output = parent.resolve("release")
                val displaced = parent.resolve("captured-stage")
                var replacement: Path? = null

                assertFailsWith<IOException>(replacementKind) {
                    PackSetBuilder.build(
                        releaseInputs(output),
                        releaseCatalogJar(),
                        PackSetBuilderHooks(
                            afterOwnedDirectoryIdentityCapturedBeforeParentValidation = { stage ->
                                Files.move(stage, displaced)
                                when (replacementKind) {
                                    "real-directory" -> Files.move(externalEmpty, stage)
                                    else -> Files.createSymbolicLink(stage, externalEmpty)
                                }
                                replacement = stage
                            }
                        ),
                    )
                }

                val replacementPath = requireNotNull(replacement)
                assertTrue(Files.exists(replacementPath, NOFOLLOW_LINKS), replacementKind)
                if (replacementKind == "real-directory") {
                    assertTrue(Files.isDirectory(replacementPath, NOFOLLOW_LINKS))
                } else {
                    assertTrue(Files.isSymbolicLink(replacementPath))
                }
                assertTrue(Files.isDirectory(displaced, NOFOLLOW_LINKS))
                assertEquals("outside", Files.readString(sentinel))
                assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            }
        }
    }

    @Test
    fun `cleanup leaves every unregistered attacker entry untouched`() {
        listOf("real-directory", "file", "symlink").forEach { attackerKind ->
            withRoots("release-unregistered-$attackerKind") { parent, external ->
                val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
                val output = parent.resolve("release")
                var leakedStage: Path? = null
                var attackerEntry: Path? = null

                assertFailsWith<IOException>(attackerKind) {
                    PackSetBuilder.build(
                        releaseInputs(output),
                        releaseCatalogJar(),
                        PackSetBuilderHooks(
                            beforePrePublishVerification = { stage ->
                                leakedStage = stage
                                val entry = stage.resolve("attacker-entry")
                                when (attackerKind) {
                                    "real-directory" -> {
                                        Files.createDirectory(entry)
                                        Files.writeString(entry.resolve("attacker.txt"), "attacker")
                                    }
                                    "file" -> Files.writeString(entry, "attacker")
                                    else -> Files.createSymbolicLink(entry, external)
                                }
                                attackerEntry = entry
                            }
                        ),
                    )
                }

                val stage = requireNotNull(leakedStage)
                val entry = requireNotNull(attackerEntry)
                assertTrue(Files.isDirectory(stage, NOFOLLOW_LINKS), attackerKind)
                assertTrue(Files.exists(entry, NOFOLLOW_LINKS), attackerKind)
                when (attackerKind) {
                    "real-directory" ->
                        assertEquals("attacker", Files.readString(entry.resolve("attacker.txt")))
                    "file" -> assertEquals("attacker", Files.readString(entry))
                    else -> assertTrue(Files.isSymbolicLink(entry))
                }
                assertEquals("outside", Files.readString(sentinel))
                assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            }
        }
    }

    @Test
    fun `cleanup leaves an identity-mismatched registered file untouched`() {
        withRoots("release-owned-file-replacement") { parent, _ ->
            val output = parent.resolve("release")
            var stage: Path? = null
            var replacement: Path? = null

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    releaseInputs(output),
                    releaseCatalogJar(),
                    PackSetBuilderHooks(
                        beforePrePublishVerification = { ownedStage ->
                            stage = ownedStage
                            val catalog =
                                Files.list(ownedStage).use { entries ->
                                    entries
                                        .filter { it.fileName.toString().endsWith(".jar") }
                                        .findFirst()
                                        .orElseThrow()
                                }
                            Files.delete(catalog)
                            replacement = Files.writeString(catalog, "attacker")
                        }
                    ),
                )
            }

            assertTrue(Files.isDirectory(requireNotNull(stage), NOFOLLOW_LINKS))
            assertEquals("attacker", Files.readString(requireNotNull(replacement)))
            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `stage name swapped immediately after creation never redirects composition`() {
        listOf("real-directory", "symlink").forEach { replacementKind ->
            withRoots("release-created-stage-$replacementKind") { parent, external ->
                val externalSentinel =
                    Files.writeString(external.resolve("sentinel.txt"), "outside")
                val output = parent.resolve("release")
                val displaced = parent.resolve("displaced-owned-stage")
                var replacement: Path? = null

                assertFailsWith<IOException>(replacementKind) {
                    PackSetBuilder.build(
                        releaseInputs(output),
                        releaseCatalogJar(),
                        PackSetBuilderHooks(
                            afterOwnedDirectoryCreatedBeforeComposition = { stage ->
                                Files.move(stage, displaced)
                                when (replacementKind) {
                                    "real-directory" -> {
                                        Files.createDirectory(stage)
                                        Files.writeString(stage.resolve("attacker.txt"), "attacker")
                                    }
                                    else -> Files.createSymbolicLink(stage, external)
                                }
                                replacement = stage
                            }
                        ),
                    )
                }

                assertFalse(Files.exists(output, NOFOLLOW_LINKS))
                assertEquals("outside", Files.readString(externalSentinel))
                assertEquals(1L, Files.list(external).use { it.count() })
                assertTrue(Files.isDirectory(displaced, NOFOLLOW_LINKS))
                assertTrue(Files.list(displaced).use { it.count() } > 0L)
                if (replacementKind == "real-directory") {
                    assertEquals(
                        "attacker",
                        Files.readString(requireNotNull(replacement).resolve("attacker.txt")),
                    )
                } else {
                    assertTrue(Files.isSymbolicLink(requireNotNull(replacement)))
                }
            }
        }
    }

    @Test
    fun `unsafe and preexisting outputs fail before a stage is created`() {
        val parent = Files.createTempDirectory("release-output-matrix-")
        val external = Files.createTempDirectory("release-output-matrix-external-")
        try {
            val empty = Files.createDirectory(parent.resolve("empty"))
            val nonempty = Files.createDirectory(parent.resolve("nonempty"))
            val sentinel = Files.writeString(nonempty.resolve("sentinel.txt"), "keep")
            val file = Files.writeString(parent.resolve("file"), "keep")
            val link = Files.createSymbolicLink(parent.resolve("link"), external)
            val parentLink = Files.createSymbolicLink(parent.resolve("parent-link"), external)

            listOf(
                    empty,
                    nonempty,
                    file,
                    link,
                    Path.of("relative-release"),
                    Path.of("/"),
                    parentLink.resolve("release"),
                )
                .forEach { output ->
                    assertFailsWith<IllegalArgumentException>(output.toString()) {
                        PackSetBuilder.build(releaseInputs(output), releaseCatalogJar())
                    }
                }

            assertEquals("keep", Files.readString(sentinel))
            assertEquals("keep", Files.readString(file))
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(
                emptyList(),
                Files.list(parent).use { entries ->
                    entries.filter { it.fileName.toString().startsWith(".packset-stage-") }.toList()
                },
            )
        } finally {
            deleteTreeNoFollow(parent)
            deleteTreeNoFollow(external)
        }
    }

    @Test
    fun `concurrent output of every filesystem type is preserved without replacement`() {
        listOf("empty-directory", "nonempty-directory", "file", "symlink").forEach { kind ->
            withRoots("release-concurrent-$kind") { parent, external ->
                val externalSentinel =
                    Files.writeString(external.resolve("sentinel.txt"), "outside")
                val output = parent.resolve("release")
                val failure =
                    assertFailsWith<IOException>(kind) {
                        PackSetBuilder.build(
                            releaseInputs(output),
                            releaseCatalogJar(),
                            PackSetBuilderHooks(
                                rename =
                                    SecureRename { heldParent, from, to ->
                                        val target = heldParent.anchor.resolve(to)
                                        when (kind) {
                                            "empty-directory" -> Files.createDirectory(target)
                                            "nonempty-directory" -> {
                                                Files.createDirectory(target)
                                                Files.writeString(
                                                    target.resolve("sentinel.txt"),
                                                    "keep",
                                                )
                                            }
                                            "file" -> Files.writeString(target, "keep")
                                            "symlink" -> Files.createSymbolicLink(target, external)
                                        }
                                        heldParent.renameNoReplace(from, to)
                                    }
                            ),
                        )
                    }

                assertEquals("Release output already exists.", failure.message)
                assertEquals("outside", Files.readString(externalSentinel))
                when (kind) {
                    "nonempty-directory" ->
                        assertEquals("keep", Files.readString(output.resolve("sentinel.txt")))
                    "file" -> assertEquals("keep", Files.readString(output))
                    "symlink" -> assertTrue(Files.isSymbolicLink(output))
                    else -> assertTrue(Files.isDirectory(output, NOFOLLOW_LINKS))
                }
            }
        }
    }

    @Test
    fun `native publication unavailable fails closed and removes the owned stage`() {
        val parent = Files.createTempDirectory("release-native-unavailable-")
        val output = parent.resolve("release")
        try {
            val failure =
                assertFailsWith<IOException> {
                    PackSetBuilder.build(
                        releaseInputs(output),
                        releaseCatalogJar(),
                        PackSetBuilderHooks(
                            rename =
                                SecureRename { _, _, _ ->
                                    throw IOException("renameat2 unavailable")
                                }
                        ),
                    )
                }
            assertEquals("renameat2 unavailable", failure.message)
            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertEquals(emptyList(), Files.list(parent).use { it.toList() })
        } finally {
            deleteTreeNoFollow(parent)
        }
    }

    @Test
    fun `stage replacement before final verification never publishes or traverses attacker data`() {
        withRoots("release-stage-swap") { parent, external ->
            val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
            val output = parent.resolve("release")
            var replacement: Path? = null

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    releaseInputs(output),
                    releaseCatalogJar(),
                    PackSetBuilderHooks(
                        beforePrePublishVerification = { stage ->
                            deleteTreeNoFollow(stage)
                            Files.createSymbolicLink(stage, external)
                            replacement = stage
                        }
                    ),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertEquals("outside", Files.readString(sentinel))
            assertEquals(1L, Files.list(external).use { it.count() })
            assertTrue(Files.isSymbolicLink(requireNotNull(replacement)))
        }
    }

    @Test
    fun `stage replacement after identity check is rejected before native rename`() {
        withRoots("release-rename-swap") { parent, external ->
            val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
            val output = parent.resolve("release")

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    releaseInputs(output),
                    releaseCatalogJar(),
                    PackSetBuilderHooks(
                        afterPreRenameIdentityVerified = { stage ->
                            deleteTreeNoFollow(stage)
                            Files.createSymbolicLink(stage, external)
                        }
                    ),
                )
            }

            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            assertEquals("outside", Files.readString(sentinel))
            assertEquals(1L, Files.list(external).use { it.count() })
        }
    }

    @Test
    fun `identical-byte entry replacement after identity check is never published`() {
        val parent = Files.createTempDirectory("release-entry-identity-")
        val output = parent.resolve("release")
        try {
            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    releaseInputs(output),
                    releaseCatalogJar(),
                    PackSetBuilderHooks(
                        afterPreRenameIdentityVerified = { stage ->
                            val catalog =
                                Files.list(stage).use { entries ->
                                    entries
                                        .filter { it.fileName.toString().endsWith(".jar") }
                                        .findFirst()
                                        .orElseThrow()
                                }
                            val bytes = Files.readAllBytes(catalog)
                            Files.delete(catalog)
                            Files.write(catalog, bytes)
                        }
                    ),
                )
            }
            assertFalse(Files.exists(output, NOFOLLOW_LINKS))
        } finally {
            deleteTreeNoFollow(parent)
        }
    }

    @Test
    fun `postrename output replacement fails closed without deleting attacker path`() {
        withRoots("release-output-swap") { parent, external ->
            val sentinel = Files.writeString(external.resolve("sentinel.txt"), "outside")
            val output = parent.resolve("release")
            val displaced = parent.resolve("displaced-owned-release")

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    releaseInputs(output),
                    releaseCatalogJar(),
                    PackSetBuilderHooks(
                        afterRenameBeforeOutputOpen = { published ->
                            Files.move(published, displaced)
                            Files.createSymbolicLink(published, external)
                        }
                    ),
                )
            }

            assertTrue(Files.isSymbolicLink(output))
            assertEquals("outside", Files.readString(sentinel))
            assertTrue(Files.isDirectory(displaced, NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `postrename registered entry replacement is left untouched with the leaked output`() {
        withRoots("release-entry-swap") { parent, external ->
            val sentinel = Files.writeString(external.resolve("sentinel.jar"), "outside")
            val output = parent.resolve("release")
            var replacement: Path? = null

            assertFailsWith<IOException> {
                PackSetBuilder.build(
                    releaseInputs(output),
                    releaseCatalogJar(),
                    PackSetBuilderHooks(
                        afterOutputOpenedBeforeVerification = { published ->
                            val catalog =
                                Files.list(published).use { entries ->
                                    entries
                                        .filter { it.fileName.toString().endsWith(".jar") }
                                        .findFirst()
                                        .orElseThrow()
                                }
                            Files.delete(catalog)
                            Files.createSymbolicLink(catalog, sentinel)
                            replacement = catalog
                        }
                    ),
                )
            }

            assertTrue(Files.isDirectory(output, NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(requireNotNull(replacement)))
            assertEquals("outside", Files.readString(sentinel))
        }
    }

    private fun withRoots(prefix: String, block: (Path, Path) -> Unit) {
        val parent = Files.createTempDirectory("$prefix-parent-")
        val external = Files.createTempDirectory("$prefix-external-")
        try {
            block(parent, external)
        } finally {
            deleteTreeNoFollow(parent)
            deleteTreeNoFollow(external)
        }
    }
}

private fun releaseInputs(output: Path) = ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output)

private fun releaseCatalogJar(): Path =
    generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile }
        .resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")

private fun deleteTreeNoFollow(root: Path) {
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
