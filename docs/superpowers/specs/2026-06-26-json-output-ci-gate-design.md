# StressLine — JSON Output, CI Gate, Body-from-File, and Custom Help

**Date:** 2026-06-26
**Status:** Approved design

## Purpose

Three end-user features that make StressLine usable in automation and nicer on
the command line:

1. **JSON output + pass/fail thresholds** — emit the run summary as JSON (to
   stdout and/or a file) and exit non-zero when a latency/error threshold is
   breached, turning StressLine into a CI smoke/regression gate.
2. **Request body from a file** — `-d @path` reads the body from a file.
3. **Custom help** — a curated `-h`/`--help` with synopsis, grouped flags,
   examples, and the mode/duration rules.

These extend the existing CLI without changing current behavior: every existing
invocation keeps working, and a run with none of the new flags is unchanged.

## Goals & Non-Goals

**Goals**
- Machine-readable summary on stdout (`--json`) and/or a file (`--json-out`).
- CI gating via `--fail-if-error-rate` and `--fail-if-p95`, surfaced through exit
  codes.
- `-d @file` body loading.
- A genuinely helpful `--help`.

**Non-Goals**
- New load modes, multi-URL/scenario files, templated request data (unchanged
  non-goals).
- Additional output formats (CSV, etc.) or per-interval time series.
- Additional threshold metrics beyond error-rate and p95 (deferred until needed).

## CLI Surface

New and changed flags:

```
Output:
  --json                       Emit the summary as JSON on stdout. Suppresses the
                               human summary; the live progress line moves to stderr.
  --json-out [PATH]            Also write the JSON summary to a file. With no PATH,
                               writes ./stressline-run-<timestamp>.json. A PATH is
                               taken only if it does not start with '-' and ends in
                               '.json' (case-insensitive); parent dirs are created.

CI gate (evaluated after a completed run):
  --fail-if-error-rate <PCT>   Exit 1 if (failed / total) * 100 > PCT  (e.g. 1, 0.5)
  --fail-if-p95 <DUR>          Exit 1 if p95 latency > DUR             (e.g. 200ms)

Request:
  -d, --body <STR|@FILE>       Body string, or @path to read the body from a file
```

Existing flags (`-u/--url` + positional URL, `-c/--concurrency`, `-r/--rate`,
`-n/--requests`, `-t/--duration`, `-X/--method`, `-H/--header`, `--timeout`,
`--insecure`, `--no-progress`) are unchanged.

### `--json-out` value rule

kotlinx-cli cannot express an option whose value is optional, so `--json-out` is
extracted by a small pre-scan of the argument list before kotlinx-cli parses the
remainder:

- Find the `--json-out` token. Inspect the next token.
- If the next token exists, does **not** start with `-`, and ends with `.json`
  (case-insensitive) → that token is the path (`JsonOutTarget.File(path)`);
  consume both tokens.
- Otherwise → `JsonOutTarget.Auto`; consume only `--json-out`.
- The remaining tokens are handed to kotlinx-cli unchanged.

Consequence (intended): a URL never ends in `.json`, so `--json-out https://x`
leaves the URL alone and uses the auto name. A custom path that omits `.json` is
not treated as a path. Documented in help.

### Exit codes

- `0` — success (run completed; any thresholds passed).
- `1` — a `--fail-if-*` threshold was breached on a completed run.
- `2` — usage/validation error, or a `--json-out` file write failure.

Thresholds are evaluated **only after a normally completed run**. A Ctrl-C
partial run prints its partial summary (as today) but does not run threshold
checks, so an interrupted run never spuriously fails a gate.

### Output routing

- Live progress writes to **stdout** by default, but to **stderr** when `--json`
  is set (so stdout stays pure JSON). `--no-progress` still disables it entirely.
- The end-of-run summary:
  - `--json` set → JSON object to **stdout** (no leading blank line, no human
    summary).
  - `--json` not set → human summary to stdout (unchanged).
- `--json-out` is independent: if set, the same JSON is also written to the
  resolved file regardless of `--json`. So `--json-out report.json` alone shows
  the normal human summary on screen *and* saves the JSON file.

## JSON Shape

A single flat object. Only error categories that actually occurred appear in
`errors`; every distinct status code received appears in `statusCodes`.

```json
{
  "url": "https://example.com",
  "mode": { "type": "concurrency", "value": 50 },
  "total": 18432,
  "success": 18432,
  "failed": 0,
  "errorRatePct": 0.0,
  "durationSeconds": 10.01,
  "throughputPerSec": 1841.0,
  "latencyMs": { "min": 4, "mean": 27, "p50": 24, "p90": 41, "p95": 52, "p99": 88, "max": 213 },
  "errors": { "timeout": 3, "http": 1 },
  "statusCodes": { "200": 18430, "500": 2 }
}
```

