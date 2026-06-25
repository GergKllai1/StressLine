# StressLine

A local HTTP/HTTPS stress-testing CLI built with Kotlin coroutines.

## Install (Linux/macOS)

Installs a `stressline` command on your `PATH`. Requires a Java 21+ runtime.

```bash
./install.sh                     # per-user install into ~/.local/bin (no sudo)
PREFIX=/usr/local ./install.sh   # system-wide (may require sudo)

stressline --url https://example.com --concurrency 50 --duration 30s
```

If the installer warns that the bin directory isn't on your `PATH`, add the
printed `export PATH=...` line to your shell profile. Remove the command with
`./uninstall.sh` (honors the same `PREFIX`).

The installer builds the app distribution (`./gradlew installDist`) — a launcher
script plus its dependency jars — and symlinks the launcher into your bin
directory. No fat jar required.

## Build a portable fat jar (alternative)

If you'd rather have a single self-contained jar instead of an install:

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
  `--concurrency` or `--rate` needs it. If it can't reach the needed value,
  raise the hard limit first: `ulimit -n 65535`.
- Target-rate (`--rate`) pacing uses a `delay`-based loop with ~1 ms JVM timer
  granularity, so it is accurate up to roughly low-thousands of RPS. For higher
  sustained load, use fixed-concurrency (`--concurrency`), which has no such
  ceiling.
