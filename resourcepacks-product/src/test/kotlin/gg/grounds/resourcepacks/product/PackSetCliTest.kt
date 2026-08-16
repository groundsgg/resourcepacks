package gg.grounds.resourcepacks.product

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackSetCliTest {
    @Test
    fun `real cli failures have exit two stable stderr and no stack trace`() {
        val cases =
            listOf(
                emptyList<String>() to "Missing required release arguments.",
                listOf("--unknown", "value") to "Unknown argument: --unknown",
                listOf("--version") to "Missing value for --version",
                listOf("positional") to "Unknown argument: positional",
            )
        cases.forEach { (args, diagnostic) ->
            val result = runCli(args)
            assertEquals(2, result.exitCode, args.toString())
            assertEquals("packset: $diagnostic\n", result.stderr, args.toString())
            assertFalse("Exception" in result.stderr)
            assertFalse("\tat " in result.stderr)
            assertEquals("", result.stdout)
        }
    }

    @Test
    fun `real cli publishes exactly four files`() {
        val parent = Files.createTempDirectory("packset-real-cli-")
        val output = parent.resolve("release")
        try {
            val result =
                runCli(
                    listOf(
                        "--publication-type",
                        "release",
                        "--publication-id",
                        "v${ReleaseTestContext.version}",
                        "--version",
                        ReleaseTestContext.version,
                        "--commit",
                        "b".repeat(40),
                        "--output",
                        output.toString(),
                    )
                )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("", result.stderr)
            assertEquals("", result.stdout)
            assertEquals(4L, Files.list(output).use { it.count() })
            assertTrue(Files.isRegularFile(output.resolve("manifest.json")))
        } finally {
            deleteCliTree(parent)
        }
    }

    private fun runCli(args: List<String>): CliResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath =
            cliCatalogJar().toString() + File.pathSeparator + System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                    listOf(
                        java,
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        classpath,
                        "gg.grounds.resourcepacks.product.MainKt",
                    ) + args
                )
                .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return CliResult(process.waitFor(), stdout, stderr)
    }
}

private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

private fun cliCatalogJar(): Path = ReleaseTestContext.catalogJar

private fun deleteCliTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
