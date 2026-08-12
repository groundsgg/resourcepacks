package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestRoundTripTest {
    @Test
    fun `decode validate encode is semantic and byte idempotent`() {
        val directory = Files.createTempDirectory("manifest-round-trip-")
        try {
            val artifacts = sampleArtifacts(directory)
            val initial = PackSetManifestJson.encode(matchingManifest(artifacts))
            val decoded = PackSetManifestJson.decodeAndValidate(initial, artifacts)

            assertTrue(decoded.isValid, decoded.problems.joinToString())
            assertEquals(initial.toList(), PackSetManifestJson.encode(decoded.manifest!!).toList())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
