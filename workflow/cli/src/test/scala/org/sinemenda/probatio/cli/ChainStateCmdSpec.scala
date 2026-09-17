package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.Outcome

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal

/**
 * The cli-level oracle for chain-state attribution (spec 5).
 *
 * Written against the spec's scenarios and the Step-1 contract before the
 * Step-3 implementation exists: every test that exercises real extraction
 * is expected RED at the Step-2 polarity check (the extraction path and
 * `compute` body are `???`).
 *
 * Environment contract the oracle pins (Step 3 must honour it — the names
 * are the port's, `OPENSPEC_ROOT` is the predecessor's own):
 *   - `OPENSPEC_ROOT` — the root `openspec-graph.py export` builds against;
 *     the export fails (exit 2) when `<root>/openspec` does not exist.
 *   - `PROBATIO_SCANNER_DIR` — the directory holding `openspec-graph.py`.
 *     Without it the port cannot reach the extractor and MUST announce the
 *     degraded fallback, never claim the extractor ran.
 *
 * Fixture layout mirrors the bats oracle: the change directory IS the
 * fixture root — `specs/<name>/spec.md` + `evidence-ledger.jsonl` — and
 * `OPENSPEC_ROOT` is pointed elsewhere so the graph export fails and the
 * predecessor runs its degraded arm (exactly what chain-state.bats does).
 *
 * spec: chain-state-attribution
 */
