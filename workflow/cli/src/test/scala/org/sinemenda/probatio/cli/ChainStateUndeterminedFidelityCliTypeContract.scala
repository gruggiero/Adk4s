package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.PrePassOutcome
import org.sinemenda.probatio.core.RequirementSet
import org.sinemenda.probatio.core.UndeterminedReason

/**
 * CLI-side typed contract for chain-state undetermined fidelity (spec 2 of
 * `repair-probatio-cutover`, Step 1).
 *
 * Pins the adapter boundary the Step-3 implementation must satisfy:
 * `ChainStateInputs` carries the pre-pass OUTCOME (not a bare lint map),
 * so the verdict fold can only ever see `Completed` lint data or a stated
 * `DidNotRun` — and the emit path's reason is a refined
 * `UndeterminedReason`.
 *
 * spec: chain-state-undetermined-fidelity — Implementation Anchor: ChainStateCmd
 */
final class ChainStateUndeterminedFidelityCliTypeContract extends ProbatioCliSuite:

  // ── ChainStateInputs — carries the pre-pass outcome, not a bare map ──
  val inputsPrePassSig: ChainStateCmd.ChainStateInputs => PrePassOutcome =
    (i: ChainStateCmd.ChainStateInputs) => i.prePass

  test("ChainStateInputs carries a PrePassOutcome"):
    val inputs: ChainStateCmd.ChainStateInputs = ChainStateCmd.ChainStateInputs(
      extracted = RequirementSet.empty(org.sinemenda.probatio.core.FactSource.Degraded),
      prePass = PrePassOutcome.DidNotRun(UndeterminedReason.stated("spec-lint.sh absent")),
      specBaselines = Map.empty,
      effectiveBaseline = "b",
      resolvedBaseline = "b",
      artifactUnchanged = (_, _) => false
    )
    inputs.prePass match
      case PrePassOutcome.DidNotRun(reason) =>
        assertEquals(reason.text, "spec-lint.sh absent")
      case PrePassOutcome.Completed(_) =>
        fail("the pinned outcome must be DidNotRun")
