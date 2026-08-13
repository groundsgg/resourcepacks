package gg.grounds.resourcepacks.product

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildPackSetTaskTest {
    @Test
    fun `buildPackSet forwards only the four explicit release properties`() {
        val root =
            generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { it.resolve("settings.gradle.kts").exists() }
        val build = root.resolve("resourcepacks-product/build.gradle.kts").toFile().readText()
        assertContains(build, "buildPackSet")
        listOf("packSetVersion", "provenanceCommit", "provenanceTag", "releaseOutput").forEach {
            assertContains(build, it)
        }
        assertTrue("git " !in build.lowercase())
    }

    @Test
    fun `generated catalog provenance equals the complete current Gradle jar`() {
        val root =
            generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { it.resolve("settings.gradle.kts").exists() }
        val catalog =
            root.resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")
        val actual = ArtifactDigests.readRegularFile(catalog)
        val expected = CatalogJarProvider.expectation()

        assertEquals(actual.size, expected.size)
        assertEquals(actual.sha256, expected.sha256)
    }
}
