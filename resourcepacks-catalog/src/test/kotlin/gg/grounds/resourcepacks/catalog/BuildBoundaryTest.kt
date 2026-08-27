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
    fun `the build exposes the current root project set`() {
        val output = runGradle("projects")

        assertEquals(projectSet(), projectNames(output))
    }

    @Test
    fun `testkit fixtures preserve every root project`() {
        val fixtureDirectory = Files.createTempDirectory("resourcepacks-project-fixture")

        try {
            copyCompleteFixture(fixtureDirectory)

            assertEquals(projectSet(), projectNames(runGradle(fixtureDirectory, "projects")))
        } finally {
            fixtureDirectory.toFile().deleteRecursively()
        }
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
            copyCompleteFixture(fixtureDirectory)

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

    @Test
    fun `the build accepts only an exact commit bound Edge version outside version txt`() {
        val commit = "44c012405d70a0c819c220ec1efd9ab0caea2bd7"
        val edgeVersion = "0.0.0-edge.999.g44c012405d70"

        runGradle(
            "help",
            "-PpackSetVersion=$edgeVersion",
            "-PpublicationType=build",
            "-PpublicationId=$commit",
            "-PprovenanceCommit=$commit",
        )

        listOf(
                arrayOf("-PpackSetVersion=$edgeVersion"),
                arrayOf(
                    "-PpackSetVersion=0.0.0-edge.999.g000000000000",
                    "-PpublicationType=build",
                    "-PpublicationId=$commit",
                    "-PprovenanceCommit=$commit",
                ),
                arrayOf(
                    "-PpackSetVersion=$edgeVersion",
                    "-PpublicationType=release",
                    "-PpublicationId=$commit",
                    "-PprovenanceCommit=$commit",
                ),
            )
            .forEach { arguments ->
                assertContains(
                    runGradleAndFail("help", *arguments),
                    "packSetVersion must equal version.txt or an exact build identity.",
                )
            }
    }

    @Test
    fun `catalog generation tracks the exact selected release or Edge version`() {
        val commit = "44c012405d70a0c819c220ec1efd9ab0caea2bd7"
        val edgeVersion = "0.0.0-edge.999.g44c012405d70"
        val generated =
            rootDirectory.resolve(
                "resourcepacks-catalog/build/generated/sources/catalog/kotlin/" +
                    "gg/grounds/resourcepacks/catalog/CatalogBuildInfo.kt"
            )
        val generatedResource =
            rootDirectory.resolve(
                "resourcepacks-catalog/build/generated/resources/catalog/" +
                    "gg/grounds/resourcepacks/catalog/catalog-version.txt"
            )

        runGradle(
            ":resourcepacks-catalog:generateCatalogBuildInfo",
            ":resourcepacks-catalog:generateCatalogVersionResource",
        )
        assertContains(generated.readText(), rootDirectory.resolve("version.txt").readText().trim())
        assertEquals(rootDirectory.resolve("version.txt").readText(), generatedResource.readText())

        runGradle(
            ":resourcepacks-catalog:generateCatalogBuildInfo",
            ":resourcepacks-catalog:generateCatalogVersionResource",
            "-PpackSetVersion=$edgeVersion",
            "-PpublicationType=build",
            "-PpublicationId=$commit",
            "-PprovenanceCommit=$commit",
        )
        assertContains(generated.readText(), edgeVersion)
        assertEquals("$edgeVersion\n", generatedResource.readText())
    }

    private fun assertInvalidVersion(contents: String) {
        val fixtureDirectory = Files.createTempDirectory("resourcepacks-version-fixture")

        try {
            copyCompleteFixture(fixtureDirectory)
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

    private fun copyCompleteFixture(fixtureDirectory: Path) {
        listOf("settings.gradle.kts", "build.gradle.kts", "version.txt").forEach {
            copyFixtureFile(it, fixtureDirectory)
        }
        projectSet().forEach { project ->
            val directory = project.removePrefix(":")
            copyFixtureFile("$directory/build.gradle.kts", fixtureDirectory)
            copyFixtureFile("$directory/gradle.lockfile", fixtureDirectory)
        }
    }

    private fun projectNames(output: String): Set<String> =
        Regex("Project '(:[^']*)'").findAll(output).map { it.groupValues[1] }.toSet()

    private fun projectSet() =
        setOf(
            ":resourcepacks-catalog",
            ":resourcepacks-client",
            ":resourcepacks-contract",
            ":resourcepacks-product",
        )
}
