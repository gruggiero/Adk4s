package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

/**
 * The multicall entry point (R-P6).
 *
 * Reads argv(0) and argv(1), dispatches to the appropriate entrypoint.
 * The multicall binary is invoked either as
 * `probatio <subcommand> <args>` (argv(1) dispatch) or as a symlink
 * `<subcommand> <args>` (argv(0) dispatch). Both paths resolve to the
 * same `Subcommand` and produce identical behavior.
 *
 * spec: cli-protocol — Requirement: Multicall dispatch by argv(1) and argv(0)
 * spec: cli-protocol — Implementation Anchor: ProbatioMain
 */
object ProbatioMain:

  /**
   * Dispatches to the subcommand and returns the exit code.
   *
   * Resolves the subcommand from argv, delegates to the subcommand's
   * entrypoint, and maps the `Outcome` to an exit code via `ExitCode.from`.
   * Parse errors emit a single-line error on stderr naming the offending
   * token and return a non-zero exit code.
   *
   * If `--help` is present in the remaining args, the subcommand's help
   * output is printed on stdout and exit 0 is returned (R-P5).
   *
   * spec: cli-protocol — Requirement: Three-way exit protocol for every subcommand
   * spec: cli-protocol — Requirement: Arg parsing errors name the missing or invalid flag
   * spec: cli-protocol — Requirement: Help lists every flag with its default
   */
  def dispatch(args: Array[String]): Int =
    val argv0: String         = if args.nonEmpty then args(0) else MulticallDispatch.binaryName
    val argv1: Option[String] = if args.length >= 2 then Some(args(1)) else None
    val rest: Array[String]   = if args.length >= 2 then args.drop(2) else Array.empty

    MulticallDispatch.resolve(argv0, argv1) match
      case Left(err) =>
        System.err.println(CliErrorRender.render(err))
        1
      case Right(sub) =>
        if rest.contains("--help") then
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
  private def runSubcommand(sub: Subcommand, args: Array[String]): Outcome[Int] =
    sub match
      case Subcommand.Gate           => GateCmd.run(args)
      case Subcommand.SpecLint       => SpecLintCmd.run(args)
      case Subcommand.ChainState     => ChainStateCmd.run(args)
      case Subcommand.Ledger         => LedgerCmd.run(args)
      case Subcommand.Checkpoint     => CheckpointCmd.run(args)
      case Subcommand.RegistryCheck  => RegistryCheckCmd.run(args)
      case Subcommand.Reconcile      => ReconcileCmd.run(args)
      case Subcommand.Scan           => ScanCmd.run(args)
      case Subcommand.RemovalAudit   => RemovalAuditCmd.run(args)
      case Subcommand.DangerScan     => DangerScanCmd.run(args)
      case Subcommand.ImpactScan     => ImpactScanCmd.run(args)
      case Subcommand.Metals         => MetalsCmd.run(args)
      case Subcommand.ConceptScanner => ConceptScannerCmd.run(args)
      case Subcommand.Graph          => GraphCmd.run(args)
      case Subcommand.InstallSkills  => InstallSkillsCmd.run(args)
      case Subcommand.InstallHooks   => InstallHooksCmd.run(args)

  /**
   * The JVM entry point — delegates to `dispatch` and exits with the
   * returned code. Required for `assembly` and `native-image` to find
   * the main method.
   *
   * spec: cli-protocol — Implementation Anchor: ProbatioMain
   */
  def main(args: Array[String]): Unit =
    val code: Int = dispatch(args)
    sys.exit(code)
