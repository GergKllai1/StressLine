package dev.stressline

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Structure

interface FdLimit {
	/** (soft, hard) limits, or null if unsupported on this platform. */
	fun current(): Pair<Long, Long>?

	/** Raise the soft limit toward [needed]; returns the resulting soft limit, or null if unsupported/failed. */
	fun raiseTo(needed: Long): Long?
}

object FdLimitPlanner {
	private const val HEADROOM = 64L

	data class Plan(
		val shouldRaise: Boolean,
		val target: Long,
		val sufficient: Boolean,
	)

	fun plan(
		needed: Long,
		soft: Long,
		hard: Long,
	): Plan {
		val want = needed + HEADROOM
		if (want <= soft) return Plan(shouldRaise = false, target = soft, sufficient = true)
		val target = minOf(want, hard)
		return Plan(shouldRaise = true, target = target, sufficient = target >= want)
	}
}

fun ensureFdLimit(
	neededConcurrency: Int,
	fd: FdLimit,
	log: (String) -> Unit,
) {
	val (soft, hard) = fd.current() ?: return // unsupported platform: no-op
	val plan = FdLimitPlanner.plan(neededConcurrency.toLong(), soft, hard)
	if (!plan.shouldRaise) return
	val result = fd.raiseTo(plan.target)
	if (result == null) {
		log("Warning: could not raise the file-descriptor limit; you may hit 'Too many open files'. Try: ulimit -n ${plan.target}")
		return
	}
	if (plan.sufficient) {
		log("Raised file-descriptor soft limit to $result for $neededConcurrency concurrent connections.")
	} else {
		log(
			"Warning: raised FD soft limit to $result, but $neededConcurrency connections may need more. Raise the hard limit (ulimit -Hn) or run with sudo.",
		)
	}
}

class JnaFdLimit : FdLimit {
	@Structure.FieldOrder("cur", "max")
	class RLimit : Structure() {
		@JvmField var cur: Long = 0

		@JvmField var max: Long = 0
	}

	private interface CLib : Library {
		fun getrlimit(
			resource: Int,
			rlim: RLimit,
		): Int

		fun setrlimit(
			resource: Int,
			rlim: RLimit,
		): Int
	}

	// RLIMIT_NOFILE: 7 on Linux, 8 on macOS.
	private val resource = if (Platform.isMac()) 8 else 7

	private val libc: CLib? =
		if (Platform.isWindows()) {
			null
		} else {
			runCatching { Native.load("c", CLib::class.java) }.getOrNull()
		}

	override fun current(): Pair<Long, Long>? {
		val c = libc ?: return null
		val r = RLimit()
		if (c.getrlimit(resource, r) != 0) return null
		return r.cur to r.max
	}

	override fun raiseTo(needed: Long): Long? {
		val c = libc ?: return null
		val r = RLimit()
		if (c.getrlimit(resource, r) != 0) return null
		if (r.cur >= needed) return r.cur
		r.cur = minOf(needed, r.max)
		if (c.setrlimit(resource, r) != 0) return null
		return r.cur
	}
}
