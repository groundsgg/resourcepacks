package gg.grounds.resourcepacks.product

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner

class BuildBoundaryTest {
    private val rootDirectory =
        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    @Test
    fun `the product depends on the catalog and has no publication tasks`() {
        val dependencies =
            runGradle(":resourcepacks-product:dependencies", "--configuration", "runtimeClasspath")
        val tasks = runGradle(":resourcepacks-product:tasks", "--all")

        assertContains(dependencies, "project :resourcepacks-catalog")
        assertFalse(tasks.contains("publishToMavenLocal"), "product must not be published")
        assertFalse(tasks.contains("generatePomFileFor"), "product must not create a Maven POM")
    }

    private fun runGradle(vararg arguments: String): String =
        GradleRunner.create()
            .withProjectDir(rootDirectory.toFile())
            .withArguments(
                "--gradle-user-home",
                Path.of(System.getProperty("user.home"), ".gradle").toString(),
                "--stacktrace",
                *arguments,
            )
            .forwardOutput()
            .build()
            .output
}
