# JSON Output, CI Gate, Body-from-File & Custom Help — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JSON summary output (stdout and/or file), pass/fail thresholds with exit codes, `-d @file` body loading, and a curated `--help` to the StressLine CLI.

**Architecture:** Pure helpers do the work — `Cli.parseArgs` grows new flags and a pre-scan for `--json-out`; `Report.json` hand-rolls a JSON string; `Thresholds.evaluate` returns breach messages; `Help.text` is a static string. `Main` wires routing (progress→stderr under `--json`), the JSON file write (with auto-timestamp filename), and threshold-based exit codes.

**Tech Stack:** Kotlin 2.2.0, kotlinx-cli, kotlinx.coroutines, Ktor client, Kotest. No new dependencies.

## Global Constraints

- Kotlin `2.1`-compatible source built on JVM toolchain `17`; build with `./gradlew` (never assume a system gradle).
- Package root `dev.stressline`; sources in `src/main/kotlin/dev/stressline/`, tests in `src/test/kotlin/dev/stressline/`.
- **2-space indentation, enforced by kotlinter.** Before every commit run `./gradlew formatKotlin` then confirm `./gradlew lintKotlin test` is green. Unformatted code fails the build.
- Tests use Kotest `ShouldSpec` with `context(...) { should(...) { ... } }` and `kotest-assertions-core`. Run one class with `./gradlew test --tests "dev.stressline.<ClassName>"`; full suite `./gradlew test`.
- **No new dependencies.** JSON is hand-rolled. All floating-point formatting in JSON/threshold output MUST use `java.util.Locale.US` (e.g. `String.format(java.util.Locale.US, "%.2f", d)`) so decimals never become locale commas.
- Exit codes: `0` success, `1` threshold breach (only after a completed run), `2` usage/validation error or JSON file-write failure.
- TDD: failing test first, see it fail, minimal implementation, see it pass, commit.
- Existing behavior must not change when none of the new flags are used.

---

### Task 1: RunConfig — JsonOutTarget and new fields

**Files:**
- Modify: `src/main/kotlin/dev/stressline/RunConfig.kt`
- Test: `src/test/kotlin/dev/stressline/RunConfigTest.kt`

**Interfaces:**
- Consumes: existing `LoadMode`, `RunConfig`.
- Produces:
  - `sealed interface JsonOutTarget { data object Auto : JsonOutTarget; data class File(val path: String) : JsonOutTarget }`
  - `RunConfig` gains (appended, all defaulted): `jsonToStdout: Boolean = false`, `jsonOut: JsonOutTarget? = null`, `failIfErrorRate: Double? = null`, `failIfP95: Duration? = null`.

- [ ] **Step 1: Write the failing test** — append inside the `RunConfigTest` spec body (after the existing `context` blocks)

```kotlin
    context("new output and threshold fields") {
      should("default to off/null") {
        val c = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1))
        c.jsonToStdout shouldBe false
        c.jsonOut shouldBe null
        c.failIfErrorRate shouldBe null
        c.failIfP95 shouldBe null
      }
      should("carry a JsonOutTarget.File path") {
        val c =
          RunConfig(
            url = "http://x",
            mode = LoadMode.FixedConcurrency(1),
            jsonOut = JsonOutTarget.File("reports/run.json"),
          )
        c.jsonOut shouldBe JsonOutTarget.File("reports/run.json")
      }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.RunConfigTest"`
Expected: FAIL — `JsonOutTarget` and the new fields are unresolved.

- [ ] **Step 3: Implement** — add the sealed interface and fields in `RunConfig.kt`

Add after the `StopCondition` interface (before `data class RunConfig`):

```kotlin
sealed interface JsonOutTarget {
  data object Auto : JsonOutTarget

  data class File(
    val path: String,
  ) : JsonOutTarget
}
```

Change the `RunConfig` data class to append the new fields:

```kotlin
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
  val jsonToStdout: Boolean = false,
  val jsonOut: JsonOutTarget? = null,
  val failIfErrorRate: Double? = null,
  val failIfP95: Duration? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew formatKotlin && ./gradlew test --tests "dev.stressline.RunConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/RunConfig.kt src/test/kotlin/dev/stressline/RunConfigTest.kt
git commit -m "feat: add JsonOutTarget and JSON/threshold fields to RunConfig"
```

