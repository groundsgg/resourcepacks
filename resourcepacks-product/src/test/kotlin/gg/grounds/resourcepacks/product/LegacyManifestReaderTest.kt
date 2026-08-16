package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.contract.ManifestDecodeResult
import gg.grounds.resourcepacks.contract.PackSetContractJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyManifestReaderTest {
    @Test
    fun `recorded schema one publications are available only to the dedicated legacy verifier`() {
        val directory = Files.createTempDirectory("legacy-manifest-")
        try {
            listOf("v0.1.0", "v0.1.1").forEach { release ->
                val artifacts =
                    legacyArtifacts(directory.resolve(release), release.removePrefix("v"))
                val bytes = fixture("legacy/$release-manifest.json")

                assertTrue(
                    LegacyManifestReader.decodeAndValidate(bytes, artifacts).isValid,
                    release,
                )
                assertFalse(
                    PackSetContractJson.decodeManifest(bytes) is ManifestDecodeResult.Success,
                    release,
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { name }.readBytes()

    private fun legacyArtifacts(directory: Path, version: String): ManifestArtifacts {
        Files.createDirectories(directory)
        val catalog = directory.resolve("grounds-resourcepacks-catalog-$version.jar")
        val content = directory.resolve("13a936c521299ecb9702d0b63e6458171f926bba.zip")
        val platform = directory.resolve("175cf2b94164a8a07fdf8d00cf144404c763199e.zip")
        Files.write(catalog, byteArrayOf(1, 2, 3))
        Files.write(content, byteArrayOf(4, 5, 6, 7))
        Files.write(platform, byteArrayOf(8, 9, 10, 11, 12))
        return ManifestArtifacts(
            catalog,
            mapOf(PackRole.CONTENT to content, PackRole.PLATFORM to platform),
        )
    }
}
