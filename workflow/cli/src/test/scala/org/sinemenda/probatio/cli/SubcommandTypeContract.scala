package org.sinemenda.probatio.cli

/**
 * Compile-negative tests for the Subcommand sealed enum and the append-only
 * ledger surface.
 *
 * munit's `compileErrors` returns the compiler error string (empty if the
 * code compiles successfully). We assert the error is non-empty — i.e. the
 * forbidden construction does NOT compile.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Compile-Negative: A subcommand named update/delete/rewrite/edit
 * spec: port-scanner-to-probatio/cli-protocol — Compile-Negative: An exit code other than 0/1/2
 * spec: port-scanner-to-probatio/cli-protocol — Compile-Negative: A CliError variant without offendingToken
 */
final class SubcommandTypeContract extends ProbatioCliSuite:

  // ── Mutation subcommands do not exist in the sealed enum
  // spec: cli-protocol — Compile-Negative: Subcommand.update
  test("Subcommand.update does not compile (append-only invariant)"):
    val err: String = compileErrors("Subcommand.update")
    assert(err.nonEmpty, "Subcommand.update should not exist — append-only invariant")

  test("Subcommand.delete does not compile (append-only invariant)"):
    val err: String = compileErrors("Subcommand.delete")
    assert(err.nonEmpty, "Subcommand.delete should not exist — append-only invariant")

  test("Subcommand.rewrite does not compile (append-only invariant)"):
    val err: String = compileErrors("Subcommand.rewrite")
    assert(err.nonEmpty, "Subcommand.rewrite should not exist — append-only invariant")

  test("Subcommand.edit does not compile (append-only invariant)"):
    val err: String = compileErrors("Subcommand.edit")
    assert(err.nonEmpty, "Subcommand.edit should not exist — append-only invariant")

  // ── No exit code outside {0,1,2} — the enum has exactly three cases
  test("ExitCode has no fourth case"):
    val err: String = compileErrors("ExitCode.FourthCase")
    assert(err.nonEmpty, "ExitCode.FourthCase should not exist — exactly three cases")

  // ── Outcome has no fourth case (exit-code mapping totality)
  test("Outcome has no fourth case (exit-code mapping totality)"):
    val err: String = compileErrors("Outcome.FourthCase[Int]()")
    assert(err.nonEmpty, "Outcome.FourthCase should not exist — exactly three cases")

  // ── CliError is an enum — no anonymous subclass possible, and every
  // case carries the offending token by construction.
  test("CliError cannot be anonymously instantiated (enum, not sealed trait)"):
    val err: String = compileErrors("new CliError {}")
    assert(err.nonEmpty, "CliError is an enum — anonymous instantiation should not compile")
