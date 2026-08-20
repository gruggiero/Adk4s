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
      case Subcommand.Gate           => gateHelp
      case Subcommand.SpecLint       => specLintHelp
      case Subcommand.ChainState     => chainStateHelp
      case Subcommand.Ledger         => ledgerHelp
      case Subcommand.Checkpoint     => checkpointHelp
      case Subcommand.RegistryCheck  => registryCheckHelp
      case Subcommand.Reconcile      => reconcileHelp
      case Subcommand.Scan           => scanHelp
      case Subcommand.RemovalAudit   => removalAuditHelp
      case Subcommand.DangerScan     => dangerScanHelp
      case Subcommand.ImpactScan     => impactScanHelp
      case Subcommand.Metals         => metalsHelp
      case Subcommand.ConceptScanner => conceptScannerHelp
      case Subcommand.Graph          => graphHelp
      case Subcommand.InstallSkills  => installSkillsHelp
      case Subcommand.InstallHooks   => installHooksHelp

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

  private val registryCheckHelp: HelpOutput = HelpOutput(
    Subcommand.RegistryCheck,
    List(
      FlagHelp("--change", "change name", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val reconcileHelp: HelpOutput = HelpOutput(
    Subcommand.Reconcile,
    List(
      FlagHelp("--change", "change name", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val scanHelp: HelpOutput = HelpOutput(
    Subcommand.Scan,
    List(
      FlagHelp("--module", "module to scan", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val removalAuditHelp: HelpOutput = HelpOutput(
    Subcommand.RemovalAudit,
    List(
      FlagHelp("--baseline", "baseline SHA", "required"),
      FlagHelp("--module", "module to audit", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val dangerScanHelp: HelpOutput = HelpOutput(
    Subcommand.DangerScan,
    List(
      FlagHelp("--baseline", "baseline SHA", "required"),
      FlagHelp("--also", "additional file patterns to scan", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val impactScanHelp: HelpOutput = HelpOutput(
    Subcommand.ImpactScan,
    List(
      FlagHelp("--type", "public type name", "required"),
      FlagHelp("--module", "module to scan", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val metalsHelp: HelpOutput = HelpOutput(
    Subcommand.Metals,
    List(
      FlagHelp("start", "start the metals server", "none"),
      FlagHelp("stop", "stop the metals server", "none"),
      FlagHelp("call", "call a metals method", "none"),
      FlagHelp("--method", "LSP method name", "none"),
      FlagHelp("--params", "JSON params string", "none")
    ),
    HelpOutput.threeWayExit
  )

  private val conceptScannerHelp: HelpOutput = HelpOutput(
    Subcommand.ConceptScanner,
    List(
      FlagHelp("--module", "module to scan", "required")
    ),
    HelpOutput.threeWayExit
  )

  private val graphHelp: HelpOutput = HelpOutput(
    Subcommand.Graph,
    List(
      FlagHelp("--module", "module to extract graph from", "required"),
      FlagHelp("--format", "output format (dot, json)", "dot")
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
