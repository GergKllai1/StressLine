package dev.stressline

import kotlin.time.Duration
import kotlin.time.DurationUnit

object Report {
  fun line(
    s: Snapshot,
    elapsed: Duration,
  ): String {
    val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
    val rps = s.total / secs
    return "%5.1fs | sent %d | ok %d | err %d | %.0f req/s | p95 %dms".format(
      secs,
      s.total,
      s.success,
      s.failed,
      rps,
      s.latencyP95.inWholeMilliseconds,
    )
  }

  fun summary(
    s: Snapshot,
    elapsed: Duration,
  ): String {
    val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
    val rps = s.total / secs
    val sb = StringBuilder()
    sb.appendLine("--- StressLine results ---")
    sb.appendLine("Total:       ${s.total}")
    sb.appendLine("Success:     ${s.success}")
    sb.appendLine("Failed:      ${s.failed}")
    sb.appendLine("Duration:    %.2fs".format(secs))
    sb.appendLine("Throughput:  %.0f req/s".format(rps))
    sb.appendLine("Latency (ms):")
    sb.appendLine(
      "  min %d | mean %d | p50 %d | p90 %d | p95 %d | p99 %d | max %d".format(
        s.latencyMin.inWholeMilliseconds,
        s.latencyMean.inWholeMilliseconds,
        s.latencyP50.inWholeMilliseconds,
        s.latencyP90.inWholeMilliseconds,
        s.latencyP95.inWholeMilliseconds,
        s.latencyP99.inWholeMilliseconds,
        s.latencyMax.inWholeMilliseconds,
      ),
    )
    if (s.errorCounts.isNotEmpty()) {
      sb.appendLine("Errors:")
      s.errorCounts.toSortedMap().forEach { (k, v) -> sb.appendLine("  $k: $v") }
    }
    if (s.statusCounts.isNotEmpty()) {
      sb.appendLine("Status codes:")
      s.statusCounts.toSortedMap().forEach { (k, v) -> sb.appendLine("  $k: $v") }
    }
    return sb.toString().trimEnd()
  }

  fun json(
    s: Snapshot,
    elapsed: Duration,
    config: RunConfig,
  ): String {
    val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
    val rps = s.total / secs
    val errorRate = if (s.total == 0L) 0.0 else s.failed.toDouble() / s.total * 100.0
    val (modeType, modeValue) =
      when (val m = config.mode) {
        is LoadMode.FixedConcurrency -> "concurrency" to m.workers
        is LoadMode.TargetRate -> "rate" to m.rps
      }
    val errors =
      s.errorCounts.toSortedMap().entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
        "\"${escapeJson(it.key)}\":${it.value}"
      }
    val statusCodes =
      s.statusCounts.toSortedMap().entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
        "\"${it.key}\":${it.value}"
      }
    return buildString {
      append("{")
      append("\"url\":\"").append(escapeJson(config.url)).append("\",")
      append("\"mode\":{\"type\":\"")
        .append(modeType)
        .append("\",\"value\":")
        .append(modeValue)
        .append("},")
      append("\"total\":").append(s.total).append(",")
      append("\"success\":").append(s.success).append(",")
      append("\"failed\":").append(s.failed).append(",")
      append("\"errorRatePct\":").append(num2(errorRate)).append(",")
      append("\"durationSeconds\":").append(num2(secs)).append(",")
      append("\"throughputPerSec\":").append(num2(rps)).append(",")
      append("\"latencyMs\":{")
      append("\"min\":").append(s.latencyMin.inWholeMilliseconds).append(",")
      append("\"mean\":").append(s.latencyMean.inWholeMilliseconds).append(",")
      append("\"p50\":").append(s.latencyP50.inWholeMilliseconds).append(",")
      append("\"p90\":").append(s.latencyP90.inWholeMilliseconds).append(",")
      append("\"p95\":").append(s.latencyP95.inWholeMilliseconds).append(",")
      append("\"p99\":").append(s.latencyP99.inWholeMilliseconds).append(",")
      append("\"max\":").append(s.latencyMax.inWholeMilliseconds)
      append("},")
      append("\"errors\":").append(errors).append(",")
      append("\"statusCodes\":").append(statusCodes)
      append("}")
    }
  }

  private fun num2(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)

  private fun escapeJson(text: String): String =
    buildString {
      for (c in text) {
        when (c) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(c)
        }
      }
    }
}
