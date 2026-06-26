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
import kotlin.time.Duration.Companion.seconds

class LoadEngine(
	private val runner: HttpRunner,
	private val results: Channel<RequestResult>,
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
		val semaphore = Semaphore(maxOf(1, rps * 2))

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

		when (stop) {
			is StopCondition.Requests -> {
				repeat(stop.count) {
					launchOne()
					delay(interval)
				}
			}
			is StopCondition.ForDuration -> {
				withTimeoutOrNull(stop.duration) {
					while (coroutineContext.isActive) {
						launchOne()
						delay(interval)
					}
				}
			}
		}
	}
}