---

### Task 2: `-d @file` body loading

**Files:**
- Modify: `src/main/kotlin/dev/stressline/Cli.kt`
- Test: `src/test/kotlin/dev/stressline/CliTest.kt`

**Interfaces:**
- Consumes: existing `parseArgs`, `CliValidationException`.
- Produces: `parseArgs` resolves a `-d`/`--body` value starting with `@` by reading the file at the remaining path as UTF-8; a missing/unreadable file throws `CliValidationException("body file not found: <path>")`. A plain body is unchanged.

- [ ] **Step 1: Write the failing test** — add a new `context` inside `CliTest`

```kotlin
    context("body from a file") {
      should("read the body from @path") {
        val f = java.io.File.createTempFile("stressline-body", ".txt")
        f.writeText("payload-from-file")
        f.deleteOnExit()
        val c = parseArgs(arrayOf("http://x", "-c", "1", "-d", "@${f.path}"))
        c.body shouldBe "payload-from-file"
      }
      should("keep a plain body unchanged") {
        val c = parseArgs(arrayOf("http://x", "-c", "1", "-d", "literal"))
        c.body shouldBe "literal"
      }
      should("reject a missing body file") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("http://x", "-c", "1", "-d", "@/no/such/file.json"))
        }
      }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.CliTest"`
Expected: FAIL — `@/no/such/file.json` is currently passed through as a literal body, so the rejection test fails (and the read test returns `@...`).

- [ ] **Step 3: Implement** — in `Cli.kt`, resolve the body before building `RunConfig`

Add this just before the `return RunConfig(` statement in `parseArgs`:

```kotlin
  val resolvedBody =
    body?.let { raw ->
      if (raw.startsWith("@")) {
        val path = raw.substring(1)
        val file = java.io.File(path)
        if (!file.isFile) throw CliValidationException("body file not found: $path")
        file.readText()
      } else {
        raw
      }
    }
```

Change the `RunConfig(...)` call to use it: `body = resolvedBody,` (replacing `body = body,`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew formatKotlin && ./gradlew test --tests "dev.stressline.CliTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/Cli.kt src/test/kotlin/dev/stressline/CliTest.kt
git commit -m "feat: support -d @file to read request body from a file"
```

---

### Task 3: CLI flags — `--json`, `--json-out`, thresholds

**Files:**
- Modify: `src/main/kotlin/dev/stressline/Cli.kt`
- Test: `src/test/kotlin/dev/stressline/CliTest.kt`

**Interfaces:**
- Consumes: `RunConfig`, `JsonOutTarget`, `DurationParser`, `CliValidationException`.
- Produces: `parseArgs` now sets `jsonToStdout` (from `--json`), `jsonOut` (from a pre-scan of `--json-out`), `failIfErrorRate` (from `--fail-if-error-rate`, validated ≥ 0), and `failIfP95` (from `--fail-if-p95`, parsed via `DurationParser`, bad value → `CliValidationException`). A new private top-level `extractJsonOut(args): Pair<JsonOutTarget?, Array<String>>` removes `--json-out` (+ its path token) from the args before kotlinx-cli parses the rest.

- [ ] **Step 1: Write the failing test** — add a new `context` inside `CliTest`

```kotlin
    context("JSON and threshold flags") {
      should("set jsonToStdout from --json") {
        parseArgs(arrayOf("http://x", "-c", "1", "--json")).jsonToStdout shouldBe true
      }
      should("treat bare --json-out as Auto") {
        parseArgs(arrayOf("http://x", "-c", "1", "--json-out")).jsonOut shouldBe JsonOutTarget.Auto
      }
      should("treat --json-out before another flag as Auto") {
        parseArgs(arrayOf("http://x", "--json-out", "-c", "1")).jsonOut shouldBe JsonOutTarget.Auto
      }
      should("take a .json path after --json-out") {
        parseArgs(arrayOf("http://x", "-c", "1", "--json-out", "reports/run.json")).jsonOut shouldBe
          JsonOutTarget.File("reports/run.json")
      }
      should("not treat a non-.json token (a URL) as the path") {
        val c = parseArgs(arrayOf("--json-out", "https://example.com", "-c", "1"))
        c.jsonOut shouldBe JsonOutTarget.Auto
        c.url shouldBe "https://example.com"
      }
      should("parse --fail-if-error-rate and --fail-if-p95") {
        val c = parseArgs(arrayOf("http://x", "-c", "1", "--fail-if-error-rate", "1.5", "--fail-if-p95", "200ms"))
        c.failIfErrorRate shouldBe 1.5
        c.failIfP95 shouldBe 200.milliseconds
      }
      should("reject a negative error-rate threshold") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("http://x", "-c", "1", "--fail-if-error-rate", "-1"))
        }
      }
      should("reject a malformed p95 threshold") {
        shouldThrow<CliValidationException> {
          parseArgs(arrayOf("http://x", "-c", "1", "--fail-if-p95", "bogus"))
        }
      }
    }
