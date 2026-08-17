package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackSetResolverTest {
    @Test
    fun `refresh activates an exact stable manifest without downloading ZIPs`() {
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val manifestUri =
            URI(
                "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/manifest.json"
            )
        val server =
            LoopbackPackSetServer(
                mapOf(
                    source.channelUri to LoopbackPackSetServer.response(200, "channel-1", channel),
                    manifestUri to LoopbackPackSetServer.response(200, "manifest-1", manifest),
                )
            )

        val result =
            PackSetResolver(server, PackSetClientConfig(source, Path.of("cache")))
                .refresh(ResolverCache(null, null, null, null, null))

        val activated = assertIs<RefreshResult.Activated>(result)
        assertEquals("v1.2.3", activated.snapshot.manifest.publication.id)
        assertEquals(
            listOf("content", "platform"),
            activated.snapshot.packs.map(ResolvedPack::role),
        )
        assertEquals(
            listOf(source.channelUri, manifestUri),
            server.requests.map(LoopbackPackSetServer.Request::uri),
        )
    }

    @Test
    fun `refresh activates an exact edge manifest from the build publication path`() {
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.EDGE)
        val manifest = edgeManifest().encodeToByteArray()
        val channel = edgeChannel(manifest).encodeToByteArray()
        val manifestUri =
            URI(
                "https://assets.example.test/resourcepacks/packsets/global/builds/1969c1e6a3799e976de46eab019a16b2ee257ea7/manifest.json"
            )
        val server =
            LoopbackPackSetServer(
                mapOf(
                    source.channelUri to LoopbackPackSetServer.response(200, body = channel),
                    manifestUri to LoopbackPackSetServer.response(200, body = manifest),
                )
            )

        val result = PackSetResolver(server, config(source)).refresh(emptyCache())

        val activated = assertIs<RefreshResult.Activated>(result)
        assertEquals(
            "1969c1e6a3799e976de46eab019a16b2ee257ea7",
            activated.snapshot.manifest.publication.id,
        )
        assertEquals(
            listOf(source.channelUri, manifestUri),
            server.requests.map(LoopbackPackSetServer.Request::uri),
        )
    }

    @Test
    fun `a not modified channel reuses its validated snapshot with only one conditional GET`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val manifestUri =
            URI(
                "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/manifest.json"
            )
        var round = 0
        val transport = ScriptedTransport { uri, etag, _ ->
            when (round++) {
                0 -> {
                    assertEquals(source.channelUri, uri)
                    assertNull(etag)
                    LoopbackPackSetServer.response(200, "channel-1", channel)
                }
                1 -> {
                    assertEquals(manifestUri, uri)
                    assertNull(etag)
                    LoopbackPackSetServer.response(200, "manifest-1", manifest)
                }
                2 -> {
                    assertEquals(source.channelUri, uri)
                    assertEquals("channel-1", etag)
                    LoopbackPackSetServer.response(304)
                }
                else -> error("Unexpected request.")
            }
        }
        val resolver = PackSetResolver(transport, config(source))
        val activated = assertIs<RefreshResult.Activated>(resolver.refresh(emptyCache()))

        val unchanged = assertIs<RefreshResult.Unchanged>(resolver.refresh(activated.cache))

        assertEquals(activated.snapshot, unchanged.snapshot)
        assertEquals(3, round)
    }

    @Test
    fun `a not modified manifest retains its ETag after a changed channel response`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        var round = 0
        val transport = ScriptedTransport { _, _, _ ->
            when (round++) {
                0 -> LoopbackPackSetServer.response(200, "channel-1", channel)
                1 -> LoopbackPackSetServer.response(200, "manifest-1", manifest)
                2 -> LoopbackPackSetServer.response(200, "channel-2", channel)
                3 -> LoopbackPackSetServer.response(304)
                else -> error("Unexpected request.")
            }
        }
        val resolver = PackSetResolver(transport, config(source))
        val initial = assertIs<RefreshResult.Activated>(resolver.refresh(emptyCache()))

        val refreshed = assertIs<RefreshResult.Activated>(resolver.refresh(initial.cache))

        assertEquals("manifest-1", refreshed.cache.manifestEtag)
    }

    @Test
    fun `refresh rejects manifest bytes whose digest differs from the channel reference`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel =
            stableChannel(manifest)
                .replace("\"sha256\": \"${sha256(manifest)}\"", "\"sha256\": \"${"0".repeat(64)}\"")
                .encodeToByteArray()
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> LoopbackPackSetServer.response(200, body = channel)
                else -> LoopbackPackSetServer.response(200, body = manifest)
            }
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `refresh rejects a valid manifest bound to a different publication target`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel =
            stableChannel(manifest)
                .replace("v1.2.3/manifest.json", "v1.2.4/manifest.json")
                .replace(
                    "\"id\": \"v1.2.3\",\n    \"type\": \"release\"",
                    "\"id\": \"v1.2.4\",\n    \"type\": \"release\"",
                )
                .encodeToByteArray()
        var request = 0
        val transport = ScriptedTransport { _, _, _ ->
            LoopbackPackSetServer.response(200, body = if (request++ == 0) channel else manifest)
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
    }

    @Test
    fun `oversized channel closes its response stream and fails closed`() {
        val source = source()
        val body = CloseTrackingInputStream(ByteArray(65_537))
        val transport = ScriptedTransport { _, _, _ -> PackSetHttpResponse(200, null, body) }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(body.closed)
        assertEquals(1, transport.requests.size)
    }

    private fun stableChannel(manifest: ByteArray) =
        """
        {
          "channel": "stable",
          "manifest": {
            "sha256": "${sha256(manifest)}",
            "size": ${manifest.size},
            "url": "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/manifest.json"
          },
          "packSet": "global",
          "schemaVersion": 2,
          "sequence": 1,
          "target": {
            "id": "v1.2.3",
            "type": "release"
          }
        }
        """
            .trimIndent() + "\n"

    private fun edgeChannel(manifest: ByteArray) =
        """
        {
          "channel": "edge",
          "manifest": {
            "sha256": "${sha256(manifest)}",
            "size": ${manifest.size},
            "url": "https://assets.example.test/resourcepacks/packsets/global/builds/1969c1e6a3799e976de46eab019a16b2ee257ea7/manifest.json"
          },
          "packSet": "global",
          "schemaVersion": 2,
          "sequence": 1,
          "target": {
            "id": "1969c1e6a3799e976de46eab019a16b2ee257ea7",
            "type": "build"
          }
        }
        """
            .trimIndent() + "\n"

    private fun edgeManifest() =
        stableManifest
            .replace("1.2.3", "0.0.0-edge.42.g1969c1e6a379")
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun source() =
        PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)

    private fun config(source: PackSetSource) = PackSetClientConfig(source, Path.of("cache"))

    private fun emptyCache() = ResolverCache(null, null, null, null, null)

    private class ScriptedTransport(
        private val script: (URI, String?, Duration) -> PackSetHttpResponse
    ) : PackSetHttpTransport {
        val requests = mutableListOf<URI>()

        override fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse {
            requests += uri
            return script(uri, ifNoneMatch, timeout)
        }
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        val stableManifest =
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
              "packSet": "global",
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
                  "url": "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/grounds-content-pack-v1.2.3.zip",
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
                  "url": "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/grounds-platform-pack-v1.2.3.zip",
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
                .trimIndent() + "\n"
    }
}
