package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MetricsCollectorTest :
	ShouldSpec({
		context("empty snapshot") {
			should("return all zeros and empty maps without throwing") {
				val collector = ChannelMetricsCollector()
				val s = collector.snapshot()
				s.total shouldBe 0L
				s.success shouldBe 0L
				s.failed shouldBe 0L
				s.errorCounts shouldBe emptyMap()
				s.statusCounts shouldBe emptyMap()
				s.latencyMin shouldBe Duration.ZERO
				s.latencyMean shouldBe Duration.ZERO
				s.latencyP50 shouldBe Duration.ZERO
				s.latencyP90 shouldBe Duration.ZERO
				s.latencyP95 shouldBe Duration.ZERO
				s.latencyP99 shouldBe Duration.ZERO
				s.latencyMax shouldBe Duration.ZERO
			}
		}
		context("when draining a sequence of results") {
			should("count successes, failures, and error categories") {
				runTest {
					val collector = ChannelMetricsCollector()
					val channel = Channel<RequestResult>(Channel.UNLIMITED)
					channel.send(RequestResult(10.milliseconds, RequestError.None(200)))
					channel.send(RequestResult(10.milliseconds, RequestError.None(204)))
					channel.send(RequestResult(10.milliseconds, RequestError.HttpError(500)))
					channel.send(RequestResult(10.milliseconds, RequestError.Timeout))
					channel.close()
					collector.consume(channel)

					val s = collector.snapshot()
					s.total shouldBe 4L
					s.success shouldBe 2L
					s.failed shouldBe 2L
					s.errorCounts["http"] shouldBe 1L
					s.errorCounts["timeout"] shouldBe 1L
					s.statusCounts[200] shouldBe 1L
					s.statusCounts[204] shouldBe 1L
					s.statusCounts[500] shouldBe 1L
				}
			}
			should("compute p95 latency within histogram precision") {
				runTest {
					val collector = ChannelMetricsCollector()
					val channel = Channel<RequestResult>(Channel.UNLIMITED)
					for (ms in 1..100) channel.send(RequestResult(ms.milliseconds, RequestError.None(200)))
					channel.close()
					collector.consume(channel)

					val p95Ms = collector.snapshot().latencyP95.inWholeMilliseconds
					p95Ms shouldBeGreaterThanOrEqual 93L
					p95Ms shouldBeLessThanOrEqual 97L
				}
			}
		}
		context("other errors") {
			suspend fun snapshotOfOthers(messages: List<String>): Snapshot {
				val collector = ChannelMetricsCollector()
				val channel = Channel<RequestResult>(Channel.UNLIMITED)
				messages.forEach { channel.send(RequestResult(10.milliseconds, RequestError.Other(it))) }
				channel.close()
				collector.consume(channel)
				return collector.snapshot()
			}

			should("list the five most common messages, most frequent first") {
				runTest {
					val messages = (1..6).flatMap { n -> List(n) { "error $n" } }
					snapshotOfOthers(messages).topOtherErrors shouldBe
						listOf("error 6" to 6L, "error 5" to 5L, "error 4" to 4L, "error 3" to 3L, "error 2" to 2L)
				}
			}
			should("cut long messages to 200 characters") {
				runTest {
					val top = snapshotOfOthers(listOf("x".repeat(500))).topOtherErrors.single()
					top.first.length shouldBe 200
				}
			}
			should("fold messages beyond fifty distinct ones into one bucket") {
				runTest {
					val messages = (1..50).flatMap { n -> listOf("known $n", "known $n") } + (1..30).map { "late $it" } + (1..30).map { "late $it" }
					val top = snapshotOfOthers(messages).topOtherErrors
					top.first() shouldBe ("(further distinct messages)" to 60L)
				}
			}
		}
	})
