package org.sinemenda.probatio.core

/**
 * Compile-negative tests for completion-witness-refusal.
 *
 * The two forbidden constructions from the spec's Compile-Negative
 * Obligations table must NOT compile: a witness verdict with only two
 * variants, and a refusal that does not carry the offending row.
 *
 * spec: completion-witness-refusal — Compile-Negative Obligations
 */
final class CompletionWitnessRefusalCompileNegative extends ProbatioSuite:

  // ── A witness verdict with only two variants ───────────────────────
  // spec: completion-witness-refusal — Compile-Negative: A witness verdict with only two variants
  // Collapsing "could not read the corroboration" into either verdict
  // arm turns an environment fault into a claim — a refusal nobody
  // earned or an allow nobody verified. The third variant exists and
  // carries a reason; a fourth (or a missing third) does not compile.
  test("a witness verdict cannot be constructed as a fourth or reasonless variant"):
    val err: String = compileErrors(
      """val v: WitnessVerdict = WitnessVerdict.Unknown"""
    )
    assert(
      err.nonEmpty,
      "WitnessVerdict.Unknown must not compile — the verdict has exactly three variants"
    )

  // ── A refusal constructed without the offending row ────────────────
  // spec: completion-witness-refusal — Compile-Negative: A refusal constructed without the offending row
  // A refusal that does not name what it refuses over cannot be acted
  // on — the offending ClaimVerdict is a required parameter.
  test("a refusal cannot be constructed without the offending row"):
    val err: String = compileErrors(
      """WitnessVerdict.Unwitnessed()"""
    )
    assert(
      err.nonEmpty,
      "WitnessVerdict.Unwitnessed must require the offending row — a refusal always names it"
    )
