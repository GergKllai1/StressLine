package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.cio.CIOEngineConfig
import kotlin.time.Duration.Companion.seconds

private fun engineConfigFor(config: RunConfig): CIOEngineConfig = buildHttpClient(config).use { it.engine.config as CIOEngineConfig }

class HttpClientFactoryTest :
	ShouldSpec({
		context("sizing the CIO engine") {
			should("allow the run's connection limit in total and per host") {
				val engine = engineConfigFor(RunConfig(url = "http://x", mode = LoadMode.TargetRate(800), timeout = 60.seconds))
				engine.maxConnectionsCount shouldBe 48_000
				engine.endpoint.maxConnectionsPerRoute shouldBe 48_000
			}
			should("use --timeout as the request and connect timeout") {
				val engine = engineConfigFor(RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), timeout = 60.seconds))
				engine.requestTimeout shouldBe 60_000L
				engine.endpoint.connectTimeout shouldBe 60_000L
			}
		}
		context("TLS verification") {
			should("trust any certificate with --insecure") {
				engineConfigFor(RunConfig(url = "https://x", mode = LoadMode.FixedConcurrency(1), insecure = true))
					.https.trustManager
					.shouldNotBeNull()
			}
			should("keep the default trust manager otherwise") {
				engineConfigFor(RunConfig(url = "https://x", mode = LoadMode.FixedConcurrency(1)))
					.https.trustManager
					.shouldBeNull()
			}
		}
	})
