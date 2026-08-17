package gg.grounds.resourcepacks.client

import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface PackSetStateListener {
    fun onState(state: PackSetClientState)
}

class PackSetClient(
    private val config: PackSetClientConfig,
    private val transport: PackSetHttpTransport = JdkPackSetHttpTransport(config.connectTimeout),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : AutoCloseable {
    internal constructor(
        config: PackSetClientConfig,
        transport: PackSetHttpTransport,
        scheduler: ScheduledExecutorService,
        retryPolicy: RetryPolicy,
    ) : this(config, transport, scheduler) {
        this.retryPolicy = retryPolicy
    }

    private val lifecycle = Any()
    private val state =
        AtomicReference(
            PackSetClientState(config.source, null, null, PackSetClientStatus.STARTING, null)
        )
    private val listeners = mutableListOf<ListenerRegistration>()
    private val diskCache = PackSetDiskCache(config.cacheDirectory)

    private var retryPolicy = RetryPolicy()
    private var source = config.source
    private var sourceGeneration = 0L
    private var cached: GenerationCache? = null
    private var failures = 0
    private var started = false
    private var closed = false
    private var inFlight: CompletableFuture<RefreshResult>? = null
    private var periodicTask: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private val events = ArrayDeque<StateEvent>()
    private var dispatchingEvents = false
    private var activeCallbacks = 0
    private var callbacksDrained = CompletableFuture.completedFuture<Void>(null)
    private val inListenerCallback = ThreadLocal.withInitial { false }
    private var closeJoinObserver: (Boolean) -> Unit = {}

    fun start() {
        val immediate: CompletionStage<RefreshResult>
        synchronized(lifecycle) {
            if (closed || started) return
            started = true
            immediate = beginRefreshLocked()
        }
        // Keep the immediate request alive; callers may instead observe state through listeners.
        immediate.exceptionally { null }
    }

    fun state(): PackSetClientState = state.get()

    fun refreshNow(): CompletionStage<RefreshResult> =
        synchronized(lifecycle) {
            if (closed)
                return@synchronized CompletableFuture.completedFuture(
                    RefreshResult.Failed("Client is closed.")
                )
            beginRefreshLocked()
        }

    fun reconfigure(source: PackSetSource): CompletionStage<RefreshResult> {
        var drain = false
        val refresh: CompletionStage<RefreshResult>
        synchronized(lifecycle) {
            if (closed)
                return CompletableFuture.completedFuture(RefreshResult.Failed("Client is closed."))
            if (this.source != source) {
                sourceGeneration += 1
                this.source = source
                cached = null
                failures = 0
                inFlight = null
                retryTask?.cancel(false)
                retryTask = null
                periodicTask?.cancel(false)
                periodicTask = null
                val previous = state.get()
                val changed =
                    PackSetClientState(
                        source,
                        null,
                        previous.current ?: previous.degradedFallback,
                        PackSetClientStatus.STARTING,
                        null,
                    )
                drain = commitLocked(changed)
            }
            refresh = beginRefreshLocked()
        }
        if (drain) scheduleDrain()
        return refresh
    }

    fun addListener(listener: PackSetStateListener): AutoCloseable {
        val registration =
            synchronized(lifecycle) {
                if (closed) null
                else
                    listeners.firstOrNull { it.listener === listener }
                        ?: ListenerRegistration(listener).also(listeners::add)
            }
        val removed = AtomicBoolean(false)
        return AutoCloseable {
            if (removed.compareAndSet(false, true) && registration != null)
                synchronized(lifecycle) {
                    registration.active = false
                    listeners.remove(registration)
                }
        }
    }

    override fun close() {
        val callbackBarrier: CompletableFuture<Void>
        val pendingRefresh: CompletableFuture<RefreshResult>?
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            sourceGeneration += 1
            periodicTask?.cancel(false)
            retryTask?.cancel(false)
            periodicTask = null
            retryTask = null
            pendingRefresh = inFlight
            inFlight = null
            listeners.forEach { it.active = false }
            listeners.clear()
            events.clear()
            state.set(PackSetClientState(source, null, null, PackSetClientStatus.CLOSED, null))
            callbackBarrier = callbacksDrained
        }
        pendingRefresh?.complete(RefreshResult.Failed("Client is closed."))
        scheduler.shutdownNow()
        if (!inListenerCallback.get()) {
            closeJoinObserver(false)
            callbackBarrier.join()
            closeJoinObserver(true)
        }
        // Closing listeners means no application callback is dispatched for the terminal state.
    }

    private fun beginRefreshLocked(): CompletionStage<RefreshResult> {
        inFlight?.let {
            return it
        }
        val future = CompletableFuture<RefreshResult>()
        val scheduledSource = source
        val scheduledGeneration = sourceGeneration
        inFlight = future
        try {
            scheduler.execute { refresh(scheduledSource, scheduledGeneration, future) }
        } catch (_: RejectedExecutionException) {
            inFlight = null
            future.complete(RefreshResult.Failed("Refresh scheduler was unavailable."))
        }
        return future
    }

    private fun refresh(
        refreshSource: PackSetSource,
        generation: Long,
        future: CompletableFuture<RefreshResult>,
    ) {
        val refreshConfig = config.copy(source = refreshSource)
        val resolver = PackSetResolver(transport, refreshConfig)
        var cache = cachedFor(refreshSource, generation)
        if (cache == null) {
            cache =
                diskCache.load(
                    refreshSource,
                    refreshConfig.maxChannelBytes,
                    refreshConfig.maxManifestBytes,
                )
            if (cache != null) {
                when (val recovered = resolver.revalidate(cache)) {
                    is RefreshResult.Activated -> {
                        cache = requireNotNull(resolver.cacheOf(recovered))
                        if (installCache(refreshSource, generation, cache)) {
                            publishCurrent(
                                generation,
                                PackSetClientState(
                                    refreshSource,
                                    recovered.snapshot,
                                    null,
                                    PackSetClientStatus.READY,
                                    null,
                                ),
                            )
                        }
                    }
                    else -> cache = null
                }
            }
        }
        if (!isCurrent(generation)) {
            future.complete(RefreshResult.Failed("Source changed."))
            return
        }
        val result = resolver.refresh(cache ?: emptyCache())
        if (!isCurrent(generation)) {
            future.complete(RefreshResult.Failed("Source changed."))
            return
        }
        when (result) {
            is RefreshResult.Activated -> {
                try {
                    val refreshedCache = requireNotNull(resolver.cacheOf(result))
                    diskCache.store(refreshSource, refreshedCache)
                    if (!isCurrent(generation)) {
                        future.complete(RefreshResult.Failed("Source changed."))
                        return
                    }
                    if (!installCache(refreshSource, generation, refreshedCache)) {
                        future.complete(RefreshResult.Failed("Source changed."))
                        return
                    }
                    publishCurrent(
                        generation,
                        PackSetClientState(
                            refreshSource,
                            result.snapshot,
                            null,
                            PackSetClientStatus.READY,
                            null,
                        ),
                    )
                    complete(future, result, generation)
                } catch (_: java.io.IOException) {
                    failed(refreshSource, generation, future, "Cache write failed.")
                } catch (_: RuntimeException) {
                    failed(refreshSource, generation, future, "Cache write failed.")
                }
            }
            is RefreshResult.Unchanged -> {
                val refreshedCache = requireNotNull(resolver.cacheOf(result))
                if (!installCache(refreshSource, generation, refreshedCache)) {
                    future.complete(RefreshResult.Failed("Source changed."))
                    return
                }
                publishCurrent(
                    generation,
                    PackSetClientState(
                        refreshSource,
                        result.snapshot,
                        null,
                        PackSetClientStatus.READY,
                        null,
                    ),
                )
                complete(future, result, generation)
            }
            is RefreshResult.Failed -> failed(refreshSource, generation, future, result.reason)
        }
    }

    private fun failed(
        refreshSource: PackSetSource,
        generation: Long,
        future: CompletableFuture<RefreshResult>,
        reason: String,
    ) {
        var drain = false
        var accepted = false
        synchronized(lifecycle) {
            if (!closed && sourceGeneration == generation && source == refreshSource) {
                val previous = state.get()
                val fallback = previous.current ?: previous.degradedFallback
                drain =
                    commitLocked(
                        PackSetClientState(
                            refreshSource,
                            previous.current,
                            fallback,
                            if (fallback == null) PackSetClientStatus.UNAVAILABLE
                            else PackSetClientStatus.DEGRADED,
                            reason,
                        )
                    )
                failures += 1
                val retryDelay = retryPolicy.delayForFailure(failures - 1)
                retryTask?.cancel(false)
                periodicTask?.cancel(false)
                periodicTask = null
                retryTask =
                    try {
                        scheduler.schedule(
                            { refreshNow() },
                            retryDelay.toMillis(),
                            java.util.concurrent.TimeUnit.MILLISECONDS,
                        )
                    } catch (_: RejectedExecutionException) {
                        null
                    }
                accepted = true
            }
            if (inFlight === future) inFlight = null
        }
        if (drain) scheduleDrain()
        future.complete(
            if (accepted) RefreshResult.Failed(reason) else RefreshResult.Failed("Source changed.")
        )
    }

    private fun isCurrent(generation: Long): Boolean =
        synchronized(lifecycle) { !closed && sourceGeneration == generation }

    private fun cachedFor(source: PackSetSource, generation: Long): ResolverCache? =
        synchronized(lifecycle) {
            cached?.takeIf { !closed && it.source == source && it.generation == generation }?.cache
        }

    private fun installCache(
        source: PackSetSource,
        generation: Long,
        cache: ResolverCache,
    ): Boolean =
        synchronized(lifecycle) {
            if (closed || sourceGeneration != generation || this.source != source) false
            else {
                cached = GenerationCache(source, generation, cache)
                true
            }
        }

    private fun publishCurrent(generation: Long, next: PackSetClientState): Boolean {
        var drain = false
        synchronized(lifecycle) {
            if (closed || sourceGeneration != generation || source != next.source) return false
            drain = commitLocked(next)
        }
        if (drain) scheduleDrain()
        return true
    }

    private fun commitLocked(next: PackSetClientState): Boolean {
        check(Thread.holdsLock(lifecycle))
        val previous = state.get()
        if (closed || previous == next) return false
        state.set(next)
        if (listeners.isEmpty()) return false
        events += StateEvent(next, listeners.toList())
        return if (dispatchingEvents) false
        else {
            dispatchingEvents = true
            true
        }
    }

    private fun scheduleDrain() {
        try {
            scheduler.execute(::drainEvents)
        } catch (_: RejectedExecutionException) {
            synchronized(lifecycle) {
                events.clear()
                dispatchingEvents = false
            }
        }
    }

    private fun drainEvents() {
        while (true) {
            val event =
                synchronized(lifecycle) {
                    if (closed || events.isEmpty()) {
                        dispatchingEvents = false
                        return
                    }
                    events.removeFirst()
                }
            event.listeners.forEach { registration ->
                val invoke =
                    synchronized(lifecycle) {
                        if (closed || !registration.active) false
                        else {
                            if (activeCallbacks == 0) callbacksDrained = CompletableFuture()
                            activeCallbacks += 1
                            true
                        }
                    }
                if (!invoke) return@forEach
                inListenerCallback.set(true)
                try {
                    registration.listener.onState(event.state)
                } catch (x: Throwable) {
                    if (x is InterruptedException) Thread.currentThread().interrupt()
                    // Listener faults are isolated; fatal JVM errors are intentionally not rethrown
                    // from the scheduler because they would strand later lifecycle events.
                } finally {
                    inListenerCallback.set(false)
                    synchronized(lifecycle) {
                        activeCallbacks -= 1
                        if (activeCallbacks == 0) callbacksDrained.complete(null)
                    }
                }
            }
        }
    }

    private fun emptyCache() = ResolverCache(null, null, null, null, null)

    private fun complete(
        future: CompletableFuture<RefreshResult>,
        result: RefreshResult,
        generation: Long,
    ) {
        var completion: RefreshResult = result
        val resultSource =
            when (result) {
                is RefreshResult.Activated -> result.snapshot.source
                is RefreshResult.Unchanged -> result.snapshot.source
                is RefreshResult.Failed -> error("Only successful refreshes can complete here.")
            }
        synchronized(lifecycle) {
            if (closed || sourceGeneration != generation || source != resultSource) {
                completion = RefreshResult.Failed("Source changed.")
                if (inFlight === future) inFlight = null
            } else if (inFlight === future) {
                inFlight = null
                failures = 0
                retryTask?.cancel(false)
                retryTask = null
                if (!closed && sourceGeneration == generation) {
                    periodicTask?.cancel(false)
                    periodicTask =
                        scheduler.schedule(
                            { refreshNow() },
                            config.refreshInterval.toMillis(),
                            java.util.concurrent.TimeUnit.MILLISECONDS,
                        )
                }
            }
        }
        future.complete(completion)
    }

    private data class GenerationCache(
        val source: PackSetSource,
        val generation: Long,
        val cache: ResolverCache,
    )

    private data class StateEvent(
        val state: PackSetClientState,
        val listeners: List<ListenerRegistration>,
    )

    private class ListenerRegistration(val listener: PackSetStateListener) {
        var active = true
    }
}
