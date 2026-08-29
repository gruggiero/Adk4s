package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.BannerOutput
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.CheckId
import org.sinemenda.probatio.core.GatePayload
import org.sinemenda.probatio.core.LintReport
import org.sinemenda.probatio.core.Verdict
import upickle.default.*

/**
 * Renders a core result type to the exact stdout string the bash original
 * emits (R-P1 wiring).
 *
 * A typeclass with a `render(value: A): String` method. Each core result
 * type (`ChainStateReport`, `LintReport`, `GatePayload`, `BannerOutput`)
 * gets a given instance. The entrypoint calls `StdoutRenderer[A].render(result)`
 * and prints the string.
 *
 * Rendering is testable independently of I/O — the property test calls
 * `render` directly and compares to the predecessor's output. The typeclass
 * is extensible — new subcommands add a given instance without modifying
 * existing code.
 *
 * spec: cli-wiring — Concepts Introduced: StdoutRenderer
 * spec: cli-wiring — Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report
 * spec: cli-wiring — Requirement: The spec-lint subcommand wires to the F1–F10 checks and emits the CONTEXT block
 */
trait StdoutRenderer[A]:
  /** Render the value to the stdout string. */
  def render(value: A): String

object StdoutRenderer:
  /** Summon the renderer for a given type. */
  def apply[A](using renderer: StdoutRenderer[A]): StdoutRenderer[A] = renderer

  /**
   * Render a ChainStateReport as JSON on stdout — byte-compatible with
   * the predecessor `chain-state.sh` output.
   *
   * spec: cli-wiring — Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report
   */
  given StdoutRenderer[ChainStateReport] with
    def render(report: ChainStateReport): String =
      // Manual JSON construction to ensure snake_case keys match the
      // chain-state-report-contract.jq (uPickle's derived ReadWriter
      // uses camelCase, which produces "unmappedObligations" — the
      // contract requires "unmapped_obligations").
      val obj: ujson.Obj = ujson.Obj(
        "change"               -> ujson.Str(report.change),
        "baseline"             -> ujson.Str(report.baseline),
        "total"                -> ujson.Num(report.total),
        "bound"                -> ujson.Num(report.bound),
        "resolved"             -> ujson.Num(report.resolved),
        "discharged"           -> ujson.Num(report.discharged),
        "unresolved"           -> ujson.Arr(report.unresolved.map(e => ujson.read(write(e)))*),
        "unmapped_obligations" -> ujson.Arr(report.unmappedObligations.map(e => ujson.read(write(e)))*)
      )
      ujson.write(obj)

  /**
   * Render a ChainStateUndetermined as JSON on stdout — the undetermined
   * report shape with null counts, matching the predecessor's
   * `die_undetermined` output.
   */
  given StdoutRenderer[org.sinemenda.probatio.core.ChainStateUndetermined] with
    def render(undetermined: org.sinemenda.probatio.core.ChainStateUndetermined): String =
      val obj: ujson.Obj = ujson.Obj(
        "change"               -> ujson.Str(undetermined.change),
        "baseline"             -> ujson.Str(undetermined.baseline),
        "undetermined"         -> ujson.Bool(true),
        "reason"               -> ujson.Str(undetermined.reason),
        "total"                -> ujson.Null,
        "bound"                -> ujson.Null,
        "resolved"             -> ujson.Null,
        "discharged"           -> ujson.Null,
        "unresolved"           -> ujson.Arr(),
        "unmapped_obligations" -> ujson.Arr()
      )
      ujson.write(obj)

  /**
   * Render a LintReport as the F1–F10 verdict text — byte-compatible with
   * the predecessor `spec-lint.sh` output.
   *
   * spec: cli-wiring — Requirement: The spec-lint subcommand wires to the F1–F10 checks and emits the CONTEXT block
   */
  given StdoutRenderer[LintReport] with
    def render(report: LintReport): String =
      val verdictLines: List[String] = report.verdicts.map { v =>
        s"${CheckId.asString(v.check)}: ${Verdict.asString(v.verdict)} — ${v.requirement}"
      }
      val warningLines: List[String] = report.warnings.map(w => s"WARN ${w.code} line ${w.line}: ${w.message}")
      (verdictLines ++ warningLines).mkString("\n")

  /**
   * Render a GatePayload as JSON on stdout — byte-compatible with the
   * predecessor `gate.sh` hook-json output.
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   */
  given StdoutRenderer[GatePayload] with
    def render(payload: GatePayload): String =
      write(payload)

  /**
   * Render a BannerOutput as text on stdout — byte-compatible with the
   * predecessor `gate.sh` banner output.
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   * spec: cli-wiring — Property: gate-banner-byte-compatibility
   */
  given StdoutRenderer[BannerOutput] with
    def render(banner: BannerOutput): String =
      banner.payload
