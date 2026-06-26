package dev.stressline

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.optional
import kotlin.time.Duration.Companion.seconds

class CliValidationException(
  message: String,
) : Exception(message)

fun parseArgs(args: Array<String>): RunConfig {
  val (jsonOut, rest) = extractJsonOut(args)
  val parser = ArgParser("stressline")
  val urlOpt by parser.option(
    ArgType.String,
    shortName = "u",
    fullName = "url",
    description = "Target URL (or pass it as the first argument)",
  )
  val urlArg by parser.argument(ArgType.String, fullName = "url", description = "Target URL").optional()
  val method by parser.option(ArgType.String, shortName = "X", fullName = "method", description = "HTTP method").default("GET")
  val headerOpts by parser.option(ArgType.String, shortName = "H", fullName = "header", description = "Header 'Name: Value'").multiple()
  val body by parser.option(ArgType.String, shortName = "d", fullName = "body", description = "Request body")
  val concurrency by parser.option(
    ArgType.Int,
    shortName = "c",
    fullName = "concurrency",
    description = "Fixed concurrency: N virtual users",
  )
  val rate by parser.option(ArgType.Int, shortName = "r", fullName = "rate", description = "Target requests per second")
  val requests by parser.option(ArgType.Int, shortName = "n", fullName = "requests", description = "Stop after N requests")
  val duration by parser.option(ArgType.String, shortName = "t", fullName = "duration", description = "Stop after duration e.g. 30s")
  val timeout by parser.option(ArgType.String, fullName = "timeout", description = "Per-request timeout").default("5s")
  val insecure by parser.option(ArgType.Boolean, fullName = "insecure", description = "Skip TLS verification").default(false)
  val noProgress by parser.option(ArgType.Boolean, fullName = "no-progress", description = "Disable live progress").default(false)
  val json by parser.option(ArgType.Boolean, fullName = "json", description = "Emit summary as JSON on stdout").default(false)
  val failIfErrorRate by parser.option(ArgType.Double, fullName = "fail-if-error-rate", description = "Exit 1 if error rate % exceeds this")
  val failIfP95 by parser.option(ArgType.String, fullName = "fail-if-p95", description = "Exit 1 if p95 latency exceeds this duration")

  parser.parse(rest)

  if (urlOpt != null && urlArg != null && urlOpt != urlArg) {
    throw CliValidationException("URL specified twice with different values; pass it once (as an argument or with --url)")
  }
  val url =
    urlOpt ?: urlArg
      ?: throw CliValidationException("a target URL is required (pass it as the first argument or with --url)")

  if (concurrency != null && concurrency!! <= 0) {
    throw CliValidationException("--concurrency must be a positive integer")
  }
  if (rate != null && rate!! <= 0) {
    throw CliValidationException("--rate must be a positive integer")
  }
  if (requests != null && requests!! <= 0) {
    throw CliValidationException("--requests must be a positive integer")
  }
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

  val mode =
    when {
      concurrency != null && rate != null ->
        throw CliValidationException("--concurrency and --rate are mutually exclusive")
      concurrency != null -> LoadMode.FixedConcurrency(concurrency!!)
      rate != null -> LoadMode.TargetRate(rate!!)
      else -> throw CliValidationException("Exactly one of --concurrency or --rate is required")
    }

  val stop =
    when {
      requests != null && duration != null ->
        throw CliValidationException("--requests and --duration are mutually exclusive")
      requests != null -> StopCondition.Requests(requests!!)
      duration != null -> StopCondition.ForDuration(DurationParser.parse(duration!!))
      else -> StopCondition.ForDuration(10.seconds)
    }

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

  return RunConfig(
    url = url,
    mode = mode,
    method = method,
    headers = headerOpts.map(::parseHeader),
    body = resolvedBody,
    stop = stop,
    timeout = DurationParser.parse(timeout),
    insecure = insecure,
    showProgress = !noProgress,
    jsonToStdout = json,
    jsonOut = jsonOut,
    failIfErrorRate = failIfErrorRate,
    failIfP95 = failP95,
  )
}

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
