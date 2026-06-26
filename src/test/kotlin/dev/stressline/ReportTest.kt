package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun sampleSnapshot() = Snapshot(
	total = 100,
	success = 90,
	failed = 10,
	errorCounts = mapOf("http" to 6L, "timeout" to 4L),
	statusCounts = mapOf(200 to 90L, 500 to 6L),
	latencyMin = 1.milliseconds,
	latencyMean = 20.milliseconds,
	latencyP50 = 18.milliseconds,
	latencyP90 = 40.milliseconds,
	latencyP95 = 50.milliseconds,
	latencyP99 = 80.milliseconds,
	latencyMax = 120.milliseconds,
)

class ReportTest :
	ShouldSpec({
		context("the live progress line") {
			should("include sent, ok, and err counts and p95") {
				val line = Report.line(sampleSnapshot(), 2.seconds)
				line shouldContain "sent 100"
				line shouldContain "ok 90"
				line shouldContain "err 10"
				line shouldContain "p95 50ms"
			}
		}
		context("the final summary") {
			should("include latency percentiles and error breakdown") {
				val text = Report.summary(sampleSnapshot(), 2.seconds)
				text shouldContain "Total:       100"
				text shouldContain "Success:     90"
				text shouldContain "Failed:      10"
				text shouldContain "p95"
				text shouldContain "timeout"
				text shouldContain "500"
			}
		}
		context("json output") {
			should("serialize the snapshot and config fields") {
				val config = RunConfig(url = "https://example.com", mode = LoadMode.FixedConcurrency(50))
				val json = Report.json(sampleSnapshot(), 2.seconds, config)
				json shouldContain "\"url\":\"https://example.com\""
				json shouldContain "\"mode\":{\"type\":\"concurrency\",\"value\":50}"
				json shouldContain "\"total\":100"
				json shouldContain "\"failed\":10"
				json shouldContain "\"errorRatePct\":10.00"
				json shouldContain "\"p95\":50"
				json shouldContain "\"errors\":{\"http\":6,\"timeout\":4}"
				json shouldContain "\"statusCodes\":{\"200\":90,\"500\":6}"
			}
			should("produce balanced braces") {
				val config = RunConfig(url = "http://x", mode = LoadMode.TargetRate(10))
				val json = Report.json(sampleSnapshot(), 1.seconds, config)
				json.count { it == '{' } shouldBe json.count { it == '}' }
				json shouldContain "\"type\":\"rate\""
			}
		}
	})
