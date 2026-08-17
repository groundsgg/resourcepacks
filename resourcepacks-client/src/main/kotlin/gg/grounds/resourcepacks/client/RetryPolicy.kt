package gg.grounds.resourcepacks.client

import java.time.Duration
import java.util.function.DoubleSupplier
import kotlin.math.min
import kotlin.math.roundToLong

class RetryPolicy(
    private val jitter: DoubleSupplier = DoubleSupplier { 0.8 + Math.random() * 0.4 }
) {
    fun delayForFailure(failures: Int): Duration {
        val seconds = BACKOFF_SECONDS[failures.coerceIn(0, BACKOFF_SECONDS.lastIndex)]
        val factor = jitter.asDouble
        require(factor in 0.8..1.2) { "Retry jitter must be between 0.8 and 1.2." }
        return Duration.ofMillis(min(30_000, (seconds * 1_000.0 * factor).roundToLong()))
    }

    private companion object {
        val BACKOFF_SECONDS = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 16.0, 30.0)
    }
}