```

Add this import to the top of `CliTest.kt` (next to the existing imports):

```kotlin
import kotlin.time.Duration.Companion.milliseconds
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.CliTest"`
Expected: FAIL — the new options/fields are unresolved.

- [ ] **Step 3: Implement** — in `Cli.kt`

No new imports are required: `ArgType` and `default` are already imported, and `ArgType.Double`/`ArgType.Boolean` are members of `ArgType`. Do not re-add them.

Add this private top-level function at the end of the file (after `parseArgs`):

```kotlin
// kotlinx-cli cannot express an option with an optional value, so pull
// --json-out out of the argument list before it parses the rest.
private fun extractJsonOut(raw: Array<String>): Pair<JsonOutTarget?, Array<String>> {
  val remaining = mutableListOf<String>()
  var target: JsonOutTarget? = null
  var i = 0
  while (i < raw.size) {
    if (raw[i] == "--json-out") {
      val next = raw.getOrNull(i + 1)
      if (next != null && !next.startsWith("-") && next.lowercase().endsWith(".json")) {
        target = JsonOutTarget.File(next)
        i += 2
      } else {
        target = JsonOutTarget.Auto
        i += 1
      }
    } else {
      remaining.add(raw[i])
      i += 1
    }
  }
  return target to remaining.toTypedArray()
}
```

At the very start of `parseArgs`, before `val parser = ArgParser(...)`, extract json-out and parse the remainder:

```kotlin
  val (jsonOut, rest) = extractJsonOut(args)
```

Add the three new options alongside the existing ones (e.g. after the `noProgress` option):

```kotlin
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit summary as JSON on stdout").default(false)
  val failIfErrorRate by parser.option(ArgType.Double, fullName = "fail-if-error-rate", description = "Exit 1 if error rate % exceeds this")
  val failIfP95 by parser.option(ArgType.String, fullName = "fail-if-p95", description = "Exit 1 if p95 latency exceeds this duration")
```

Change `parser.parse(args)` to `parser.parse(rest)`.

Add validation after the existing non-positive-integer checks:

```kotlin
  if (failIfErrorRate != null && failIfErrorRate!! < 0) {
    throw CliValidationException("--fail-if-error-rate must be >= 0")
  }
  val failP95 =
    failIfP95?.let {
      try {
        DurationParser.parse(it)
      } catch (e: IllegalArgumentException) {
        throw CliValidationException("--fail-if-p95: ${e.message}")
      }
    }
```

Add the four fields to the `RunConfig(...)` call:

```kotlin
    jsonToStdout = json,
    jsonOut = jsonOut,
    failIfErrorRate = failIfErrorRate,
    failIfP95 = failP95,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew formatKotlin && ./gradlew test --tests "dev.stressline.CliTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/Cli.kt src/test/kotlin/dev/stressline/CliTest.kt
git commit -m "feat: add --json, --json-out, and threshold flags to the CLI"
```

---

### Task 4: `Report.json` serializer

**Files:**
- Modify: `src/main/kotlin/dev/stressline/Report.kt`
- Test: `src/test/kotlin/dev/stressline/ReportTest.kt`

