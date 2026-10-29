package org.sinemenda.probatio.migration

import upickle.default.*

/**
 * Types for the seam swap protocol and oracle-green regression (R-M1, R-M3).
 *
 * The existing environment-variable override seams are the strangler
 * swap-points. A migration step is complete when the oracle is green with
 * the ported tool substituted at its seam and every other tool still
 * running on the predecessor implementation.
 *
 * spec: migration-protocol — Requirement: Bats oracle is the porting acceptance suite
 * spec: migration-protocol — Requirement: Hook shims are swapped in dependency order after oracle clearance
 * spec: migration-protocol — Property: oracle-green-at-every-step
 */
object SeamTypes:

  /**
   * The tools whose live invocation paths the cutover replaces — the seam
   * set the differential comparison ranges over.
   *
   * `Ledger` and `Checkpoint` are seams even though the predecessor
   * implementation carries no `*_OVERRIDE` variable for them: their live
   * paths (`scanner/ledger.sh`, `scanner/checkpoint.sh`) are replaced by
   * the same exec-shim mechanism, and under materialisation an arm's seam
   * content is what sits at that path. A seam that is not in this set is
   * not compared.
   *
   * spec: differential-harness-integrity — Type-Constraint: the seam enum gains a ledger seam and a checkpoint seam
   * spec: differential-harness-integrity — Requirement: Every swapped seam is represented in the comparison
   */
  enum ToolId:
    case Ledger
    case ChainState
    case SpecLint
    case DangerScan
    case Reconcile
    case Checkpoint
    case Gate

  object ToolId:
    given ReadWriter[ToolId] = readwriter[ujson.Value].bimap(
      (t: ToolId) => ujson.Str(t.toString),
      (v: ujson.Value) =>
        v match
          case ujson.Str("Ledger")     => ToolId.Ledger
          case ujson.Str("ChainState") => ToolId.ChainState
          case ujson.Str("SpecLint")   => ToolId.SpecLint
          case ujson.Str("DangerScan") => ToolId.DangerScan
          case ujson.Str("Reconcile")  => ToolId.Reconcile
          case ujson.Str("Checkpoint") => ToolId.Checkpoint
          case ujson.Str("Gate")       => ToolId.Gate
          case other                   => sys.error(s"invalid ToolId: $other")
    )

    /**
     * The `*_OVERRIDE` environment variable name for this tool's delegation
     * seam inside the predecessor scripts, if one exists.
     *
     * `None` for `Ledger` and `Checkpoint` — the predecessors declare no
     * such variable, and inventing a name nothing reads would be a seam
     * that looks live but is not. Under materialisation the env-var
     * mechanism is superseded: an arm's seam holds its implementation at
     * the live path itself.
     */
    def overrideEnvVar(tool: ToolId): Option[String] = tool match
      case ToolId.Ledger     => None
      case ToolId.ChainState => Some("CHAIN_STATE_OVERRIDE")
      case ToolId.SpecLint   => Some("SPEC_LINT_OVERRIDE")
      case ToolId.DangerScan => Some("DANGER_SCAN_OVERRIDE")
      case ToolId.Reconcile  => Some("RECONCILE_OVERRIDE")
      case ToolId.Checkpoint => None
      case ToolId.Gate       => Some("GATE_OVERRIDE")

    /**
     * The live invocation path of a seam, relative to the schema directory
     * (`openspec/schemas/verified-scala3`). This is the path the cutover
     * replaces — the file an arm's materialisation resolves.
     */
    def seamPath(tool: ToolId): String = tool match
      case ToolId.Ledger     => "scanner/ledger.sh"
      case ToolId.ChainState => "scanner/chain-state.sh"
      case ToolId.SpecLint   => "scanner/spec-lint.sh"
      case ToolId.DangerScan => "scanner/danger-scan.sh"
      case ToolId.Reconcile  => "scanner/reconcile.sh"
      case ToolId.Checkpoint => "scanner/checkpoint.sh"
      case ToolId.Gate       => "hooks/gate.sh"

    /**
     * The predecessor implementation source for a seam, relative to the
     * schema directory. For every swapped tool this is the recorded
     * `*.predecessor.bak` file — the revert target preserved by the
     * measured swap.
     */
    def predecessorSource(tool: ToolId): String = tool match
      case ToolId.Ledger     => "scanner/ledger.sh.predecessor.bak"
      case ToolId.ChainState => "scanner/chain-state.sh.predecessor.bak"
      case ToolId.SpecLint   => "scanner/spec-lint.sh.predecessor.bak"
      case ToolId.DangerScan => "scanner/danger-scan.sh.predecessor.bak"
      case ToolId.Reconcile  => "scanner/reconcile.sh.predecessor.bak"
      case ToolId.Checkpoint => "scanner/checkpoint.sh.predecessor.bak"
      case ToolId.Gate       => "hooks/gate.sh.predecessor.bak"

    /**
     * The R-M3 swap order: purest, best-covered tools first; gate last.
     * Mirrors [[SwapOrder.swapOrder]] position-for-position.
     */
    val swapOrder: List[ToolId] = List(
      ToolId.Ledger,
      ToolId.ChainState,
      ToolId.SpecLint,
      ToolId.DangerScan,
      ToolId.Reconcile,
      ToolId.Checkpoint,
      ToolId.Gate
    )

  /** Which implementation a seam resolves to. */
  enum Implementation:
    case Ported
    case Predecessor

  /**
   * A seam configuration: which tools are on the ported implementation
   * (the rest are on the predecessor).
   *
   * Construction goes through the smart constructor [[SeamConfiguration.fromPorted]],
   * which takes only the ported set. The predecessor set is derived as
   * the complement. A configuration in which a seam appears in both
   * the ported and the predecessor sets is unconstructible — the raw
   * constructor does not accept a predecessor set.
   *
   * spec: cutover-gate — Requirement: A seam resolves to exactly one implementation
   * spec: cutover-gate — Compile-Negative: A SeamConfiguration in which a seam appears in both sets
   */
  final case class SeamConfiguration private (portedTools: Set[ToolId]):
    /**
     * The tools on the predecessor implementation (derived: all tools
     * not in the ported set).
     */
    def predecessorTools: Set[ToolId] = ToolId.swapOrder.toSet -- portedTools

    /**
     * Resolve a seam to exactly one implementation. Returns `Some(Ported)`
     * if the seam is in the ported set, `Some(Predecessor)` if it is in
     * the derived predecessor set. Always defined for every seam in
     * `ToolId.swapOrder` because the two sets partition the tool space.
     *
     * spec: cutover-gate — Property: seam-resolves-to-exactly-one
     */
    def resolve(seam: ToolId): Option[Implementation] =
      if portedTools.contains(seam) then Some(Implementation.Ported)
      else if predecessorTools.contains(seam) then Some(Implementation.Predecessor)
      else None

    /** A configuration with ALL tools on the predecessor. */
    def withPredecessor: SeamConfiguration = SeamConfiguration.fromPorted(Set.empty)

    /**
     * A configuration with exactly one tool ported (the first in swap
     * order that is not yet ported), for incremental testing.
     */
    def withPorted: SeamConfiguration =
      val next: Option[ToolId] = ToolId.swapOrder.find(t => !portedTools.contains(t))
      next match
        case Some(tool) => SeamConfiguration.fromPorted(portedTools + tool)
        case None       => this // all ported

  object SeamConfiguration:
    /**
     * Smart constructor: takes only the ported set. The predecessor set
     * is derived as the complement of `ToolId.swapOrder.toSet`. A seam
     * can never appear in both sets because the predecessor set is not
     * an input — it is computed.
     *
     * spec: cutover-gate — Requirement: A seam resolves to exactly one implementation
     */
    def fromPorted(portedTools: Set[ToolId]): SeamConfiguration =
      SeamConfiguration(portedTools)

  /**
   * The outcome of running the oracle under a seam configuration.
   *
   * The oracle produces a set of pass/fail outcomes. Regression holds
   * when the outcomes are identical before and after a substitution.
   */
  final case class OracleOutcome(
    passed: Int,
    failed: Int,
    skipped: Int
  ):
    def total: Int = passed + failed + skipped

  object OracleOutcome:
    given ReadWriter[OracleOutcome] = readwriter[ujson.Value].bimap(
      (o: OracleOutcome) =>
        ujson.Obj(
          "passed"  -> ujson.Num(o.passed),
          "failed"  -> ujson.Num(o.failed),
          "skipped" -> ujson.Num(o.skipped)
        ),
      (v: ujson.Value) =>
        v match
          case obj: ujson.Obj =>
            val m: Map[String, ujson.Value] = obj.value.toMap
            (m("passed"), m("failed"), m("skipped")) match
              case (p: ujson.Num, f: ujson.Num, s: ujson.Num) =>
                OracleOutcome(
                  passed = p.value.toInt,
                  failed = f.value.toInt,
                  skipped = s.value.toInt
                )
              case _ => sys.error(s"invalid OracleOutcome fields: $v")
          case other => sys.error(s"invalid OracleOutcome: $other")
    )