- `mode.type` is `"concurrency"` or `"rate"` (the only two modes); `value` is the
  worker count or target RPS.
- `errorRatePct` = `0` when `total == 0`.
- `errors` keys use the existing category labels: `http` (a 4xx/5xx response),
  `timeout`, `connection-refused`, `too-many-files`, `other`. `http` is a rollup;
  the per-code detail is in `statusCodes`.
- JSON is produced by a small hand-rolled serializer (no new dependency); the
  `url` string is escaped.

## Architecture

Small, single-purpose units. Existing files: `Cli.kt`, `RunConfig.kt`,
`Report.kt`, `Main.kt`, `MetricsCollector.kt` (provides `Snapshot`).

| Unit | Responsibility |
|------|---------------|
| `RunConfig` | Add `jsonToStdout: Boolean`, `jsonOut: JsonOutTarget?`, `failIfErrorRate: Double?`, `failIfP95: Duration?`. Add `sealed interface JsonOutTarget { data object Auto; data class File(val path: String) }`. |
| `Cli.parseArgs` | Pre-scan extracts `--json-out` → `JsonOutTarget?`. Adds `--json`, `--fail-if-error-rate`, `--fail-if-p95`. Resolves `-d @file` (reads the file; missing file → `CliValidationException`). Validates error-rate ≥ 0 and parses the p95 `Duration` via `DurationParser`. Stays pure — no file write, no timestamp. |
| `Report.json(snapshot, elapsed, config)` | Returns the JSON string. Pure. |
| `Thresholds.evaluate(snapshot, elapsed, config)` | Returns a `List<String>` of breach messages (empty = pass). Pure. |
| `Help.text` | The curated help string. Pure. |
| `Main` | Intercepts `-h`/`--help` → print `Help.text`, exit 0. Routes progress (stderr when `--json`) and the summary (JSON vs human). If `jsonOut` set, resolves the filename (`Auto` → `stressline-run-<timestamp>.json`; `File` → its path, creating parent dirs) and writes; write failure → stderr message + exit 2. After a completed run, calls `Thresholds.evaluate`; if non-empty, prints messages to stderr and exits 1. The auto timestamp uses wall-clock time at write time. |

The summary-print path reuses the existing `printSummaryOnce()` guard (so the
Ctrl-C shutdown hook and the normal `finally` still print exactly once); it now
renders JSON to stdout when `--json` is set, otherwise the human summary.

## `-d @file` Resolution

In `parseArgs`, after the body option is read: if it is non-null and starts with
`@`, read the file at the remaining path (`body.substring(1)`) as UTF-8 text and
use it as the body. A nonexistent/unreadable file → `CliValidationException`
("body file not found: <path>"). A literal `@` body is therefore always treated
as a file reference (documented).

## Custom Help

`Main` checks for `-h`/`--help` in the raw args before parsing and, if present,
prints `Help.text` to stdout and exits 0 (bypassing kotlinx-cli's auto-help).
`Help.text` is a static string containing: the synopsis (positional URL and
`-u`), flags grouped as in this spec, 3–4 examples (including `--json | jq`,
`--json-out`, a CI-gate invocation, and `-d @file`), and the rules (exactly one
of `-c`/`-r`; `-n`/`-t` mutually exclusive, default `10s`; duration format
`500ms`/`30s`/`2m`; the `--json-out` `.json` rule).

## Testing

TDD with Kotest `ShouldSpec`, as in the rest of the project.

- **`Cli`** — `--json` sets the flag; `--json-out` (bare / followed by a flag) →
  `Auto`; `--json-out report.json` → `File`; `--json-out https://x` → `Auto` with
  the URL still parsed positionally; `-d @file` reads a temp file; `-d @missing`
  throws; `--fail-if-error-rate` parses and rejects negatives; `--fail-if-p95`
  parses a duration.
- **`Report.json`** — asserts the object contains the expected keys and correct
  values for a known `Snapshot` (counts, `errorRatePct`, latency fields, the
  `errors` and `statusCodes` maps); a syntactic sanity check (balanced braces).
- **`Thresholds`** — error-rate breach and pass; p95 breach and pass; both set;
  none set → empty; empty snapshot (`total == 0`) → pass.
- **`Help`** — `Help.text` contains the key flags, an example, and the mode rules.
- **`Main` wiring** — manual smoke test in the plan: `--json | jq` yields valid
  JSON on stdout with progress on stderr; `--json-out` (auto and explicit path,
  including a nested dir) creates the file; a breached `--fail-if-*` exits 1 while
  a passing run exits 0; `-d @file` sends the file contents.

## Out of Scope / Unchanged

- No new runtime dependency (JSON is hand-rolled).
- No change to load generation, metrics collection, the HTTP runner, or the
  install/release tooling.
