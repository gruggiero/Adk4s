package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.*
import org.sinemenda.probatio.migration.ControlledVariable
import org.sinemenda.probatio.migration.HermeticEnv

import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.withTempDir

/**
 * Test oracle for the gate-event-completeness spec (spec 8).
 *
 * Covers the six adapter events, the ambient post-bash evidence writer,
 * the state-backed blocking tiers, the bounded refusal budget, the escape
 * hatch, the hook envelope names, and the spec's compile-negative
 * obligations. Derived from the SPEC and the predecessor's documented
 * behavior — NOT from the implementation.
 *
 * spec: gate-event-completeness — all Requirements, all Compile-Negative Obligations
 */
final class GateEventSpec extends ProbatioCliSuite:

  // The suite drives hundreds of in-process gate invocations that each
  // spawn git/bash subprocesses — the 30s default is too tight.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  // ── fixtures ────────────────────────────────────────────────────────

  private val change: String   = "test-change"
  private val specName: String = "test-spec"

  private def gitInit(dir: Path): Unit =
    // spec: hermetic-test-processes — fixture processes go through the
    // shared helper so the child sees the fixed base only.
    val code: Int =
      HermeticEnv.run(List("git", "-C", dir.toString, "init", "-q"), HermeticEnv.empty)
    assertEquals(code, 0, "git init must succeed")
    HermeticEnv.run(List("git", "-C", dir.toString, "config", "user.email", "t@t"), HermeticEnv.empty)
    HermeticEnv.run(List("git", "-C", dir.toString, "config", "user.name", "t"), HermeticEnv.empty)

  private def gitCommitAll(dir: Path): Unit =
    HermeticEnv.run(List("git", "-C", dir.toString, "add", "-A"), HermeticEnv.empty)
    HermeticEnv.run(List("git", "-C", dir.toString, "commit", "-q", "-m", "init"), HermeticEnv.empty)

  /** A minimal workflow repo: `openspec/` + one change with one spec. */
  private def mkRepo(repo: Path, withGit: Boolean): Unit =
    Files.createDirectories(repo.resolve("openspec/changes").resolve(change).resolve("specs").resolve(specName))
    if withGit then
      gitInit(repo)
      gitCommitAll(repo)

  /** The state dir a resolved repo yields. */
  private def stateDir(repo: Path): Path =
    repo.resolve(".git").resolve("verified-scala3-gate")

  /** The ledger file in the conventional change-dir location. */
  private def ledgerFile(repo: Path): Path =
    repo.resolve("openspec/changes").resolve(change).resolve("evidence-ledger.jsonl")

  /** Parsed ledger rows (empty when the file does not exist). */
  private def ledgerRows(repo: Path): List[ujson.Value] =
    val f: Path = ledgerFile(repo)
    if !Files.isRegularFile(f) then Nil
    else
      Files
        .readAllLines(f)
        .toArray
        .toList
        .collect { case s: String if s.trim.nonEmpty => ujson.read(s.trim) }

  /** Run the gate with an explicit environment and an empty channel. */
  private def runGate(repo: Path, args: List[String], env: Map[String, String]): Outcome[Int] =
    GateCmd.run(
      ("--repo" +: repo.toString +: args).toArray,
      env,
      () => None
    )

  /** Run the gate feeding `payload` through the input channel. */
  private def runGatePayload(
    repo: Path,
    args: List[String],
    env: Map[String, String],
    payload: String
  ): Outcome[Int] =
    GateCmd.run(
      ("--repo" +: repo.toString +: args).toArray,
      env,
      () => Some(payload)
    )

  /** A post-bash harness payload with the given command and tool_response. */
  private def postBashPayload(repo: Path, command: String, response: ujson.Value): String =
    ujson.write(
      ujson.Obj(
        "tool_name"     -> ujson.Str("Bash"),
        "tool_input"    -> ujson.Obj("command" -> ujson.Str(command)),
        "tool_response" -> response,
        "cwd"           -> ujson.Str(repo.toString)
      )
    )

  // ── Scenario: post-tool observation event is handled ────────────────
  // spec: gate-event-completeness — Scenario: post-tool observation event is handled

  test("the post-bash event is accepted and exits 0"):
    withTempDir("gate-post-bash") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "post-bash", "--format", "text", "--session", "t"),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0)               => ()
        case Outcome.Ran(n)               => fail(s"post-bash should exit 0, got $n")
        case Outcome.Finding(msg)         => fail(s"post-bash must never block: $msg")
        case Outcome.Undetermined(reason) => fail(s"post-bash must not be undetermined: $reason")
    }

  // ── Scenario: an event name no adapter sends routes to the injection tier
  // spec: gate-event-compatibility — Requirement: An unrecognised event name routes to the injection tier
  // spec: gate-event-compatibility — Scenario: Adversarial — an arbitrary unrecognised name does not error
  //
  // SUPERSEDES gate-event-completeness's "still rejected" scenario:
  // spec 4 of repair-probatio-cutover restores the predecessor's
  // fallthrough — an unrecognised name runs the context-injection tier
  // and terminates clean, never with an error status.

  test("an event name no adapter sends routes to the injection tier and exits clean"):
    withTempDir("gate-bad-event") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (out: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "inject", "--format", "text"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(0)               => ()
        case Outcome.Ran(n)               => fail(s"an unrecognised event must exit clean, got Ran($n)")
        case Outcome.Finding(msg)         => fail(s"an unrecognised event must not error: $msg")
        case Outcome.Undetermined(reason) => fail(s"an unrecognised event must not be undetermined: $reason")
      assert(
        err.contains("unrecognised event 'inject'"),
        s"the diagnostic output must name the supplied event: $err"
      )
      assert(
        out.nonEmpty,
        "the injection tier ran — the banner path emits output for a repo carrying the workflow"
      )
    }

  // ── Obligation: every adapter-configured event name is handled ──────
  // spec: gate-event-completeness — Proof Obligation: adapter-configured events

  /** The repository root — walked up from the test working directory. */
  private def repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find((p: Path) => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  /**
   * Every `--event` token an installed adapter can emit, extracted from
   * the adapter configuration files themselves — `devin.hooks.v1.json`
   * and `claude.settings.json` carry `--event <token>` in shell commands;
   * the pi extension passes it as the array form `"--event", "<token>"`.
   */
  private def adapterEventTokens: Set[String] =
    val adaptersDir: Path =
      repoRoot.resolve("openspec/schemas/verified-scala3/hooks/adapters")
    val files: List[Path] = List(
      adaptersDir.resolve("devin.hooks.v1.json"),
      adaptersDir.resolve("claude.settings.json"),
      adaptersDir.resolve("pi/verified-scala3-gate.ts")
    )
    val tokenRe: scala.util.matching.Regex =
      """--event["\s,]*"?([a-z][a-z-]*)""".r
    files
      .filter(Files.isRegularFile(_))
      .flatMap { (f: Path) =>
        tokenRe
          .findAllMatchIn(Files.readString(f))
          .map((m: scala.util.matching.Regex.Match) => m.group(1))
          .toList
      }
      .toSet

  test("every adapter-configured event name is handled"):
    val tokens: Set[String] = adapterEventTokens
    assert(tokens.nonEmpty, "adapter config files yielded no --event tokens")
    withTempDir("gate-adapter-events") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      tokens.foreach { (token: String) =>
        val outcome: Outcome[Int] = runGate(
          repo,
          List("--event", token, "--format", "text"),
          Map.empty
        )
        outcome match
          case Outcome.Ran(_) => ()
          case Outcome.Finding(msg) =>
            fail(s"adapter-configured event '$token' was rejected: $msg")
          case Outcome.Undetermined(reason) =>
            fail(s"adapter-configured event '$token' undetermined: $reason")
      }
    }

  // ── Scenario: the post-tool observation event never blocks ──────────
  // spec: gate-event-completeness — Scenario: the post-tool observation event never blocks

  test("post-bash never blocks, even with a refusal-shaped payload"):
    withTempDir("gate-post-bash-nb") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = postBashPayload(
        repo,
        "sbt adk4s-core/test",
        ujson.Str("Error: This command requires approval")
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "post-bash", "--format", "text", "--session", "t"),
        Map.empty,
        payload
      )
      outcome match
        case Outcome.Ran(0)               => ()
        case Outcome.Ran(n)               => fail(s"post-bash should exit 0, got $n")
        case Outcome.Finding(msg)         => fail(s"post-bash must never block: $msg")
        case Outcome.Undetermined(reason) => fail(s"post-bash must not be undetermined: $reason")
    }

  // ── Property: post-tool-observation-never-blocks ────────────────────
  // spec: gate-event-completeness — Property: post-tool-observation-never-blocks

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  /** The spec's `genHarnessPayload` pieces: tool name × command × response. */
  final private case class PostBashInput(
    toolName: String,
    command: String,
    response: ujson.Value
  )

  private def genToolName: Gen[String] =
    Gen.frequency1(
      12 -> Gen.constant("Bash"),
      4  -> Gen.element1("Edit", "Read", "Write", "MultiEdit", "Grep", "WebFetch"),
      1  -> Gen.string(Gen.alphaNum, Range.linear(1, 10))
    )

  /** Commands the ring-shape table recognises (redirects included). */
  private def genRingCommand: Gen[String] =
    Gen.element1(
      "sbt adk4s-core/test",
      "sbt probatio-cli/test -- --excluded-tags=Slow",
      "sbt test >log 2>&1",
      "bats openspec/schemas/verified-scala3/tests/hook-tiers.bats",
      "tools/bats run",
      "openspec/schemas/verified-scala3/scanner/danger-scan.sh HEAD",
      "openspec/schemas/verified-scala3/scanner/registry-check.sh",
      "openspec/schemas/verified-scala3/scanner/spec-lint.sh --artifacts x"
    )

  private def genPipeline: Gen[String] =
    Gen.element1(
      "sbt adk4s-core/test | tail -5",
      "bats tests/x.bats | grep ok",
      "danger-scan.sh | tee out"
    )

  private def genChained: Gen[String] =
    Gen.element1(
      "sbt test && echo done",
      "bats tests/x.bats ; ls",
      "sbt test &",
      "sbt test\nbats x"
    )

  /** Non-ring commands: dedup targets, non-shapes, arbitrary strings. */
  private def genUnrelatedCommand: Gen[String] =
    Gen.choice1(
      Gen.element1(
        "ls -la",
        "git status",
        "echo hello",
        "ledger.sh run -- sbt adk4s-core/test",
        "checkpoint.sh report --change x"
      ),
      Gen.string(Gen.alphaNum, Range.linear(0, 30))
    )

  private def genCommand: Gen[String] =
    Gen.frequency1(
      38 -> genRingCommand,
      22 -> genPipeline,
      12 -> genChained,
      28 -> genUnrelatedCommand
    )

  /** `genHarnessResponse` — constructive, mirroring the spec's classes. */
  private def genHarnessResponse: Gen[ujson.Value] =
    Gen.frequency1(
      30 -> Gen.constant(ujson.Obj("output" -> ujson.Str("ok"))),
      20 -> Gen.int(Range.linear(0, 255)).map((n: Int) => ujson.Str(s"Error: Exit code $n")),
      15 -> Gen
        .element1(
          "Error: This command requires approval",
          "Error: Path does not exist: /x",
          "permission denied"
        )
        .map(ujson.Str(_)),
      10 -> Gen.constant(ujson.Obj("interrupted" -> ujson.Bool(true))),
      25 -> Gen.element1(ujson.Null, ujson.Num(1.0), ujson.Arr(ujson.Str("x")))
    )

  private def genHarnessPayload: Gen[PostBashInput] =
    for
      toolName <- genToolName
      command  <- genCommand
      response <- genHarnessResponse
    yield PostBashInput(toolName, command, response)

  property("post-tool-observation-never-blocks", coverConfig):
    for
      input <- genHarnessPayload.forAll
        .cover(15, "non-bash-tool", (i: PostBashInput) => i.toolName != "Bash")
        .cover(15, "pipeline", (i: PostBashInput) => i.command.contains("|"))
        .cover(25, "ring-shaped", (i: PostBashInput) => GateDecisions.ambientVerdict(i.command).isRight)
      withGit <- Gen.boolean.forAll
    yield withTempDir("gate-post-bash-prop") { (repo: Path) =>
      mkRepo(repo, withGit)
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "post-bash", "--format", "text", "--session", "t"),
        Map.empty,
        ujson.write(
          ujson.Obj(
            "tool_name"     -> ujson.Str(input.toolName),
            "tool_input"    -> ujson.Obj("command" -> ujson.Str(input.command)),
            "tool_response" -> input.response,
            "cwd"           -> ujson.Str(repo.toString)
          )
        )
      )
      outcome match
        case Outcome.Ran(0) => Result.success
        case _ => // danger-scan:allow test assertion — unexpected outcome fails the test
          Result.failure.log(s"post-bash blocked/errored: $outcome")
    }

  // ── Scenario: a successful response records a green row ─────────────
  // spec: gate-event-completeness — Scenario: a successful response records a green row

  test("a matching test command with exit 0 records a green row"):
    withTempDir("gate-post-bash-green") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"post-bash should exit 0, got $other")
      val rows: List[ujson.Value] = ledgerRows(repo)
      assert(rows.nonEmpty, "expected at least one ledger row")
      assertEquals(rows.lastOption.flatMap((v: ujson.Value) => v.obj.get("exit")).map(_.num.toInt), Some(0))
      assertEquals(rows.lastOption.flatMap((v: ujson.Value) => v.obj.get("ring")).map(_.str), Some("R3"))
    }

  // ── Scenario: a failing response records the reported code ──────────
  // spec: gate-event-completeness — Scenario: a failing response records the reported code

  test("a matching test command with exit 1 records the reported code"):
    withTempDir("gate-post-bash-red") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "1"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"post-bash should exit 0 (even for red rows), got $other")
      val rows: List[ujson.Value] = ledgerRows(repo)
      assert(rows.nonEmpty, "expected at least one ledger row")
      assertEquals(rows.lastOption.flatMap((v: ujson.Value) => v.obj.get("exit")).map(_.num.toInt), Some(1))
    }

  // ── Scenario: a harness refusal records nothing ─────────────────────
  // spec: gate-event-completeness — Scenario: a harness refusal records nothing

  test("a refusal tool_response records no row"):
    withTempDir("gate-post-bash-refusal") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = postBashPayload(
        repo,
        "sbt adk4s-core/test",
        ujson.Str("Error: This command requires approval")
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "post-bash", "--format", "text", "--session", "t"),
        Map.empty,
        payload
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"post-bash should exit 0, got $other")
      assertEquals(ledgerRows(repo).length, 0, "a refusal must record nothing")
    }

  // ── Scenario: an interrupted run records nothing ────────────────────
  // spec: gate-event-completeness — Scenario: an interrupted run records nothing

  test("an interrupted tool_response records no row"):
    withTempDir("gate-post-bash-int") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = postBashPayload(
        repo,
        "sbt adk4s-core/test",
        ujson.Obj("interrupted" -> ujson.Bool(true))
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "post-bash", "--format", "text", "--session", "t"),
        Map.empty,
        payload
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"post-bash should exit 0, got $other")
      assertEquals(ledgerRows(repo).length, 0, "an interrupted run must record nothing")
    }

  // ── Scenario: a command whose reported status is not the ring's records nothing
  // spec: gate-event-completeness — Scenario: a command whose reported status is not the ring's records nothing

  test("a compound command records nothing"):
    withTempDir("gate-post-bash-compound") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // `| grep` hands the exit to the last pipeline element — the reported
      // status is grep's, not the ring's.
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test | grep ok",
          "--exit",
          "0"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"post-bash should exit 0, got $other")
      assertEquals(ledgerRows(repo).length, 0, "a compound command must record nothing")
    }

  // ── Scenario: a redirected command is still recorded ────────────────
  // spec: gate-event-completeness — Scenario: a redirected command is still recorded

  test("a redirected command is still recorded"):
    withTempDir("gate-post-bash-redir") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // `>log 2>&1` does not hand the exit to another command — sbt's own
      // status is reported.
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test >log 2>&1",
          "--exit",
          "0"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"post-bash should exit 0, got $other")
      assert(ledgerRows(repo).nonEmpty, "a redirected command must still record a row")
    }

  // ── Scenario: a verified and checkpointed predecessor allows the action
  // spec: gate-event-completeness — Scenario: a verified and checkpointed predecessor allows the action

  /** A spec ordered before `specName` — the predecessor-check fixture. */
  private def priorSpec: String = "prior-spec"

  /**
   * Write an implementation-order.md ordering `priorSpec` before
   * `specName` and declaring `rel` as `specName`'s expected file — the
   * predecessor's two-section layout: a `specs/<n>/spec.md` order list
   * plus the Expected-Files ownership table (bare spec names).
   */
  private def writeImplOrder(repo: Path, rel: String): Unit =
    Files.writeString(
      repo.resolve("openspec/changes").resolve(change).resolve("implementation-order.md"),
      s"""# Order
         |
         || # | Spec |
         ||---|------|
         || 1 | specs/$priorSpec/spec.md |
         || 2 | specs/$specName/spec.md |
         |
         |## Expected Changed Production Files
         |
         || # | Spec | Expected Files |
         ||---|------|----------------|
         || 2 | $specName | `$rel` |
         |""".stripMargin
    )

  test("a verified and checkpointed predecessor allows the tool call"):
    withTempDir("gate-tool-call-allow") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val session: SessionId = SessionId.fromRaw("t")
      val sd: Path           = stateDir(repo)
      Files.createDirectories(sd)
      // prior-spec: verified AND checkpointed — the predecessor check passes.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"presentation-$change-$priorSpec-${session.encoded}"), "hash")
      // specName owns the file and is past its oracle phase.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      Files.writeString(sd.resolve(s"presentation-$change-$specName-${session.encoded}"), "hash")
      Files.writeString(sd.resolve(s"grant-$change-$specName-${session.encoded}"), "granted")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0)       => ()
        case Outcome.Finding(msg) => fail(s"verified+checkpointed should allow: $msg")
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"expected allow, got $other")
      assert(
        trace.contains(s"allow (phase verified, $change/$specName)"),
        s"missing allow-phase trace: $trace"
      )
    }

  // ── Scenario: a verified but uncheckpointed predecessor blocks ──────
  // spec: gate-event-completeness — Scenario: a verified but uncheckpointed predecessor blocks with the distinct reason

  test("a verified but uncheckpointed predecessor blocks with the distinct reason"):
    withTempDir("gate-tool-call-block") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // prior-spec: verified but NO presentation marker — never checkpointed.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      // specName owns the file and is past its oracle phase.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        // The predecessor's tool-call block exits 2 — Undetermined, not
        // Finding (exit 1 is the completion tier's refusal code).
        case Outcome.Undetermined(msg) =>
          assert(msg.contains("checkpoint"), s"expected the checkpoint reason, got: $msg")
        case Outcome.Ran(n)       => fail(s"verified-not-checkpointed should block, got Ran($n)")
        case Outcome.Finding(msg) => fail(s"tool-call block must be exit 2, got Finding: $msg")
    }

  // ── Regression: Expected-Files ownership gates only production paths ─
  // Ring-8 F1: the predecessor consults the Expected-Files ownership
  // mapping inside the production branch only. A declared non-production
  // path (build.sbt-shaped) allows unconditionally — even when a prior
  // spec is verified-but-uncheckpointed, the state that would block a
  // production edit.

  test("a declared non-production file is not gated by spec ownership"):
    withTempDir("gate-nonprod-owned") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "build.sbt")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // prior-spec verified but uncheckpointed — would block the
      // production path; a non-production path must still allow.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("build.sbt").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a declared non-production path must allow, got $other")
    }

  // ── Scenario: an unreadable state directory allows rather than refusing
  // spec: gate-event-completeness — Scenario: an unreadable state directory allows rather than refusing

  test("an unreadable state directory allows rather than refusing"):
    withTempDir("gate-tool-call-unreadable") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      // A FILE where the state directory should be — resolution fails,
      // and the tier must fail OPEN (allow), never refuse.
      Files.writeString(stateDir(repo), "not-a-directory")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Ran(0)          => ()
        case Outcome.Ran(n)          => fail(s"unreadable state must allow, got Ran($n)")
        case Outcome.Finding(msg)    => fail(s"unreadable state must allow, got: $msg")
        case Outcome.Undetermined(r) => fail(s"unreadable state must allow, got undetermined: $r")
    }

  // ── Scenario: the escape hatch bypasses both checks under either name
  // spec: gate-event-completeness — Scenario: the escape hatch bypasses both checks under either name

  test("the escape hatch bypasses the tool-call check under PROBATIO_HOOKS"):
    withTempDir("gate-escape-new") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // Would-block state: an uncheckpointed prior.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map("PROBATIO_HOOKS" -> "off")
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"PROBATIO_HOOKS=off must bypass, got $other")
    }

  test("the escape hatch bypasses the tool-call check under the legacy alias"):
    withTempDir("gate-escape-legacy") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // Would-block state: an uncheckpointed prior.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map("VERIFIED_SCALA3_HOOKS" -> "off")
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"VERIFIED_SCALA3_HOOKS=off must bypass, got $other")
    }

  // ── Scenarios: the bounded refusal budget ────────────────────────────
  // spec: gate-event-completeness — Requirement: At most one refusal is issued per turn

  /**
   * A repo whose completion tier must refuse: a checkpoint presentation
   * marker exists for the session and the stubbed chain-state reports an
   * unresolved obligation.
   */
  private def mkRefusingRepo(repo: Path, session: SessionId): Path =
    mkRepo(repo, withGit = true)
    val sd: Path = stateDir(repo)
    Files.createDirectories(sd)
    Files.writeString(
      sd.resolve(s"presentation-$change-$specName-${session.encoded}"),
      "hash"
    )
    val stub: Path = repo.resolve("chain-state-stub.sh")
    Files.writeString(
      stub,
      "#!/usr/bin/env bash\n" +
        "echo '{\"change\":\"x\",\"baseline\":\"0000000\",\"total\":1,\"bound\":1," +
        "\"resolved\":1,\"discharged\":0,\"unresolved\":[\"obl\"],\"unmapped_obligations\":[]}'\n"
    )
    stub.toFile.setExecutable(true)
    stub

  test("the first refusal in a turn is issued"):
    withTempDir("gate-completion-first") { (repo: Path) =>
      val stub: Path = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      outcome match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"completion should refuse, got $other")
    }

  test("the second refusal in the same turn is not issued"):
    withTempDir("gate-completion-second") { (repo: Path) =>
      val stub: Path               = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      val args: List[String] =
        List("--event", "completion", "--format", "text", "--session", "t")
      val first: Outcome[Int]  = runGate(repo, args, env)
      val second: Outcome[Int] = runGate(repo, args, env)
      first match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"first completion should refuse, got $other")
      second match
        case Outcome.Ran(0)       => ()
        case Outcome.Finding(msg) => fail(s"second refusal must not be issued: $msg")
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"expected allow, got $other")
    }

  test("the budget resets on a new turn"):
    withTempDir("gate-completion-reset") { (repo: Path) =>
      val stub: Path               = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      val completion: List[String] =
        List("--event", "completion", "--format", "text", "--session", "t")
      val first: Outcome[Int] = runGate(repo, completion, env)
      first match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"first completion should refuse, got $other")
      // A new turn starts: prompt-submit clears the session's refusal markers.
      runGate(repo, List("--event", "prompt-submit", "--format", "text", "--session", "t"), env)
      val third: Outcome[Int] = runGate(repo, completion, env)
      third match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a new turn should refuse again, got $other")
    }

  // ── Property: refusal-budget-is-bounded-and-nonzero ─────────────────
  // spec: gate-event-completeness — Property: refusal-budget-is-bounded-and-nonzero

  /**
   * `genTurnSequence` — the spec's turn model: a non-empty list (1–6) of
   * actions, each independently blockable (a completion event in a
   * refusing repo) or not (a session-start — informational, never
   * refuses). A forced blockable index guarantees ≥1 blockable action
   * by construction rather than by filtering. prompt-submit is never in
   * the sequence — it would end the turn (it clears the markers).
   */
  private def genTurnSequence: Gen[List[Boolean]] =
    for
      // Constant ranges — linear ranges scale with the hedgehog size
      // parameter, which skews the first blockable position to 0 and
      // starves the `blockable-not-first` cover class.
      n <- Gen.int(Range.constant(1, 6))
      // The first blockable position — weighted toward >0 so the
      // `blockable-not-first` class is well-covered.
      first <-
        if n == 1 then Gen.constant(0)
        else
          Gen.frequency1(
            2 -> Gen.constant(0),
            3 -> Gen.int(Range.constant(1, n - 1))
          )
      rest <- Gen.frequency1(3 -> Gen.constant(true), 2 -> Gen.constant(false)).list(Range.singleton(n))
    yield List.tabulate(n)((i: Int) => i == first || (i > first && rest(i)))

  property("refusal-budget-is-bounded-and-nonzero", coverConfig):
    for turn <- genTurnSequence.forAll
        .cover(25, "single-blockable", (t: List[Boolean]) => t.count(identity) == 1)
        .cover(40, "multiple-blockable", (t: List[Boolean]) => t.count(identity) >= 2)
        .cover(20, "blockable-not-first", (t: List[Boolean]) => t.indexOf(true) > 0)
    yield withTempDir("gate-completion-prop") { (repo: Path) =>
      val stub: Path               = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      val refusals: Int =
        turn.foldLeft(0) { (n: Int, blockable: Boolean) =>
          val event: String = if blockable then "completion" else "session-start"
          runGate(repo, List("--event", event, "--format", "text", "--session", "t"), env) match
            case Outcome.Finding(_) => n + 1
            case _ => // danger-scan:allow counter — non-Finding outcomes don't increment
              n
        }
      if refusals == 1 then Result.success
      else
        Result.failure.log(
          s"expected exactly 1 refusal in turn $turn, got $refusals"
        ) // danger-scan:allow test assertion — an unexpected outcome fails the test, never passes silently
    }

  // ── Scenario: the prompt event carries the harness's name ───────────
  // spec: gate-event-completeness — Scenario: the prompt event carries the harness's name

  test("the prompt-submit envelope names UserPromptSubmit"):
    withTempDir("gate-envelope-prompt") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List("--event", "prompt-submit", "--format", "hook-json", "--session", "t"),
            Map.empty
          )
        )
      val trimmed: String = out.trim
      assert(trimmed.nonEmpty, "prompt-submit should emit an envelope")
      val envelope: ujson.Value = ujson.read(trimmed)
      val name: String =
        envelope.obj("hookSpecificOutput").obj("hookEventName").str
      assertEquals(name, "UserPromptSubmit")
    }

  // ── Scenario: the internal name never appears in the envelope ───────
  // spec: gate-event-completeness — Scenario: the internal name never appears in the envelope

  test("the internal enum name never appears in the envelope"):
    withTempDir("gate-envelope-internal") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      // For every event that emits an envelope, the hookEventName is the
      // harness's own name — never the internal GateEvent case name. (For
      // SessionStart the two tokens coincide, so the distinguishing
      // assertion is on prompt-submit: "UserPromptSubmit", never
      // "PromptSubmit".)
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List("--event", "prompt-submit", "--format", "hook-json", "--session", "t"),
            Map.empty
          )
        )
      val name: String =
        ujson.read(out.trim).obj("hookSpecificOutput").obj("hookEventName").str
      assertNotEquals(name, "PromptSubmit")
      assertEquals(name, GateEvent.harnessName(GateEvent.PromptSubmit))
    }

  // ── Compile-Negative Obligations ────────────────────────────────────

  // spec: gate-event-completeness — Compile-Negative: A ToolOutcome.Exit constructed from a harness response classified as a refusal

  test("compile-negative: ToolOutcome.Exit cannot be constructed outside classify"):
    val err: String = compileErrors(
      "val fabricated = org.sinemenda.probatio.core.ToolOutcome.Exit(9)"
    )
    assert(err.nonEmpty, "direct Exit construction must not compile")
    val errSkip: String = compileErrors(
      "val fabricated = org.sinemenda.probatio.core.ToolOutcome.Skip(\"x\")"
    )
    assert(errSkip.nonEmpty, "direct Skip construction must not compile")

  // spec: gate-event-completeness — Compile-Negative: A GateEvent match omitting the 6th case
  //
  // The omission side is enforced by the build's exhaustiveness
  // escalation (-Wconf:name=PatternMatchExhaustivity:e, which Ring 0
  // applies to production code): a match listing only the five old cases
  // is a hard compile error there. The toolbox compiler used by
  // compileErrors reports exhaustiveness as a warning, not an error —
  // so this test pins the positive control: a six-case match typechecks,
  // and PostBash exists as a case, which is exactly what makes any
  // five-case match non-exhaustive by construction.

  test("compile-negative: a GateEvent match must name all six cases"):
    val describe: GateEvent => String = (e: GateEvent) =>
      e match
        case GateEvent.SessionStart => "session-start"
        case GateEvent.PromptSubmit => "prompt-submit"
        case GateEvent.ToolCall     => "tool-call"
        case GateEvent.PostEdit     => "post-edit"
        case GateEvent.PostBash     => "post-bash"
        case GateEvent.Completion   => "completion"
    assertEquals(describe(GateEvent.PostBash), "post-bash")

  // spec: gate-event-completeness — Compile-Negative: file I/O or env reads inside core gate decision functions

  test("compile-negative: no file/env-reading API exists on the decision module"):
    // GateDecisions takes all state as values. There is no I/O-typed
    // entry point to call — the module boundary makes it unconstructible.
    // (The classpath side — fs2-io absent from probatio-core — is pinned
    // in ToolOutcomeSpec.)
    val err: String = compileErrors(
      "org.sinemenda.probatio.core.GateDecisions.readFile(\"x\")"
    )
    assert(err.nonEmpty, "no file-reading API may exist on GateDecisions")
    val errEnv: String = compileErrors(
      "org.sinemenda.probatio.core.GateDecisions.readEnv(\"x\")"
    )
    assert(errEnv.nonEmpty, "no env-reading API may exist on GateDecisions")
    // Positive control: the decision functions ARE callable with pure args.
    assert(GateDecisions.isReadOnlyTool("Read"))

  // spec: gate-event-completeness — Compile-Negative: A raw SessionId constructed without its encoder

  test("compile-negative: a raw string cannot become a SessionId"):
    val err: String = compileErrors(
      "val s: org.sinemenda.probatio.core.SessionId = \"raw-session\""
    )
    assert(err.nonEmpty, "raw String must not be a SessionId")
    val errCtor: String = compileErrors(
      "val s = org.sinemenda.probatio.core.SessionId(\"raw-session\")"
    )
    assert(errCtor.nonEmpty, "SessionId has no public String constructor")

  // ── Ring 5 mutation coverage ────────────────────────────────────────
  // Pinpoint tests from the Ring-5 survivor audit. The scenario tests
  // above exercise these paths but do not observe the trace channel,
  // the flag/payload precedence, the scanner-argument surface, or the
  // marker-grammar edges — each test below asserts the observable
  // contract a survived mutant violated.

  /** Run the gate with the trace channel pointed at `repo/trace.log`. */
  private def runTraced(
    repo: Path,
    args: List[String],
    env: Map[String, String]
  ): (String, Outcome[Int]) =
    val traceFile: Path = repo.resolve("trace.log")
    val outcome: Outcome[Int] =
      runGate(repo, args, env + ("PROBATIO_HOOKS_TRACE" -> traceFile.toString))
    val text: String =
      if Files.isRegularFile(traceFile) then Files.readString(traceFile) else ""
    (text, outcome)

  private def traceLines(text: String, frag: String): Int =
    text.split("\n", -1).toList.count((l: String) => l.contains(frag))

  // ── completion-witness-refusal fixtures (spec 3 of repair-probatio-cutover) ──

  /**
   * `git rev-parse --short HEAD` — the baseline form the ledger records
   * (the post-bash writer's `--short`), so a "current baseline" claim
   * matches what the corroboration check resolves.
   */
  private def shortHead(repo: Path): String =
    HermeticEnv
      .capture(
        List("git", "-C", repo.toString, "rev-parse", "--short", "HEAD"),
        HermeticEnv.empty
      )
      .out
      .trim

  /**
   * A validator-shaped ledger row for the completion fixtures: the 15
   * contract fields plus `source: "ambient"` when the row is a witness.
   */
  private def ledgerRowJson(
    exit: Int,
    baseline: String,
    command: String,
    obligation: String,
    ambient: Boolean
  ): String =
    val row: ujson.Obj = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-01-01T00:00:00Z"),
      "change"     -> ujson.Str(change),
      "spec"       -> ujson.Str(specName),
      "ring"       -> ujson.Str("R3"),
      "obligation" -> ujson.Str(obligation),
      "artifact"   -> ujson.Str("tests/x.bats"),
      "command"    -> ujson.Str(command),
      "exit"       -> ujson.Num(exit.toDouble),
      "baseline"   -> ujson.Str(baseline)
    )
    if ambient then row("source") = ujson.Str("ambient")
    ujson.write(row)

  /**
   * A repo whose completion tier evaluates: a resolvable HEAD (the
   * current baseline), the session's checkpoint-presentation marker,
   * and a clean chain-state stub. Returns the short HEAD sha (the
   * baseline form ledger rows record) and the stub path.
   */
  private def mkCompletionFixture(repo: Path): (String, Path) =
    mkRepo(repo, withGit = true)
    // mkRepo's dirs are empty — seed a worktree file so HEAD resolves.
    Files.writeString(repo.resolve("seed.txt"), "seed")
    gitCommitAll(repo)
    val base: String = shortHead(repo)
    assert(base.nonEmpty, "the fixture commit must resolve HEAD")
    val sd: Path = stateDir(repo)
    Files.createDirectories(sd)
    Files.writeString(
      sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
      "h"
    )
    val chainState: Path = repo.resolve("cs-clean.sh")
    Files.writeString(
      chainState,
      "#!/usr/bin/env bash\n" +
        "echo '{\"change\":\"x\",\"baseline\":\"b\",\"total\":0,\"bound\":0,\"resolved\":0," +
        "\"discharged\":0,\"unresolved\":[],\"unmapped_obligations\":[]}'\n"
    )
    chainState.toFile.setExecutable(true)
    (base, chainState)

  test("the trace channel records the silent prologue skips"):
    withTempDir("gate-trace-skip") { (repo: Path) =>
      // No openspec/ — the relevance guard skips silently.
      val (t1: String, o1: Outcome[Int]) = runTraced(
        repo,
        List("--event", "tool-call", "--format", "text", "--session", "t", "--file", "x"),
        Map.empty
      )
      assertEquals(o1, Outcome.Ran(0))
      assert(t1.contains("skip: no openspec/ here"), s"missing relevance-skip trace: $t1")
      assert(t1.contains("event=tool-call"), s"missing event token in trace: $t1")
      // Hook control off — the second silent skip.
      mkRepo(repo, withGit = false)
      val (t2: String, o2: Outcome[Int]) = runTraced(
        repo,
        List("--event", "tool-call", "--format", "text", "--session", "t", "--file", "x"),
        Map("PROBATIO_HOOKS" -> "off")
      )
      assertEquals(o2, Outcome.Ran(0))
      assert(
        t2.contains("skip: hook control env var=off"),
        s"missing hook-control trace: $t2"
      )
    }

  test("the deprecated trace alias still writes the trace file"):
    withTempDir("gate-trace-alias") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val traceFile: Path = repo.resolve("alias-trace.log")
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "session-start", "--format", "text", "--session", "t"),
        Map("VERIFIED_SCALA3_HOOKS_TRACE" -> traceFile.toString)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        Files.isRegularFile(traceFile),
        "VERIFIED_SCALA3_HOOKS_TRACE must be honoured as the trace alias"
      )
    }

  test("the heartbeat records the event token and format"):
    withTempDir("gate-heartbeat-fields") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      runGate(
        repo,
        List("--event", "post-bash", "--format", "text", "--session", "t", "--command", "x", "--exit", "0"),
        Map.empty
      )
      val hb: ujson.Value =
        ujson.read(Files.readString(stateDir(repo).resolve("heartbeat")).trim)
      assertEquals(hb.obj("event").str, "post-bash")
      assertEquals(hb.obj("format").str, "text")
    }

  test("post-edit emits the spec-lint finding text in text mode"):
    withTempDir("gate-postedit-text") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val specFile: Path =
        repo.resolve(s"openspec/changes/$change/specs/$specName/spec.md")
      Files.writeString(specFile, "# spec\n")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              specFile.toString
            ),
            Map.empty
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("spec-lint: check could not run"),
        s"expected the spec-lint unavailability finding, got: $out"
      )
      assert(
        !out.contains("hookSpecificOutput"),
        s"text mode must not emit the hook envelope: $out"
      )
    }

  test("post-edit wraps findings in the PostToolUse envelope under hook-json"):
    withTempDir("gate-postedit-json") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val specFile: Path =
        repo.resolve(s"openspec/changes/$change/specs/$specName/spec.md")
      Files.writeString(specFile, "# spec\n")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "hook-json",
              "--session",
              "t",
              "--file",
              specFile.toString
            ),
            Map.empty
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      val envelope: ujson.Value = ujson.read(out.trim)
      assertEquals(
        envelope.obj("hookSpecificOutput").obj("hookEventName").str,
        "PostToolUse"
      )
      assert(
        envelope
          .obj("hookSpecificOutput")
          .obj("additionalContext")
          .str
          .contains("spec-lint"),
        "the envelope must carry the finding text"
      )
    }

  test("post-edit runs the spec-lint scanner through its override"):
    withTempDir("gate-postedit-scanner") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val argvFile: Path = repo.resolve("lint-argv.txt")
      val stub: Path     = repo.resolve("stub-spec-lint.sh")
      Files.writeString(
        stub,
        s"printf '%s\\n' \"$$@\" > ${argvFile}\n" +
          "echo LINT-OUT-MARKER; echo LINT-ERR >&2\n"
      )
      // Deliberately NOT executable — the predecessor's `-x || -f`
      // probe accepts a plain regular file (invoked via `bash`).
      val specFile: Path =
        repo.resolve(s"openspec/changes/$change/specs/$specName/spec.md")
      Files.writeString(specFile, "# spec\n")
      val (out: String, traced: (String, Outcome[Int])) =
        StdoutCapture.captureOut(
          runTraced(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              specFile.toString
            ),
            Map("SPEC_LINT_OVERRIDE" -> stub.toString)
          )
        )
      assert(
        out.contains(s"spec-lint ($change):"),
        s"expected the change-attributed lint header, got: $out"
      )
      assert(out.contains("LINT-OUT-MARKER"), s"expected the stub's stdout: $out")
      assert(out.contains("LINT-ERR"), s"stderr must merge into the report: $out")
      // The predecessor invokes the scanner with `--artifacts <dir>`.
      val argv: String = Files.readString(argvFile)
      assert(argv.contains("--artifacts"), s"missing --artifacts arg: $argv")
      assert(
        argv.contains(s"openspec/changes/$change"),
        s"the artifacts dir must name the change: $argv"
      )
      val (trace: String, _: Outcome[Int]) = traced
      assert(trace.contains("event=post-edit"), s"missing event token: $trace")
      assert(trace.contains("post-edit: file="), s"missing post-edit trace: $trace")
      assert(trace.contains("findings="), s"missing findings trace: $trace")
    }

  test("post-edit reports danger-scan for a production scala file"):
    withTempDir("gate-postedit-prod") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val prodFile: Path = repo.resolve("src/main/scala/A.scala")
      Files.createDirectories(prodFile.getParent)
      Files.writeString(prodFile, "object A\n")
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              prodFile.toString
            ),
            Map.empty
          )
        )
      assert(
        out.contains("danger-scan: check could not run"),
        s"expected the danger-scan unavailability finding, got: $out"
      )
    }

  test("post-edit emits nothing for an unrelated file"):
    withTempDir("gate-postedit-quiet") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              repo.resolve("README.md").toString
            ),
            Map.empty
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      // Exact-empty: an unconditional emit still produces a newline.
      assertEquals(out, "")
    }

  test("a relative --file path normalises against the repo"):
    withTempDir("gate-relfile") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          "src/main/scala/A.scala",
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a relative production path must normalise and block, got $other")
    }

  test("the --file flag suppresses the payload's tool_name"):
    withTempDir("gate-file-suppresses-tool") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // A state where a non-read-only tool would be refused — if the
      // payload's tool_name were honoured, Edit on a production path
      // would reach the oracle lock and block.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      // The payload claims the WRITING tool Edit — but the predecessor
      // reads .tool_name only when --file is absent, so TOOL_NAME stays
      // "" — and "" is inside the predecessor's read-only case, so the
      // call allows. A mutant honouring the payload tool_name would
      // block here.
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name"  -> ujson.Str("Edit"),
          "tool_input" -> ujson.Obj("file_path" -> ujson.Str("other.txt")),
          "cwd"        -> ujson.Str(repo.toString)
        )
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString
        ),
        Map.empty,
        payload
      )
      assertEquals(
        outcome,
        Outcome.Ran(0),
        "--file suppresses the payload tool_name; the empty name is read-only"
      )
    }

  test("the payload supplies file_path and tool_name when flags are absent"):
    withTempDir("gate-payload-fields") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name" -> ujson.Str("Edit"),
          "tool_input" -> ujson.Obj(
            "file_path" -> ujson.Str(repo.resolve("src/main/scala/A.scala").toString)
          ),
          "cwd" -> ujson.Str(repo.toString)
        )
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "tool-call", "--format", "text", "--session", "t"),
        Map.empty,
        payload
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a payload-driven production edit must block, got $other")
    }

  test("a read-only tool on a payload-supplied path is allowed"):
    withTempDir("gate-readonly-payload") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name" -> ujson.Str("Read"),
          "tool_input" -> ujson.Obj(
            "file_path" -> ujson.Str(repo.resolve("src/main/scala/A.scala").toString)
          ),
          "cwd" -> ujson.Str(repo.toString)
        )
      )
      val (trace: String, outcome: Outcome[Int]) = {
        val traceFile: Path = repo.resolve("trace.log")
        val o: Outcome[Int] = GateCmd.run(
          ("--repo" +: repo.toString +: List(
            "--event",
            "tool-call",
            "--format",
            "text",
            "--session",
            "t"
          )).toArray,
          Map("PROBATIO_HOOKS_TRACE" -> traceFile.toString),
          () => Some(payload)
        )
        (if Files.isRegularFile(traceFile) then Files.readString(traceFile) else "", o)
      }
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("read-only tool 'Read' on production path"),
        s"missing read-only trace: $trace"
      )
    }

  test("VERIFIED_SCALA3_ALLOW_PATHS allows a production edit under the prefix"):
    withTempDir("gate-allow-paths") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // Would-block state: an uncheckpointed prior.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map("VERIFIED_SCALA3_ALLOW_PATHS" -> repo.resolve("src/main").toString)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(trace.contains("allow-listed path"), s"trace: $trace")
    }

  test("VERIFIED_SCALA3_ACTIVE_SPEC overrides the active spec resolution"):
    withTempDir("gate-active-spec") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "unrelated.txt")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // prior-spec uncheckpointed; specName verified+checkpointed.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map("VERIFIED_SCALA3_ACTIVE_SPEC" -> specName)
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the overridden active spec must still check priors, got $other")
      assert(
        trace.contains("active spec overridden via env var"),
        s"missing override trace: $trace"
      )
    }

  test("the predecessor check scans only specs BEFORE the active one"):
    withTempDir("gate-priors-order") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // The ACTIVE spec is fully checkpointed; the PRIOR is not. A
      // `dropWhile` regression would check the active spec and allow.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an uncheckpointed prior must block even when the active spec is clean, got $other")
    }

  test("a RED ledger row at an ancestor baseline advances the oracle phase"):
    withTempDir("gate-phase-advance") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      // mkRepo's dirs are empty — seed a worktree file so HEAD resolves.
      Files.writeString(repo.resolve("seed.txt"), "seed")
      gitCommitAll(repo)
      val headSha: String =
        HermeticEnv
          .capture(
            List("git", "-C", repo.toString, "rev-parse", "HEAD"),
            HermeticEnv.empty
          )
          .out
          .trim
      assert(headSha.nonEmpty, "the fixture commit must resolve HEAD")
      val sd0: Path = stateDir(repo)
      Files.createDirectories(sd0)
      Files.writeString(sd0.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd0.resolve(s"presentation-$change-$priorSpec-t"), "h")
      Files.writeString(
        ledgerFile(repo),
        ujson.write(
          ujson.Obj(
            "change"   -> ujson.Str(change),
            "spec"     -> ujson.Str(specName),
            "ring"     -> ujson.Str("R3"),
            "exit"     -> ujson.Str("1"),
            "baseline" -> ujson.Str(headSha)
          )
        ) + "\n"
      )
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      // The phase file must be WRITTEN — a skipped write leaves the next
      // call still at oracle.
      assertEquals(
        Files.readString(stateDir(repo).resolve(s"phase-$change-$specName")),
        "implementation"
      )
      assert(
        trace.contains("phase oracle → implementation"),
        s"missing phase-advance trace: $trace"
      )
      val firstCount: Int = traceLines(trace, "phase oracle → implementation")
      // A second call: the phase no longer transitions — the trace must
      // not repeat the advance line.
      val (trace2: String, _: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      // The trace file accumulates across calls — the second call must
      // not ADD an advance line.
      assertEquals(
        traceLines(trace2, "phase oracle → implementation"),
        firstCount,
        s"a non-transition must not emit the phase-advance trace: $trace2"
      )
    }

  test("the production refusal names the prior and the escape hatch"):
    withTempDir("gate-refusal-text") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val (_: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List(
              "--event",
              "tool-call",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              repo.resolve("src/main/scala/A.scala").toString,
              "--tool",
              "Edit"
            ),
            Map.empty
          )
        )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"expected the predecessor block, got $other")
      assert(err.contains(s"predecessor spec $priorSpec is verified (not checkpointed)"), s"reason text: $err")
      assert(err.contains("All prior specs must be verified AND checkpointed"), s"reason text: $err")
      assert(err.contains("(run checkpoint.sh)"), s"reason text: $err")
      assert(err.contains("before editing this spec's production code"), s"reason text: $err")
      assert(err.contains("VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1"), s"escape hatch hint: $err")
      // The refusal marker binds the turn: the second call allows.
      val (trace2: String, second: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(second, Outcome.Ran(0))
      assert(trace2.contains("already refused once this turn, allow"), s"trace: $trace2")
    }

  test("the tool-call refusal is a decision:block envelope under hook-json"):
    withTempDir("gate-refusal-json") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "tool-call",
              "--format",
              "hook-json",
              "--session",
              "t",
              "--file",
              repo.resolve("src/main/scala/A.scala").toString,
              "--tool",
              "Edit"
            ),
            Map.empty
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.endsWith("}\n"), s"the envelope line ends with a newline: $out")
      val envelope: ujson.Value = ujson.read(out.trim)
      assertEquals(envelope.obj("decision").str, "block")
      assert(envelope.obj("reason").str.contains("predecessor"), s"reason: $out")
    }

  test("post-bash records a row from the payload's Bash command"):
    withTempDir("gate-postbash-payload") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // The predecessor's outcome shape: an object tool_response is a
      // success report; the exit code rides on the "Error: Exit code N"
      // string shape.
      val payload: String = postBashPayload(
        repo,
        "sbt adk4s-core/test",
        ujson.Str("Error: Exit code 1")
      )
      val (trace: String, outcome: Outcome[Int]) = {
        val tf: Path = repo.resolve("trace.log")
        val o: Outcome[Int] = GateCmd.run(
          ("--repo" +: repo.toString +: List(
            "--event",
            "post-bash",
            "--format",
            "text",
            "--session",
            "t"
          )).toArray,
          Map("PROBATIO_HOOKS_TRACE" -> tf.toString),
          () => Some(payload)
        )
        (if Files.isRegularFile(tf) then Files.readString(tf) else "", o)
      }
      assertEquals(outcome, Outcome.Ran(0))
      val rows: List[ujson.Value] = ledgerRows(repo)
      assert(rows.nonEmpty, "a Bash payload with exit_code must record")
      val row: ujson.Value = rows.lastOption.getOrElse(fail("row must exist"))
      assertEquals(row.obj("exit").num.toInt, 1)
      assertEquals(row.obj("ring").str, "R3")
      assertEquals(row.obj("source").str, "ambient")
      assertEquals(row.obj("command").str, "sbt adk4s-core/test")
      assert(
        trace.contains("post-bash: row appended (ring=R3, exit=1)"),
        s"missing record trace: $trace"
      )
    }

  test("post-bash ignores a payload whose tool is not Bash"):
    withTempDir("gate-postbash-notbash") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name"     -> ujson.Str("Read"),
          "tool_input"    -> ujson.Obj("command" -> ujson.Str("sbt adk4s-core/test")),
          "tool_response" -> ujson.Obj("exit_code" -> ujson.Num(0)),
          "cwd"           -> ujson.Str(repo.toString)
        )
      )
      val tf: Path = repo.resolve("trace.log")
      val outcome: Outcome[Int] = GateCmd.run(
        ("--repo" +: repo.toString +: List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t"
        )).toArray,
        Map("PROBATIO_HOOKS_TRACE" -> tf.toString),
        () => Some(payload)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(ledgerRows(repo).length, 0)
      assert(
        Files.readString(tf).contains("tool is Read, not Bash — not recorded"),
        "the non-Bash skip must be traced"
      )
    }

  test("post-bash without --exit records nothing"):
    withTempDir("gate-postbash-noexit") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(ledgerRows(repo).length, 0)
      // A command without a resolvable exit is not a pair — the resolved
      // pair traces empty, matching the predecessor's empty-field print.
      assert(
        trace.contains("post-bash: command='' exit="),
        s"a missing --exit must resolve to the empty pair: $trace"
      )
    }

  test("the ambient row names the presented-but-ungranted spec"):
    withTempDir("gate-postbash-spec") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${session.encoded}"),
        "h"
      )
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(ledgerRows(repo).lastOption.map((v: ujson.Value) => v.obj("spec").str), Some(specName))
      assert(
        trace.contains("command='sbt adk4s-core/test' exit=0"),
        s"missing resolved-pair trace: $trace"
      )
      assert(
        trace.contains("row appended (ring=R3, exit=0)"),
        s"missing record trace: $trace"
      )
    }

  test("the ambient row baseline is the short HEAD sha"):
    withTempDir("gate-postbash-baseline") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // mkRepo's dirs are empty — seed a worktree file so HEAD resolves.
      Files.writeString(repo.resolve("seed.txt"), "seed")
      gitCommitAll(repo)
      val shortSha: String =
        HermeticEnv
          .capture(
            List("git", "-C", repo.toString, "rev-parse", "--short", "HEAD"),
            HermeticEnv.empty
          )
          .out
          .trim
      assert(shortSha.nonEmpty, "the fixture commit must resolve HEAD")
      runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(ledgerRows(repo).lastOption.map((v: ujson.Value) => v.obj("baseline").str), Some(shortSha))
    }

  test("prompt-submit writes the grant for a presented-but-ungranted spec"):
    withTempDir("gate-grant-write") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      // Single-segment names so the right-to-left markerTriple split is
      // unambiguous.
      Files.writeString(
        sd.resolve(s"presentation-cg-sp-${session.encoded}"),
        "ph"
      )
      // An EMPTY presentation hash writes no grant (the predecessor's
      // non-empty guard).
      Files.writeString(sd.resolve(s"presentation-cg-e1-${session.encoded}"), "")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List("--event", "prompt-submit", "--format", "text", "--session", "t"),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(
        Files.readString(sd.resolve(s"grant-cg-sp-${session.encoded}")),
        "ph"
      )
      assert(
        !Files.exists(sd.resolve(s"grant-cg-e1-${session.encoded}")),
        "an empty presentation hash must not grant"
      )
      assert(trace.contains("grant written for cg/sp"), s"trace: $trace")
      assert(
        trace.contains("emit:"),
        s"the first prompt-submit must trace the banner emit: $trace"
      )
      // Idempotent: a second prompt-submit does not re-trace the grant
      // (the trace file accumulates — the count must not grow).
      val (trace2: String, _: Outcome[Int]) = runTraced(
        repo,
        List("--event", "prompt-submit", "--format", "text", "--session", "t"),
        Map.empty
      )
      assertEquals(
        traceLines(trace2, "grant written for"),
        traceLines(trace, "grant written for")
      )
    }

  test("a repeated prompt-submit in one session suppresses the banner"):
    withTempDir("gate-banner-suppress") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val args: List[String] =
        List("--event", "prompt-submit", "--format", "text", "--session", "t")
      val (out1: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(runGate(repo, args, Map.empty))
      val (trace: String, out2res: (String, Outcome[Int])) = {
        val tf: Path = repo.resolve("trace.log")
        val r: (String, Outcome[Int]) = StdoutCapture.captureOut(
          runGate(repo, args, Map("PROBATIO_HOOKS_TRACE" -> tf.toString))
        )
        (if Files.isRegularFile(tf) then Files.readString(tf) else "", r)
      }
      assert(out1.nonEmpty, "the first prompt-submit emits the banner")
      assertEquals(out2res._1.trim, "", "an unchanged banner is suppressed")
      assert(
        trace.contains("skip: unchanged since last injection this session"),
        s"missing suppression trace: $trace"
      )
    }

  test("completion allows when stop_hook_active is set by flag or payload"):
    withTempDir("gate-stop-active") { (repo: Path) =>
      // The marker is bound to session v — the --turn-text run below
      // reaches the marker check (the flagged/payload runs allow before
      // it) and must find a presentation to refuse on.
      val stub: Path               = mkRefusingRepo(repo, SessionId.fromRaw("v"))
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      // Sessions t and u also carry the marker — the flag/payload must
      // allow BEFORE the marker check, not because no marker was found.
      val sd: Path = stateDir(repo)
      List("t", "u").foreach { (s: String) =>
        Files.writeString(
          sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw(s).encoded}"),
          "h"
        )
      }
      val (flagTrace: String, flagged: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "completion",
          "--format",
          "text",
          "--session",
          "t",
          "--stop-hook-active",
          "true"
        ),
        env
      )
      assertEquals(flagged, Outcome.Ran(0))
      assert(
        flagTrace.contains("stop_hook_active=true"),
        s"missing stop-hook trace: $flagTrace"
      )
      // The payload's stop_hook_active is honoured too — and --turn-text
      // suppresses that read entirely.
      val payload: String = ujson.write(
        ujson.Obj(
          "stop_hook_active" -> ujson.Bool(true),
          "cwd"              -> ujson.Str(repo.toString)
        )
      )
      val viaPayload: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "completion", "--format", "text", "--session", "u"),
        env,
        payload
      )
      assertEquals(viaPayload, Outcome.Ran(0))
      val suppressed: Outcome[Int] = runGatePayload(
        repo,
        List(
          "--event",
          "completion",
          "--format",
          "text",
          "--session",
          "v",
          "--turn-text",
          "prompt"
        ),
        env,
        payload
      )
      suppressed match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"--turn-text must suppress the payload stop_hook_active read, got $other")
      // stop_hook_active:false proceeds to the marker check — a jq
      // `if . then "true"` regression that renders false as "true"
      // would wrongly allow here.
      val w: SessionId = SessionId.fromRaw("w")
      Files.writeString(
        stateDir(repo).resolve(s"presentation-$change-$specName-${w.encoded}"),
        "h"
      )
      val falsePayload: String = ujson.write(
        ujson.Obj(
          "stop_hook_active" -> ujson.Bool(false),
          "cwd"              -> ujson.Str(repo.toString)
        )
      )
      val viaFalse: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "completion", "--format", "text", "--session", "w"),
        env,
        falsePayload
      )
      viaFalse match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"stop_hook_active:false must proceed to the marker check, got $other")
    }

  test("completion with a chain-state exit-2 stub is undetermined"):
    withTempDir("gate-completion-exit2") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val stub: Path = repo.resolve("cs-exit2.sh")
      Files.writeString(stub, "echo '{\"total\":0}'\nexit 2\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a non-{0,1} chain-state exit must be undetermined, got $other")
      assert(
        trace.contains("running chain-state"),
        s"missing scan trace: $trace"
      )
      assert(
        trace.contains("refuse (undetermined)"),
        s"missing undetermined trace: $trace"
      )
    }

  // ── completion-witness-refusal (spec 3 of repair-probatio-cutover) ──
  //
  // The corroboration verdict is computed IN-CORE — the adapter reads the
  // evidence ledger itself (`readLedgerFile` + `Ledger.readValidated` +
  // `ReconcileEngine.classify` + `GateDecisions.corroborationVerdict`), so
  // no reconcile subprocess is involved and no tool resolution can skip
  // the check (the defect this spec repairs). The refusal detail text is
  // the reconcile report's own rendering — byte-identical to what the
  // predecessor appended.

  // spec: completion-witness-refusal — Requirement: A turn is refused when a green result has no corroboration
  test("completion is refused when a green row has no witness"):
    withTempDir("gate-completion-uncorr") { (repo: Path) =>
      val (base: String, chainState: Path) = mkCompletionFixture(repo)
      // A written green claim at the current baseline with no
      // corroborating observation — testimony.
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, base, "sbt test", "the unwitnessed obligation", ambient = false) + "\n"
      )
      val (out: String, traced: (String, Outcome[Int])) =
        StdoutCapture.captureOut(
          runTraced(
            repo,
            List("--event", "completion", "--format", "text", "--session", "t"),
            Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
          )
        )
      val outcome: Outcome[Int] = traced._2
      outcome match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"uncorroborated evidence must refuse (exit 1), got $other")
      assert(out.contains("uncorroborated"), s"refusal reason: $out")
      assert(
        out.contains("the unwitnessed obligation"),
        s"the refusal names the offending row: $out"
      )
      assert(
        out.contains("sbt test"),
        s"the refusal names the offending row's command: $out"
      )
      assert(out.endsWith("\n"), s"the text refusal ends with a newline: $out")
      assert(
        traced._1.contains("refuse (uncorroborated)"),
        s"missing uncorroborated trace: ${traced._1}"
      )
    }

  // spec: completion-witness-refusal — Scenario: Adversarial — a single uncorroborated green result refuses the turn
  test("a single uncorroborated green result among corroborated ones refuses and names it"):
    withTempDir("gate-completion-mixed") { (repo: Path) =>
      val (base: String, chainState: Path) = mkCompletionFixture(repo)
      // A corroborated claim (witnessed by an ambient row at the same
      // key) alongside ONE uncorroborated claim at a different key.
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, base, "sbt test", "obl-witnessed", ambient = false) + "\n" +
          ledgerRowJson(0, base, "sbt test", "obl-witnessed", ambient = true) + "\n" +
          ledgerRowJson(0, base, "make check", "obl-lonely", ambient = false) + "\n"
      )
      val (out: String, traced: (String, Outcome[Int])) =
        StdoutCapture.captureOut(
          runTraced(
            repo,
            List("--event", "completion", "--format", "text", "--session", "t"),
            Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
          )
        )
      traced._2 match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"one uncorroborated claim among corroborated ones must refuse, got $other")
      assert(
        out.contains("obl-lonely"),
        s"the refusal names the uncorroborated result: $out"
      )
      assert(
        !out.contains("obl-witnessed"),
        s"the corroborated claim is not named as uncorroborated: $out"
      )
    }

  // spec: completion-witness-refusal — Scenario: Happy path — every green result is corroborated and completion proceeds
  test("completion proceeds when every green row is witnessed"):
    withTempDir("gate-completion-witnessed") { (repo: Path) =>
      val (base: String, chainState: Path) = mkCompletionFixture(repo)
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, base, "sbt test", "obl-witnessed", ambient = false) + "\n" +
          ledgerRowJson(0, base, "sbt test", "obl-witnessed", ambient = true) + "\n"
      )
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a fully witnessed record must allow, got $other")
    }

  // spec: completion-witness-refusal — Scenario: Edge case — a non-green result needs no corroboration
  test("a red result with no witness needs no corroboration"):
    withTempDir("gate-completion-red") { (repo: Path) =>
      val (base: String, chainState: Path) = mkCompletionFixture(repo)
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(1, base, "sbt test", "obl-red", ambient = false) + "\n"
      )
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a red row is exempt from corroboration, got $other")
    }

  // spec: completion-witness-refusal — Property: parity-with-predecessor-on-the-completion-tier (declared divergence)
  // The declared divergence, exercised directly: an uncorroborated green
  // claim at a NON-current baseline is a warrant for the predecessor's
  // unfiltered reconcile but out of the ported current-baseline scope —
  // the port allows.
  test("an uncorroborated claim at a stale baseline does not refuse"):
    withTempDir("gate-completion-stale") { (repo: Path) =>
      val (_: String, chainState: Path) = mkCompletionFixture(repo)
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, "0000000", "sbt test", "obl-stale", ambient = false) + "\n"
      )
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a stale-baseline claim is outside the current-baseline scope, got $other")
    }

  // spec: completion-witness-refusal — Scenario: Error path — an unreadable evidence record allows completion with a stated reason
  test("an unreadable evidence record allows completion with a stated reason"):
    withTempDir("gate-completion-badrecord") { (repo: Path) =>
      val (_: String, chainState: Path) = mkCompletionFixture(repo)
      // The record exists but cannot be parsed — the corroboration check
      // cannot run, and the tier must SAY SO, naming the unreadable input.
      Files.writeString(ledgerFile(repo), "this is not json\n")
      val traced: (String, Outcome[Int]) =
        runTraced(
          repo,
          List("--event", "completion", "--format", "text", "--session", "t"),
          Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
        )
      traced._2 match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an unreadable record fails open, got $other")
      assert(
        traced._1.contains("corroboration") && traced._1.contains("evidence-ledger"),
        s"the trace must state the corroboration check could not run, naming the record: ${traced._1}"
      )
    }

  // spec: completion-witness-refusal — Scenario: Adversarial — an unreadable record does not produce a refusal
  test("an unparseable record does not produce a refusal"):
    withTempDir("gate-completion-badrecord2") { (repo: Path) =>
      val (_: String, chainState: Path) = mkCompletionFixture(repo)
      Files.writeString(ledgerFile(repo), "this is not json\n")
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
      )
      outcome match
        case Outcome.Ran(0) => ()
        case Outcome.Ran(n) =>
          fail(s"an unreadable record must not exit the tool: $n")
        case Outcome.Finding(msg) =>
          fail(s"an unreadable record must never refuse: $msg")
        case Outcome.Undetermined(reason) =>
          fail(s"an unreadable record must not refuse-undetermined: $reason")
    }

  // spec: completion-witness-refusal — Scenario: Adversarial — a second attempt in the same turn is not refused
  test("a second completion attempt in the same turn proceeds after an uncorroborated refusal"):
    withTempDir("gate-completion-second-uncorr") { (repo: Path) =>
      val (base: String, chainState: Path) = mkCompletionFixture(repo)
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, base, "sbt test", "obl-lonely", ambient = false) + "\n"
      )
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
      val args: List[String] =
        List("--event", "completion", "--format", "text", "--session", "t")
      runGate(repo, args, env) match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the first attempt must refuse, got $other")
      runGate(repo, args, env) match
        case Outcome.Ran(0)       => ()
        case Outcome.Finding(msg) => fail(s"the second refusal must not be issued: $msg")
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"expected allow, got $other")
    }

  // spec: completion-witness-refusal — Scenario: Edge case — a new turn refuses again
  test("a new turn refuses again on an uncorroborated warrant"):
    withTempDir("gate-completion-newturn") { (repo: Path) =>
      val (base: String, chainState: Path) = mkCompletionFixture(repo)
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, base, "sbt test", "obl-lonely", ambient = false) + "\n"
      )
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> chainState.toString)
      val args: List[String] =
        List("--event", "completion", "--format", "text", "--session", "t")
      runGate(repo, args, env) match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the first attempt must refuse, got $other")
      // A new turn starts: prompt-submit clears the session's refusal markers.
      runGate(repo, List("--event", "prompt-submit", "--format", "text", "--session", "t"), env)
      runGate(repo, args, env) match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a new turn must refuse again, got $other")
    }

  test("completion unresolved entries refuse with requirement and reasons"):
    withTempDir("gate-completion-unresolved") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val stub: Path = repo.resolve("cs-unresolved.sh")
      Files.writeString(
        stub,
        "echo '{\"total\":1,\"unresolved\":[{\"requirement\":\"req-1\",\"reasons\":[\"why-1\"]}]}'\n"
      )
      val (out: String, traced: (String, Outcome[Int])) =
        StdoutCapture.captureOut(
          runTraced(
            repo,
            List("--event", "completion", "--format", "text", "--session", "t"),
            Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
          )
        )
      val outcome: Outcome[Int] = traced._2
      outcome match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"unresolved obligations must refuse, got $other")
      assert(out.contains("req-1"), s"unresolved requirement names: $out")
      assert(out.contains("why-1"), s"unresolved reasons: $out")
      assert(out.contains(change), s"the change name prefixes the block: $out")
      assert(
        traced._1.contains("refuse (unresolved)"),
        s"missing unresolved trace: ${traced._1}"
      )
    }

  test("a change directory literally named archive is not scanned"):
    withTempDir("gate-completion-archive") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      Files.createDirectories(
        repo.resolve("openspec/changes").resolve("archive").resolve("specs")
      )
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // The stub fails loudly if the archive dir is ever scanned — an
      // `activeChangeDirs` regression that includes archive surfaces
      // here, not as a silent clean run.
      val argvOut: Path = repo.resolve("cs-argv.txt")
      val stub: Path    = repo.resolve("cs-names.sh")
      Files.writeString(
        stub,
        s"for a in \"$$@\"; do [ \"$$a\" = archive ] && echo SCANNED-ARCHIVE && exit 9; done\n" +
          s"printf '%s\\n' \"$$@\" > $argvOut\necho '{\"total\":0}'\n"
      )
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      // archive/ is excluded from the scan; test-change resolves clean.
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("allow (fully discharged)"),
        s"missing discharge trace: $trace"
      )
      val argv: String = Files.readString(argvOut)
      assert(
        !argv.split("\n").exists((a: String) => a == "archive"),
        s"archive must never be scanned: $argv"
      )
      assert(argv.contains(change), s"argv: $argv")
    }

  test("the completion scan passes change-dir, change, and baseline to chain-state"):
    withTempDir("gate-cs-args") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val argvOut: Path = repo.resolve("argv.txt")
      val stub: Path    = repo.resolve("cs-argv.sh")
      Files.writeString(
        stub,
        s"printf '%s\\n' \"$$@\" > ${argvOut}\necho '{\"total\":0}'\n"
      )
      runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      val argv: String = Files.readString(argvOut)
      assert(argv.contains("--change-dir"), s"argv: $argv")
      assert(argv.contains(change), s"argv: $argv")
      assert(argv.contains("--baseline"), s"argv: $argv")
      assert(
        argv.split("\n").exists((a: String) => a == "--change"),
        s"argv must carry the --change flag token: $argv"
      )
      // The fixture repo has no commit — rev-parse fails and the gate
      // passes the predecessor's `unknown` baseline, not an empty flag.
      assert(
        argv.split("\n").exists((a: String) => a == "unknown"),
        s"an unborn HEAD must pass the literal unknown baseline: $argv"
      )
    }

  test("the channel is not read when --repo is present and the event needs no payload"):
    withTempDir("gate-channel-gate") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val read: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      val channel: () => Option[String] =
        () =>
          read.set(true)
          Some("{}")
      GateCmd.run(
        ("--repo" +: repo.toString +: List(
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "t"
        )).toArray,
        Map.empty,
        channel
      )
      assert(!read.get(), "session-start with --repo must not read the channel")
      GateCmd.run(
        ("--repo" +: repo.toString +: List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t"
        )).toArray,
        Map.empty,
        channel
      )
      assert(read.get(), "post-bash must read the channel for its payload")
    }

  test("the payload .cwd resolves the repo when --repo is absent"):
    withTempDir("gate-cwd-fallback") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name"     -> ujson.Str("Bash"),
          "tool_input"    -> ujson.Obj("command" -> ujson.Str("sbt adk4s-core/test")),
          "tool_response" -> ujson.Obj("exit_code" -> ujson.Num(0)),
          "cwd"           -> ujson.Str(repo.toString)
        )
      )
      // No --repo: the payload's .cwd is the repo fallback.
      val outcome: Outcome[Int] = GateCmd.run(
        Array("--event", "post-bash", "--format", "text", "--session", "t"),
        Map.empty,
        () => Some(payload)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        ledgerRows(repo).nonEmpty,
        "the .cwd fallback must resolve the repo and record the row"
      )
    }

  test("duplicate flags resolve last-wins"):
    withTempDir("gate-lastwins") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      // --format text then --format hook-json: the LAST value wins, so
      // the envelope shape must appear on stdout.
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "prompt-submit",
              "--format",
              "text",
              "--format",
              "hook-json",
              "--session",
              "t"
            ),
            Map.empty
          )
        )
      assert(
        out.contains("hookSpecificOutput"),
        s"last-wins must yield the hook-json envelope, got: $out"
      )
    }

  test("the checkpoint-output sweep trace names each recorded triple"):
    withTempDir("gate-sweep-trace") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // Single-segment names so the right-to-left markerTriple split is
      // unambiguous: cg/sp/t parse as (change, spec, session).
      Files.writeString(sd.resolve("checkpoint-output-cg-sp-t"), "content")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List("--event", "session-start", "--format", "text", "--session", "t"),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("presentation recorded for cg/sp (session t)"),
        s"missing sweep trace: $trace"
      )
      assert(Files.isRegularFile(sd.resolve("presentation-cg-sp-t")))
    }

  test("tool-call without git state fails open on both path classes"):
    withTempDir("gate-nogit") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (trace: String, o1: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(o1, Outcome.Ran(0))
      assert(
        trace.contains("bounded-refusal state unavailable"),
        s"missing fail-open trace: $trace"
      )
      val (trace2: String, o2: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("build.sbt").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(o2, Outcome.Ran(0))
      assert(
        trace2.contains("non-production path, allow"),
        s"missing non-prod trace: $trace2"
      )
    }

  test("post-bash and completion degrade to allow without git state"):
    withTempDir("gate-nogit-tiers") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (t1: String, o1: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(o1, Outcome.Ran(0))
      assert(t1.contains("STATE_DIR unavailable, skipping record"), s"trace: $t1")
      val (t2: String, o2: Outcome[Int]) = runTraced(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map.empty
      )
      assertEquals(o2, Outcome.Ran(0))
      assert(t2.contains("no STATE_DIR, no marker check possible"), s"trace: $t2")
    }

  test("the grant lock refuses a Step-0 edit without a grant"):
    withTempDir("gate-grant-lock") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      // specName was presented this session but never granted.
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${session.encoded}"),
        "h"
      )
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve(s"openspec/changes/$change/implementation-progress.md").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(msg) =>
          assert(msg.contains("grant"), s"expected the grant refusal, got: $msg")
          assert(
            msg.contains("no human grant for spec"),
            s"the refusal must name the missing grant: $msg"
          )
          assert(
            msg.contains("prompt-submit"),
            s"the refusal must name the granting event: $msg"
          )
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a grant-less Step-0 edit must refuse, got $other")
      assert(trace.contains("refuse (missing grant"), s"trace: $trace")
      // The refusal marker binds the turn.
      val (trace2: String, second: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve(s"openspec/changes/$change/implementation-progress.md").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(second, Outcome.Ran(0))
      assert(
        trace2.contains("already refused grant once this turn"),
        s"trace: $trace2"
      )
    }

  test("the grant lock waives a verified-and-presented required spec"):
    withTempDir("gate-grant-waive") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${session.encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve(s"openspec/changes/$change/implementation-progress.md").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("waive grant"),
        s"the waiver trace must fire: $trace"
      )
    }

  test("a prior session's grant satisfies the grant lock"):
    withTempDir("gate-grant-prior-session") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${session.encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"grant-$change-$specName-priorsession"), "g")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve(s"openspec/changes/$change/implementation-progress.md").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(trace.contains("found from a prior session"), s"trace: $trace")
      assert(trace.contains("grant satisfied"), s"trace: $trace")
    }

  test("an empty spec list in implementation-order falls back to spec dirs"):
    withTempDir("gate-specdirs-fallback") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // implementation-order.md exists but names no specs — the
      // predecessor falls back to the specs/ directory listing.
      Files.writeString(
        repo.resolve("openspec/changes").resolve(change).resolve("implementation-order.md"),
        "# Order\n\n(no spec table)\n"
      )
      // A second spec dir orders BEFORE test-spec alphabetically.
      Files.createDirectories(
        repo.resolve("openspec/changes").resolve(change).resolve("specs").resolve(priorSpec)
      )
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      // Implementation — past the oracle lock but not yet verified, so
      // the fallback resolves it as the active spec.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "implementation")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the spec-dir fallback must still check priors, got $other")
    }

  // ── Ring 5 round 2 — surviving-mutant pinpoints ─────────────────────

  test("a non-production file allows with the state dir present"):
    withTempDir("gate-nonprod-statedir") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      Files.createDirectories(stateDir(repo))
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("build.sbt").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("non-production path, allow"),
        s"missing non-production trace: $trace"
      )
    }

  test("a non-production file allows when no change is active"):
    withTempDir("gate-nonprod-nochange") { (repo: Path) =>
      // openspec/changes exists but holds no change directories.
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec/changes"))
      Files.createDirectories(stateDir(repo))
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("build.sbt").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("non-production path, allow"),
        s"missing non-production trace: $trace"
      )
    }

  test("the payload tool_input.path supplies the file when file_path is absent"):
    withTempDir("gate-payload-path-alt") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      // file_path is `false` — jq `//` semantics read .path instead.
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name" -> ujson.Str("Edit"),
          "tool_input" -> ujson.Obj(
            "file_path" -> ujson.Bool(false),
            "path"      -> ujson.Str(repo.resolve("src/main/scala/A.scala").toString)
          ),
          "cwd" -> ujson.Str(repo.toString)
        )
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "tool-call", "--format", "text", "--session", "t"),
        Map.empty,
        payload
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the .path fallback must reach the production edit, got $other")
    }

  test("completion allows silently when no presentation marker exists"):
    withTempDir("gate-completion-nomarker") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      Files.createDirectories(stateDir(repo))
      // A marker for a DIFFERENT session must not refuse this session —
      // the predecessor's `&&` binds presentation to this session.
      Files.writeString(
        stateDir(repo).resolve("presentation-cg-sp-other"),
        "h"
      )
      val stub: Path = repo.resolve("cs-clean.sh")
      Files.writeString(stub, "echo '{\"total\":0}'\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("no checkpoint presentation marker for this session"),
        s"missing no-marker trace: $trace"
      )
    }

  test("a second completion refusal in the turn is suppressed"):
    withTempDir("gate-completion-twice") { (repo: Path) =>
      val stub: Path               = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val env: Map[String, String] = Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      val first: Outcome[Int] =
        runGate(repo, List("--event", "completion", "--format", "text", "--session", "t"), env)
      first match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the first completion must refuse, got $other")
      val (trace: String, second: Outcome[Int]) = runTraced(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        env
      )
      assertEquals(second, Outcome.Ran(0))
      assert(
        trace.contains("already refused once this turn, allow"),
        s"missing suppression trace: $trace"
      )
    }

  test("completion is undetermined when the chain-state tool is absent"):
    withTempDir("gate-completion-notool") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // No CHAIN_STATE_OVERRIDE and no chain-state.sh on disk — the
      // predecessor's missing-tool exit (127) is undetermined.
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"a missing chain-state tool must be undetermined, got $other")
    }

  test("completion skips reconcile when the change has no ledger"):
    withTempDir("gate-completion-noledger") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // No evidence-ledger.jsonl — the reconcile tool must NOT run
      // (the predecessor's `&&` requires both). If it ran, its
      // UNCORROBORATED verdict would refuse.
      val reconcile: Path = repo.resolve("reconcile-stub.sh")
      Files.writeString(reconcile, "echo UNCORROBORATED-EVIDENCE\nexit 1\n")
      val chainState: Path = repo.resolve("cs-clean.sh")
      Files.writeString(chainState, "echo '{\"total\":0}'\n")
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map(
          "RECONCILE_OVERRIDE"   -> reconcile.toString,
          "CHAIN_STATE_OVERRIDE" -> chainState.toString
        )
      )
      assertEquals(
        outcome,
        Outcome.Ran(0),
        "no ledger means reconcile never runs — no uncorroborated finding"
      )
    }

  test("--check-installed resolves the repo from the payload cwd"):
    withTempDir("gate-probe-payload") { (repo: Path) =>
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec"))
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve("heartbeat"), """{"ts":"t1","event":"post-bash"}""")
      val payload: String =
        ujson.write(ujson.Obj("cwd" -> ujson.Str(repo.toString)))
      // No --repo: the channel is read and .cwd resolves the repo.
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          GateCmd.run(
            Array("--check-installed"),
            Map.empty,
            () => Some(payload)
          )
        )
      val installed: Boolean = ujson.read(out.trim).obj("installed").bool
      assert(installed, s"payload .cwd must resolve the repo: $out")
      assertEquals(ujson.read(out.trim).obj("last_run").str, "t1")
    }

  test("session-start reads the channel for the repo fallback"):
    withTempDir("gate-sessionstart-cwd") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val read: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      val payload: String =
        ujson.write(ujson.Obj("cwd" -> ujson.Str(repo.toString)))
      val channel: () => Option[String] =
        () =>
          read.set(true)
          Some(payload)
      val outcome: Outcome[Int] = GateCmd.run(
        Array("--event", "session-start", "--format", "text", "--session", "t"),
        Map.empty,
        channel
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        read.get(),
        "session-start without --repo must read the channel for .cwd"
      )
      assert(
        Files.isRegularFile(stateDir(repo).resolve("heartbeat")),
        "the heartbeat must land in the .cwd-resolved repo"
      )
    }

  test("post-bash traces the skip reason for a non-command response"):
    withTempDir("gate-postbash-skipreason") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // A plain string response is not an "Error: Exit code N" shape —
      // classify reports not-a-command-outcome and nothing records.
      val payload: String =
        postBashPayload(repo, "sbt adk4s-core/test", ujson.Str("all good"))
      val tf: Path = repo.resolve("trace.log")
      val outcome: Outcome[Int] = GateCmd.run(
        ("--repo" +: repo.toString +: List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t"
        )).toArray,
        Map("PROBATIO_HOOKS_TRACE" -> tf.toString),
        () => Some(payload)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(ledgerRows(repo).length, 0)
      assert(
        Files.readString(tf).contains("not-a-command-outcome — not recorded"),
        "the classify skip reason must be traced"
      )
    }

  test("post-bash traces the resolved pair for a non-ring command"):
    withTempDir("gate-postbash-nonring") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "ls -la",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(ledgerRows(repo).length, 0)
      assert(
        trace.contains("command='ls -la' exit=0"),
        s"missing resolved-pair trace: $trace"
      )
      assert(
        trace.contains("no ring shape match, not recorded"),
        s"missing ambient-skip trace: $trace"
      )
    }

  test("the ambient row reports unknown spec when no spec dirs exist"):
    withTempDir("gate-postbash-nospec") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // A change dir with NO specs/ — firstActiveChange picks it (it
      // sorts before test-change) and specDirs is empty.
      Files.createDirectories(repo.resolve("openspec/changes").resolve("aaa-change"))
      Files.createDirectories(stateDir(repo))
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      val rows: List[ujson.Value] = Files
        .readAllLines(
          repo.resolve("openspec/changes/aaa-change/evidence-ledger.jsonl")
        )
        .toArray
        .toList
        .collect { case s: String if s.trim.nonEmpty => ujson.read(s.trim) }
      assert(rows.nonEmpty, "the ambient row must land in the active change's ledger")
      assertEquals(
        rows.lastOption.map((v: ujson.Value) => v.obj("spec").str),
        Some("unknown")
      )
    }

  // ── Ring 5 PASS B remediation, round 3 ─────────────────────────────
  // Pinpoint additions for mutants that survived rounds 1–2. Each test
  // names the production semantic it pins.

  test("the predecessor-check escape env bypasses the priors scan"):
    withTempDir("gate-skip-predecessor") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // prior-spec: verified but uncheckpointed — the scan would block.
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      // specName: past oracle so the call reaches the predecessor check.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "implementation")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map("VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK" -> "1")
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("predecessor check skipped"),
        s"missing skip trace: $trace"
      )
      assert(
        trace.contains(s"allow (phase implementation, $change/$specName)"),
        s"missing allow trace: $trace"
      )
    }

  test("a spec-dir target on an untracked spec allows without consulting grants"):
    withTempDir("gate-specdir-untracked") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // specName is presented-but-ungranted AND not verified — if the
      // grant lock were consulted it would refuse.
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "implementation")
      // The target is a file under a spec dir with NO phase file.
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/brand-new/design.md")
      Files.createDirectories(target.getParent)
      Files.writeString(target, "# new\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("has no phase file"),
        s"missing untracked-spec trace: $trace"
      )
    }

  test("a spec-dir target on a past-oracle spec allows without consulting grants"):
    withTempDir("gate-specdir-past-oracle") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // prior-spec: presented-but-ungranted and unverified — consulted,
      // it would refuse.
      Files.writeString(
        sd.resolve(s"presentation-$change-$priorSpec-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "implementation")
      // specName is past oracle — editing its spec dir needs no grant.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "implementation")
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/$specName/design.md")
      Files.writeString(target, "# design\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("past oracle"),
        s"missing past-oracle trace: $trace"
      )
    }

  test("a spec-dir target scans priors in implementation-order"):
    withTempDir("gate-specdir-priors") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      // The target spec is at oracle (the lock applies) and holds a
      // grant — only the ungranted PRIOR may refuse.
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "oracle")
      Files.writeString(sd.resolve(s"presentation-$change-$specName-${session.encoded}"), "h")
      Files.writeString(sd.resolve(s"grant-$change-$specName-${session.encoded}"), "g")
      // prior-spec: presented this session, never granted, unverified.
      Files.writeString(sd.resolve(s"presentation-$change-$priorSpec-${session.encoded}"), "h")
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "implementation")
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/$specName/design.md")
      Files.writeString(target, "# design\n")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(msg) =>
          assert(msg.contains(priorSpec), s"the refusal must name the prior: $msg")
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an ungranted prior must refuse the spec-dir edit, got $other")
    }

  test("a Step-0 progress edit with no presented specs is allowed"):
    withTempDir("gate-step0-clear") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "implementation")
      val target: Path =
        repo.resolve(s"openspec/changes/$change/implementation-progress.md")
      Files.writeString(target, "# progress\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("non-production path, allow"),
        s"missing non-production trace: $trace"
      )
    }

  test("a non-transition neither writes the phase file nor traces an advance"):
    withTempDir("gate-phase-stationary") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // specName is at oracle with no R3 rows — advancePhase(oracle,
      // no red, no green) stays oracle: nothing to write, nothing to
      // trace.
      val captured: (String, String, (String, Outcome[Int])) =
        StdoutCapture.captureBoth(
          runTraced(
            repo,
            List(
              "--event",
              "tool-call",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              repo.resolve("src/main/scala/A.scala").toString,
              "--tool",
              "Edit"
            ),
            Map.empty
          )
        )
      val stderr: String        = captured._2
      val trace: String         = captured._3._1
      val outcome: Outcome[Int] = captured._3._2
      assert(
        stderr.contains("oracle phase has not advanced") && stderr.endsWith("\n"),
        s"the text refusal prints the reason on stderr: $stderr"
      )
      outcome match
        case Outcome.Undetermined(reason: String) =>
          assert(
            reason.contains("oracle phase has not advanced"),
            s"the refusal names the unadvanced oracle phase: $reason"
          )
          assert(
            reason.contains("Run the test oracle first"),
            s"the refusal names the remedy: $reason"
          )
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an oracle-phase production edit must refuse, got $other")
      assert(
        trace.contains("refuse (oracle phase"),
        s"missing oracle-phase refusal trace: $trace"
      )
      assert(
        trace.contains("file mapped to spec test-spec via Expected Files table"),
        s"missing the ownership-resolution trace: $trace"
      )
      assert(
        !trace.contains("oracle →"),
        s"a stationary phase must not trace an advance: $trace"
      )
      assert(
        !Files.exists(sd.resolve(s"phase-$change-$specName")),
        "a stationary phase must not write the phase file"
      )
    }

  test("an implementation-phase prior is named by phase in the refusal"):
    withTempDir("gate-prior-impl") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "implementation")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      outcome match
        case Outcome.Undetermined(msg) =>
          assert(
            msg.contains(s"predecessor spec $priorSpec is implementation"),
            s"the refusal must carry the prior's phase token: $msg"
          )
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an unverified prior must refuse, got $other")
      assert(
        trace.contains(s"refuse (predecessor $priorSpec implementation"),
        s"missing refusal trace: $trace"
      )
    }

  test("post-bash traces an empty command and exit for a command-less payload"):
    withTempDir("gate-postbash-nocmd") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name"     -> ujson.Str("Bash"),
          "tool_input"    -> ujson.Obj(),
          "tool_response" -> ujson.Str("Error: Exit code 1"),
          "cwd"           -> ujson.Str(repo.toString)
        )
      )
      val tf: Path = repo.resolve("trace.log")
      val outcome: Outcome[Int] = GateCmd.run(
        ("--repo" +: repo.toString +: List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t"
        )).toArray,
        Map("PROBATIO_HOOKS_TRACE" -> tf.toString),
        () => Some(payload)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assertEquals(ledgerRows(repo).length, 0)
      assert(
        Files
          .readString(tf)
          .split("\n")
          .exists((l: String) => l.endsWith("post-bash: command='' exit=")),
        s"the resolved-pair trace must show empty fields: ${Files.readString(tf)}"
      )
    }

  test("the ambient row names the first presented-but-ungranted spec"):
    withTempDir("gate-postbash-granted") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      // A second spec dir that sorts BEFORE test-spec and is already
      // granted — the scan must skip it.
      Files.createDirectories(
        repo.resolve(s"openspec/changes/$change/specs/aaa-granted")
      )
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"presentation-$change-aaa-granted-${session.encoded}"), "h")
      Files.writeString(sd.resolve(s"grant-$change-aaa-granted-${session.encoded}"), "g")
      Files.writeString(sd.resolve(s"presentation-$change-$specName-${session.encoded}"), "h")
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      val row: ujson.Value = ledgerRows(repo).lastOption.getOrElse(
        sys.error("the ambient row must land in the ledger")
      )
      assertEquals(row.obj("spec").str, specName)
      assert(
        !row.obj.keySet.contains("session"),
        s"ambient rows carry no session: ${row.obj.keySet}"
      )
    }

  test("chain-state stderr is not merged into the JSON read"):
    withTempDir("gate-cs-stderr") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // stderr noise would corrupt the JSON parse if merged — the
      // predecessor discards chain-state's stderr.
      val stub: Path = repo.resolve("cs-noisy.sh")
      Files.writeString(stub, "echo CS-NOISE >&2\necho '{\"total\":0}'\n")
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      assertEquals(outcome, Outcome.Ran(0))
    }

  test("a non-JSON chain-state output is undetermined"):
    withTempDir("gate-cs-garbage") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val stub: Path = repo.resolve("cs-garbage.sh")
      Files.writeString(stub, "echo 'not json at all'\n")
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"non-JSON chain state must be undetermined, got $other")
    }

  test("non-conforming unresolved entries yield no names block"):
    withTempDir("gate-cs-mixed-names") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // jq's map is atomic: one non-conforming entry empties the whole
      // names block — the refusal still fires on the count.
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val stub: Path = repo.resolve("cs-mixed.sh")
      Files.writeString(
        stub,
        "echo '{\"total\":2,\"unresolved\":[{\"requirement\":\"obl-X\",\"reasons\":[\"r1\"]},{\"other\":1}]}'\n"
      )
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
      )
      outcome match
        case Outcome.Finding(msg) =>
          assert(
            !msg.contains("obl-X"),
            s"a non-conforming entry must void the whole names block: $msg"
          )
          assert(
            !msg.contains("Stryker"),
            s"the empty names block stays empty: $msg"
          )
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"unresolved entries must refuse, got $other")
    }

  test("a completion refusal emits the decision:block envelope under hook-json"):
    withTempDir("gate-completion-json-refusal") { (repo: Path) =>
      val stub: Path = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List("--event", "completion", "--format", "hook-json", "--session", "t"),
            Map("CHAIN_STATE_OVERRIDE" -> stub.toString)
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      val envelope: ujson.Value = ujson.read(out.trim)
      assertEquals(envelope.obj("decision").str, "block")
    }

  test("prompt-submit emits the UserPromptSubmit envelope under hook-json"):
    withTempDir("gate-prompt-envelope") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec"))
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          GateCmd.run(
            ("--repo" +: repo.toString +: List(
              "--event",
              "prompt-submit",
              "--format",
              "hook-json",
              "--session",
              "t"
            )).toArray,
            Map.empty,
            () => None
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      val envelope: ujson.Value = ujson.read(out.trim)
      assertEquals(
        envelope.obj("hookSpecificOutput").obj("hookEventName").str,
        "UserPromptSubmit"
      )
    }

  test("post-bash with no change dirs skips the record"):
    withTempDir("gate-postbash-nochange") { (repo: Path) =>
      // openspec/changes exists but is empty — firstActiveChange finds
      // nothing to attribute a row to.
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec/changes"))
      Files.createDirectories(stateDir(repo))
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "post-bash",
          "--format",
          "text",
          "--session",
          "t",
          "--command",
          "sbt adk4s-core/test",
          "--exit",
          "0"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("missing required fields"),
        s"missing no-change trace: $trace"
      )
    }

  test("--check-installed with --repo does not read the channel"):
    withTempDir("gate-probe-nochannel") { (repo: Path) =>
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec"))
      Files.createDirectories(stateDir(repo))
      val read: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      val channel: () => Option[String] =
        () =>
          read.set(true)
          Some("{}")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          GateCmd.run(
            Array("--check-installed", "--repo", repo.toString),
            Map.empty,
            channel
          )
        )
      assert(
        !read.get(),
        "an explicit --repo must not touch the input channel"
      )
      assert(!ujson.read(out.trim).obj("installed").bool, s"no heartbeat: $out")
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the probe must exit 0, got $other")
    }

  // ── Ring 5 PASS B survivor remediation, round 4 ─────────────────────
  // Pinpoint tests for the flag/env-resolution mutants, the
  // default-scanner fallback paths, the grant-lock `applies` arms, and
  // output-envelope exactness — each asserts the observable contract a
  // survived mutant violated.

  test("a read-only tool on a non-production path is allowed and traced"):
    withTempDir("gate-readonly-nonprod") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name" -> ujson.Str("Read"),
          "tool_input" -> ujson.Obj(
            "file_path" -> ujson.Str(repo.resolve("notes.md").toString)
          ),
          "cwd" -> ujson.Str(repo.toString)
        )
      )
      val tf: Path = repo.resolve("trace.log")
      val outcome: Outcome[Int] = GateCmd.run(
        ("--repo" +: repo.toString +: List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t"
        )).toArray,
        Map("PROBATIO_HOOKS_TRACE" -> tf.toString),
        () => Some(payload)
      )
      assertEquals(outcome, Outcome.Ran(0))
      val trace: String = Files.readString(tf)
      assert(
        trace.contains("read-only tool 'Read', allow — "),
        s"missing non-production read-only trace: $trace"
      )
      assert(
        !trace.contains("on production path"),
        s"a non-production file must not take the production arm: $trace"
      )
    }

  test("VERIFIED_SCALA3_ALLOW_PATHS does not waive the Step-0 grant lock"):
    withTempDir("gate-allow-nonprod") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // The allowlist is consulted only for production paths — a
      // non-production Step-0 signature falls through to the grant lock.
      val outcome: Outcome[Int] = runGate(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve(s"openspec/changes/$change/implementation-progress.md").toString,
          "--tool",
          "Edit"
        ),
        Map("VERIFIED_SCALA3_ALLOW_PATHS" -> repo.toString)
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"the allowlist must not waive the grant lock, got $other")
    }

  test("VERIFIED_SCALA3_ALLOW_PATHS honors colon-separated prefixes"):
    withTempDir("gate-allow-colon") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "src/main/scala/A.scala")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      val prefix: String = repo.resolve("src/main").toString
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map("VERIFIED_SCALA3_ALLOW_PATHS" -> s"/nonexistent:$prefix")
      )
      assertEquals(outcome, Outcome.Ran(0))
      // The second colon-separated prefix matched — the trace names it.
      assert(
        trace.contains(s"allow-listed path $prefix"),
        s"the matching prefix must be traced: $trace"
      )
    }

  test("an absent tool name resolves to the predecessor's empty (read-only) name"):
    withTempDir("gate-no-tool") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // No --tool, no --file, no payload — the predecessor's `""` tool
      // name is inside its read-only case, so the call allows.
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "tool-call", "--format", "text", "--session", "t"),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
    }

  test("a non-string tool_name reads as the empty name"):
    withTempDir("gate-nonstring-tool") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      // jq's `// empty` yields "" for a non-string field — the empty
      // name is read-only, so the call allows even with a presented
      // spec that would block a writable tool.
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name"     -> ujson.Num(123),
          "tool_input"    -> ujson.Obj(),
          "tool_response" -> ujson.Obj(),
          "cwd"           -> ujson.Str(repo.toString)
        )
      )
      val outcome: Outcome[Int] = runGatePayload(
        repo,
        List("--event", "tool-call", "--format", "text", "--session", "t"),
        Map.empty,
        payload
      )
      assertEquals(outcome, Outcome.Ran(0))
    }

  test("an untracked spec-dir target sorting after a presented spec still skips the lock"):
    withTempDir("gate-specdir-sorted") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      // test-spec sorts before zzz-new: if the grant scan ran it would
      // find the presented-but-ungranted test-spec in the priors.
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/zzz-new/design.md")
      Files.createDirectories(target.getParent)
      Files.writeString(target, "# new\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("has no phase file"),
        s"missing untracked-spec trace: $trace"
      )
      assert(
        trace.contains("non-production path, allow") &&
          trace.contains("specs/zzz-new/design.md"),
        s"missing the non-production allow trace: $trace"
      )
    }

  test("a past-oracle spec-dir target sorting after a presented spec skips the lock"):
    withTempDir("gate-specdir-sorted-past") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(
        sd.resolve(s"presentation-$change-$specName-${SessionId.fromRaw("t").encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"phase-$change-zzz-old"), "implementation")
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/zzz-old/design.md")
      Files.createDirectories(target.getParent)
      Files.writeString(target, "# design\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("past oracle"),
        s"missing past-oracle trace: $trace"
      )
    }

  test("a production edit with no active change is allowed and traced"):
    withTempDir("gate-no-change-tool") { (repo: Path) =>
      gitInit(repo)
      // openspec/changes exists but is empty — firstActiveChange finds
      // nothing, so the oracle lock allows outright.
      Files.createDirectories(repo.resolve("openspec/changes"))
      Files.createDirectories(repo.resolve("src/main/scala"))
      Files.writeString(repo.resolve("src/main/scala/A.scala"), "object A\n")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("no active change, allow"),
        s"missing no-active-change trace: $trace"
      )
    }

  test("a production edit with every spec verified is allowed and traced"):
    withTempDir("gate-all-verified") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "unrelated.txt")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "verified")
      // The file is not in Expected Files and no spec is non-verified —
      // the fallback scan resolves no active spec.
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains(s"all specs verified for $change"),
        s"missing all-verified trace: $trace"
      )
    }

  test("a production edit on an unmapped file falls back to the first non-verified spec"):
    withTempDir("gate-fallback-spec") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      writeImplOrder(repo, "unrelated.txt")
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"phase-$change-$priorSpec"), "verified")
      // prior-spec is verified AND checkpointed — the presentation
      // marker from an earlier session satisfies the priors check.
      Files.writeString(
        sd.resolve(s"presentation-$change-$priorSpec-${SessionId.fromRaw("u").encoded}"),
        "h"
      )
      Files.writeString(sd.resolve(s"phase-$change-$specName"), "implementation")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          repo.resolve("src/main/scala/A.scala").toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains(
          s"file not in Expected Files table, fallback to first non-verified: $specName"
        ),
        s"missing fallback trace: $trace"
      )
    }

  test("post-edit resolves the payload file_path when --file is absent"):
    withTempDir("gate-postedit-payloadfile") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      Files.createDirectories(repo.resolve("src/main/scala"))
      val target: Path = repo.resolve("src/main/scala/A.scala")
      Files.writeString(target, "object A\n")
      val stub: Path = repo.resolve("ds-stub.sh")
      Files.writeString(stub, "echo DANGER-PAYLOAD-OUT\n")
      val payload: String = ujson.write(
        ujson.Obj(
          "tool_name" -> ujson.Str("Edit"),
          "tool_input" -> ujson.Obj(
            "file_path" -> ujson.Str(target.toString)
          ),
          "cwd" -> ujson.Str(repo.toString)
        )
      )
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGatePayload(
            repo,
            List("--event", "post-edit", "--format", "text", "--session", "t"),
            Map("DANGER_SCAN_OVERRIDE" -> stub.toString),
            payload
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("DANGER-PAYLOAD-OUT"),
        s"the payload-supplied file must reach the scanner: $out"
      )
    }

  test("post-edit runs the repo's default danger-scan when no override is set"):
    withTempDir("gate-postedit-default-ds") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val scannerDir: Path =
        repo.resolve("openspec/schemas/verified-scala3/scanner")
      Files.createDirectories(scannerDir)
      Files.writeString(
        scannerDir.resolve("danger-scan.sh"),
        "echo DANGER-DEFAULT-OUT\n"
      )
      Files.createDirectories(repo.resolve("src/main/scala"))
      val target: Path = repo.resolve("src/main/scala/A.scala")
      Files.writeString(target, "object A\n")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              target.toString
            ),
            Map.empty
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("DANGER-DEFAULT-OUT"),
        s"the default scanner must run from the schema dir: $out"
      )
    }

  test("post-edit runs the repo's default spec-lint when no override is set"):
    withTempDir("gate-postedit-default-lint") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val scannerDir: Path =
        repo.resolve("openspec/schemas/verified-scala3/scanner")
      Files.createDirectories(scannerDir)
      Files.writeString(
        scannerDir.resolve("spec-lint.sh"),
        "echo LINT-DEFAULT-OUT\n"
      )
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/$specName/spec.md")
      Files.writeString(target, "# spec\n")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              target.toString
            ),
            Map.empty
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("LINT-DEFAULT-OUT"),
        s"the default spec-lint must run from the schema dir: $out"
      )
      assert(
        out.contains(s"spec-lint ($change)"),
        s"the finding names the resolved change: $out"
      )
    }

  test("post-edit findings keep their line structure in text output"):
    withTempDir("gate-postedit-lines") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val stub: Path = repo.resolve("ds-lines.sh")
      Files.writeString(stub, "printf 'LINE-ONE\\nLINE-TWO\\n'\n")
      Files.createDirectories(repo.resolve("src/main/scala"))
      val target: Path = repo.resolve("src/main/scala/A.scala")
      Files.writeString(target, "object A\n")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "text",
              "--session",
              "t",
              "--file",
              target.toString
            ),
            Map("DANGER_SCAN_OVERRIDE" -> stub.toString)
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("LINE-ONE\nLINE-TWO"),
        s"multi-line findings keep their newlines: $out"
      )
      assert(out.endsWith("\n"), s"the report ends with a newline: $out")
    }

  test("post-edit hook-json output ends with a newline"):
    withTempDir("gate-postedit-json-nl") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val stub: Path = repo.resolve("ds-nl.sh")
      Files.writeString(stub, "echo HOOK-NL-OUT\n")
      Files.createDirectories(repo.resolve("src/main/scala"))
      val target: Path = repo.resolve("src/main/scala/A.scala")
      Files.writeString(target, "object A\n")
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List(
              "--event",
              "post-edit",
              "--format",
              "hook-json",
              "--session",
              "t",
              "--file",
              target.toString
            ),
            Map("DANGER_SCAN_OVERRIDE" -> stub.toString)
          )
        )
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.endsWith("}\n"), s"the envelope line ends with a newline: $out")
    }

  test("the completion trace names the event"):
    withTempDir("gate-completion-trace-event") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        trace.contains("event=completion"),
        s"the trace prologue names the completion event: $trace"
      )
    }

  test("completion reads the ledger in-core and runs the repo's default chain-state scanner"):
    withTempDir("gate-completion-defaults") { (repo: Path) =>
      val (base: String, _: Path) = mkCompletionFixture(repo)
      // An uncorroborated green row at the current baseline — the
      // corroboration verdict is computed IN-CORE from the ledger; no
      // reconcile scanner is resolved at any path (the defect repair).
      Files.writeString(
        ledgerFile(repo),
        ledgerRowJson(0, base, "sbt test", "obl-lonely", ambient = false) + "\n"
      )
      // The chain-state leg still resolves the repo's default
      // chain-state.sh under the schema's scanner dir — no override env.
      val scannerDir: Path =
        repo.resolve("openspec/schemas/verified-scala3/scanner")
      Files.createDirectories(scannerDir)
      val chainState: Path = scannerDir.resolve("chain-state.sh")
      Files.writeString(chainState, "echo '{\"total\":0}'\n")
      chainState.toFile.setExecutable(true)
      val (out: String, outcome: Outcome[Int]) =
        StdoutCapture.captureOut(
          runGate(
            repo,
            List("--event", "completion", "--format", "text", "--session", "t"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Finding(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an uncorroborated green row must refuse, got $other")
      assert(
        out.contains("obl-lonely"),
        s"the refusal names the uncorroborated row: $out"
      )
    }

  test("non-string payload fields read as empty — jq // empty parity"):
    // `str()` reads `// empty` semantics: a non-string `tool_name`/`cwd`
    // yields "" — a garbage string would poison repo resolution and the
    // tool-name tier, so the parse surface itself is pinned here.
    val parsed: Option[HarnessPayload] = HarnessPayloadReader.parse(
      ujson.write(
        ujson.Obj(
          "tool_name"  -> ujson.Num(7),
          "cwd"        -> ujson.Arr(ujson.Str("x")),
          "tool_input" -> ujson.Obj("file_path" -> ujson.Str("f"))
        )
      )
    )
    parsed match
      case Some(p: HarnessPayload) =>
        assertEquals(p.toolName, "")
        assertEquals(p.cwd, "")
      case None => // danger-scan:allow test assertion — an object payload must parse
        fail("a JSON object payload must parse to Some")

  test("a same-session grant short-circuits the cross-session grant scan"):
    withTempDir("gate-grant-shortcircuit") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      // specName precedes zzz-new in the spec order, is presented AND
      // granted for this session — and a second session also holds a
      // grant. `if sessionGrant then None` must skip the any-session
      // scan: the predecessor emits no "prior session" line when this
      // session's own grant satisfies.
      Files.createDirectories(
        repo.resolve("openspec/changes").resolve(change).resolve("specs").resolve(specName)
      )
      Files.writeString(sd.resolve(s"presentation-$change-$specName-${session.encoded}"), "h")
      Files.writeString(sd.resolve(s"grant-$change-$specName-${session.encoded}"), "g")
      Files.writeString(sd.resolve(s"grant-$change-$specName-priorsession"), "g")
      // zzz-new must be at oracle — untracked or past-oracle targets
      // exempt the grant lock before the scan runs.
      Files.writeString(sd.resolve(s"phase-$change-zzz-new"), "oracle")
      val target: Path =
        repo.resolve(s"openspec/changes/$change/specs/zzz-new/design.md")
      Files.createDirectories(target.getParent)
      Files.writeString(target, "# new\n")
      val (trace: String, outcome: Outcome[Int]) = runTraced(
        repo,
        List(
          "--event",
          "tool-call",
          "--format",
          "text",
          "--session",
          "t",
          "--file",
          target.toString,
          "--tool",
          "Edit"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(trace.contains("grant satisfied"), s"missing grant-satisfied trace: $trace")
      assert(
        !trace.contains("prior session"),
        s"a same-session grant must not scan prior sessions: $trace"
      )
    }

  // ── gate-event-compatibility (spec 4 of repair-probatio-cutover) ──
  //
  // The dispatch-compatibility oracle: every recognised name reaches its
  // own tier, every other name routes to the injection tier and
  // terminates clean (never an error status), and the supplied name
  // survives into the diagnostic output. Derived from the SPEC and the
  // predecessor's fallthrough — NOT from the implementation.
  //
  // spec: gate-event-compatibility — all Requirements, all Properties

  // ── Scenario: Happy path — each recognised name reaches its tier
  // spec: gate-event-compatibility — Scenario: Happy path — each recognised name reaches its tier

  test("each recognised event name dispatches to its own tier"):
    EventDispatchFixtures.recognisedPairs.foreach { case (name: String, expected: GateEvent) =>
      EventDispatch.classify(name) match
        case EventDispatch.Tier(event) =>
          assertEquals(event, expected, s"'$name' must dispatch to $expected")
        case EventDispatch.Injection(
              _
            ) => // danger-scan:allow test assertion — a recognised name on injection fails the test
          fail(s"recognised name '$name' routed to the injection tier")
    }
    // no two names reach the same tier — the mapping is injective
    val events: List[GateEvent] =
      EventDispatchFixtures.recognisedPairs.map((p: (String, GateEvent)) => p._2)
    assertEquals(events.distinct.length, events.length, "two recognised names share a tier")

  test("each recognised event name runs its tier end-to-end without the injection diagnostic"):
    // Decision-level pinning alone leaves the dispatch→tier hand-off
    // unobserved: run every recognised name through the gate and assert
    // a tier outcome with NO unrecognised-name diagnostic — the
    // observable that separates a tier run from an injection run.
    EventDispatchFixtures.recognisedPairs.foreach { case (name: String, event: GateEvent) =>
      withTempDir("gate-recognised-tier") { (repo: Path) =>
        mkRepo(repo, withGit = false)
        val (_: String, err: String, outcome: Outcome[Int]) =
          StdoutCapture.captureBoth(
            runGate(
              repo,
              List("--event", name, "--format", "text", "--session", "t"),
              Map.empty
            )
          )
        outcome match
          case Outcome.Ran(_)     => () // the tier allowed
          case Outcome.Finding(_) => () // the tier blocked — also a tier outcome
          case Outcome.Undetermined(reason) =>
            fail(s"'$name' ($event) must be determinable in a minimal repo, got: $reason")
        assert(
          !err.contains("unrecognised") && !err.contains("not recognised"),
          s"recognised name '$name' produced an unrecognised-name diagnostic: $err"
        )
      }
    }

  // ── Scenario: Adversarial — a recognised name is not absorbed by the injection tier
  // spec: gate-event-compatibility — Scenario: Adversarial — a recognised name is not absorbed by the injection tier

  test("the pre-execution tier's recognised name is not absorbed by the injection tier"):
    // `tool-call` is the pre-execution tier's name.
    EventDispatch.classify("tool-call") match
      case EventDispatch.Tier(GateEvent.ToolCall) => ()
      case other => // danger-scan:allow test assertion — the misclassification fails the test
        fail(s"'tool-call' must classify as Tier(ToolCall), got $other")
    withTempDir("gate-toolcall-tier") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (_: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "tool-call", "--format", "text", "--session", "t"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(_)     => ()
        case Outcome.Finding(_) => ()
        case Outcome.Undetermined(reason) =>
          fail(s"the pre-execution tier must be determinable, got: $reason")
      assert(
        !err.contains("unrecognised") && !err.contains("not recognised"),
        s"a recognised name produced an unrecognised-name diagnostic: $err"
      )
    }

  // ── Scenario: Happy path — the alternate prompt-event name injects context
  // spec: gate-event-compatibility — Scenario: Happy path — the alternate prompt-event name injects context

  test("the alternate prompt-event name routes to the injection tier"):
    // `user-prompt-submit` — the recorded devin adapter name
    // (workflow-hygiene.bats). It is NOT `prompt-submit`: the injection
    // tier must run, the diagnostic must name the supplied name, none of
    // prompt-submit's side effects (grant writes) may fire, and the
    // heartbeat records the supplied name verbatim (predecessor $EVENT).
    withTempDir("gate-alt-prompt") { (repo: Path) =>
      mkRepo(repo, withGit = true)
      val sd: Path           = stateDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      Files.createDirectories(sd)
      Files.writeString(sd.resolve(s"presentation-$change-$specName-${session.encoded}"), "h")
      val (out: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "user-prompt-submit", "--format", "text", "--session", "t"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(0)               => ()
        case Outcome.Ran(n)               => fail(s"the alternate name must exit clean, got Ran($n)")
        case Outcome.Finding(msg)         => fail(s"the alternate name must not error: $msg")
        case Outcome.Undetermined(reason) => fail(s"the alternate name must not be undetermined: $reason")
      assert(
        err.contains("unrecognised event 'user-prompt-submit'"),
        s"the diagnostic output must name the supplied event: $err"
      )
      assert(out.nonEmpty, "the injection tier ran — the banner emitted")
      assert(
        !Files.exists(sd.resolve(s"grant-$change-$specName-${session.encoded}")),
        "an injection must not write prompt-submit's grant tokens"
      )
      val heartbeat: Option[HeartbeatRecord] =
        GateStateDirReader.resolve(repo).flatMap(GateStateDirReader.readHeartbeat)
      heartbeat match
        case Some(record) => assertEquals(record.event, "user-prompt-submit")
        case None         => fail("the injection run must record a heartbeat")
    }

  // ── Scenario: Edge case — an empty event name routes to the injection tier
  // spec: gate-event-compatibility — Scenario: Edge case — an empty event name routes to the injection tier

  test("an empty event name routes to the injection tier"):
    withTempDir("gate-empty-event") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (out: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "", "--format", "text"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(0)               => ()
        case Outcome.Ran(n)               => fail(s"an empty event name must exit clean, got Ran($n)")
        case Outcome.Finding(msg)         => fail(s"an empty event name must not error: $msg")
        case Outcome.Undetermined(reason) => fail(s"an empty event name must not be undetermined: $reason")
      assert(
        err.nonEmpty && err.contains("unrecognised"),
        s"the injection diagnostic must state the name was not recognised: $err"
      )
      assert(out.nonEmpty, "the injection tier ran — the banner emitted")
    }

  // ── Scenario: Happy path — an unrecognised name is named in the diagnostic
  // spec: gate-event-compatibility — Scenario: Happy path — an unrecognised name is named in the diagnostic

  test("an unrecognised event name is named in the diagnostic output"):
    withTempDir("gate-named-diagnostic") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (_: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "xyzzy-plugh", "--format", "text"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — a non-clean outcome fails the test
          fail(s"an unrecognised name must exit clean, got $other")
      assert(
        err.contains("'xyzzy-plugh'"),
        s"the diagnostic output must reproduce the supplied name: $err"
      )
    }

  test("the injection fallback is recorded in the opt-in trace"):
    withTempDir("gate-injection-trace") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (trace: String, outcome: Outcome[Int]) =
        runTraced(
          repo,
          List("--event", "xyzzy-plugh", "--format", "text", "--session", "t"),
          Map.empty
        )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — a non-clean outcome fails the test
          fail(s"an unrecognised name must exit clean, got $other")
      assert(
        trace.contains("injection: unrecognised event 'xyzzy-plugh'"),
        s"the trace must record the fallback with the supplied name: $trace"
      )
    }

  // ── Scenario: Adversarial — a recognised name produces no unrecognised-name diagnostic
  // spec: gate-event-compatibility — Scenario: Adversarial — a recognised name produces no unrecognised-name diagnostic

  test("a recognised event name produces no unrecognised-name diagnostic"):
    withTempDir("gate-no-diagnostic") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (_: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "prompt-submit", "--format", "text", "--session", "t"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — a non-clean outcome fails the test
          fail(s"prompt-submit should exit clean in a minimal repo, got $other")
      assert(
        !err.contains("unrecognised") && !err.contains("not recognised"),
        s"a recognised name produced an unrecognised-name diagnostic: $err"
      )
    }

  // ── Envelope fidelity: an injection under hook-json names SessionStart
  // spec: gate-event-compatibility — Requirement: The payload envelope reports the harness event name
  // The predecessor's `*)` arm maps every unrecognised name to
  // SessionStart — the wildcard envelope branch this spec preserves.

  test("an injection under hook-json emits the SessionStart envelope"):
    withTempDir("gate-injection-envelope") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (out: String, _: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", "xyzzy-plugh", "--format", "hook-json"),
            Map.empty
          )
        )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — a non-clean outcome fails the test
          fail(s"an unrecognised name must exit clean, got $other")
      ujson.read(out.trim)("hookSpecificOutput")("hookEventName") match
        case ujson.Str("SessionStart") => ()
        case other => // danger-scan:allow test assertion — a wrong envelope name fails the test
          fail(s"the injection envelope must name SessionStart (predecessor `*)`), got: $other")
    }

  // ── Property: event-dispatch-is-total
  // spec: gate-event-compatibility — Property: event-dispatch-is-total

  property("event-dispatch-is-total"):
    for name <- EventDispatchFixtures.genEventName.forAll
    yield
      val dispatch: EventDispatch = EventDispatch.classify(name)
      val carriesName: Boolean = dispatch match
        case EventDispatch.Injection(supplied) => supplied == name
        case EventDispatch.Tier(_)             => true
      Result.all(
        List(
          Result
            .assert(dispatch.isTier || dispatch.isInjection)
            .log(s"classify('$name') = $dispatch"),
          Result
            .assert(carriesName)
            .log(s"the injection lost the supplied name '$name'")
        )
      )

  // ── Property: recognised-names-never-fall-back
  // spec: gate-event-compatibility — Property: recognised-names-never-fall-back
  //
  // Enumerated, not sampled — the domain is the closed six-name set, so
  // the property loops over the full enumeration. The shipped table is
  // additionally compared to the oracle's own closed set, so a drift in
  // the implementation's table fails even when its self-consistency
  // still holds.

  property("recognised-names-never-fall-back"):
    for _ <- Gen.constant(()).forAll
    yield
      val names: List[String] = EventDispatch.recognisedNames
      val oracleNames: List[String] =
        EventDispatchFixtures.recognisedPairs.map((p: (String, GateEvent)) => p._1)
      Result.all(
        List(
          Result
            .assert(names.forall((n: String) => EventDispatch.classify(n).isTier))
            .log("a recognised name fell back to the injection tier"),
          Result
            .assert(names.map(EventDispatch.classify).distinct.length == names.length)
            .log("the name→tier mapping is not injective"),
          Result
            .assert(names.sorted == oracleNames.sorted)
            .log(s"the recognised set drifted: shipped=$names oracle=$oracleNames")
        )
      )

  // ── Property: unrecognised-names-exit-clean
  // spec: gate-event-compatibility — Property: unrecognised-names-exit-clean

  property("unrecognised-names-exit-clean"):
    for name <- EventDispatchFixtures.genUnrecognisedName.forAll
    yield withTempDir("gate-unrecognised-prop") { (repo: Path) =>
      mkRepo(repo, withGit = false)
      val (_: String, err: String, outcome: Outcome[Int]) =
        StdoutCapture.captureBoth(
          runGate(
            repo,
            List("--event", name, "--format", "text", "--session", "t"),
            Map.empty
          )
        )
      // `contains("")` is vacuously true — the empty name's diagnostic is
      // the presence of the unrecognised-name line itself.
      val named: Boolean =
        if name.isEmpty then err.contains("unrecognised") else err.contains(s"'$name'")
      Result.all(
        List(
          Result
            .assert(outcome == Outcome.Ran(0))
            .log(s"'$name' did not terminate clean: $outcome"),
          Result
            .assert(named)
            .log(s"the diagnostic did not name '$name': $err")
        )
      )
    }

  // ── spec: hermetic-test-processes ─────────────────────────────────
  // The two adversarial scenarios for the spawned-tool boundary: an
  // inherited harness session and an undeclared control variable must
  // never reach the tool.

  // spec: hermetic-test-processes — Scenario: Adversarial — an inherited harness session does not reach the tool
  test("an inherited harness session does not reach the tool"):
    withTempDir("gate-hermetic-session") { (repo: Path) =>
      // The invoking shell carries the harness session variable; the test
      // declares a different session for the tool.
      val stub: Path = mkRefusingRepo(repo, SessionId.fromRaw("declared-session"))
      val env: HermeticEnv = HermeticEnv.buildWithExtras(
        Map(
          ControlledVariable.VerifiedScala3SessionId -> "declared-session",
          ControlledVariable.ChainStateOverride      -> stub.toString
        ),
        Map("CLAUDE_CODE_SESSION_ID" -> "foreign-harness-session")
      )
      // The boundary: the harness variable cannot be in the tool's environment.
      assert(
        !env.toMap.contains("CLAUDE_CODE_SESSION_ID"),
        "the inherited harness session variable must not survive the build"
      )
      // No --session flag: the tool resolves its session from the
      // environment alone — the channel the harness variable would win.
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--stop-hook-active", "false"),
        env.toMap
      )
      outcome match
        case Outcome.Finding(_) => ()
        case other              => fail(s"completion should refuse, got $other")
      // The resolved session is observable: the refusal marker is keyed by
      // it. It must be keyed by the declared session, never the inherited one.
      val sd: Path = stateDir(repo)
      assert(
        Files.exists(sd.resolve(s"completion-refused-${SessionId.fromRaw("declared-session").encoded}")),
        "the refusal marker must be keyed by the declared session"
      )
      assert(
        !Files.exists(sd.resolve(s"completion-refused-${SessionId.fromRaw("foreign-harness-session").encoded}")),
        "the inherited harness session must never reach the tool"
      )
    }

  // spec: hermetic-test-processes — Scenario: Adversarial — an undeclared workflow control variable does not reach the tool
  test("an undeclared workflow control variable does not reach the tool"):
    withTempDir("gate-hermetic-hooks") { (repo: Path) =>
      // The invoking shell disables the workflow's hooks; the test does
      // not declare the variable.
      val stub: Path = mkRefusingRepo(repo, SessionId.fromRaw("t"))
      val env: HermeticEnv = HermeticEnv.buildWithExtras(
        Map(ControlledVariable.ChainStateOverride -> stub.toString),
        Map("VERIFIED_SCALA3_HOOKS" -> "off", "PROBATIO_HOOKS" -> "off")
      )
      assert(
        !env.toMap.contains("VERIFIED_SCALA3_HOOKS") && !env.toMap.contains("PROBATIO_HOOKS"),
        "the hook-control variable must not survive the build undeclared"
      )
      // The escape hatch reads the same environment the session reads. If
      // it leaked, the refusal would be bypassed — a Finding here is the
      // evidence the gate ran its tiers instead.
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "completion", "--format", "text", "--session", "t"),
        env.toMap
      )
      outcome match
        case Outcome.Finding(_) => ()
        case Outcome.Ran(0)     => fail("the inherited hooks=off reached the tool and bypassed the tier")
        case other              => fail(s"expected the refusal, got $other")
    }