**Interfaces:**
- Consumes: `Snapshot`, `RunConfig`, `LoadMode`.
- Produces: `Report.json(s: Snapshot, elapsed: Duration, config: RunConfig): String` — a single-line JSON object as specified. `errorRatePct = 0.0` when `total == 0`. `errors` and `statusCodes` are sorted by key for deterministic output. All doubles formatted with `Locale.US` to two decimals.

- [ ] **Step 1: Write the failing test** — add a new `context` inside `ReportTest`

```kotlin
    context("json output") {
      should("serialize the snapshot and config fields") {
        val config = RunConfig(url = "https://example.com", mode = LoadMode.FixedConcurrency(50))
        val json = Report.json(sampleSnapshot(), 2.seconds, config)
        json shouldContain "\"url\":\"https://example.com\""
        json shouldContain "\"mode\":{\"type\":\"concurrency\",\"value\":50}"
        json shouldContain "\"total\":100"
        json shouldContain "\"failed\":10"
        json shouldContain "\"errorRatePct\":10.00"
        json shouldContain "\"p95\":50"
        json shouldContain "\"errors\":{\"http\":6,\"timeout\":4}"
        json shouldContain "\"statusCodes\":{\"200\":90,\"500\":6}"
      }
      should("produce balanced braces") {
        val config = RunConfig(url = "http://x", mode = LoadMode.TargetRate(10))
        val json = Report.json(sampleSnapshot(), 1.seconds, config)
        json.count { it == '{' } shouldBe json.count { it == '}' }
        json shouldContain "\"type\":\"rate\""
      }
    }
```

