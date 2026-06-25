package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class RunConfigTest : ShouldSpec({
    context("parsing a header line") {
        should("split on the first colon and trim") {
            parseHeader("Content-Type: application/json") shouldBe ("Content-Type" to "application/json")
        }
        should("preserve colons in the value") {
            parseHeader("X-Url: http://a:8080") shouldBe ("X-Url" to "http://a:8080")
        }
        should("reject a line without a colon") {
            shouldThrow<IllegalArgumentException> { parseHeader("nope") }
        }
    }
    context("RunConfig defaults") {
        should("default stop, timeout, and progress") {
            val c = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(4))
            c.stop shouldBe StopCondition.ForDuration(kotlin.time.Duration.parse("10s"))
            c.showProgress shouldBe true
        }
    }
})
