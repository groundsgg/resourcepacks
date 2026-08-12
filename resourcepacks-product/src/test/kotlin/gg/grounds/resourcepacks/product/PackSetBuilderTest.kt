package gg.grounds.resourcepacks.product

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackSetBuilderTest {
    @Test
    fun `isolated builder phase failures publish none and preserve catalog and artwork`() {
        val root = repositoryRoot()
        val catalog = catalogJar()
        val sourcePaths =
            Files.walk(root.resolve("art")).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().toList()
            } + listOf(catalog)
        val before = sourcePaths.associateWith(::immutableFileState)
        val cases =
            listOf<Pair<String, PackSetBuilderHooks>>(
                "source-read" to
                    PackSetBuilderHooks(
                        beforeComposition = { throw IOException("source art read unavailable") }
                    ),
                "catalog-copy" to
                    PackSetBuilderHooks(
                        afterCatalogCopy = { _, staged -> Files.write(staged, byteArrayOf(0x42)) }
                    ),
                "composer" to
                    PackSetBuilderHooks(
                        afterComposition = { throw IOException("composer phase failed") }
                    ),
                "stage-write" to
                    PackSetBuilderHooks(
                        afterStageWrite = { throw IOException("stage write failed") }
                    ),
                "hash" to
                    PackSetBuilderHooks(
                        beforePrePublishVerification = { stage ->
                            val pack =
                                Files.list(stage).use { entries ->
                                    entries
                                        .filter { it.fileName.toString().endsWith(".zip") }
                                        .findFirst()
                                        .orElseThrow()
                                }
                            Files.write(pack, byteArrayOf(0x42))
                        }
                    ),
                "manifest" to
                    PackSetBuilderHooks(
                        beforeManifestValidation = { manifest -> Files.writeString(manifest, "{}") }
                    ),
                "native-rename" to
                    PackSetBuilderHooks(
                        rename = SecureRename { _, _, _ -> throw IOException("rename failed") }
                    ),
            )
        val parent = Files.createTempDirectory("packset-phase-matrix-")
        try {
            cases.forEachIndexed { index, (name, hooks) ->
                val output = parent.resolve("release-$index")
                assertFailsWith<Exception>(name) {
                    PackSetBuilder.build(
                        ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output),
                        catalog,
                        hooks,
                    )
                }
                assertFalse(Files.exists(output, NOFOLLOW_LINKS), name)
                assertEquals(before, sourcePaths.associateWith(::immutableFileState), name)
            }
            assertEquals(emptyList(), Files.list(parent).use { it.toList() })
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build publishes exactly the measured four artifact release atomically`() {
        val parent = Files.createTempDirectory("packset-builder-")
        val output = parent.resolve("release")
        try {
            val artifacts =
                PackSetBuilder.build(
                    ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output),
                    catalogJar(),
                )

            assertEquals(
                setOf(
                    artifacts.content.file.fileName.toString(),
                    artifacts.platform.file.fileName.toString(),
                    artifacts.catalog.file.fileName.toString(),
                    "manifest.json",
                ),
                Files.list(output).use { paths ->
                    paths.map { it.fileName.toString() }.toList().toSet()
                },
            )
            assertTrue(Files.isRegularFile(artifacts.manifest.file))
            assertTrue(artifacts.content.file.fileName.toString().startsWith("grounds-content-"))
            assertTrue(artifacts.platform.file.fileName.toString().startsWith("grounds-platform-"))
            assertTrue(
                artifacts.catalog.file.fileName.toString() ==
                    "grounds-resourcepacks-catalog-0.0.0.jar"
            )
            listOf(artifacts.content, artifacts.platform, artifacts.catalog, artifacts.manifest)
                .forEach { artifact ->
                    assertTrue(Files.isRegularFile(artifact.file, NOFOLLOW_LINKS))
                    assertFalse(Files.isSymbolicLink(artifact.file))
                    assertEquals(ArtifactDigests.readRegularFile(artifact.file).sha1, artifact.sha1)
                    assertEquals(
                        ArtifactDigests.readRegularFile(artifact.file).sha256,
                        artifact.sha256,
                    )
                    assertEquals(Files.size(artifact.file), artifact.size)
                }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build rejects wrong explicit provenance before creating output`() {
        val parent = Files.createTempDirectory("packset-builder-invalid-")
        val output = parent.resolve("release")
        try {
            assertFailsWith<IllegalArgumentException> {
                PackSetBuilder.build(
                    ReleaseInputs("0.0.0", "A".repeat(40), "v0.0.0", output),
                    catalogJar(),
                )
            }
            assertTrue(Files.notExists(output))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `atomic publication never replaces a concurrently existing target`() {
        val parent = Files.createTempDirectory("packset-builder-collision-")
        val stage = Files.createDirectory(parent.resolve("stage"))
        val target = Files.createDirectory(parent.resolve("release"))
        val sentinel = Files.writeString(target.resolve("sentinel.txt"), "keep")
        try {
            val failure =
                assertFailsWith<java.io.IOException> {
                    AtomicNoReplaceRename.publish(stage, target)
                }
            assertEquals("Release output already exists.", failure.message)
            assertEquals("keep", Files.readString(sentinel))
            assertTrue(Files.isDirectory(stage))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog provider rejects missing wrong-name and stale jar bytes without publication`() {
        val parent = Files.createTempDirectory("packset-catalog-input-")
        val correctName = "resourcepacks-catalog-0.0.0.jar"
        try {
            val cases =
                listOf(
                    parent.resolve(correctName),
                    Files.copy(catalogJar(), parent.resolve("wrong-name.jar")),
                    staleCatalogJar(parent.resolve("stale").resolve(correctName)),
                )
            cases.forEachIndexed { index, candidate ->
                val output = parent.resolve("release-$index")
                assertFailsWith<Exception>(candidate.toString()) {
                    PackSetBuilder.build(
                        ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output),
                        candidate,
                    )
                }
                assertFalse(Files.exists(output, NOFOLLOW_LINKS))
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same explicit inputs are byte identical and changed commit changes only manifest`() {
        val parent = Files.createTempDirectory("packset-determinism-")
        try {
            val first = parent.resolve("first")
            val second = parent.resolve("second")
            val changed = parent.resolve("changed")
            PackSetBuilder.build(
                ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", first),
                catalogJar(),
            )
            PackSetBuilder.build(
                ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", second),
                catalogJar(),
            )
            PackSetBuilder.build(
                ReleaseInputs("0.0.0", "b".repeat(40), "v0.0.0", changed),
                catalogJar(),
            )

            val names =
                Files.list(first).use {
                    it.map { path -> path.fileName.toString() }.sorted().toList()
                }
            assertEquals(
                names,
                Files.list(second).use {
                    it.map { path -> path.fileName.toString() }.sorted().toList()
                },
            )
            assertEquals(
                names,
                Files.list(changed).use {
                    it.map { path -> path.fileName.toString() }.sorted().toList()
                },
            )
            names.forEach { name ->
                assertContentEquals(
                    Files.readAllBytes(first.resolve(name)),
                    Files.readAllBytes(second.resolve(name)),
                    name,
                )
                if (name == "manifest.json") {
                    assertFalse(
                        Files.readAllBytes(first.resolve(name))
                            .contentEquals(Files.readAllBytes(changed.resolve(name)))
                    )
                } else {
                    assertContentEquals(
                        Files.readAllBytes(first.resolve(name)),
                        Files.readAllBytes(changed.resolve(name)),
                        name,
                    )
                }
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}

private fun catalogJar(): java.nio.file.Path =
    repositoryRoot().resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")

private fun repositoryRoot(): Path =
    generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile }

private data class ImmutableFileState(
    val bytes: List<Byte>,
    val fileKey: Any?,
    val creationTime: java.nio.file.attribute.FileTime,
    val lastModifiedTime: java.nio.file.attribute.FileTime,
)

private fun immutableFileState(path: Path): ImmutableFileState {
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    return ImmutableFileState(
        Files.readAllBytes(path).toList(),
        attrs.fileKey(),
        attrs.creationTime(),
        attrs.lastModifiedTime(),
    )
}

private fun staleCatalogJar(path: Path): Path {
    Files.createDirectories(path.parent)
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
        jar.putNextEntry(JarEntry("gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class"))
        jar.write("stale".toByteArray())
        jar.closeEntry()
    }
    return path
}
