package org.sinemenda.probatio.core

/** The chain-state computation as a pure function (R-C3).
  *
  * Computes chain state over `(SpecLintReport, Ledger, Requirements, Baseline)`.
  * Reads no files internally; all file I/O lives in the CLI layer. The same
  * inputs always produce the same output — no environment variables, wall-clock
  * time, or external state.
  *
  * spec: probatio-core — Requirement: Chain-state computation is referentially transparent
  * spec: probatio-core — Property: Chain-state computation is referentially transparent
  */
object ChainState:

  /** A requirement in the requirements list. */
  final case class Requirement(
    spec: String,
    requirement: String
  )

  /** Compute chain state from declared inputs.
    *
    * Returns either an undetermined result with a reason, or a chain-state
    * report with bound, resolved, and discharged counts.
    *
    * spec: probatio-core — Scenario: same inputs produce same output
    * spec: probatio-core — Scenario: an unreadable ledger yields undetermined, not zero
    * spec: probatio-core — Scenario: a failed lint yields undetermined, not zero
    * spec: probatio-core — Scenario: a genuinely empty ledger is reported as zero discharged
    */
  def compute(
    lint: LintReport,
    ledger: Ledger.LedgerData,
    reqs: List[Requirement],
    baseline: String,
    change: String
  ): Either[ChainStateUndetermined, ChainStateReport] =
    // If the lint itself failed (not a finding, but a non-lint failure),
    // the result is undetermined — never collapsed into a finding or clean.
    if !lint.lintSuccess then
      Left(ChainStateUndetermined(change, baseline, "lint failure: spec-lint did not complete successfully"))
    else
      // The ledger is readable (it's a typed value, not a file). If it were
      // corrupt, the CLI layer would have produced an undetermined before
      // reaching this point. Here, we compute over the typed records.
      val records: List[LedgerRecord] = Ledger.read(ledger)

      // Filter records to the current change and baseline.
      val matchingRecords: List[LedgerRecord] = records.filter(r =>
        r.change == change && r.baseline == baseline
      )

      // Build the set of discharged requirements from ledger records.
      // A discharged requirement is one that has a matching ledger record
      // (same spec + requirement name, ring R0–R8).
      val dischargedReqs: Set[(String, String)] = matchingRecords
        .filter(r => r.ring != Ring.Manual)
        .map(r => (r.spec, r.obligation))
        .toSet

      // Determine bound/resolved from the lint report verdicts.
      val verdictsByReq: Map[String, RequirementVerdict] =
        lint.verdicts.map(v => v.requirement -> v).toMap

      val total: Int = reqs.length
      val bound: Int = reqs.count(r => verdictsByReq.get(r.requirement).exists(v =>
        v.verdict == Verdict.Bound || v.verdict == Verdict.Resolved
      ))
      val resolved: Int = reqs.count(r => verdictsByReq.get(r.requirement).exists(v =>
        v.verdict == Verdict.Resolved
      ))
      val discharged: Int = reqs.count(r => dischargedReqs.contains((r.spec, r.requirement)))

      // Build the unresolved list — requirements that are not fully discharged.
      val unresolved: List[UnresolvedEntry] = reqs.flatMap { r =>
        val verdict: Option[RequirementVerdict] = verdictsByReq.get(r.requirement)
        val isDischarged: Boolean = dischargedReqs.contains((r.spec, r.requirement))
        if isDischarged then None
        else
          val reasons: List[UnresolvedReason] =
            if verdict.isEmpty then List(UnresolvedReason.Unbound)
            else verdict match
              case Some(v) if v.verdict == Verdict.Unbound => List(UnresolvedReason.Unbound)
              case Some(v) if v.verdict == Verdict.Bound   => List(UnresolvedReason.Unresolved)
              case Some(v) if v.verdict == Verdict.Resolved => List(UnresolvedReason.Undischarged)
              case _ => List(UnresolvedReason.Failed)
          Some(UnresolvedEntry(r.spec, r.requirement, reasons))
      }

      Right(ChainStateReport(
        change = change,
        baseline = baseline,
        total = total,
        bound = bound,
        resolved = resolved,
        discharged = discharged,
        unresolved = unresolved,
        unmappedObligations = List.empty
      ))
