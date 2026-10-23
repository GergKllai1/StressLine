package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Builds the CIO client for a run, allowing [connectionLimit] connections and [RunConfig.timeout] per request and connect. */
fun buildHttpClient(config: RunConfig): HttpClient {
	val connections = config.connectionLimit()
	val timeoutMs = config.timeout.inWholeMilliseconds
	return HttpClient(CIO) {
		expectSuccess = false
		// CIO's defaults (100 connections per host, 15 s request timeout) would otherwise cap the run.
		engine {
			maxConnectionsCount = connections
			requestTimeout = timeoutMs
			endpoint {
				maxConnectionsPerRoute = connections
				connectTimeout = timeoutMs
			}
			if (config.insecure) {
				https { trustManager = trustAll }
			}
		}
	}
}

private val trustAll =
	object : X509TrustManager {
		override fun checkClientTrusted(
			chain: Array<out X509Certificate>?,
			authType: String?,
		) {}

		override fun checkServerTrusted(
			chain: Array<out X509Certificate>?,
			authType: String?,
		) {}

		override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
	}
