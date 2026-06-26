package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
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
		context("URL as a positional argument") {
			should("accept the URL as the first argument (curl-style)") {
				val c = parseArgs(arrayOf("https://example.com", "--concurrency", "8"))
				c.url shouldBe "https://example.com"
			}
			should("accept the positional URL after options too") {
				val c = parseArgs(arrayOf("--concurrency", "8", "https://example.com"))
				c.url shouldBe "https://example.com"
			}
			should("still accept --url") {
				val c = parseArgs(arrayOf("-u", "https://example.com", "--concurrency", "8"))
				c.url shouldBe "https://example.com"
			}
			should("reject when no URL is given at all") {
				shouldThrow<CliValidationException> { parseArgs(arrayOf("--concurrency", "8")) }
			}
			should("reject when the argument and --url disagree") {
				shouldThrow<CliValidationException> {
					parseArgs(arrayOf("https://a.example", "--url", "https://b.example", "--concurrency", "8"))
				}
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
		context("JSON and threshold flags") {
			should("set jsonToStdout from --json") {
				parseArgs(arrayOf("http://x", "-c", "1", "--json")).jsonToStdout shouldBe true
			}
			should("treat bare --json-out as Auto") {
				parseArgs(arrayOf("http://x", "-c", "1", "--json-out")).jsonOut shouldBe JsonOutTarget.Auto
			}
			should("treat --json-out before another flag as Auto") {
				parseArgs(arrayOf("http://x", "--json-out", "-c", "1")).jsonOut shouldBe JsonOutTarget.Auto
			}
			should("take a .json path after --json-out") {
				parseArgs(arrayOf("http://x", "-c", "1", "--json-out", "reports/run.json")).jsonOut shouldBe
					JsonOutTarget.File("reports/run.json")
			}
			should("not treat a non-.json token (a URL) as the path") {
				val c = parseArgs(arrayOf("--json-out", "https://example.com", "-c", "1"))
				c.jsonOut shouldBe JsonOutTarget.Auto
				c.url shouldBe "https://example.com"
			}
			should("parse --fail-if-error-rate and --fail-if-p95") {
				val c = parseArgs(arrayOf("http://x", "-c", "1", "--fail-if-error-rate", "1.5", "--fail-if-p95", "200ms"))
				c.failIfErrorRate shouldBe 1.5
				c.failIfP95 shouldBe 200.milliseconds
			}
			should("reject a negative error-rate threshold") {
				shouldThrow<CliValidationException> {
					parseArgs(arrayOf("http://x", "-c", "1", "--fail-if-error-rate", "-1"))
				}
			}
			should("reject a malformed p95 threshold") {
				shouldThrow<CliValidationException> {
					parseArgs(arrayOf("http://x", "-c", "1", "--fail-if-p95", "bogus"))
				}
			}
		}
		context("body from a file") {
			should("read the body from @path") {
				val f = java.io.File.createTempFile("stressline-body", ".txt")
				f.writeText("payload-from-file")
				f.deleteOnExit()
				val c = parseArgs(arrayOf("http://x", "-c", "1", "-d", "@${f.path}"))
				c.body shouldBe "payload-from-file"
			}
			should("keep a plain body unchanged") {
				val c = parseArgs(arrayOf("http://x", "-c", "1", "-d", "literal"))
				c.body shouldBe "literal"
			}
			should("reject a missing body file") {
				shouldThrow<CliValidationException> {
					parseArgs(arrayOf("http://x", "-c", "1", "-d", "@/no/such/file.json"))
				}
			}
		}
	})
