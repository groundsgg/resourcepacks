package gg.grounds.resourcepacks.client

import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A single-thread scheduler whose clock and task execution are controlled by the test. */
internal class DeterministicScheduledExecutor :
    AbstractExecutorService(), ScheduledExecutorService {
    private val sequence = AtomicLong()
    private val tasks = mutableListOf<ManualTask<*>>()
    private var nowNanos = 0L
    private var shutdown = false

    override fun execute(command: Runnable) {
        schedule(command, 0, TimeUnit.NANOSECONDS)
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        enqueue(
            ManualTask(nowNanos + unit.toNanos(delay), sequence.getAndIncrement(), command, null)
        )

    override fun <V> schedule(
        callable: Callable<V>,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<V> =
        enqueue(ManualTask(nowNanos + unit.toNanos(delay), sequence.getAndIncrement(), callable))

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = throw UnsupportedOperationException("Periodic tasks must be one-shot.")

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = throw UnsupportedOperationException("Periodic tasks must be one-shot.")

    @Synchronized
    private fun <V> enqueue(task: ManualTask<V>): ManualTask<V> {
        if (shutdown) throw RejectedExecutionException("Scheduler is shut down.")
        tasks += task
        return task
    }

    fun runCurrent() {
        while (true) {
            val task =
                synchronized(this) {
                    tasks
                        .filter { !it.isCancelled && it.dueNanos <= nowNanos }
                        .minWithOrNull(compareBy<ManualTask<*>> { it.dueNanos }.thenBy { it.order })
                        ?.also(tasks::remove)
                } ?: return
            task.run()
        }
    }

    fun advanceToNext(): Duration {
        val due =
            synchronized(this) {
                tasks.filterNot { it.isCancelled }.minOfOrNull(ManualTask<*>::dueNanos)
            } ?: error("No scheduled task.")
        val elapsed = Duration.ofNanos(due - nowNanos)
        nowNanos = due
        runCurrent()
        return elapsed
    }

    fun pendingDelays(): List<Duration> =
        synchronized(this) {
            tasks
                .filterNot { it.isCancelled }
                .map { Duration.ofNanos(it.dueNanos - nowNanos) }
                .sorted()
        }

    @Synchronized
    override fun shutdown() {
        shutdown = true
    }

    @Synchronized
    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        val pending = tasks.filterNot { it.isDone }.onEach { it.cancel(false) }
        tasks.clear()
        return pending.mapTo(mutableListOf()) { it }
    }

    @Synchronized override fun isShutdown(): Boolean = shutdown

    @Synchronized override fun isTerminated(): Boolean = shutdown && tasks.none { !it.isDone }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

    private class ManualTask<V> : FutureTask<V>, RunnableScheduledFuture<V> {
        val dueNanos: Long
        val order: Long

        constructor(
            dueNanos: Long,
            order: Long,
            runnable: Runnable,
            result: V,
        ) : super(runnable, result) {
            this.dueNanos = dueNanos
            this.order = order
        }

        constructor(dueNanos: Long, order: Long, callable: Callable<V>) : super(callable) {
            this.dueNanos = dueNanos
            this.order = order
        }

        override fun isPeriodic(): Boolean = false

        override fun getDelay(unit: TimeUnit): Long = unit.convert(dueNanos, TimeUnit.NANOSECONDS)

        override fun compareTo(other: Delayed): Int =
            when (other) {
                is ManualTask<*> ->
                    compareValuesBy(this, other, ManualTask<*>::dueNanos, ManualTask<*>::order)
                else ->
                    getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
            }
    }
}
