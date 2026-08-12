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
                PackSetBuilder.build(ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output))

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
                PackSetBuilder.build(ReleaseInputs("0.0.0", "A".repeat(40), "v0.0.0", output))
            }
            assertTrue(Files.notExists(output))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
