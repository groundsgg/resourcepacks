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
    private val refreshExecution = Any()
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
    private val outstandingRefreshes = mutableSetOf<CompletableFuture<RefreshResult>>()
    private var periodicTask: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private val events = ArrayDeque<StateEvent>()
    private var dispatchingEvents = false
    private var activeCallbacks = 0
    private var callbacksDrained = CompletableFuture.completedFuture<Void>(null)
    private var closeCompletion: CompletableFuture<Void>? = null
    private val inListenerCallback = ThreadLocal.withInitial { false }
    private val inCloseRefreshCompletion = ThreadLocal.withInitial { false }
    private var closeJoinObserver: (Boolean) -> Unit = {}

    fun start() {
        val immediate: RefreshLaunch
        synchronized(lifecycle) {
            if (closed || started) return
            started = true
            immediate = beginRefreshLocked()
        }
        // Keep the immediate request alive; callers may instead observe state through listeners.
        finishLaunch(immediate).exceptionally { null }
    }

    fun state(): PackSetClientState = state.get()

    fun refreshNow(): CompletionStage<RefreshResult> {
        val launch = synchronized(lifecycle) { if (closed) null else beginRefreshLocked() }
        return if (launch == null)
            CompletableFuture.completedFuture(RefreshResult.Failed("Client is closed."))
        else finishLaunch(launch)
    }

    fun reconfigure(source: PackSetSource): CompletionStage<RefreshResult> {
        var drain = false
        var launch: RefreshLaunch? = null
        synchronized(lifecycle) {
            if (!closed && this.source != source) {
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
            if (!closed) launch = beginRefreshLocked()
        }
        if (drain) scheduleDrain()
        return launch?.let(::finishLaunch)
            ?: CompletableFuture.completedFuture(RefreshResult.Failed("Client is closed."))
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
        var ownsClose = false
        var callbackBarrier = CompletableFuture.completedFuture<Void>(null)
        var pendingRefreshes = emptyList<CompletableFuture<RefreshResult>>()
        val completion: CompletableFuture<Void>
        synchronized(lifecycle) {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
            } else {
                ownsClose = true
                completion = CompletableFuture()
                closeCompletion = completion
                closed = true
                sourceGeneration += 1
                periodicTask?.cancel(false)
                retryTask?.cancel(false)
                periodicTask = null
                retryTask = null
                pendingRefreshes = outstandingRefreshes.toList()
                outstandingRefreshes.clear()
                inFlight = null
                listeners.forEach { it.active = false }
                listeners.clear()
                events.clear()
                state.set(PackSetClientState(source, null, null, PackSetClientStatus.CLOSED, null))
                callbackBarrier = callbacksDrained
            }
        }
        if (!ownsClose) {
            if (!inListenerCallback.get() && !inCloseRefreshCompletion.get()) completion.join()
            return
        }
        try {
            inCloseRefreshCompletion.set(true)
            try {
                pendingRefreshes.forEach { it.complete(RefreshResult.Failed("Client is closed.")) }
            } finally {
                inCloseRefreshCompletion.set(false)
            }
            scheduler.shutdownNow()
            if (inListenerCallback.get()) {
                callbackBarrier.whenComplete { _, failure ->
                    if (failure == null) completion.complete(null)
                    else completion.completeExceptionally(failure)
                }
            } else {
                closeJoinObserver(false)
                callbackBarrier.join()
                closeJoinObserver(true)
                completion.complete(null)
            }
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
            throw failure
        }
        // Closing listeners means no application callback is dispatched for the terminal state.
    }

    private fun beginRefreshLocked(): RefreshLaunch {
        inFlight?.let {
            return RefreshLaunch(it, null)
        }
        val future = CompletableFuture<RefreshResult>()
        val scheduledSource = source
        val scheduledGeneration = sourceGeneration
        inFlight = future
        outstandingRefreshes += future
        var rejected: RefreshCompletion? = null
        try {
            scheduler.execute {
                val outcome =
                    synchronized(refreshExecution) {
                        try {
                            refresh(scheduledSource, scheduledGeneration, future)
                        } catch (_: Throwable) {
                            failed(scheduledSource, scheduledGeneration, future, "Refresh failed.")
                        }
                    }
                if (outcome.drainEvents) scheduleDrain()
                completePublic(outcome.completion)
            }
        } catch (_: RejectedExecutionException) {
            inFlight = null
            rejected =
                RefreshCompletion(
                    future,
                    RefreshResult.Failed("Refresh scheduler was unavailable."),
                )
        }
        return RefreshLaunch(future, rejected)
    }

    private fun finishLaunch(launch: RefreshLaunch): CompletionStage<RefreshResult> {
        launch.rejected?.let(::completePublic)
        return launch.future
    }

    private fun refresh(
        refreshSource: PackSetSource,
        generation: Long,
        future: CompletableFuture<RefreshResult>,
    ): RefreshExecutionOutcome {
        var drain = false
        if (!isCurrent(generation)) {
            return stale(future, drain)
        }
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
                            val published =
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
                            drain = drain || published.drainEvents
                            if (!published.accepted) return stale(future, drain)
                        }
                    }
                    else -> cache = null
                }
            }
        }
        if (!isCurrent(generation)) {
            return stale(future, drain)
        }
        val result = resolver.refresh(cache ?: emptyCache())
        if (!isCurrent(generation)) {
            return stale(future, drain)
        }
        return when (result) {
            is RefreshResult.Activated -> {
                try {
                    val refreshedCache = requireNotNull(resolver.cacheOf(result))
                    diskCache.store(refreshSource, refreshedCache)
                    if (!isCurrent(generation)) {
                        return stale(future, drain)
                    }
                    if (!installCache(refreshSource, generation, refreshedCache)) {
                        return stale(future, drain)
                    }
                    val published =
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
                    drain = drain || published.drainEvents
                    if (!published.accepted) stale(future, drain)
                    else complete(future, result, generation, drain)
                } catch (_: java.io.IOException) {
                    failed(refreshSource, generation, future, "Cache write failed.", drain)
                } catch (_: RuntimeException) {
                    failed(refreshSource, generation, future, "Cache write failed.", drain)
                }
            }
            is RefreshResult.Unchanged -> {
                val refreshedCache = requireNotNull(resolver.cacheOf(result))
                if (!installCache(refreshSource, generation, refreshedCache)) {
                    return stale(future, drain)
                }
                val published =
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
                drain = drain || published.drainEvents
                if (!published.accepted) stale(future, drain)
                else complete(future, result, generation, drain)
            }
            is RefreshResult.Failed ->
                failed(refreshSource, generation, future, result.reason, drain)
        }
    }

    private fun failed(
        refreshSource: PackSetSource,
        generation: Long,
        future: CompletableFuture<RefreshResult>,
        reason: String,
        priorDrain: Boolean = false,
    ): RefreshExecutionOutcome {
        var drain = priorDrain
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
                    ) || drain
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
        return RefreshExecutionOutcome(
            RefreshCompletion(
                future,
                if (accepted) RefreshResult.Failed(reason)
                else RefreshResult.Failed("Source changed."),
            ),
            drain,
        )
    }

    private fun stale(
        future: CompletableFuture<RefreshResult>,
        drainEvents: Boolean,
    ): RefreshExecutionOutcome {
        synchronized(lifecycle) { if (inFlight === future) inFlight = null }
        return RefreshExecutionOutcome(
            RefreshCompletion(future, RefreshResult.Failed("Source changed.")),
            drainEvents,
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

    private fun publishCurrent(generation: Long, next: PackSetClientState): StateCommit =
        synchronized(lifecycle) {
            if (closed || sourceGeneration != generation || source != next.source)
                StateCommit(false, false)
            else StateCommit(true, commitLocked(next))
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
        drainEvents: Boolean,
    ): RefreshExecutionOutcome {
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
                        try {
                            scheduler.schedule(
                                { refreshNow() },
                                config.refreshInterval.toMillis(),
                                java.util.concurrent.TimeUnit.MILLISECONDS,
                            )
                        } catch (_: RejectedExecutionException) {
                            null
                        }
                }
            }
        }
        return RefreshExecutionOutcome(RefreshCompletion(future, completion), drainEvents)
    }

    private fun completePublic(completion: RefreshCompletion) {
        completion.future.complete(completion.result)
        synchronized(lifecycle) { outstandingRefreshes.remove(completion.future) }
    }

    private data class RefreshLaunch(
        val future: CompletableFuture<RefreshResult>,
        val rejected: RefreshCompletion?,
    )

    private data class RefreshExecutionOutcome(
        val completion: RefreshCompletion,
        val drainEvents: Boolean,
    )

    private data class RefreshCompletion(
        val future: CompletableFuture<RefreshResult>,
        val result: RefreshResult,
    )

    private data class StateCommit(val accepted: Boolean, val drainEvents: Boolean)

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
