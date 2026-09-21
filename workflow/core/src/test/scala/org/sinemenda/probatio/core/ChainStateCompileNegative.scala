package org.sinemenda.probatio.core

/**
 * Compile-negative tests for chain-state-undetermined-fidelity.
 *
 * The three forbidden constructions from the spec's Compile-Negative
 * Obligations table must NOT compile: a report assembled from a pre-pass
 * that did not complete, a could-not-determine carrying counts, and a
 * reason built from an empty string.
 *
 * spec: chain-state-undetermined-fidelity — Compile-Negative Obligations
 */
final class ChainStateCompileNegative extends ProbatioSuite:

  // ── A verdict report built from a pre-pass that did not complete ─────
  // spec: chain-state-undetermined-fidelity — Scenario: Adversarial — a non-completed pre-pass cannot produce a report
  // The defect this spec repairs: counts assembled without the invocation
  // that produces them. `from` accepts only the `Completed` variant, so a
  // `DidNotRun` argument is a type error, not a runtime rejection.
  test("a verdict report cannot be built from a pre-pass that did not complete"):
    val err: String = compileErrors(
      """ChainStateReport.from(
           PrePassOutcome.DidNotRun(UndeterminedReason.stated("spec-lint did not run")),
           "c", "b", 0, 0, 0, 0, Nil, Nil
         )"""
    )
    assert(
      err.nonEmpty,
      "ChainStateReport.from must not accept PrePassOutcome.DidNotRun — the report is unconstructible"
    )

  // ── A could-not-determine outcome carrying counts ────────────────────
  // spec: chain-state-undetermined-fidelity — Compile-Negative: A could-not-determine outcome carrying counts
  // A could-not-determine that carries numbers invites a reader to use
  // them — the type has no count field, so the construction does not
  // compile.
  test("a could-not-determine outcome cannot carry counts"):
    val err: String = compileErrors(
      """ChainStateUndetermined("c", "b", UndeterminedReason.stated("x"), total = 3)"""
    )
    assert(
      err.nonEmpty,
      "ChainStateUndetermined must have no count parameter — the field does not exist"
    )

  // ── A could-not-determine reason built from an empty string ──────────
  // spec: chain-state-undetermined-fidelity — Compile-Negative: A could-not-determine reason built from an empty string
  // A reason that names nothing fails the requirement that every reason
  // names an input. The opaque type has no public `apply`; the validating
  // route `of` returns an Either.
  test("a could-not-determine reason cannot be built from an empty string"):
    val err: String = compileErrors("""UndeterminedReason("")""")
    assert(
      err.nonEmpty,
      "UndeterminedReason must have no public apply — `of` is the validating route"
    )
