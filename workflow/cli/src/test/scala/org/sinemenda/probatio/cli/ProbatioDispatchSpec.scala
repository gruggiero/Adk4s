package org.sinemenda.probatio.cli

/**
 * Tests for ProbatioMain.dispatch — the multicall entry point that resolves
 * the subcommand, delegates to the entrypoint, and maps Outcome to exit code.
 *
 * These tests exercise the entrypoint stubs (which return ???). They fail
 * RED until the entrypoints are implemented in Step 3.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Three-way exit protocol for every subcommand
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Multicall dispatch by argv(1) and argv(0)
 */
final class ProbatioDispatchSpec extends ProbatioCliSuite:

  // ── Scenario: dispatch resolves subcommand from argv(1) and returns exit code
  // spec: cli-protocol — Scenario: argv(1) dispatch works
  test("dispatch resolves 'gate' from argv(1) and returns an exit code"):
    val code: Int = ProbatioMain.dispatch(Array("probatio", "gate", "--event", "session-start"))
    assert(code >= 0 && code <= 2, s"exit code $code outside {0,1,2}")

  // ── Scenario: dispatch resolves subcommand from argv(0) (symlink)
  // spec: cli-protocol — Scenario: argv(0) dispatch via symlink works
  test("dispatch resolves 'gate' from argv(0) symlink and returns an exit code"):
    val code: Int = ProbatioMain.dispatch(Array("/usr/local/bin/gate", "--event", "session-start"))
    assert(code >= 0 && code <= 2, s"exit code $code outside {0,1,2}")

  // ── Scenario: unknown subcommand returns non-zero
  // spec: cli-protocol — Scenario: Unknown subcommand is rejected
  test("dispatch with unknown subcommand 'foo' returns non-zero"):
    val code: Int = ProbatioMain.dispatch(Array("probatio", "foo"))
    assert(code != 0, s"unknown subcommand should return non-zero, got $code")

  // ── Scenario: mutation subcommand is rejected
  // spec: cli-protocol — Scenario: Mutation subcommand does not exist (adversarial)
  test("dispatch with mutation subcommand 'update' returns non-zero"):
    val code: Int = ProbatioMain.dispatch(Array("probatio", "update"))
    assert(code != 0, s"mutation subcommand should return non-zero, got $code")

  // ── Scenario: every subcommand dispatches and returns a valid exit code
  // spec: cli-protocol — Requirement: Three-way exit protocol for every subcommand
  test("every subcommand dispatches and returns an exit code in {0,1,2}"):
    Subcommand.values.foreach { sub =>
      val name: String = Subcommand.cliName(sub)
      val code: Int    = ProbatioMain.dispatch(Array("probatio", name, "--help"))
      assert(
        code >= 0 && code <= 2,
        s"subcommand $name returned exit code $code, outside {0,1,2}"
      )
    }
