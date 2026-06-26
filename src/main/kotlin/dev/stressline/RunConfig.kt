package dev.stressline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface LoadMode {
	data class FixedConcurrency(
		val workers: Int,
	) : LoadMode

	data class TargetRate(
		val rps: Int,
	) : LoadMode
}

sealed interface StopCondition {
	data class Requests(
		val count: Int,
	) : StopCondition

	data class ForDuration(
		val duration: Duration,
	) : StopCondition
}

sealed interface JsonOutTarget {
	data object Auto : JsonOutTarget

	data class File(
		val path: String,
	) : JsonOutTarget
}

data class RunConfig(
	val url: String,
	val mode: LoadMode,
	val method: String = "GET",
	val headers: List<Pair<String, String>> = emptyList(),
	val body: String? = null,
	val stop: StopCondition = StopCondition.ForDuration(10.seconds),
	val timeout: Duration = 5.seconds,
	val insecure: Boolean = false,
	val showProgress: Boolean = true,
	val jsonToStdout: Boolean = false,
	val jsonOut: JsonOutTarget? = null,
	val failIfErrorRate: Double? = null,
	val failIfP95: Duration? = null,
)

fun parseHeader(raw: String): Pair<String, String> {
	val idx = raw.indexOf(':')
	require(idx > 0) { "Invalid header '$raw' (expected 'Name: Value')" }
	return raw.substring(0, idx).trim() to raw.substring(idx + 1).trim()
}
