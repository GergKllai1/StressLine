package dev.stressline

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

class ProgressReporter(
  private val collector: MetricsCollector,
  private val enabled: Boolean,
  private val out: Appendable = System.out,
) {
  suspend fun runLive(intervalMs: Long = 200) {
    if (!enabled) return
    val start = TimeSource.Monotonic.markNow()
    while (coroutineContext.isActive) {
      out.append('\r').append(Report.line(collector.snapshot(), start.elapsedNow()))
      delay(intervalMs)
    }
  }
}
