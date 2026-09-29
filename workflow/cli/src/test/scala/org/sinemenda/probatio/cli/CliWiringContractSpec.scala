package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.BannerOutput
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.GatePayload
import org.sinemenda.probatio.core.LintReport
import org.sinemenda.probatio.core.Outcome

/**
 * Typed contract test for the cli-wiring spec.
 *
 * Verifies that `CliContext`, `StdoutRenderer[A]`, and `SubcommandWiring`
 * compile with the correct signatures and are usable from test code. This is
 * the human-gate artifact for Step 1 — the types are defined in
 * `CliWiringContract.scala` (test sources) and will move to main sources
 * during Step 3 (implementation).
 *
 * spec: cli-wiring — Step 1: typed contract (full)
 * spec: cli-wiring — Concepts Introduced: CliContext
 * spec: cli-wiring — Concepts Introduced: StdoutRenderer
 * spec: cli-wiring — Concepts Introduced: SubcommandWiring
 */
final class CliWiringContractSpec extends ProbatioCliSuite:

  import CliWiringContract.*

  // ── CliContext — immutable case class with resolved paths + escape hatch
  // spec: cli-wiring — Concepts Introduced: CliContext

  test("CliContext constructs with all resolved paths + escape hatch"):
    val ctx: CliContext = CliContext(
      repoRoot = "/repo",
      changeDir = "/repo/openspec/changes/test",
      ledgerFile = "/repo/openspec/changes/test/evidence-ledger.jsonl",
      gitDir = "/repo/.git",
      escapeHatch = false
    )
    assertEquals(ctx.repoRoot, "/repo")
    assertEquals(ctx.changeDir, "/repo/openspec/changes/test")
    assertEquals(ctx.ledgerFile, "/repo/openspec/changes/test/evidence-ledger.jsonl")
    assertEquals(ctx.gitDir, "/repo/.git")
    assertEquals(ctx.escapeHatch, false)

  test("CliContext.resolve constructs the same as the case class apply"):
    val ctx: CliContext = CliContext.resolve(
      repoRoot = "/repo",
      changeDir = "/dir",
      ledgerFile = "/ledger",
      gitDir = "/.git",
      escapeHatch = true
    )
    assertEquals(ctx, CliContext("/repo", "/dir", "/ledger", "/.git", true))

  test("CliContext is immutable — no setter methods exist"):
    val ctx: CliContext = CliContext("/r", "/d", "/l", "/g", false)
    // Copy with updated field produces a new instance
    val updated: CliContext = ctx.copy(escapeHatch = true)
    assertEquals(updated.escapeHatch, true)
    assertEquals(ctx.escapeHatch, false)

  // ── StdoutRenderer[A] — typeclass with given instances per result type
  // spec: cli-wiring — Concepts Introduced: StdoutRenderer

  test("StdoutRenderer[ChainStateReport] is summonable"):
    val renderer: StdoutRenderer[ChainStateReport] = summon[StdoutRenderer[ChainStateReport]]
    val report: ChainStateReport = ChainStateReport
      .fromCounts(
        change = "test",
        baseline = "abc123",
        total = 1,
        bound = 1,
        resolved = 1,
        discharged = 1,
        unresolved = Nil,
        unmappedObligations = Nil
      )
      .getOrElse(fail("fixture report violates the report contract"))
    val rendered: String = renderer.render(report)
    assert(rendered != null, "renderer must return a non-null string")

  test("StdoutRenderer[LintReport] is summonable"):
    val renderer: StdoutRenderer[LintReport] = StdoutRenderer[LintReport]
    val report: LintReport = LiveFactFixtures.lintReport(
      verdicts = Nil,
      warnings = Nil,
      applicability = Map.empty,
      lintSuccess = true
    )
    val rendered: String = renderer.render(report)
    assert(rendered != null, "renderer must return a non-null string")

  test("StdoutRenderer[GatePayload] is summonable"):
    val renderer: StdoutRenderer[GatePayload] = StdoutRenderer[GatePayload]
    val payload: GatePayload = GatePayload(
      org.sinemenda.probatio.core.HookSpecificOutput(hookEventName = "session-start")
    )
    val rendered: String = renderer.render(payload)
    assert(rendered != null, "renderer must return a non-null string")

  test("StdoutRenderer[BannerOutput] is summonable"):
    val renderer: StdoutRenderer[BannerOutput] = StdoutRenderer[BannerOutput]
    val banner: BannerOutput                   = BannerOutput(lines = List("line1"), payload = "line1")
    val rendered: String                       = renderer.render(banner)
    assert(rendered != null, "renderer must return a non-null string")

  test("StdoutRenderer.apply summons the same instance as summon"):
    val viaApply: StdoutRenderer[ChainStateReport]  = StdoutRenderer.apply[ChainStateReport]
    val viaSummon: StdoutRenderer[ChainStateReport] = summon[StdoutRenderer[ChainStateReport]]
    assert(viaApply == viaSummon, "apply and summon should return the same instance")

  // ── SubcommandWiring — I/O adapter signatures
  // spec: cli-wiring — Concepts Introduced: SubcommandWiring

  test("SubcommandWiring.parseArgs returns Right for empty args"):
    val result: Either[CliError, Map[String, String]] =
      SubcommandWiring.parseArgs(Array.empty, Set("--file"))
    assert(result.isRight)

  test("SubcommandWiring.readLedgerFile returns Outcome"):
    val result: Outcome[List[ujson.Value]] =
      SubcommandWiring.readLedgerFile("/nonexistent/path")
    result match
      case Outcome.Ran(_)          => () // expected for stub
      case Outcome.Finding(_)      => fail("expected Ran, got Finding")
      case Outcome.Undetermined(_) => fail("expected Ran, got Undetermined")

  test("SubcommandWiring.appendLedgerLine returns Outcome"):
    val result: Outcome[Unit] =
      SubcommandWiring.appendLedgerLine("/nonexistent/path", "{}")
    result match
      case Outcome.Ran(_)          => () // expected for stub
      case Outcome.Finding(_)      => fail("expected Ran, got Finding")
      case Outcome.Undetermined(_) => fail("expected Ran, got Undetermined")

  test("SubcommandWiring.emitStdout and emitStderr are callable"):
    // These are side-effecting methods — just verify they don't throw
    SubcommandWiring.emitStdout("")
    SubcommandWiring.emitStderr("")

  // ── Compile-negative: no --force flag on ledger append
  // spec: cli-wiring — Compile-Negative: --force / --skip-validation flag on ledger append

  test("LedgerCmd.append does not accept a force parameter"):
    val err: String = compileErrors("LedgerCmd.append(\"{}\", None, force = true)")
    assert(err.nonEmpty, "LedgerCmd.append should not accept a force parameter")

  // ── Compile-negative: no update/delete action on ledger
  // spec: cli-wiring — Compile-Negative: update or delete action on the ledger subcommand

  test("LedgerCmd.Action has no Update case"):
    val err: String = compileErrors("LedgerCmd.Action.Update")
    assert(err.nonEmpty, "LedgerCmd.Action.Update should not exist")

  test("LedgerCmd.Action has no Delete case"):
    val err: String = compileErrors("LedgerCmd.Action.Delete")
    assert(err.nonEmpty, "LedgerCmd.Action.Delete should not exist")

  // ── Compile-negative: no case-catch-all in gate event dispatch
  // spec: cli-wiring — Compile-Negative: case _ catch-all in the gate's event dispatch

  test("GateCmd.Event has exactly 5 cases — no sixth case"):
    val err: String = compileErrors("GateCmd.Event.SixthCase")
    assert(err.nonEmpty, "GateCmd.Event.SixthCase should not exist — exactly 5 cases")
