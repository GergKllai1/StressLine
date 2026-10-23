package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunConfigTest :
	ShouldSpec({
		context("parsing a header line") {
			should("split on the first colon and trim") {
				parseHeader("Content-Type: application/json") shouldBe ("Content-Type" to "application/json")
			}
			should("preserve colons in the value") {
				parseHeader("X-Url: http://a:8080") shouldBe ("X-Url" to "http://a:8080")
			}
			should("reject a line without a colon") {
				shouldThrow<IllegalArgumentException> { parseHeader("nope") }
			}
		}
		context("RunConfig defaults") {
			should("default stop, timeout, and progress") {
				val c = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(4))
				c.stop shouldBe StopCondition.ForDuration(kotlin.time.Duration.parse("10s"))
				c.showProgress shouldBe true
			}
		}
		context("new output and threshold fields") {
			should("default to off/null") {
				val c = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1))
				c.jsonToStdout shouldBe false
				c.jsonOut shouldBe null
				c.failIfErrorRate shouldBe null
				c.failIfP95 shouldBe null
			}
			should("carry a JsonOutTarget.File path") {
				val c =
					RunConfig(
						url = "http://x",
						mode = LoadMode.FixedConcurrency(1),
						jsonOut = JsonOutTarget.File("reports/run.json"),
					)
				c.jsonOut shouldBe JsonOutTarget.File("reports/run.json")
			}
		}
		context("connection limit") {
			fun checkLimit(
				mode: LoadMode,
				timeout: Duration,
				connections: Int?,
				expected: Int,
			) {
				val c = RunConfig(url = "http://x", mode = mode, timeout = timeout, connections = connections)
				c.connectionLimit() shouldBe expected
			}

			should("equal the worker count in fixed-concurrency mode") {
				checkLimit(mode = LoadMode.FixedConcurrency(250), timeout = 5.seconds, connections = null, expected = 250)
			}
			should("equal rate x timeout in target-rate mode") {
				checkLimit(mode = LoadMode.TargetRate(800), timeout = 60.seconds, connections = null, expected = 48_000)
			}
			should("round a fractional rate x timeout up") {
				checkLimit(mode = LoadMode.TargetRate(10), timeout = 1500.milliseconds, connections = null, expected = 15)
			}
			should("never drop below one") {
				checkLimit(mode = LoadMode.TargetRate(3), timeout = 100.milliseconds, connections = null, expected = 1)
			}
			should("use --connections when given, in either mode") {
				checkLimit(mode = LoadMode.TargetRate(800), timeout = 60.seconds, connections = 3000, expected = 3000)
				checkLimit(mode = LoadMode.FixedConcurrency(10), timeout = 5.seconds, connections = 50, expected = 50)
			}
		}
	})
