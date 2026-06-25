package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private val trustAll = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun main(args: Array<String>) {
    val config = try {
        parseArgs(args)
    } catch (e: CliValidationException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    }

    if (config.mode is LoadMode.FixedConcurrency) {
        ensureFdLimit((config.mode as LoadMode.FixedConcurrency).workers, JnaFdLimit()) { println(it) }
    }

    val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            if (config.insecure) {
                https { trustManager = trustAll }
            }
        }
    }

    val collector = ChannelMetricsCollector()
    val results = Channel<RequestResult>(capacity = 1024)
    val runner = KtorHttpRunner(client, config)
    val engine = LoadEngine(runner, results)
    val reporter = ProgressReporter(collector, enabled = config.showProgress)

    runBlocking {
        val collectorJob = launch { collector.consume(results) }
        val progressJob = launch { reporter.runLive() }
        val start = TimeSource.Monotonic.markNow()
        try {
            engine.run(config.mode, config.stop)
        } finally {
            results.close()
            collectorJob.join()
            progressJob.cancelAndJoin()
            client.close()
        }
        println()
        println(Report.summary(collector.snapshot(), start.elapsedNow()))
    }
}
