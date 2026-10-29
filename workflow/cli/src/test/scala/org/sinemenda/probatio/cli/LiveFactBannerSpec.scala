package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.ActiveChangeWithChainState
import org.sinemenda.probatio.core.BannerEngine
import org.sinemenda.probatio.core.BannerInputs
import org.sinemenda.probatio.core.BannerOutput
import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.HeartbeatRecord
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.RepositoryFacts
import org.sinemenda.probatio.core.SessionId
import org.sinemenda.probatio.core.StampFormat
import org.sinemenda.probatio.migration.HermeticEnv
import org.sinemenda.probatio.migration.HermeticResult

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.ChainStub
import LiveFactFixtures.Materialised
import LiveFactFixtures.MutatedField
import LiveFactFixtures.RepoShape
import LiveFactFixtures.RootDocShape
import LiveFactFixtures.SchemaShape
import LiveFactFixtures.RegistryShape
import LiveFactFixtures.InventoryShape
import LiveFactFixtures.ProfileShape
import LiveFactFixtures.bracket
import LiveFactFixtures.deleteTree
import LiveFactFixtures.withMaterialised
import LiveFactFixtures.withTempDir
import LiveFactFixtures.genFactsPair
import LiveFactFixtures.genRepositoryFacts

/**
 * Test oracle for spec: live-fact-banner.
 *
 * Written from the spec and the approved Step 1 contract ONLY, before the
 * reader and gate wiring exist — tests that exercise `RepositoryFactsReader`,
 * `GateStateDirReader`, and the `GateCmd` banner path are RED at polarity
 * (their seams are `???`); tests of the already-pinned pure render surface
 * and the compile-negatives are GREEN-by-design.
 *
 * spec: live-fact-banner — Step 2: test oracle
 */
