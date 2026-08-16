package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.contract.CanonicalChannelJson
import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.ChannelManifestReference
import gg.grounds.resourcepacks.contract.ChannelTarget
import gg.grounds.resourcepacks.contract.PackSetChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelContractParityTest {
    @Test
    fun `release object layout and contract channel fixture name the same manifest`() {
        val identity =
            PublicationIdentity(
                PublicationType.RELEASE,
                "v0.1.2",
                "0.1.2",
                "1969c1e6a3799e976de46eab019a16b2ee257ea7",
            )
        val document =
            ChannelDocument(
                2,
                PackSetObjectLayout.PACK_SET_ID,
                PackSetChannel.STABLE,
                7,
                ChannelTarget(PublicationType.RELEASE, identity.id),
                ChannelManifestReference(
                    PackSetObjectLayout.manifest(identity).publicUrl,
                    "a".repeat(64),
                    123,
                ),
            )

        assertEquals(
            "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json",
            document.manifest.url,
        )
        assertEquals(
            document.manifest.url,
            CanonicalChannelJson.decode(CanonicalChannelJson.encode(document)).let {
                (it as gg.grounds.resourcepacks.contract.ChannelDecodeResult.Success)
                    .document
                    .manifest
                    .url
            },
        )
    }

    @Test
    fun `build object layout and contract channel fixture name the same manifest`() {
        val commit = "1969c1e6a3799e976de46eab019a16b2ee257ea7"
        val identity =
            PublicationIdentity(
                PublicationType.BUILD,
                commit,
                "0.0.0-edge.42.g1969c1e6a379",
                commit,
            )
        val document =
            ChannelDocument(
                2,
                PackSetObjectLayout.PACK_SET_ID,
                PackSetChannel.EDGE,
                8,
                ChannelTarget(PublicationType.BUILD, identity.id),
                ChannelManifestReference(
                    PackSetObjectLayout.manifest(identity).publicUrl,
                    "b".repeat(64),
                    456,
                ),
            )

        assertEquals(
            "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/$commit/manifest.json",
            document.manifest.url,
        )
        assertEquals(
            document.manifest.url,
            CanonicalChannelJson.decode(CanonicalChannelJson.encode(document)).let {
                (it as gg.grounds.resourcepacks.contract.ChannelDecodeResult.Success)
                    .document
                    .manifest
                    .url
            },
        )
    }
}
