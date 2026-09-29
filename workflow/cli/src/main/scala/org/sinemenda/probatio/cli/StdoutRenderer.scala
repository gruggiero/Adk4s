package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.BannerOutput
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.CheckId
import org.sinemenda.probatio.core.ClaimVerdict
import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.DriftScanResult
import org.sinemenda.probatio.core.DriftWarning
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.GatePayload
import org.sinemenda.probatio.core.LintContext
import org.sinemenda.probatio.core.LintReport
import org.sinemenda.probatio.core.ReconcileReport
import org.sinemenda.probatio.core.Ring
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
        "reason"               -> ujson.Str(undetermined.reason.text),
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
      val warningLines: List[String] = report.warnings.map { w =>
        val atLine: String = w.line.fold("")(l => s" line $l")
        s"WARN ${w.code}$atLine: ${w.message}"
      }
      (verdictLines ++ warningLines).mkString("\n")

  /**
   * Render a LintContext as the CONTEXT block — byte-compatible with the
   * predecessor `spec-lint.sh` `CONTEXT — repository facts` section:
   * schema version, artifact-DAG status, install-root scan, instruction
   * drift, registry, inventory, deterministic kit, and the check
   * applicability lines.
   *
   * spec: spec-lint-engine — Requirement: The CLI emits the predecessor's context block before the findings
   */
  given StdoutRenderer[LintContext] with
    def render(context: LintContext): String =
      val header: List[String] = List(
        "spec-lint: CONTEXT — repository facts. These decide each conditional check's",
        "           APPLICABILITY. Compliance remains yours; applicability does not."
      )
      val schemaLine: List[String] = context.schemaVersion match
        case FactRead.Present(v) =>
          List(s"  schema                openspec/schemas/verified-scala3  v$v")
        case FactRead.Unreadable(r) =>
          List(s"  schema                openspec/schemas/verified-scala3  UNREADABLE — $r")
        case FactRead.Absent => Nil
      val schemaOpt: Option[Int] = context.schemaVersion match
        case FactRead.Present(v) => Some(v)
        case _ => // danger-scan:allow reject-to-None — Absent/Unreadable schema yields no version to compare
          None
      val drift: DriftScanResult = DriftScan.scan(schemaOpt, context.installRoots)
      val driftLines: List[String] = drift.warnings.flatMap {
        case DriftWarning.VersionMismatch(root, expected, found) =>
          List(
            s"  !! INSTRUCTION DRIFT: skill at $root is schema v$found, this schema is v$expected.",
            s"     Checks added after v$found are NOT in the instructions you are following.",
            "     Re-install (scanner/install-skills.sh) before trusting this report."
          )
        case DriftWarning.PreRenameStamp(root, expected, found) =>
          val head: String = expected match
            case Some(e) =>
              s"  !! INSTRUCTION DRIFT: skill at $root carries a pre-rename stamp " +
                s"(verified-scala3-schema/$found), this schema is v$e."
            case None =>
              s"  !! INSTRUCTION DRIFT: skill at $root carries a pre-rename stamp " +
                s"(verified-scala3-schema/$found)."
          List(
            head,
            "     Re-install (scanner/install-skills.sh) before trusting this report."
          )
        case DriftWarning.NoStampDeclared(root) =>
          List(s"  !! skill $root/openspec-spec-lint declares no schema version — pre-v7 install")
        case DriftWarning.Unreadable(root, reason) =>
          List(s"  !! skill $root/openspec-spec-lint could not be read — $reason")
      }
      val noSkill: List[String] =
        if drift.noSkillInstalled then List("  (no openspec-spec-lint skill installed in the searched roots)")
        else Nil
      val registryLines: List[String] = context.registry match
        case FactRead.Present(n) =>
          List(
            s"  behavioural registry  openspec/concepts/             PRESENT ($n concepts)",
            "    -> check 17 ALTITUDE **APPLIES**. \"N/A\" is not a valid verdict for it.",
            "       F10 checks the structural half; W7 lists code-identifier candidates;",
            "       reading the clause prose for behavioural altitude is still your job."
          )
        case FactRead.Absent =>
          List(
            "  behavioural registry  openspec/concepts/             ABSENT",
            "    -> check 17 ALTITUDE is N/A (attested by this script, not assumed)."
          )
        case FactRead.Unreadable(r) =>
          List(s"  behavioural registry  openspec/concepts/             UNREADABLE — $r")
      val inventoryLines: List[String] = context.inventoryTypes match
        case FactRead.Present(types) =>
          val zeroRows: List[String] =
            if types.isEmpty then
              List(
                "    !! parsed 0 type rows — the file exists but this script read nothing",
                "       from it. Fix the table shape before trusting W7 silence."
              )
            else Nil
          List(
            s"  type inventory        openspec/concept-inventory.md  PRESENT (${types.length} typed rows)",
            "    -> check 6 (reused concepts exist) **APPLIES**."
          ) ++ zeroRows
        case FactRead.Absent =>
          List(
            "  type inventory        openspec/concept-inventory.md  ABSENT",
            "    -> check 6 is N/A; run the concept scanner before trusting reuse claims."
          )
        case FactRead.Unreadable(r) =>
          List(s"  type inventory        openspec/concept-inventory.md  UNREADABLE — $r")
      val profileLines: List[String] = context.profile match
        case FactRead.Present(Some(kit)) =>
          List(
            "  capability profile    openspec/capability-profile.md PRESENT",
            "    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**",
            s"       deterministic test kit detected: $kit"
          )
        case FactRead.Present(None) =>
          List(
            "  capability profile    openspec/capability-profile.md PRESENT",
            "    -> check 3 **APPLIES**. Check 18: no deterministic test kit detected —",
            "       a concurrency requirement here is a capability gap, not an N/A."
          )
        case FactRead.Absent =>
          List(
            "  capability profile    openspec/capability-profile.md ABSENT",
            "    -> run detect-capabilities first; checks 3 and 18 cannot be judged."
          )
        case FactRead.Unreadable(r) =>
          List(s"  capability profile    openspec/capability-profile.md UNREADABLE — $r")
      (header ++ schemaLine ++ driftLines ++ noSkill ++
        registryLines ++ inventoryLines ++ profileLines).mkString("\n")

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

  /**
   * Render a ReconcileReport as the predecessor's text summary —
   * byte-compatible with `reconcile.sh`'s `jq -r` emit: the counts
   * header, then the contradicted block, then the testimony block.
   *
   * spec: danger-reconcile-engines — Scenario: Adversarial — an observer disagreeing with the written outcome is contradicted, reported separately
   */
  given StdoutRenderer[ReconcileReport] with
    def render(report: ReconcileReport): String =
      val header: String =
        s"reconcile: ${report.change} — ${report.rows} row(s), " +
          s"${report.witnesses} ambient witness(es), " +
          s"${report.claims.length} claim(s) needing corroboration, " +
          s"${report.witnessed.length} witnessed"
      val contradictedBlock: String =
        if report.contradicted.isEmpty then ""
        else
          "\n  contradicted (a witness recorded a different exit):" +
            report.contradicted
              .map { (v: ClaimVerdict) =>
                s"\n    ${v.spec}/${Ring.asString(v.ring)} ${v.obligation} — claimed 0, observed ${v.observed.mkString(",")}"
              }
              .mkString("")
      val testimonyBlock: String =
        if report.testimony.isEmpty then ""
        else
          "\n  testimony (green claim, no witness at this baseline):" +
            report.testimony
              .map((v: ClaimVerdict) => s"\n    ${v.spec}/${Ring.asString(v.ring)} ${v.obligation} — ${v.command}")
              .mkString("")
      header + contradictedBlock + testimonyBlock

  /**
   * Render a ReconcileReport as compact JSON — byte-compatible with the
   * predecessor's `jq -s -c` emit: `{change, rows, witnesses, claims,
   * witnessed, testimony, contradicted}` where the verdict arrays carry
   * `{spec, ring, obligation, command, baseline, verdict, observed}`.
   */
  def reconcileJson(report: ReconcileReport): String =
    def verdictJson(v: ClaimVerdict): ujson.Obj = ujson.Obj(
      "spec"       -> ujson.Str(v.spec),
      "ring"       -> ujson.Str(Ring.asString(v.ring)),
      "obligation" -> ujson.Str(v.obligation),
      "command"    -> ujson.Str(v.command),
      "baseline"   -> ujson.Str(v.baseline),
      "verdict"    -> ujson.Str(v.verdict),
      "observed"   -> ujson.Arr(v.observed.map((e: BigInt) => ujson.Num(e.toDouble))*)
    )
    ujson.write(
      ujson.Obj(
        "change"       -> ujson.Str(report.change),
        "rows"         -> ujson.Num(report.rows.toDouble),
        "witnesses"    -> ujson.Num(report.witnesses.toDouble),
        "claims"       -> ujson.Num(report.claims.length.toDouble),
        "witnessed"    -> ujson.Num(report.witnessed.length.toDouble),
        "testimony"    -> ujson.Arr(report.testimony.map(verdictJson)*),
        "contradicted" -> ujson.Arr(report.contradicted.map(verdictJson)*)
      )
    )
