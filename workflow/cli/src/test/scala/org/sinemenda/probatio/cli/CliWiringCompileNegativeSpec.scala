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

  test("GateCmd.Event has no seventh case"):
    val err: String = compileErrors("GateCmd.Event.SeventhCase")
    assert(err.nonEmpty, "GateCmd.Event.SeventhCase should not exist — exactly 6 cases")

  test("GateEvent has no seventh case"):
    val err: String = compileErrors("org.sinemenda.probatio.core.GateEvent.SeventhCase")
    assert(err.nonEmpty, "GateEvent.SeventhCase should not exist — exactly 6 cases")

  // ── An install mode defaulting to write
  // spec: install-tool-surface-parity — Compile-Negative: An install mode defaulting to write
  // The entrypoint requires the mode explicitly — a call without it fails
  // at compile time, so a write-by-default installer is unconstructible.

  test("InstallHooksCmd.runInstaller requires the mode explicitly"):
    val err: String = compileErrors(
      "InstallHooksCmd.runInstaller(java.nio.file.Paths.get(\"/tmp\"))"
    )
    assert(err.nonEmpty, "runInstaller without an InstallMode must not compile — write-by-default is unconstructible")

  // ── A prerequisite report built from names alone
  // spec: install-tool-surface-parity — Compile-Negative: A prerequisite report built from names alone
  // The type holds PrerequisiteProbes (name AND finding), never names —
  // checked-and-present is distinguishable from not-checked.

  test("PrerequisiteReport cannot be built from names alone"):
    val err: String = compileErrors(
      "org.sinemenda.probatio.core.PrerequisiteReport(List(\"jq\"))"
    )
    assert(err.nonEmpty, "PrerequisiteReport(List(\"jq\")) must not compile — the report holds probes, not names")

  // ── A single-directory install target
  // spec: install-tool-surface-parity — Compile-Negative: A single-directory install target
  // The enum has no such variant — a target that can name one directory
  // permits the narrowed install this spec removes.

  test("InstallTarget has no single-directory variant"):
    val err: String = compileErrors(
      "org.sinemenda.probatio.core.InstallTarget.SingleDir(java.nio.file.Paths.get(\"/tmp\"))"
    )
    assert(err.nonEmpty, "InstallTarget.SingleDir must not exist — only AllPresentHarnesses and NamedHarness")
