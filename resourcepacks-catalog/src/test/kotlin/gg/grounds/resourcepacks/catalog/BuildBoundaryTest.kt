package gg.grounds.resourcepacks.catalog

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
        runGradle(":resourcepacks-catalog:verifyCatalogRuntimeClasspath")
    }

    @Test
    fun `the build requires committed dependency locks`() {
        runGradle("verifyDependencyLocks")
    }

    @Test
    fun `the catalog runtime verification rejects a testkit control mutation by resolved coordinate`() {
        val buildFile = rootDirectory.resolve("resourcepacks-catalog/build.gradle.kts")
        val originalBuild = buildFile.readText()

        try {
            buildFile.writeText(
                originalBuild +
                    "\n dependencies { runtimeOnly(\"gg.grounds:resource-pack-testkit:0.1.0\") }\n"
            )

            val failure = runGradleAndFail(":resourcepacks-catalog:verifyCatalogRuntimeClasspath")

            assertContains(failure, "gg.grounds:resource-pack-testkit:0.1.0")
        } finally {
            buildFile.writeText(originalBuild)
        }
    }

    @Test
    fun `version txt rejects prerelease numeric identifiers with leading zeroes`() {
        assertInvalidVersion("1.0.0-01\n")
    }

    @Test
    fun `version txt rejects leading whitespace instead of trimming it`() {
        assertInvalidVersion(" 1.0.0\n")
    }

    private fun assertInvalidVersion(contents: String) {
        val versionFile = rootDirectory.resolve("version.txt")
        val originalVersion = versionFile.readText()

        try {
            versionFile.writeText(contents)
            val failure = runGradleAndFail("help")
            assertContains(failure, "version.txt must contain an exact ASCII SemVer value")
        } finally {
            versionFile.writeText(originalVersion)
        }
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

    private fun runGradleAndFail(vararg arguments: String): String =
        GradleRunner.create()
            .withProjectDir(rootDirectory.toFile())
            .withArguments(
                "--gradle-user-home",
                Path.of(System.getProperty("user.home"), ".gradle").toString(),
                "--stacktrace",
                *arguments,
            )
            .buildAndFail()
            .output
}
