# StressLine — Local HTTP Stress Testing CLI

**Date:** 2026-06-25
**Status:** Approved design

## Purpose

A command-line tool for local stress testing of HTTP/HTTPS endpoints. It fires
concurrent requests at a target URL using Kotlin coroutines, then reports
latency, throughput, and error statistics. Built with `kotlinx-cli` for argument
parsing and `kotlinx.coroutines` for concurrency.

Scope is a **single local load generator** running in one JVM on one machine.
Distributed load generation across multiple machines is explicitly out of scope.

## Goals & Non-Goals

**Goals**
- Drive concurrent HTTP load against a single URL with configurable method,
  headers, and body.
- Two load models: fixed concurrency (N virtual users) and target request rate
  (RPS).
- Accurate latency percentiles and categorized error reporting.
- Live progress during the run and a clean final summary.
- "Just works" locally — automatically raise the file-descriptor soft limit when
  the requested concurrency needs it.

**Non-Goals (v1)**
- Distributed / multi-machine load generation.
- Scenario files / multi-step request flows (single request shape only).
- Protocols other than HTTP/HTTPS.
- Sustained throughput beyond what a single JVM can produce (see Throughput).

## Architecture

Single Gradle (Kotlin DSL) project producing a runnable CLI. Internals split
into small, single-purpose units communicating through well-defined interfaces.

| Unit | Responsibility | Depends on |
|------|---------------|------------|
| `Cli` (main) | Parse args (kotlinx-cli), build `RunConfig`, wire components, print output | kotlinx-cli |
| `RunConfig` | Immutable value object: url, method, headers, body, mode, concurrency/rps, stop condition, timeout, flags | — |
| `FdLimit` | At startup, raise the FD soft limit toward the hard limit if requested concurrency needs it | JNA |
| `HttpRunner` | Wrap Ktor CIO client; execute one request → `RequestResult` | Ktor client |
| `LoadEngine` | Own coroutine structure; drive workers per selected mode; emit results into a `Channel` | coroutines, `HttpRunner` |
| `MetricsCollector` | Single coroutine draining the channel; feed HdrHistogram + counters; expose `snapshot()` (interface-backed) | HdrHistogram |
| `ProgressReporter` | Periodically read `snapshot()`, render live line; render final summary | `MetricsCollector` |

`MetricsCollector` sits behind an interface so the channel-based implementation
can be swapped (e.g. for shared concurrent accumulators) without touching the
engine.

### Chosen approach: channel-based pipeline

Worker coroutines execute requests and emit a small `RequestResult` into a
buffered `Channel`. A single collector coroutine drains the channel, feeds a
latency histogram and counters, and periodically snapshots stats for the live
progress line.

Rationale: idiomatic coroutines design; no lock contention on the hot path
(workers only `send`); clean separation between load generation and aggregation;
backpressure falls out naturally; the collector is a pure fold over a stream and
is therefore trivial to test. At local-tool scale the single collector is not a
bottleneck — CPU (TLS, request encoding) and the target server bind first. If
profiling ever shows otherwise, switching to shared concurrent accumulators is a
localized change behind the `MetricsCollector` interface.

## CLI Surface

Program name: `stressline`.

```
stressline --url <URL> [options]

Target
  -u, --url        Target URL (required)
  -X, --method     HTTP method (default: GET)
  -H, --header     Header "Name: Value" (repeatable)
  -d, --body       Request body (string; for POST/PUT/etc.)

Load mode (exactly one required)
  -c, --concurrency <N>   Fixed-concurrency mode: N virtual users
  -r, --rate <RPS>        Target-RPS mode: requests per second
                          (mutually exclusive with --concurrency)

Stop condition (choose one; defaults to --duration 10s)
  -n, --requests <N>      Stop after N total requests
  -t, --duration <DUR>    Stop after duration, e.g. 30s, 2m

Tuning
  --timeout <DUR>         Per-request timeout (default: 5s)
  --insecure              Skip TLS certificate verification
  --no-progress           Disable live progress line
```

Validation (post-parse, since kotlinx-cli does not express XOR natively):
- Exactly one of `--concurrency` / `--rate` must be provided; otherwise a clear
  error.
- `--requests` and `--duration` are mutually exclusive; if neither, default to
  `--duration 10s`.
- Durations (`30s`, `2m`, `500ms`) parsed by a small helper into
  `kotlin.time.Duration`.

## Data Flow & Concurrency

```
main → RunConfig → FdLimit.ensure(concurrency) → LoadEngine.run()
                        │
   ┌────────────────────┴─────────────────────┐
   │  coroutineScope (structured)              │
   │                                           │
   │  workers ──RequestResult──▶ Channel ──▶ MetricsCollector
   │   (HttpRunner)              (buffered)      (HdrHistogram + counters)
   │                                                  │
   │                                          ProgressReporter (~5 Hz)
   └───────────────────────────────────────────────────────────────────┘
```

### Lifecycle
1. `main` builds `RunConfig`; `FdLimit` raises the soft limit if needed;
   constructs a single shared Ktor client (connection pooling); starts
   `MetricsCollector` and `ProgressReporter` coroutines.
2. `LoadEngine.run()` opens a `coroutineScope`. Workers loop until the stop
   condition; each result is `channel.send(...)`.
3. Stop condition: duration uses a deadline; request-count uses a shared
   `AtomicInteger` claimed before each request so workers stop at exactly N.
