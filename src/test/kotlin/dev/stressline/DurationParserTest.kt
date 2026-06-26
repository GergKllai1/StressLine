package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationParserTest :
	ShouldSpec({
		context("parsing valid duration strings") {
			should("parse milliseconds") { DurationParser.parse("500ms") shouldBe 500.milliseconds }
			should("parse seconds") { DurationParser.parse("30s") shouldBe 30.seconds }
			should("parse minutes") { DurationParser.parse("2m") shouldBe 2.minutes }
			should("trim surrounding whitespace") { DurationParser.parse("  10s ") shouldBe 10.seconds }
		}
		context("parsing invalid duration strings") {
			should("reject a missing unit") { shouldThrow<IllegalArgumentException> { DurationParser.parse("30") } }
			should("reject an unknown unit") { shouldThrow<IllegalArgumentException> { DurationParser.parse("5h") } }
			should("reject non-numeric input") { shouldThrow<IllegalArgumentException> { DurationParser.parse("abc") } }
		}
	})
