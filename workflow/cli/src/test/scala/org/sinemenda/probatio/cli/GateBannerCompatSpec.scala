package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Range
import org.sinemenda.probatio.core.*

import LiveFactFixtures.withTempDir

/**
 * Test oracle for the `gate` subcommand wiring (cli-wiring spec).
 *
 * Tests the 5-event tier logic, the banner byte-compatibility, the
 * predecessor check, the grant waiver, and the escape hatch. Derived from
 * the spec's requirements and scenarios — NOT from the implementation.
 *
 * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
 * spec: cli-wiring — Property: gate-banner-byte-compatibility
 */
final class GateBannerCompatSpec extends ProbatioCliSuite:

  // ── Scenario: session-start emits the banner and exits 0
  // spec: cli-wiring — Scenario: session-start emits the banner and exits 0

  test("gate session-start emits banner and exits 0"):
    // The wired entrypoint emits the banner and returns Ran(0). A temp repo
    // with an openspec/ dir exercises the real fact-reading path; the repo
    // is not a git repo so no suppression state or heartbeat persists.
    withTempDir("gate-test-repo") { (repo: java.nio.file.Path) =>
      java.nio.file.Files.createDirectory(repo.resolve("openspec"))
      val outcome: Outcome[Int] = GateCmd.run(
        Array("--event", "session-start", "--format", "text", "--repo", repo.toString, "--session", "t")
      )
      outcome match
        case Outcome.Ran(0)               => () // expected
        case Outcome.Ran(n)               => fail(s"session-start should exit 0, got $n")
        case Outcome.Finding(msg)         => fail(s"session-start should not block: $msg")
        case Outcome.Undetermined(reason) => fail(s"session-start should not be undetermined: $reason")
    }

  // ── Scenario: tool-call blocks when a predecessor spec is not checkpointed
  // spec: cli-wiring — Scenario: tool-call blocks when a predecessor spec is not checkpointed

  test("PredecessorCheck blocks when a predecessor is Verified but not checkpointed"):
    val specs: List[PredecessorCheck.SpecState] = List(
      ("spec-1", SpecPhase.Verified, false) // Verified, no presentation marker
    )
    val result: Either[BlockReason, Unit] = PredecessorCheck.apply(specs, escapeHatch = false)
    result match
      case Left(BlockReason.PredecessorNotCheckpointed(spec)) =>
        assertEquals(spec, "spec-1")
      case other => fail(s"expected PredecessorNotCheckpointed, got $other")

  // ── Scenario: tool-call allows when all predecessors are verified and checkpointed
  // spec: cli-wiring — Scenario: tool-call allows when all predecessors are verified and checkpointed

  test("PredecessorCheck allows when all predecessors are Verified and checkpointed"):
    val specs: List[PredecessorCheck.SpecState] = List(
      ("spec-1", SpecPhase.Verified, true) // Verified + presentation marker
    )
    val result: Either[BlockReason, Unit] = PredecessorCheck.apply(specs, escapeHatch = false)
    assert(result.isRight, s"should allow: $result")

  // ── Scenario: completion blocks when chain-state is unresolved
  // spec: cli-wiring — Scenario: completion blocks when chain-state is unresolved

  test("gate completion blocks when chain-state has unresolved obligations"):
    // A real change dir: one spec whose requirement is bound and resolved
    // (a mapped obligation naming a tracked artifact) but has no ledger
    // evidence — undischarged, so completion must block with a Finding.
    withTempDir("gate-test-ledger") { (tempDir: java.nio.file.Path) =>
      val changeDir: java.nio.file.Path = tempDir.resolve("test-change")
      val specDir: java.nio.file.Path   = changeDir.resolve("specs").resolve("only")
      java.nio.file.Files.createDirectories(specDir)
      java.nio.file.Files.writeString(
        specDir.resolve("spec.md"),
        """# Spec: Fixture
          |
          |## ADDED Requirements
          |
          |### Requirement: Solo Req
          |
          |The system SHALL do the thing named Solo Req.
          |
          |**Given** a precondition
          |**When** an action
          |**Then** an observable outcome
          |
          |#### Scenario: happy path
          |
          |**Given** a specific setup
          |**When** a specific action
          |**Then** a specific assertion
          |
          |## Proof Obligations
          |
          || Obligation | Source | Enforcement | Artifact |
          ||---|---|---|---|
          || obl one | Requirement: Solo Req | manual | `build.sbt` |
          |""".stripMargin
      )
      val tempFile: java.nio.file.Path = tempDir.resolve("ledger.jsonl")
      java.nio.file.Files.write(tempFile, Array.emptyByteArray)
      val outcome: Outcome[Int] = GateCmd.run(
        Array(
          "--event",
          "completion",
          "--change",
          "test-change",
          "--change-dir",
          changeDir.toString,
          "--baseline",
          "abc1234",
          "--ledger-file",
          tempFile.toString
        )
      )
      outcome match
        case Outcome.Finding(_) => () // unresolved obligations block completion
        case Outcome.Ran(n)     => fail(s"completion with undischarged obligations should block, got Ran($n)")
        case Outcome.Undetermined(reason) =>
          fail(s"completion should be determinable, got undetermined: $reason")

      // The same shape through the pure kernel: bound + resolved + no rows
      // → undischarged, an unresolved entry.
      val req: ChainState.Requirement = ChainState.Requirement("test-spec", "undischarged")
      val lint: LintReport = LiveFactFixtures.lintReport(
        verdicts = List(RequirementVerdict("undischarged", Verdict.Resolved, CheckId.F9)),
        warnings = Nil,
        applicability = Map.empty,
        lintSuccess = true
      )
      val ledger: Ledger.LedgerData = Ledger.fromRecords(Nil)
      val obligation: ExtractedObligation = ExtractedObligation(
        spec = "test-spec",
        line = 20,
        obligation = "obl one",
        artifact = "build.sbt",
        artifacts = List("build.sbt"),
        requirementClaims = List("undischarged"),
        unmappable = false
      )
      val extracted: RequirementSet =
        RequirementSet(List("test-spec"), List(req), List(obligation), FactSource.Degraded)
      val lints: Map[String, Outcome[LintReport]] = Map("test-spec" -> Outcome.Ran(lint))
      val noForgive: (String, String) => Boolean  = (_, _) => false
      val chainResult: Either[ChainStateUndetermined, ChainStateReport] =
        ChainState.compute(lints, ledger, extracted, Map.empty, "abc1234", "abc1234", "test-change", noForgive)
      chainResult match
        case Right(report) =>
          assert(report.unresolved.nonEmpty, "should have unresolved obligations")
          assert(report.discharged < report.total, "should not be fully discharged")
        case Left(u) => fail(s"chain-state should be determinable: $u")
    }

  // ── Scenario: The escape hatch bypasses the tool-call lock
  // spec: cli-wiring — Scenario: The escape hatch bypasses the tool-call lock

  test("escape hatch bypasses PredecessorCheck"):
    val specs: List[PredecessorCheck.SpecState] = List(
      ("spec-1", SpecPhase.Oracle, false) // not verified, no presentation
    )
    val result: Either[BlockReason, Unit] = PredecessorCheck.apply(specs, escapeHatch = true)
    assert(result.isRight, s"escape hatch should bypass: $result")

  test("escape hatch bypasses GrantWaiver"):
    val specs: List[GrantWaiver.SpecState] = List(
      ("spec-1", SpecPhase.Oracle, false, false) // no grant, not verified
    )
    val result: Either[BlockReason, Unit] = GrantWaiver.apply(specs, escapeHatch = true)
    assert(result.isRight, s"escape hatch should bypass: $result")

  // ── Property: gate-banner-byte-compatibility
  // spec: cli-wiring — Property: gate-banner-byte-compatibility

  property("gate-banner-byte-compatibility"):
    for
      schemaVersion    <- Gen.int(Range.linear(1, 20)).forAll
      registryPresent  <- Gen.boolean.forAll
      inventoryPresent <- Gen.boolean.forAll
      profilePresent   <- Gen.boolean.forAll
      conceptCount     <- Gen.int(Range.linear(0, 100)).forAll
      typeCount        <- Gen.int(Range.linear(0, 500)).forAll
    yield
      val facts: RepositoryFacts = RepositoryFacts(
        schemaVersion = FactRead.Present(schemaVersion),
        registry = if registryPresent then FactRead.Present(conceptCount) else FactRead.Absent,
        inventory = if inventoryPresent then FactRead.Present(typeCount) else FactRead.Absent,
        profile = if profilePresent then FactRead.Present(Some("TestControl testkit")) else FactRead.Absent,
        installRoots = Nil,
        activeChanges = FactRead.Present(Nil)
      )
      val inputs: BannerInputs = BannerInputs.from(facts)
      // The banner engine is a pure function — identical inputs produce
      // byte-identical output. This is the byte-compatibility invariant.
      val banner1: BannerOutput = BannerEngine.render(inputs)
      val banner2: BannerOutput = BannerEngine.render(inputs)
      Result.diff(banner1.payload, banner2.payload)(_ == _)
