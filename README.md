# StressLine

A local HTTP/HTTPS stress-testing CLI built with Kotlin coroutines.

## Install (Linux/macOS)

Installs a `stressline` command on your `PATH`. Requires a Java 21+ runtime.

### From a release (recommended)

Downloads the latest published tarball and installs it (no build needed).
Replace `<owner>/<repo>` with this project's GitHub repository:

```bash
curl -fsSL https://raw.githubusercontent.com/<owner>/<repo>/main/install-release.sh \
    | STRESSLINE_REPO=<owner>/<repo> sh
```

Environment overrides: `PREFIX` (default `~/.local`; use `/usr/local` for
system-wide) and `VERSION` (default `latest`, or a tag like `v0.1.0`).

### From source

```bash
./install.sh                     # per-user install into ~/.local/bin (no sudo)
PREFIX=/usr/local ./install.sh   # system-wide (may require sudo)
```

Both installers symlink a launcher (the app distribution: a start script plus
its dependency jars) into `PREFIX/bin`. If you're warned that the bin directory
isn't on your `PATH`, add the printed `export PATH=...` line to your shell
profile. Remove the command with `./uninstall.sh` (honors the same `PREFIX`).

## Releasing

A GitHub Actions workflow (`.github/workflows/release.yml`) builds the
distribution tarball and publishes it to a GitHub Release when a version tag is
pushed:

```bash
git tag v0.1.0
git push origin v0.1.0
```

It uploads both `stressline-<version>.tar.gz` and a stable-named
`stressline.tar.gz`, so `install-release.sh` can fetch the latest via the
`releases/latest/download/` URL without an API call.

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
