package org.sinemenda.probatio.cli

/**
 * CLI surface snapshot test — enumerates subcommands, asserts 1:1 with the
 * predecessor script set, and asserts no mutation subcommands exist.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: One subcommand per predecessor script
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Append-only ledger surface — no mutation subcommands
 */
final class CliSurfaceSpec extends ProbatioCliSuite:

  // The predecessor script set — exactly one subcommand per predecessor.
  private val predecessorSet: Set[String] = Set(
    "gate",
    "spec-lint",
    "chain-state",
    "ledger",
    "checkpoint",
    "registry-check",
    "reconcile",
    "scan",
    "removal-audit",
    "danger-scan",
    "impact-scan",
    "metals",
    "concept-scanner",
    "graph",
    "install-skills",
    "install-hooks"
  )

  // ── Scenario: Every predecessor script has a corresponding subcommand
  // spec: cli-protocol — Scenario: Every predecessor script has a corresponding subcommand
  test("every predecessor script has a corresponding subcommand"):
    val subcommandNames: Set[String] = Subcommand.values.map(Subcommand.cliName).toSet
    assertEquals(subcommandNames, predecessorSet)

  // ── Scenario: the subcommand count is exactly 16
  test("the subcommand count is exactly 16 (one per predecessor script)"):
    assertEquals(Subcommand.values.length, 16)

  // ── Scenario: Unknown subcommand is rejected
  // spec: cli-protocol — Scenario: Unknown subcommand is rejected
  test("unknown subcommand 'foo' is rejected with UnknownSubcommand"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("foo")
    assert(result.isLeft)
    result match
      case Left(CliError.UnknownSubcommand(token)) => assertEquals(token, "foo")
      case other                                   => fail(s"expected UnknownSubcommand, got $other")

  // ── Scenario: Mutation subcommand does not exist (adversarial)
  // spec: cli-protocol — Scenario: Mutation subcommand does not exist (adversarial)
  test("mutation subcommand 'update' does not exist in the enum"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("update")
    assert(result.isLeft)
    result match
      case Left(CliError.UnknownSubcommand(token)) => assertEquals(token, "update")
      case other                                   => fail(s"expected UnknownSubcommand for 'update', got $other")

  test("mutation subcommand 'delete' does not exist in the enum"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("delete")
    assert(result.isLeft)

  test("mutation subcommand 'rewrite' does not exist in the enum"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("rewrite")
    assert(result.isLeft)

  test("mutation subcommand 'edit' does not exist in the enum"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("edit")
    assert(result.isLeft)

  // ── Scenario: Ledger subcommand exposes only append
  // spec: cli-protocol — Scenario: Ledger subcommand exposes only append
  test("ledger subcommand exposes only Append, Read, Validate — no mutation action"):
    val actions: Set[String] = LedgerCmd.Action.values.map(_.toString).toSet
    assertEquals(actions, Set("Append", "Read", "Validate"))
    assert(!actions.contains("Update"))
    assert(!actions.contains("Delete"))
    assert(!actions.contains("Rewrite"))
    assert(!actions.contains("Edit"))

  // ── Scenario: metals exposes start, stop, and call sub-subcommands
  // spec: cli-protocol — Scenario: Every predecessor script has a corresponding subcommand
  test("metals exposes start, stop, and call sub-subcommands"):
    val subActions: Set[String] = MetalsCmd.SubAction.values.map(_.toString).toSet
    assertEquals(subActions, Set("Start", "Stop", "Call"))

  // ── Scenario: each subcommand name round-trips through cliName + fromString
  test("each subcommand name round-trips through cliName + fromString"):
    Subcommand.values.foreach { sub =>
      val name: String = Subcommand.cliName(sub)
      Subcommand.fromString(name) match
        case Right(parsed) => assertEquals(parsed, sub)
        case Left(err)     => fail(s"round-trip failed for $name: $err")
    }
