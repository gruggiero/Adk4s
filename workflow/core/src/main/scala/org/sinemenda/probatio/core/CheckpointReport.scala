package org.sinemenda.probatio.core

/**
 * The generated checkpoint: the per-ring evidence for one change/spec at
 * one baseline, plus the supplied correctness verdict carried VERBATIM —
 * never recomputed, never reinterpreted.
 *
 * The raw constructor is private; the only construction path is
 * `CheckpointReport.of`, which derives `unresolvedCount` and
 * `markerWritten` from the evidence and the supplied verdict:
 *   - `unresolvedCount` is read off the verdict's own `unresolved`
 *     member (the predecessor's `.chain_state.unresolved | length`);
 *   - `markerWritten` is true iff every requested ring is green AND the
 *     verdict reports nothing unresolved — a report that claims the
 *     marker may be written while a ring is unevidenced is
 *     unrepresentable.
 *
 * `markerWritten` is the structural precondition for the write, not a
 * claim that a file was written — the marker file itself is a
 * session-keyed side effect the CLI performs when the flag holds.
 *
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): CheckpointReport
 * spec: ledger-checkpoint-parity — Compile-Negative: A CheckpointReport constructed with a marker-written flag set while any requested ring is unevidenced
 */
final case class CheckpointReport private (
  change: String,
  spec: String,
  baseline: String,
  rings: List[RingEvidence],
  chainState: ujson.Value,
  unresolvedCount: Int,
  markerWritten: Boolean
)

