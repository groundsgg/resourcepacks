package gg.grounds.resourcepacks.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChannelMutationTest {
    @Test
    fun `controlled mutations produce sorted deterministic typed diagnostics`() {
        val result =
            decode(
                stable
                    .replace("\"sequence\": 7", "\"sequence\": 0")
                    .replace("\"type\": \"release\"", "\"type\": \"build\"")
            )

        assertEquals(
            listOf(
                ChannelDiagnostic(
                    "/sequence",
                    ChannelDiagnosticCode.INVALID_VALUE,
                    "Sequence must be positive.",
                ),
                ChannelDiagnostic(
                    "/target/id",
                    ChannelDiagnosticCode.INVALID_VALUE,
                    "Build target ID must be lowercase 40-hex.",
                ),
                ChannelDiagnostic(
                    "/target/type",
                    ChannelDiagnosticCode.INVALID_VALUE,
                    "Stable channels require release targets.",
                ),
            ),
            result.diagnostics,
        )
    }

    @Test
    fun `noncanonical syntax malformed input and trailing input are rejected`() {
        listOf(
                stable.replace("  \"channel\"", " \"channel\""),
                stable.replace("\"channel\": \"stable\"", "\"channel\": \"st\\u0061ble\""),
                stable + "x",
                "\uFEFF$stable",
            )
            .forEach {
                assertIs<ChannelDecodeResult.Failure>(
                    CanonicalChannelJson.decode(it.encodeToByteArray())
                )
            }
        assertIs<ChannelDecodeResult.Failure>(
            CanonicalChannelJson.decode(byteArrayOf(0xc3.toByte()))
        )
        listOf(
                byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
                byteArrayOf(0xfe.toByte(), 0xff.toByte()),
                byteArrayOf(0xff.toByte(), 0xfe.toByte()),
                byteArrayOf(0x00.toByte(), 0x00.toByte(), 0xfe.toByte(), 0xff.toByte()),
                byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00.toByte(), 0x00.toByte()),
            )
            .forEach { bom ->
                assertIs<ChannelDecodeResult.Failure>(
                    CanonicalChannelJson.decode(bom + stable.encodeToByteArray())
                )
            }
    }

    @Test
    fun `duplicate unknown missing null and wrong object fields are rejected at every object`() {
        val cases =
            listOf(
                stable.replaceFirst(
                    "\"channel\": \"stable\",",
                    "\"channel\": \"stable\",\n  \"channel\": \"stable\",",
                ),
                stable.replace("\"sequence\": 7", "\"unknown\": true,\n  \"sequence\": 7"),
                stable.replace("  \"sequence\": 7,\n", ""),
                stable.replace("\"target\": {", "\"target\": null,\n  \"target2\": {"),
                stable.replace("\"sha256\": \"${"a".repeat(64)}\"", "\"sha256\": null"),
                stable.replace("\"id\": \"v0.1.2\"", "\"extra\": true"),
            )
        cases.forEach {
            assertIs<ChannelDecodeResult.Failure>(
                CanonicalChannelJson.decode(it.encodeToByteArray())
            )
        }
        assertIs<ChannelDecodeResult.Failure>(
            CanonicalChannelJson.decode(
                stable
                    .replace(
                        """  "manifest": {
    "sha256": "${"a".repeat(64)}",
    "size": 123,
    "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json"
  },""",
                        """  "manifest": [],""",
                    )
                    .encodeToByteArray()
            )
        )
    }

    @Test
    fun `integer boundaries sequence size target and digest pairing are strict`() {
        listOf(
                stable.replace("\"sequence\": 7", "\"sequence\": 1.5"),
                stable.replace("\"sequence\": 7", "\"sequence\": 9223372036854775808"),
                stable.replace("\"sequence\": 7", "\"sequence\": -1"),
                stable.replace("\"size\": 123", "\"size\": 0"),
                stable.replace("\"size\": 123", "\"size\": -1"),
                stable.replace("\"size\": 123", "\"size\": 1048577"),
                stable.replace("\"type\": \"release\"", "\"type\": \"build\""),
                ChannelDocumentTest.edgeFixture.replace(
                    "\"type\": \"build\"",
                    "\"type\": \"release\"",
                ),
                stable.replace("v0.1.2", "v01.2"),
                stable.replace("${"a".repeat(64)}", "${"A".repeat(64)}"),
                stable.replace("${"a".repeat(64)}", "${"a".repeat(63)}g"),
            )
            .forEach {
                assertIs<ChannelDecodeResult.Failure>(
                    CanonicalChannelJson.decode(it.encodeToByteArray())
                )
            }
    }

    @Test
    fun `manifest URL authority and exact immutable location are strict`() {
        listOf(
                "http://cdn.grounds.gg",
                "https://user@cdn.grounds.gg",
                "https://cdn.grounds.gg:443",
                "https://other.example",
            )
            .forEach { origin ->
                assertIs<ChannelDecodeResult.Failure>(
                    CanonicalChannelJson.decode(
                        stable.replace("https://cdn.grounds.gg", origin).encodeToByteArray()
                    )
                )
            }
        listOf(
                "releases/v0.1.2/../manifest.json",
                "releases/v0.1.2%2fmanifest.json",
                "releases/v0.1.2/manifest.json?download=1",
                "releases/v0.1.2/manifest.json#part",
                "builds/v0.1.2/manifest.json",
                "releases/v0.1.2/other.json",
            )
            .forEach { path ->
                val url = "resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json"
                assertIs<ChannelDecodeResult.Failure>(
                    CanonicalChannelJson.decode(
                        stable
                            .replace(url, "resourcepacks/packsets/grounds-global/$path")
                            .encodeToByteArray()
                    )
                )
            }
    }

    private fun decode(bytes: String): ChannelDecodeResult.Failure =
        assertIs<ChannelDecodeResult.Failure>(
            CanonicalChannelJson.decode(bytes.encodeToByteArray())
        )

    private companion object {
        val stable = ChannelDocumentTest.stableFixture
    }
}
