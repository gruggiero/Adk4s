package org.sinemenda.probatio.cli

import hedgehog.*

/**
 * Tests for multicall dispatch by argv(1) and argv(0).
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Multicall dispatch by argv(1) and argv(0)
 * spec: port-scanner-to-probatio/cli-protocol — Property: multicall-dispatch-equivalence
 */
final class MulticallDispatchSpec extends ProbatioCliSuite:

  // ── Scenario: argv(1) dispatch works
  // spec: cli-protocol — Scenario: argv(1) dispatch works
  test("argv(1) dispatch resolves 'gate' subcommand"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("/usr/local/bin/probatio", Some("gate"))
    assertEquals(result, Right(Subcommand.Gate))

  test("argv(1) dispatch resolves 'spec-lint' subcommand"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("probatio", Some("spec-lint"))
    assertEquals(result, Right(Subcommand.SpecLint))

  // ── Scenario: argv(0) dispatch via symlink works
  // spec: cli-protocol — Scenario: argv(0) dispatch via symlink works
  test("argv(0) dispatch via symlink basename 'gate' works"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("/usr/local/bin/gate", None)
    assertEquals(result, Right(Subcommand.Gate))

  test("argv(0) dispatch via symlink basename 'chain-state' works"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("/usr/local/bin/chain-state", None)
    assertEquals(result, Right(Subcommand.ChainState))

  // ── Scenario: argv(0) dispatch with alias works
  // spec: cli-protocol — Scenario: argv(0) dispatch with alias works
  test("argv(0)='prob' alias dispatches by argv(1)='gate'"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("/usr/local/bin/prob", Some("gate"))
    assertEquals(result, Right(Subcommand.Gate))

  // ── Scenario: Dispatch failing on one signal is forbidden (adversarial)
  // spec: cli-protocol — Scenario: Dispatch failing on one signal is forbidden (adversarial)
  test("dispatch works when only argv(0) signal is present (no argv(1))"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("/usr/local/bin/ledger", None)
    assertEquals(result, Right(Subcommand.Ledger))

  test("dispatch works when only argv(1) signal is present (argv(0)=probatio)"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("probatio", Some("danger-scan"))
    assertEquals(result, Right(Subcommand.DangerScan))

  test("unknown subcommand via argv(1) returns UnknownSubcommand"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("probatio", Some("foo"))
    assert(result.isLeft)
    result match
      case Left(CliError.UnknownSubcommand(token)) => assertEquals(token, "foo")
      case other                                   => fail(s"expected UnknownSubcommand, got $other")

  test("no argv(1) and argv(0)=probatio returns UnknownSubcommand"):
    val result: Either[CliError, Subcommand] =
      MulticallDispatch.resolve("probatio", None)
    assert(result.isLeft)

  // ── Property: multicall-dispatch-equivalence
  // spec: cli-protocol — Property: multicall-dispatch-equivalence
  property("multicall-dispatch-equivalence"):
    for sub <- Gen.element(Subcommand.Gate, Subcommand.values.toList.drop(1)).forAll
    yield
      val cliName: String = Subcommand.cliName(sub)
      // argv(1) dispatch: probatio <sub>
      val byArgv1: Either[CliError, Subcommand] =
        MulticallDispatch.resolve("/usr/local/bin/probatio", Some(cliName))
      // argv(0) dispatch: symlink <sub> (no argv(1))
      val byArgv0: Either[CliError, Subcommand] =
        MulticallDispatch.resolve(s"/usr/local/bin/$cliName", None)
      Result
        .assert(byArgv1 == Right(sub))
        .and(Result.assert(byArgv0 == Right(sub)))
        .and(Result.assert(byArgv1 == byArgv0))
