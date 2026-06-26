package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private fun snap(
  total: Long,
  failed: Long,
  p95: Duration,
): Snapshot =
  Snapshot(
    total = total,
    success = total - failed,
    failed = failed,
    errorCounts = emptyMap(),
    statusCounts = emptyMap(),
    latencyMin = Duration.ZERO,
    latencyMean = Duration.ZERO,
    latencyP50 = Duration.ZERO,
    latencyP90 = Duration.ZERO,
    latencyP95 = p95,
    latencyP99 = Duration.ZERO,
    latencyMax = Duration.ZERO,
  )

class ThresholdsTest :
  ShouldSpec({
    context("no thresholds configured") {
      should("return no breaches") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1))
        Thresholds.evaluate(snap(100, 50, 100.milliseconds), config).shouldBeEmpty()
      }
    }
    context("error-rate threshold") {
      should("breach when the rate exceeds the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfErrorRate = 10.0)
        Thresholds.evaluate(snap(100, 20, 1.milliseconds), config).shouldHaveSize(1)
      }
      should("pass when the rate is within the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfErrorRate = 10.0)
        Thresholds.evaluate(snap(100, 5, 1.milliseconds), config).shouldBeEmpty()
      }
      should("treat an empty run as passing") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfErrorRate = 0.0)
        Thresholds.evaluate(snap(0, 0, Duration.ZERO), config).shouldBeEmpty()
      }
    }
    context("p95 threshold") {
      should("breach when p95 exceeds the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfP95 = 200.milliseconds)
        Thresholds.evaluate(snap(100, 0, 300.milliseconds), config).shouldHaveSize(1)
      }
      should("pass when p95 is within the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfP95 = 200.milliseconds)
        Thresholds.evaluate(snap(100, 0, 100.milliseconds), config).shouldBeEmpty()
      }
    }
    context("both thresholds breached") {
      should("return two messages") {
        val config =
          RunConfig(
            url = "http://x",
            mode = LoadMode.FixedConcurrency(1),
            failIfErrorRate = 1.0,
            failIfP95 = 50.milliseconds,
          )
        Thresholds.evaluate(snap(100, 50, 300.milliseconds), config).shouldHaveSize(2)
      }
    }
  })
