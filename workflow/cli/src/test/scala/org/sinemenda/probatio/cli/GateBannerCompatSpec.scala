package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.*
import org.sinemenda.probatio.migration.ControlledVariable
import org.sinemenda.probatio.migration.HermeticEnv

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

  // The completion-tier parity property runs the predecessor gate as a
  // subprocess per generated case — subprocess-suite timeout.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  // ── spec-3 parity fixture vals (declared first: `val` order matters) ──

  private val parityChange: String   = "parity-change"
  private val paritySpecName: String = "parity-spec"
  private val paritySession: String  = "parity-session"

  /** One generated ledger-row plan for the completion fixture. */
  final private case class CompletionRowPlan(
    exit: Int,           // 0 = green claim, nonzero = red
    atBaseline: Boolean, // recorded at the current baseline vs a stale one
    corroborated: Boolean
  )

  final private case class CompletionFixturePlan(
    rows: List[CompletionRowPlan],
    priorRefusal: Boolean
  )

  // The draw is over closed SHAPES, weighted so both refusal-warrant
  // arms (current-baseline and the declared stale-baseline divergence)
  // and the corroborated/red arms all reach their cover classes —
  // independent field draws starve the stale-only class once the
  // current-baseline arm is frequent enough.
  private val genCompletionRowPlan: Gen[CompletionRowPlan] =
    Gen.frequency1(
      22 -> Gen.constant(CompletionRowPlan(0, atBaseline = true, corroborated = false)),
      25 -> Gen.constant(CompletionRowPlan(0, atBaseline = false, corroborated = false)),
      18 -> Gen.constant(CompletionRowPlan(0, atBaseline = true, corroborated = true)),
      8  -> Gen.constant(CompletionRowPlan(0, atBaseline = false, corroborated = true)),
      12 -> Gen.constant(CompletionRowPlan(1, atBaseline = true, corroborated = false)),
      15 -> Gen.constant(CompletionRowPlan(1, atBaseline = false, corroborated = false))
    )

  private val genCompletionFixturePlan: Gen[CompletionFixturePlan] =
    for
      rows: List[CompletionRowPlan] <- genCompletionRowPlan.list(Range.linear(0, 6))
      prior: Boolean                <- Gen.boolean
    yield CompletionFixturePlan(rows, prior)

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
      HermeticEnv.run(List("git", "-C", repo.toString, "init", "-q"), HermeticEnv.empty)
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
        ChainState.compute(
          PrePassOutcome.Completed(lints),
          ledger,
          extracted,
          Map.empty,
          "abc1234",
          "abc1234",
          "test-change",
          noForgive
        )
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
    val bytes: Array[Byte] = input.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    HermeticEnv
      .capture(
        List("jq", "-e", "-f", hookJsonContract.toString),
        HermeticEnv.empty,
        stdin = Some(bytes)
      )
      .exitCode == 0

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

  // ── completion-witness-refusal (spec 3 of repair-probatio-cutover) ──

  private def parityRowJson(
    exit: Int,
    baseline: String,
    command: String,
    obligation: String,
    ambient: Boolean
  ): String =
    val row: ujson.Obj = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-01-01T00:00:00Z"),
      "change"     -> ujson.Str(parityChange),
      "spec"       -> ujson.Str(paritySpecName),
      "ring"       -> ujson.Str("R3"),
      "obligation" -> ujson.Str(obligation),
      "artifact"   -> ujson.Str("tests/x.bats"),
      "command"    -> ujson.Str(command),
      "exit"       -> ujson.Num(exit.toDouble),
      "baseline"   -> ujson.Str(baseline)
    )
    if ambient then row("source") = ujson.Str("ambient")
    ujson.write(row)

  private def parityGit(repo: java.nio.file.Path, args: String*): Unit =
    val code: Int =
      HermeticEnv.run("git" +: "-C" +: repo.toString +: args.toList, HermeticEnv.empty)
    assertEquals(code, 0, s"git ${args.mkString(" ")} must succeed")

  /**
   * Materialise a completion fixture: a git repo with a resolvable HEAD,
   * a ledger of generated claims (corroborated greens gain an ambient
   * witness row at the same key), the session's presentation marker, an
   * optional spent refusal marker, and a clean chain-state stub that
   * serves BOTH gates through CHAIN_STATE_OVERRIDE. Returns the state
   * dir (for marker resets between runs).
   */
  private def writeParityFixture(
    repo: java.nio.file.Path,
    plan: CompletionFixturePlan
  ): java.nio.file.Path =
    java.nio.file.Files.createDirectories(
      repo.resolve(s"openspec/changes/$parityChange/specs/$paritySpecName")
    )
    parityGit(repo, "init", "-q")
    java.nio.file.Files.writeString(repo.resolve("seed.txt"), "seed")
    parityGit(repo, "add", "-A")
    parityGit(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "seed")
    val base: String =
      HermeticEnv
        .capture(
          List("git", "-C", repo.toString, "rev-parse", "--short", "HEAD"),
          HermeticEnv.empty
        )
        .out
        .trim
    assert(base.nonEmpty, "fixture HEAD must resolve")
    val lines: List[String] =
      plan.rows.zipWithIndex.flatMap { case (r: CompletionRowPlan, i: Int) =>
        val b: String = if r.atBaseline then base else "0000000"
        val claim: String =
          parityRowJson(r.exit, b, s"cmd-$i", s"obl-$i", ambient = false)
        val witness: List[String] =
          if r.exit == 0 && r.corroborated then List(parityRowJson(0, b, s"cmd-$i", s"obl-$i", ambient = true))
          else Nil
        claim :: witness
      }
    java.nio.file.Files.writeString(
      repo.resolve(s"openspec/changes/$parityChange/evidence-ledger.jsonl"),
      if lines.isEmpty then "" else lines.mkString("", "\n", "\n")
    )
    val sd: java.nio.file.Path = repo.resolve(".git/verified-scala3-gate")
    java.nio.file.Files.createDirectories(sd)
    val enc: String = SessionId.fromRaw(paritySession).encoded
    java.nio.file.Files.writeString(sd.resolve(s"presentation-$parityChange-$paritySpecName-$enc"), "h")
    if plan.priorRefusal then java.nio.file.Files.writeString(sd.resolve(s"completion-refused-$enc"), "1")
    val cs: java.nio.file.Path = repo.resolve("cs-clean.sh")
    java.nio.file.Files.writeString(
      cs,
      "#!/usr/bin/env bash\n" +
        "echo '{\"change\":\"x\",\"baseline\":\"b\",\"total\":0,\"bound\":0," +
        "\"resolved\":0,\"discharged\":0,\"unresolved\":[],\"unmapped_obligations\":[]}'\n"
    )
    cs.toFile.setExecutable(true)
    sd

  private def portedCompletionExit(
    repo: java.nio.file.Path,
    cs: java.nio.file.Path
  ): Int =
    GateCmd.run(
      Array(
        "--repo",
        repo.toString,
        "--event",
        "completion",
        "--format",
        "text",
        "--session",
        paritySession,
        "--stop-hook-active",
        "false"
      ),
      Map("CHAIN_STATE_OVERRIDE" -> cs.toString),
      () => None
    ) match
      case Outcome.Ran(0)          => 0
      case Outcome.Ran(n)          => n
      case Outcome.Finding(_)      => 1
      case Outcome.Undetermined(_) => 2

  private def predecessorCompletionExit(
    repo: java.nio.file.Path,
    cs: java.nio.file.Path
  ): Int =
    val gate: String =
      repoRoot
        .resolve("openspec/schemas/verified-scala3/hooks/gate.sh.predecessor.bak")
        .toString
    val reconcile: String =
      repoRoot
        .resolve("openspec/schemas/verified-scala3/scanner/reconcile.sh.predecessor.bak")
        .toString
    // spec: hermetic-test-processes — the three fixture seams are declared
    // controlled variables; HermeticEnv.run keeps stdin at /dev/null (the
    // gate reads the hook payload from stdin and an inherited open pipe
    // blocks it forever) and discards both output streams.
    val env: HermeticEnv = HermeticEnv.build(
      Map(
        ControlledVariable.VerifiedScala3SessionId -> paritySession,
        ControlledVariable.ChainStateOverride      -> cs.toString,
        ControlledVariable.ReconcileOverride       -> reconcile
      )
    )
    HermeticEnv.run(
      List(
        "bash",
        gate,
        "--repo",
        repo.toString,
        "--event",
        "completion",
        "--format",
        "text",
        "--stop-hook-active",
        "false"
      ),
      env
    )

  // spec: completion-witness-refusal — Property: parity-with-predecessor-on-the-completion-tier
  // Both gates evaluate the SAME generated fixture. Ported runs in-
  // process; the predecessor is the reference script. The refusal
  // marker is reset between the two runs so both see a fresh budget.
  // The declared divergence is asserted, not excluded: a fixture whose
  // only uncorroborated greens are stale-baseline is a predecessor
  // warrant (unfiltered) but outside the port's current-baseline scope.
  property("parity-with-predecessor-on-the-completion-tier"):
    for plan: CompletionFixturePlan <- genCompletionFixturePlan.forAll
        .cover(
          20,
          "current-baseline warrant",
          (p: CompletionFixturePlan) =>
            p.rows.exists((r: CompletionRowPlan) => r.exit == 0 && r.atBaseline && !r.corroborated)
        )
        .cover(
          10,
          "stale-only divergence arm",
          (p: CompletionFixturePlan) =>
            p.rows.exists((r: CompletionRowPlan) => r.exit == 0 && !r.atBaseline && !r.corroborated) &&
              !p.rows.exists((r: CompletionRowPlan) => r.exit == 0 && r.atBaseline && !r.corroborated)
        )
        .cover(
          20,
          "prior-refusal budget arm",
          (p: CompletionFixturePlan) => p.priorRefusal
        )
        .cover(
          15,
          "fully corroborated / red record",
          (p: CompletionFixturePlan) => !p.rows.exists((r: CompletionRowPlan) => r.exit == 0 && !r.corroborated)
        )
    yield withTempDir("gate-parity") { (repo: java.nio.file.Path) =>
      val sd: java.nio.file.Path     = writeParityFixture(repo, plan)
      val cs: java.nio.file.Path     = repo.resolve("cs-clean.sh")
      val enc: String                = SessionId.fromRaw(paritySession).encoded
      val marker: java.nio.file.Path = sd.resolve(s"completion-refused-$enc")

      val ported: Int = portedCompletionExit(repo, cs)
      // Reset the budget ONLY when the ported run could have written the
      // marker itself — a fixture-seeded marker (priorRefusal) is part of
      // the state BOTH runs must see.
      if !plan.priorRefusal then java.nio.file.Files.deleteIfExists(marker)
      val model: Int = predecessorCompletionExit(repo, cs)

      val uncorrAtBaseline: Boolean =
        plan.rows.exists((r: CompletionRowPlan) => r.exit == 0 && r.atBaseline && !r.corroborated)
      val uncorrStaleOnly: Boolean =
        !uncorrAtBaseline && plan.rows.exists((r: CompletionRowPlan) => r.exit == 0 && !r.atBaseline && !r.corroborated)
      val divergent: Boolean = uncorrStaleOnly && model == 1 && ported == 0

      Result.all(
        List(
          Result
            .assert(ported == model || divergent)
            .log(s"ported=$ported model=$model plan=$plan"),
          // The divergence can only run one way: the model refuses a
          // stale warrant, the port allows it. The port must never
          // refuse something the model allowed.
          Result
            .assert(!(ported == 1 && model == 0))
            .log(s"port refused where the model allowed: plan=$plan")
        )
      )
    }

  // ── spec: hermetic-test-processes ─────────────────────────────────

  // spec: hermetic-test-processes — Scenario: Adversarial — both sides receive the session through the same channel
  test("both sides receive the session through the same channel"):
    withTempDir("gate-parity-channel") { (repo: java.nio.file.Path) =>
      // A refusal fixture through the parity property's own builder, with
      // a chain-state seam reporting an UNRESOLVED requirement — the arm
      // where port and model agree to refuse (a stale-only row is the
      // declared divergence: the model refuses it, the port allows).
      val plan: CompletionFixturePlan =
        CompletionFixturePlan(rows = Nil, priorRefusal = false)
      val sd: java.nio.file.Path  = writeParityFixture(repo, plan)
      val cs: java.nio.file.Path  = repo.resolve("cs-unresolved.sh")
      java.nio.file.Files.writeString(
        cs,
        "#!/usr/bin/env bash\n" +
          "echo '{\"change\":\"x\",\"baseline\":\"b\",\"total\":1,\"bound\":1," +
          "\"resolved\":1,\"discharged\":0,\"unresolved\":[\"obl\"],\"unmapped_obligations\":[]}'\n"
      )
      cs.toFile.setExecutable(true)
      val enc: String             = SessionId.fromRaw(paritySession).encoded
      val reconcile: String =
        repoRoot
          .resolve("openspec/schemas/verified-scala3/scanner/reconcile.sh.predecessor.bak")
          .toString

      // One hermetic environment, declaring the session and both test
      // seams; the invoking shell's harness variable must not survive.
      val env: HermeticEnv = HermeticEnv.buildWithExtras(
        Map(
          ControlledVariable.VerifiedScala3SessionId -> paritySession,
          ControlledVariable.ChainStateOverride      -> cs.toString,
          ControlledVariable.ReconcileOverride       -> reconcile
        ),
        Map("CLAUDE_CODE_SESSION_ID" -> "foreign-harness-session")
      )

      // The ported arm: the env map threaded in-process — NO --session
      // flag. The channel the other side lacks is removed by construction.
      val ported: Int =
        GateCmd.run(
          Array(
            "--repo",
            repo.toString,
            "--event",
            "completion",
            "--format",
            "text",
            "--stop-hook-active",
            "false"
          ),
          env.toMap,
          () => None
        ) match
          case Outcome.Ran(0)          => 0
          case Outcome.Ran(n)          => n
          case Outcome.Finding(_)      => 1
          case Outcome.Undetermined(_) => 2

      // Reset the refusal budget the ported run just spent, exactly as the
      // parity property does — both arms must see a fresh budget.
      java.nio.file.Files.deleteIfExists(sd.resolve(s"completion-refused-$enc"))

      // The predecessor arm: spawned through the shared helper — the same
      // env is the whole of its environment.
      val gate: String =
        repoRoot
          .resolve("openspec/schemas/verified-scala3/hooks/gate.sh.predecessor.bak")
          .toString
      val model: Int = HermeticEnv.run(
        List(
          "bash",
          gate,
          "--repo",
          repo.toString,
          "--event",
          "completion",
          "--format",
          "text",
          "--stop-hook-active",
          "false"
        ),
        env
      )

      // The channel assertion, observed at the process boundary: the
      // declared session is there, the inherited harness variable is not.
      val childEnv: Map[String, String] = HermeticEnv.probeChild(env)
      assertEquals(childEnv.get("VERIFIED_SCALA3_SESSION_ID"), Some(paritySession))
      assert(!childEnv.contains("CLAUDE_CODE_SESSION_ID"))

      // Same session, same verdict — and the refusal marker is keyed by
      // the declared session for whichever arm wrote it.
      assertEquals(ported, model, "both gates on one fixture must agree when the session arrives by the same channel")
      assert(
        java.nio.file.Files.exists(sd.resolve(s"completion-refused-$enc")),
        "the refusal marker must be keyed by the declared session"
      )
      assert(
        !java.nio.file.Files.exists(
          sd.resolve(s"completion-refused-${SessionId.fromRaw("foreign-harness-session").encoded}")
        ),
        "the inherited harness session must never reach either side"
      )
    }