(The existing `sampleSnapshot()` helper in `ReportTest.kt` has `total=100, failed=10, errorCounts={"http":6,"timeout":4}, statusCounts={200:90, 500:6}, latencyP95=50ms`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.ReportTest"`
Expected: FAIL — `Report.json` is unresolved.

- [ ] **Step 3: Implement** — add to `Report.kt`

Add `import` is not needed (use fully-qualified `java.util.Locale`). Add these members inside `object Report` (after `summary`):

```kotlin
  fun json(
    s: Snapshot,
    elapsed: Duration,
    config: RunConfig,
  ): String {
    val secs = elapsed.toDouble(DurationUnit.SECONDS).coerceAtLeast(0.001)
    val rps = s.total / secs
    val errorRate = if (s.total == 0L) 0.0 else s.failed.toDouble() / s.total * 100.0
    val (modeType, modeValue) =
      when (val m = config.mode) {
        is LoadMode.FixedConcurrency -> "concurrency" to m.workers
        is LoadMode.TargetRate -> "rate" to m.rps
      }
    val errors =
      s.errorCounts.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") {
        "\"${escapeJson(it.key)}\":${it.value}"
      }
    val statusCodes =
      s.statusCounts.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") {
        "\"${it.key}\":${it.value}"
      }
    return buildString {
      append("{")
      append("\"url\":\"").append(escapeJson(config.url)).append("\",")
      append("\"mode\":{\"type\":\"").append(modeType).append("\",\"value\":").append(modeValue).append("},")
      append("\"total\":").append(s.total).append(",")
      append("\"success\":").append(s.success).append(",")
      append("\"failed\":").append(s.failed).append(",")
      append("\"errorRatePct\":").append(num2(errorRate)).append(",")
      append("\"durationSeconds\":").append(num2(secs)).append(",")
      append("\"throughputPerSec\":").append(num2(rps)).append(",")
      append("\"latencyMs\":{")
      append("\"min\":").append(s.latencyMin.inWholeMilliseconds).append(",")
      append("\"mean\":").append(s.latencyMean.inWholeMilliseconds).append(",")
      append("\"p50\":").append(s.latencyP50.inWholeMilliseconds).append(",")
      append("\"p90\":").append(s.latencyP90.inWholeMilliseconds).append(",")
      append("\"p95\":").append(s.latencyP95.inWholeMilliseconds).append(",")
      append("\"p99\":").append(s.latencyP99.inWholeMilliseconds).append(",")
      append("\"max\":").append(s.latencyMax.inWholeMilliseconds)
      append("},")
      append("\"errors\":").append(errors).append(",")
      append("\"statusCodes\":").append(statusCodes)
      append("}")
    }
  }

  private fun num2(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)

  private fun escapeJson(text: String): String =
    buildString {
      for (c in text) {
        when (c) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(c)
        }
      }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew formatKotlin && ./gradlew test --tests "dev.stressline.ReportTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/Report.kt src/test/kotlin/dev/stressline/ReportTest.kt
git commit -m "feat: add hand-rolled JSON summary serializer"
```

---

### Task 5: `Thresholds.evaluate`

**Files:**
- Create: `src/main/kotlin/dev/stressline/Thresholds.kt`
- Test: `src/test/kotlin/dev/stressline/ThresholdsTest.kt`

**Interfaces:**
- Consumes: `Snapshot`, `RunConfig`.
- Produces: `object Thresholds { fun evaluate(s: Snapshot, config: RunConfig): List<String> }` — returns one message per breached threshold, empty if all pass or none configured. Error rate is `0` when `total == 0`. Compares `s.latencyP95 > config.failIfP95` and `errorRate > config.failIfErrorRate`.

- [ ] **Step 1: Write the failing test** — `ThresholdsTest.kt`

```kotlin
package dev.stressline

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private fun snap(
  total: Long,
  failed: Long,
  p95: Duration,
): Snapshot =
  Snapshot(
    total = total,
    success = total - failed,
    failed = failed,
    errorCounts = emptyMap(),
    statusCounts = emptyMap(),
    latencyMin = Duration.ZERO,
    latencyMean = Duration.ZERO,
    latencyP50 = Duration.ZERO,
    latencyP90 = Duration.ZERO,
    latencyP95 = p95,
    latencyP99 = Duration.ZERO,
    latencyMax = Duration.ZERO,
  )

class ThresholdsTest :
  ShouldSpec({
    context("no thresholds configured") {
      should("return no breaches") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1))
        Thresholds.evaluate(snap(100, 50, 100.milliseconds), config).shouldBeEmpty()
      }
    }
    context("error-rate threshold") {
      should("breach when the rate exceeds the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfErrorRate = 10.0)
        Thresholds.evaluate(snap(100, 20, 1.milliseconds), config).shouldHaveSize(1)
      }
      should("pass when the rate is within the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfErrorRate = 10.0)
        Thresholds.evaluate(snap(100, 5, 1.milliseconds), config).shouldBeEmpty()
      }
      should("treat an empty run as passing") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfErrorRate = 0.0)
        Thresholds.evaluate(snap(0, 0, Duration.ZERO), config).shouldBeEmpty()
      }
    }
    context("p95 threshold") {
      should("breach when p95 exceeds the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfP95 = 200.milliseconds)
        Thresholds.evaluate(snap(100, 0, 300.milliseconds), config).shouldHaveSize(1)
      }
      should("pass when p95 is within the limit") {
        val config = RunConfig(url = "http://x", mode = LoadMode.FixedConcurrency(1), failIfP95 = 200.milliseconds)
        Thresholds.evaluate(snap(100, 0, 100.milliseconds), config).shouldBeEmpty()
      }
    }
    context("both thresholds breached") {
      should("return two messages") {
        val config =
          RunConfig(
            url = "http://x",
            mode = LoadMode.FixedConcurrency(1),
            failIfErrorRate = 1.0,
            failIfP95 = 50.milliseconds,
          )
        Thresholds.evaluate(snap(100, 50, 300.milliseconds), config).shouldHaveSize(2)
      }
    }
  })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.ThresholdsTest"`
Expected: FAIL — `Thresholds` is unresolved.

- [ ] **Step 3: Implement** — `Thresholds.kt`

```kotlin
package dev.stressline

object Thresholds {
  fun evaluate(
    s: Snapshot,
    config: RunConfig,
  ): List<String> {
    val breaches = mutableListOf<String>()
    config.failIfErrorRate?.let { limit ->
      val rate = if (s.total == 0L) 0.0 else s.failed.toDouble() / s.total * 100.0
      if (rate > limit) {
        val rateStr = String.format(java.util.Locale.US, "%.2f", rate)
        breaches.add("error rate $rateStr% exceeds threshold $limit%")
      }
    }
    config.failIfP95?.let { limit ->
      if (s.latencyP95 > limit) {
        breaches.add("p95 ${s.latencyP95.inWholeMilliseconds}ms exceeds threshold ${limit.inWholeMilliseconds}ms")
      }
    }
    return breaches
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew formatKotlin && ./gradlew test --tests "dev.stressline.ThresholdsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/Thresholds.kt src/test/kotlin/dev/stressline/ThresholdsTest.kt
git commit -m "feat: add threshold evaluation for CI gating"
```

---

### Task 6: `Help.text`

**Files:**
- Create: `src/main/kotlin/dev/stressline/Help.kt`
- Test: `src/test/kotlin/dev/stressline/HelpTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object Help { val text: String }` — the curated help string covering synopsis, all flag groups, examples, and the mode/duration rules.

- [ ] **Step 1: Write the failing test** — `HelpTest.kt`

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "dev.stressline.HelpTest"`
Expected: FAIL — `Help` is unresolved.

- [ ] **Step 3: Implement** — `Help.kt`

```kotlin
package dev.stressline

object Help {
  val text: String =
    """
    stressline — local HTTP/HTTPS stress tester

    Usage:
      stressline <URL> [options]
      stressline -u <URL> [options]

    Load mode (exactly one required):
      -c, --concurrency <N>        N concurrent clients, each looping as fast as it can
      -r, --rate <RPS>             Hold a target of N requests per second

    Stop condition (default: --duration 10s):
      -n, --requests <N>           Stop after N total requests
      -t, --duration <DUR>         Stop after a duration: 500ms, 30s, 2m

    Request:
      -X, --method <M>             HTTP method (default GET)
      -H, --header "K: V"          Add a header (repeatable)
      -d, --body <STR|@FILE>       Body string, or @path to read it from a file

    Output:
      --json                       Emit the summary as JSON on stdout (progress -> stderr)
      --json-out [PATH]            Also write JSON to a file; bare = stressline-run-<ts>.json,
                                   or a PATH ending in .json (parent dirs created)
      --no-progress                Disable the live progress line

    CI gate (exit 1 if breached on a completed run):
      --fail-if-error-rate <PCT>   e.g. 1, 0.5
      --fail-if-p95 <DUR>          e.g. 200ms

    Tuning:
      --timeout <DUR>              Per-request timeout (default 5s)
      --insecure                   Skip TLS certificate verification

      -h, --help                   Show this help

    Examples:
      stressline https://example.com -c 50 -t 10s
      stressline https://api.example.com/health -r 200 -t 1m
      stressline https://example.com -c 20 -n 1000 --json | jq .latencyMs.p95
      stressline https://example.com -c 20 -t 30s --json-out reports/run.json
      stressline https://api/users -X POST -H "Content-Type: application/json" -d @body.json -c 10 -t 30s
      stressline https://example.com -c 50 -t 30s --fail-if-p95 200ms --fail-if-error-rate 1
    """.trimIndent()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew formatKotlin && ./gradlew test --tests "dev.stressline.HelpTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/stressline/Help.kt src/test/kotlin/dev/stressline/HelpTest.kt
git commit -m "feat: add curated --help text"
```

---

### Task 7: Main wiring — help, JSON routing, file write, exit codes

**Files:**
- Modify: `src/main/kotlin/dev/stressline/Main.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: `parseArgs`, `CliValidationException`, `Help`, `Report.json`/`Report.summary`, `Thresholds.evaluate`, `JsonOutTarget`, `ProgressReporter(collector, enabled, out)`, `RunConfig` (new fields).
- Produces: `main` that prints curated help on `-h`/`--help` (exit 0); routes progress to stderr and the summary to JSON when `--json`; writes the JSON file when `jsonOut` is set (auto-timestamp filename or explicit path, parent dirs created; write failure → exit 2); and exits 1 when `Thresholds.evaluate` returns breaches after a completed run.

- [ ] **Step 1: Implement** — rewrite `Main.kt`

```kotlin
package dev.stressline

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private val trustAll =
  object : X509TrustManager {
    override fun checkClientTrusted(
      chain: Array<out X509Certificate>?,
      authType: String?,
    ) {}

    override fun checkServerTrusted(
      chain: Array<out X509Certificate>?,
      authType: String?,
    ) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
  }

private fun writeJsonFile(
  target: JsonOutTarget,
  json: String,
) {
  val path =
    when (target) {
      is JsonOutTarget.Auto -> {
        val ts =
          java.time.LocalDateTime
            .now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        "stressline-run-$ts.json"
      }
      is JsonOutTarget.File -> target.path
    }
  try {
    val file = java.io.File(path)
    file.parentFile?.mkdirs()
    file.writeText(json)
    System.err.println("Wrote JSON to $path")
  } catch (e: Exception) {
    System.err.println("error: could not write JSON to $path: ${e.message}")
    exitProcess(2)
  }
}

fun main(args: Array<String>) {
  if (args.any { it == "-h" || it == "--help" }) {
    println(Help.text)
    exitProcess(0)
  }

  val config =
    try {
      parseArgs(args)
    } catch (e: CliValidationException) {
      System.err.println("error: ${e.message}")
      exitProcess(2)
    }

  when (val m = config.mode) {
    is LoadMode.FixedConcurrency -> ensureFdLimit(m.workers, JnaFdLimit()) { println(it) }
    is LoadMode.TargetRate -> ensureFdLimit(m.rps * 2, JnaFdLimit()) { println(it) }
  }

  val client =
    HttpClient(CIO) {
      expectSuccess = false
      engine {
        if (config.insecure) {
          https { trustManager = trustAll }
        }
      }
    }

  val collector = ChannelMetricsCollector()
  val start = TimeSource.Monotonic.markNow()
  val summaryPrinted = AtomicBoolean(false)

  fun printSummaryOnce() {
    if (summaryPrinted.compareAndSet(false, true)) {
      if (config.jsonToStdout) {
        println(Report.json(collector.snapshot(), start.elapsedNow(), config))
      } else {
        println()
        println(Report.summary(collector.snapshot(), start.elapsedNow()))
      }
    }
  }

  Runtime.getRuntime().addShutdownHook(Thread { printSummaryOnce() })

  val results = Channel<RequestResult>(capacity = 1024)
  val runner = KtorHttpRunner(client, config)
  val engine = LoadEngine(runner, results)
  val progressOut: Appendable = if (config.jsonToStdout) System.err else System.out
  val reporter = ProgressReporter(collector, enabled = config.showProgress, out = progressOut)

  runBlocking {
    val collectorJob = launch { collector.consume(results) }
    val progressJob = launch { reporter.runLive() }
    try {
      engine.run(config.mode, config.stop)
    } finally {
      results.close()
      collectorJob.join()
      progressJob.cancelAndJoin()
      client.close()
      printSummaryOnce()
    }
  }

  val finalSnapshot = collector.snapshot()
  config.jsonOut?.let { target ->
    writeJsonFile(target, Report.json(finalSnapshot, start.elapsedNow(), config))
  }

  val breaches = Thresholds.evaluate(finalSnapshot, config)
  if (breaches.isNotEmpty()) {
    breaches.forEach { System.err.println("threshold breach: $it") }
    exitProcess(1)
  }
}
```

- [ ] **Step 2: Build the jar**

Run: `./gradlew formatKotlin && ./gradlew installDist`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test — help and JSON routing**

```bash
./build/install/stressline/bin/stressline --help        # curated help, exit 0
echo "help exit: $?"
python3 -m http.server 8099 >/dev/null 2>&1 &
SRV=$!; sleep 1
# JSON on stdout is clean (progress went to stderr), piped to a parser-ish check:
./build/install/stressline/bin/stressline http://localhost:8099 -c 10 -n 200 --json 2>/dev/null | head -c 1
echo   # expect a single '{' as the first stdout byte
```
Expected: help prints and exits 0; the `--json` stdout starts with `{` (no progress text contaminating it).

- [ ] **Step 4: Manual smoke test — json-out files and exit codes**

```bash
# auto-named file:
./build/install/stressline/bin/stressline http://localhost:8099 -c 5 -n 50 --json-out >/dev/null 2>&1
ls stressline-run-*.json && rm -f stressline-run-*.json
# explicit nested path:
./build/install/stressline/bin/stressline http://localhost:8099 -c 5 -n 50 --json-out reports/run.json >/dev/null 2>&1
cat reports/run.json | head -c 1; echo; rm -rf reports
# passing gate -> exit 0:
./build/install/stressline/bin/stressline http://localhost:8099 -c 5 -n 50 --fail-if-p95 10s >/dev/null 2>&1
echo "pass exit: $?"
# breaching gate -> exit 1:
./build/install/stressline/bin/stressline http://localhost:8099 -c 5 -n 50 --fail-if-p95 1ms >/dev/null 2>&1
echo "breach exit: $?"
kill $SRV 2>/dev/null
```
Expected: an auto-named `stressline-run-<ts>.json` is created; `reports/run.json` is created (starts with `{`); passing gate prints `pass exit: 0`; breaching gate prints `breach exit: 1`.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew formatKotlin && ./gradlew lintKotlin test`
Expected: BUILD SUCCESSFUL — all specs from Tasks 1–6 green, lint clean.

- [ ] **Step 6: Update `README.md`**

In the **Usage** block, add the new flags under a new group and the `-d` change:

```
Output:
  --json                Emit the summary as JSON on stdout (progress → stderr)
  --json-out [PATH]     Also write JSON to a file (bare → stressline-run-<ts>.json,
                        or a PATH ending in .json)

CI gate (exit 1 if breached):
  --fail-if-error-rate <PCT>   e.g. 1, 0.5
  --fail-if-p95 <DUR>          e.g. 200ms
```

Change the request line to: `  -d, --body <STR|@FILE>  Body string, or @path to read it from a file`.

Add a short **Use in CI** subsection after "Reading the results":

```markdown
## Use in CI

Gate a pipeline on latency/error thresholds — a breach exits non-zero:

\`\`\`bash
stressline https://staging.example.com/health -r 100 -t 30s \
  --fail-if-p95 200ms --fail-if-error-rate 1 \
  --json-out reports/loadtest.json
\`\`\`

Exit codes: `0` pass, `1` a threshold was breached, `2` bad usage. `--json`
prints the summary to stdout (progress goes to stderr) for piping to `jq`.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/stressline/Main.kt README.md
git commit -m "feat: wire JSON output, json-out file, thresholds, and custom help into main"
```

---

## Self-Review Notes

- **Spec coverage:** `--json` stdout + progress→stderr (Task 7) ✓; `--json-out` auto/path with `.json` rule + parent dirs (Tasks 3, 7) ✓; `--fail-if-error-rate`/`--fail-if-p95` + exit 1 (Tasks 3, 5, 7) ✓; exit codes 0/1/2 (Task 7) ✓; `-d @file` (Task 2) ✓; custom `-h/--help` (Tasks 6, 7) ✓; JSON shape with `mode`, `errorRatePct`, sorted `errors`/`statusCodes` (Task 4) ✓; no new dependency / Locale.US numbers (Tasks 4, 5) ✓; thresholds only after a completed run (Task 7, post-`runBlocking`) ✓; README CI docs (Task 7) ✓.
- **Deviation from spec (intentional):** `Thresholds.evaluate` takes `(Snapshot, RunConfig)` — `elapsed` was unused (error rate and p95 come from the snapshot), so it was dropped for a cleaner signature.
- **Type consistency:** `JsonOutTarget.Auto`/`File(path)`, `RunConfig.jsonToStdout`/`jsonOut`/`failIfErrorRate`/`failIfP95`, `Report.json(Snapshot, Duration, RunConfig)`, `Thresholds.evaluate(Snapshot, RunConfig)`, and `ProgressReporter(collector, enabled, out)` are used identically across producing and consuming tasks.
- **Lint:** every task formats before committing and the final task runs `lintKotlin` — required because kotlinter fails the build on unformatted code.
