package gg.grounds.resourcepacks.catalog

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    fun `the build exposes exactly the catalog contract and product projects`() {
        val output = runGradle("projects")

        val projectNames =
            Regex("Project '(:[^']*)'").findAll(output).map { it.groupValues[1] }.toSet()

        assertEquals(
            setOf(":resourcepacks-catalog", ":resourcepacks-contract", ":resourcepacks-product"),
            projectNames,
        )
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
        val fixtureDirectory = Files.createTempDirectory("resourcepacks-catalog-runtime-fixture")

        try {
            copyFixtureFile("settings.gradle.kts", fixtureDirectory)
            copyFixtureFile("build.gradle.kts", fixtureDirectory)
            copyFixtureFile("version.txt", fixtureDirectory)
            copyFixtureFile("resourcepacks-catalog/build.gradle.kts", fixtureDirectory)
            copyFixtureFile("resourcepacks-catalog/gradle.lockfile", fixtureDirectory)
            copyFixtureFile("resourcepacks-contract/build.gradle.kts", fixtureDirectory)
            copyFixtureFile("resourcepacks-contract/gradle.lockfile", fixtureDirectory)
            copyFixtureFile("resourcepacks-product/build.gradle.kts", fixtureDirectory)
            copyFixtureFile("resourcepacks-product/gradle.lockfile", fixtureDirectory)

            val buildFile = fixtureDirectory.resolve("resourcepacks-catalog/build.gradle.kts")
            buildFile.writeText(
                buildFile.readText() +
                    "\n dependencies { runtimeOnly(\"gg.grounds:resource-pack-testkit:0.1.0\") }\n"
            )

            runGradle(fixtureDirectory, ":resourcepacks-catalog:dependencies", "--write-locks")

            val failure =
                runGradleAndFail(
                    fixtureDirectory,
                    ":resourcepacks-catalog:verifyCatalogRuntimeClasspath",
                )

            assertContains(failure, "Forbidden catalog runtime components:")
            assertContains(failure, "gg.grounds:resource-pack-testkit:0.1.0")
        } finally {
            fixtureDirectory.toFile().deleteRecursively()
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
        val fixtureDirectory = Files.createTempDirectory("resourcepacks-version-fixture")

        try {
            copyFixtureFile("settings.gradle.kts", fixtureDirectory)
            copyFixtureFile("build.gradle.kts", fixtureDirectory)
            Files.createDirectories(fixtureDirectory.resolve("resourcepacks-catalog"))
            Files.createDirectories(fixtureDirectory.resolve("resourcepacks-contract"))
            Files.createDirectories(fixtureDirectory.resolve("resourcepacks-product"))
            fixtureDirectory.resolve("version.txt").writeText(contents)

            val failure = runGradleAndFail(fixtureDirectory, "help")
            assertContains(failure, "version.txt must contain an exact ASCII SemVer value")
        } finally {
            fixtureDirectory.toFile().deleteRecursively()
        }
    }

    private fun runGradle(vararg arguments: String): String = runGradle(rootDirectory, *arguments)

    private fun runGradle(projectDirectory: Path, vararg arguments: String): String =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
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
        runGradleAndFail(rootDirectory, *arguments)

    private fun runGradleAndFail(projectDirectory: Path, vararg arguments: String): String =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withArguments(
                "--gradle-user-home",
                Path.of(System.getProperty("user.home"), ".gradle").toString(),
                "--stacktrace",
                *arguments,
            )
            .buildAndFail()
            .output

    private fun copyFixtureFile(relativePath: String, fixtureDirectory: Path) {
        val target = fixtureDirectory.resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.copy(rootDirectory.resolve(relativePath), target, StandardCopyOption.REPLACE_EXISTING)
    }
}
