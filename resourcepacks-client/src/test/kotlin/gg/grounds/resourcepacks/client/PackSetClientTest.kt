package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackSetClientTest {
    // Break caught: a failed refresh not scheduling the first bounded retry leaves the client
    // unavailable.
    @Test
    fun `retry policy uses exact bounded exponential delays at both jitter bounds`() {
        assertEquals(
            listOf(800L, 1_600L, 3_200L, 6_400L, 12_800L, 24_000L, 24_000L),
            (0..6).map { RetryPolicy { 0.8 }.delayForFailure(it).toMillis() },
        )
        assertEquals(
            listOf(1_200L, 2_400L, 4_800L, 9_600L, 19_200L, 30_000L, 30_000L),
            (0..6).map { RetryPolicy { 1.2 }.delayForFailure(it).toMillis() },
        )
    }

    @Test
    fun `closing client marks it closed`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val directory = Files.createTempDirectory("pack-client-test")
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
        val client = PackSetClient(PackSetClientConfig(source, directory), scheduler = executor)
        client.close()
        assertEquals(PackSetClientStatus.CLOSED, client.state().status)
        executor.shutdownNow()
        deleteTree(directory)
    }

    // Break caught: two callers can otherwise trigger two simultaneous network refreshes.
    @Test
    fun `concurrent refresh calls share one in flight result`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val directory = Files.createTempDirectory("pack-client-test")
        val source =
            PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport =
            object : PackSetHttpTransport {
                override fun get(
                    uri: URI,
                    ifNoneMatch: String?,
                    timeout: Duration,
                ): PackSetHttpResponse {
                    entered.countDown()
                    assertTrue(release.await(1, TimeUnit.SECONDS))
                    return PackSetHttpResponse(
                        500,
                        null,
                        java.io.ByteArrayInputStream(ByteArray(0)),
                    )
                }
            }
        val client = PackSetClient(PackSetClientConfig(source, directory), transport, executor)
        try {
            val first = client.refreshNow()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = client.refreshNow()
            assertSame(first, second)
            release.countDown()
            assertEquals(
                RefreshResult.Failed::class,
                first.toCompletableFuture().get(1, TimeUnit.SECONDS)::class,
            )
        } finally {
            release.countDown()
            client.close()
            deleteTree(directory)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
