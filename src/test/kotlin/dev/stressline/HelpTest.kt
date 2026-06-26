package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.string.shouldContain

class HelpTest :
	ShouldSpec({
		context("help text") {
			should("cover usage, key flags, examples, and rules") {
				val t = Help.text
				t shouldContain "stressline <URL>"
				t shouldContain "--concurrency"
				t shouldContain "--rate"
				t shouldContain "exactly one"
				t shouldContain "@FILE"
				t shouldContain "--json"
				t shouldContain "--json-out"
				t shouldContain "--fail-if-p95"
				t shouldContain "Examples:"
				t shouldContain "jq"
			}
		}
	})
