# StressLine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local HTTP/HTTPS stress-testing CLI that drives concurrent requests with coroutines and reports latency, throughput, and categorized errors.

**Architecture:** A single Gradle (Kotlin DSL) project. Worker coroutines execute requests via a Ktor CIO client and send small `RequestResult` records into a buffered `Channel`; a single `MetricsCollector` coroutine drains the channel into an HdrHistogram and counters; a `ProgressReporter` renders a live line and a final summary. Two load modes (fixed concurrency, target RPS) live behind one `LoadEngine`. A startup `FdLimit` step raises the file-descriptor soft limit when needed.

**Tech Stack:** Kotlin, kotlinx-cli, kotlinx.coroutines, Ktor client (CIO), HdrHistogram, JNA, Kotest (ShouldSpec).

## Global Constraints

- Kotlin `2.1.0`, JVM toolchain `21`, Gradle wrapper `8.10` or newer.
- Package root: `dev.stressline`. Source under `src/main/kotlin/dev/stressline/`, tests under `src/test/kotlin/dev/stressline/`.
- Dependency versions (exact): kotlinx-cli `0.3.6`; kotlinx-coroutines-core / -test `1.10.1`; Ktor `3.0.3` (client-core, client-cio, client-mock); HdrHistogram `2.2.2`; JNA `5.15.0`; Kotest `5.9.1` (runner-junit5, assertions-core); shadow plugin `com.gradleup.shadow` `8.3.5`.
- All tests use Kotest `ShouldSpec` with `context(...) { should(...) { ... } }` nesting and `kotest-assertions-core` matchers (`shouldBe`, etc.).
- TDD: write the failing test first, see it fail, implement minimally, see it pass, commit.
- Run tests with `./gradlew test --tests "dev.stressline.<ClassName>"`. The full suite runs with `./gradlew test`.
- Coroutine cancellation must always propagate: never swallow `CancellationException`.

---

### Task 1: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `.gitignore`
- Create: `src/test/kotlin/dev/stressline/SmokeTest.kt`
- Create: Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable project where `./gradlew test` runs Kotest.

- [ ] **Step 1: Create the Gradle wrapper**

Run (requires a system `gradle`; if absent, install via SDKMAN `sdk install gradle 8.10`):

```bash
gradle wrapper --gradle-version 8.10
```

Expected: creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.{jar,properties}`.

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "StressLine"
```

- [ ] **Step 3: Write `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.stressline"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("net.java.dev.jna:jna:5.15.0")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.stressline.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Write `.gitignore`**

```
.gradle/
build/
```

- [ ] **Step 5: Write the smoke test** — `src/test/kotlin/dev/stressline/SmokeTest.kt`

```kotlin
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
```

- [ ] **Step 6: Run the smoke test**

Run: `./gradlew test --tests "dev.stressline.SmokeTest"`
Expected: PASS (BUILD SUCCESSFUL). This proves the toolchain, dependencies, and Kotest runner all resolve.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts .gitignore gradlew gradlew.bat gradle src/test
git commit -m "chore: scaffold Gradle project with Kotest"
```

---

### Task 2: Duration parser

**Files:**
- Create: `src/main/kotlin/dev/stressline/DurationParser.kt`
- Test: `src/test/kotlin/dev/stressline/DurationParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object DurationParser { fun parse(input: String): kotlin.time.Duration }`. Accepts `<integer><unit>` where unit ∈ `ms`, `s`, `m`. Throws `IllegalArgumentException` on anything else.

- [ ] **Step 1: Write the failing test** — `DurationParserTest.kt`

```kotlin
package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationParserTest : ShouldSpec({
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.DurationParserTest"`
Expected: FAIL — `DurationParser` is unresolved.

- [ ] **Step 3: Write the implementation** — `DurationParser.kt`

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.DurationParserTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/DurationParser.kt src/test/kotlin/dev/stressline/DurationParserTest.kt
git commit -m "feat: add duration string parser"
```

---

### Task 3: RequestResult and error classification

**Files:**
- Create: `src/main/kotlin/dev/stressline/RequestResult.kt`
- Test: `src/test/kotlin/dev/stressline/RequestResultTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class RequestResult(val latency: kotlin.time.Duration, val error: RequestError)` with `val isSuccess: Boolean`.
  - `sealed interface RequestError` with: `data class None(val status: Int)`, `data class HttpError(val status: Int)`, `data object Timeout`, `data object ConnectionRefused`, `data object TooManyFiles`, `data class Other(val message: String)`.
  - Companion: `RequestError.fromStatus(status: Int): RequestError` and `RequestError.fromException(e: Throwable): RequestError`.

- [ ] **Step 1: Write the failing test** — `RequestResultTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.ConnectException

