package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.measureTimedValue

interface HttpRunner {
	suspend fun execute(): RequestResult
}

class KtorHttpRunner(
	private val client: HttpClient,
	private val config: RunConfig,
) : HttpRunner {
	override suspend fun execute(): RequestResult = try {
		val timed =
			measureTimedValue {
				withTimeout(config.timeout) {
					val response =
						client.request(config.url) {
							method = HttpMethod.parse(config.method)
							config.headers.forEach { (k, v) -> header(k, v) }
							config.body?.let { setBody(it) }
						}
					response.bodyAsText() // drain so the connection is released
					response.status.value
				}
			}
		RequestResult(timed.duration, RequestError.fromStatus(timed.value))
	} catch (_: TimeoutCancellationException) {
		RequestResult(config.timeout, RequestError.Timeout)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		RequestResult(Duration.ZERO, RequestError.fromException(e))
	}
}
