package gg.grounds.resourcepacks.client

import com.sun.net.httpserver.HttpServer
import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `activated fingerprint hashes the exact validated document bytes in order`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val transport = ScriptedTransport { uri, _, _ ->
            LoopbackPackSetServer.response(
                200,
                body = if (uri == source.channelUri) channel else manifest,
            )
        }

        val activated =
            assertIs<RefreshResult.Activated>(
                PackSetResolver(transport, config(source)).refresh(emptyCache())
            )

        assertEquals(
            "aa8dbcbc939f3943c91f399f0871a82e035aa6059346edeb5dc36a851966ba26",
            activated.snapshot.fingerprint,
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

        val unchanged =
            assertIs<RefreshResult.Unchanged>(
                resolver.refresh(requireNotNull(resolver.cacheOf(activated)))
            )

        assertEquals(activated.snapshot, unchanged.snapshot)
        assertEquals(3, round)
    }

    // Break caught: a 304 response must never activate a validated snapshot that belongs to a
    // different configured source.
    @Test
    fun `not modified response rejects a cache bound to another source`() {
        val oldSource = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val oldResolver =
            PackSetResolver(validTransport(oldSource, channel, manifest), config(oldSource))
        val activated = assertIs<RefreshResult.Activated>(oldResolver.refresh(emptyCache()))
        val oldCache = requireNotNull(oldResolver.cacheOf(activated))
        val newSource =
            PackSetSource(URI("https://new-assets.example.test"), "global", PackSetChannel.STABLE)
        val transport = ScriptedTransport { uri, etag, _ ->
            assertEquals(newSource.channelUri, uri)
            assertNull(etag)
            LoopbackPackSetServer.response(304)
        }

        val result = PackSetResolver(transport, config(newSource)).refresh(oldCache)

        assertIs<RefreshResult.Failed>(result)
        assertEquals(listOf(newSource.channelUri), transport.requests)
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

        val refreshed =
            assertIs<RefreshResult.Activated>(
                resolver.refresh(requireNotNull(resolver.cacheOf(initial)))
            )

        assertEquals("manifest-1", requireNotNull(resolver.cacheOf(refreshed)).manifestEtag)
    }

    @Test
    fun `refresh rejects manifest bytes whose digest differs from the channel reference`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel =
            stableChannel(manifest)
                .replace("\"sha256\": \"${sha256(manifest)}\"", "\"sha256\": \"${"0".repeat(64)}\"")
                .encodeToByteArray()
        val channelBody = CloseTrackingInputStream(channel)
        val manifestBody = CloseTrackingInputStream(manifest)
        val manifestUri = URI(STABLE_MANIFEST_URI)
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> PackSetHttpResponse(200, null, manifestBody)
                else -> error("Unexpected request: $uri")
            }
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertTrue(manifestBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
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
        val channelBody = CloseTrackingInputStream(channel)
        val manifestBody = CloseTrackingInputStream(manifest)
        val manifestUri =
            URI(
                "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.4/manifest.json"
            )
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> PackSetHttpResponse(200, null, manifestBody)
                else -> error("Unexpected request: $uri")
            }
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertTrue(manifestBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
    }

    @Test
    fun `oversized channel closes its response stream and fails closed`() {
        val source = source()
        val body = CloseTrackingInputStream(ByteArray(65_537))
        val transport = ScriptedTransport { _, _, _ -> PackSetHttpResponse(200, null, body) }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(body.closed)
        assertEquals(listOf(source.channelUri), transport.requests)
    }

    @Test
    fun `a stalled body is closed and fails within the request timeout`() {
        val source = source()
        val body = StallingInputStream()
        val transport = ScriptedTransport { _, _, _ -> PackSetHttpResponse(200, null, body) }
        val config =
            PackSetClientConfig(source, Path.of("cache"), requestTimeout = Duration.ofMillis(100))
        val started = System.nanoTime()

        val result = PackSetResolver(transport, config).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(body.closed)
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos())
        assertEquals(listOf(source.channelUri), transport.requests)
    }

    @Test
    fun `transport rejects injected clients that permit redirects`() {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

        assertFailsWith<IllegalArgumentException> { JdkPackSetHttpTransport(client, false) }
    }

    @Test
    fun `transport returns a redirect response without following its location`() {
        var requests = 0
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/redirect") { exchange ->
            requests += 1
            exchange.responseHeaders.add("Location", "/follow-up")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/follow-up") { exchange ->
            requests += 1
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val response =
                JdkPackSetHttpTransport()
                    .get(
                        URI("http://127.0.0.1:${server.address.port}/redirect"),
                        null,
                        Duration.ofSeconds(1),
                    )

            response.use { assertEquals(302, it.status) }
            assertEquals(1, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `channel 404 and 500 fail before a manifest request`() {
        listOf(404, 500).forEach { status ->
            val source = source()
            val body = CloseTrackingInputStream(ByteArray(0))
            val transport = ScriptedTransport { _, _, _ -> PackSetHttpResponse(status, null, body) }

            val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

            assertIs<RefreshResult.Failed>(result, "status $status")
            assertTrue(body.closed, "status $status")
            assertEquals(listOf(source.channelUri), transport.requests, "status $status")
        }
    }

    @Test
    fun `manifest 404 and 500 fail after the channel request`() {
        listOf(404, 500).forEach { status ->
            val source = source()
            val manifest = stableManifest.encodeToByteArray()
            val channel = stableChannel(manifest).encodeToByteArray()
            val channelBody = CloseTrackingInputStream(channel)
            val statusBody = CloseTrackingInputStream(ByteArray(0))
            val manifestUri = URI(STABLE_MANIFEST_URI)
            val transport = ScriptedTransport { uri, _, _ ->
                when (uri) {
                    source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                    manifestUri -> PackSetHttpResponse(status, null, statusBody)
                    else -> error("Unexpected request: $uri")
                }
            }

            val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

            assertIs<RefreshResult.Failed>(result, "status $status")
            assertTrue(channelBody.closed, "status $status")
            assertTrue(statusBody.closed, "status $status")
            assertEquals(
                listOf(source.channelUri, manifestUri),
                transport.requests,
                "status $status",
            )
        }
    }

    @Test
    fun `channel and manifest read failures close their response independently`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        listOf(true, false).forEach { failChannel ->
            val body = FailingInputStream()
            val transport = ScriptedTransport { uri, _, _ ->
                when {
                    failChannel && uri == source.channelUri -> PackSetHttpResponse(200, null, body)
                    !failChannel && uri != source.channelUri -> PackSetHttpResponse(200, null, body)
                    uri == source.channelUri -> LoopbackPackSetServer.response(200, body = channel)
                    else -> LoopbackPackSetServer.response(200, body = manifest)
                }
            }

            val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

            assertIs<RefreshResult.Failed>(result)
            assertTrue(body.closed)
            assertEquals(
                if (failChannel) listOf(source.channelUri)
                else listOf(source.channelUri, URI(STABLE_MANIFEST_URI)),
                transport.requests,
            )
        }
    }

    @Test
    fun `bounded reader accepts exact channel and manifest limits and rejects one extra byte`() {
        listOf(65_536, 1_048_576).forEach { limit ->
            assertEquals(
                limit,
                BoundedResponseReader.read(java.io.ByteArrayInputStream(ByteArray(limit)), limit)
                    .size,
            )
            assertFailsWith<IllegalArgumentException> {
                BoundedResponseReader.read(
                    java.io.ByteArrayInputStream(ByteArray(limit + 1)),
                    limit,
                )
            }
        }
    }

    @Test
    fun `manifest declared size mismatch fails after both valid responses are closed`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel =
            stableChannel(manifest)
                .replace("\"size\": ${manifest.size}", "\"size\": ${manifest.size + 1}")
                .encodeToByteArray()
        val channelBody = CloseTrackingInputStream(channel)
        val manifestBody = CloseTrackingInputStream(manifest)
        val manifestUri = URI(STABLE_MANIFEST_URI)
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> PackSetHttpResponse(200, null, manifestBody)
                else -> error("Unexpected request: $uri")
            }
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertTrue(manifestBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
    }

    @Test
    fun `channel PackSet mismatch fails before a manifest request`() {
        val channel =
            stableChannel(stableManifest.encodeToByteArray())
                .replace("\"packSet\": \"global\"", "\"packSet\": \"other\"")
                .encodeToByteArray()

        assertFailedChannelDocument(channel)
    }

    @Test
    fun `manifest PackSet mismatch fails after exact integrity validation`() {
        val manifest =
            stableManifest
                .replace("\"packSet\": \"global\"", "\"packSet\": \"other\"")
                .encodeToByteArray()

        assertFailedManifestDocument(manifest)
    }

    @Test
    fun `document channel mismatch fails at the requested edge channel URI`() {
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.EDGE)
        val channel = stableChannel(stableManifest.encodeToByteArray()).encodeToByteArray()

        assertFailedChannelDocument(channel, source)
    }

    @Test
    fun `alternate manifest origin fails before its URL is requested`() {
        val channel =
            stableChannel(stableManifest.encodeToByteArray())
                .replace("https://assets.example.test", "https://mirror.example.test")
                .encodeToByteArray()

        assertFailedChannelDocument(channel)
    }

    @Test
    fun `alternate manifest root fails before its URL is requested`() {
        val channel =
            stableChannel(stableManifest.encodeToByteArray())
                .replace("/releases/v1.2.3/manifest.json", "/releases/v9.9.9/manifest.json")
                .encodeToByteArray()

        assertFailedChannelDocument(channel)
    }

    @Test
    fun `alternate artifact origin fails after exact manifest fetch`() {
        val manifest =
            stableManifest
                .replace("https://assets.example.test", "https://mirror.example.test")
                .encodeToByteArray()

        assertFailedManifestDocument(manifest)
    }

    @Test
    fun `alternate artifact root fails after exact manifest fetch`() {
        val manifest =
            stableManifest.replace("/releases/v1.2.3/", "/releases/v9.9.9/").encodeToByteArray()

        assertFailedManifestDocument(manifest)
    }

    @Test
    fun `malformed channel fails before a manifest request`() {
        val valid = stableChannel(stableManifest.encodeToByteArray())
        val malformed = valid.dropLast(2).encodeToByteArray()

        assertFailedChannelDocument(malformed)
    }

    @Test
    fun `malformed manifest fails after exact integrity validation`() {
        val malformed = stableManifest.dropLast(2).encodeToByteArray()

        assertFailedManifestDocument(malformed)
    }

    @Test
    fun `noncanonical channel fails before a manifest request`() {
        val noncanonical =
            stableChannel(stableManifest.encodeToByteArray())
                .replaceFirst("{\n", "{ \n")
                .encodeToByteArray()

        assertFailedChannelDocument(noncanonical)
    }

    @Test
    fun `noncanonical manifest fails after exact integrity validation`() {
        val noncanonical = stableManifest.replaceFirst("{\n", "{ \n").encodeToByteArray()

        assertFailedManifestDocument(noncanonical)
    }

    @Test
    fun `channel connection failure fails without a response to close`() {
        val source = source()
        val transport = ScriptedTransport { _, _, _ ->
            throw java.io.IOException("synthetic connection failure")
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertEquals(listOf(source.channelUri), transport.requests)
    }

    @Test
    fun `manifest connection failure follows a closed valid channel response`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channelBody = CloseTrackingInputStream(stableChannel(manifest).encodeToByteArray())
        val manifestUri = URI(STABLE_MANIFEST_URI)
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> throw java.io.IOException("synthetic connection failure")
                else -> error("Unexpected request: $uri")
            }
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
    }

    @Test
    fun `manifest header timeout follows a closed valid channel response`() {
        val source = source()
        val timeout = Duration.ofMillis(100)
        val manifest = stableManifest.encodeToByteArray()
        val channelBody = CloseTrackingInputStream(stableChannel(manifest).encodeToByteArray())
        val manifestUri = URI(STABLE_MANIFEST_URI)
        val transport = ScriptedTransport { uri, _, actualTimeout ->
            assertEquals(timeout, actualTimeout)
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> throw java.net.http.HttpTimeoutException("headers timed out")
                else -> error("Unexpected request: $uri")
            }
        }
        val timedConfig = config(source).copy(requestTimeout = timeout)

        val result = PackSetResolver(transport, timedConfig).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
    }

    @Test
    fun `transport times out when response headers never arrive`() {
        val requests = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/no-headers") { exchange ->
            requests.countDown()
            release.await(2, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val timeout = Duration.ofMillis(100)

            assertFailsWith<java.net.http.HttpTimeoutException> {
                JdkPackSetHttpTransport()
                    .get(URI("http://127.0.0.1:${server.address.port}/no-headers"), null, timeout)
            }
            assertTrue(requests.await(1, TimeUnit.SECONDS))
            assertEquals(0L, requests.count)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `resolver accepts otherwise valid documents exactly at configured byte limits`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val transport = validTransport(source, channel, manifest)
        val exactConfig =
            config(source).copy(maxChannelBytes = channel.size, maxManifestBytes = manifest.size)

        val result = PackSetResolver(transport, exactConfig).refresh(emptyCache())

        assertIs<RefreshResult.Activated>(result)
        assertEquals(listOf(source.channelUri, URI(STABLE_MANIFEST_URI)), transport.requests)
    }

    @Test
    fun `resolver rejects an otherwise valid channel one byte over its configured limit`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val channelBody = CloseTrackingInputStream(channel)
        val transport = ScriptedTransport { _, _, _ -> PackSetHttpResponse(200, null, channelBody) }
        val oneByteShort = config(source).copy(maxChannelBytes = channel.size - 1)

        val result = PackSetResolver(transport, oneByteShort).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertEquals(listOf(source.channelUri), transport.requests)
    }

    @Test
    fun `resolver rejects an otherwise valid manifest one byte over its configured limit`() {
        val source = source()
        val manifest = stableManifest.encodeToByteArray()
        val channel = stableChannel(manifest).encodeToByteArray()
        val channelBody = CloseTrackingInputStream(channel)
        val manifestBody = CloseTrackingInputStream(manifest)
        val manifestUri = URI(STABLE_MANIFEST_URI)
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> PackSetHttpResponse(200, null, manifestBody)
                else -> error("Unexpected request: $uri")
            }
        }
        val oneByteShort = config(source).copy(maxManifestBytes = manifest.size - 1)

        val result = PackSetResolver(transport, oneByteShort).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertTrue(manifestBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
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

    private fun assertFailedChannelDocument(channel: ByteArray, source: PackSetSource = source()) {
        val channelBody = CloseTrackingInputStream(channel)
        val transport = ScriptedTransport { _, _, _ -> PackSetHttpResponse(200, null, channelBody) }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertEquals(listOf(source.channelUri), transport.requests)
    }

    private fun assertFailedManifestDocument(manifest: ByteArray) {
        val source = source()
        val channelBody = CloseTrackingInputStream(stableChannel(manifest).encodeToByteArray())
        val manifestBody = CloseTrackingInputStream(manifest)
        val manifestUri = URI(STABLE_MANIFEST_URI)
        val transport = ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> PackSetHttpResponse(200, null, channelBody)
                manifestUri -> PackSetHttpResponse(200, null, manifestBody)
                else -> error("Unexpected request: $uri")
            }
        }

        val result = PackSetResolver(transport, config(source)).refresh(emptyCache())

        assertIs<RefreshResult.Failed>(result)
        assertTrue(channelBody.closed)
        assertTrue(manifestBody.closed)
        assertEquals(listOf(source.channelUri, manifestUri), transport.requests)
    }

    private fun validTransport(source: PackSetSource, channel: ByteArray, manifest: ByteArray) =
        ScriptedTransport { uri, _, _ ->
            when (uri) {
                source.channelUri -> LoopbackPackSetServer.response(200, body = channel)
                URI(STABLE_MANIFEST_URI) -> LoopbackPackSetServer.response(200, body = manifest)
                else -> error("Unexpected request: $uri")
            }
        }

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

    private class StallingInputStream : java.io.InputStream() {
        @Volatile var closed = false

        override fun read(): Int {
            while (!closed) Thread.sleep(10)
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    private class FailingInputStream : java.io.InputStream() {
        var closed = false

        override fun read(): Int = throw java.io.IOException("synthetic body failure")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val STABLE_MANIFEST_URI =
            "https://assets.example.test/resourcepacks/packsets/global/releases/v1.2.3/manifest.json"

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
