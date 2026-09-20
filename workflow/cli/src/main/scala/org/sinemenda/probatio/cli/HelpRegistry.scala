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
        "hook event name (session-start, prompt-submit, tool-call, post-edit, post-bash, completion)",
        "required"
      ),
      FlagHelp("--format", "output format (hook-json, text)", "hook-json"),
      FlagHelp("--repo", "repository root", "payload cwd"),
      FlagHelp("--session", "session id", "payload session"),
      FlagHelp("--file", "file path (tool-call, post-edit)", "none"),
      FlagHelp("--tool", "tool name (tool-call)", "none"),
      FlagHelp("--command", "command string (post-bash test seam)", "none"),
      FlagHelp("--exit", "exit code (post-bash test seam)", "none"),
      FlagHelp("--turn-text", "turn transcript text (completion)", "none"),
      FlagHelp("--stop-hook-active", "stop-hook flag (completion)", "none"),
      FlagHelp("--check-installed", "read the heartbeat and report installation state", "boolean"),
      FlagHelp("--change", "change name", "none"),
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
      FlagHelp("run", "execute a command and append a self-observed record", "none"),
      FlagHelp("read", "read records from the ledger", "none"),
      FlagHelp("verify", "verify every row and replay recorded commands", "none"),
      FlagHelp("--file", "ledger file path", "required"),
      FlagHelp("--change", "change name filter", "none"),
      FlagHelp("--spec", "spec name filter", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val checkpointHelp: HelpOutput = HelpOutput(
    Subcommand.Checkpoint,
    List(
      FlagHelp("report", "generate the checkpoint from recorded evidence", "none"),
      FlagHelp("regenerate-tasks", "rewrite tasks.md checkbox state from the tracker", "none"),
      FlagHelp("--ledger", "ledger file", "required"),
      FlagHelp("--change", "change name", "required"),
      FlagHelp("--spec", "spec name", "required"),
      FlagHelp("--baseline", "baseline SHA", "required"),
      FlagHelp("--rings", "comma-separated ring names (R0-R9, manual)", "required"),
      FlagHelp("--chain-state-json", "supplied correctness verdict JSON file", "required"),
      FlagHelp("--format", "json|text", "none"),
      FlagHelp("--change-dir", "change directory (per-spec baseline, marker repo)", "none"),
      FlagHelp("--session", "implementing session identity", "none")
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
