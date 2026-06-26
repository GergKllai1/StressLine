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

  parser.parse(args)

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

  return RunConfig(
    url = url,
    mode = mode,
    method = method,
    headers = headerOpts.map(::parseHeader),
    body = body,
    stop = stop,
    timeout = DurationParser.parse(timeout),
    insecure = insecure,
    showProgress = !noProgress,
  )
}
