@file:Suppress("DEPRECATION") // Characterizes legacy channelUri compatibility coverage.

package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.ChannelManifestReference
import gg.grounds.resourcepacks.contract.ChannelTarget
import gg.grounds.resourcepacks.contract.ManifestCatalog
import gg.grounds.resourcepacks.contract.ManifestMinecraft
import gg.grounds.resourcepacks.contract.ManifestProvenance
import gg.grounds.resourcepacks.contract.ManifestPublication
import gg.grounds.resourcepacks.contract.PackSetChannel
import gg.grounds.resourcepacks.contract.PackSetManifest
import gg.grounds.resourcepacks.contract.PublicationType
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class PackSetSourceTest {
    @Test
    fun `snapshot publication exposes the validated channel target contract`() {
        val snapshot =
            PackSetSnapshot(
                PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE),
                channel(),
                manifest(),
                listOf(resolvedPack()),
            )

        val publication: ChannelTarget = snapshot.publication

        assertEquals(ChannelTarget(PublicationType.RELEASE, "v1.2.3"), publication)
    }

    @Test
    fun `release source rejects unsafe IDs and separates cache namespaces`() {
        val base = URI("https://assets.example.test")
        listOf("1.2.3", "v01.2.3", "v1.2.3/other", "v1.2.3%2fother", "../v1.2.3").forEach { id ->
            assertFailsWith<IllegalArgumentException> { PackSetSource.release(base, "global", id) }
        }
        val pin = PackSetSource.release(base, "global", "v1.2.3")
        assertEquals(
            URI(
                "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/manifest.json"
            ),
            pin.requestUri,
        )
        assertNotEquals(PackSetSource(base, "global", PackSetChannel.STABLE).cacheKey, pin.cacheKey)
        assertNotEquals(PackSetSource.release(base, "global", "v1.2.4").cacheKey, pin.cacheKey)
        assertIs<PackSetSelection.Release>(pin.selection)
    }

    @Test
    fun `source uses policy canonical URI and a stable SHA-256 cache key`() {
        val source =
            PackSetSource(URI("https://assets.example.test/"), "global_1", PackSetChannel.EDGE)

        assertEquals(URI("https://assets.example.test"), source.baseUri)
        assertEquals(
            URI("https://assets.example.test/resourcepacks/packsets/global_1/channels/edge.json"),
            source.channelUri,
        )
        assertEquals(
            "60fc278013fa173d2d1f5cf3db9b1bf5ba8aa651f1267ea5c930031925496fe6",
            source.cacheKey,
        )
        assertEquals(
            source.cacheKey,
            PackSetSource(URI("https://assets.example.test"), "global_1", PackSetChannel.EDGE)
                .cacheKey,
        )
    }

    @Test
    fun `source rejects unsafe origins and pack set segments through the validation policy`() {
        listOf(
                URI("http://assets.example.test"),
                URI("https://user@assets.example.test"),
                URI("https://assets.example.test:443"),
                URI("https://assets.example.test/path"),
            )
            .forEach { unsafeBaseUri ->
                assertFailsWith<IllegalArgumentException> {
                    PackSetSource(unsafeBaseUri, "global", PackSetChannel.STABLE)
                }
            }
        listOf("", "contains/slash", "contains\\backslash", "has space").forEach { unsafePackSet ->
            assertFailsWith<IllegalArgumentException> {
                PackSetSource(
                    URI("https://assets.example.test"),
                    unsafePackSet,
                    PackSetChannel.STABLE,
                )
            }
        }
    }

    @Test
    fun `client config defaults are bounded and rejects non-positive durations and limits`() {
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
        val config = PackSetClientConfig(source, Path.of("cache"))

        assertEquals(Duration.ofSeconds(60), config.refreshInterval)
        assertEquals(Duration.ofSeconds(5), config.connectTimeout)
        assertEquals(Duration.ofSeconds(5), config.requestTimeout)
        assertEquals(65_536, config.maxChannelBytes)
        assertEquals(1_048_576, config.maxManifestBytes)

        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { invalidDuration ->
            assertFailsWith<IllegalArgumentException> {
                PackSetClientConfig(source, Path.of("cache"), refreshInterval = invalidDuration)
            }
            assertFailsWith<IllegalArgumentException> {
                PackSetClientConfig(source, Path.of("cache"), connectTimeout = invalidDuration)
            }
            assertFailsWith<IllegalArgumentException> {
                PackSetClientConfig(source, Path.of("cache"), requestTimeout = invalidDuration)
            }
        }
        listOf(0, -1).forEach { invalidLimit ->
            assertFailsWith<IllegalArgumentException> {
                PackSetClientConfig(source, Path.of("cache"), maxChannelBytes = invalidLimit)
            }
            assertFailsWith<IllegalArgumentException> {
                PackSetClientConfig(source, Path.of("cache"), maxManifestBytes = invalidLimit)
            }
        }
    }

    @Test
    fun `snapshot makes a defensive unmodifiable pack copy`() {
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
        val packs = mutableListOf(resolvedPack())
        val snapshot = PackSetSnapshot(source, channel(), manifest(), packs)
        packs.clear()

        assertEquals(1, snapshot.packs.size)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.packs as MutableList<ResolvedPack>).clear()
        }
    }

    @Test
    fun `snapshot derives fingerprint from channel and manifest identity only`() {
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
        val sameIdentity = PackSetSnapshot(source, channel(), manifest(), listOf(resolvedPack()))
        val sameIdentityDifferentPacks =
            PackSetSnapshot(source, channel(), manifest(), listOf(resolvedPack(required = false)))
        val changedSequence =
            PackSetSnapshot(source, channel(sequence = 2), manifest(), listOf(resolvedPack()))
        val changedTarget =
            PackSetSnapshot(
                source,
                channel(target = ChannelTarget(PublicationType.RELEASE, "v1.2.4")),
                manifest(),
                listOf(resolvedPack()),
            )
        val changedManifestIdentity =
            PackSetSnapshot(
                source,
                channel(),
                manifest(publicationId = "v1.2.4"),
                listOf(resolvedPack()),
            )

        assertEquals(
            "7f20f25e7b5d322a9192c3fb06e3b48af87dd62a20914433d4bd482e18746b42",
            sameIdentity.fingerprint,
        )
        assertEquals(sameIdentity.fingerprint, sameIdentityDifferentPacks.fingerprint)
        assertNotEquals(sameIdentity.fingerprint, changedSequence.fingerprint)
        assertNotEquals(sameIdentity.fingerprint, changedTarget.fingerprint)
        assertNotEquals(sameIdentity.fingerprint, changedManifestIdentity.fingerprint)
    }

    private fun channel(
        sequence: Long = 1,
        target: ChannelTarget = ChannelTarget(PublicationType.RELEASE, "v1.2.3"),
    ) =
        ChannelDocument(
            schemaVersion = 2,
            packSet = "global",
            channel = PackSetChannel.STABLE,
            sequence = sequence,
            target = target,
            manifest =
                ChannelManifestReference(
                    "https://assets.example.test/manifest.json",
                    "a".repeat(64),
                    100,
                ),
        )

    private fun manifest(publicationId: String = "v1.2.3") =
        PackSetManifest(
            schemaVersion = 2,
            packSet = "global",
            publication = ManifestPublication(PublicationType.RELEASE, publicationId),
            version = "1.2.3",
            minecraft = ManifestMinecraft("1.21.8", 55),
            catalog =
                ManifestCatalog(
                    "global",
                    "1.2.3",
                    "gg.grounds:catalog:1.2.3",
                    "catalog.jar",
                    "b".repeat(64),
                    100,
                ),
            packs = emptyList(),
            provenance = ManifestProvenance("grounds/resourcepacks", "c".repeat(40)),
        )

    private fun resolvedPack(required: Boolean = true) =
        ResolvedPack(
            order = 1,
            role = "global",
            id = "global-pack",
            uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            uri = URI("https://assets.example.test/pack.zip"),
            sha1 = "d".repeat(40),
            sha256 = "e".repeat(64),
            size = 100,
            required = required,
        )
}
