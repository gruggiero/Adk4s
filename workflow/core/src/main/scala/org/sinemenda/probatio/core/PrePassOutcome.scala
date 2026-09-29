package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The outcome of the mechanical pre-pass that produces the facts a
 * chain-state verdict is computed from — the spec-lint run the adapter
 * invokes before the verdict fold (the predecessor's `"$SPEC_LINT"
 * --artifacts "$CHANGE_DIR"` invocation).
 *
 * The split is the boundary the cutover broke: a verdict — a
 * `ChainStateReport` carrying bound/resolved/discharged counts — exists
 * only on the `Completed` arm. A pre-pass that is absent, cannot be
 * executed, exits outside its declared outcome range, or produces no
 * recognised completion marker is `DidNotRun`, and a `DidNotRun` carries
 * a stated reason and NO counts: there is no measurement to count.
 *
 * `Completed` carries the per-spec lint outcomes the pre-pass produced —
 * a `DidNotRun` cannot hold lint data at all, so a measurement cannot be
 * read out of a run that never happened.
 *
 * spec: chain-state-undetermined-fidelity — Concepts Introduced (new): PrePassOutcome
 * spec: chain-state-undetermined-fidelity — Requirement: A verdict is produced only from a completed pre-pass
 */
enum PrePassOutcome:

  /** The pre-pass ran to completion and produced its per-spec lint outcomes. */
  case Completed(lints: Map[String, Outcome[LintReport]])

  /**
   * The pre-pass did not run to completion. `reason` names the unreadable
   * or unavailable input — the pre-pass tool itself, the evidence record,
   * the change directory, or the baseline — never a bare failure marker.
   */
  case DidNotRun(reason: UndeterminedReason)

object PrePassOutcome:

  extension (o: PrePassOutcome)

    /** True iff the pre-pass ran to completion. */
    def isCompleted: Boolean = o match
      case Completed(_) => true
      case DidNotRun(_) => false

    /** True iff the pre-pass did not run to completion. */
    def didNotRun: Boolean = o match
      case Completed(_) => false
      case DidNotRun(_) => true

/**
 * A stated could-not-determine reason — non-empty by construction, naming
 * the unreadable or unavailable input (the pre-pass, the evidence record,
 * the change directory, the baseline).
 *
 * The opaque type has no public `apply`: `UndeterminedReason("")` does
 * not compile, so a reason naming nothing is unrepresentable rather than
 * merely unrecommended. `of` is the validating route (returns `Left`
 * naming the violated clause); `stated` is the total route for adapter
 * text that must never emit a bare failure marker — empty input maps to
 * `unclassifiable`, which still names the input under inspection.
 *
 * spec: chain-state-undetermined-fidelity — Requirement: Every distinct could-not-determine reason is named
 * spec: chain-state-undetermined-fidelity — Compile-Negative: a could-not-determine reason constructed from an empty string
 */
opaque type UndeterminedReason = String

object UndeterminedReason:

  /**
   * The validating construction route. `Left` when `text` is empty — a
   * could-not-determine verdict with no stated reason is a claim without
   * evidence, the defect class this type exists to remove.
   */
  def of(text: String): Either[String, UndeterminedReason] =
    if text.isEmpty then
      Left(
        "an undetermined reason must be a non-empty string naming the unreadable input"
      )
    else Right(text)

  /**
   * The total construction route for adapter-side reason text: an empty
   * input yields `unclassifiable` — which still names the input under
   * inspection rather than emitting a bare failure marker. Non-empty
   * input is taken verbatim.
   */
  def stated(text: String): UndeterminedReason =
    of(text).fold(_ => unclassifiable, identity)

  /**
   * The reason for a cause that could not be classified into a named
   * input — the predecessor's catch-all shape, still naming the input
   * under inspection rather than a bare "failed".
   */
  val unclassifiable: UndeterminedReason = "the input under inspection"

  extension (r: UndeterminedReason)
    /** The stated reason text. */
    def text: String = r

  /**
   * Wire codec: writes the reason text; reads route through `of`, so an
   * empty reason on the wire is rejected (the codec crashes — a
   * contract-violating value is a bug, never admitted).
   */
  given ReadWriter[UndeterminedReason] = readwriter[String].bimap(
    (r: UndeterminedReason) => r.text,
    (s: String) =>
      of(s) match
        case Right(r) => r
        case Left(
              err
            ) => // danger-scan:allow type-rejection — an empty reason on the wire rejects, never maps to a valid value
          sys.error(s"invalid undetermined reason on the wire: $err")
  )
