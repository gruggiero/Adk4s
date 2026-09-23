package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

/**
 * CLI surface snapshot test — enumerates subcommands, asserts 1:1 with the
 * predecessor script set, and asserts no mutation subcommands exist.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: One subcommand per predecessor script
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Append-only ledger surface — no mutation subcommands
 */
final class CliSurfaceSpec extends ProbatioCliSuite:

  // The exposed tool set — only tools that perform their described work.
  // Five unported tools remain removed (registry-check, scan, removal-audit,
  // impact-scan, concept-scanner); `graph` is exposed — graph-tool-port
  // supplies its implementation.
  private val exposedToolSet: Set[String] = Set(
    "gate",
    "spec-lint",
    "chain-state",
    "ledger",
    "checkpoint",
    "reconcile",
    "danger-scan",
    "metals",
    "install-skills",
    "install-hooks",
    "graph"
  )

  // ── Scenario: Every exposed tool has a corresponding subcommand
  // spec: cli-entrypoint-contract — Requirement: A tool that has no implementation is not nameable on the tool surface
  test("every exposed tool has a corresponding subcommand"):
    val subcommandNames: Set[String] = Subcommand.values.map(Subcommand.cliName).toSet
    assertEquals(subcommandNames, exposedToolSet)

  // ── Scenario: the subcommand count is exactly 11
  test("the subcommand count is exactly 11 (ported tools only)"):
    assertEquals(Subcommand.values.length, 11)

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
  // spec 7 (ledger-checkpoint-parity): the approved typed contract
  // extends the action set to the predecessor's full op surface —
  // validate renamed to verify, run added. Still no mutation action.
  test("ledger subcommand exposes only Append, Run, Read, Verify — no mutation action"):
    val actions: Set[String] = LedgerCmd.Action.values.map(_.toString).toSet
    assertEquals(actions, Set("Append", "Run", "Read", "Verify"))
    assert(!actions.contains("Update"))
    assert(!actions.contains("Delete"))
    assert(!actions.contains("Rewrite"))
    assert(!actions.contains("Edit"))

  // ── Scenario: metals exposes only the start sub-action (stop/call removed)
  // spec: cli-entrypoint-contract — Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
  test("metals exposes only the Start sub-action (stop and call removed)"):
    val subActions: Set[String] = MetalsCmd.SubAction.values.map(_.toString).toSet
    assertEquals(subActions, Set("Start"))

  // ── Scenario: each subcommand name round-trips through cliName + fromString
  test("each subcommand name round-trips through cliName + fromString"):
    Subcommand.values.foreach { sub =>
      val name: String = Subcommand.cliName(sub)
      Subcommand.fromString(name) match
        case Right(parsed) => assertEquals(parsed, sub)
        case Left(err)     => fail(s"round-trip failed for $name: $err")
    }

  // ── graph-tool-port — Step 2 oracle ─────────────────────────────────
  // GraphCmd.run is ??? until Step 3 — these dispatch tests are RED at
  // polarity by design.

  // spec: graph-tool-port — Scenario: Happy path — each of the five operations dispatches
  test("graph: each of the five operations dispatches"):
    val ops: List[Array[String]] = List(
      Array("export"),
      Array("stats"),
      Array("impact", "concept:Agent"),
      Array("obligations"),
      Array("concept-code", "Agent")
    )
    ops.foreach { (opArgs: Array[String]) =>
      GraphCmd.run(opArgs) match
        case Outcome.Ran(_)          => () // dispatched and ran
        case Outcome.Finding(_)      => () // a finding is a ran operation's verdict
        case Outcome.Undetermined(r) =>
          fail(s"op '${opArgs.mkString(" ")}' reported could-not-determine: $r")
    }

  // spec: graph-tool-port — Scenario: Adversarial — an unimplemented operation name is rejected
  test("graph: an unimplemented operation name is rejected, naming it"):
    GraphCmd.run(Array("transmogrify")) match
      case Outcome.Ran(_) =>
        fail("an unknown operation must not terminate with the clean status")
      case Outcome.Finding(d)      => assert(d.contains("transmogrify"), s"must name the op: $d")
      case Outcome.Undetermined(r) => assert(r.contains("transmogrify"), s"must name the op: $r")
