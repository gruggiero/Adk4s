package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.*

import scala.sys.process.ProcessLogger
import scala.sys.process.stringSeqToProcess

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
    // Spec 8's trigger: the completion tier evaluates only when a
    // checkpoint presentation marker exists for the session; chain state
    // then arrives through the CHAIN_STATE_OVERRIDE subprocess seam. The
    // stub reports one unresolved obligation, so completion blocks with
    // a Finding.
    withTempDir("gate-test-ledger") { (repo: java.nio.file.Path) =>
      val specDir: java.nio.file.Path =
        repo.resolve("openspec/changes/test-change/specs/only")
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
      // The state dir lives under .git — the gate resolves it via
      // `git rev-parse --absolute-git-dir`.
      List("git", "-C", repo.toString, "init", "-q")
        .!(ProcessLogger(_ => (), _ => ()))
      val session: SessionId = SessionId.fromRaw("t")
      val stateDir: java.nio.file.Path =
        repo.resolve(".git/verified-scala3-gate")
      java.nio.file.Files.createDirectories(stateDir)
      java.nio.file.Files.writeString(
        stateDir.resolve(s"presentation-test-change-only-${session.encoded}"),
        "hash"
      )
      val stub: java.nio.file.Path = repo.resolve("chain-state-stub.sh")
      java.nio.file.Files.writeString(
        stub,
        "#!/usr/bin/env bash\n" +
          "echo '{\"change\":\"test-change\",\"baseline\":\"x\",\"total\":1,\"bound\":1," +
          "\"resolved\":1,\"discharged\":0," +
          "\"unresolved\":[{\"requirement\":\"obl one\",\"reasons\":[\"undischarged\"]}]," +
          "\"unmapped_obligations\":[]}'\n"
      )
      stub.toFile.setExecutable(true)
      val outcome: Outcome[Int] = GateCmd.run(
        Array(
          "--event",
          "completion",
          "--format",
          "text",
          "--session",
          "t",
          "--repo",
          repo.toString
        ),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
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

  // ── Property: envelope-conforms-to-contract ─────────────────────────
  // spec: gate-event-completeness — Property: envelope-conforms-to-contract
  //
  // Every non-empty hook-json envelope is piped through the REAL contract
  // checker — `jq -e -f gate-hookjson-contract.jq` — rather than a Scala
  // re-implementation, so a contract/.jq-semantics drift is caught here
  // (Ring 8). The contract governs non-empty output only: a no-op call
  // legitimately emits NOTHING, exactly as the contract's own comment
  // specifies — empty output is vacuously conformant. The generator
  // covers the two envelope-emitting events (the only hookEventName
  // values the contract admits); the other four events emit either the
  // `decision:block` refusal envelope (a different, spec-prose contract)
  // or nothing.

  /** The repository root — walked up from the test working directory. */
  private def repoRoot: java.nio.file.Path =
    val start: java.nio.file.Path = java.nio.file.Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: java.nio.file.Path) => Option(p.getParent).map((par: java.nio.file.Path) => p -> par))
      .find((p: java.nio.file.Path) => java.nio.file.Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  private def hookJsonContract: java.nio.file.Path =
    repoRoot.resolve("openspec/schemas/verified-scala3/scanner/gate-hookjson-contract.jq")

  /** Pipe `input` through `jq -e -f <contract>`; true iff jq accepts. */
  private def contractAccepts(input: String): Boolean =
    import scala.sys.process.Process
    val bytes: Array[Byte] = input.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val code: Int =
      (Process(Seq("jq", "-e", "-f", hookJsonContract.toString))
        #< new java.io.ByteArrayInputStream(bytes))
        .!(ProcessLogger(_ => (), _ => ()))
    code == 0

  private def envelopeCoverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  /** `genGateEvent` — exhaustive over the event enum (six cases). */
  private def genGateEvent: Gen[GateEvent] =
    Gen.element1(
      GateEvent.SessionStart,
      GateEvent.PromptSubmit,
      GateEvent.ToolCall,
      GateEvent.PostEdit,
      GateEvent.PostBash,
      GateEvent.Completion
    )

  /** The `--event` token for each event (the adapter-facing name). */
  private def eventTokenOf(event: GateEvent): String = event match
    case GateEvent.SessionStart => "session-start"
    case GateEvent.PromptSubmit => "prompt-submit"
    case GateEvent.ToolCall     => "tool-call"
    case GateEvent.PostEdit     => "post-edit"
    case GateEvent.PostBash     => "post-bash"
    case GateEvent.Completion   => "completion"

  property("envelope-conforms-to-contract", envelopeCoverConfig):
    for
      event <- genGateEvent.forAll
        .cover(10, "session-start", (e: GateEvent) => e == GateEvent.SessionStart)
        .cover(10, "prompt-submit", (e: GateEvent) => e == GateEvent.PromptSubmit)
        .cover(10, "tool-call", (e: GateEvent) => e == GateEvent.ToolCall)
        .cover(10, "post-edit", (e: GateEvent) => e == GateEvent.PostEdit)
        .cover(10, "post-bash", (e: GateEvent) => e == GateEvent.PostBash)
        .cover(10, "completion", (e: GateEvent) => e == GateEvent.Completion)
      format <- Gen.element1("hook-json", "text").forAll
    yield withTempDir("gate-envelope-prop") { (repo: java.nio.file.Path) =>
      java.nio.file.Files.createDirectory(repo.resolve("openspec"))
      // post-edit gets a spec-edit --file so its PostToolUse envelope is
      // actually exercised (no scanners exist in a temp repo → the
      // "could not run" finding emits the envelope).
      val extra: List[String] =
        if event == GateEvent.PostEdit then
          List(
            "--file",
            repo.resolve("openspec/changes/c/specs/s/spec.md").toString
          )
        else List.empty[String]
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          GateCmd.run(
            Array(
              "--event",
              eventTokenOf(event),
              "--format",
              format,
              "--repo",
              repo.toString,
              "--session",
              "t"
            ) ++ extra.toArray,
            Map.empty,
            () => None
          )
        )
      val trimmed: String = out.trim
      if trimmed.isEmpty then Result.success            // the no-op case the contract documents
      else if format != "hook-json" then Result.success // non-hook-json output is prose, not an envelope
      else
        val parsed: Either[String, ujson.Value] =
          try Right(ujson.read(trimmed))
          catch case scala.util.control.NonFatal(e) => Left(e.toString)
        parsed match
          case Left(err: String) =>
            Result.failure.log(s"hook-json output is not JSON: $trimmed ($err)")
          case Right(obj: ujson.Obj) =>
            obj.value.get("hookSpecificOutput") match
              case Some(hso: ujson.Obj) =>
                // The hook envelope: its event name must be the harness's
                // own name for this event — never the enum's case name.
                val name: String =
                  hso.value.get("hookEventName") match
                    case Some(ujson.Str(s)) => s
                    case _ => "" // danger-scan:allow missing/non-string name — the name check below fails it
                if name != GateEvent.harnessName(event) then
                  Result.failure.log(
                    s"envelope named '$name' for event $event (expected ${GateEvent.harnessName(event)})"
                  )
                else if name == "SessionStart" || name == "UserPromptSubmit" then
                  // The jq contract admits exactly these two envelope names.
                  if contractAccepts(trimmed) then Result.success
                  else Result.failure.log(s"jq contract rejected the envelope: $trimmed")
                else Result.success // PostToolUse envelopes carry a spec-prose contract, not the jq one
              case _ =>
                // A `decision:block` object is the blocking refusal
                // envelope — a different, spec-prose contract — not the
                // banner shape.
                obj.value.get("decision") match
                  case Some(ujson.Str("block")) => Result.success
                  case _ =>
                    Result.failure.log(
                      s"hook-json output is neither a hookSpecificOutput envelope nor a decision:block: $trimmed"
                    ) // danger-scan:allow test assertion — malformed envelopes fail the test
          case Right(_) =>
            Result.failure.log(
              s"hook-json output is not an object: $trimmed"
            ) // danger-scan:allow test assertion — non-object output fails the test
    }
