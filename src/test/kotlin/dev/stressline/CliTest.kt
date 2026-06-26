package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class CliTest :
  ShouldSpec({
    context("fixed-concurrency mode") {
      should("build a RunConfig with workers and default 10s duration") {
        val c = parseArgs(arrayOf("--url", "http://x", "--concurrency", "8"))
        c.url shouldBe "http://x"
        c.mode shouldBe LoadMode.FixedConcurrency(8)
        c.stop shouldBe StopCondition.ForDuration(10.seconds)
      }
    }
    context("target-rate mode with request count") {
      should("build a RunConfig with rps and request count") {
        val c = parseArgs(arrayOf("--url", "http://x", "--rate", "100", "--requests", "500"))
        c.mode shouldBe LoadMode.TargetRate(100)
        c.stop shouldBe StopCondition.Requests(500)
      }
    }
    context("headers, method, body, timeout") {
      should("parse repeated headers and other options") {
        val c =
          parseArgs(
            arrayOf(
              "--url",
              "http://x",
              "--concurrency",
              "1",
              "-X",
              "POST",
              "-d",
              "hello",
              "-H",
              "A: 1",
              "-H",
              "B: 2",
              "--timeout",
              "2s",
            ),
          )
        c.method shouldBe "POST"
        c.body shouldBe "hello"
        c.headers shouldBe listOf("A" to "1", "B" to "2")
        c.timeout shouldBe 2.seconds
      }
    }
    context("mode validation") {
      should("reject specifying both concurrency and rate") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("--url", "http://x", "--concurrency", "1", "--rate", "1"))
        }
      }
      should("reject specifying neither concurrency nor rate") {
        shouldThrow<CliValidationException> { parseArgs(arrayOf("--url", "http://x")) }
      }
    }
    context("stop validation") {
      should("reject specifying both requests and duration") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("--url", "http://x", "--concurrency", "1", "--requests", "5", "--duration", "5s"))
        }
      }
    }
    context("non-positive load/stop integers") {
      should("reject --concurrency 0") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("--url", "http://x", "--concurrency", "0"))
        }
      }
      should("reject --rate 0") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("--url", "http://x", "--rate", "0"))
        }
      }
      should("reject --requests 0") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("--url", "http://x", "--concurrency", "1", "--requests", "0"))
        }
      }
    }
  })
