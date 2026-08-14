package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StreamingReleaseTest {
    @Test
    fun `pack-limit source boundary streams successfully under a small heap`() {
        withSparseFixture(PACK_LIMIT_SOURCE_BOUNDARY) { source, catalog, output ->
            val result = runFixture("exact", source, catalog, output)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("OK\n", result.stdout)
            assertEquals(4L, Files.list(output).use { it.count() })
        }
    }

    @Test
    fun `pack-limit source max plus one fails with a controlled limit problem under a small heap`() {
        withSparseFixture(PACK_LIMIT_SOURCE_BOUNDARY + 1L) { source, catalog, output ->
            val result = runFixture("overflow", source, catalog, output)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("LIMIT\n", result.stdout)
            assertFalse(Files.exists(output))
        }
    }

    private fun withSparseFixture(size: Long, block: (Path, Path, Path) -> Unit) {
        val root = Files.createTempDirectory("packset-streaming-")
        try {
            val source = root.resolve("large-source.bin")
            Files.newByteChannel(source, WRITE, CREATE_NEW).use { channel ->
                channel.position(size - 1L)
                channel.write(ByteBuffer.wrap(byteArrayOf(0)))
            }
            block(source, streamingCatalogJar(), root.resolve("release"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun runFixture(mode: String, source: Path, catalog: Path, output: Path): FixtureResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath =
            catalog.toString() + File.pathSeparator + System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                    java,
                    "-Xmx48m",
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    StreamingReleaseFixture::class.java.name,
                    mode,
                    source.toString(),
                    catalog.toString(),
                    output.toString(),
                )
                .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return FixtureResult(process.waitFor(), stdout, stderr)
    }
}

internal object StreamingReleaseFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val source = Path.of(args[1])
        val catalog = Path.of(args[2])
        val output = Path.of(args[3])
        val platform =
            ProductGraph.packs
                .last()
                .copy(
                    definition =
                        ProductGraph.packs.last().definition.copy(description = "x", icon = null),
                    contributions =
                        listOf(
                            PackContribution(
                                ContributionId.of("grounds:streaming-boundary"),
                                PackFormatRange(PackSetConstants.FORMAT, PackSetConstants.FORMAT),
                                listOf(
                                    PackEntry.file(
                                        "assets/grounds/streaming/large-source.bin",
                                        source,
                                    )
                                ),
                            )
                        ),
                )
        try {
            PackSetBuilder.build(
                ReleaseInputs("0.0.0", "a".repeat(40), "v0.0.0", output),
                catalog,
                PackSetBuilderHooks(),
                listOf(ProductGraph.packs.first(), platform),
            )
            check(mode == "exact") { "Overflow fixture unexpectedly published." }
            println("OK")
        } catch (failure: ProductValidationException) {
            check(mode == "overflow") { "Exact fixture hit a limit: ${failure.result.problems}" }
            check(
                failure.result.problems.any {
                    it.code == ProductProblemCode.UNCOMPRESSED_SIZE_LIMIT_EXCEEDED
                }
            ) {
                "Overflow fixture returned the wrong problem: ${failure.result.problems}"
            }
            println("LIMIT")
        }
    }
}

private data class FixtureResult(val exitCode: Int, val stdout: String, val stderr: String)

// Exact UTF-8 bytes of the deterministic empty-description pack.mcmeta are included by the
// authoritative builder limit, leaving this many bytes for the only contribution source.
private val PACK_LIMIT_SOURCE_BOUNDARY =
    PackSetConstants.platformLimits.maxUncompressedBytes!! - 78L

private fun streamingCatalogJar(): Path =
    generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile }
        .resolve("resourcepacks-catalog/build/libs/resourcepacks-catalog-0.0.0.jar")
