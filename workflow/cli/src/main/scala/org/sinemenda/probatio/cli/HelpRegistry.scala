package org.sinemenda.probatio.cli

/**
 * Registry of `HelpOutput` for each subcommand (R-P5).
 *
 * Each subcommand's `--help` output lists every flag it accepts with its
 * default value (or "required" / "none"). The registry is the single
 * source of truth for help text; `ProbatioMain.dispatch` looks up the
 * `HelpOutput` for the resolved subcommand when `--help` is present in
 * the remaining args.
 *
 * spec: cli-protocol — Requirement: Help lists every flag with its default
 * spec: cli-protocol — Property: help-lists-every-flag
 */
object HelpRegistry:

  /** Returns the `HelpOutput` for the given subcommand. */
  def helpFor(sub: Subcommand): HelpOutput =
    sub match
      case Subcommand.Gate          => gateHelp
      case Subcommand.SpecLint      => specLintHelp
      case Subcommand.ChainState    => chainStateHelp
      case Subcommand.Ledger        => ledgerHelp
      case Subcommand.Checkpoint    => checkpointHelp
      case Subcommand.Reconcile     => reconcileHelp
      case Subcommand.DangerScan    => dangerScanHelp
      case Subcommand.Metals        => metalsHelp
      case Subcommand.InstallSkills => installSkillsHelp
      case Subcommand.InstallHooks  => installHooksHelp

  /** Renders top-level usage listing all available subcommands. */
  def topLevelUsage: String =
    val subcommands: String = Subcommand.values
      .map(sub => s"  ${Subcommand.cliName(sub)}")
      .mkString("\n")
    s"probatio <subcommand> [options]\n\nSubcommands:\n$subcommands\n\nRun 'probatio <subcommand> --help' for details.\n"

  private val gateHelp: HelpOutput = HelpOutput(
    Subcommand.Gate,
    List(
      FlagHelp(
        "--event",
        "hook event name (session-start, prompt-submit, tool-call, post-edit, completion)",
        "required"
      ),
      FlagHelp("--change", "change name", "required"),
      FlagHelp("--spec", "spec name", "none"),
      FlagHelp("--baseline", "baseline SHA", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val specLintHelp: HelpOutput = HelpOutput(
    Subcommand.SpecLint,
    List(
      FlagHelp("--change", "change name", "required"),
      FlagHelp("--spec", "spec name", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val chainStateHelp: HelpOutput = HelpOutput(
    Subcommand.ChainState,
    List(
      FlagHelp("--change", "change name", "required"),
      FlagHelp("--baseline", "baseline SHA", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val ledgerHelp: HelpOutput = HelpOutput(
    Subcommand.Ledger,
    List(
      FlagHelp("append", "append a record to the ledger", "none"),
      FlagHelp("read", "read records from the ledger", "none"),
      FlagHelp("validate", "validate a record against the contract", "none"),
      FlagHelp("--file", "ledger file path", "required"),
      FlagHelp("--change", "change name filter", "none"),
      FlagHelp("--spec", "spec name filter", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val checkpointHelp: HelpOutput = HelpOutput(
    Subcommand.Checkpoint,
    List(
      FlagHelp("--change", "change name", "required"),
      FlagHelp("--spec", "spec name", "required"),
      FlagHelp("--ring", "ring name (R0-R8)", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val reconcileHelp: HelpOutput = HelpOutput(
    Subcommand.Reconcile,
    List(
      FlagHelp("--file", "ledger file", "required"),
      FlagHelp("--change", "change name", "required"),
      FlagHelp("--spec", "spec name", "none"),
      FlagHelp("--baseline", "baseline SHA", "none"),
      FlagHelp("--format", "json|text", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val dangerScanHelp: HelpOutput = HelpOutput(
    Subcommand.DangerScan,
    List(
      FlagHelp("<baseline>", "baseline ref (positional; default HEAD)", "none"),
      FlagHelp("--also", "additional files to scan (all remaining args)", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val metalsHelp: HelpOutput = HelpOutput(
    Subcommand.Metals,
    List(
      FlagHelp("start", "start the metals server", "none"),
      FlagHelp("--method", "LSP method name", "none"),
      FlagHelp("--params", "JSON params string", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val installSkillsHelp: HelpOutput = HelpOutput(
    Subcommand.InstallSkills,
    List(
      FlagHelp("--dir", "skills directory", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val installHooksHelp: HelpOutput = HelpOutput(
    Subcommand.InstallHooks,
    List(
      FlagHelp("--dir", "hooks directory", "required")
    ),
    HelpOutput.threeWayExit
  )
