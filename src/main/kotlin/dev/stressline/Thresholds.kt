package dev.stressline

object Thresholds {
  fun evaluate(
    s: Snapshot,
    config: RunConfig,
  ): List<String> {
    val breaches = mutableListOf<String>()
    config.failIfErrorRate?.let { limit ->
      val rate = if (s.total == 0L) 0.0 else s.failed.toDouble() / s.total * 100.0
      if (rate > limit) {
        val rateStr = String.format(java.util.Locale.US, "%.2f", rate)
        breaches.add("error rate $rateStr% exceeds threshold $limit%")
      }
    }
    config.failIfP95?.let { limit ->
      if (s.latencyP95 > limit) {
        breaches.add("p95 ${s.latencyP95.inWholeMilliseconds}ms exceeds threshold ${limit.inWholeMilliseconds}ms")
      }
    }
    return breaches
  }
}
