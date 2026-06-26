# StressLine

A fast, local HTTP/HTTPS stress-testing CLI. Point it at a URL, choose how hard
to hit it, and get latency percentiles, throughput, and a breakdown of errors —
all from a single command, no config files.

```
$ stressline https://example.com --concurrency 50 --duration 10s
--- StressLine results ---
Total:       18432
Success:     18432
Failed:      0
Duration:    10.01s
Throughput:  1841 req/s
Latency (ms):
  min 4 | mean 27 | p50 24 | p90 41 | p95 52 | p99 88 | max 213
Status codes:
  200: 18432
```

## Requirements

- **Java 17 or newer** on your `PATH` (`java -version` to check). That's the only
  runtime dependency.
- Linux or macOS.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/GergKllai1/StressLine/main/install-release.sh | sh
```

This installs a `stressline` command into `~/.local/bin` (no sudo). If it warns
that the directory isn't on your `PATH`, add the printed `export PATH=...` line
to your shell profile and reopen your shell.

Options are environment variables placed on the `sh` side of the pipe, e.g.
`curl ... | PREFIX=/usr/local sh`:

- `PREFIX` — install location (default `~/.local`; use `/usr/local` for a
  system-wide install, which may need `sudo`).
- `VERSION` — a specific release tag (default `latest`).

## Uninstall

```bash
curl -fsSL https://raw.githubusercontent.com/GergKllai1/StressLine/main/uninstall.sh | sh
```

Pass the same `PREFIX` you installed with if you didn't use the default — note
it goes on the `sh` side of the pipe, e.g. `curl ... | PREFIX=/usr/local sh`. It
removes only `PREFIX/bin/stressline` and `PREFIX/share/stressline`.

## Quick start

The URL can be the first argument (curl-style) or given with `-u`/`--url`.

```bash
# Hammer a URL with 50 concurrent clients for 10 seconds
stressline https://example.com --concurrency 50 --duration 10s

# Hold a steady 200 requests/second for one minute
stressline https://api.example.com/health --rate 200 --duration 1m

# Send exactly 1000 requests, 20 at a time
stressline https://example.com --requests 1000 --concurrency 20

# POST JSON with headers
stressline https://api.example.com/users \
  -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"test"}' \
  --concurrency 10 --duration 30s

# A local server with a self-signed certificate
stressline https://localhost:8443 --insecure --concurrency 25 --duration 15s
```

## Usage

```
stressline <URL> [options]
stressline -u <URL> [options]     # equivalent

Load mode (exactly one required):
  -c, --concurrency <N>   N concurrent clients, each looping as fast as it can
  -r, --rate <RPS>        Hold a target of N requests per second

Stop condition (default: --duration 10s):
  -n, --requests <N>      Stop after N total requests
  -t, --duration <DUR>    Stop after a duration, e.g. 500ms, 30s, 2m

Request:
  -X, --method <M>        HTTP method (default GET)
  -H, --header "K: V"     Add a header (repeat for multiple)
  -d, --body <STR>        Request body

Tuning:
  --timeout <DUR>         Per-request timeout (default 5s)
  --insecure              Skip TLS certificate verification
  --no-progress           Disable the live progress line

  -h, --help              Show this help
```

A live progress line updates while the run is in flight; press **Ctrl-C** at any
time to stop early and still get a summary of what ran so far.

## Choosing a load mode

The two modes answer different questions:

- **`--concurrency N` (closed model)** — "How does the service behave with N
  clients pushing as hard as they can?" Each client fires a request, waits for
  the response, then immediately fires the next. Throughput naturally backs off
  if the server slows down. Use this for capacity and saturation testing, and
  for the highest load you can generate.

- **`--rate R` (open model)** — "How does the service behave at exactly R
  requests/second?" Requests are launched on a schedule regardless of how fast
  responses come back, which better models real-world traffic. Use this to test
  a specific target load or an SLA.

If your *achieved* throughput comes in well below a `--rate` target, the server
(or your machine) couldn't keep up — that itself is a useful result.

## Reading the results

- **Total / Success / Failed** — request counts. **Success is 2xx and 3xx
  responses.** Any 4xx/5xx is counted as failed and broken out under *Errors*
  and *Status codes*.
- **Throughput** — requests per second actually achieved over the run.
- **Latency percentiles** — `p95 52` means 95% of requests completed in 52 ms or
  less. Watch the **tail** (p95/p99), not just the mean — a good average can hide
  a slow tail that real users feel. Latency is measured end-to-end per request,
  including reading the response body.
- **Errors** — grouped by cause:
  - `http` — a 4xx/5xx response (see *Status codes* for which)
  - `timeout` — no response within `--timeout`
  - `connection-refused` — nothing accepting connections at the target
  - `too-many-files` — you hit the file-descriptor limit (see Tips)
  - `other` — anything else (DNS, TLS, resets); the run never crashes on these

## Tips & limits

- **It's a single-machine generator.** Comfortable into the tens of thousands of
  RPS depending on TLS and the target. For more than that, you'd need a
  distributed load tool.
- **The target — or your own machine — is often the real limit.** If you're
  testing a server on the same box, you're sharing CPU with it. Low throughput
  may mean the *target* is the bottleneck, which is usually what you want to find.
- **High concurrency needs file descriptors.** Each open connection uses one. On
  Linux/macOS StressLine raises its own soft limit automatically, but if it warns
  it couldn't go high enough, raise it yourself before the run:
  `ulimit -n 65535`.
- **`--rate` has a pacing ceiling of roughly a few thousand RPS** (it's limited by
  the ~1 ms JVM timer). To push higher, use `--concurrency`, which has no such
  ceiling.
- **Connections are reused (keep-alive)** automatically — essential for accurate,
  high-throughput numbers.
- **`--insecure`** disables TLS verification — handy for self-signed or local
  endpoints. Don't use it when the certificate actually matters.

## Build from source

If you'd rather build it yourself (requires the repo checked out; the build
fetches a JDK 17 toolchain automatically if you don't have one):

```bash
./install.sh                 # build and install into ~/.local/bin
# or produce a single portable jar:
./gradlew shadowJar          # -> build/libs/StressLine-<version>-all.jar
```
