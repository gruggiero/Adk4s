package org.sinemenda.probatio.core

/**
 * Typed contract for chain-state undetermined fidelity (spec 2 of
 * `repair-probatio-cutover`, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `PrePassOutcome` is a two-variant enum: `Completed(lints)` carries the
 *    per-spec lint outcomes the mechanical pre-pass produced; `DidNotRun`
 *    carries a stated reason and NO lint data — a measurement cannot be
 *    read out of a run that never happened. `isCompleted`/`didNotRun` are
 *    the exhaustive probes the Ring-6 kernel mirrors.
 *  - `UndeterminedReason` is an opaque refined type: no public `apply`, so
 *    `UndeterminedReason("")` does not compile; `of` is the validating
 *    route (returns `Either`), `stated` the total adapter route (empty
 *    input maps to `unclassifiable`, which still names the input under
 *    inspection rather than emitting a bare failure marker).
 *  - `ChainState.compute` takes `PrePassOutcome` as its first parameter —
 *    `DidNotRun` short-circuits to the undetermined verdict carrying the
 *    stated reason, so a report is produced only from a completed pre-pass.
 *  - `ChainStateReport.from` accepts `PrePassOutcome.Completed` as its
 *    first parameter — the completed pre-pass is the evidence token; the
 *    report's factory cannot be reached from `DidNotRun` at all. The raw
 *    `fromCounts` validator is `private[probatio]` — wire reconstruction
 *    (a report measured by another process's completed pre-pass) and
 *    in-package tests keep it; outside the probatio packages no route
 *    builds a report without a `Completed`.
 *  - `ChainStateUndetermined.reason` is an `UndeterminedReason` — a reason
 *    naming nothing is unconstructible; the type carries no count fields
 *    (total/bound/resolved/discharged exist only on `ChainStateReport`).
 *
 * spec: chain-state-undetermined-fidelity — Concepts Introduced (new): PrePassOutcome
 * spec: chain-state-undetermined-fidelity — Requirement: A verdict is produced only from a completed pre-pass
 * spec: chain-state-undetermined-fidelity — Requirement: Every distinct could-not-determine reason is named
 * spec: chain-state-undetermined-fidelity — Requirement: Counts are impossible without completed evidence
 */
final class ChainStateUndeterminedFidelityTypeContract extends ProbatioSuite:

  // ── PrePassOutcome — the completed / did-not-run boundary ───────────
  val completedApplySig: Map[String, Outcome[LintReport]] => PrePassOutcome.Completed =
    PrePassOutcome.Completed.apply

  val completedLintsSig: PrePassOutcome.Completed => Map[String, Outcome[LintReport]] =
    (c: PrePassOutcome.Completed) => c.lints

  val didNotRunApplySig: UndeterminedReason => PrePassOutcome.DidNotRun =
    PrePassOutcome.DidNotRun.apply

  val didNotRunReasonSig: PrePassOutcome.DidNotRun => UndeterminedReason =
    (d: PrePassOutcome.DidNotRun) => d.reason

  val isCompletedSig: PrePassOutcome => Boolean =
    (o: PrePassOutcome) => o.isCompleted

  val didNotRunSig: PrePassOutcome => Boolean =
    (o: PrePassOutcome) => o.didNotRun

  // ── UndeterminedReason — the refined stated reason ──────────────────
  val reasonOfSig: String => Either[String, UndeterminedReason] =
    UndeterminedReason.of

  val reasonStatedSig: String => UndeterminedReason =
    UndeterminedReason.stated

  val reasonUnclassifiableSig: UndeterminedReason =
    UndeterminedReason.unclassifiable

  val reasonTextSig: UndeterminedReason => String =
    (r: UndeterminedReason) => r.text

  // ── ChainState.compute — the verdict path takes the outcome ─────────
  val computePrePassSig: (
    PrePassOutcome,
    Ledger.LedgerData,
    RequirementSet,
    Map[String, List[String]],
    String,
    String,
    String,
    (String, String) => Boolean
  ) => Either[ChainStateUndetermined, ChainStateReport] = ChainState.compute

  // ── ChainStateReport.from — gated on the completed pre-pass ─────────
  val reportFromSig: (
    PrePassOutcome.Completed,
    String,
    String,
    Int,
    Int,
    Int,
    Int,
    List[UnresolvedEntry],
    List[UnmappedObligation]
  ) => Either[String, ChainStateReport] = ChainStateReport.from

  // ── ChainStateUndetermined — reason is refined, counts absent ───────
  val undeterminedApplySig: (String, String, UndeterminedReason) => ChainStateUndetermined =
    ChainStateUndetermined.apply

  // ── The pinned surface evaluates ────────────────────────────────────
  test("PrePassOutcome.Completed carries the lint map; DidNotRun carries only a reason"):
    val completed: PrePassOutcome.Completed = PrePassOutcome.Completed(Map.empty)
    assertEquals(completed.lints, Map.empty[String, Outcome[LintReport]])
    assert(completed.isCompleted && !completed.didNotRun)
    val reason: UndeterminedReason          = UndeterminedReason.stated("spec-lint.sh absent")
    val didNotRun: PrePassOutcome.DidNotRun = PrePassOutcome.DidNotRun(reason)
    assert(didNotRun.didNotRun && !didNotRun.isCompleted)
    assertEquals(didNotRun.reason.text, "spec-lint.sh absent")

  test("UndeterminedReason.of rejects the empty string; stated maps it to the named fallback"):
    assert(UndeterminedReason.of("").isLeft)
    assertEquals(UndeterminedReason.stated(""), UndeterminedReason.unclassifiable)
    assert(UndeterminedReason.unclassifiable.text.nonEmpty)

  test("a DidNotRun pre-pass yields the undetermined verdict with no report"):
    val result: Either[ChainStateUndetermined, ChainStateReport] = ChainState.compute(
      PrePassOutcome.DidNotRun(UndeterminedReason.stated("spec-lint.sh exit 127")),
      Ledger.fromRecords(Nil),
      RequirementSet.empty(FactSource.Degraded),
      Map.empty,
      "b",
      "b",
      "c",
      (_, _) => false
    )
    result match
      case Left(u) =>
        assertEquals(u.reason.text, "spec-lint.sh exit 127")
      case Right(_) =>
        fail("a pre-pass that did not run must never produce a measured report")

  test("ChainStateReport.from accepts a completed pre-pass and validates the counts"):
    val built: Either[String, ChainStateReport] = ChainStateReport.from(
      PrePassOutcome.Completed(Map.empty),
      "c",
      "b",
      total = 0,
      bound = 0,
      resolved = 0,
      discharged = 0,
      unresolved = Nil,
      unmappedObligations = Nil
    )
    assert(built.isRight, s"a contract-valid report through the gated factory must construct: $built")
