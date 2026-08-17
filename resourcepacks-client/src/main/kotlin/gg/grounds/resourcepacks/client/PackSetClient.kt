package gg.grounds.resourcepacks.client

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
    private val listeners = linkedSetOf<PackSetStateListener>()
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

    fun start() {
        val immediate: CompletionStage<RefreshResult>
        synchronized(lifecycle) {
            if (closed || started) return
            started = true
            periodicTask =
                scheduler.scheduleWithFixedDelay(
                    { refreshNow() },
                    config.refreshInterval.toMillis(),
                    config.refreshInterval.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
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
        var changed: PackSetClientState? = null
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
                val previous = state.get()
                changed =
                    PackSetClientState(
                        source,
                        null,
                        previous.current ?: previous.degradedFallback,
                        PackSetClientStatus.STARTING,
                        null,
                    )
                state.set(changed)
            }
            refresh = beginRefreshLocked()
        }
        changed?.let(::notifyListeners)
        return refresh
    }

    fun addListener(listener: PackSetStateListener): AutoCloseable {
        synchronized(lifecycle) { if (!closed) listeners += listener }
        val removed = AtomicBoolean(false)
        return AutoCloseable {
            if (removed.compareAndSet(false, true))
                synchronized(lifecycle) { listeners -= listener }
        }
    }

    override fun close() {
        var changed: PackSetClientState? = null
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            sourceGeneration += 1
            periodicTask?.cancel(false)
            retryTask?.cancel(false)
            periodicTask = null
            retryTask = null
            inFlight?.complete(RefreshResult.Failed("Client is closed."))
            inFlight = null
            listeners.clear()
            changed = PackSetClientState(source, null, null, PackSetClientStatus.CLOSED, null)
            state.set(changed)
        }
        scheduler.shutdownNow()
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
                    failures = 0
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
                    complete(future, result)
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
                failures = 0
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
                complete(future, result)
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
        if (!isCurrent(generation)) {
            future.complete(RefreshResult.Failed("Source changed."))
            return
        }
        val previous = state.get()
        val fallback = previous.current ?: previous.degradedFallback
        publishCurrent(
            generation,
            PackSetClientState(
                refreshSource,
                previous.current,
                fallback,
                if (fallback == null) PackSetClientStatus.UNAVAILABLE
                else PackSetClientStatus.DEGRADED,
                reason,
            ),
        )
        failures += 1
        synchronized(lifecycle) {
            if (!closed && sourceGeneration == generation) {
                retryTask?.cancel(false)
                retryTask =
                    scheduler.schedule(
                        { refreshNow() },
                        retryPolicy.delayForFailure(failures - 1).toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
            }
            if (inFlight === future) inFlight = null
        }
        future.complete(RefreshResult.Failed(reason))
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

    private fun publish(next: PackSetClientState) {
        val previous = state.getAndSet(next)
        if (previous != next && state.get() === next) notifyListeners(next)
    }

    private fun publishCurrent(generation: Long, next: PackSetClientState): Boolean {
        val previous =
            synchronized(lifecycle) {
                if (closed || sourceGeneration != generation) return false
                state.getAndSet(next)
            }
        if (previous != next && state.get() === next) notifyListeners(next)
        return true
    }

    private fun notifyListeners(next: PackSetClientState) {
        val snapshot = synchronized(lifecycle) { listeners.toList() }
        snapshot.forEach {
            try {
                it.onState(next)
            } catch (_: RuntimeException) {
                // One plugin listener must not suppress another listener or the refresh loop.
            }
        }
    }

    private fun emptyCache() = ResolverCache(null, null, null, null, null)

    private fun complete(future: CompletableFuture<RefreshResult>, result: RefreshResult) {
        synchronized(lifecycle) {
            if (inFlight === future) {
                inFlight = null
                failures = 0
                retryTask?.cancel(false)
                retryTask = null
            }
        }
        future.complete(result)
    }

    private data class GenerationCache(
        val source: PackSetSource,
        val generation: Long,
        val cache: ResolverCache,
    )
}
