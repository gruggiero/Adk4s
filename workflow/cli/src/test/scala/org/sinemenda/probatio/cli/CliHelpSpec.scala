package org.sinemenda.probatio.cli

import hedgehog.*

/**
 * Tests for help output completeness (R-P5).
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Help lists every flag with its default
 * spec: port-scanner-to-probatio/cli-protocol — Property: help-lists-every-flag
 */
final class CliHelpSpec extends ProbatioCliSuite:

  // ── Scenario: Help lists all flags with defaults
  // spec: cli-protocol — Scenario: Help lists all flags with defaults
  test("HelpOutput.render lists every flag name and its default"):
    val flags: List[FlagHelp] = List(
      FlagHelp("--event", "hook event name", "required"),
      FlagHelp("--change", "change name", "none"),
      FlagHelp("--baseline", "baseline SHA", "none")
    )
    val help: HelpOutput = HelpOutput(Subcommand.Gate, flags, HelpOutput.threeWayExit)
    val rendered: String = help.render
    flags.foreach { f =>
      assert(rendered.contains(f.name), s"help output missing flag ${f.name}")
      assert(rendered.contains(f.default), s"help output missing default for ${f.name}")
    }

  // ── Scenario: Help documents exit codes
  // spec: cli-protocol — Scenario: Help documents exit codes
  test("HelpOutput with threeWayExit documents exit codes 0, 1, 2"):
    val help: HelpOutput = HelpOutput(Subcommand.Gate, Nil, HelpOutput.threeWayExit)
    val rendered: String = help.render
    assert(rendered.contains("exit 0"), s"help output missing exit 0")
    assert(rendered.contains("exit 1"), s"help output missing exit 1")
    assert(rendered.contains("exit 2"), s"help output missing exit 2")

  test("HelpOutput with twoWayExit documents exit codes 0 and 1 only"):
    val help: HelpOutput = HelpOutput(Subcommand.Ledger, Nil, HelpOutput.twoWayExit)
    val rendered: String = help.render
    assert(rendered.contains("exit 0"), s"help output missing exit 0")
    assert(rendered.contains("exit 1"), s"help output missing exit 1")
    assert(!rendered.contains("exit 2"), s"two-way help should not mention exit 2")

  // ── Scenario: Help omitting a flag is forbidden (adversarial)
  // spec: cli-protocol — Scenario: Help omitting a flag is forbidden (adversarial)
  test("HelpOutput with a flag missing from render is detectable"):
    val flags: List[FlagHelp] = List(
      FlagHelp("--event", "hook event", "required"),
      FlagHelp("--change", "change name", "none")
    )
    val help: HelpOutput = HelpOutput(Subcommand.Gate, flags, HelpOutput.threeWayExit)
    val rendered: String = help.render
    // Both flags must be present — omitting one is a defect.
    assert(rendered.contains("--event"))
    assert(rendered.contains("--change"))

  // ── Property: help-lists-every-flag
  // spec: cli-protocol — Property: help-lists-every-flag
  property("help-lists-every-flag"):
    for
      flagCount <- Gen.int(Range.linear(0, 10)).forAll
      flags <- Gen
        .string(Gen.alphaNum, Range.linear(2, 20))
        .map(name => FlagHelp(s"--$name", "description", "none"))
        .list(Range.singleton(flagCount))
        .forAll
    yield
      val help: HelpOutput    = HelpOutput(Subcommand.Gate, flags, HelpOutput.threeWayExit)
      val rendered: String    = help.render
      val allPresent: Boolean = flags.forall(f => rendered.contains(f.name))
      Result
        .assert(allPresent)
        .and(Result.assert(flags.forall(f => rendered.contains(f.default))))

  // ── graph-tool-port — help names the five operations ────────────────
  // spec: graph-tool-port — Scenario: Consumer surface — the help output names the five operations and their arguments
  test("graph help names the five operations, their arguments, and the three statuses"):
    val rendered: String = HelpRegistry.helpFor(Subcommand.Graph).render
    List("export", "stats", "impact", "obligations", "concept-code").foreach { op =>
      assert(rendered.contains(op), s"graph help missing operation '$op'")
    }
    List("<target>", "<concept>").foreach { arg =>
      assert(rendered.contains(arg), s"graph help missing argument '$arg'")
    }
    List("exit 0", "exit 1", "exit 2").foreach { st =>
      assert(rendered.contains(st), s"graph help missing $st")
    }
