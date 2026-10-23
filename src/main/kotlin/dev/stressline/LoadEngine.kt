package dev.stressline

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LoadEngine(
	private val runner: HttpRunner,
	private val results: Channel<RequestResult>,
	private val timeout: Duration = 5.seconds,
	private val timeSource: TimeSource = TimeSource.Monotonic,
) {
	suspend fun run(
		mode: LoadMode,
		stop: StopCondition,
	) {
		when (mode) {
			is LoadMode.FixedConcurrency -> runFixed(mode.workers, stop)
			is LoadMode.TargetRate -> runRate(mode.rps, stop)
		}
	}

	private suspend fun runFixed(
		workers: Int,
		stop: StopCondition,
	) = coroutineScope {
		when (stop) {
			is StopCondition.Requests -> {
				val remaining = AtomicInteger(stop.count)
				repeat(workers) {
					launch {
						while (remaining.getAndDecrement() > 0) {
							results.send(runner.execute())
						}
					}
				}
			}
			is StopCondition.ForDuration -> {
				withTimeoutOrNull(stop.duration) {
					coroutineScope {
						repeat(workers) {
							launch {
								while (isActive) results.send(runner.execute())
							}
						}
					}
				}
			}
		}
	}

	private suspend fun runRate(
		rps: Int,
		stop: StopCondition,
	) = coroutineScope {
		val interval = (1.seconds) / rps
		val semaphore = Semaphore(maxInFlight(rps, timeout))
		val start = timeSource.markNow()
		var launched = 0L

		suspend fun launchOne() {
			semaphore.acquire()
			launch {
				try {
					results.send(runner.execute())
				} finally {
					semaphore.release()
				}
			}
		}

		// delay() rounds up to whole milliseconds, so pace against the clock: launch every request due by now, then sleep until the next.
		suspend fun launchDue(limit: Long) {
			val due = minOf(limit, (start.elapsedNow() / interval).toLong() + 1)
			while (launched < due) {
				launchOne()
				launched++
			}
			if (launched < limit) delay(interval * launched.toDouble() - start.elapsedNow())
		}

		when (stop) {
			is StopCondition.Requests -> {
				while (launched < stop.count) launchDue(stop.count.toLong())
			}
			is StopCondition.ForDuration -> {
				withTimeoutOrNull(stop.duration) {
					while (coroutineContext.isActive) launchDue(Long.MAX_VALUE)
				}
			}
		}
	}
}
