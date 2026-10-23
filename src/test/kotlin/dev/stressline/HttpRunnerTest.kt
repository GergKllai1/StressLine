package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun clientReturning(
	status: HttpStatusCode,
	capture: (HttpRequestData) -> Unit = {},
): HttpClient = HttpClient(
	MockEngine { request ->
		capture(request)
		respond(content = "ok", status = status)
	},
) { expectSuccess = false }

class HttpRunnerTest :
	ShouldSpec({
		context("a successful GET") {
			should("return a success result with the status") {
				runBlocking {
					val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), timeout = 5.seconds)
					val result = KtorHttpRunner(clientReturning(HttpStatusCode.OK), config).execute()
					result.isSuccess shouldBe true
					result.error shouldBe RequestError.None(200)
				}
			}
		}
		context("a POST with headers and body") {
			should("send the configured method, headers, and body") {
				runBlocking {
					var seen: HttpRequestData? = null
					val client = clientReturning(HttpStatusCode.Created) { seen = it }
					val config =
						RunConfig(
							url = "http://x",
							mode = LoadMode.FixedConcurrency(1),
							method = "POST",
							body = "payload",
							headers = listOf("X-A" to "1"),
						)
					KtorHttpRunner(client, config).execute()
					seen!!.method.value shouldBe "POST"
					seen!!.headers["X-A"] shouldBe "1"
					(seen!!.body as TextContent).text shouldBe "payload"
				}
			}
		}
		context("a 500 response") {
			should("be classified as an http error") {
				runBlocking {
					val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1))
					val result = KtorHttpRunner(clientReturning(HttpStatusCode.InternalServerError), config).execute()
					result.error shouldBe RequestError.HttpError(500)
				}
			}
		}
		context("a request that fails after a while") {
			should("report how long it ran and the failure message") {
				runBlocking {
					val client =
						HttpClient(
							MockEngine {
								delay(200.milliseconds)
								throw IOException("Connection reset by peer")
							},
						)
					val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), timeout = 5.seconds)
					val result = KtorHttpRunner(client, config).execute()
					result.error shouldBe RequestError.Other("Connection reset by peer")
					result.latency shouldBeGreaterThanOrEqualTo 200.milliseconds
				}
			}
		}
		context("a request slower than --timeout") {
			should("be a timeout lasting the configured timeout") {
				runBlocking {
					val client =
						HttpClient(
							MockEngine {
								delay(5.seconds)
								respond(content = "late")
							},
						)
					val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), timeout = 100.milliseconds)
					KtorHttpRunner(client, config).execute() shouldBe RequestResult(100.milliseconds, RequestError.Timeout)
				}
			}
		}
	})