final class LiveFactBannerSpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  // ════════════════════════════════════════════════════════════════════
  // Harness helpers
  // ════════════════════════════════════════════════════════════════════

  /** Capture stdout and stderr produced by `thunk`. */
  private def captureBoth[A](thunk: => A): (String, String, A) =
    StdoutCapture.captureBoth(thunk)

  /** Capture stdout (stderr swallowed) produced by `thunk`. */
  private def capture[A](thunk: => A): (String, A) =
    val (out, _, result) = captureBoth(thunk)
    (out, result)

  /** Run `git` under `dir`; returns the exit code. */
  private def git(dir: Path, args: String*): Int =
    LiveFactFixtures.git(dir, args*)._1

  /** A repository that passes the relevance guard and has a real git dir. */
  private def withGitRepo[A](f: Path => A): A =
    withTempDir("live-fact-repo") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec"))
      assertEquals(git(repo, "init", "-q"), 0, "git init must succeed")
      f(repo)
    }

  /** A git repo with `openspec/changes/some-change/specs/only` — the bats fixture. */
  private def withChangeRepo[A](f: Path => A): A =
    withGitRepo { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/changes/some-change/specs/only"))
      f(repo)
    }

  /**
   * Write a stub `scanner/chain-state.sh` into the repo's schema dir — the
   * fallback seam the reader uses when `CHAIN_STATE_OVERRIDE` is unset.
   */
  private def writeChainState(repo: Path, reportJson: String, exit: Int): Unit =
    val script: Path =
      repo.resolve("openspec/schemas/verified-scala3/scanner/chain-state.sh")
    Files.createDirectories(script.getParent)
    Files.write(
      script,
      s"#!/usr/bin/env bash\ncat <<'REPORT'\n$reportJson\nREPORT\nexit $exit\n"
        .getBytes(StandardCharsets.UTF_8)
    )
    script.toFile.setExecutable(true)

  private def reportWith(n: Int): String =
    val entries: String =
      if n == 0 then "[]"
      else
        (0 until n)
          .map(i => s"""{"spec":"s","requirement":"Req $i","reasons":["undischarged"]}""")
          .mkString("[", ",", "]")
    s"""{"change":"some-change","baseline":"abc1234","total":${n + 1},"bound":${n + 1},""" +
      s""""resolved":${n + 1},"discharged":1,"unresolved":$entries,"unmapped_obligations":[]}"""

  /** Invoke the gate against `repo` with extra flags; returns (stdout, outcome). */
  private def gate(repo: Path, extra: String*): (String, Outcome[Int]) =
    val args: Array[String] = Array("--repo", repo.toString) ++ extra.toArray
    capture(GateCmd.run(args))

  /** Invoke the gate with an explicit environment — the env-seam tests. */
  private def gateEnv(
    args: Array[String],
    env: Map[String, String]
  ): (String, Outcome[Int]) =
    capture(GateCmd.run(args, env))

  /** Invoke the gate with an explicit environment; also captures stderr. */
  private def gateEnvErr(
    args: Array[String],
    env: Map[String, String]
  ): (String, String, Outcome[Int]) =
    captureBoth(GateCmd.run(args, env))

  // ════════════════════════════════════════════════════════════════════
  // Requirement: Every fact the banner states is read during the run
  // ════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — a present registry is reported present with its exact count
  // spec: live-fact-banner — Scenario: Happy path — a present registry is reported present with its exact count
  test("a present registry is reported present with its exact count"):
    withChangeRepo { (repo: Path) =>
      val concepts: Path = repo.resolve("openspec/concepts")
      Files.createDirectories(concepts)
      (0 until 5).foreach { (i: Int) =>
        Files.write(concepts.resolve(s"c$i.md"), s"# Concept: C$i\n".getBytes(StandardCharsets.UTF_8))
      }
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-reg")
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("PRESENT (5 concepts)"),
        s"registry line must name the exact count, got:\n$out"
      )
    }

  // ── Scenario: Adversarial — a present registry is never reported absent
  // spec: live-fact-banner — Scenario: Adversarial — a present registry is never reported absent
  // The proof-obligation form runs the BUILT ARTIFACT from a working
  // directory other than the repository root.
  test("a present registry is never reported absent, even invoked outside the repo"):
    // Resolve via this class's code-source location rather than user.dir:
    // `sbt test` runs with the repo root as cwd while stryker4s's forked
    // test-runner uses the module base directory (workflow/cli).
    val artifact: Path = Path
      .of(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
      .resolve("../../native-image/probatio")
      .normalize()
    if !Files.exists(artifact) then
      fail(s"built artifact not found at $artifact — conformance check FAILS, does not skip")
    withChangeRepo { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/concepts"))
      Files.write(repo.resolve("openspec/concepts/c.md"), "# Concept: C\n".getBytes(StandardCharsets.UTF_8))
      withTempDir("elsewhere") { (elsewhere: Path) =>
        // spec: hermetic-test-processes — the spawned tool runs under the
        // shared helper's hermetic environment.
        val r: HermeticResult = HermeticEnv.capture(
          List(
            artifact.toString,
            "gate",
            "--event",
            "session-start",
            "--format",
            "text",
            "--repo",
            repo.toString,
            "--session",
            "s-out"
          ),
          HermeticEnv.empty,
          cwd = Some(elsewhere.toFile)
        )
        val out: String = r.out
        val code: Int   = r.exitCode
        assertEquals(code, 0, "the gate never fails")
        val regLine: Option[String] = out.linesIterator.find(_.contains("behavioural registry"))
        assert(
          regLine.exists(l => l.contains("PRESENT") && l.contains("1 concepts")),
          s"registry must be reported present:\n$out"
        )
      }
    }

  // ── Scenario: Error path — unreadable registry reported as unreadable
  // spec: live-fact-banner — Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent
  test("an unreadable registry is reported unreadable, never absent"):
    withChangeRepo { (repo: Path) =>
      val concepts: Path = repo.resolve("openspec/concepts")
      Files.createDirectories(concepts)
      Files.write(concepts.resolve("c.md"), "x\n".getBytes(StandardCharsets.UTF_8))
      concepts.toFile.setReadable(false, false)
      concepts.toFile.setExecutable(false, false)
      bracket(
        concepts,
        { (c: Path) =>
          c.toFile.setReadable(true, false)
          c.toFile.setExecutable(true, false)
        }
      ) { (_: Path) =>
        val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-unr")
        assertEquals(outcome, Outcome.Ran(0))
        val regLine: Option[String] = out.linesIterator.find(_.contains("registry"))
        assert(
          regLine.exists(_.contains("UNREADABLE")),
          s"unreadable registry must state UNREADABLE, got: ${regLine.getOrElse("<no registry line>")}"
        )
        assert(
          !regLine.exists(_.contains("ABSENT")),
          "an unreadable fact must never be stated as absent"
        )
      }
    }

  // ── Scenario: Edge case — a genuinely absent registry, consequence attributed to this run
  // spec: live-fact-banner — Scenario: Edge case — a genuinely absent registry is reported absent, with its consequence
  test("an absent registry is reported absent with its consequence attributed to this run"):
    withChangeRepo { (repo: Path) =>
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-abs")
      assertEquals(outcome, Outcome.Ran(0))
      val regLine: Option[String] = out.linesIterator.find(_.contains("registry"))
      assert(regLine.exists(_.contains("ABSENT")), s"absent registry must state ABSENT:\n$out")
      assert(
        out.contains("attested by this run"),
        "the consequence must be attributed to this run, not assumed"
      )
    }

  // ════════════════════════════════════════════════════════════════════
  // Requirement: Instruction drift across every searched install root
  // ════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — a user-scoped root with an older declared version is reported
  // spec: live-fact-banner — Scenario: Happy path — a user-scoped root with an older declared version is reported
  test("drift at a user-scoped root is reported with both versions named"):
    withMaterialised(
      RepoShape(
        schema = SchemaShape.Versioned(14),
        registry = RegistryShape.Absent,
        inventory = InventoryShape.Absent,
        profile = ProfileShape.Absent,
        rootDocs = List(
          RootDocShape.Absent,
          RootDocShape.Absent,
          RootDocShape.Absent,
          RootDocShape.Absent,
          RootDocShape.Stamped(13, StampFormat.New), // homeClaude
          RootDocShape.Absent
        ),
        changes = Nil,
        chainStub = ChainStub.Absent
      )
    ) { (m: Materialised) =>
      val facts: RepositoryFacts =
        RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
      val out: BannerOutput = BannerEngine.render(BannerInputs.from(facts))
      assert(
        out.payload.contains("INSTRUCTION DRIFT"),
        s"drift at the user-scoped root must be reported:\n${out.payload}"
      )
      assert(
        out.payload.contains("v13") && out.payload.contains("v14"),
        s"both versions must be named:\n${out.payload}"
      )
      assert(
        out.payload.contains(m.userHome.resolve(".claude/skills").toString),
        s"the drifting root must be named:\n${out.payload}"
      )
    }

  // ── Scenario: Error path — no instruction document at any root is reported as such
  // spec: live-fact-banner — Scenario: Error path — no instruction document at any root is reported as such
  test("no instruction document at any root is reported, with drift checking named unavailable"):
    withMaterialised(
      RepoShape(
        schema = SchemaShape.Versioned(14),
        registry = RegistryShape.Absent,
        inventory = InventoryShape.Absent,
        profile = ProfileShape.Absent,
        rootDocs = List.fill(DriftScan.installRoots.length)(RootDocShape.Absent),
        changes = Nil,
        chainStub = ChainStub.Absent
      )
    ) { (m: Materialised) =>
      val facts: RepositoryFacts =
        RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
      val out: BannerOutput = BannerEngine.render(BannerInputs.from(facts))
      assert(
        out.payload.contains("no openspec-spec-lint skill installed"),
        s"the no-install state must be stated:\n${out.payload}"
      )
      assert(
        out.payload.contains("drift checking is unavailable"),
        s"the consequence must be named — drift checking is unavailable:\n${out.payload}"
      )
    }

  // ════════════════════════════════════════════════════════════════════
  // Requirement: Active-change facts carry live chain-state
  // ════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — an active change with unresolved requirements shows the count
  // spec: live-fact-banner — Scenario: Happy path — an active change with unresolved requirements shows the count
  test("an active change is named and its unresolved count appears paired with its label"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(3), exit = 0)
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-cs")
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.contains("some-change"), s"the change must be named:\n$out")
      assert(out.contains("unresolved 3"), s"count must be paired with its label:\n$out")
      assert(out.contains("Req 0") && out.contains("Req 2"), s"requirements must be named:\n$out")
    }

  // ── Scenario: Error path — a change whose chain-state cannot be determined says so
  // spec: live-fact-banner — Scenario: Error path — a change whose chain-state cannot be determined says so
  test("a change whose chain-state cannot be determined is undetermined, not zero"):
    withChangeRepo { (repo: Path) =>
      // no chain-state tool at all — the fallback seam finds nothing
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-und")
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.linesIterator.exists(l => l.trim.startsWith("undetermined")),
        s"chain-state must be reported undetermined:\n$out"
      )
      assert(
        !out.contains("unresolved 0"),
        "undetermined must never render as a clean zero"
      )
    }

  test("a chain-state tool exiting non-zero produces undetermined, and the gate still exits 0"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, "{}", exit = 2)
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-cs2")
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.contains("undetermined"), s"nonzero tool exit must be reported:\n$out")
    }

  test("a report combining undetermined with a null total is not rendered clean"):
    withChangeRepo { (repo: Path) =>
      writeChainState(
        repo,
        """{"change":"c","baseline":"b","undetermined":true,"reason":"stub","total":null,""" +
          """"bound":null,"resolved":null,"discharged":null,"unresolved":[],"unmapped_obligations":[]}""",
        exit = 0
      )
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-null")
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.contains("undetermined"), s"a null total is undetermined:\n$out")
      assert(!out.contains("unresolved 0"), "null total must never render as clean")
    }

  // ════════════════════════════════════════════════════════════════════
  // Requirement: suppression iff facts unchanged
  // ════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — an unchanged repeat is suppressed
  // spec: live-fact-banner — Scenario: Happy path — an unchanged repeat is suppressed
  test("an unchanged repeat injection within one session is suppressed, exactly empty"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(1), exit = 0)
      val (out1, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sup-a")
      assert(out1.contains("READ FROM DISK"), s"the first injection must emit the banner:\n$out1")
      val (out2, oc2) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sup-a")
      assertEquals(oc2, Outcome.Ran(0))
      // The captured stream is process-global: a parallel suite's writes can
      // interleave, so the check is "our gate emitted no banner", not
      // byte-emptiness of the shared stream.
      assert(
        !out2.contains("READ FROM DISK"),
        s"an unchanged repeat must emit no banner:\n$out2"
      )
      val (out3, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sup-a")
      assert(!out3.contains("READ FROM DISK"), "the third call must also be suppressed")
    }

  test("a suppressed call in hook-json format is exactly empty, not a JSON envelope"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(1), exit = 0)
      val (out1, _) = gate(repo, "--event", "prompt-submit", "--format", "hook-json", "--session", "sup-j")
      assert(out1.contains("additionalContext"), s"the first injection must emit the envelope:\n$out1")
      val (out2, _) = gate(repo, "--event", "prompt-submit", "--format", "hook-json", "--session", "sup-j")
      assert(
        !out2.contains("additionalContext"),
        s"suppressed hook-json output must carry no banner envelope:\n$out2"
      )
    }

  // ── Scenario: Adversarial — a changed fact defeats suppression
  // spec: live-fact-banner — Scenario: Adversarial — a changed fact defeats suppression
  test("a changed fact defeats suppression within the same session"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(1), exit = 0)
      val (_, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sup-chg")
      writeChainState(repo, reportWith(2), exit = 0)
      val (out2, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sup-chg")
      assert(out2.nonEmpty, "a changed fact must re-inject")
      assert(out2.contains("unresolved 2"), s"the new count must appear:\n$out2")
    }

  // ── Scenario: Edge case — a different session is not suppressed by another session's state
  // spec: live-fact-banner — Scenario: Edge case — a different session is not suppressed by another session's state
  test("a different session is not suppressed by another session's state"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(1), exit = 0)
      val (o1, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sess-c")
      assert(o1.nonEmpty)
      val (o2, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sess-c")
      assert(
        !o2.contains("READ FROM DISK"),
        s"precondition: session c must suppress its own repeat:\n$o2"
      )
      val (o3, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "sess-d")
      assert(o3.nonEmpty, "a new session must inject regardless of another's state")
    }

  test("sessions differing only in filename-unsafe characters are distinct"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(1), exit = 0)
      val (o1, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "abc!def")
      assert(o1.nonEmpty)
      val (o2, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "abc?def")
      assert(
        o2.nonEmpty,
        "'abc?def' must not be suppressed by 'abc!def' — lossless encoding"
      )
    }

  // ════════════════════════════════════════════════════════════════════
  // Emission contract
  // ════════════════════════════════════════════════════════════════════

  test("prompt-submit maps to UserPromptSubmit in hook-json"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(0), exit = 0)
      val (out, outcome) =
        gate(repo, "--event", "prompt-submit", "--format", "hook-json", "--session", "s-hj")
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("\"hookEventName\":\"UserPromptSubmit\""),
        s"prompt-submit must map to UserPromptSubmit:\n$out"
      )
    }

  test("hook-json and text carry the identical payload"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(2), exit = 0)
      val (textOut, _) =
        gate(repo, "--event", "session-start", "--format", "text", "--session", "s-txt")
      val (jsonOut, _) =
        gate(repo, "--event", "session-start", "--format", "hook-json", "--session", "s-jsn")
      val ctx: String =
        ujson.read(jsonOut.trim)("hookSpecificOutput")("additionalContext").str
      assertEquals(ctx, textOut.trim, "hook-json additionalContext must equal the text payload")
    }

  test("the invariant text and the read-from-disk trailer are in every emitted banner"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(0), exit = 0)
      val (out, _) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-inv")
      assert(out.contains("NEVER LET A CLAIM OUTRUN ITS EVIDENCE"), s"invariant:\n$out")
      assert(out.contains("READ FROM DISK"), s"trailer:\n$out")
      assert(out.endsWith("\n"), "the text payload ends with a newline")
    }

  // ── Relevance guard: a repository outside the workflow receives nothing
  // spec: gate-payload — Scenario: a repository outside the workflow receives nothing
  test("a repository with no openspec directory injects nothing, writes no state, exits 0"):
    withTempDir("no-openspec-repo") { (repo: Path) =>
      assertEquals(git(repo, "init", "-q"), 0, "git init")
      val (out, outcome) = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-no")
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        !out.contains("READ FROM DISK"),
        s"no banner may be emitted for a non-workflow repository:\n$out"
      )
      val gitDir: Path = repo.resolve(".git")
      assert(
        !Files.exists(gitDir.resolve("verified-scala3-gate")),
        "no state directory may be created in a non-openspec repo"
      )
    }

  // ════════════════════════════════════════════════════════════════════
  // Heartbeat + installation probe
  // ════════════════════════════════════════════════════════════════════

  test("--check-installed reports false before any run, true after, with event recorded"):
    withChangeRepo { (repo: Path) =>
      val (out0, oc0) = gate(repo, "--check-installed")
      assertEquals(oc0, Outcome.Ran(0))
      assert(out0.contains("\"installed\":false"), s"must report not installed:\n$out0")
      assert(out0.contains("\"last_run\":null"), s"no run yet — last_run is null:\n$out0")
      assert(out0.contains("\"event\":null"), s"no run yet — event is null:\n$out0")
      assert(out0.endsWith("\n"), "the probe line ends with a newline")

      writeChainState(repo, reportWith(0), exit = 0)
      val (_, _)    = gate(repo, "--event", "session-start", "--format", "text", "--session", "s-hb")
      val (out1, _) = gate(repo, "--check-installed")
      assert(out1.contains("\"installed\":true"), s"must report installed:\n$out1")
      assert(out1.contains("\"event\":\"session-start\""), s"event must be recorded:\n$out1")
      assert(
        out1.contains("\"last_run\":\"") && !out1.contains("\"last_run\":null"),
        s"last_run must carry the recorded timestamp:\n$out1"
      )
    }

  test("the heartbeat records the emitted event name"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(0), exit = 0)
      val (_, _) = gate(repo, "--event", "prompt-submit", "--format", "text", "--session", "s-hb2")
      val (o, _) = gate(repo, "--check-installed")
      assert(o.contains("\"event\":\"prompt-submit\""), s"the emitted event must be recorded:\n$o")
    }

  test("the default format is hook-json and the envelope names the hook event"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(0), exit = 0)
      val (out, outcome) = gate(repo, "--event", "session-start", "--session", "s-df")
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.contains("hookSpecificOutput"), s"the default format is hook-json:\n$out")
      assert(
        out.contains("\"hookEventName\":\"SessionStart\""),
        s"session-start maps to SessionStart:\n$out"
      )
      assert(out.endsWith("\n"), "the envelope line ends with a newline")
    }

  test("--check-installed needs no --event and creates no state directory"):
    withChangeRepo { (repo: Path) =>
      val gitDir: Path   = repo.resolve(".git")
      val (out, outcome) = gate(repo, "--check-installed")
      assertEquals(outcome, Outcome.Ran(0))
      assert(out.contains("\"installed\":false"))
      assert(
        !Files.exists(gitDir.resolve("verified-scala3-gate")),
        "the probe is a pure read — no state dir may be created"
      )
    }

  test("the heartbeat and probe work from inside a git worktree"):
    withChangeRepo { (repo: Path) =>
      git(repo, "config", "user.email", "t@t")
      git(repo, "config", "user.name", "t")
      Files.write(
        repo.resolve("openspec/tracked.md"),
        "x\n".getBytes(StandardCharsets.UTF_8)
      )
      git(repo, "add", "-A")
      assertEquals(git(repo, "commit", "-q", "-m", "init"), 0, "commit")
      val wt: Path = repo.getParent.resolve(s"${repo.getFileName}-wt")
      bracket((), (_: Unit) => if Files.exists(wt) then deleteTree(wt)) { (_: Unit) =>
        assertEquals(
          git(repo, "worktree", "add", "-q", wt.toString, "-b", "wt-branch"),
          0,
          "worktree add"
        )
        writeChainState(wt, reportWith(0), exit = 0)
        val (_, c1) = gate(wt, "--event", "session-start", "--format", "text", "--session", "s-wt")
        assertEquals(c1, Outcome.Ran(0))
        val (o2, _) = gate(wt, "--check-installed")
        assert(o2.contains("\"installed\":true"), s"worktree heartbeat must persist:\n$o2")
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Environment seam — hook control, repo resolution, session, HOME.
  // `GateCmd.run(args, env)` threads an explicit env through the same
  // logic `run(args)` executes against the process environment.
  // ════════════════════════════════════════════════════════════════════

  test("PROBATIO_HOOKS=off suppresses the banner entirely"):
    withChangeRepo { (repo: Path) =>
      val (out, outcome) = gateEnv(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-off"
        ),
        Map("PROBATIO_HOOKS" -> "off")
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        !out.contains("READ FROM DISK"),
        s"hook-control off must emit no banner:\n$out"
      )
    }

  test("the deprecated VERIFIED_SCALA3_HOOKS alias still suppresses while the schema version is unknown"):
    withChangeRepo { (repo: Path) =>
      // no schema.yaml → version unknown → the alias window is open
      val (out, outcome) = gateEnv(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-alias"
        ),
        Map("VERIFIED_SCALA3_HOOKS" -> "off")
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        !out.contains("READ FROM DISK"),
        s"the alias must suppress while its window is open:\n$out"
      )
    }

  test("the VERIFIED_SCALA3_HOOKS alias is honoured at v15 and expires at v16"):
    List(15 -> false, 16 -> true).foreach { case (v, emits) =>
      withChangeRepo { (repo: Path) =>
        val schemaDir: Path = repo.resolve("openspec/schemas/verified-scala3")
        Files.createDirectories(schemaDir)
        Files.writeString(schemaDir.resolve("schema.yaml"), s"version: $v\n")
        val (out, _) = gateEnv(
          Array(
            "--repo",
            repo.toString,
            "--event",
            "session-start",
            "--format",
            "text",
            "--session",
            s"s-v$v"
          ),
          Map("VERIFIED_SCALA3_HOOKS" -> "off")
        )
        assertEquals(
          out.contains("READ FROM DISK"),
          emits,
          s"schema v$v + VERIFIED_SCALA3_HOOKS=off must ${if emits then "emit" else "suppress"}"
        )
      }
    }

  test("the deprecated alias warns on stderr and names its replacement"):
    withChangeRepo { (repo: Path) =>
      val (_, err, outcome) = gateEnvErr(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-dep"
        ),
        Map("VERIFIED_SCALA3_HOOKS" -> "on")
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        err.contains("VERIFIED_SCALA3_HOOKS is deprecated") && err.contains("renamed to"),
        s"the deprecation warning must name the old var: $err"
      )
      assert(
        err.contains("schema v14") && err.contains("one major version"),
        s"the warning must name the expiry window: $err"
      )
      assert(
        err.endsWith("use PROBATIO_HOOKS.\n"),
        s"the warning must end by naming the replacement var: $err"
      )
    }

  test("the alias-expiry schema version is read from the version: line, not the first line"):
    withChangeRepo { (repo: Path) =>
      val schemaDir: Path = repo.resolve("openspec/schemas/verified-scala3")
      Files.createDirectories(schemaDir)
      Files.writeString(
        schemaDir.resolve("schema.yaml"),
        "# generated schema\nversion: 16\n"
      )
      val (out, _) = gateEnv(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-firstline"
        ),
        Map("VERIFIED_SCALA3_HOOKS" -> "off")
      )
      assert(
        out.contains("READ FROM DISK"),
        s"schema v16 expires the alias — a first-line parse would wrongly honour it:\n$out"
      )
    }

  test("the alias-expiry version parse takes the first colon only"):
    withChangeRepo { (repo: Path) =>
      val schemaDir: Path = repo.resolve("openspec/schemas/verified-scala3")
      Files.createDirectories(schemaDir)
      Files.writeString(schemaDir.resolve("schema.yaml"), "version: x:16\n")
      val (out, _) = gateEnv(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-colon"
        ),
        Map("VERIFIED_SCALA3_HOOKS" -> "off")
      )
      assert(
        !out.contains("READ FROM DISK"),
        s"version 'x:16' is unparseable — the alias stays honoured and suppresses:\n$out"
      )
    }

  test("CLAUDE_PROJECT_DIR resolves the repository when --repo is not passed"):
    withChangeRepo { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/changes/cpd-marker-change"))
      val (out, outcome) = gateEnv(
        Array("--event", "session-start", "--format", "text", "--session", "s-cpd"),
        Map("CLAUDE_PROJECT_DIR" -> repo.toString)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("cpd-marker-change"),
        s"the env-resolved repo's facts must be the ones stated:\n$out"
      )
    }

  test("HOME overrides the user-scoped roots exactly like the predecessor's $HOME"):
    withTempDir("home-override") { (base: Path) =>
      val repo: Path = base.resolve("repo")
      val home: Path = base.resolve("home")
      Files.createDirectories(repo.resolve("openspec/changes/c1"))
      assertEquals(git(repo, "init", "-q"), 0, "git init")
      val schemaDir: Path = repo.resolve("openspec/schemas/verified-scala3")
      Files.createDirectories(schemaDir)
      Files.writeString(schemaDir.resolve("schema.yaml"), "version: 15\n")
      val skillDoc: Path =
        home.resolve(".claude/skills/openspec-spec-lint/SKILL.md")
      Files.createDirectories(skillDoc.getParent)
      Files.writeString(skillDoc, "---\ngeneratedBy: probatio-schema/14.0.0\n---\n")
      val (out, outcome) = gateEnv(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-home"
        ),
        Map("HOME" -> home.toString)
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains(home.resolve(".claude/skills").toString),
        s"drift at $$HOME/.claude/skills must name that root:\n$out"
      )
    }

  test("without HOME the user.home property is the fallback and the banner still emits"):
    withChangeRepo { (repo: Path) =>
      val (out, outcome) = gateEnv(
        Array(
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "s-nh"
        ),
        Map.empty
      )
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("READ FROM DISK"),
        s"the user.home fallback must not break emission:\n$out"
      )
    }

  test("env session vars feed the session identity, strongest signal first"):
    withChangeRepo { (repo: Path) =>
      writeChainState(repo, reportWith(0), exit = 0)
      val stateDir: GateStateDir = GateStateDirReader.resolve(repo) match
        case Some(d) => d
        case None    => fail("a git repo must resolve a state dir")
      gateEnv(
        Array("--repo", repo.toString, "--event", "session-start", "--format", "text"),
        Map(
          "CLAUDE_CODE_SESSION_ID"     -> "env-session",
          "VERIFIED_SCALA3_SESSION_ID" -> "fallback-sess"
        )
      )
      assert(
        Files.exists(
          GateStateDirReader.fingerprintFile(stateDir, SessionId.fromRaw("env-session"))
        ),
        "CLAUDE_CODE_SESSION_ID must win over the fallback var"
      )
      gateEnv(
        Array("--repo", repo.toString, "--event", "session-start", "--format", "text"),
        Map("VERIFIED_SCALA3_SESSION_ID" -> "fallback-sess")
      )
      assert(
        Files.exists(
          GateStateDirReader.fingerprintFile(stateDir, SessionId.fromRaw("fallback-sess"))
        ),
        "VERIFIED_SCALA3_SESSION_ID must be used when CLAUDE_CODE_SESSION_ID is absent"
      )
    }

  test("a non-git repository still emits the banner — no state dir is not suppression"):
    withTempDir("nogit-repo") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/changes/c1"))
      val (out, outcome) =
        gate(repo, "--event", "session-start", "--format", "text", "--session", "s-ng")
      assertEquals(outcome, Outcome.Ran(0))
      assert(
        out.contains("READ FROM DISK"),
        s"a missing state dir is fail-open — the banner must still emit:\n$out"
      )
    }

  test("a completion event with no presentation marker allows mid-work stops"):
    withChangeRepo { (repo: Path) =>
      // The predecessor's completion tier triggers only when the session
      // holds a checkpoint-presentation marker; the flag surface
      // (--ledger-file/--change/--baseline) was the pre-cutover stub.
      assertEquals(gate(repo, "--event", "completion")._2, Outcome.Ran(0))
    }

  test("a completion event is triggered by the session's presentation marker"):
    withChangeRepo { (repo: Path) =>
      // Marker present + unresolved chain state → the completion refuses.
      writeChainState(repo, reportWith(1), 0)
      val (_, outcome) = gateEnv(
        Array("--repo", repo.toString, "--event", "completion", "--session", "s-comp", "--format", "text"),
        Map.empty[String, String]
      )
      // No marker yet → allow; write the marker, then refuse.
      assertEquals(outcome, Outcome.Ran(0))
      val stateDir: Path = repo.resolve(".git/verified-scala3-gate")
      Files.createDirectories(stateDir)
      Files.writeString(
        stateDir.resolve(s"presentation-some-change-only-${SessionId.fromRaw("s-comp").encoded}"),
        "deadbeef"
      )
      val (_, outcome2) = gateEnv(
        Array("--repo", repo.toString, "--event", "completion", "--session", "s-comp", "--format", "text"),
        Map.empty[String, String]
      )
      outcome2 match
        case Outcome.Finding(msg) =>
          assert(msg.contains("unresolved"), s"the refusal must name unresolved requirements: $msg")
        case other => fail(s"a presented session with unresolved requirements must refuse, got $other")
    }

  test("the escape hatch bypasses the completion chain-state check"):
    withChangeRepo { (repo: Path) =>
      val (_, outcome) = gateEnv(
        Array("--repo", repo.toString, "--event", "completion"),
        Map("PROBATIO_HOOKS" -> "off")
      )
      assertEquals(outcome, Outcome.Ran(0))
    }

  test("tool-call is a recognised event — fails open without state, never an unknown event"):
    withChangeRepo { (repo: Path) =>
      // The predecessor allows a tool call when the state directory is
      // unavailable (fail-open); the pre-cutover stub returned
      // Undetermined unconditionally.
      assertEquals(gate(repo, "--event", "tool-call")._2, Outcome.Ran(0))
    }

  test("the escape hatch bypasses the tool-call predecessor check"):
    withChangeRepo { (repo: Path) =>
      val (_, outcome) = gateEnv(
        Array("--repo", repo.toString, "--event", "tool-call"),
        Map("PROBATIO_HOOKS" -> "off")
      )
      assertEquals(outcome, Outcome.Ran(0))
    }

  test("post-edit is informational and exits 0"):
    withChangeRepo((repo: Path) => assertEquals(gate(repo, "--event", "post-edit")._2, Outcome.Ran(0)))

  // ════════════════════════════════════════════════════════════════════
  // GateStateDir — resolution + heartbeat round-trip
  // ════════════════════════════════════════════════════════════════════

  test("resolve returns the <git-dir>/verified-scala3-gate dir in a git repo, None outside one"):
    withGitRepo { (repo: Path) =>
      GateStateDirReader.resolve(repo) match
        case Some(d) =>
          assertEquals(d.path.getFileName.toString, "verified-scala3-gate")
          assertEquals(d.path.getParent.getFileName.toString, ".git")
        case None => fail("a git repo must resolve its gate state dir")
    }
    withTempDir("nogit-state") { (dir: Path) =>
      assertEquals(
        GateStateDirReader.resolve(dir),
        Option.empty[GateStateDir],
        "a non-git directory resolves no state dir — never a fabricated path"
      )
    }

  test("the heartbeat file round-trips all fields and ends with a newline"):
    withTempDir("heartbeat") { (dir: Path) =>
      val d: GateStateDir = GateStateDir(dir)
      GateStateDirReader.writeHeartbeat(
        d,
        HeartbeatRecord("2026-01-02T03:04:05Z", "prompt-submit", "hook-json")
      )
      assert(
        Files.readString(dir.resolve("heartbeat")).endsWith("\n"),
        "the heartbeat file ends with a newline"
      )
      GateStateDirReader.readHeartbeat(d) match
        case Some(hb) =>
          assertEquals(hb.ts, "2026-01-02T03:04:05Z")
          assertEquals(hb.event, "prompt-submit")
          assertEquals(hb.format, "hook-json")
        case None => fail("a written heartbeat must read back")
    }

  // ════════════════════════════════════════════════════════════════════
  // SessionId — resolution order + lossless encoding (pure)
  // ════════════════════════════════════════════════════════════════════

  test("session identity resolves strongest signal first, empty strings are absent"):
    assertEquals(SessionId.resolve(Some("a"), Some("b"), Some("c"), 42).raw, "a")
    assertEquals(SessionId.resolve(None, Some("b"), Some("c"), 42).raw, "b")
    assertEquals(SessionId.resolve(None, None, Some("c"), 42).raw, "c")
    assertEquals(SessionId.resolve(None, None, None, 42).raw, "ppid-42")
    assertEquals(SessionId.resolve(Some(""), Some("b"), None, 42).raw, "b")

  test("the session encoding is injective and filename-safe"):
    val a: SessionId = SessionId.fromRaw("abc!def")
    val b: SessionId = SessionId.fromRaw("abc?def")
    assert(a.encoded != b.encoded, "lossy sanitisation would collide these")
    assert(a.encoded.matches("[A-Za-z0-9._-]+"), s"encoded must be filename-safe: ${a.encoded}")

  // ════════════════════════════════════════════════════════════════════
  // Compile-Negative Obligations (spec names this file)
  // ════════════════════════════════════════════════════════════════════

  // spec: live-fact-banner — Compile-Negative: BannerInputs constructed from literals rather than from a RepositoryFacts value
  test("compile-negative: BannerInputs from literals does not compile"):
    val err: String = compileErrors(
      "org.sinemenda.probatio.core.BannerInputs(org.sinemenda.probatio.core.FactRead.Present(14), Nil, org.sinemenda.probatio.core.FactRead.Absent)"
    )
    assert(err.nonEmpty, "BannerInputs(literals) must not compile")

  // spec: live-fact-banner — Compile-Negative: a file read inside BannerEngine
  test("compile-negative: no path-based construction route into the engine"):
    val err: String = compileErrors(
      "org.sinemenda.probatio.core.BannerInputs.from(java.nio.file.Paths.get(\".\"))"
    )
    assert(err.nonEmpty, "BannerInputs.from(Path) must not compile — the engine cannot be handed a file to read")

  // spec: live-fact-banner — Compile-Negative: DriftScan.installRoots narrowed
  test("compile-negative: an InstallRoots value with fewer than six roots does not compile"):
    val err: String = compileErrors(
      "org.sinemenda.probatio.core.InstallRoots(org.sinemenda.probatio.core.InstallRootRef(org.sinemenda.probatio.core.RootBase.RepoRoot, \".agents/skills\"))"
    )
    assert(err.nonEmpty, "a narrowed InstallRoots must not compile")

  // ════════════════════════════════════════════════════════════════════
  // Property: banner-states-only-read-facts
  // spec: live-fact-banner — Property: banner-states-only-read-facts
  //
  // Every presence word, absence word, and integer count in the emitted
  // banner is derivable from the facts record — the anti-fabrication
  // invariant. Generated over RepositoryFacts directly (the render is a
  // pure projection of it).
  // ════════════════════════════════════════════════════════════════════

  private def factLineCheck(
    text: String,
    marker: String,
    fact: FactRead[Int],
    presentPat: Int => String
  ): Result =
    val line: Option[String] = text.linesIterator.find(_.contains(marker))
    fact match
      case FactRead.Present(n) =>
        Result
          .assert(line.exists(l => l.contains(presentPat(n))))
          .log(s"$marker: expected '${presentPat(n)}' in line ${line.getOrElse("<none>")}")
      case FactRead.Absent =>
        Result
          .assert(line.exists(l => l.contains("ABSENT") && !l.contains("PRESENT")))
          .log(s"$marker: expected ABSENT in line ${line.getOrElse("<none>")}")
      case FactRead.Unreadable(_) =>
        Result
          .assert(line.exists(l => l.contains("UNREADABLE")))
          .log(s"$marker: expected UNREADABLE in line ${line.getOrElse("<none>")}")

  property("banner-states-only-read-facts", coverConfig):
    for f <- genRepositoryFacts.forAll
        .cover(
          40,
          "registry-present",
          (x: RepositoryFacts) =>
            x.registry match
              case FactRead.Present(_) => true
              case _                   => false
        )
        .cover(
          25,
          "registry-absent",
          (x: RepositoryFacts) =>
            x.registry match
              case FactRead.Absent => true
              case _               => false
        )
    yield
      val text: String = BannerEngine.render(BannerInputs.from(f)).payload

      val registryR: Result =
        factLineCheck(text, "registry", f.registry, n => s"PRESENT ($n concepts)")
      val inventoryR: Result =
        factLineCheck(text, "type inventory", f.inventory, n => s"PRESENT ($n typed rows)")
      val profileR: Result =
        val line: Option[String] = text.linesIterator.find(_.contains("capability profile"))
        f.profile match
          case FactRead.Present(_) =>
            Result
              .assert(line.exists(l => l.contains("PRESENT")))
              .log(s"profile line: ${line.getOrElse("<none>")}")
          case FactRead.Absent =>
            Result
              .assert(line.exists(l => l.contains("ABSENT")))
              .log(s"profile line: ${line.getOrElse("<none>")}")
          case FactRead.Unreadable(_) =>
            Result
              .assert(line.exists(l => l.contains("UNREADABLE")))
              .log(s"profile line: ${line.getOrElse("<none>")}")
      val schemaR: Result =
        val line: Option[String] = text.linesIterator.find(_.contains("openspec/schemas/verified-scala3"))
        f.schemaVersion match
          case FactRead.Present(v) =>
            Result
              .assert(line.exists(_.contains(s"v$v")))
              .log(s"schema line: ${line.getOrElse("<none>")}")
          case FactRead.Absent =>
            Result
              .assert(line.exists(_.contains("ABSENT")))
              .log(s"schema line: ${line.getOrElse("<none>")}")
          case FactRead.Unreadable(_) =>
            Result
              .assert(line.exists(_.contains("UNREADABLE")))
              .log(s"schema line: ${line.getOrElse("<none>")}")

      // Every stated unresolved count equals a measured report's list length.
      val unresolvedR: Result =
        val stated: List[Int] =
          "unresolved (\\d+)".r.findAllMatchIn(text).map(_.group(1).toInt).toList
        val expected: List[Int] = f.activeChanges match
          case FactRead.Present(cs) =>
            cs.collect { case ActiveChangeWithChainState(_, _, Right(r)) =>
              r.unresolved.length
            }
          case _ => Nil
        Result
          .assert(stated.sorted == expected.sorted)
          .log(s"stated unresolved counts $stated != report counts $expected")

      // Undetermined reports state undetermined, never a clean zero for that change.
      val undeterminedR: Result =
        val expected: Int = f.activeChanges match
          case FactRead.Present(cs) =>
            cs.count {
              case ActiveChangeWithChainState(_, _, Left(_)) => true
              case _                                         => false
            }
          case _ => 0
        val stated: Int = text.linesIterator.count(_.trim.startsWith("undetermined —"))
        Result
          .assert(stated == expected)
          .log(s"undetermined lines $stated != expected $expected")

      // Every drift warning names its root.
      val driftR: Result =
        val baseline: Option[Int] = f.schemaVersion match
          case FactRead.Present(v) => Some(v)
          case _                   => None
        val expectedRoots: Set[String] =
          DriftScan.scan(baseline, f.installRoots).warnings.map(_.rootPath).toSet
        Result
          .assert(expectedRoots.forall(text.contains))
          .log(s"warning roots missing from text: $expectedRoots")

      registryR.and(inventoryR).and(profileR).and(schemaR).and(unresolvedR).and(undeterminedR).and(driftR)

  // ════════════════════════════════════════════════════════════════════
  // Property: suppression-tracks-facts
  // spec: live-fact-banner — Property: suppression-tracks-facts
  //
  // The second injection in a session is suppressed iff the facts are
  // equal. Modelled as: writeFingerprint(f1) then check whether f2's
  // fingerprint reads back as equal — over the generated domain, fingerprint
  // equality coincides with record equality (the encoding is injective).
  // ════════════════════════════════════════════════════════════════════

  property(
    "suppression-tracks-facts",
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(500))
  ):
    for
      triple <- genFactsPair.forAll
        .cover(40, "equal", (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.isEmpty)
        .cover(40, "changed", (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.isDefined)
        .cover(
          5,
          "changed-schema",
          (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.contains(MutatedField.SchemaVersion)
        )
        .cover(
          5,
          "changed-registry",
          (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.contains(MutatedField.Registry)
        )
        .cover(
          5,
          "changed-inventory",
          (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.contains(MutatedField.Inventory)
        )
        .cover(
          5,
          "changed-profile",
          (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.contains(MutatedField.Profile)
        )
        .cover(
          5,
          "changed-roots",
          (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.contains(MutatedField.InstallRoots)
        )
        .cover(
          5,
          "changed-changes",
          (t: (RepositoryFacts, RepositoryFacts, Option[MutatedField])) => t._3.contains(MutatedField.ActiveChanges)
        )
      sid <- Gen.string(Gen.alpha, Range.linear(3, 15)).map(SessionId.fromRaw).forAll
    yield
      val (f1, f2, _) = triple
      withTempDir("gate-fp") { (dirPath: Path) =>
        val dir: GateStateDir = GateStateDir(dirPath)
        GateStateDirReader.writeFingerprint(dir, sid, f1.fingerprint)
        val recorded: Option[String] = GateStateDirReader.readFingerprint(dir, sid)
        val wouldSuppress: Boolean   = recorded.contains(f2.fingerprint)
        Result
          .assert(recorded.contains(f1.fingerprint))
          .log("the recorded fingerprint must read back")
          .and(
            Result
              .assert(wouldSuppress == (f1 == f2))
              .log(s"suppress=$wouldSuppress but equal=${f1 == f2}")
          )
      }

end LiveFactBannerSpec
