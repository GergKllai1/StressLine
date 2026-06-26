package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

private fun clientReturning(
  status: HttpStatusCode,
  capture: (HttpRequestData) -> Unit = {},
): HttpClient =
  HttpClient(
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
  })
