package gg.grounds.resourcepacks.catalog

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner

class BuildBoundaryTest {
    private val rootDirectory =
        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    @Test
    fun `the build exposes exactly the catalog and product projects`() {
        val output = runGradle("projects")

        val projectNames =
            Regex("Project '(:[^']*)'").findAll(output).map { it.groupValues[1] }.toSet()

        assertEquals(setOf(":resourcepacks-catalog", ":resourcepacks-product"), projectNames)
    }

    @Test
    fun `the catalog publishes the version from version txt under its fixed coordinate`() {
        runGradle(":resourcepacks-catalog:generatePomFileForMavenJavaPublication")

        val pom =
            rootDirectory
                .resolve("resourcepacks-catalog/build/publications/mavenJava/pom-default.xml")
                .readText()
        val version = rootDirectory.resolve("version.txt").readText().trim()

        assertContains(pom, "<groupId>gg.grounds</groupId>")
        assertContains(pom, "<artifactId>resourcepacks-catalog</artifactId>")
        assertContains(pom, "<version>$version</version>")
    }

    @Test
    fun `the catalog runtime classpath excludes server config portal test and product builder libraries`() {
        val output =
            runGradle(":resourcepacks-catalog:dependencies", "--configuration", "runtimeClasspath")

        listOf(
                "paper",
                "bukkit",
                "minestom",
                "velocity",
                "portal",
                "junit",
                "kotest",
                "resource-pack-builder",
            )
            .forEach { forbidden ->
                assertFalse(
                    output.contains(forbidden, ignoreCase = true),
                    "catalog runtime graph contains $forbidden",
                )
            }
        assertFalse(
            Regex("gg\\.grounds:[^:\\n]*config", RegexOption.IGNORE_CASE).containsMatchIn(output),
            "catalog runtime graph contains a Grounds config library",
        )
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