final class ChainStateCmdSpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(100))

  // ── fixture builders (the bats shapes, verbatim) ─────────────────────

  private val specHeader: String =
    """# Spec: Fixture
      |
      |## Concepts Used (behavioral)
      |
      || Concept | Role here | File |
      ||---------|-----------|------|
      || (none) | fixture spec | — |
      |
      |## Concepts Used (from inventory)
      |
      || Concept | Kind | Package |
      ||---------|------|---------|
      |
      |## ADDED Requirements
      |
      |""".stripMargin

  private def reqBlock(title: String): String =
    s"""### Requirement: $title
       |
       |The system SHALL do the thing named $title.
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
       |""".stripMargin

  private val poHeader: String =
    "## Proof Obligations\n\n| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n"

  /** A code-shaped artifact token that IS tracked in this repository. */
  private val resolvesArtifact: String =
    "openspec/schemas/verified-scala3/tests/correctness-invariant.bats"

  /** A code-shaped token that matches nothing — F9 fires. */
  private val unresolvedArtifact: String =
    "tests/totally-fake-nonexistent-fixture-artifact.bats"

  private val baseline: String = "00d3de1"
  private val change: String   = "fixture-change"

  /** Write `text` as `specs/<spec>/spec.md` under `fx`. */
  private def writeSpec(fx: Path, spec: String, text: String): Unit =
    val dir: Path = fx.resolve("specs").resolve(spec)
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("spec.md"), text, StandardCharsets.UTF_8)

  private def writeLedger(fx: Path, lines: List[String]): Unit =
    Files.writeString(
      fx.resolve("evidence-ledger.jsonl"),
      lines.mkString("", "\n", if lines.isEmpty then "" else "\n"),
      StandardCharsets.UTF_8
    )

  private def ledgerJson(
    spec: String,
    obligation: String,
    artifact: String,
    exit: Int,
    baseline: String = baseline,
    ring: String = "manual"
  ): String =
    s"{\"v\":1,\"ts\":\"2026-09-17T00:00:00Z\",\"change\":\"$change\"," +
      s"\"spec\":\"$spec\",\"ring\":\"$ring\",\"obligation\":\"$obligation\"," +
      s"\"artifact\":\"$artifact\",\"command\":\"true\",\"exit\":$exit," +
      s"\"baseline\":\"$baseline\"}"

  /** A directory guaranteed to have no `openspec/` child — forces degraded. */
  private def noOpenspecRoot(fx: Path): Path =
    val root: Path = fx.resolve("no-openspec-root")
    Files.createDirectories(root)
    root

  /** The real scanner dir — `openspec-graph.py` lives there. */
  private def scannerDir: Path =
    repoRoot.resolve("openspec/schemas/verified-scala3/scanner")

  private def repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(fail(s"could not locate the repository root from $start"))

  private def runCmd(fx: Path, env: Map[String, String]): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(
      ChainStateCmd.run(
        Array(
          "--change-dir",
          fx.toString,
          "--change",
          change,
          "--baseline",
          baseline
        ),
        env
      )
    )

  private def degradedEnv(fx: Path): Map[String, String] =
    Map("OPENSPEC_ROOT" -> noOpenspecRoot(fx).toString)

  private def countOccurrences(haystack: String, needle: String): Int =
    haystack.sliding(needle.length).count(_ == needle)

  /** The `reason` field of the undetermined report, when stdout parses. */
  private def undeterminedReportReason(stdout: String): Option[String] =
    val parsed: ujson.Value = ujson.read(stdout.trim)
    parsed.obj.get("reason").map(_.str)

  // ════════════════════════════════════════════════════════════════════
  // Scenario: a change directory with no readable spec documents is could-not-determine
  // spec: chain-state-attribution — Scenario: Error path — a change directory with no readable spec documents is could-not-determine
  // ════════════════════════════════════════════════════════════════════

  test("a change directory with no readable spec documents is could-not-determine"):
    LiveFactFixtures.withTempDir("chain-state-no-specs") { (fx: Path) =>
      writeLedger(fx, Nil)
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx))
      outcome match
        case Outcome.Undetermined(_) =>
          assert(
            stdout.contains("\"undetermined\":true") || stdout.contains("\"undetermined\": true"),
            s"the undetermined report must still be emitted, got: $stdout"
          )
        case Outcome.Ran(_) =>
          fail(s"no readable specs must not be a clean result: $stdout")
        case Outcome.Finding(_) =>
          fail(s"no readable specs must not collapse to a finding: $stdout")
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: a genuinely empty requirement set is distinguishable from an unread one
  // spec: chain-state-attribution — Scenario: Adversarial — a genuinely empty requirement set is distinguishable from an unread one
  // ════════════════════════════════════════════════════════════════════

  test("a spec document with zero requirements reports a clean zero total"):
    LiveFactFixtures.withTempDir("chain-state-empty") { (fx: Path) =>
      writeSpec(fx, "only", specHeader + poHeader + "| n/a | n/a | n/a | n/a |\n")
      writeLedger(fx, Nil)
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx))
      outcome match
        case Outcome.Ran(_) =>
          val parsed: ujson.Value = ujson.read(stdout.trim)
          assertEquals(parsed("total").num.toInt, 0, "a readable empty set reports total 0")
          assertEquals(parsed("unresolved").arr.toList.length, 0)
        case other =>
          fail(s"a readable-but-empty change is clean, not undetermined/finding: $other")
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: the transitive extractor is available and is named
  // spec: chain-state-attribution — Scenario: Happy path — the transitive extractor is available and is named
  // ════════════════════════════════════════════════════════════════════

  test("the graph extraction path names the transitive extractor as the source"):
    LiveFactFixtures.withTempDir("chain-state-graph") { (fx: Path) =>
      // An openspec/ tree under the fixture root so the export succeeds;
      // the scanner dir supplies openspec-graph.py itself.
      val root: Path = fx.resolve("graph-root")
      Files.createDirectories(root.resolve("openspec"))
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl | Requirement 1 | manual | `$resolvesArtifact` |\n"
      )
      writeLedger(fx, Nil)
      val env: Map[String, String] = Map(
        "OPENSPEC_ROOT"        -> root.toString,
        "PROBATIO_SCANNER_DIR" -> scannerDir.toString
      )
      val (_, stderr, _) = runCmd(fx, env)
      assert(
        stderr.contains("openspec-graph"),
        s"the diagnostic channel must name the transitive extractor, got: $stderr"
      )
      assert(
        !stderr.contains("degraded"),
        s"the graph path must not be reported as the fallback, got: $stderr"
      )
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: the extractor is unavailable and the fallback is announced
  // spec: chain-state-attribution — Scenario: Error path — the extractor is unavailable and the fallback is announced
  // ════════════════════════════════════════════════════════════════════

  test("when the extractor is not runnable the fallback path is announced and the result is still produced"):
    LiveFactFixtures.withTempDir("chain-state-degraded") { (fx: Path) =>
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
      )
      writeLedger(fx, Nil)
      val (stdout, stderr, _) = runCmd(fx, degradedEnv(fx))
      assert(
        stderr.contains("degraded"),
        s"the diagnostic channel must state the fallback path was used, got: $stderr"
      )
      assert(stdout.trim.nonEmpty, "a report is still produced on the output channel")
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: a fallback result is never reported as an extractor result
  // spec: chain-state-attribution — Scenario: Adversarial — a fallback result is never reported as an extractor result
  // ════════════════════════════════════════════════════════════════════

  test("a degraded run never presents the extractor as its source"):
    LiveFactFixtures.withTempDir("chain-state-never-graph") { (fx: Path) =>
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
      )
      writeLedger(fx, Nil)
      val (_, stderr, _) = runCmd(fx, degradedEnv(fx))
      stderr.linesIterator.foreach { (line: String) =>
        assert(
          !line.contains("graph") || line.contains("degraded"),
          s"the extractor may only be named in a fallback context, got line: $line"
        )
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: a missing ledger produces a single-marker diagnostic
  // spec: chain-state-attribution — Scenario: Error path — a missing ledger produces a single-marker diagnostic
  // ════════════════════════════════════════════════════════════════════

  test("a missing ledger produces a diagnostic with the marker exactly once"):
    LiveFactFixtures.withTempDir("chain-state-marker") { (fx: Path) =>
      // A readable spec is required so fact measurement completes and the
      // ledger read is reached — the predecessor enumerates specs before
      // reading the ledger, so a missing spec tree preempts this path.
      writeSpec(fx, "only", specHeader + poHeader)
      val missing: Path = fx.resolve("no-such-ledger.jsonl")
      val (stdout, stderr, outcome) = StdoutCapture.captureBoth(
        ChainStateCmd.run(
          Array(
            "--change-dir",
            fx.toString,
            "--change",
            change,
            "--baseline",
            baseline,
            "--ledger-file",
            missing.toString
          ),
          degradedEnv(fx)
        )
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"missing ledger must be undetermined, got $other")
      val markerLines: List[String] =
        stderr.linesIterator.filter(_.contains("UNDETERMINED")).toList
      assertEquals(
        markerLines.length,
        1,
        s"exactly one diagnostic line carries the marker, got: $stderr"
      )
      assertEquals(
        countOccurrences(stderr, "UNDETERMINED"),
        1,
        s"the marker appears exactly once in the diagnostic channel, got: $stderr"
      )
      assert(
        stderr.contains("no-such-ledger.jsonl"),
        s"the diagnostic names the missing path, got: $stderr"
      )
      // The report's reason field must not double the marker either.
      undeterminedReportReason(stdout).foreach { (reason: String) =>
        assertEquals(
          countOccurrences(reason, "UNDETERMINED"),
          0,
          s"the report's reason field carries the marker at most zero times — it is a reason, not a diagnostic line: $reason"
        )
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: the report is still emitted on the output channel when undetermined
  // spec: chain-state-attribution — Scenario: Happy path — the report is still emitted on the output channel when undetermined
  // ════════════════════════════════════════════════════════════════════

  test("an undetermined run still emits the report on stdout"):
    LiveFactFixtures.withTempDir("chain-state-und-report") { (fx: Path) =>
      writeSpec(fx, "only", specHeader + poHeader)
      val (stdout, _, outcome) = StdoutCapture.captureBoth(
        ChainStateCmd.run(
          Array(
            "--change-dir",
            fx.toString,
            "--change",
            change,
            "--baseline",
            baseline,
            "--ledger-file",
            fx.resolve("missing.jsonl").toString
          ),
          degradedEnv(fx)
        )
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"expected undetermined, got $other")
      val parsed: ujson.Value = ujson.read(stdout.trim)
      assertEquals(parsed("change").str, change)
      assertEquals(parsed("baseline").str, baseline)
      assert(parsed("undetermined").bool, "the report carries the undetermined flag")
      assert(parsed("reason").str.nonEmpty, "the report states its reason")
      List("total", "bound", "resolved", "discharged").foreach { (k: String) =>
        assert(parsed.obj(k) == ujson.Null, s"count $k is null in an undetermined report")
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: unmapped obligations exit nonzero even with zero unresolved
  // spec: chain-state-attribution — Requirement: An obligation that maps to no known requirement is reported separately
  // (predecessor: unresolved_n > 0 || unmapped_n > 0 → exit 1)
  // ════════════════════════════════════════════════════════════════════

  test("an unmapped obligation with every requirement discharged is still a finding"):
    LiveFactFixtures.withTempDir("chain-state-unmapped") { (fx: Path) =>
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl one | Requirement: Solo Req | manual | `$resolvesArtifact` |\n" +
          s"| orphan | Property: dangling-prop | manual | `$unresolvedArtifact` |\n"
      )
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx))
      val parsed: ujson.Value  = ujson.read(stdout.trim)
      assert(
        parsed("unmapped_obligations").arr.nonEmpty,
        s"the orphan row carrying a finding must be listed, got: $stdout"
      )
      outcome match
        case Outcome.Finding(_) => ()
        case other =>
          fail(s"unmapped_obligations > 0 must exit nonzero even with zero unresolved, got $other")
    }

  // ════════════════════════════════════════════════════════════════════
  // Flag surface — the spec anchors `--format`, `--spec`, `--artifacts`,
  // `--forgive-unchanged` on ChainStateCmd.
  // spec: chain-state-attribution — Implementation Anchor: ChainStateCmd
  // ════════════════════════════════════════════════════════════════════

  test("the spec-mandated flags are accepted, not rejected as unknown"):
    LiveFactFixtures.withTempDir("chain-state-flags") { (fx: Path) =>
      writeSpec(fx, "only", specHeader + poHeader)
      writeLedger(fx, Nil)
      List("--format", "--artifacts", "--forgive-unchanged").foreach { (flag: String) =>
        val args: Array[String] =
          if flag == "--format" then
            Array("--change-dir", fx.toString, "--change", change, "--baseline", baseline, "--format", "json")
          else Array("--change-dir", fx.toString, "--change", change, "--baseline", baseline, flag)
        val (_, stderr, outcome) = StdoutCapture.captureBoth(ChainStateCmd.run(args, degradedEnv(fx)))
        outcome match
          case Outcome.Finding(msg) =>
            assert(
              !msg.contains("arg parse error") && !stderr.contains(flag),
              s"flag $flag must be accepted, got: $msg / $stderr"
            )
          case _ => ()
      }
      val (_, stderr2, outcome2) = StdoutCapture.captureBoth(
        ChainStateCmd.run(
          Array("--change-dir", fx.toString, "--change", change, "--baseline", baseline, "--spec", "only"),
          degradedEnv(fx)
        )
      )
      outcome2 match
        case Outcome.Finding(msg) =>
          assert(
            !msg.contains("arg parse error"),
            s"flag --spec must be accepted, got: $msg / $stderr2"
          )
        case _ => ()
    }

  // ════════════════════════════════════════════════════════════════════
  // Property: empty-is-not-unreadable
  // spec: chain-state-attribution — Property: empty-is-not-unreadable
  // ════════════════════════════════════════════════════════════════════

  /**
   * A change with zero requirements and a readable empty ledger vs the same
   * change with an unreadable ledger — the results must differ in exit
   * status. The spec's pair generator, constructive by construction.
   */
  private def genEmptyVsUnreadable: Gen[(Path, Path)] =
    Gen.string(Gen.alphaNum, Range.linear(5, 12)).map { (suffix: String) =>
      val base: Path       = Files.createTempDirectory("chain-state-pair-" + suffix)
      val readable: Path   = base.resolve("readable")
      val unreadable: Path = base.resolve("unreadable")
      Files.createDirectories(readable)
      Files.createDirectories(unreadable)
      List(readable, unreadable).foreach { (fx: Path) =>
        writeSpec(fx, "only", specHeader + poHeader + "| n/a | n/a | n/a | n/a |\n")
      }
      writeLedger(readable, Nil)
      // "Unreadable" = corrupt JSONL — a line that is not a ledger record.
      writeLedger(unreadable, List("{not json"))
      (readable, unreadable)
    }

  property("empty-is-not-unreadable", coverConfig):
    for pair <- genEmptyVsUnreadable.forAll
        .cover(50, "pair-generated", (_: (Path, Path)) => true)
    yield
      val (readableFx, unreadableFx) = pair
      try
        val (_, _, r1) = runCmd(readableFx, degradedEnv(readableFx))
        val (_, _, r2) = runCmd(unreadableFx, degradedEnv(unreadableFx))
        val exit1: Int = Outcome.toExitCode(r1)
        val exit2: Int = Outcome.toExitCode(r2)
        Result
          .assert(exit1 != exit2)
          .log(s"empty-but-readable ($exit1) and unreadable ($exit2) must differ")
      finally // scalafix:ok DisableSyntax.NoKeywordFinally
        // fixture cleanup must always run
        LiveFactFixtures.deleteTree(readableFx.getParent)

  // ════════════════════════════════════════════════════════════════════
  // Ring-8 fix coverage: the adversarial findings' regression tests.
  // ════════════════════════════════════════════════════════════════════

  /**
   * R8-F1: an existing-but-unlistable `specs/` tree is a discovery fault —
   * undetermined, never a clean zero. `Files.walk` throws on a mode-000
   * directory; environments that permit the walk anyway (root, non-POSIX)
   * skip the end-to-end assertion via `cancel`.
   */
  test("an unlistable specs tree is could-not-determine, never a clean zero"):
    LiveFactFixtures.withTempDir("chain-state-unlistable") { (fx: Path) =>
      val specsDir: Path = fx.resolve("specs")
      Files.createDirectories(specsDir.resolve("only"))
      Files.writeString(
        specsDir.resolve("only").resolve("spec.md"),
        specHeader + poHeader,
        StandardCharsets.UTF_8
      )
      writeLedger(fx, Nil)
      try Files.setPosixFilePermissions(specsDir, java.util.Set.of())
      catch case NonFatal(_) => ()
      try
        SpecLintCmd.findSpecs(specsDir, (_: Path) => true) match
          case Left(_) =>
            val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx))
            outcome match
              case Outcome.Undetermined(_) =>
                assert(
                  stdout.contains("\"undetermined\":true") || stdout.contains("\"undetermined\": true"),
                  s"the undetermined report must still be emitted, got: $stdout"
                )
              case other =>
                fail(s"an unlistable spec tree must not produce a result: $other — $stdout")
          case Right(_) =>
            assume(
              false,
              "the environment permits walking a mode-000 directory; the fault cannot be simulated"
            )
      finally // scalafix:ok DisableSyntax.NoKeywordFinally
        // restore listability so fixture cleanup can delete the tree
        try
          Files.setPosixFilePermissions(
            specsDir,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
          )
        catch case NonFatal(_) => ()
    }

  /**
   * R8-F3: a parseable export that fails the `.obligations` usability
   * gate takes the degraded path and is announced as invalid JSON — the
   * "extractor ran" diagnostic may only name the graph path when the
   * export is actually usable.
   */
  test("a parseable export without .obligations is announced as invalid JSON, never as graph"):
    LiveFactFixtures.withTempDir("chain-state-unusable-export") { (fx: Path) =>
      assume(pythonAvailable(), "the graph-export probe needs python3")
      val stubDir: Path = fx.resolve("stub-scanner")
      Files.createDirectories(stubDir)
      Files.writeString(
        stubDir.resolve("openspec-graph.py"),
        "import sys\nprint('{\"requirements\": []}')\n",
        StandardCharsets.UTF_8
      )
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
      )
      writeLedger(fx, Nil)
      val env: Map[String, String] = Map(
        "OPENSPEC_ROOT"        -> fx.toString,
        "PROBATIO_SCANNER_DIR" -> stubDir.toString
      )
      val (_, stderr, _) = runCmd(fx, env)
      assert(
        stderr.contains("produced invalid JSON"),
        s"an unusable export is announced as invalid JSON, got: $stderr"
      )
      assert(
        !stderr.contains("export (graph)"),
        s"the graph-path diagnostic may not name an unusable export, got: $stderr"
      )
    }

  /**
   * R8-F6: a non-empty per-spec baseline map makes a failed ledger read
   * non-fatal — the predecessor skips the per-spec read and the spec's
   * requirements report undischarged (exit 1), never undetermined.
   */
  test("a corrupt ledger under a populated baseline map is undischarged, not undetermined"):
    LiveFactFixtures.withTempDir("chain-state-map-corrupt") { (fx: Path) =>
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
      )
      writeLedger(fx, List("{corrupt"))
      Files.writeString(
        fx.resolve("implementation-progress.md"),
        "**BASELINE SHA**: `dead000`\n\n## Spec 1: only\n\n### Baseline\nSHA `00d3de1`\n",
        StandardCharsets.UTF_8
      )
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx))
      outcome match
        case Outcome.Finding(_) =>
          val parsed: ujson.Value = ujson.read(stdout.trim)
          val reasons: List[String] =
            parsed("unresolved").arr.headOption.toList
              .flatMap((e: ujson.Value) => e("reasons").arr.map(_.str).toList)
          assert(
            reasons.contains("undischarged"),
            s"the mapped spec's requirement reports undischarged, got: $stdout"
          )
        case other =>
          fail(s"baseline-map tolerance: a corrupt ledger is undischarged, got $other — $stdout")
    }

  /**
   * R8-N1: the contract admits any integer `v >= 1`, but the READER knows
   * only v=1 — the predecessor refuses a `v:2` row with die_undetermined
   * ("Refusing to report a partial result"). A contract-valid,
   * version-unknown row must never be admitted as discharge evidence.
   */
  test("a v:2 ledger row is undetermined, not discharge evidence"):
    LiveFactFixtures.withTempDir("chain-state-v2-row") { (fx: Path) =>
      writeSpec(
        fx,
        "only",
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| obl | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
      )
      // Contract-valid (v is an integer >= 1) but a version this reader
      // does not know — the predecessor dies undetermined on it.
      writeLedger(
        fx,
        List(
          s"{\"v\":2,\"ts\":\"2026-09-17T00:00:00Z\",\"change\":\"$change\"," +
            s"\"spec\":\"only\",\"ring\":\"manual\",\"obligation\":\"obl\"," +
            s"\"artifact\":\"$resolvesArtifact\",\"command\":\"true\",\"exit\":0," +
            s"\"baseline\":\"$baseline\"}"
        )
      )
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx))
      outcome match
        case Outcome.Undetermined(_) =>
          assert(
            stdout.contains("\"undetermined\":true") || stdout.contains("\"undetermined\": true"),
            s"the undetermined report must still be emitted, got: $stdout"
          )
        case other =>
          fail(s"a v:2 row must refuse the whole read, not discharge: $other — $stdout")
    }

  /** `python3` is runnable — the graph-export probe's own prerequisite. */
  private def pythonAvailable(): Boolean =
    try new ProcessBuilder("python3", "-c", "pass").start().waitFor() == 0
    catch case NonFatal(_) => false
