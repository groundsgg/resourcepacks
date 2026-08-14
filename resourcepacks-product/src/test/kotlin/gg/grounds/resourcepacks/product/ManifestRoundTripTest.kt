package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `manifest collections are defensive snapshots`() {
        val source = sampleManifest().packs.toMutableList()
        val manifest = sampleManifest().copy(packs = source)
        source.clear()
        assertEquals(2, manifest.packs.size)
        assertFailsWith<UnsupportedOperationException> {
            (manifest.packs as MutableList<PackManifest>).clear()
        }
    }

    @Test
    fun `artifact and result collections are defensive snapshots`() {
        val paths = mutableMapOf(PackRole.CONTENT to java.nio.file.Path.of("content.zip"))
        val artifacts = ManifestArtifacts(java.nio.file.Path.of("catalog.jar"), paths)
        paths.clear()
        assertEquals(1, artifacts.packs.size)
        assertFailsWith<UnsupportedOperationException> {
            (artifacts.packs as MutableMap<PackRole, java.nio.file.Path>).clear()
        }
        val source = mutableListOf(ManifestProblem("/x", ManifestProblemCode.INVALID_VALUE, "x"))
        val result = ManifestValidationResult(null, source)
        source.clear()
        assertEquals(1, result.problems.size)
        assertFailsWith<UnsupportedOperationException> {
            (result.problems as MutableList<ManifestProblem>).clear()
        }
    }

    @Test
    fun `rejects semantically valid noncanonical bytes and malformed UTF-8`() {
        val directory = Files.createTempDirectory("manifest-canonical-input-")
        try {
            val artifacts = sampleArtifacts(directory)
            val canonical = PackSetManifestJson.encode(matchingManifest(artifacts))
            val whitespace = canonical.toString(Charsets.UTF_8).replace("\n", "\n ").toByteArray()
            assertEquals(
                ManifestProblemCode.NON_CANONICAL_JSON,
                PackSetManifestJson.decodeAndValidate(whitespace, artifacts).problems.single().code,
            )
            assertEquals(
                ManifestProblemCode.MALFORMED_JSON,
                PackSetManifestJson.decodeAndValidate(
                        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + canonical,
                        artifacts,
                    )
                    .problems
                    .single()
                    .code,
            )
            assertEquals(
                ManifestProblemCode.MALFORMED_JSON,
                PackSetManifestJson.decodeAndValidate(
                        byteArrayOf(0xC3.toByte(), 0x28) + canonical,
                        artifacts,
                    )
                    .problems
                    .single()
                    .code,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
