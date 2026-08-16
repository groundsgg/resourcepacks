package gg.grounds.resourcepacks.contract

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChannelDocumentTest {
    @Test
    fun `stable fixture encodes and decodes as canonical bytes`() {
        val fixture = stableFixture.toByteArray(Charsets.UTF_8)

        assertContentEquals(fixture, CanonicalChannelJson.encode(stableDocument))
        assertEquals(
            stableDocument,
            assertIs<ChannelDecodeResult.Success>(CanonicalChannelJson.decode(fixture)).document,
        )
        assertContentEquals(
            fixture,
            CanonicalChannelJson.encode(
                assertIs<ChannelDecodeResult.Success>(CanonicalChannelJson.decode(fixture)).document
            ),
        )
        assertEquals(
            CanonicalChannelJson.decode(fixture),
            PackSetContractJson.decodeChannel(fixture),
        )
    }

    @Test
    fun `edge fixture encodes and decodes as canonical bytes`() {
        val fixture = edgeFixture.toByteArray(Charsets.UTF_8)

        assertContentEquals(fixture, CanonicalChannelJson.encode(edgeDocument))
        assertEquals(
            edgeDocument,
            assertIs<ChannelDecodeResult.Success>(CanonicalChannelJson.decode(fixture)).document,
        )
        assertContentEquals(
            fixture,
            CanonicalChannelJson.encode(
                assertIs<ChannelDecodeResult.Success>(CanonicalChannelJson.decode(fixture)).document
            ),
        )
    }

    internal companion object {
        val stableDocument =
            ChannelDocument(
                schemaVersion = 2,
                packSet = "grounds-global",
                channel = PackSetChannel.STABLE,
                sequence = 7,
                target = ChannelTarget(PublicationType.RELEASE, "v0.1.2"),
                manifest =
                    ChannelManifestReference(
                        "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json",
                        "a".repeat(64),
                        123,
                    ),
            )
        val edgeDocument =
            ChannelDocument(
                schemaVersion = 2,
                packSet = "grounds-global",
                channel = PackSetChannel.EDGE,
                sequence = 8,
                target =
                    ChannelTarget(
                        PublicationType.BUILD,
                        "1969c1e6a3799e976de46eab019a16b2ee257ea7",
                    ),
                manifest =
                    ChannelManifestReference(
                        "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/1969c1e6a3799e976de46eab019a16b2ee257ea7/manifest.json",
                        "b".repeat(64),
                        456,
                    ),
            )
        val stableFixture =
            """
            {
              "channel": "stable",
              "manifest": {
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "size": 123,
                "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json"
              },
              "packSet": "grounds-global",
              "schemaVersion": 2,
              "sequence": 7,
              "target": {
                "id": "v0.1.2",
                "type": "release"
              }
            }
            """
                .trimIndent() + "\n"
        val edgeFixture =
            """
            {
              "channel": "edge",
              "manifest": {
                "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "size": 456,
                "url": "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/1969c1e6a3799e976de46eab019a16b2ee257ea7/manifest.json"
              },
              "packSet": "grounds-global",
              "schemaVersion": 2,
              "sequence": 8,
              "target": {
                "id": "1969c1e6a3799e976de46eab019a16b2ee257ea7",
                "type": "build"
              }
            }
            """
                .trimIndent() + "\n"
    }
}
