package dev.stressline

import kotlin.time.Duration
import kotlin.time.DurationUnit

object Report {
    fun line(s: Snapshot, elapsed: Duration): String {
        val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
        val rps = s.total / secs
        return "%5.1fs | sent %d | ok %d | err %d | %.0f req/s | p95 %dms".format(
            secs, s.total, s.success, s.failed, rps, s.latencyP95.inWholeMilliseconds,
        )
    }

    fun summary(s: Snapshot, elapsed: Duration): String {
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
        sb.appendLine("  min %d | mean %d | p50 %d | p90 %d | p95 %d | p99 %d | max %d".format(
            s.latencyMin.inWholeMilliseconds, s.latencyMean.inWholeMilliseconds,
            s.latencyP50.inWholeMilliseconds, s.latencyP90.inWholeMilliseconds,
            s.latencyP95.inWholeMilliseconds, s.latencyP99.inWholeMilliseconds,
            s.latencyMax.inWholeMilliseconds,
        ))
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
}
