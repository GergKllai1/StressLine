package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
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
    context("target rate with a request count") {
      should("execute exactly N requests") {
        runTest {
          val runner = CountingRunner()
          val channel = Channel<RequestResult>(Channel.UNLIMITED)
          LoadEngine(runner, channel).run(LoadMode.TargetRate(100), StopCondition.Requests(50))
          channel.close()
          runner.count.get() shouldBe 50
          channel.toList().size shouldBe 50
        }
      }
    }
  })
