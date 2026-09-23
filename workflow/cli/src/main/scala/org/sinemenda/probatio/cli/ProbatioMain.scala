package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

/**
 * The multicall entry point.
 *
 * Obtains the invocation name from the runtime and the arguments as
 * `ProgramArgs` (program name excluded), dispatches to the appropriate
 * entrypoint. The multicall binary is invoked either as
 * `probatio <subcommand> <args>` (generic-name dispatch) or as a symlink
 * `<subcommand> <args>` (invocation-name dispatch). Both paths resolve to
 * the same `Subcommand` and produce identical behavior.
 *
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 * spec: cli-entrypoint-contract — Implementation Anchor: ProbatioMain
 */
object ProbatioMain:

  /**
   * Dispatches to the subcommand and returns the exit code.
   *
   * Resolves the subcommand from the invocation name and the argument list
   * via `MulticallDispatch.resolveAndSplit`, delegates to the subcommand's
   * entrypoint, and maps the `Outcome` to an exit code via `ExitCode.from`.
   * Parse errors emit a single-line error on stderr naming the offending
   * token and return a non-zero exit code.
   *
   * If `--help` is present in the remaining args, the subcommand's help
   * output is printed on stdout and exit 0 is returned.
   *
   * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
   * spec: cli-protocol — Requirement: Three-way exit protocol for every subcommand
   * spec: cli-protocol — Requirement: Arg parsing errors name the missing or invalid flag
   * spec: cli-protocol — Requirement: Help lists every flag with its default
   */
  def dispatch(name: InvocationName, args: ProgramArgs): Int =
    // Top-level `--help` under the generic name: show usage, exit 0.
    if MulticallDispatch.genericNames.contains(name.basename)
      && args.headOption.contains("--help")
    then
      System.out.println(HelpRegistry.topLevelUsage)
      0
    else
      MulticallDispatch.resolveAndSplit(name, args) match
        case Left(err) =>
          System.err.println(CliErrorRender.render(err))
          1
        case Right((sub, rest)) =>
          // The installer subcommands own their `-h`/`--help` handling:
          // predecessor parity requires the parser — not a blanket
          // intercept — to decide. `install-skills --help` is an
          // invalid-option finding (exit 1), and a `--help` sitting in a
          // flag's value position is consumed as the value.
          val ownsHelp: Boolean =
            sub == Subcommand.InstallSkills || sub == Subcommand.InstallHooks
          if rest.contains("--help") && !ownsHelp then
            System.out.println(HelpRegistry.helpFor(sub).render)
            0
          else
            val outcome: Outcome[Int] = runSubcommand(sub, rest)
            ExitCode.toInt(ExitCode.from(outcome))

  /**
   * Dispatches to the subcommand's entrypoint, returning an `Outcome[Int]`.
   *
   * The entrypoint delegates to probatio-core for decision logic; the CLI
   * boundary is arg parsing + exit-code mapping only (Ring 6
   * cross-reference: no decision logic is re-implemented here).
   *
   * spec: cli-protocol — Ring 6 Cross-Reference (formal contracts live in probatio-core)
   */
  private def runSubcommand(sub: Subcommand, args: ProgramArgs): Outcome[Int] =
    val rest: Array[String] = args.toArray
    sub match
      case Subcommand.Gate          => GateCmd.run(rest)
      case Subcommand.SpecLint      => SpecLintCmd.run(rest)
      case Subcommand.ChainState    => ChainStateCmd.run(rest)
      case Subcommand.Ledger        => LedgerCmd.run(rest)
      case Subcommand.Checkpoint    => CheckpointCmd.run(rest)
      case Subcommand.Reconcile     => ReconcileCmd.run(rest)
      case Subcommand.DangerScan    => DangerScanCmd.run(rest)
      case Subcommand.Metals        => MetalsCmd.run(rest)
      case Subcommand.InstallSkills => InstallSkillsCmd.run(rest)
      case Subcommand.InstallHooks  => InstallHooksCmd.run(rest)
      case Subcommand.Graph         => GraphCmd.run(rest)

  /**
   * The JVM entry point — obtains the invocation name from the runtime,
   * wraps `args` as `ProgramArgs` unchanged, delegates to `dispatch`, and
   * exits with the returned code.
   *
   * The invocation name is extracted from `sun.java.command` (JVM) or
   * `ProcessHandle.current().info().command()` (native-image). The JVM
   * delivers `args` without the program name (JVM convention), so `args`
   * is wrapped as `ProgramArgs` unchanged.
   *
   * spec: cli-entrypoint-contract — Implementation Anchor: ProbatioMain
   * spec: cli-entrypoint-contract — Scenario: Happy path — a runtime entry point produces the argument value
   */
  def main(args: Array[String]): Unit =
    val programArgs: ProgramArgs = ProgramArgs.fromRuntime(args)
    val rawName: String          = extractInvocationName
    InvocationName.fromRuntime(rawName) match
      case Left(err) =>
        System.err.println(err)
        sys.exit(1)
      case Right(inv) =>
        val code: Int = dispatch(inv, programArgs)
        sys.exit(code)

  /**
   * Extracts the invocation name from the runtime.
   *
   * On the JVM, `sun.java.command` is the full command line; the first
   * token is the program name (class or JAR path). On native-image,
   * `ProcessHandle.current().info().command()` gives the executable path.
   * Falls back to the generic name `probatio` if neither is available
   * (e.g. when running under a test harness).
   */
  private def extractInvocationName: String =
    // Try sun.java.command (JVM)
    val fromCommand: Option[String] =
      try
        Option(System.getProperty("sun.java.command")).flatMap { cmd =>
          val first: String = cmd.takeWhile(c => c != ' ')
          if first.nonEmpty then Some(first) else None
        }
      catch case _: SecurityException => None // danger-scan:allow name-probe-fallback — unreadable property falls back to the generic name
    // Try ProcessHandle.current().info().command() (JVM 16+ / native-image)
    val fromProcess: Option[String] =
      try
        val cmd: java.util.Optional[String] = ProcessHandle.current().info().command()
        if cmd.isPresent then Some(cmd.get) else None // danger-scan:allow guarded-get — isPresent guards the get
      catch case _: Throwable => None // danger-scan:allow name-probe-fallback — any probe failure falls back to the generic name, never to a verdict
    fromCommand.orElse(fromProcess).getOrElse("probatio")
