package dev.stressline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object DurationParser {
    private val regex = Regex("""^(\d+)(ms|s|m)$""")

    fun parse(input: String): Duration {
        val match = regex.matchEntire(input.trim())
            ?: throw IllegalArgumentException("Invalid duration '$input' (expected e.g. 500ms, 30s, 2m)")
        val value = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "ms" -> value.milliseconds
            "s" -> value.seconds
            "m" -> value.minutes
            else -> error("unreachable")
        }
    }
}