4. When the engine scope completes, the channel is closed; `MetricsCollector`
   finishes draining; `ProgressReporter` prints the final summary.
5. Ktor client closed in a `finally`. **Ctrl-C** cancels the scope (shutdown
   hook) so a partial summary still prints.

### Load modes (one `LoadEngine`, strategy split)
- **Fixed-concurrency:** launch N worker coroutines, each looping until the stop
  condition, calling `HttpRunner` and sending results.
- **Target-RPS:** a `delay`-based pacing gate launches request jobs at the target
  rate into a bounded worker pool (semaphore). A slow server creates backpressure
  rather than unbounded coroutine growth.

### Concurrency correctness
- Workers never touch shared mutable stats — only `channel.send(...)`, so no
  locks on the hot path.
- `Channel` is buffered (capacity ~1024); backpressure engages only if it fills.
- Target-RPS in-flight work is bounded by a worker-pool semaphore.
- `Dispatchers.Default` for orchestration; Ktor CIO is non-blocking, so no thread
  starvation.

## File-Descriptor Auto-Raise (`FdLimit`)

Local high-concurrency runs otherwise hit the FD soft limit (often 1024) and
fail with misleading connection errors. At startup, if requested concurrency
would exceed the soft limit, `FdLimit` calls `setrlimit(RLIMIT_NOFILE)` via JNA
to raise the soft limit toward `min(needed, hardLimit)` and logs what it did.

- Raising the soft limit up to the hard limit requires no privileges.
- If even the hard limit is insufficient, it cannot self-fix (needs root) and
  falls back to a clear message recommending `ulimit -n 65535`.
- On Windows (no equivalent limit) it no-ops.
- The native call is wrapped behind an interface so logic is testable with a
  fake; the real call is exercised only on Linux/macOS.

## Errors & Metrics Output

### Error categorization
`RequestResult.error` is a sealed type: `None` (2xx/3xx), `HttpError(code)`
(4xx/5xx), `Timeout`, `ConnectionRefused`, `TooManyFiles` (FD exhaustion —
flagged with the `ulimit` hint), `Other(msg)`. A failing endpoint never crashes
the run; every request resolves to a result.

### Output
- **Live line** (~5 Hz; suppressed by `--no-progress` or non-TTY):
  `elapsed | sent N | ok N | err N | RPS ~X | p95 Yms`.
- **Final summary:**
  - Counts: total, success, failed (by error category + status-code histogram).
  - Throughput: achieved RPS, duration.
  - Latency (ms) from HdrHistogram: min, mean, p50, p90, p95, p99, max.
  - Numbers right-aligned in a fixed-width block.

## Testing Strategy

TDD. Kotest with the `ShouldSpec` (`context(...) { should(...) { ... } }`) style
throughout; `kotest-assertions-core` for assertions; `kotest-runner-junit5` via
`useJUnitPlatform()`.

- `RequestResult` / `RunConfig` / duration parser / error mapping → pure unit
  tests.
- `MetricsCollector` → feed a known sequence of results, assert
  snapshot/percentiles and per-category counts. Pure and deterministic.
- `LoadEngine` → `kotlinx-coroutines-test` virtual time + a fake `HttpRunner`;
  assert worker count, request-count mode stops at exactly N, RPS-mode pacing
  launches at the target rate.
- `HttpRunner` → Ktor `MockEngine` to assert request shaping (method/headers/
  body) and status→category mapping; a couple of integration tests against an
  embedded local server.
- CLI parsing/validation (XOR rules) → unit tests on the arg layer.
- `FdLimit` → interface-wrapped, tested with a fake; real native call exercised
  only on Linux/macOS.

Example:
```kotlin
class MetricsCollectorTest : ShouldSpec({
    context("when draining a sequence of results") {
        should("compute p95 from the recorded latencies") { /* ... */ }
        should("count timeouts separately from http errors") { /* ... */ }
    }
})
```

## Project Scaffolding

- Gradle Kotlin DSL.
- `application` plugin (`./gradlew run --args="..."`).
- Shadow plugin for a fat runnable jar.
- **Dependencies:** kotlinx-cli, kotlinx-coroutines-core, Ktor client CIO,
  HdrHistogram, JNA.
- **Test:** Kotest (`kotest-runner-junit5`, `kotest-assertions-core`),
  `kotlinx-coroutines-test`, Ktor `MockEngine`.

## Realistic Throughput (and limits)

Single JVM, single machine. The tool itself is not the ceiling — sockets, CPU,
and the target server bind first.

- Coroutines / virtual users: 10k–100k+ trivially; not the limiter.
- Plain HTTP, keep-alive, small responses, fast target: ~30k–100k RPS.
- HTTPS with connection reuse: ~10k–40k RPS (TLS crypto is CPU cost).
- HTTPS without keep-alive: low thousands (handshake + ephemeral-port/`TIME_WAIT`
  exhaustion). Connection reuse via the shared client matters enormously.

Walls hit first, in order: FD `ulimit` (mitigated by `FdLimit`), ephemeral
ports/`TIME_WAIT` (mitigated by keep-alive + shared client), CPU (TLS/parsing),
and the target server (halved if it shares the local machine).

Comfortable target: **tens of thousands of RPS**, covering the vast majority of
local "will my service fall over" questions. Beyond ~500k sustained RPS requires
a distributed generator — out of scope.
