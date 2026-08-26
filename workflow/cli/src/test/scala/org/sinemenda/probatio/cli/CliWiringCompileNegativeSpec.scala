package org.sinemenda.probatio.cli

/**
 * Compile-negative tests for the cli-wiring spec.
 *
 * Verifies the three forbidden constructions from the spec's
 * Compile-Negative Obligations table do NOT compile.
 *
 * spec: cli-wiring — Compile-Negative Obligations
 * spec: cli-wiring — Compile-Negative: --force / --skip-validation flag on ledger append
 * spec: cli-wiring — Compile-Negative: update or delete action on the ledger subcommand
 * spec: cli-wiring — Compile-Negative: case _ catch-all in the gate's event dispatch
 */
final class CliWiringCompileNegativeSpec extends ProbatioCliSuite:

  // ── No --force flag on ledger append
  // spec: cli-wiring — Compile-Negative: --force / --skip-validation flag on ledger append
  // The bypass is unconstructible — the append method signature has no force parameter.

  test("LedgerCmd.append does not accept a force parameter"):
    val err: String = compileErrors("LedgerCmd.append(\"{}\", None, force = true)")
    assert(err.nonEmpty, "LedgerCmd.append should not accept a force parameter — the bypass is unconstructible")

  test("LedgerCmd.append does not accept a skipValidation parameter"):
    val err: String = compileErrors("LedgerCmd.append(\"{}\", None, skipValidation = true)")
    assert(err.nonEmpty, "LedgerCmd.append should not accept skipValidation — the bypass is unconstructible")

  // ── No update/delete action on ledger
  // spec: cli-wiring — Compile-Negative: update or delete action on the ledger subcommand
  // The mutation action is unconstructible — the enum has no Update or Delete case.

  test("LedgerCmd.Action.Update does not compile"):
    val err: String = compileErrors("LedgerCmd.Action.Update")
    assert(err.nonEmpty, "LedgerCmd.Action.Update should not exist — append-only invariant")

  test("LedgerCmd.Action.Delete does not compile"):
    val err: String = compileErrors("LedgerCmd.Action.Delete")
    assert(err.nonEmpty, "LedgerCmd.Action.Delete should not exist — append-only invariant")

  // ── No case-catch-all in gate event dispatch
  // spec: cli-wiring — Compile-Negative: case _ catch-all in the gate's event dispatch
  // The catch-all is unconstructible — the compiler rejects it at Ring 0
  // via -Wconf:name=PatternMatchExhaustivity:e

  test("GateCmd.Event has no sixth case"):
    val err: String = compileErrors("GateCmd.Event.SixthCase")
    assert(err.nonEmpty, "GateCmd.Event.SixthCase should not exist — exactly 5 cases")

  test("GateEvent has no sixth case"):
    val err: String = compileErrors("org.sinemenda.probatio.core.GateEvent.SixthCase")
    assert(err.nonEmpty, "GateEvent.SixthCase should not exist — exactly 5 cases")
