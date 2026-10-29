package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

import java.lang.ProcessBuilder

/**
 * Typed contract for hermetic test processes (spec 1 of
 * `finish-probatio-replacement`, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `ControlledVariable` is a closed enum of exactly the variables a
 *    workflow tool reads — the harness session variables, the workflow's
 *    own control variables in both spellings, and the test-seam overrides.
 *    Each case carries the literal environment name; there is no route from
 *    an arbitrary string to a controlled variable.
 *  - `HermeticEnv` is a final case class with a private constructor and no
 *    public `apply` (the companion is defined, so no apply is generated).
 *    The ONLY constructor is `HermeticEnv.build(declared, inherited)` —
 *    plus the one-argument convenience that reads the process environment once, inside
 *    the helper, and passes it through the same filter.
 *  - `build` yields the fixed base (PATH / HOME / TMPDIR drawn from
 *    `inherited`) plus exactly the declared controlled variables. A
 *    non-controlled entry of `inherited` does not survive either — the
 *    child environment contains nothing the test did not opt into.
 *  - `processBuilder(command, env)` is the single route to a child
 *    process: it clears the builder's inherited environment and installs
 *    `env` — redirects, working directory and stdin stay with the caller.
 *  - The file exists identically in probatio-core and probatio-cli test
 *    sources (the `MigrationTypes.ToolId` / `SeamTypes.ToolId` convention);
 *    the sbt-probatio copy arrives in Scala 2.12 encoding at Step 3.
 *
 * spec: hermetic-test-processes — Concepts Introduced (new): HermeticEnv, ControlledVariable
 * spec: hermetic-test-processes — Requirement: A spawned tool sees only the variables its test declares
 * spec: hermetic-test-processes — Requirement: Process construction in test code goes through the shared helper
 */
final class HermeticEnvTypeContract extends ProbatioSuite:

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
