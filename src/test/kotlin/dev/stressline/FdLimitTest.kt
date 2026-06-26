package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

private class FakeFdLimit(
	var soft: Long,
	val hard: Long,
) : FdLimit {
	var raisedTo: Long? = null

	override fun current() = soft to hard

	override fun raiseTo(needed: Long): Long {
		raisedTo = needed
		soft = minOf(needed, hard)
		return soft
	}
}

class FdLimitTest :
	ShouldSpec({
		context("planning a limit change") {
			should("not raise when the soft limit already covers needs plus headroom") {
				FdLimitPlanner.plan(needed = 100, soft = 1024, hard = 1048576).shouldRaise shouldBe false
			}
			should("raise to needed plus headroom when below the hard limit") {
				val plan = FdLimitPlanner.plan(needed = 5000, soft = 1024, hard = 1048576)
				plan.shouldRaise shouldBe true
				plan.target shouldBe 5064
				plan.sufficient shouldBe true
			}
			should("cap at the hard limit and report insufficiency") {
				val plan = FdLimitPlanner.plan(needed = 2_000_000, soft = 1024, hard = 4096)
				plan.shouldRaise shouldBe true
				plan.target shouldBe 4096
				plan.sufficient shouldBe false
			}
		}
		context("ensureFdLimit") {
			should("raise the fake limit when concurrency exceeds it") {
				val fake = FakeFdLimit(soft = 1024, hard = 1048576)
				val logs = mutableListOf<String>()
				ensureFdLimit(5000, fake) { logs.add(it) }
				fake.raisedTo shouldBe 5064
				logs.size shouldBe 1
			}
			should("do nothing when the limit already suffices") {
				val fake = FakeFdLimit(soft = 1024, hard = 1048576)
				val logs = mutableListOf<String>()
				ensureFdLimit(100, fake) { logs.add(it) }
				fake.raisedTo shouldBe null
				logs.size shouldBe 0
			}
		}
	})
