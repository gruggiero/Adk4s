package org.sinemenda.probatio.core

import org.sinemenda.probatio.verified.GateKernel

/**
 * Typed contract for completion-witness-refusal (spec 3 of
 * `repair-probatio-cutover`, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath,
 * `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `WitnessVerdict` is a three-variant enum: `Witnessed` (checked,
 *    corroborated), `Unwitnessed(row)` — carrying the offending
 *    `ClaimVerdict` so a refusal can name what it refuses over — and
 *    `Undeterminable(reason)` carrying a stated `UndeterminedReason`.
 *    The third variant keeps "the corroboration check could not run"
 *    distinct from both verdict arms — an unreadable input is never a
 *    refusal and never a corroboration.
 *  - `CompletionDecision` is the verdict bounded by the per-turn
 *    refusal budget: `Allow`, `AllowUndetermined(reason)` — the
 *    fail-open proceed that still names the unreadable input — and
 *    `Refuse(unwitnessed)`, the promoted verdict which keeps the
 *    offending row. `isRefusal`/`namedRow` are the probes the Ring-6
 *    kernel contract quantifies over.
 *  - `GateDecisions.corroborationVerdict` computes the verdict from an
 *    already-built `ReconcileReport` — pure, no I/O — scoped to the
 *    CURRENT baseline (the declared divergence from the predecessor's
 *    unfiltered reconcile).
 *  - `GateDecisions.decideCompletion` bounds the verdict by
 *    `RefusalBudget`: `Undeterminable` abstains without consuming the
 *    budget; `Unwitnessed` refuses only while the budget is unspent.
 *  - `ReconcileReport.uncorroborated` is the record-order view the
 *    verdict is computed over — testimony and contradicted claims.
 *  - `GateKernel.decideCompletion` is the Ring-6 mirror: abstracted
 *    rows `(isGreen, baseline, corroborated)`, real `BigInt` baseline
 *    equality, refusal carrying the index of a justifying row.
 *
 * spec: completion-witness-refusal — Concepts Introduced (new): WitnessVerdict
 * spec: completion-witness-refusal — Contract: decideCompletion
 */
final class CompletionWitnessRefusalTypeContract extends ProbatioSuite:

  // ── WitnessVerdict — the three-variant corroboration verdict ──────
  val witnessedSig: WitnessVerdict =
    WitnessVerdict.Witnessed

  val unwitnessedSig: ClaimVerdict => WitnessVerdict.Unwitnessed =
    WitnessVerdict.Unwitnessed.apply

  val undeterminableSig: UndeterminedReason => WitnessVerdict.Undeterminable =
    WitnessVerdict.Undeterminable.apply

  val verdictNamedRowSig: WitnessVerdict => Option[ClaimVerdict] =
    (v: WitnessVerdict) => v.namedRow

  // ── CompletionDecision — the bounded decision ──────────────────────
  val allowSig: CompletionDecision =
    CompletionDecision.Allow

  val allowUndeterminedSig: UndeterminedReason => CompletionDecision.AllowUndetermined =
    CompletionDecision.AllowUndetermined.apply

  val refuseSig: WitnessVerdict.Unwitnessed => CompletionDecision.Refuse =
    CompletionDecision.Refuse.apply

  val isRefusalSig: CompletionDecision => Boolean =
    (d: CompletionDecision) => d.isRefusal

  val decisionNamedRowSig: CompletionDecision => Option[ClaimVerdict] =
    (d: CompletionDecision) => d.namedRow

  // ── GateDecisions — the pure predicates (bodies land at Step 3) ────
  val corroborationVerdictSig: (ReconcileReport, String) => WitnessVerdict =
    GateDecisions.corroborationVerdict

  val decideCompletionSig: (WitnessVerdict, RefusalBudget) => CompletionDecision =
    GateDecisions.decideCompletion

  // ── ReconcileReport — the record-order uncorroborated view ─────────
  val uncorroboratedSig: ReconcileReport => List[ClaimVerdict] =
    (r: ReconcileReport) => r.uncorroborated

  // ── RefusalBudget — the per-turn bound (existing type, unchanged) ──
  val budgetFromMarkerSig: Boolean => RefusalBudget =
    RefusalBudget.fromMarker

  val budgetExhaustedSig: RefusalBudget => Boolean =
    (b: RefusalBudget) => b.exhausted

  // ── GateKernel — the Ring-6 mirror (abstracted rows, BigInt ids) ───
  val kernelEvidenceRowSig: (Boolean, BigInt, Boolean) => GateKernel.EvidenceRow =
    GateKernel.EvidenceRow.apply

  val kernelDecideCompletionSig: (
    stainless.collection.List[GateKernel.EvidenceRow],
    BigInt,
    BigInt
  ) => GateKernel.CompletionDecision =
    GateKernel.decideCompletion

  // ── The pinned surface evaluates ───────────────────────────────────
  private val claim: ClaimVerdict = ClaimVerdict(
    spec = "s",
    ring = Ring.R3,
    obligation = "o",
    command = "c",
    baseline = "b",
    verdict = "testimony",
    observed = List.empty[BigInt]
  )

  test("WitnessVerdict is closed over three variants; Unwitnessed carries its row"):
    val witnessed: WitnessVerdict   = WitnessVerdict.Witnessed
    val unwitnessed: WitnessVerdict = WitnessVerdict.Unwitnessed(claim)
    val undeterminable: WitnessVerdict =
      WitnessVerdict.Undeterminable(UndeterminedReason.stated("record absent"))
    assertEquals(witnessed.namedRow, None)
    assertEquals(unwitnessed.namedRow, Some(claim))
    assertEquals(undeterminable.namedRow, None)

  test("CompletionDecision names the refused row iff it is a refusal"):
    val unwitnessed: WitnessVerdict.Unwitnessed = WitnessVerdict.Unwitnessed(claim)
    val refuse: CompletionDecision              = CompletionDecision.Refuse(unwitnessed)
    val allow: CompletionDecision               = CompletionDecision.Allow
    val abstain: CompletionDecision =
      CompletionDecision.AllowUndetermined(UndeterminedReason.stated("state area unreadable"))
    assert(refuse.isRefusal)
    assertEquals(refuse.namedRow, Some(claim))
    assert(!allow.isRefusal && allow.namedRow.isEmpty)
    assert(!abstain.isRefusal && abstain.namedRow.isEmpty)
