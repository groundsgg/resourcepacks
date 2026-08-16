package gg.grounds.resourcepacks.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PackSetContractJsonTest {
    @Test
    fun `decodes the exact canonical schema two release manifest`() {
        val result = PackSetContractJson.decodeManifest(releaseManifest.toByteArray())

        val decoded = assertIs<ManifestDecodeResult.Success>(result).manifest
        assertEquals(2, decoded.schemaVersion)
        assertEquals("grounds-global", decoded.packSet)
        assertEquals(ManifestPublication(PublicationType.RELEASE, "v0.1.2"), decoded.publication)
        assertEquals("1969c1e6a3799e976de46eab019a16b2ee257ea7", decoded.provenance.commit)
        assertEquals(
            "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-content-pack-v0.1.2.zip",
            decoded.packs.first().url,
        )
    }

    @Test
    fun `rejects noncanonical publication URL roots`() {
        val result =
            PackSetContractJson.decodeManifest(
                releaseManifest.replace("releases/v0.1.2", "releases/v9.9.9").toByteArray()
            )

        val failure = assertIs<ManifestDecodeResult.Failure>(result)
        assertEquals(
            listOf(
                ManifestDiagnostic(
                    "/packs/0/url",
                    ManifestDiagnosticCode.INVALID_VALUE,
                    "Pack URL mismatch.",
                ),
                ManifestDiagnostic(
                    "/packs/1/url",
                    ManifestDiagnosticCode.INVALID_VALUE,
                    "Pack URL mismatch.",
                ),
            ),
            failure.diagnostics,
        )
    }

    @Test
    fun `decodes the exact canonical schema two build manifest`() {
        val result = PackSetContractJson.decodeManifest(buildManifest.toByteArray())

        val decoded = assertIs<ManifestDecodeResult.Success>(result).manifest
        assertEquals(
            ManifestPublication(PublicationType.BUILD, "1969c1e6a3799e976de46eab019a16b2ee257ea7"),
            decoded.publication,
        )
        assertEquals("0.0.0-edge.42.g1969c1e6a379", decoded.version)
        assertEquals(
            "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/1969c1e6a3799e976de46eab019a16b2ee257ea7/grounds-platform-pack-edge-1969c1e6a379.zip",
            decoded.packs.last().url,
        )
    }

    @Test
    fun `manifest value strings accept the limit and reject one additional character`() {
        val maximum = "x".repeat(ManifestParserLimits.MAX_STRING)
        val overlong = "x".repeat(ManifestParserLimits.MAX_STRING + 1)

        assertDiagnostics(
            releaseManifest.replace(
                "\"schemaVersion\": 2",
                "\"padding\": \"$maximum\",\n  \"schemaVersion\": 2",
            ),
            listOf(
                ManifestDiagnostic(
                    "/padding",
                    ManifestDiagnosticCode.UNKNOWN_FIELD,
                    "Unknown field.",
                )
            ),
            "maximum value string",
        )
        assertDiagnostics(
            releaseManifest.replace(
                "\"schemaVersion\": 2",
                "\"padding\": \"$overlong\",\n  \"schemaVersion\": 2",
            ),
            listOf(
                ManifestDiagnostic(
                    "/",
                    ManifestDiagnosticCode.MALFORMED_JSON,
                    "String exceeds ${ManifestParserLimits.MAX_STRING} characters.",
                )
            ),
            "overlong value string",
        )
    }

    @Test
    fun `manifest integer tokens accept the limit and reject one additional character`() {
        val maximum = "1".repeat(ManifestParserLimits.MAX_NUMBER)
        val overlong = "1".repeat(ManifestParserLimits.MAX_NUMBER + 1)

        assertDiagnostics(
            releaseManifest.replace(
                "\"schemaVersion\": 2",
                "\"padding\": $maximum,\n  \"schemaVersion\": 2",
            ),
            listOf(
                ManifestDiagnostic(
                    "/padding",
                    ManifestDiagnosticCode.UNKNOWN_FIELD,
                    "Unknown field.",
                )
            ),
            "maximum integer token",
        )
        assertDiagnostics(
            releaseManifest.replace(
                "\"schemaVersion\": 2",
                "\"padding\": $overlong,\n  \"schemaVersion\": 2",
            ),
            listOf(
                ManifestDiagnostic(
                    "/",
                    ManifestDiagnosticCode.MALFORMED_JSON,
                    "Number exceeds ${ManifestParserLimits.MAX_NUMBER} characters.",
                )
            ),
            "overlong integer token",
        )
    }

    @Test
    fun `build packs may reference a canonical historical build root with matching role suffix`() {
        val old = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val historical =
            buildManifest
                .replace(
                    "1969c1e6a3799e976de46eab019a16b2ee257ea7/grounds-content-pack-edge-1969c1e6a379",
                    "$old/grounds-content-pack-edge-aaaaaaaaaaaa",
                )
                .replace(
                    "1969c1e6a3799e976de46eab019a16b2ee257ea7/grounds-platform-pack-edge-1969c1e6a379",
                    "$old/grounds-platform-pack-edge-aaaaaaaaaaaa",
                )
        assertIs<ManifestDecodeResult.Success>(
            PackSetContractJson.decodeManifest(historical.toByteArray())
        )
    }

    @Test
    fun `build publication cannot name release-root pack objects`() {
        val malformed =
            buildManifest.replace(
                "builds/1969c1e6a3799e976de46eab019a16b2ee257ea7",
                "releases/1969c1e6a3799e976de46eab019a16b2ee257ea7",
            )

        val failure =
            assertIs<ManifestDecodeResult.Failure>(
                PackSetContractJson.decodeManifest(malformed.toByteArray())
            )
        assertEquals(
            listOf("/packs/0/url", "/packs/1/url"),
            failure.diagnostics.map(ManifestDiagnostic::pointer),
        )
    }

    @Test
    fun `hostile URL forms are rejected at every pack boundary`() {
        listOf(
                "http://cdn.grounds.gg" to "scheme",
                "https://user@cdn.grounds.gg" to "userinfo",
                "https://cdn.grounds.gg:443" to "port",
                "https://other.example" to "host",
            )
            .forEach { (origin, name) ->
                val hostile = releaseManifest.replace("https://cdn.grounds.gg", origin)
                assertDiagnostics(
                    hostile,
                    listOf(
                        diagnostic("/packs/0/url", "Pack URL mismatch."),
                        diagnostic("/packs/1/url", "Pack URL mismatch."),
                    ),
                    name,
                )
            }
    }

    @Test
    fun `release URL matrix rejects hostile object locations with exact diagnostics`() {
        listOf(
                Triple(
                    "traversal",
                    "grounds-content-pack-v0.1.2.zip",
                    "../grounds-content-pack-v0.1.2.zip",
                ),
                Triple(
                    "encoded separator",
                    "grounds-content-pack-v0.1.2.zip",
                    "%2fgrounds-content-pack-v0.1.2.zip",
                ),
                Triple(
                    "query",
                    "grounds-content-pack-v0.1.2.zip",
                    "grounds-content-pack-v0.1.2.zip?download=1",
                ),
                Triple(
                    "fragment",
                    "grounds-content-pack-v0.1.2.zip",
                    "grounds-content-pack-v0.1.2.zip#part",
                ),
                Triple(
                    "wrong role",
                    "grounds-content-pack-v0.1.2.zip",
                    "grounds-platform-pack-v0.1.2.zip",
                ),
                Triple(
                    "wrong filename",
                    "grounds-content-pack-v0.1.2.zip",
                    "grounds-content-pack-v0.1.2.jar",
                ),
            )
            .forEach { (name, from, to) ->
                assertDiagnostics(
                    releaseManifest.replaceFirst(from, to),
                    listOf(diagnostic("/packs/0/url", "Pack URL mismatch.")),
                    name,
                )
            }
    }

    @Test
    fun `release publication and immutable pack identity matrix is exact`() {
        val cases =
            listOf(
                Triple(
                    "wrong pack set",
                    releaseManifest.replace("grounds-global", "other-packset"),
                    listOf(
                        diagnostic("/packSet", "PackSet mismatch."),
                        diagnostic("/packs/0/url", "Pack URL mismatch."),
                        diagnostic("/packs/1/url", "Pack URL mismatch."),
                    ),
                ),
                Triple(
                    "wrong release publication",
                    releaseManifest.replaceFirst("\"id\": \"v0.1.2\"", "\"id\": \"v0.1.3\""),
                    listOf(
                        diagnostic("/catalog/file", "Catalog filename mismatch."),
                        diagnostic("/packs/0/url", "Pack URL mismatch."),
                        diagnostic("/packs/1/url", "Pack URL mismatch."),
                        diagnostic("/publication", "Release publication mismatch."),
                    ),
                ),
                Triple(
                    "wrong pack role",
                    releaseManifest.replaceFirst("\"role\": \"content\"", "\"role\": \"platform\""),
                    listOf(diagnostic("/packs/0", "Pack metadata mismatch.")),
                ),
                Triple(
                    "wrong pack name",
                    releaseManifest.replaceFirst(
                        "\"id\": \"grounds-content\"",
                        "\"id\": \"grounds-other__\"",
                    ),
                    listOf(diagnostic("/packs/0", "Pack metadata mismatch.")),
                ),
                Triple(
                    "wrong pack UUID",
                    releaseManifest.replaceFirst(
                        "44591d5b-71f5-5c2a-a5b2-d3ee7be47e53",
                        "00000000-0000-0000-0000-000000000000",
                    ),
                    listOf(diagnostic("/packs/0", "Pack metadata mismatch.")),
                ),
            )
        cases.forEach { (name, bytes, expected) -> assertDiagnostics(bytes, expected, name) }
    }

    @Test
    fun `build publication and commit identity matrix is exact`() {
        val commit = "1969c1e6a3799e976de46eab019a16b2ee257ea7"
        val cases =
            listOf(
                Triple(
                    "wrong build publication",
                    buildManifest.replaceFirst(
                        "\"id\": \"$commit\"",
                        "\"id\": \"${"a".repeat(40)}\"",
                    ),
                    listOf(diagnostic("/publication", "Build publication mismatch.")),
                ),
                Triple(
                    "publication commit mismatch",
                    buildManifest.replaceFirst(
                        "\"commit\": \"$commit\"",
                        "\"commit\": \"${"a".repeat(40)}\"",
                    ),
                    listOf(
                        diagnostic("/catalog/file", "Catalog filename mismatch."),
                        diagnostic("/publication", "Build publication mismatch."),
                        diagnostic("/version", "Build version commit mismatch."),
                    ),
                ),
                Triple(
                    "version commit mismatch",
                    buildManifest.replace(
                        "0.0.0-edge.42.g1969c1e6a379",
                        "0.0.0-edge.42.g000000000000",
                    ),
                    listOf(diagnostic("/version", "Build version commit mismatch.")),
                ),
                Triple(
                    "release root under build",
                    buildManifest.replace("/builds/$commit/", "/releases/$commit/"),
                    listOf(
                        diagnostic("/packs/0/url", "Pack URL mismatch."),
                        diagnostic("/packs/1/url", "Pack URL mismatch."),
                    ),
                ),
                Triple(
                    "historical build filename must bind its own root commit",
                    buildManifest.replace(
                        "grounds-content-pack-edge-1969c1e6a379.zip",
                        "grounds-content-pack-edge-aaaaaaaaaaaa.zip",
                    ),
                    listOf(diagnostic("/packs/0/url", "Pack URL mismatch.")),
                ),
            )
        cases.forEach { (name, bytes, expected) -> assertDiagnostics(bytes, expected, name) }
    }

    private fun assertDiagnostics(bytes: String, expected: List<ManifestDiagnostic>, name: String) {
        val failure =
            assertIs<ManifestDecodeResult.Failure>(
                PackSetContractJson.decodeManifest(bytes.toByteArray()),
                name,
            )
        assertEquals(expected, failure.diagnostics, name)
    }

    private fun diagnostic(pointer: String, message: String) =
        ManifestDiagnostic(pointer, ManifestDiagnosticCode.INVALID_VALUE, message)

    private companion object {
        val releaseManifest =
            """
            {
              "catalog": {
                "coordinate": "gg.grounds:resourcepacks-catalog:0.1.2",
                "file": "grounds-resourcepack-catalog-v0.1.2.jar",
                "id": "grounds:resourcepacks",
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "size": 3,
                "version": "0.1.2"
              },
              "minecraft": {
                "resourcePackFormat": 88,
                "version": "26.2"
              },
              "packSet": "grounds-global",
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
                  "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-content-pack-v0.1.2.zip",
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
                  "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-platform-pack-v0.1.2.zip",
                  "uuid": "8da7cffe-bb04-55e0-9868-7789ce5de362"
                }
              ],
              "provenance": {
                "commit": "1969c1e6a3799e976de46eab019a16b2ee257ea7",
                "repository": "groundsgg/resourcepacks"
              },
              "publication": {
                "id": "v0.1.2",
                "type": "release"
              },
              "schemaVersion": 2,
              "version": "0.1.2"
            }
            """
                .trimIndent() + "\n"

        val buildManifest =
            releaseManifest
                .replace("0.1.2", "0.0.0-edge.42.g1969c1e6a379")
                .replace(
                    "releases/v0.0.0-edge.42.g1969c1e6a379",
                    "builds/1969c1e6a3799e976de46eab019a16b2ee257ea7",
                )
                .replace(
                    "grounds-content-pack-v0.0.0-edge.42.g1969c1e6a379",
                    "grounds-content-pack-edge-1969c1e6a379",
                )
                .replace(
                    "grounds-platform-pack-v0.0.0-edge.42.g1969c1e6a379",
                    "grounds-platform-pack-edge-1969c1e6a379",
                )
                .replace(
                    "grounds-resourcepack-catalog-v0.0.0-edge.42.g1969c1e6a379",
                    "grounds-resourcepack-catalog-edge-1969c1e6a379",
                )
                .replace(
                    "\"id\": \"v0.0.0-edge.42.g1969c1e6a379\",\n    \"type\": \"release\"",
                    "\"id\": \"1969c1e6a3799e976de46eab019a16b2ee257ea7\",\n    \"type\": \"build\"",
                )
    }
}
