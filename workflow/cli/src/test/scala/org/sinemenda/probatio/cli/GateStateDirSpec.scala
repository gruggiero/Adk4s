package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Gen
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.*
import org.sinemenda.probatio.migration.HermeticEnv

import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.withTempDir

/**
 * Test oracle for the gate-event-completeness spec — gate state.
 *
 * The blocking tiers read the predecessor's state surface (phase files,
 * presentation markers, grants, refusal markers) and fail OPEN when it
 * is unavailable. The installation probe reports whether the gate has
 * run — it looks for evidence the gate itself wrote, not for the hooks
 * configuration.
 *
 * spec: gate-event-completeness — Requirement: The blocking tiers consult repository state and fail open when it is unavailable
 * spec: gate-event-completeness — Requirement: The installation probe reports whether the gate has run
 * spec: gate-event-completeness — Property: unreadable-state-allows
 */
final class GateStateDirSpec extends ProbatioCliSuite:

  private val change: String   = "test-change"
  private val specName: String = "test-spec"

  private def gitInit(dir: Path): Unit =
    val code: Int =
      HermeticEnv.run(List("git", "-C", dir.toString, "init", "-q"), HermeticEnv.empty)
    assertEquals(code, 0, "git init must succeed")

  private def stateDir(repo: Path): Path =
    repo.resolve(".git").resolve("verified-scala3-gate")

  private def runGate(repo: Path, args: List[String]): Outcome[Int] =
    GateCmd.run(("--repo" +: repo.toString +: args).toArray, Map.empty, () => None)

  // ── Scenario: blocking decisions derive from the read state ─────────
  // spec: gate-event-completeness — Requirement: The blocking tiers consult repository state and fail open when it is unavailable

  test("readPhase returns the persisted phase, defaulting to Oracle"):
    withTempDir("gate-state-phase") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir =
        GateStateDirReader.resolve(repo).getOrElse(fail("state dir must resolve in a git repo"))
      Files.createDirectories(dir.path)
      assertEquals(GateStateDirReader.readPhase(dir, change, specName), SpecPhase.Oracle)
      Files.writeString(dir.path.resolve(s"phase-$change-$specName"), "implementation")
      assertEquals(GateStateDirReader.readPhase(dir, change, specName), SpecPhase.Implementation)
      // An unrecognised phase token fails safe to Oracle, never crashes.
      Files.writeString(dir.path.resolve(s"phase-$change-$specName"), "bogus")
      assertEquals(GateStateDirReader.readPhase(dir, change, specName), SpecPhase.Oracle)
    }

  test("grant and presentation reads reflect the written markers"):
    withTempDir("gate-state-markers") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir =
        GateStateDirReader.resolve(repo).getOrElse(fail("state dir must resolve"))
      Files.createDirectories(dir.path)
      val session: SessionId = SessionId.fromRaw("t")
      assert(!GateStateDirReader.hasGrant(dir, change, specName, session))
      assert(!GateStateDirReader.hasSessionPresentation(dir, session))
      Files.writeString(dir.path.resolve(s"presentation-$change-$specName-${session.encoded}"), "h")
      assert(GateStateDirReader.hasSessionPresentation(dir, session))
      Files.writeString(dir.path.resolve(s"grant-$change-$specName-${session.encoded}"), "g")
      assert(GateStateDirReader.hasGrant(dir, change, specName, session))
    }

  test("session-scoped refusal markers are written, read, and cleared"):
    withTempDir("gate-state-refusals") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir =
        GateStateDirReader.resolve(repo).getOrElse(fail("state dir must resolve"))
      Files.createDirectories(dir.path)
      val session: SessionId = SessionId.fromRaw("t")
      assert(!GateStateDirReader.hasRefusal(dir, RefusalKind.Completion, session))
      GateStateDirReader.writeRefusal(dir, RefusalKind.Completion, session)
      assert(GateStateDirReader.hasRefusal(dir, RefusalKind.Completion, session))
      // The predecessor's refusal marker is an EMPTY file — only its
      // name carries meaning.
      assertEquals(
        Files.readString(
          GateStateDirReader.refusalFile(dir, RefusalKind.Completion, session)
        ),
        ""
      )
      GateStateDirReader.clearRefusals(dir, session)
      assert(!GateStateDirReader.hasRefusal(dir, RefusalKind.Completion, session))
    }

  // ── Scenario: an unreadable state directory allows rather than refusing
  // spec: gate-event-completeness — Scenario: an unreadable state directory allows rather than refusing

  test("a state path that is not a directory resolves to fail-open"):
    withTempDir("gate-state-unreadable") { (repo: Path) =>
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec"))
      // A FILE where the state dir should be: resolution/creation fails,
      // and every event must allow rather than refuse.
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
          repo.resolve("src/A.scala").toString,
          "--tool",
          "Edit"
        )
      )
      outcome match
        case Outcome.Ran(0)          => ()
        case Outcome.Ran(n)          => fail(s"unreadable state must allow, got Ran($n)")
        case Outcome.Finding(msg)    => fail(s"unreadable state must allow, got: $msg")
        case Outcome.Undetermined(r) => fail(s"unreadable state must allow, got: $r")
    }

  // ── Property: unreadable-state-allows ───────────────────────────────
  // spec: gate-event-completeness — Property: unreadable-state-allows

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  property("unreadable-state-allows", coverConfig):
    for
      // `genBlockingEvent`: the two Tier-A events that can refuse.
      eventToken <- Gen.element1("tool-call", "completion").forAll
      // `genUnreadableStateShape`: the state directory is absent (no
      // git — it cannot resolve), present-but-empty, or
      // present-but-unreadable (a file occupies its path — resolution
      // fails).
      shape <- Gen
        .element1("absent", "empty", "unreadable")
        .forAll
        .cover(25, "absent-directory", (s: String) => s == "absent")
        .cover(25, "present-but-empty", (s: String) => s == "empty")
        .cover(25, "present-but-unreadable", (s: String) => s == "unreadable")
    yield withTempDir("gate-unreadable-prop") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec"))
      if shape != "absent" then gitInit(repo)
      if shape == "empty" then Files.createDirectories(stateDir(repo))
      if shape == "unreadable" then Files.writeString(stateDir(repo), "not-a-directory")
      val args: List[String] =
        if eventToken == "tool-call" then
          List(
            "--event",
            eventToken,
            "--format",
            "text",
            "--session",
            "t",
            "--file",
            repo.resolve("src/A.scala").toString,
            "--tool",
            "Edit"
          )
        else List("--event", eventToken, "--format", "text", "--session", "t")
      val outcome: Outcome[Int] = runGate(repo, args)
      outcome match
        case Outcome.Ran(0) => Result.success
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          Result.failure.log(s"$eventToken with $shape state gave $other")
    }

  // ── Scenarios: the installation probe ───────────────────────────────
  // spec: gate-event-completeness — Requirement: The installation probe reports whether the gate has run

  test("a repository where the gate has run reports installed"):
    withTempDir("gate-probe-ran") { (repo: Path) =>
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec"))
      // The gate's own evidence of having run: a heartbeat in STATE_DIR.
      val sd: Path = stateDir(repo)
      Files.createDirectories(sd)
      Files.writeString(sd.resolve("heartbeat"), "{}")
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          GateCmd.run(
            Array("--check-installed", "--repo", repo.toString),
            Map.empty,
            () => None
          )
        )
      val installed: Boolean = ujson.read(out.trim).obj("installed").bool
      assert(installed, s"expected installed=true, got: $out")
    }

  test("a repository where the gate has never run reports not installed"):
    withTempDir("gate-probe-never") { (repo: Path) =>
      gitInit(repo)
      Files.createDirectories(repo.resolve("openspec"))
      // No state dir at all — the gate has never run here.
      val (out: String, _: Outcome[Int]) =
        StdoutCapture.captureOut(
          GateCmd.run(
            Array("--check-installed", "--repo", repo.toString),
            Map.empty,
            () => None
          )
        )
      val installed: Boolean = ujson.read(out.trim).obj("installed").bool
      assert(!installed, s"expected installed=false, got: $out")
    }

  test("a repository outside the workflow writes no state"):
    withTempDir("gate-probe-outside") { (repo: Path) =>
      gitInit(repo)
      // NO openspec/ directory — the gate is irrelevant here and must not
      // create any state.
      val outcome: Outcome[Int] = runGate(
        repo,
        List("--event", "session-start", "--format", "text", "--session", "t")
      )
      outcome match
        case Outcome.Ran(_) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"outside-workflow repo should no-op, got $other")
      assert(
        !Files.exists(stateDir(repo)),
        "no state may be written outside the workflow"
      )
    }

  // ── Ring 5 mutation coverage ────────────────────────────────────────
  // Pinpoint tests from the Ring-5 survivor audit — the marker filename
  // grammar, heartbeat field rendering, spec-dir listing, and the
  // checkpoint-output sweep. These shapes are exercised by the scenario
  // tests but their exact membership/content was unasserted.

  private def resolvedDir(repo: Path): GateStateDir =
    val dir: GateStateDir =
      GateStateDirReader.resolve(repo).getOrElse(fail("state dir must resolve"))
    Files.createDirectories(dir.path)
    dir

  test("the presentation-marker glob requires a segment before the session"):
    withTempDir("gate-state-glob") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir  = resolvedDir(repo)
      val session: SessionId = SessionId.fromRaw("t")
      // Matching: presentation-<change>-<spec>-<session> with
      // single-segment change/spec names so the right-to-left
      // markerTriple split is unambiguous.
      Files.writeString(
        dir.path.resolve(s"presentation-cname-sname-${session.encoded}"),
        "h"
      )
      // Decoys: a single-segment name (`presentation-<x>-t` — the
      // predecessor's `presentation-*-*-$SESSION` glob needs two `*`
      // segments) and a marker under a DIFFERENT session.
      Files.writeString(dir.path.resolve("presentation-nosplit-t"), "h")
      Files.writeString(dir.path.resolve(s"presentation-cname-sname-other"), "h")
      // Sharper decoys for session x: a NON-presentation marker ending
      // in the session suffix (the `presentation-` glob is load-
      // bearing), a name carrying the encoded session without the
      // leading dash (the `-` in `-<enc>` is part of the glob), and a
      // single-segment name (a `*-*-` shape is required).
      val xEnc: String = SessionId.fromRaw("x").encoded
      Files.writeString(dir.path.resolve(s"grant-cname-sname-$xEnc"), "g")
      Files.writeString(dir.path.resolve(s"presentation-cname-sname$xEnc"), "h")
      Files.writeString(dir.path.resolve(s"presentation-sname-$xEnc"), "h")
      assert(
        GateStateDirReader.hasSessionPresentation(dir, session),
        "presentation-<change>-<spec>-<session> must match"
      )
      assert(
        !GateStateDirReader.hasSessionPresentation(dir, SessionId.fromRaw("x")),
        "a different session's marker must not match"
      )
      assertEquals(
        GateStateDirReader.sessionPresentations(dir, session),
        List(("cname", "sname"))
      )
      assertEquals(
        GateStateDirReader.sessionPresentations(dir, SessionId.fromRaw("x")),
        List.empty[(String, String)],
        "decoy names must yield no (change, spec) pair for session x"
      )
      assert(
        GateStateDirReader.hasAnySessionPresentation(dir, "cname", "sname"),
        "any-session presentation must match both sessions"
      )
    }

  test("findAnySessionGrant returns the sorted first grant file name"):
    withTempDir("gate-state-anygrant") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir = resolvedDir(repo)
      assertEquals(
        GateStateDirReader.findAnySessionGrant(dir, change, specName),
        None
      )
      Files.writeString(dir.path.resolve(s"grant-$change-$specName-zsess"), "g")
      Files.writeString(dir.path.resolve(s"grant-$change-$specName-asess"), "g")
      assertEquals(
        GateStateDirReader.findAnySessionGrant(dir, change, specName),
        Some(s"grant-$change-$specName-asess")
      )
      assert(GateStateDirReader.hasAnySessionGrant(dir, change, specName))
      assert(
        !GateStateDirReader.hasAnySessionGrant(dir, change, "other-spec"),
        "a grant for another spec must not match"
      )
    }

  test("specDirs lists only directories, sorted"):
    withTempDir("gate-state-specdirs") { (repo: Path) =>
      val chgDir: Path = repo.resolve("openspec/changes").resolve(change)
      Files.createDirectories(chgDir.resolve("specs").resolve("b-spec"))
      Files.createDirectories(chgDir.resolve("specs").resolve("a-spec"))
      Files.writeString(chgDir.resolve("specs").resolve("notes.txt"), "x")
      assertEquals(
        GateStateDirReader.specDirs(chgDir),
        List("a-spec", "b-spec")
      )
      assertEquals(
        GateStateDirReader.specDirs(repo.resolve("no-such-change")),
        List.empty[String]
      )
      assertEquals(
        GateStateDirReader.specDirs(repo.resolve("openspec/changes").resolve("empty")),
        List.empty[String]
      )
    }

  test("the heartbeat reads jq `// empty` field semantics"):
    withTempDir("gate-state-heartbeat") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir = resolvedDir(repo)
      val hb: Path          = dir.path.resolve("heartbeat")
      // null and absent fields read as "".
      Files.writeString(hb, """{"ts":null,"event":"post-bash"}""")
      val rec: HeartbeatRecord =
        GateStateDirReader.readHeartbeat(dir).getOrElse(fail("heartbeat must read"))
      assertEquals(rec.ts, "")
      assertEquals(rec.event, "post-bash")
      assertEquals(rec.format, "")
      // Non-string fields render with jq -r semantics.
      Files.writeString(hb, """{"ts":5,"event":true,"format":{"a":1}}""")
      val rec2: HeartbeatRecord =
        GateStateDirReader.readHeartbeat(dir).getOrElse(fail("heartbeat must read"))
      assertEquals(rec2.ts, "5")
      assertEquals(rec2.event, "true")
      assertEquals(rec2.format, """{"a":1}""")
      // A fractional number renders exactly (not truncated to int) and
      // `false` renders as "" — jq `// empty` treats false like null.
      Files.writeString(hb, """{"ts":1.5,"event":false,"format":"x"}""")
      val rec3: HeartbeatRecord =
        GateStateDirReader.readHeartbeat(dir).getOrElse(fail("heartbeat must read"))
      assertEquals(rec3.ts, "1.5")
      assertEquals(rec3.event, "")
      assertEquals(rec3.format, "x")
      // `false` and `null` records are not installed (jq -e .).
      Files.writeString(hb, "false")
      assertEquals(GateStateDirReader.readHeartbeat(dir), None)
      Files.writeString(hb, "null")
      assertEquals(GateStateDirReader.readHeartbeat(dir), None)
      // A bare string is a run record with unreadable fields.
      Files.writeString(hb, "\"ran\"")
      assertEquals(
        GateStateDirReader.readHeartbeat(dir),
        Some(HeartbeatRecord("", "", ""))
      )
    }

  test("the checkpoint-output sweep records triples and consumes outputs"):
    withTempDir("gate-state-sweep") { (repo: Path) =>
      gitInit(repo)
      val dir: GateStateDir = resolvedDir(repo)
      Files.writeString(dir.path.resolve("checkpoint-output-chg-sp-sess"), "content")
      // Three hyphens after the prefix — a `>= 2`-vs-`== 2` regression
      // would skip this name entirely.
      Files.writeString(dir.path.resolve("checkpoint-output-a-b-c-s2"), "content")
      // Fewer than two hyphens after the prefix: outside the glob — the
      // predecessor never enters the loop, so the file is NOT consumed.
      Files.writeString(dir.path.resolve("checkpoint-output-chg-sp"), "content")
      // Unparseable name (empty change segment): consumed, no marker.
      Files.writeString(dir.path.resolve("checkpoint-output--sp-sess"), "content")
      val recorded: List[(String, String, String)] =
        GateStateDirReader.sweepCheckpointOutputs(
          dir,
          (p: Path) => Some("sha256-" + p.getFileName.toString)
        )
      assertEquals(
        recorded.toSet,
        Set(("chg", "sp", "sess"), ("a-b", "c", "s2"))
      )
      assert(
        Files.isRegularFile(dir.path.resolve("presentation-chg-sp-sess")),
        "the presentation marker must be written"
      )
      assertEquals(
        Files.readString(dir.path.resolve("presentation-chg-sp-sess")),
        "sha256-checkpoint-output-chg-sp-sess"
      )
      assert(!Files.exists(dir.path.resolve("checkpoint-output-chg-sp-sess")))
      assert(!Files.exists(dir.path.resolve("checkpoint-output-a-b-c-s2")))
      assert(
        Files.isRegularFile(dir.path.resolve("presentation-a-b-c-s2")),
        "a 3-hyphen name yields a presentation marker too"
      )
      assert(
        Files.exists(dir.path.resolve("checkpoint-output-chg-sp")),
        "a name outside the glob is left alone"
      )
      assert(!Files.exists(dir.path.resolve("checkpoint-output--sp-sess")))
      assert(
        !Files.exists(dir.path.resolve("presentation--sp-sess")),
        "an unparseable name yields no marker"
      )
    }

  // ── completion-witness-refusal (spec 3 of repair-probatio-cutover) ──

  // spec: completion-witness-refusal — Scenario: Happy path — an unavailable state area allows with a named reason
  test("completion with no resolvable state area allows and names it"):
    withTempDir("gate-completion-nostate") { (repo: Path) =>
      // No git — `git rev-parse --absolute-git-dir` fails → no state dir
      // resolves, and the tier must fail OPEN while saying why.
      Files.createDirectories(repo.resolve("openspec/changes/test-change"))
      val traceFile: Path = repo.resolve("trace.log")
      val outcome: Outcome[Int] = GateCmd.run(
        ("--repo" +: repo.toString +:
          List("--event", "completion", "--format", "text", "--session", "t")).toArray,
        Map("PROBATIO_HOOKS_TRACE" -> traceFile.toString),
        () => None
      )
      outcome match
        case Outcome.Ran(0) => ()
        case other => // danger-scan:allow test assertion — unexpected outcome fails the test
          fail(s"an unavailable state area fails open, got $other")
      val trace: String =
        if Files.isRegularFile(traceFile) then Files.readString(traceFile) else ""
      assert(
        trace.contains("STATE_DIR"),
        s"the trace names the unavailable state area: $trace"
      )
    }
