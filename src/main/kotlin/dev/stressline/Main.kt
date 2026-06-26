package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess
import kotlin.time.TimeSource

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

private fun writeJsonFile(
  target: JsonOutTarget,
  json: String,
) {
  val path =
    when (target) {
      is JsonOutTarget.Auto -> {
        val ts =
          java.time.LocalDateTime
            .now()
            .format(
              java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss"),
            )
        "stressline-run-$ts.json"
      }
      is JsonOutTarget.File -> target.path
    }
  try {
    val file = java.io.File(path)
    file.parentFile?.mkdirs()
    file.writeText(json)
    System.err.println("Wrote JSON to $path")
  } catch (e: Exception) {
    System.err.println("error: could not write JSON to $path: ${e.message}")
    exitProcess(2)
  }
}

fun main(args: Array<String>) {
  if (args.any { it == "-h" || it == "--help" }) {
    println(Help.text)
    exitProcess(0)
  }

  val config =
    try {
      parseArgs(args)
    } catch (e: CliValidationException) {
      System.err.println("error: ${e.message}")
      exitProcess(2)
    }

  when (val m = config.mode) {
    is LoadMode.FixedConcurrency -> ensureFdLimit(m.workers, JnaFdLimit()) { println(it) }
    is LoadMode.TargetRate -> ensureFdLimit(m.rps * 2, JnaFdLimit()) { println(it) }
  }

  val client =
    HttpClient(CIO) {
      expectSuccess = false
      engine {
        if (config.insecure) {
          https { trustManager = trustAll }
        }
      }
    }

  val collector = ChannelMetricsCollector()
  val start = TimeSource.Monotonic.markNow()
  val summaryPrinted = AtomicBoolean(false)

  fun printSummaryOnce() {
    if (summaryPrinted.compareAndSet(false, true)) {
      if (config.jsonToStdout) {
        println(Report.json(collector.snapshot(), start.elapsedNow(), config))
      } else {
        println()
        println(Report.summary(collector.snapshot(), start.elapsedNow()))
      }
    }
  }

  Runtime.getRuntime().addShutdownHook(Thread { printSummaryOnce() })

  val results = Channel<RequestResult>(capacity = 1024)
  val runner = KtorHttpRunner(client, config)
  val engine = LoadEngine(runner, results)
  val progressOut: Appendable = if (config.jsonToStdout) System.err else System.out
  val reporter = ProgressReporter(collector, enabled = config.showProgress, out = progressOut)

  runBlocking {
    val collectorJob = launch { collector.consume(results) }
    val progressJob = launch { reporter.runLive() }
    try {
      engine.run(config.mode, config.stop)
    } finally {
      results.close()
      collectorJob.join()
      progressJob.cancelAndJoin()
      client.close()
      printSummaryOnce()
    }
  }

  val finalSnapshot = collector.snapshot()
  config.jsonOut?.let { target ->
    writeJsonFile(target, Report.json(finalSnapshot, start.elapsedNow(), config))
  }

  val breaches = Thresholds.evaluate(finalSnapshot, config)
  if (breaches.isNotEmpty()) {
    breaches.forEach { System.err.println("threshold breach: $it") }
    exitProcess(1)
  }
}
