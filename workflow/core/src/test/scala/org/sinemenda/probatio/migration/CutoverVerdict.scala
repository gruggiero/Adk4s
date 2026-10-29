package org.sinemenda.probatio.migration

/**
 * The gate's decision. A refusal always names its evidence.
 *
 * `Proceed` authorises the swap or stage transition.
 * `Revert(evidence)` refuses it, carrying the `DifferentialResult`
 * that produced the refusal so the evidence is always inspectable.
 *
 * The `Revert` variant requires a `DifferentialResult` — it cannot be
 * constructed without one. This is the type-level enforcement of the
 * obligation that a refusal always names its evidence.
 *
 * spec: cutover-gate — Concepts Introduced: CutoverVerdict
 * spec: cutover-gate — Compile-Negative: CutoverVerdict.Revert constructed without a DifferentialResult
 * spec: cutover-gate — Compile-Negative: CutoverVerdict pattern match omitting a case
 */
enum CutoverVerdict:
  case Proceed
  case Revert(evidence: DifferentialResult)
