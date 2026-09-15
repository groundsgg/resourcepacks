@file:Suppress("DEPRECATION") // Characterizes legacy channelUri compatibility coverage.

package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.PackSetChannel
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackSetClientLifecycleTest {
    // Break caught: CompletableFuture continuations are application callbacks and must not run
    // while close owns the lifecycle lock.
    @Test
    fun `close completes an in-flight refresh outside the lifecycle lock`() {
        withDirectory { directory ->
            val source = source()
            val scheduler = DeterministicScheduledExecutor()
            val client =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> error("Refresh must remain queued.") },
                    scheduler,
                )
            val lifecycle = lifecycleLock(client)
            val callbackHeldLock = AtomicBoolean(false)
            val refresh = client.refreshNow()
            refresh.whenComplete { _, _ -> callbackHeldLock.set(Thread.holdsLock(lifecycle)) }

            client.close()

            assertFalse(callbackHeldLock.get())
            assertIs<RefreshResult.Failed>(refresh.toCompletableFuture().join())
        }
    }

    // Break caught: success must reset the failure index, restore exactly one periodic task, and a
    // later periodic failure must replace it with the first retry delay.
    @Test
    fun `success resets retry generation and periodic failure restarts at first delay`() {
        withDirectory { directory ->
            val source = source()
            val documents = clientDocuments(source)
            val scheduler = DeterministicScheduledExecutor()
            val round = AtomicInteger()
            val transport = ScriptedTransport { uri, _ ->
                when (round.getAndIncrement()) {
                    0 -> LoopbackPackSetServer.response(500)
                    1 -> {
                        assertEquals(source.channelUri, uri)
                        LoopbackPackSetServer.response(200, "channel-1", documents.channel)
                    }
                    2 -> {
                        assertEquals(manifestUri(source), uri)
                        LoopbackPackSetServer.response(200, "manifest-1", documents.manifest)
                    }
                    3 -> LoopbackPackSetServer.response(500)
                    else -> error("Unexpected request.")
                }
            }
            val client = client(source, directory, transport, scheduler)
            try {
                client.refreshNow()
                scheduler.runCurrent()
                assertEquals(listOf(Duration.ofMillis(800)), scheduler.pendingDelays())

                assertEquals(Duration.ofMillis(800), scheduler.advanceToNext())
                assertEquals(listOf(Duration.ofSeconds(60)), scheduler.pendingDelays())

                assertEquals(Duration.ofSeconds(60), scheduler.advanceToNext())
                assertEquals(listOf(Duration.ofMillis(800)), scheduler.pendingDelays())
            } finally {
                client.close()
            }
        }
    }

    // Break caught: an unchanged conditional refresh must not duplicate the same READY event.
    @Test
    fun `unchanged refresh does not notify and schedules the next periodic refresh at sixty seconds`() {
        withDirectory { directory ->
            val source = source()
            val documents = clientDocuments(source)
            val scheduler = DeterministicScheduledExecutor()
            val round = AtomicInteger()
            val transport = ScriptedTransport { uri, etag ->
                when (round.getAndIncrement()) {
                    0 ->
                        response(
                            source.channelUri,
                            uri,
                            null,
                            etag,
                            200,
                            "channel-1",
                            documents.channel,
                        )
                    1 ->
                        response(
                            manifestUri(source),
                            uri,
                            null,
                            etag,
                            200,
                            "manifest-1",
                            documents.manifest,
                        )
                    2 -> response(source.channelUri, uri, "channel-1", etag, 304)
                    else -> error("Unexpected request.")
                }
            }
            val client = client(source, directory, transport, scheduler)
            val events = mutableListOf<PackSetClientState>()
            client.addListener(events::add)
            try {
                val initial = client.refreshNow()
                scheduler.runCurrent()
                assertIs<RefreshResult.Activated>(initial.toCompletableFuture().join())
                assertEquals(listOf(PackSetClientStatus.READY), events.map { it.status })
                assertEquals(listOf(Duration.ofSeconds(60)), scheduler.pendingDelays())

                assertEquals(Duration.ofSeconds(60), scheduler.advanceToNext())

                assertEquals(listOf(PackSetClientStatus.READY), events.map { it.status })
                assertEquals(listOf(Duration.ofSeconds(60)), scheduler.pendingDelays())
            } finally {
                client.close()
            }
        }
    }

    // Break caught: a changed validated document must publish exactly one new READY snapshot.
    @Test
    fun `changed refresh notifies exactly once`() {
        withDirectory { directory ->
            val source = source()
            val first = clientDocuments(source, 1)
            val second = clientDocuments(source, 2)
            val scheduler = DeterministicScheduledExecutor()
            val responses =
                ArrayDeque(
                    listOf(
                        LoopbackPackSetServer.response(200, "channel-1", first.channel),
                        LoopbackPackSetServer.response(200, "manifest-1", first.manifest),
                        LoopbackPackSetServer.response(200, "channel-2", second.channel),
                        LoopbackPackSetServer.response(304),
                    )
                )
            val client =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> responses.removeFirst() },
                    scheduler,
                )
            val fingerprints = mutableListOf<String>()
            client.addListener { state -> state.current?.let { fingerprints += it.fingerprint } }
            try {
                val initial = client.refreshNow()
                scheduler.runCurrent()
                assertIs<RefreshResult.Activated>(initial.toCompletableFuture().join())

                val changed = client.refreshNow()
                scheduler.runCurrent()
                assertIs<RefreshResult.Activated>(changed.toCompletableFuture().join())

                assertEquals(2, fingerprints.size)
                assertTrue(fingerprints[0] != fingerprints[1])
            } finally {
                client.close()
            }
        }
    }

    // Break caught: persisted raw documents must be rebound through normal resolver validation
    // before a restart can reuse them on a 304.
    @Test
    fun `restart revalidates raw documents through the resolver before conditional reuse`() {
        withDirectory { directory ->
            val source = source()
            val documents = clientDocuments(source)
            val firstScheduler = DeterministicScheduledExecutor()
            val firstResponses =
                ArrayDeque(
                    listOf(
                        LoopbackPackSetServer.response(200, "channel-1", documents.channel),
                        LoopbackPackSetServer.response(200, "manifest-1", documents.manifest),
                    )
                )
            val first =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> firstResponses.removeFirst() },
                    firstScheduler,
                )
            val firstRefresh = first.refreshNow()
            firstScheduler.runCurrent()
            assertIs<RefreshResult.Activated>(firstRefresh.toCompletableFuture().join())
            val expected = requireNotNull(first.state().current).fingerprint
            first.close()

            val secondScheduler = DeterministicScheduledExecutor()
            val second =
                client(
                    source,
                    directory,
                    ScriptedTransport { uri, etag ->
                        response(source.channelUri, uri, "channel-1", etag, 304)
                    },
                    secondScheduler,
                )
            val events = mutableListOf<PackSetClientState>()
            second.addListener(events::add)
            try {
                val refresh = second.refreshNow()
                secondScheduler.runCurrent()

                assertIs<RefreshResult.Unchanged>(refresh.toCompletableFuture().join())
                assertEquals(expected, requireNotNull(second.state().current).fingerprint)
                assertEquals(listOf(PackSetClientStatus.READY), events.map { it.status })
            } finally {
                second.close()
            }
        }
    }

    // Break caught: reconfiguration must synchronously clear dispatchable current, preserve only
    // the old fallback, and publish STARTING before the new source becomes READY.
    @Test
    fun `reconfigure clears current and orders starting before new activation`() {
        withDirectory { directory ->
            val oldSource = source()
            val newSource =
                PackSetSource(
                    URI("https://new-assets.example.test"),
                    "global",
                    PackSetChannel.STABLE,
                )
            val oldDocuments = clientDocuments(oldSource)
            val newDocuments = clientDocuments(newSource)
            val scheduler = DeterministicScheduledExecutor()
            val responses =
                ArrayDeque(
                    listOf(
                        LoopbackPackSetServer.response(200, body = oldDocuments.channel),
                        LoopbackPackSetServer.response(200, body = oldDocuments.manifest),
                        LoopbackPackSetServer.response(200, body = newDocuments.channel),
                        LoopbackPackSetServer.response(200, body = newDocuments.manifest),
                    )
                )
            val client =
                client(
                    oldSource,
                    directory,
                    ScriptedTransport { _, _ -> responses.removeFirst() },
                    scheduler,
                )
            val events = mutableListOf<PackSetClientState>()
            client.addListener(events::add)
            try {
                val initial = client.refreshNow()
                scheduler.runCurrent()
                assertIs<RefreshResult.Activated>(initial.toCompletableFuture().join())
                val oldSnapshot = requireNotNull(client.state().current)
                events.clear()

                val refresh = client.reconfigure(newSource)
                val starting = client.state()
                assertEquals(PackSetClientStatus.STARTING, starting.status)
                assertNull(starting.current)
                assertEquals(oldSnapshot, starting.degradedFallback)
                scheduler.runCurrent()

                assertIs<RefreshResult.Activated>(refresh.toCompletableFuture().join())
                assertEquals(
                    listOf(PackSetClientStatus.STARTING, PackSetClientStatus.READY),
                    events.map { it.status },
                )
                assertEquals(listOf(newSource, newSource), events.map { it.source })
                assertNull(events.last().degradedFallback)
            } finally {
                client.close()
            }
        }
    }

    // Break caught: listener failures, including Error subclasses, must not suppress later
    // listeners or reorder lifecycle delivery.
    @Test
    fun `listeners run in registration order and isolate throwable failures`() {
        withDirectory { directory ->
            val source = source()
            val documents = clientDocuments(source)
            val scheduler = DeterministicScheduledExecutor()
            val responses =
                ArrayDeque(
                    listOf(
                        LoopbackPackSetServer.response(200, body = documents.channel),
                        LoopbackPackSetServer.response(200, body = documents.manifest),
                    )
                )
            val client =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> responses.removeFirst() },
                    scheduler,
                )
            val calls = mutableListOf<String>()
            client.addListener {
                calls += "first"
                throw AssertionError("synthetic listener failure")
            }
            client.addListener { calls += "second" }
            client.addListener { calls += "third" }
            try {
                client.refreshNow()
                scheduler.runCurrent()

                assertEquals(listOf("first", "second", "third"), calls)
            } finally {
                client.close()
            }
        }
    }

    // Break caught: a multi-thread injected scheduler must not run READY concurrently with an
    // earlier STARTING callback or deliver it out of FIFO order.
    @Test
    fun `lifecycle events remain strictly ordered and nonconcurrent on a multithread scheduler`() {
        withDirectory { directory ->
            val oldSource = source()
            val newSource =
                PackSetSource(
                    URI("https://new-assets.example.test"),
                    "global",
                    PackSetChannel.STABLE,
                )
            val oldDocuments = clientDocuments(oldSource)
            val newDocuments = clientDocuments(newSource)
            val startingEntered = CountDownLatch(1)
            val releaseStarting = CountDownLatch(1)
            val delivered = CountDownLatch(2)
            val responses = AtomicInteger()
            val transport = ScriptedTransport { uri, _ ->
                when (responses.getAndIncrement()) {
                    0 -> LoopbackPackSetServer.response(200, body = oldDocuments.channel)
                    1 -> LoopbackPackSetServer.response(200, body = oldDocuments.manifest)
                    2 -> {
                        assertEquals(newSource.channelUri, uri)
                        assertTrue(startingEntered.await(1, TimeUnit.SECONDS))
                        LoopbackPackSetServer.response(200, body = newDocuments.channel)
                    }
                    3 -> LoopbackPackSetServer.response(200, body = newDocuments.manifest)
                    else -> error("Unexpected request.")
                }
            }
            val executor = Executors.newScheduledThreadPool(4)
            val client = client(oldSource, directory, transport, executor)
            try {
                assertIs<RefreshResult.Activated>(
                    client.refreshNow().toCompletableFuture().get(1, TimeUnit.SECONDS)
                )
                val statuses = CopyOnWriteArrayList<PackSetClientStatus>()
                val callbackActive = AtomicBoolean(false)
                val concurrent = AtomicBoolean(false)
                client.addListener { state ->
                    if (!callbackActive.compareAndSet(false, true)) concurrent.set(true)
                    try {
                        statuses += state.status
                        if (state.status == PackSetClientStatus.STARTING) {
                            startingEntered.countDown()
                            assertTrue(releaseStarting.await(1, TimeUnit.SECONDS))
                        }
                    } finally {
                        callbackActive.set(false)
                        delivered.countDown()
                    }
                }

                val refresh = client.reconfigure(newSource)
                assertTrue(startingEntered.await(1, TimeUnit.SECONDS))
                assertIs<RefreshResult.Activated>(
                    refresh.toCompletableFuture().get(1, TimeUnit.SECONDS)
                )
                assertEquals(listOf(PackSetClientStatus.STARTING), statuses)
                releaseStarting.countDown()
                assertTrue(delivered.await(1, TimeUnit.SECONDS))

                assertFalse(concurrent.get())
                assertEquals(
                    listOf(PackSetClientStatus.STARTING, PackSetClientStatus.READY),
                    statuses,
                )
            } finally {
                releaseStarting.countDown()
                client.close()
            }
        }
    }

    // Break caught: an old generation completing after reconfiguration must not publish stale
    // READY or get ordered after the new source's STARTING/READY pair.
    @Test
    fun `stale old activation cannot overtake reconfigure starting and new ready`() {
        withDirectory { directory ->
            val oldSource = source()
            val newSource =
                PackSetSource(
                    URI("https://new-assets.example.test"),
                    "global",
                    PackSetChannel.STABLE,
                )
            val oldDocuments = clientDocuments(oldSource)
            val newDocuments = clientDocuments(newSource)
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val delivered = CountDownLatch(2)
            val transport = ScriptedTransport { uri, _ ->
                when (uri) {
                    oldSource.channelUri -> {
                        oldEntered.countDown()
                        assertTrue(releaseOld.await(1, TimeUnit.SECONDS))
                        LoopbackPackSetServer.response(200, body = oldDocuments.channel)
                    }
                    manifestUri(oldSource) ->
                        LoopbackPackSetServer.response(200, body = oldDocuments.manifest)
                    newSource.channelUri ->
                        LoopbackPackSetServer.response(200, body = newDocuments.channel)
                    manifestUri(newSource) ->
                        LoopbackPackSetServer.response(200, body = newDocuments.manifest)
                    else -> error("Unexpected URI: $uri")
                }
            }
            val executor = Executors.newSingleThreadScheduledExecutor()
            val client = client(oldSource, directory, transport, executor)
            val events = CopyOnWriteArrayList<Pair<PackSetSource, PackSetClientStatus>>()
            client.addListener {
                events += it.source to it.status
                delivered.countDown()
            }
            try {
                val stale = client.refreshNow()
                assertTrue(oldEntered.await(1, TimeUnit.SECONDS))
                val current = client.reconfigure(newSource)
                releaseOld.countDown()

                assertIs<RefreshResult.Failed>(stale.toCompletableFuture().get(1, TimeUnit.SECONDS))
                assertIs<RefreshResult.Activated>(
                    current.toCompletableFuture().get(1, TimeUnit.SECONDS)
                )
                assertTrue(delivered.await(1, TimeUnit.SECONDS))
                assertEquals(
                    listOf(
                        newSource to PackSetClientStatus.STARTING,
                        newSource to PackSetClientStatus.READY,
                    ),
                    events,
                )
            } finally {
                releaseOld.countDown()
                client.close()
            }
        }
    }

    // Break caught: reconfiguration used to clear inFlight and let a multi-worker injected
    // scheduler enter the replacement refresh while the stale refresh still owned transport/cache
    // work. The stale completion could then race the replacement's lifecycle.
    @Test
    fun `reconfigure serializes replacement refresh on a multi-worker scheduler`() {
        withDirectory { directory ->
            val oldSource = source()
            val newSource =
                PackSetSource(
                    URI("https://new-assets.example.test"),
                    "global",
                    PackSetChannel.STABLE,
                )
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val newEntered = CountDownLatch(1)
            val releaseNew = CountDownLatch(1)
            val scheduler = TrackingScheduler(2, 2)
            val transport = ScriptedTransport { uri, _ ->
                when (uri) {
                    oldSource.channelUri -> {
                        oldEntered.countDown()
                        assertTrue(releaseOld.await(1, TimeUnit.SECONDS))
                    }
                    newSource.channelUri -> {
                        newEntered.countDown()
                        assertTrue(releaseNew.await(1, TimeUnit.SECONDS))
                    }
                    else -> error("Unexpected URI: $uri")
                }
                LoopbackPackSetServer.response(500)
            }
            val client = client(oldSource, directory, transport, scheduler)
            try {
                val stale = client.refreshNow().toCompletableFuture()
                assertTrue(oldEntered.await(1, TimeUnit.SECONDS))

                val current = client.reconfigure(newSource).toCompletableFuture()
                assertSame(current, client.refreshNow().toCompletableFuture())
                assertTrue(scheduler.taskStarts.await(1, TimeUnit.SECONDS))
                assertTrue(
                    awaitCondition {
                        newEntered.count == 0L ||
                            scheduler.taskThreads.count { it.state == Thread.State.BLOCKED } >= 1
                    }
                )
                assertEquals(1L, newEntered.count, "replacement transport overlapped stale work")

                releaseOld.countDown()
                assertIs<RefreshResult.Failed>(stale.get(1, TimeUnit.SECONDS))
                assertTrue(newEntered.await(1, TimeUnit.SECONDS))
                assertFalse(current.isDone, "stale completion completed the replacement future")

                releaseNew.countDown()
                assertIs<RefreshResult.Failed>(current.get(1, TimeUnit.SECONDS))
            } finally {
                releaseOld.countDown()
                releaseNew.countDown()
                client.close()
            }
        }
    }

    // Break caught: rapid A-to-B-to-A changes could run all three queued refresh bodies at once,
    // including a stale B request, and race stores for the same A cache directory.
    @Test
    fun `rapid source changes keep one refresh body active and preserve coalescing`() {
        withDirectory { directory ->
            val sourceA = source()
            val sourceB =
                PackSetSource(
                    URI("https://new-assets.example.test"),
                    "global",
                    PackSetChannel.STABLE,
                )
            val firstA = AtomicBoolean(true)
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val unexpectedEntered = CountDownLatch(1)
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val requests = CopyOnWriteArrayList<URI>()
            val scheduler = TrackingScheduler(3, 3)
            val transport = ScriptedTransport { uri, _ ->
                requests += uri
                val nowActive = active.incrementAndGet()
                maxActive.accumulateAndGet(nowActive, ::maxOf)
                try {
                    if (uri == sourceA.channelUri && firstA.compareAndSet(true, false)) {
                        oldEntered.countDown()
                        assertTrue(releaseOld.await(1, TimeUnit.SECONDS))
                    } else {
                        unexpectedEntered.countDown()
                    }
                    LoopbackPackSetServer.response(500)
                } finally {
                    active.decrementAndGet()
                }
            }
            val client = client(sourceA, directory, transport, scheduler)
            try {
                val staleA = client.refreshNow().toCompletableFuture()
                assertTrue(oldEntered.await(1, TimeUnit.SECONDS))
                val staleB = client.reconfigure(sourceB).toCompletableFuture()
                val currentA = client.reconfigure(sourceA).toCompletableFuture()
                assertSame(currentA, client.refreshNow().toCompletableFuture())

                assertTrue(scheduler.taskStarts.await(1, TimeUnit.SECONDS))
                assertTrue(
                    awaitCondition {
                        unexpectedEntered.count == 0L ||
                            scheduler.taskThreads.count { it.state == Thread.State.BLOCKED } >= 2
                    }
                )
                assertEquals(1L, unexpectedEntered.count, "refresh bodies overlapped")
                assertEquals(1, maxActive.get())

                releaseOld.countDown()
                assertIs<RefreshResult.Failed>(staleA.get(1, TimeUnit.SECONDS))
                assertIs<RefreshResult.Failed>(staleB.get(1, TimeUnit.SECONDS))
                assertIs<RefreshResult.Failed>(currentA.get(1, TimeUnit.SECONDS))
                assertEquals(listOf(sourceA.channelUri, sourceA.channelUri), requests)
                assertEquals(1, maxActive.get())
            } finally {
                releaseOld.countDown()
                client.close()
            }
        }
    }

    // Break caught: completing a public refresh future while owning refreshExecution lets a
    // synchronous continuation enqueue and join its next refresh while that refresh is blocked on
    // the same mutex.
    @Test
    fun `reentrant refresh continuation runs after serialized execution is released`() {
        withDirectory { directory ->
            val source = source()
            val firstTransportEntered = CountDownLatch(1)
            val releaseFirstTransport = CountDownLatch(1)
            val callbackEntered = CountDownLatch(1)
            val callbackFinished = CountDownLatch(1)
            val requests = AtomicInteger()
            val scheduler = TrackingScheduler(2, 2)
            val transport = ScriptedTransport { uri, _ ->
                assertEquals(source.channelUri, uri)
                if (requests.getAndIncrement() == 0) {
                    firstTransportEntered.countDown()
                    assertTrue(releaseFirstTransport.await(1, TimeUnit.SECONDS))
                }
                LoopbackPackSetServer.response(500)
            }
            val client = client(source, directory, transport, scheduler)
            val continuationResult = java.util.concurrent.atomic.AtomicReference<RefreshResult>()
            try {
                val first = client.refreshNow().toCompletableFuture()
                assertTrue(firstTransportEntered.await(1, TimeUnit.SECONDS))
                first.whenComplete { _, _ ->
                    callbackEntered.countDown()
                    continuationResult.set(client.refreshNow().toCompletableFuture().join())
                    callbackFinished.countDown()
                }

                releaseFirstTransport.countDown()
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
                assertTrue(scheduler.taskStarts.await(1, TimeUnit.SECONDS))
                assertTrue(
                    callbackFinished.await(1, TimeUnit.SECONDS),
                    "reentrant continuation deadlocked with the next serialized refresh",
                )

                assertIs<RefreshResult.Failed>(continuationResult.get())
                assertEquals(2, requests.get())
            } finally {
                releaseFirstTransport.countDown()
                client.close()
            }
        }
    }

    // Break caught: replacing the sole inFlight reference during A-to-B-to-C reconfiguration
    // orphaned queued B when close completed only C and removed B from the scheduler queue.
    @Test
    fun `close completes every superseded refresh before queued transports can start`() {
        withDirectory { directory ->
            val sourceA = source()
            val sourceB =
                PackSetSource(URI("https://b-assets.example.test"), "global", PackSetChannel.STABLE)
            val sourceC =
                PackSetSource(URI("https://c-assets.example.test"), "global", PackSetChannel.STABLE)
            val firstTransportEntered = CountDownLatch(1)
            val releaseFirstTransport = CountDownLatch(1)
            val requests = CopyOnWriteArrayList<URI>()
            val callbackCount = AtomicInteger()
            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val transport = ScriptedTransport { uri, _ ->
                requests += uri
                assertEquals(sourceA.channelUri, uri)
                firstTransportEntered.countDown()
                while (releaseFirstTransport.count > 0) {
                    try {
                        releaseFirstTransport.await()
                    } catch (_: InterruptedException) {
                        // Keep A active while close removes the queued B/C refresh tasks.
                    }
                }
                LoopbackPackSetServer.response(500)
            }
            val client = client(sourceA, directory, transport, scheduler)
            client.addListener { callbackCount.incrementAndGet() }
            try {
                val refreshA = client.refreshNow().toCompletableFuture()
                assertTrue(firstTransportEntered.await(1, TimeUnit.SECONDS))
                val refreshB = client.reconfigure(sourceB).toCompletableFuture()
                val refreshC = client.reconfigure(sourceC).toCompletableFuture()

                client.close()

                assertEquals(
                    listOf(true, true, true),
                    listOf(refreshA, refreshB, refreshC).map { it.isDone },
                )
                listOf(refreshA, refreshB, refreshC).forEach {
                    assertIs<RefreshResult.Failed>(it.join())
                }
                releaseFirstTransport.countDown()
                assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS))
                assertEquals(listOf(sourceA.channelUri), requests)
                assertEquals(0, callbackCount.get())
            } finally {
                releaseFirstTransport.countDown()
                client.close()
                scheduler.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }

    // Break caught: a stale old-source failure must not increment the new generation's failure
    // count or select its second retry delay.
    @Test
    fun `stale old failure cannot change new source retry generation`() {
        withDirectory { directory ->
            val oldSource = source()
            val newSource =
                PackSetSource(
                    URI("https://new-assets.example.test"),
                    "global",
                    PackSetChannel.STABLE,
                )
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val scheduler = RecordingScheduler()
            val transport = ScriptedTransport { uri, _ ->
                if (uri == oldSource.channelUri) {
                    oldEntered.countDown()
                    assertTrue(releaseOld.await(1, TimeUnit.SECONDS))
                } else assertEquals(newSource.channelUri, uri)
                LoopbackPackSetServer.response(500)
            }
            val client = client(oldSource, directory, transport, scheduler)
            try {
                val stale = client.refreshNow()
                assertTrue(oldEntered.await(1, TimeUnit.SECONDS))
                val current = client.reconfigure(newSource)
                releaseOld.countDown()

                assertIs<RefreshResult.Failed>(stale.toCompletableFuture().get(1, TimeUnit.SECONDS))
                assertIs<RefreshResult.Failed>(
                    current.toCompletableFuture().get(1, TimeUnit.SECONDS)
                )
                assertEquals(listOf(Duration.ofMillis(800)), scheduler.positiveDelays)
                assertEquals(newSource, client.state().source)
            } finally {
                releaseOld.countDown()
                client.close()
            }
        }
    }

    // Break caught: close must join a callback already outside the lifecycle lock so no listener
    // code can continue after close returns.
    @Test
    fun `close waits for an active listener callback before returning`() {
        withDirectory { directory ->
            val source = source()
            val documents = clientDocuments(source)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closeReturned = CountDownLatch(1)
            val callbackFinished = CountDownLatch(1)
            val order = CopyOnWriteArrayList<String>()
            val executor = Executors.newSingleThreadScheduledExecutor()
            val responses =
                ArrayDeque(
                    listOf(
                        LoopbackPackSetServer.response(200, body = documents.channel),
                        LoopbackPackSetServer.response(200, body = documents.manifest),
                    )
                )
            val client =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> responses.removeFirst() },
                    executor,
                )
            client.addListener {
                try {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // Deliberately remain active: close's guarantee may not depend on
                            // listeners cooperating with scheduler interruption.
                        }
                    }
                } finally {
                    order += "callback-finished"
                    callbackFinished.countDown()
                }
            }
            installCloseJoinObserver(client) { afterJoin ->
                if (afterJoin) order += "after-join"
                else {
                    order += "before-join"
                    release.countDown()
                }
            }
            val closer =
                thread(start = false, name = "pack-client-close-test") {
                    client.close()
                    order += "close-returned"
                    closeReturned.countDown()
                }
            try {
                val refresh = client.refreshNow()
                assertIs<RefreshResult.Activated>(
                    refresh.toCompletableFuture().get(1, TimeUnit.SECONDS)
                )
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                closer.start()
                assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
                assertEquals(0L, callbackFinished.count)
                assertEquals(
                    listOf("before-join", "callback-finished", "after-join", "close-returned"),
                    order,
                )
            } finally {
                release.countDown()
                if (closer.state == Thread.State.NEW) closer.start()
                closer.join(1_000)
                client.close()
            }
        }
    }

    // Break caught: a second idempotent close used to observe closed=true and return while the
    // first close still had an admitted listener callback to join.
    @Test
    fun `concurrent close callers share the callback completion barrier`() {
        withDirectory { directory ->
            val source = source()
            val documents = clientDocuments(source)
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val firstAtJoin = CountDownLatch(1)
            val allowFirstJoin = CountDownLatch(1)
            val callbackFinished = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val secondReturned = CountDownLatch(1)
            val executor = Executors.newSingleThreadScheduledExecutor()
            val responses =
                ArrayDeque(
                    listOf(
                        LoopbackPackSetServer.response(200, body = documents.channel),
                        LoopbackPackSetServer.response(200, body = documents.manifest),
                    )
                )
            val client =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> responses.removeFirst() },
                    executor,
                )
            client.addListener {
                try {
                    callbackEntered.countDown()
                    while (releaseCallback.count > 0) {
                        try {
                            releaseCallback.await()
                        } catch (_: InterruptedException) {
                            // Keep the admitted callback active across scheduler shutdown.
                        }
                    }
                } finally {
                    callbackFinished.countDown()
                }
            }
            installCloseJoinObserver(client) { afterJoin ->
                if (!afterJoin) {
                    firstAtJoin.countDown()
                    assertTrue(allowFirstJoin.await(1, TimeUnit.SECONDS))
                }
            }
            val firstCloser =
                thread(start = false, name = "pack-client-first-close") { client.close() }
            val secondCloser =
                thread(start = false, name = "pack-client-second-close") {
                    secondStarted.countDown()
                    client.close()
                    secondReturned.countDown()
                }
            try {
                assertIs<RefreshResult.Activated>(
                    client.refreshNow().toCompletableFuture().get(1, TimeUnit.SECONDS)
                )
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
                firstCloser.start()
                assertTrue(firstAtJoin.await(1, TimeUnit.SECONDS))
                secondCloser.start()
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
                assertTrue(
                    awaitCondition {
                        secondCloser.state == Thread.State.WAITING ||
                            secondCloser.state == Thread.State.TERMINATED
                    }
                )
                assertEquals(
                    1L,
                    secondReturned.count,
                    "second close returned before first completed",
                )

                releaseCallback.countDown()
                allowFirstJoin.countDown()
                firstCloser.join(1_000)
                secondCloser.join(1_000)
                assertFalse(firstCloser.isAlive)
                assertFalse(secondCloser.isAlive)
                assertEquals(0L, callbackFinished.count)
                assertEquals(0L, secondReturned.count)
            } finally {
                releaseCallback.countDown()
                allowFirstJoin.countDown()
                if (firstCloser.state == Thread.State.NEW) firstCloser.start()
                if (secondCloser.state == Thread.State.NEW) secondCloser.start()
                firstCloser.join(1_000)
                secondCloser.join(1_000)
                client.close()
            }
        }
    }

    // Break caught: shutdown must cancel delayed retry/periodic work and reject later refreshes.
    @Test
    fun `shutdown cancels delayed work and closes the refresh lifecycle`() {
        withDirectory { directory ->
            val source = source()
            val scheduler = DeterministicScheduledExecutor()
            val client =
                client(
                    source,
                    directory,
                    ScriptedTransport { _, _ -> LoopbackPackSetServer.response(500) },
                    scheduler,
                )
            val refresh = client.refreshNow()
            scheduler.runCurrent()
            assertIs<RefreshResult.Failed>(refresh.toCompletableFuture().join())
            assertEquals(listOf(Duration.ofMillis(800)), scheduler.pendingDelays())

            client.close()

            assertTrue(scheduler.pendingDelays().isEmpty())
            assertEquals(PackSetClientStatus.CLOSED, client.state().status)
            assertIs<RefreshResult.Failed>(client.refreshNow().toCompletableFuture().join())
        }
    }

    private fun source() =
        PackSetSource(URI("https://assets.example.test"), "global", PackSetChannel.STABLE)

    private fun manifestUri(source: PackSetSource): URI =
        URI(
            "${source.baseUri}/resourcepacks/packsets/${source.packSet}/releases/v1.2.3/manifest.json"
        )

    private fun client(
        source: PackSetSource,
        directory: Path,
        transport: PackSetHttpTransport,
        scheduler: java.util.concurrent.ScheduledExecutorService,
    ) =
        PackSetClient(
            PackSetClientConfig(source, directory, refreshInterval = Duration.ofSeconds(60)),
            transport,
            scheduler,
            RetryPolicy { 0.8 },
        )

    private fun response(
        expectedUri: URI,
        actualUri: URI,
        expectedEtag: String?,
        actualEtag: String?,
        status: Int,
        etag: String? = null,
        body: ByteArray = ByteArray(0),
    ): PackSetHttpResponse {
        assertEquals(expectedUri, actualUri)
        assertEquals(expectedEtag, actualEtag)
        return LoopbackPackSetServer.response(status, etag, body)
    }

    private inline fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("pack-client-lifecycle-test")
        try {
            block(directory)
        } finally {
            deleteTree(directory)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private fun lifecycleLock(client: PackSetClient): Any {
        val field = PackSetClient::class.java.getDeclaredField("lifecycle")
        field.isAccessible = true
        return field.get(client)
    }

    private fun installCloseJoinObserver(client: PackSetClient, observer: (Boolean) -> Unit) {
        val field = PackSetClient::class.java.getDeclaredField("closeJoinObserver")
        field.isAccessible = true
        field.set(client, observer)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.onSpinWait()
        }
        return true
    }

    private class ScriptedTransport(private val script: (URI, String?) -> PackSetHttpResponse) :
        PackSetHttpTransport {
        override fun get(uri: URI, ifNoneMatch: String?, timeout: Duration): PackSetHttpResponse =
            script(uri, ifNoneMatch)
    }

    private class RecordingScheduler : ScheduledThreadPoolExecutor(1) {
        val positiveDelays = CopyOnWriteArrayList<Duration>()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (delay > 0) positiveDelays += Duration.ofNanos(unit.toNanos(delay))
            return super.schedule(command, delay, unit)
        }
    }

    private class TrackingScheduler(workers: Int, expectedTasks: Int) :
        ScheduledThreadPoolExecutor(workers) {
        val taskStarts = CountDownLatch(expectedTasks)
        val taskThreads = CopyOnWriteArrayList<Thread>()

        override fun execute(command: Runnable) {
            super.execute {
                taskThreads += Thread.currentThread()
                taskStarts.countDown()
                command.run()
            }
        }
    }
}
