package org.sinemenda.probatio.cli

import hedgehog.*

/**
 * Tests for multicall dispatch by invocation name and first argument.
 *
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 * spec: cli-entrypoint-contract — Property: dispatch-equivalence-across-signals
 */
final class MulticallDispatchSpec extends ProbatioCliSuite:

  /** Helper: construct an InvocationName from a raw string. */
  private def inv(name: String): InvocationName =
    InvocationName.fromRuntime(name) match
      case Right(value) => value
      case Left(err)    => fail(s"invalid invocation name '$name': $err")

  /** Helper: construct ProgramArgs from a fixture list. */
  private def args(xs: String*): ProgramArgs =
    ProgramArgs.fromFixture(xs.toList)

  // ── Scenario: Named-tool invocation reaches the tool with all its arguments
  // spec: cli-entrypoint-contract — Scenario: Named-tool invocation reaches the tool with all its arguments
  test("generic-name dispatch resolves 'gate' and consumes the tool token"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), args("gate", "--event", "session-start"))
    assertEquals(result, Right((Subcommand.Gate, args("--event", "session-start"))))

  test("generic-name dispatch resolves 'spec-lint' and consumes the tool token"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), args("spec-lint"))
    assertEquals(result, Right((Subcommand.SpecLint, ProgramArgs.empty)))

  // ── Scenario: Symlink invocation reaches the tool with all its arguments
  // spec: cli-entrypoint-contract — Scenario: Symlink invocation reaches the tool with all its arguments
  test("symlink basename 'gate' selects Gate and passes all args unchanged"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("/usr/local/bin/gate"), args("--event", "session-start"))
    assertEquals(result, Right((Subcommand.Gate, args("--event", "session-start"))))

  test("symlink basename 'chain-state' selects ChainState and passes all args unchanged"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("/usr/local/bin/chain-state"), args("--change-dir", "d"))
    assertEquals(result, Right((Subcommand.ChainState, args("--change-dir", "d"))))

  // ── Scenario: alias 'prob' dispatches by first argument
  test("alias 'prob' dispatches by first argument 'gate'"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("/usr/local/bin/prob"), args("gate"))
    assertEquals(result, Right((Subcommand.Gate, ProgramArgs.empty)))

  // ── Scenario: Error path — the first argument names nothing and the invocation name names nothing
  // spec: cli-entrypoint-contract — Scenario: Error path — the first argument names nothing and the invocation name names nothing
  test("unknown token under generic name returns UnknownSubcommand"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), args("foo", "--x"))
    assert(result.isLeft)
    result match
      case Left(CliError.UnknownSubcommand(token)) => assertEquals(token, "foo")
      case other                                   => fail(s"expected UnknownSubcommand, got $other")

  // ── Scenario: Edge case — no arguments at all under the generic invocation name
  // spec: cli-entrypoint-contract — Scenario: Edge case — no arguments at all under the generic invocation name
  test("no args under generic name returns UnknownSubcommand"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), ProgramArgs.empty)
    assert(result.isLeft)

  // ── Scenario: Adversarial — a flag value that spells a tool name is not treated as a tool name
  // spec: cli-entrypoint-contract — Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name
  test("flag value spelling a tool name is not consumed as a tool name under symlink"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("chain-state"), args("--change", "gate", "--baseline", "abc1234"))
    assertEquals(result, Right((Subcommand.ChainState, args("--change", "gate", "--baseline", "abc1234"))))

  // ── Property: dispatch-equivalence-across-signals
  // spec: cli-entrypoint-contract — Property: dispatch-equivalence-across-signals
  property("dispatch-equivalence-across-signals"):
    for sub <- Gen.element(Subcommand.Gate, Subcommand.values.toList.drop(1)).forAll
    yield
      val cliName: String = Subcommand.cliName(sub)
      // generic-name dispatch: probatio <sub> — consumes the tool token
      val byGeneric: Either[CliError, (Subcommand, ProgramArgs)] =
        MulticallDispatch.resolveAndSplit(inv("probatio"), args(cliName))
      // symlink dispatch: <sub> (no tool token to consume)
      val bySymlink: Either[CliError, (Subcommand, ProgramArgs)] =
        MulticallDispatch.resolveAndSplit(inv(s"/usr/local/bin/$cliName"), ProgramArgs.empty)
      Result
        .assert(byGeneric == Right((sub, ProgramArgs.empty)))
        .and(Result.assert(bySymlink == Right((sub, ProgramArgs.empty))))
        .and(Result.assert(byGeneric == bySymlink))
