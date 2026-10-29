package org.sinemenda.probatio.migration

import org.sinemenda.probatio.cli.ProbatioCliSuite

import java.lang.ProcessBuilder

/**
 * Typed contract for hermetic test processes (spec 1 of
 * `finish-probatio-replacement`, Step 1) — the probatio-cli copy of
 * `workflow/core/src/test/scala/org/sinemenda/probatio/migration/HermeticEnvTypeContract.scala`.
 * Keep the two files identical apart from the suite base class (test
 * sources are not shared between modules).
 *
 * spec: hermetic-test-processes — Concepts Introduced (new): HermeticEnv, ControlledVariable
 * spec: hermetic-test-processes — Requirement: A spawned tool sees only the variables its test declares
 * spec: hermetic-test-processes — Requirement: Process construction in test code goes through the shared helper
 */
final class HermeticEnvTypeContract extends ProbatioCliSuite:

  // ── ControlledVariable — the closed set ─────────────────────────────
  val envNameSig: ControlledVariable => String =
    (v: ControlledVariable) => v.envName

  // ── HermeticEnv — private constructor, single factory ───────────────
  val buildSig: (Map[ControlledVariable, String], Map[String, String]) => HermeticEnv =
    HermeticEnv.build

  val buildCurrentSig: Map[ControlledVariable, String] => HermeticEnv =
    HermeticEnv.build

  val hasSig: HermeticEnv => ControlledVariable => Boolean =
    (env: HermeticEnv) => (v: ControlledVariable) => env.has(v)

  val valueSig: HermeticEnv => ControlledVariable => Option[String] =
    (env: HermeticEnv) => (v: ControlledVariable) => env.value(v)

  val toMapSig: HermeticEnv => Map[String, String] =
    (env: HermeticEnv) => env.toMap

  // ── The single route to a child process ─────────────────────────────
  val processBuilderSig: (List[String], HermeticEnv) => ProcessBuilder =
    HermeticEnv.processBuilder

  // ── Closed-set pinning ──────────────────────────────────────────────
  // spec: hermetic-test-processes — Concepts Introduced (new): ControlledVariable
  test("ControlledVariable is the closed set of variables the tools read"):
    val names: List[String] = ControlledVariable.values.map((v: ControlledVariable) => v.envName).toList
    assertEquals(names.length, 18, "the closed set holds exactly the measured variables")
    assertEquals(names.toSet.size, names.length, "controlled variable names must be unique")
    assert(
      names.contains("CLAUDE_CODE_SESSION_ID"),
      "the harness session variable must be controlled — it is the reported defect",
    )
    assert(
      names.contains("VERIFIED_SCALA3_SESSION_ID"),
      "the legacy session variable must be controlled",
    )
    assert(
      names.contains("PROBATIO_HOOKS") && names.contains("VERIFIED_SCALA3_HOOKS"),
      "the hook-control variable must be controlled in both spellings",
    )

  // spec: hermetic-test-processes — Requirement: A spawned tool sees only the variables its test declares
  test("the fixed base is exactly the search path, home, and temporary directory"):
    assertEquals(HermeticEnv.baseNames, Set("PATH", "HOME", "TMPDIR"))
