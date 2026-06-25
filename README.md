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
