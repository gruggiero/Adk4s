package org.sinemenda.probatio.cli

/**
 * Tests for ProbatioMain.dispatch — the multicall entry point that resolves
 * the subcommand, delegates to the entrypoint, and maps Outcome to exit code.
 *
 * These tests exercise the entrypoint stubs (which return ???). They fail
 * RED until the entrypoints are implemented in Step 3.
 *
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 * spec: cli-protocol — Requirement: Three-way exit protocol for every subcommand
 */
final class ProbatioDispatchSpec extends ProbatioCliSuite:

  /** Helper: construct an InvocationName from a raw string. */
  private def inv(name: String): InvocationName =
    InvocationName.fromRuntime(name) match
      case Right(value) => value
      case Left(err)    => fail(s"invalid invocation name '$name': $err")

  /** Helper: construct ProgramArgs from a fixture list. */
  private def args(xs: String*): ProgramArgs =
    ProgramArgs.fromFixture(xs.toList)

  // ── Scenario: dispatch resolves subcommand from first argument under generic name
  // spec: cli-entrypoint-contract — Scenario: Named-tool invocation reaches the tool with all its arguments
  test("dispatch resolves 'gate' from first argument and returns an exit code"):
    val code: Int = ProbatioMain.dispatch(inv("probatio"), args("gate", "--event", "session-start"))
    assert(code >= 0 && code <= 2, s"exit code $code outside {0,1,2}")

  // ── Scenario: dispatch resolves subcommand from invocation name (symlink)
  // spec: cli-entrypoint-contract — Scenario: Symlink invocation reaches the tool with all its arguments
  test("dispatch resolves 'gate' from symlink invocation name and returns an exit code"):
    val code: Int = ProbatioMain.dispatch(inv("/usr/local/bin/gate"), args("--event", "session-start"))
    assert(code >= 0 && code <= 2, s"exit code $code outside {0,1,2}")

  // ── Scenario: unknown subcommand returns non-zero
  // spec: cli-entrypoint-contract — Scenario: Error path — the first argument names nothing and the invocation name names nothing
  test("dispatch with unknown subcommand 'foo' returns non-zero"):
    val code: Int = ProbatioMain.dispatch(inv("probatio"), args("foo"))
    assert(code != 0, s"unknown subcommand should return non-zero, got $code")

  // ── Scenario: mutation subcommand is rejected
  // spec: cli-protocol — Scenario: Mutation subcommand does not exist (adversarial)
  test("dispatch with mutation subcommand 'update' returns non-zero"):
    val code: Int = ProbatioMain.dispatch(inv("probatio"), args("update"))
    assert(code != 0, s"mutation subcommand should return non-zero, got $code")

  // ── Scenario: every subcommand dispatches and returns a valid exit code
  // spec: cli-protocol — Requirement: Three-way exit protocol for every subcommand
  test("every subcommand dispatches and returns an exit code in {0,1,2}"):
    Subcommand.values.foreach { sub =>
      val name: String = Subcommand.cliName(sub)
      val code: Int    = ProbatioMain.dispatch(inv("probatio"), args(name, "--help"))
      assert(
        code >= 0 && code <= 2,
        s"subcommand $name returned exit code $code, outside {0,1,2}"
      )
    }
