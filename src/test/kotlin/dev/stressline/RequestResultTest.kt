package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.ConnectException

class RequestResultTest :
	ShouldSpec({
		context("classifying HTTP status codes") {
			should("treat 2xx as success") {
				RequestError.fromStatus(200) shouldBe RequestError.None(200)
			}
			should("treat 3xx as success") {
				RequestError.fromStatus(301) shouldBe RequestError.None(301)
			}
			should("treat 4xx as an http error carrying the code") {
				RequestError.fromStatus(404) shouldBe RequestError.HttpError(404)
			}
			should("treat 5xx as an http error") {
				RequestError.fromStatus(503) shouldBe RequestError.HttpError(503)
			}
		}
		context("classifying exceptions") {
			should("map 'Connection refused' to ConnectionRefused") {
				RequestError.fromException(ConnectException("Connection refused")) shouldBe RequestError.ConnectionRefused
			}
			should("map 'Too many open files' to TooManyFiles") {
				RequestError.fromException(RuntimeException("socket: Too many open files")) shouldBe RequestError.TooManyFiles
			}
			should("map anything else to Other with the message") {
				RequestError.fromException(RuntimeException("boom")).shouldBeInstanceOf<RequestError.Other>()
			}
		}
		context("isSuccess") {
			should("be true only for None") {
				RequestResult(kotlin.time.Duration.ZERO, RequestError.None(200)).isSuccess shouldBe true
				RequestResult(kotlin.time.Duration.ZERO, RequestError.HttpError(500)).isSuccess shouldBe false
			}
		}
	})
