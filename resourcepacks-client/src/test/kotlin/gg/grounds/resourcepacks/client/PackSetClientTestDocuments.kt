package gg.grounds.resourcepacks.client

import java.security.MessageDigest

internal data class PackSetClientTestDocuments(val channel: ByteArray, val manifest: ByteArray)

internal fun clientDocuments(
    source: PackSetSource,
    sequence: Long = 1,
): PackSetClientTestDocuments {
    val root = "${source.baseUri}/resourcepacks/packsets/${source.packSet}/releases/v1.2.3"
    val manifest =
        """
        {
          "catalog": {
            "coordinate": "gg.grounds:resourcepacks-catalog:1.2.3",
            "file": "grounds-resourcepack-catalog-v1.2.3.jar",
            "id": "grounds:resourcepacks",
            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "size": 3,
            "version": "1.2.3"
          },
          "minecraft": {
            "resourcePackFormat": 88,
            "version": "26.2"
          },
          "packSet": "${source.packSet}",
          "packs": [
            {
              "id": "grounds-content",
              "order": 0,
              "required": true,
              "resourcePackFormat": 88,
              "role": "content",
              "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
              "size": 4,
              "url": "$root/grounds-content-pack-v1.2.3.zip",
              "uuid": "44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"
            },
            {
              "id": "grounds-platform",
              "order": 1,
              "required": true,
              "resourcePackFormat": 88,
              "role": "platform",
              "sha1": "dddddddddddddddddddddddddddddddddddddddd",
              "sha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
              "size": 5,
              "url": "$root/grounds-platform-pack-v1.2.3.zip",
              "uuid": "8da7cffe-bb04-55e0-9868-7789ce5de362"
            }
          ],
          "provenance": {
            "commit": "1969c1e6a3799e976de46eab019a16b2ee257ea7",
            "repository": "groundsgg/resourcepacks"
          },
          "publication": {
            "id": "v1.2.3",
            "type": "release"
          },
          "schemaVersion": 2,
          "version": "1.2.3"
        }
        """
            .trimIndent()
            .plus("\n")
            .encodeToByteArray()
    val channel =
        """
        {
          "channel": "stable",
          "manifest": {
            "sha256": "${sha256(manifest)}",
            "size": ${manifest.size},
            "url": "$root/manifest.json"
          },
          "packSet": "${source.packSet}",
          "schemaVersion": 2,
          "sequence": $sequence,
          "target": {
            "id": "v1.2.3",
            "type": "release"
          }
        }
        """
            .trimIndent()
            .plus("\n")
            .encodeToByteArray()
    return PackSetClientTestDocuments(channel, manifest)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