class RequestResultTest : ShouldSpec({
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.RequestResultTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation** — `RequestResult.kt`

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.RequestResultTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/RequestResult.kt src/test/kotlin/dev/stressline/RequestResultTest.kt
git commit -m "feat: add RequestResult and error classification"
```

---

### Task 4: RunConfig value types and header parsing

**Files:**
- Create: `src/main/kotlin/dev/stressline/RunConfig.kt`
- Test: `src/test/kotlin/dev/stressline/RunConfigTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed interface LoadMode` with `data class FixedConcurrency(val workers: Int)` and `data class TargetRate(val rps: Int)`.
  - `sealed interface StopCondition` with `data class Requests(val count: Int)` and `data class ForDuration(val duration: Duration)`.
  - `data class RunConfig(url, method="GET", headers=emptyList(), body=null, mode, stop=ForDuration(10s), timeout=5s, insecure=false, showProgress=true)` where `headers: List<Pair<String,String>>`, `body: String?`.
  - `fun parseHeader(raw: String): Pair<String, String>` — splits on the first `:`, trims both sides; throws `IllegalArgumentException` if no colon.

- [ ] **Step 1: Write the failing test** — `RunConfigTest.kt`

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.RunConfigTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation** — `RunConfig.kt`

```kotlin
package dev.stressline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface LoadMode {
    data class FixedConcurrency(val workers: Int) : LoadMode
    data class TargetRate(val rps: Int) : LoadMode
}

sealed interface StopCondition {
    data class Requests(val count: Int) : StopCondition
    data class ForDuration(val duration: Duration) : StopCondition
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
)

fun parseHeader(raw: String): Pair<String, String> {
    val idx = raw.indexOf(':')
    require(idx > 0) { "Invalid header '$raw' (expected 'Name: Value')" }
    return raw.substring(0, idx).trim() to raw.substring(idx + 1).trim()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.RunConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/RunConfig.kt src/test/kotlin/dev/stressline/RunConfigTest.kt
git commit -m "feat: add RunConfig value types and header parsing"
```

---

### Task 5: CLI argument parsing

**Files:**
- Create: `src/main/kotlin/dev/stressline/Cli.kt`
- Test: `src/test/kotlin/dev/stressline/CliTest.kt`

**Interfaces:**
- Consumes: `RunConfig`, `LoadMode`, `StopCondition`, `parseHeader`, `DurationParser`.
- Produces:
  - `class CliValidationException(message: String) : Exception(message)`.
  - `fun parseArgs(args: Array<String>): RunConfig`. Enforces: exactly one of `--concurrency`/`--rate` (else `CliValidationException`); `--requests` and `--duration` mutually exclusive, defaulting to `ForDuration(10s)`.

Note: all tests pass `--url` so kotlinx-cli never triggers its built-in exit-on-missing-required behavior.

- [ ] **Step 1: Write the failing test** — `CliTest.kt`

```kotlin
package dev.stressline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class CliTest : ShouldSpec({
    context("fixed-concurrency mode") {
        should("build a RunConfig with workers and default 10s duration") {
            val c = parseArgs(arrayOf("--url", "http://x", "--concurrency", "8"))
            c.url shouldBe "http://x"
            c.mode shouldBe LoadMode.FixedConcurrency(8)
            c.stop shouldBe StopCondition.ForDuration(10.seconds)
        }
    }
    context("target-rate mode with request count") {
        should("build a RunConfig with rps and request count") {
            val c = parseArgs(arrayOf("--url", "http://x", "--rate", "100", "--requests", "500"))
            c.mode shouldBe LoadMode.TargetRate(100)
            c.stop shouldBe StopCondition.Requests(500)
        }
    }
    context("headers, method, body, timeout") {
        should("parse repeated headers and other options") {
            val c = parseArgs(arrayOf(
                "--url", "http://x", "--concurrency", "1",
                "-X", "POST", "-d", "hello",
                "-H", "A: 1", "-H", "B: 2",
                "--timeout", "2s",
            ))
            c.method shouldBe "POST"
            c.body shouldBe "hello"
            c.headers shouldBe listOf("A" to "1", "B" to "2")
            c.timeout shouldBe 2.seconds
        }
    }
    context("mode validation") {
        should("reject specifying both concurrency and rate") {
            shouldThrow<CliValidationException> {
                parseArgs(arrayOf("--url", "http://x", "--concurrency", "1", "--rate", "1"))
            }
        }
        should("reject specifying neither concurrency nor rate") {
            shouldThrow<CliValidationException> { parseArgs(arrayOf("--url", "http://x")) }
        }
    }
    context("stop validation") {
        should("reject specifying both requests and duration") {
            shouldThrow<CliValidationException> {
                parseArgs(arrayOf("--url", "http://x", "--concurrency", "1", "--requests", "5", "--duration", "5s"))
            }
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.CliTest"`
Expected: FAIL — `parseArgs` unresolved.

- [ ] **Step 3: Write the implementation** — `Cli.kt`

```kotlin
package dev.stressline

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.required
import kotlin.time.Duration.Companion.seconds

class CliValidationException(message: String) : Exception(message)

fun parseArgs(args: Array<String>): RunConfig {
    val parser = ArgParser("stressline")
    val url by parser.option(ArgType.String, shortName = "u", fullName = "url", description = "Target URL").required()
    val method by parser.option(ArgType.String, shortName = "X", fullName = "method", description = "HTTP method").default("GET")
    val headerOpts by parser.option(ArgType.String, shortName = "H", fullName = "header", description = "Header 'Name: Value'").multiple()
    val body by parser.option(ArgType.String, shortName = "d", fullName = "body", description = "Request body")
    val concurrency by parser.option(ArgType.Int, shortName = "c", fullName = "concurrency", description = "Fixed concurrency: N virtual users")
    val rate by parser.option(ArgType.Int, shortName = "r", fullName = "rate", description = "Target requests per second")
    val requests by parser.option(ArgType.Int, shortName = "n", fullName = "requests", description = "Stop after N requests")
    val duration by parser.option(ArgType.String, shortName = "t", fullName = "duration", description = "Stop after duration e.g. 30s")
    val timeout by parser.option(ArgType.String, fullName = "timeout", description = "Per-request timeout").default("5s")
    val insecure by parser.option(ArgType.Boolean, fullName = "insecure", description = "Skip TLS verification").default(false)
    val noProgress by parser.option(ArgType.Boolean, fullName = "no-progress", description = "Disable live progress").default(false)

    parser.parse(args)

    val mode = when {
        concurrency != null && rate != null ->
            throw CliValidationException("--concurrency and --rate are mutually exclusive")
        concurrency != null -> LoadMode.FixedConcurrency(concurrency!!)
        rate != null -> LoadMode.TargetRate(rate!!)
        else -> throw CliValidationException("Exactly one of --concurrency or --rate is required")
    }

    val stop = when {
        requests != null && duration != null ->
            throw CliValidationException("--requests and --duration are mutually exclusive")
        requests != null -> StopCondition.Requests(requests!!)
        duration != null -> StopCondition.ForDuration(DurationParser.parse(duration!!))
        else -> StopCondition.ForDuration(10.seconds)
    }

    return RunConfig(
        url = url,
        mode = mode,
        method = method,
        headers = headerOpts.map(::parseHeader),
        body = body,
        stop = stop,
        timeout = DurationParser.parse(timeout),
        insecure = insecure,
        showProgress = !noProgress,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.CliTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/Cli.kt src/test/kotlin/dev/stressline/CliTest.kt
git commit -m "feat: add CLI argument parsing and validation"
```

---

### Task 6: Metrics collector

**Files:**
- Create: `src/main/kotlin/dev/stressline/MetricsCollector.kt`
- Test: `src/test/kotlin/dev/stressline/MetricsCollectorTest.kt`

**Interfaces:**
- Consumes: `RequestResult`, `RequestError`.
- Produces:
  - `data class Snapshot(total: Long, success: Long, failed: Long, errorCounts: Map<String, Long>, statusCounts: Map<Int, Long>, latencyMin/Mean/P50/P90/P95/P99/Max: Duration)`.
  - `interface MetricsCollector { suspend fun consume(channel: Channel<RequestResult>); fun snapshot(): Snapshot }`.
  - `class ChannelMetricsCollector : MetricsCollector` — drains the channel until closed, recording into an HdrHistogram + counters guarded by a lock (the collector owns this state; workers never touch it). Error category labels: `"http"`, `"timeout"`, `"connection-refused"`, `"too-many-files"`, `"other"`.

- [ ] **Step 1: Write the failing test** — `MetricsCollectorTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class MetricsCollectorTest : ShouldSpec({
    context("when draining a sequence of results") {
        should("count successes, failures, and error categories") {
            runTest {
                val collector = ChannelMetricsCollector()
                val channel = Channel<RequestResult>(Channel.UNLIMITED)
                channel.send(RequestResult(10.milliseconds, RequestError.None(200)))
                channel.send(RequestResult(10.milliseconds, RequestError.None(204)))
                channel.send(RequestResult(10.milliseconds, RequestError.HttpError(500)))
                channel.send(RequestResult(10.milliseconds, RequestError.Timeout))
                channel.close()
                collector.consume(channel)

                val s = collector.snapshot()
                s.total shouldBe 4L
                s.success shouldBe 2L
                s.failed shouldBe 2L
                s.errorCounts["http"] shouldBe 1L
                s.errorCounts["timeout"] shouldBe 1L
                s.statusCounts[200] shouldBe 1L
                s.statusCounts[204] shouldBe 1L
                s.statusCounts[500] shouldBe 1L
            }
        }
        should("compute p95 latency within histogram precision") {
            runTest {
                val collector = ChannelMetricsCollector()
                val channel = Channel<RequestResult>(Channel.UNLIMITED)
                for (ms in 1..100) channel.send(RequestResult(ms.milliseconds, RequestError.None(200)))
                channel.close()
                collector.consume(channel)

                val p95Ms = collector.snapshot().latencyP95.inWholeMilliseconds
                p95Ms shouldBeGreaterThanOrEqual 93L
                p95Ms shouldBeLessThanOrEqual 97L
            }
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.MetricsCollectorTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation** — `MetricsCollector.kt`

```kotlin
package dev.stressline

import kotlinx.coroutines.channels.Channel
import org.HdrHistogram.Histogram
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

data class Snapshot(
    val total: Long,
    val success: Long,
    val failed: Long,
    val errorCounts: Map<String, Long>,
    val statusCounts: Map<Int, Long>,
    val latencyMin: Duration,
    val latencyMean: Duration,
    val latencyP50: Duration,
    val latencyP90: Duration,
    val latencyP95: Duration,
    val latencyP99: Duration,
    val latencyMax: Duration,
)

interface MetricsCollector {
    suspend fun consume(channel: Channel<RequestResult>)
    fun snapshot(): Snapshot
}

class ChannelMetricsCollector : MetricsCollector {
    // Track up to 1 hour in nanoseconds, 3 significant digits.
    private val histogram = Histogram(3_600_000_000_000L, 3)
    private var total = 0L
    private var success = 0L
    private var failed = 0L
    private val errorCounts = HashMap<String, Long>()
    private val statusCounts = HashMap<Int, Long>()
    private val lock = Any()

    override suspend fun consume(channel: Channel<RequestResult>) {
        for (result in channel) record(result)
    }

    private fun record(result: RequestResult) = synchronized(lock) {
        total++
        val nanos = result.latency.inWholeNanoseconds.coerceIn(1L, histogram.highestTrackableValue)
        histogram.recordValue(nanos)
        when (val e = result.error) {
            is RequestError.None -> { success++; statusCounts.merge(e.status, 1L, Long::plus) }
            is RequestError.HttpError -> { failed++; statusCounts.merge(e.status, 1L, Long::plus); bump("http") }
            RequestError.Timeout -> { failed++; bump("timeout") }
            RequestError.ConnectionRefused -> { failed++; bump("connection-refused") }
            RequestError.TooManyFiles -> { failed++; bump("too-many-files") }
            is RequestError.Other -> { failed++; bump("other") }
        }
    }

    private fun bump(label: String) { errorCounts.merge(label, 1L, Long::plus) }

    override fun snapshot(): Snapshot = synchronized(lock) {
        fun pct(p: Double) = histogram.getValueAtPercentile(p).nanoseconds
        Snapshot(
            total = total,
            success = success,
            failed = failed,
            errorCounts = errorCounts.toMap(),
            statusCounts = statusCounts.toMap(),
            latencyMin = (if (total == 0L) 0L else histogram.minValue).nanoseconds,
            latencyMean = (if (total == 0L) 0.0 else histogram.mean).toLong().nanoseconds,
            latencyP50 = pct(50.0),
            latencyP90 = pct(90.0),
            latencyP95 = pct(95.0),
            latencyP99 = pct(99.0),
            latencyMax = (if (total == 0L) 0L else histogram.maxValue).nanoseconds,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.MetricsCollectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/MetricsCollector.kt src/test/kotlin/dev/stressline/MetricsCollectorTest.kt
git commit -m "feat: add channel-based metrics collector"
```

---

### Task 7: HTTP runner

**Files:**
- Create: `src/main/kotlin/dev/stressline/HttpRunner.kt`
- Test: `src/test/kotlin/dev/stressline/HttpRunnerTest.kt`

**Interfaces:**
- Consumes: `RunConfig`, `RequestResult`, `RequestError`, a Ktor `HttpClient`.
- Produces:
  - `interface HttpRunner { suspend fun execute(): RequestResult }`.
  - `class KtorHttpRunner(client: HttpClient, config: RunConfig) : HttpRunner`. Applies method/headers/body, drains the response body, times the call, classifies status via `RequestError.fromStatus`, maps `TimeoutCancellationException` → `Timeout`, rethrows other `CancellationException`, maps remaining throwables via `RequestError.fromException`.

- [ ] **Step 1: Write the failing test** — `HttpRunnerTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

private fun clientReturning(status: HttpStatusCode, capture: (HttpRequestData) -> Unit = {}): HttpClient =
    HttpClient(MockEngine { request ->
        capture(request)
        respond(content = "ok", status = status)
    }) { expectSuccess = false }

class HttpRunnerTest : ShouldSpec({
    context("a successful GET") {
        should("return a success result with the status") {
            runTest {
                val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), timeout = 5.seconds)
                val result = KtorHttpRunner(clientReturning(HttpStatusCode.OK), config).execute()
                result.isSuccess shouldBe true
                result.error shouldBe RequestError.None(200)
            }
        }
    }
    context("a POST with headers and body") {
        should("send the configured method, headers, and body") {
            runTest {
                var seen: HttpRequestData? = null
                val client = clientReturning(HttpStatusCode.Created) { seen = it }
                val config = RunConfig(
                    url = "http://x", mode = LoadMode.FixedConcurrency(1),
                    method = "POST", body = "payload", headers = listOf("X-A" to "1"),
                )
                KtorHttpRunner(client, config).execute()
                seen!!.method.value shouldBe "POST"
                seen!!.headers["X-A"] shouldBe "1"
                (seen!!.body as TextContent).text shouldBe "payload"
            }
        }
    }
    context("a 500 response") {
        should("be classified as an http error") {
            runTest {
                val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1))
                val result = KtorHttpRunner(clientReturning(HttpStatusCode.InternalServerError), config).execute()
                result.error shouldBe RequestError.HttpError(500)
            }
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.HttpRunnerTest"`
Expected: FAIL — `KtorHttpRunner` unresolved.

- [ ] **Step 3: Write the implementation** — `HttpRunner.kt`

```kotlin
package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.measureTimedValue

interface HttpRunner {
    suspend fun execute(): RequestResult
}

class KtorHttpRunner(
    private val client: HttpClient,
    private val config: RunConfig,
) : HttpRunner {
    override suspend fun execute(): RequestResult {
        return try {
            val timed = measureTimedValue {
                withTimeout(config.timeout) {
                    val response = client.request(config.url) {
                        method = HttpMethod.parse(config.method)
                        config.headers.forEach { (k, v) -> header(k, v) }
                        config.body?.let { setBody(it) }
                    }
                    response.bodyAsText() // drain so the connection is released
                    response.status.value
                }
            }
            RequestResult(timed.duration, RequestError.fromStatus(timed.value))
        } catch (e: TimeoutCancellationException) {
            RequestResult(config.timeout, RequestError.Timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            RequestResult(Duration.ZERO, RequestError.fromException(e))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.HttpRunnerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/HttpRunner.kt src/test/kotlin/dev/stressline/HttpRunnerTest.kt
git commit -m "feat: add Ktor HTTP runner"
```

---

### Task 8: Load engine

**Files:**
- Create: `src/main/kotlin/dev/stressline/LoadEngine.kt`
- Test: `src/test/kotlin/dev/stressline/LoadEngineTest.kt`

**Interfaces:**
- Consumes: `HttpRunner`, `RequestResult`, `LoadMode`, `StopCondition`, a `Channel<RequestResult>`.
- Produces:
  - `class LoadEngine(runner: HttpRunner, results: Channel<RequestResult>)` with `suspend fun run(mode: LoadMode, stop: StopCondition)`.
  - Fixed-concurrency + `Requests(N)`: N workers share an `AtomicInteger(N)`; each claims via `getAndDecrement()` before executing, so exactly N requests run.
  - Fixed-concurrency + `ForDuration(d)`: N workers loop until the duration elapses (`withTimeoutOrNull`).
  - Target-rate: a `delay`-paced loop launches jobs at `1s / rps`, bounded by a `Semaphore(maxInFlight = max(1, rps*2))`. `Requests(N)` launches exactly N; `ForDuration(d)` launches until elapsed.
  - The engine does not close `results` — the caller owns the channel lifecycle.

- [ ] **Step 1: Write the failing test** — `LoadEngineTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class CountingRunner(val delay: Duration = Duration.ZERO) : HttpRunner {
    val count = AtomicInteger(0)
    override suspend fun execute(): RequestResult {
        if (delay > Duration.ZERO) kotlinx.coroutines.delay(delay)
        count.incrementAndGet()
        return RequestResult(1.seconds, RequestError.None(200))
    }
}

class LoadEngineTest : ShouldSpec({
    context("fixed concurrency with a request count") {
        should("execute exactly N requests across workers") {
            runTest {
                val runner = CountingRunner()
                // UNLIMITED so the engine never blocks on send; we drain after it finishes.
                val channel = Channel<RequestResult>(Channel.UNLIMITED)
                LoadEngine(runner, channel).run(LoadMode.FixedConcurrency(10), StopCondition.Requests(100))
                channel.close()
                runner.count.get() shouldBe 100
                channel.toList().size shouldBe 100
            }
        }
    }
    context("fixed concurrency for a duration") {
        should("keep executing until the duration elapses then stop") {
            runTest {
                val runner = CountingRunner(delay = 10.milliseconds)
                val channel = Channel<RequestResult>(Channel.UNLIMITED)
                LoadEngine(runner, channel).run(LoadMode.FixedConcurrency(4), StopCondition.ForDuration(1.seconds))
                channel.close()
                // 4 workers * (1s / 10ms) = ~400 requests under virtual time.
                runner.count.get() shouldBeGreaterThan 0
                channel.toList().size shouldBe runner.count.get()
            }
        }
    }
    context("target rate with a request count") {
        should("execute exactly N requests") {
            runTest {
                val runner = CountingRunner()
                val channel = Channel<RequestResult>(Channel.UNLIMITED)
                LoadEngine(runner, channel).run(LoadMode.TargetRate(100), StopCondition.Requests(50))
                channel.close()
                runner.count.get() shouldBe 50
                channel.toList().size shouldBe 50
            }
        }
    }
})
```

Note: `channel.toList()` (from `kotlinx.coroutines.channels.toList`) drains all buffered items after the channel is closed, giving a deterministic count without a background collector coroutine.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.LoadEngineTest"`
Expected: FAIL — `LoadEngine` unresolved.

- [ ] **Step 3: Write the implementation** — `LoadEngine.kt`

```kotlin
package dev.stressline

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

class LoadEngine(
    private val runner: HttpRunner,
    private val results: Channel<RequestResult>,
) {
    suspend fun run(mode: LoadMode, stop: StopCondition) {
        when (mode) {
            is LoadMode.FixedConcurrency -> runFixed(mode.workers, stop)
            is LoadMode.TargetRate -> runRate(mode.rps, stop)
        }
    }

    private suspend fun runFixed(workers: Int, stop: StopCondition) = coroutineScope {
        when (stop) {
            is StopCondition.Requests -> {
                val remaining = AtomicInteger(stop.count)
                repeat(workers) {
                    launch {
                        while (remaining.getAndDecrement() > 0) {
                            results.send(runner.execute())
                        }
                    }
                }
            }
            is StopCondition.ForDuration -> {
                withTimeoutOrNull(stop.duration) {
                    coroutineScope {
                        repeat(workers) {
                            launch {
                                while (isActive) results.send(runner.execute())
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runRate(rps: Int, stop: StopCondition) = coroutineScope {
        val interval = (1.seconds) / rps
        val semaphore = Semaphore(maxOf(1, rps * 2))

        suspend fun launchOne() {
            semaphore.acquire()
            launch {
                try {
                    results.send(runner.execute())
                } finally {
                    semaphore.release()
                }
            }
        }

        when (stop) {
            is StopCondition.Requests -> {
                repeat(stop.count) {
                    launchOne()
                    delay(interval)
                }
            }
            is StopCondition.ForDuration -> {
                withTimeoutOrNull(stop.duration) {
                    while (coroutineContext.isActive) {
                        launchOne()
                        delay(interval)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.LoadEngineTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/LoadEngine.kt src/test/kotlin/dev/stressline/LoadEngineTest.kt
git commit -m "feat: add load engine with fixed-concurrency and target-rate modes"
```

---

### Task 9: Report formatting and progress reporter

**Files:**
- Create: `src/main/kotlin/dev/stressline/Report.kt`
- Create: `src/main/kotlin/dev/stressline/ProgressReporter.kt`
- Test: `src/test/kotlin/dev/stressline/ReportTest.kt`

**Interfaces:**
- Consumes: `Snapshot`, `MetricsCollector`.
- Produces:
  - `object Report { fun line(s: Snapshot, elapsed: Duration): String; fun summary(s: Snapshot, elapsed: Duration): String }` — pure string builders.
  - `class ProgressReporter(collector: MetricsCollector, enabled: Boolean, out: Appendable = System.out)` with `suspend fun runLive(intervalMs: Long = 200)` — loops while active, printing `Report.line` using a `TimeSource.Monotonic` start mark; no-ops when `enabled` is false. (Only `Report` is unit-tested; the live loop is exercised via the Task 11 manual smoke test.)

- [ ] **Step 1: Write the failing test** — `ReportTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun sampleSnapshot() = Snapshot(
    total = 100, success = 90, failed = 10,
    errorCounts = mapOf("http" to 6L, "timeout" to 4L),
    statusCounts = mapOf(200 to 90L, 500 to 6L),
    latencyMin = 1.milliseconds, latencyMean = 20.milliseconds,
    latencyP50 = 18.milliseconds, latencyP90 = 40.milliseconds,
    latencyP95 = 50.milliseconds, latencyP99 = 80.milliseconds,
    latencyMax = 120.milliseconds,
)

class ReportTest : ShouldSpec({
    context("the live progress line") {
        should("include sent, ok, and err counts and p95") {
            val line = Report.line(sampleSnapshot(), 2.seconds)
            line shouldContain "sent 100"
            line shouldContain "ok 90"
            line shouldContain "err 10"
            line shouldContain "p95 50ms"
        }
    }
    context("the final summary") {
        should("include latency percentiles and error breakdown") {
            val text = Report.summary(sampleSnapshot(), 2.seconds)
            text shouldContain "Total:       100"
            text shouldContain "Success:     90"
            text shouldContain "Failed:      10"
            text shouldContain "p95"
            text shouldContain "timeout"
            text shouldContain "500"
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.ReportTest"`
Expected: FAIL — `Report` unresolved.

- [ ] **Step 3: Write `Report.kt`**

```kotlin
package dev.stressline

import kotlin.time.Duration
import kotlin.time.DurationUnit

object Report {
    fun line(s: Snapshot, elapsed: Duration): String {
        val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
        val rps = s.total / secs
        return "%5.1fs | sent %d | ok %d | err %d | %.0f req/s | p95 %dms".format(
            secs, s.total, s.success, s.failed, rps, s.latencyP95.inWholeMilliseconds,
        )
    }

    fun summary(s: Snapshot, elapsed: Duration): String {
        val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
        val rps = s.total / secs
        val sb = StringBuilder()
        sb.appendLine("--- StressLine results ---")
        sb.appendLine("Total:       ${s.total}")
        sb.appendLine("Success:     ${s.success}")
        sb.appendLine("Failed:      ${s.failed}")
        sb.appendLine("Duration:    %.2fs".format(secs))
        sb.appendLine("Throughput:  %.0f req/s".format(rps))
        sb.appendLine("Latency (ms):")
        sb.appendLine("  min %d | mean %d | p50 %d | p90 %d | p95 %d | p99 %d | max %d".format(
            s.latencyMin.inWholeMilliseconds, s.latencyMean.inWholeMilliseconds,
            s.latencyP50.inWholeMilliseconds, s.latencyP90.inWholeMilliseconds,
            s.latencyP95.inWholeMilliseconds, s.latencyP99.inWholeMilliseconds,
            s.latencyMax.inWholeMilliseconds,
        ))
        if (s.errorCounts.isNotEmpty()) {
            sb.appendLine("Errors:")
            s.errorCounts.toSortedMap().forEach { (k, v) -> sb.appendLine("  $k: $v") }
        }
        if (s.statusCounts.isNotEmpty()) {
            sb.appendLine("Status codes:")
            s.statusCounts.toSortedMap().forEach { (k, v) -> sb.appendLine("  $k: $v") }
        }
        return sb.toString().trimEnd()
    }
}
```

- [ ] **Step 4: Write `ProgressReporter.kt`**

```kotlin
package dev.stressline

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

class ProgressReporter(
    private val collector: MetricsCollector,
    private val enabled: Boolean,
    private val out: Appendable = System.out,
) {
    suspend fun runLive(intervalMs: Long = 200) {
        if (!enabled) return
        val start = TimeSource.Monotonic.markNow()
        while (coroutineContext.isActive) {
            out.append('\r').append(Report.line(collector.snapshot(), start.elapsedNow()))
            delay(intervalMs)
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.ReportTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/stressline/Report.kt src/main/kotlin/dev/stressline/ProgressReporter.kt src/test/kotlin/dev/stressline/ReportTest.kt
git commit -m "feat: add report formatting and live progress reporter"
```

---

### Task 10: File-descriptor limit

**Files:**
- Create: `src/main/kotlin/dev/stressline/FdLimit.kt`
- Test: `src/test/kotlin/dev/stressline/FdLimitTest.kt`

**Interfaces:**
- Consumes: nothing (the JNA impl is platform-specific).
- Produces:
  - `interface FdLimit { fun current(): Pair<Long, Long>?; fun raiseTo(needed: Long): Long? }` — `null` means the platform is unsupported.
  - `object FdLimitPlanner { data class Plan(val shouldRaise: Boolean, val target: Long, val sufficient: Boolean); fun plan(needed: Long, soft: Long, hard: Long): Plan }` — adds 64 fd of headroom; never targets above `hard`.
  - `fun ensureFdLimit(neededConcurrency: Int, fd: FdLimit, log: (String) -> Unit)` — wires planner + `FdLimit` + logging.
  - `class JnaFdLimit : FdLimit` — calls libc `getrlimit`/`setrlimit` for `RLIMIT_NOFILE` (7 on Linux, 8 on macOS); returns `null` on Windows.

- [ ] **Step 1: Write the failing test** — `FdLimitTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

private class FakeFdLimit(var soft: Long, val hard: Long) : FdLimit {
    var raisedTo: Long? = null
    override fun current() = soft to hard
    override fun raiseTo(needed: Long): Long {
        raisedTo = needed
        soft = minOf(needed, hard)
        return soft
    }
}

class FdLimitTest : ShouldSpec({
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.FdLimitTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation** — `FdLimit.kt`

```kotlin
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

    data class Plan(val shouldRaise: Boolean, val target: Long, val sufficient: Boolean)

    fun plan(needed: Long, soft: Long, hard: Long): Plan {
        val want = needed + HEADROOM
        if (want <= soft) return Plan(shouldRaise = false, target = soft, sufficient = true)
        val target = minOf(want, hard)
        return Plan(shouldRaise = true, target = target, sufficient = target >= want)
    }
}

fun ensureFdLimit(neededConcurrency: Int, fd: FdLimit, log: (String) -> Unit) {
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
        log("Warning: raised FD soft limit to $result, but $neededConcurrency connections may need more. Raise the hard limit (ulimit -Hn) or run with sudo.")
    }
}

class JnaFdLimit : FdLimit {
    @Structure.FieldOrder("cur", "max")
    class RLimit : Structure() {
        @JvmField var cur: Long = 0
        @JvmField var max: Long = 0
    }

    private interface CLib : Library {
        fun getrlimit(resource: Int, rlim: RLimit): Int
        fun setrlimit(resource: Int, rlim: RLimit): Int
    }

    // RLIMIT_NOFILE: 7 on Linux, 8 on macOS.
    private val resource = if (Platform.isMac()) 8 else 7

    private val libc: CLib? =
        if (Platform.isWindows()) null
        else runCatching { Native.load("c", CLib::class.java) }.getOrNull()

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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "dev.stressline.FdLimitTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/FdLimit.kt src/test/kotlin/dev/stressline/FdLimitTest.kt
git commit -m "feat: add file-descriptor limit planner and JNA raiser"
```

---

### Task 11: Main wiring, TLS, and end-to-end smoke test

**Files:**
- Create: `src/main/kotlin/dev/stressline/Main.kt`
- Create: `README.md`

**Interfaces:**
- Consumes: `parseArgs`, `CliValidationException`, `ensureFdLimit`, `JnaFdLimit`, `ChannelMetricsCollector`, `KtorHttpRunner`, `LoadEngine`, `ProgressReporter`, `Report`, `RunConfig`, `LoadMode`.
- Produces: `fun main(args: Array<String>)` — the entry point declared as `dev.stressline.MainKt` in `build.gradle.kts`.

- [ ] **Step 1: Write `Main.kt`**

```kotlin
package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private val trustAll = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun main(args: Array<String>) {
    val config = try {
        parseArgs(args)
    } catch (e: CliValidationException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    }

    if (config.mode is LoadMode.FixedConcurrency) {
        ensureFdLimit((config.mode as LoadMode.FixedConcurrency).workers, JnaFdLimit()) { println(it) }
    }

    val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            if (config.insecure) {
                https { trustManager = trustAll }
            }
        }
    }

    val collector = ChannelMetricsCollector()
    val results = Channel<RequestResult>(capacity = 1024)
    val runner = KtorHttpRunner(client, config)
    val engine = LoadEngine(runner, results)
    val reporter = ProgressReporter(collector, enabled = config.showProgress)

    runBlocking {
        val collectorJob = launch { collector.consume(results) }
        val progressJob = launch { reporter.runLive() }
        val start = TimeSource.Monotonic.markNow()
        try {
            engine.run(config.mode, config.stop)
        } finally {
            results.close()
            collectorJob.join()
            progressJob.cancelAndJoin()
            client.close()
        }
        println()
        println(Report.summary(collector.snapshot(), start.elapsedNow()))
    }
}
```

- [ ] **Step 2: Build the runnable jar**

Run: `./gradlew shadowJar`
Expected: BUILD SUCCESSFUL; a jar appears under `build/libs/` (e.g. `StressLine-0.1.0-all.jar`).

- [ ] **Step 3: Manual smoke test against a local server**

Start a throwaway server in one terminal:

```bash
python3 -m http.server 8099
```

In another terminal, run fixed-concurrency mode:

```bash
./gradlew run --args="--url http://localhost:8099 --concurrency 20 --duration 3s"
```

Expected: a live progress line updates, then a summary prints with `Total:` > 0, a non-zero throughput, latency percentiles, and a `200` status count. Then run target-rate mode:

```bash
./gradlew run --args="--url http://localhost:8099 --rate 50 --requests 200"
```

Expected: summary shows `Total: 200` and throughput near 50 req/s. Stop the Python server afterward.

- [ ] **Step 4: Verify CLI validation errors**

Run: `./gradlew run --args="--url http://localhost:8099"`
Expected: prints `error: Exactly one of --concurrency or --rate is required` and a non-zero exit. (Gradle may wrap the exit code; the error line is what matters.)

- [ ] **Step 5: Write `README.md`**

````markdown
# StressLine

A local HTTP/HTTPS stress-testing CLI built with Kotlin coroutines.

## Build

```bash
./gradlew shadowJar
java -jar build/libs/StressLine-0.1.0-all.jar --url https://example.com --concurrency 50 --duration 30s
```

## Usage

```
stressline --url <URL> [options]

Load mode (exactly one):
  -c, --concurrency <N>   N concurrent virtual users
  -r, --rate <RPS>        Target requests per second

Stop condition (default --duration 10s):
  -n, --requests <N>      Stop after N requests
  -t, --duration <DUR>    Stop after a duration (e.g. 30s, 2m)

Request:
  -X, --method <M>        HTTP method (default GET)
  -H, --header "K: V"     Add a header (repeatable)
  -d, --body <STR>        Request body

Tuning:
  --timeout <DUR>         Per-request timeout (default 5s)
  --insecure              Skip TLS verification
  --no-progress           Disable the live progress line
```

## Notes & limits

- Single-JVM, single-machine load generator. Comfortable up to tens of thousands
  of RPS depending on TLS and the target; beyond that use a distributed tool.
- Connection keep-alive is on (shared client) — essential for high RPS.
- On Linux/macOS the tool raises its own file-descriptor soft limit when
  `--concurrency` needs it. If it can't reach the needed value, raise the hard
  limit first: `ulimit -n 65535`.
````

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: PASS — every spec from Tasks 1–10 green.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/stressline/Main.kt README.md
git commit -m "feat: wire up CLI entry point and document usage"
```

---

## Self-Review Notes

- **Spec coverage:** HTTP/HTTPS load (Tasks 7, 11) ✓; both load modes (Task 8) ✓; Ktor CIO (Tasks 7, 11) ✓; method/headers/body flags (Tasks 4, 5, 7) ✓; full latency stats + live progress (Tasks 6, 9) ✓; categorized errors + per-request timeout (Tasks 3, 7) ✓; FD auto-raise (Task 10) ✓; channel-based metrics pipeline (Tasks 6, 8, 11) ✓; Kotest ShouldSpec everywhere ✓; shadow fat jar + application plugin (Tasks 1, 11) ✓; throughput limits documented in README (Task 11) ✓.
- **Cancellation safety:** `KtorHttpRunner` rethrows non-timeout `CancellationException` (Task 7); engine uses structured `coroutineScope`/`withTimeoutOrNull` (Task 8).
- **Type consistency:** `RequestError.fromStatus`/`fromException`, `ChannelMetricsCollector`, `Snapshot` fields, `LoadEngine.run(mode, stop)`, and `Report.line`/`summary` signatures are used identically across producing and consuming tasks.
