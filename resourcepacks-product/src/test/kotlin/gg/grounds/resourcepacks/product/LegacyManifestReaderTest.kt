package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.contract.ManifestDecodeResult
import gg.grounds.resourcepacks.contract.PackSetContractJson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyManifestReaderTest {
    @Test
    fun `recorded schema one publications are available only to the dedicated legacy verifier`() {
        val directory = Files.createTempDirectory("legacy-manifest-")
        try {
            mapOf(
                    "v0.1.0" to "6896b8824f61d0bb6973fa941d4200a4df5054090d1fe803f7b8333d71bd1f60",
                    "v0.1.1" to "06e3ac577eddd68e913240aedff58c7d8ac5c595f23454d4174e5f371db9dbb6",
                )
                .forEach { (release, expectedSha256) ->
                    val artifacts =
                        legacyArtifacts(directory.resolve(release), release.removePrefix("v"))
                    val bytes = fixture("legacy/$release-manifest.json")
                    val before = bytes.copyOf()

                    assertEquals(expectedSha256, sha256(bytes), release)

                    assertTrue(
                        LegacyManifestReader.decodeAndValidate(bytes, artifacts).isValid,
                        release,
                    )
                    assertFalse(
                        PackSetContractJson.decodeManifest(bytes) is ManifestDecodeResult.Success,
                        release,
                    )
                    assertContentEquals(
                        before,
                        bytes,
                        "$release fixture must remain unchanged by legacy verification",
                    )
                }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { name }.readBytes()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
