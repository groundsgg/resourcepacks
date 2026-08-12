package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackSetBuilderTest {
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
            assertTrue(Files.isRegularFile(artifacts.manifest))
            assertTrue(artifacts.content.file.fileName.toString().startsWith("grounds-content-"))
            assertTrue(artifacts.platform.file.fileName.toString().startsWith("grounds-platform-"))
            assertTrue(
                artifacts.catalog.file.fileName.toString() ==
                    "grounds-resourcepacks-catalog-0.0.0.jar"
            )
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
}

private fun catalogJar(): java.nio.file.Path =
    generateSequence(java.nio.file.Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile }
        .resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")
