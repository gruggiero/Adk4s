package org.sinemenda.probatio.core

/**
 * The classification of a harness tool response (spec 8).
 *
 * `Exit` is an outcome the harness actually reported; `Skip` is every
 * response that is not an outcome — a refusal, an interruption, or a
 * shape the gate does not recognise. The case constructors are
 * `private[ToolOutcome]`: the only construction path is `classify`, so
 * a fabricated exit code — an `Exit` built from a response that carried
 * none — is unrepresentable.
 *
 * spec: gate-event-completeness — Concepts Introduced (new): ToolOutcome
 * spec: gate-event-completeness — Requirement: An observed outcome is recorded only when the harness reported one
 * spec: gate-event-completeness — Compile-Negative: A ToolOutcome.Exit constructed from a harness response classified as a refusal
 */
sealed trait ToolOutcome

object ToolOutcome:

  /** The command ran and the harness reported this exit code. */
  final case class Exit private[ToolOutcome] (code: Int) extends ToolOutcome

  /**
   * Not a command outcome. `reason` names why — it is always non-empty
   * (the only construction path supplies one of the three reasons below).
   */
  final case class Skip private[ToolOutcome] (reason: String) extends ToolOutcome

  /** `skip:` reason when the run was cut short. */
  val Interrupted: String = "interrupted"

  /** `skip:` reason when the response is a string that is not an exit report. */
  val NotACommandOutcome: String = "not-a-command-outcome"

  /** `skip:` reason when the response is neither an object nor a string. */
  val UnrecognisedShape: String = "unrecognised-response-shape"

  /** The predecessor's `"Error: Exit code N"` report shape (anchored). */
  private val exitCodeRe: scala.util.matching.Regex =
    "^Error: Exit code ([0-9]+)".r

  /**
   * The outcome predicate, verbatim from the predecessor (established
   * first-hand from this project's session transcripts — the field is
   * undocumented):
   *
   *   - `tool_response` is an OBJECT           → the command ran and
   *     succeeded; exit 0 is what the shape means — UNLESS the object
   *     carries `interrupted: true`, in which case the run was cut short
   *     and its exit says nothing.
   *   - `tool_response` is `"Error: Exit code N"` → the command ran and
   *     returned exactly N.
   *   - any OTHER string                       → the harness declined to
   *     run the command (permission denial, missing path); NOT an
   *     outcome.
   *   - any other shape                        → not recognised.
   *
   * Total and conservative: an `Exit` is produced only for the object
   * shape (code 0) and the error-string shape (the captured code); every
   * other input is a `Skip`.
   *
   * spec: gate-event-completeness — Property: outcome-classification-is-total-and-conservative
   * spec: gate-event-completeness — Contract: classifyOutcome
   */
  def classify(response: ujson.Value): ToolOutcome =
    response match
      case obj: ujson.Obj =>
        obj.value.get("interrupted") match
          case Some(ujson.Bool(true)) => Skip(Interrupted)
          case _ => // danger-scan:allow shape-is-outcome — the object shape IS the success report (predecessor `exit:0`)
            Exit(0)
      case ujson.Str(s) =>
        exitCodeRe.findPrefixMatchOf(s) match
          case Some(m: scala.util.matching.Regex.Match) =>
            // Totality: the predecessor captures the digits as a string and
            // the ledger's own grammar check rejects an out-of-range exit —
            // observable result: no row, exit 0. A code wider than Int is
            // likewise unrecordable here, so it classifies as Skip rather
            // than throwing. (Ring 8: bare .toInt overflowed on ≥11 digits
            // and crashed the never-blocking tier.)
            m.group(1).toIntOption match
              case Some(code: Int) => Exit(code)
              case None            => Skip(NotACommandOutcome)
          case None => // danger-scan:allow refusal-is-skip — a non-outcome string is never an exit (predecessor `skip:not-a-command-outcome`)
            Skip(NotACommandOutcome)
      case _ => // danger-scan:allow shape-rejection — non-object non-string responses are not outcomes (predecessor `skip:unrecognised-response-shape`)
        Skip(UnrecognisedShape)

end ToolOutcome
