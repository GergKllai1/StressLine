package dev.stressline

import kotlinx.coroutines.channels.Channel
import org.HdrHistogram.Histogram
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

data class Snapshot(
	val total: Long,
	val success: Long,
	val failed: Long,
	val errorCounts: Map<String, Long>,
	val statusCounts: Map<Int, Long>,
	val latencyMin: Duration,
	val latencyMean: Duration,
	val latencyP50: Duration,
	val latencyP90: Duration,
	val latencyP95: Duration,
	val latencyP99: Duration,
	val latencyMax: Duration,
)

interface MetricsCollector {
	suspend fun consume(channel: Channel<RequestResult>)

	fun snapshot(): Snapshot
}

class ChannelMetricsCollector : MetricsCollector {
	// Track up to 1 hour in nanoseconds, 3 significant digits.
	private val histogram = Histogram(3_600_000_000_000L, 3)
	private var total = 0L
	private var success = 0L
	private var failed = 0L
	private val errorCounts = HashMap<String, Long>()
	private val statusCounts = HashMap<Int, Long>()
	private val lock = Any()

	override suspend fun consume(channel: Channel<RequestResult>) {
		for (result in channel) record(result)
	}

	private fun record(result: RequestResult) = synchronized(lock) {
		total++
		val nanos = result.latency.inWholeNanoseconds.coerceIn(1L, histogram.highestTrackableValue)
		histogram.recordValue(nanos)
		when (val e = result.error) {
			is RequestError.None -> {
				success++
				statusCounts.merge(e.status, 1L, Long::plus)
			}
			is RequestError.HttpError -> {
				failed++
				statusCounts.merge(e.status, 1L, Long::plus)
				bump("http")
			}
			RequestError.Timeout -> {
				failed++
				bump("timeout")
			}
			RequestError.ConnectionRefused -> {
				failed++
				bump("connection-refused")
			}
			RequestError.TooManyFiles -> {
				failed++
				bump("too-many-files")
			}
			is RequestError.Other -> {
				failed++
				bump("other")
			}
		}
	}

	private fun bump(label: String) {
		errorCounts.merge(label, 1L, Long::plus)
	}

	override fun snapshot(): Snapshot = synchronized(lock) {
		fun pct(p: Double) = histogram.getValueAtPercentile(p).nanoseconds
		Snapshot(
			total = total,
			success = success,
			failed = failed,
			errorCounts = errorCounts.toMap(),
			statusCounts = statusCounts.toMap(),
			latencyMin = (if (total == 0L) 0L else histogram.minValue).nanoseconds,
			latencyMean = (if (total == 0L) 0.0 else histogram.mean).toLong().nanoseconds,
			latencyP50 = pct(50.0),
			latencyP90 = pct(90.0),
			latencyP95 = pct(95.0),
			latencyP99 = pct(99.0),
			latencyMax = (if (total == 0L) 0L else histogram.maxValue).nanoseconds,
		)
	}
}
