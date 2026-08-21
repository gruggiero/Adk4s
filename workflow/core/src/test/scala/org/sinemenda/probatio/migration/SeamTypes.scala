package org.sinemenda.probatio.migration

import upickle.default.*

/** Types for the seam swap protocol and oracle-green regression (R-M1, R-M3).
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

  /** The tools that have override seams in the predecessor implementation.
    *
    * Each maps to an `*_OVERRIDE` environment variable in
    * `openspec/schemas/verified-scala3/hooks/gate.sh` or
    * `openspec/schemas/verified-scala3/scanner/chain-state.sh`.
    */
  enum ToolId:
    case SpecLint
    case ChainState
    case DangerScan
    case Reconcile
    case Gate

  object ToolId:
    given ReadWriter[ToolId] = readwriter[ujson.Value].bimap(
      (t: ToolId) => ujson.Str(t.toString),
      (v: ujson.Value) => v match
        case ujson.Str("SpecLint")     => ToolId.SpecLint
        case ujson.Str("ChainState")   => ToolId.ChainState
        case ujson.Str("DangerScan")   => ToolId.DangerScan
        case ujson.Str("Reconcile")    => ToolId.Reconcile
        case ujson.Str("Gate")         => ToolId.Gate
        case other                     => sys.error(s"invalid ToolId: $other")
    )

    /** The environment variable name for this tool's override seam. */
    def overrideEnvVar(tool: ToolId): String = tool match
      case ToolId.SpecLint     => "SPEC_LINT_OVERRIDE"
      case ToolId.ChainState   => "CHAIN_STATE_OVERRIDE"
      case ToolId.DangerScan   => "DANGER_SCAN_OVERRIDE"
      case ToolId.Reconcile    => "RECONCILE_OVERRIDE"
      case ToolId.Gate         => "GATE_OVERRIDE"

    /** The R-M3 swap order: purest, best-covered tools first; gate last. */
    val swapOrder: List[ToolId] = List(
      ToolId.ChainState,
      ToolId.SpecLint,
      ToolId.DangerScan,
      ToolId.Reconcile,
      ToolId.Gate
    )

  /** A seam configuration: which tools are on the ported implementation
    * (the rest are on the predecessor).
    */
  final case class SeamConfiguration(portedTools: Set[ToolId]):
    /** A configuration with ALL tools on the predecessor. */
    def withPredecessor: SeamConfiguration = SeamConfiguration(Set.empty)

    /** A configuration with exactly one tool ported (the first in swap
      * order that is not yet ported), for incremental testing.
      */
    def withPorted: SeamConfiguration =
      val next: Option[ToolId] = ToolId.swapOrder.find(t => !portedTools.contains(t))
      next match
        case Some(tool) => SeamConfiguration(portedTools + tool)
        case None       => this  // all ported

  /** The outcome of running the oracle under a seam configuration.
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
      (o: OracleOutcome) => ujson.Obj(
        "passed"  -> ujson.Num(o.passed),
        "failed"  -> ujson.Num(o.failed),
        "skipped" -> ujson.Num(o.skipped)
      ),
      (v: ujson.Value) => v match
        case obj: ujson.Obj =>
          val m: Map[String, ujson.Value] = obj.value.toMap
          (m("passed"), m("failed"), m("skipped")) match
            case (p: ujson.Num, f: ujson.Num, s: ujson.Num) =>
              OracleOutcome(
                passed  = p.value.toInt,
                failed  = f.value.toInt,
                skipped = s.value.toInt
              )
            case _ => sys.error(s"invalid OracleOutcome fields: $v")
        case other => sys.error(s"invalid OracleOutcome: $other")
    )
