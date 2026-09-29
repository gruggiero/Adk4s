package org.sinemenda.probatio.packaging

/**
 * Compile-negative obligations for native-packaging (static checks).
 *
 * These are NOT runtime tests — they are compile-time / static-analysis
 * obligations enforced by grep-based checks in the adversarial review
 * (Ring 8). The contract here documents the forbidden constructions so
 * the test oracle can reference them.
 *
 * spec: native-packaging — Compile-Negative Obligations
 */
object CompileNegative:

  /** The forbidden import strings that MUST NOT appear in packaging tests. */
  val forbiddenScalaCheckImports: List[String] = List(
    "org.scalacheck",
    "ScalaCheck",
    "Arbitrary",
    "arbitrary"
  )

  /** The forbidden CI wall-clock assertion patterns. */
  val forbiddenWallClockPatterns: List[String] = List(
    "assert(timeout",
    "assert(latency",
    "assert(duration",
    "assertTrue(timeout",
    "expect(timeout",
    "assert(p50",
    "assert(elapsed"
  )

  /** The forbidden cross-compilation patterns in CI matrix. */
  val forbiddenCrossCompilePatterns: List[String] = List(
    "rosetta",
    "Rosetta",
    "cross-compile",
    "crossCompile",
    "--target=",
    "x86_64-apple-darwin" // cross-compile target on apple-silicon runner
  )
