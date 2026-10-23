package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class CountingRunner(
	val delay: Duration = Duration.ZERO,
) : HttpRunner {
	val count = AtomicInteger(0)

	override suspend fun execute(): RequestResult {
		if (delay > Duration.ZERO) kotlinx.coroutines.delay(delay)
		count.incrementAndGet()
		return RequestResult(1.seconds, RequestError.None(200))
	}
}

class LoadEngineTest :
	ShouldSpec({
		context("fixed concurrency with a request count") {
			should("execute exactly N requests across workers") {
				runTest {
					val runner = CountingRunner()
					// UNLIMITED so the engine never blocks on send; we drain after it finishes.
					val channel = Channel<RequestResult>(Channel.UNLIMITED)
					LoadEngine(runner, channel).run(LoadMode.FixedConcurrency(10), StopCondition.Requests(100))
					channel.close()
					runner.count.get() shouldBe 100
					channel.toList().size shouldBe 100
				}
			}
		}
		context("fixed concurrency for a duration") {
			should("keep executing until the duration elapses then stop") {
				runTest {
					val runner = CountingRunner(delay = 10.milliseconds)
					val channel = Channel<RequestResult>(Channel.UNLIMITED)
					LoadEngine(runner, channel).run(LoadMode.FixedConcurrency(4), StopCondition.ForDuration(1.seconds))
					channel.close()
					// 4 workers * (1s / 10ms) = ~400 requests under virtual time.
					runner.count.get() shouldBeGreaterThan 0
					channel.toList().size shouldBe runner.count.get()
				}
			}
		}
		context("target rate") {
			suspend fun TestScope.launchTimes(
				rps: Int,
				stop: StopCondition,
				responseTime: Duration = Duration.ZERO,
			): List<Long> {
				val starts = mutableListOf<Long>()
				val runner =
					object : HttpRunner {
						override suspend fun execute(): RequestResult {
							starts += testScheduler.currentTime
							kotlinx.coroutines.delay(responseTime)
							return RequestResult(responseTime, RequestError.None(200))
						}
					}
				val channel = Channel<RequestResult>(Channel.UNLIMITED)
				LoadEngine(runner, channel, timeout = 5.seconds, timeSource = testScheduler.timeSource).run(LoadMode.TargetRate(rps), stop)
				channel.close()
				channel.toList().size shouldBe starts.size
				return starts
			}

			should("execute exactly N requests") {
				runTest {
					launchTimes(rps = 100, stop = StopCondition.Requests(50)).size shouldBe 50
				}
			}
			should("hold a rate whose interval is not a whole millisecond") {
				runTest {
					// 800/s is a 1.25 ms interval; sleeping it per launch rounds up to 2 ms and paces at 500/s.
					launchTimes(rps = 800, stop = StopCondition.Requests(2400)).last() shouldBe 2_999L
				}
			}
			should("keep launching on schedule while responses take longer than two seconds") {
				runTest {
					val starts = launchTimes(rps = 10, stop = StopCondition.Requests(50), responseTime = 3.seconds)
					starts.size shouldBe 50
					starts.last() shouldBe 4_900L
				}
			}
			should("launch rate x duration requests when stopping on a duration") {
				runTest {
					launchTimes(rps = 50, stop = StopCondition.ForDuration(2.seconds)).size shouldBe 100
				}
			}
		}
	})
