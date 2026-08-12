package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestCanonicalTest {
    @Test
    fun `encodes the complete packset contract as canonical sorted UTF-8 JSON`() {
        val encoded = PackSetManifestJson.encode(sampleManifest())

        assertEquals(expectedJson(), encoded.toString(Charsets.UTF_8))
    }

    @Test
    fun `decode validates the exact artifact bytes instead of trusting manifest metadata`() {
        val directory = Files.createTempDirectory("manifest-artifacts-")
        try {
            val artifacts = sampleArtifacts(directory)
            val result =
                PackSetManifestJson.decodeAndValidate(
                    PackSetManifestJson.encode(matchingManifest(artifacts)),
                    artifacts,
                )

            assertTrue(result.isValid, result.problems.joinToString())
            assertEquals(matchingManifest(artifacts), result.manifest)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

internal fun sampleManifest(): PackSetManifest =
    PackSetManifest(
        version = "0.1.0",
        minecraft = MinecraftManifest("26.2", 88),
        catalog =
            CatalogManifest(
                "grounds:resourcepacks",
                "0.1.0",
                "gg.grounds:resourcepacks-catalog:0.1.0",
                "grounds-resourcepacks-catalog-0.1.0.jar",
                "a".repeat(64),
                3,
            ),
        packs =
            listOf(
                PackManifest(
                    0,
                    "content",
                    "grounds-content",
                    PackSetConstants.contentUuid,
                    true,
                    "https://cdn.grounds.gg/resourcepacks/content/${"b".repeat(40)}.zip",
                    "b".repeat(40),
                    "c".repeat(64),
                    4,
                    88,
                ),
                PackManifest(
                    1,
                    "platform",
                    "grounds-platform",
                    PackSetConstants.platformUuid,
                    true,
                    "https://cdn.grounds.gg/resourcepacks/platform/${"d".repeat(40)}.zip",
                    "d".repeat(40),
                    "e".repeat(64),
                    5,
                    88,
                ),
            ),
        provenance = ProvenanceManifest("groundsgg/resourcepacks", "f".repeat(40), "v0.1.0"),
    )

internal fun sampleArtifacts(directory: java.nio.file.Path): ManifestArtifacts {
    val catalog = directory.resolve("grounds-resourcepacks-catalog-0.1.0.jar")
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

internal fun matchingManifest(artifacts: ManifestArtifacts): PackSetManifest {
    val catalog = ArtifactDigests.readRegularFile(artifacts.catalog)
    val content = ArtifactDigests.readRegularFile(artifacts.packs.getValue(PackRole.CONTENT))
    val platform = ArtifactDigests.readRegularFile(artifacts.packs.getValue(PackRole.PLATFORM))
    return sampleManifest()
        .copy(
            catalog = sampleManifest().catalog.copy(sha256 = catalog.sha256, size = catalog.size),
            packs =
                listOf(
                    sampleManifest()
                        .packs[0]
                        .copy(
                            sha1 = content.sha1,
                            sha256 = content.sha256,
                            size = content.size,
                            url = "https://cdn.grounds.gg/resourcepacks/content/${content.sha1}.zip",
                        ),
                    sampleManifest()
                        .packs[1]
                        .copy(
                            sha1 = platform.sha1,
                            sha256 = platform.sha256,
                            size = platform.size,
                            url =
                                "https://cdn.grounds.gg/resourcepacks/platform/${platform.sha1}.zip",
                        ),
                ),
        )
}

private fun expectedJson(): String =
    """
    {
      "catalog": {
        "coordinate": "gg.grounds:resourcepacks-catalog:0.1.0",
        "file": "grounds-resourcepacks-catalog-0.1.0.jar",
        "id": "grounds:resourcepacks",
        "sha256": "${"a".repeat(64)}",
        "size": 3,
        "version": "0.1.0"
      },
      "id": "grounds:global",
      "minecraft": {
        "resourcePackFormat": 88,
        "version": "26.2"
      },
      "packs": [
        {
          "id": "grounds-content",
          "order": 0,
          "required": true,
          "resourcePackFormat": 88,
          "role": "content",
          "sha1": "${"b".repeat(40)}",
          "sha256": "${"c".repeat(64)}",
          "size": 4,
          "url": "https://cdn.grounds.gg/resourcepacks/content/${"b".repeat(40)}.zip",
          "uuid": "44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"
        },
        {
          "id": "grounds-platform",
          "order": 1,
          "required": true,
          "resourcePackFormat": 88,
          "role": "platform",
          "sha1": "${"d".repeat(40)}",
          "sha256": "${"e".repeat(64)}",
          "size": 5,
          "url": "https://cdn.grounds.gg/resourcepacks/platform/${"d".repeat(40)}.zip",
          "uuid": "8da7cffe-bb04-55e0-9868-7789ce5de362"
        }
      ],
      "provenance": {
        "commit": "${"f".repeat(40)}",
        "repository": "groundsgg/resourcepacks",
        "tag": "v0.1.0"
      },
      "schemaVersion": 1,
      "version": "0.1.0"
    }

    """
        .trimIndent()
