package dev.stressline

import kotlin.time.Duration

data class RequestResult(
    val latency: Duration,
    val error: RequestError,
) {
    val isSuccess: Boolean get() = error is RequestError.None
}

sealed interface RequestError {
    data class None(val status: Int) : RequestError
    data class HttpError(val status: Int) : RequestError
    data object Timeout : RequestError
    data object ConnectionRefused : RequestError
    data object TooManyFiles : RequestError
    data class Other(val message: String) : RequestError

    companion object {
        fun fromStatus(status: Int): RequestError =
            if (status in 200..399) None(status) else HttpError(status)

        fun fromException(e: Throwable): RequestError {
            val msg = e.message ?: e::class.simpleName ?: "unknown"
            return when {
                msg.contains("Too many open files", ignoreCase = true) -> TooManyFiles
                msg.contains("Connection refused", ignoreCase = true) -> ConnectionRefused
                else -> Other(msg)
            }
        }
    }
}
