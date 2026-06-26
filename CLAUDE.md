# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

StressLine is a single-machine HTTP/HTTPS load-testing CLI written in Kotlin/JVM
(Ktor client + coroutines). One command hits a URL and prints latency
percentiles, throughput, and an error breakdown. README.md documents the user-facing CLI.

## Commands

```bash
./gradlew build              # compile + test + lint
./gradlew test               # run tests (Kotest on the JUnit5 platform)
./gradlew test --tests "dev.stressline.CliTest"          # single test class
./gradlew test --tests "dev.stressline.CliTest.*method*" # single test by name pattern
./gradlew lintKotlin         # kotlinter check (2-space indent, see .editorconfig)
./gradlew formatKotlin       # auto-fix lint
./gradlew shadowJar          # single portable jar -> build/libs/StressLine-<ver>-all.jar
./gradlew run --args="https://example.com -c 10 -t 5s"   # run from source
./install.sh                 # build + install `stressline` into ~/.local/bin
```

JDK 17 is the build/target; the foojay resolver (settings.gradle.kts) provisions
one automatically if absent. Don't raise the toolchain — the tool must run on any JRE 17+.

## Architecture

The run is a producer/consumer pipeline wired together in `Main.kt`:

```
LoadEngine ──(RequestResult)──> Channel ──> ChannelMetricsCollector ──> Snapshot
    │                                                                       │
    └─ calls HttpRunner.execute() per request          Report.{summary,json} / Thresholds
```

- **`Cli.kt` → `RunConfig`** — `parseArgs` (kotlinx-cli) validates flags into an
  immutable `RunConfig`. `--json-out` is stripped out *before* parsing
  (`extractJsonOut`) because kotlinx-cli can't express an optional-value option.
  `-h`/`--help` is intercepted in `Main` before parsing and prints `Help.text`
  (hand-curated, not the kotlinx-cli auto-help).
- **`LoadEngine.kt`** — two load modes × two stop conditions:
  - `FixedConcurrency` (closed model): N coroutines each loop `execute()` as fast as possible.
  - `TargetRate` (open model): launch on a fixed interval, bounded by a semaphore. Capped ~few-thousand RPS by the ~1ms JVM timer.
  - Stop is either `Requests(N)` or `ForDuration` (via `withTimeoutOrNull`).
- **`HttpRunner.kt`** — `KtorHttpRunner` does one request via Ktor CIO, drains the
  body (releases the keep-alive connection), and maps the outcome to a
  `RequestResult(latency, RequestError)`. It never throws on failure except for
  coroutine cancellation — exceptions become `RequestError.{Timeout,ConnectionRefused,TooManyFiles,Other}`.
- **`MetricsCollector.kt`** — single consumer folds results into an HdrHistogram
  (+ success/fail/status/error tallies) under one lock; `snapshot()` produces an
  immutable `Snapshot`. Success = HTTP 2xx/3xx; 4xx/5xx is `failed`.
- **`Report.kt`** — renders a `Snapshot` to the human summary or to JSON (hand-rolled serializer, no JSON lib).
- **`Thresholds.kt`** — `evaluate()` checks `--fail-if-*` flags against the final snapshot for CI gating.
- **`FdLimit.kt`** — raises the process's soft file-descriptor limit via JNA
  before a high-concurrency run (`FdLimitPlanner.plan` is the pure, tested logic).

### Two things that affect output routing
- **Exit codes:** `0` pass, `1` a threshold was breached, `2` bad usage / IO error. Set via `exitProcess` in `Main.kt`.
- **`--json` (jsonToStdout)** moves the summary to stdout and pushes *everything
  else* (progress, FD warnings, the `Wrote JSON` line) to stderr, so stdout stays
  pipeable to `jq`. When touching any user-facing print, respect `config.jsonToStdout`.
- A shutdown hook prints the summary on Ctrl-C so an early-stopped run still reports (guarded by `printSummaryOnce`).

## Conventions

- Kotlin idiom throughout: `sealed interface` for closed variants (`LoadMode`,
  `StopCondition`, `RequestError`, `JsonOutTarget`), `data class` configs, `kotlin.time.Duration` for all times.
- Core types (`HttpRunner`, `MetricsCollector`) are interfaces so tests inject
  mocks (`ktor-client-mock`). Keep them mockable.
- Plans/specs for past work live in `docs/superpowers/`.