object CheckpointReport:

  /**
   * Build a checkpoint from assembled ring evidence and the supplied
   * verdict. The verdict is consumed, not computed — `unresolvedCount`
   * reads its own `unresolved` member's length with jq semantics:
   * missing/`null` reads as 0, a non-array scalar or object reads as its
   * jq `length` (a malformed `unresolved` is never silently "clean").
   */
  def of(
    change: String,
    spec: String,
    baseline: String,
    rings: List[RingEvidence],
    chainState: ujson.Value
  ): CheckpointReport =
    val unresolved: Int = CheckpointEngine.unresolvedCountOf(chainState)
    CheckpointReport(
      change,
      spec,
      baseline,
      rings,
      chainState,
      unresolvedCount = unresolved,
      // markerDecision is the shipped formulation (bridge-verified):
      // "green" for the marker means green AND evidenced — a hand-built
      // RingEvidence(Green, record = None) must not grant it.
      markerWritten = CheckpointEngine.markerDecision(
        rings.map((e: RingEvidence) => e.ring),
        rings
          .filter((e: RingEvidence) => e.status == RingStatus.Green && e.record.isDefined)
          .map((e: RingEvidence) => e.ring),
        unresolved
      )
    )

  /**
   * The report as the predecessor's JSON object:
   * `{change, spec, baseline, rings: [...], chain_state: <verdict
   * verbatim>}`. The verdict member is emitted byte-for-byte as
   * supplied; an unevidenced ring's row carries only `ring` and
   * `status`, exactly as the predecessor assembles it.
   */
  def toJson(report: CheckpointReport): ujson.Value =
    val ringRows: List[ujson.Obj] = report.rings.map { (e: RingEvidence) =>
      e.record match
        case None =>
          ujson.Obj(
            "ring"   -> ujson.Str(Ring.asString(e.ring)),
            "status" -> ujson.Str(RingStatus.token(e.status))
          )
        case Some(record) =>
          val row: ujson.Obj = ujson.Obj(
            "ring"       -> ujson.Str(Ring.asString(e.ring)),
            "status"     -> ujson.Str(RingStatus.token(e.status)),
            "obligation" -> ujson.Str(record.obligation),
            "artifact"   -> ujson.Str(record.artifact),
            "command"    -> ujson.Str(record.command),
            "exit"       -> ujson.Num(record.exit.toDouble)
          )
          e.note.foreach((n: String) => row.value("note") = ujson.Str(n))
          row
    }
    ujson.Obj(
      "change"      -> ujson.Str(report.change),
      "spec"        -> ujson.Str(report.spec),
      "baseline"    -> ujson.Str(report.baseline),
      "rings"       -> ujson.Arr(ringRows*),
      "chain_state" -> report.chainState
    )

  /**
   * The report as the predecessor's `--format text` output: one line per
   * ring (`green (cmd)` / `FAILED (cmd, exit n)` / `SAME-SESSION …` /
   * `UNVERIFIED-SESSION …` / `no recorded evidence`) followed by the
   * supplied chain-state counts and its unresolved list.
   */
  def toText(report: CheckpointReport): String =
    val ringLines: List[String] = report.rings.map { (e: RingEvidence) =>
      // jq interpolates a missing member as `null` — a hand-built
      // RingEvidence without a record renders the same, never silently
      // substituted text.
      val cmd: String = e.record.fold("null")((r: LedgerRecord) => r.command)
      val ex: String  = e.record.fold("null")((r: LedgerRecord) => r.exit.toString)
      val detail: String = e.status match
        case RingStatus.Green  => s"green ($cmd)"
        case RingStatus.Failed => s"FAILED ($cmd, exit $ex)"
        case RingStatus.SameSession =>
          s"SAME-SESSION ($cmd) — ${e.note.getOrElse("no fresh-context evidence")}"
        case RingStatus.UnverifiedSession =>
          s"UNVERIFIED-SESSION ($cmd) — ${e.note.getOrElse("requires human attestation")}"
        case RingStatus.Unevidenced => "no recorded evidence"
      s"  ${Ring.asString(e.ring)}: $detail"
    }
    val unresolvedCount: Int = CheckpointEngine.unresolvedCountOf(report.chainState)
    val chainLine: String =
      "  chain state: total " + fieldText(report.chainState, "total") +
        "  bound " + fieldText(report.chainState, "bound") +
        "  resolved " + fieldText(report.chainState, "resolved") +
        "  discharged " + fieldText(report.chainState, "discharged") +
        "  unresolved " + unresolvedCount.toString
    val unresolvedBlock: String =
      report.chainState.objOpt.flatMap(_.get("unresolved")) match
        case Some(ujson.Arr(items)) if items.nonEmpty =>
          "\n" + items
            .map { (item: ujson.Value) =>
              val requirement: String = item.objOpt.flatMap(_.get("requirement")).fold("null")(jqText)
              val reasons: String = item.objOpt.flatMap(_.get("reasons")) match
                case Some(ujson.Arr(rs)) => rs.map(jqText).mkString(",")
                case _ => // danger-scan:allow jq-null — a non-array reasons member renders as jq's null interpolation
                  "null"
              s"    $requirement ($reasons)"
            }
            .mkString("\n")
        case _ => // danger-scan:allow jq-shape — the block is emitted only for a non-empty unresolved array, as the predecessor does
          ""
    val ringsBlock: String = if ringLines.isEmpty then "" else "\n" + ringLines.mkString("\n")
    "checkpoint: " + report.change + "/" + report.spec + " @ " + report.baseline +
      ringsBlock + "\n" + chainLine + unresolvedBlock

  /** jq's interpolation of a verdict member: missing renders `null`. */
  private def fieldText(chainState: ujson.Value, name: String): String =
    chainState.objOpt.flatMap(_.get(name)).fold("null")(jqText)

  /** jq's `\(v)` interpolation — strings raw, null as `null`, the rest as JSON. */
  private def jqText(v: ujson.Value): String = v match
    case ujson.Str(s) => s
    case ujson.Null   => "null"
    case ujson.Num(n) => if n == n.floor then n.toLong.toString else n.toString
    case other => // danger-scan:allow jq-render — booleans/arrays/objects interpolate as their JSON form under jq
      ujson.write(other)

end CheckpointReport
