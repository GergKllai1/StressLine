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
