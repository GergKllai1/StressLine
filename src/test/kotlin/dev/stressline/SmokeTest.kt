package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class SmokeTest : ShouldSpec({
    context("project scaffolding") {
        should("run a trivial assertion") {
            (1 + 1) shouldBe 2
        }
    }
})
