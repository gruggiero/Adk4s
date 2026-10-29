package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.Outcome

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The adapter-level oracle for chain-state-undetermined-fidelity (spec 2).
 *
 * Written against the spec and the Step-1 contract BEFORE the adapter
 * implementation exists. Every test drives `ChainStateCmd` through the
 * environment the predecessor honours — `SPEC_LINT_OVERRIDE` names the
 * pre-pass executable; small stub scripts supply the process outcomes a
 * real pre-pass would produce (the deterministic substitution the design
 * records: recorded process outcomes, no wall-clock).
 *
 * The asserted contract is the predecessor's three-way boundary:
 *   - the resolved pre-pass is absent, non-executable, exits outside
 *     {0,1}, or produces no recognised completion marker → the outcome is
 *     could-not-determine carrying a reason that names the input, and no
 *     count fields;
 *   - the pre-pass completes → the verdict is measured and carries the
 *     three counts;
 *   - exit statuses are total and disjoint: 0 clean, 1 finding,
 *     2 could-not-determine.
 *
 * spec: chain-state-undetermined-fidelity
 */
final class ChainStateUndeterminedFidelitySpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(240))

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

  private val resolvesArtifact: String =
    "openspec/schemas/verified-scala3/tests/correctness-invariant.bats"

  private val baseline: String = "00d3de1"

  /**
   * `git rev-parse 00d3de1` — ledger rows record the RESOLVED baseline
   *  (the predecessor's `ledger.sh append` stores the rev-parsed SHA), so
   *  a matching row carries the full SHA, not the short argument.
   */
  private val fullBaseline: String = "00d3de1aa49141277dc4855a353f009fc7cc941a"

  /**
   * A well-formed hex SHA that is not a commit in this repository — a
   *  stale row's baseline: it passes the record contract's hex check but
   *  never qualifies (not the resolved baseline, and `git diff` fails on
   *  it so forgiveness cannot apply).
   */
  private val staleBaseline: String = "ffffffffffffffffffffffffffffffffffffffff"
  private val change: String        = "fixture-change"

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
    baseline: String = fullBaseline
  ): String =
    s"{\"v\":1,\"ts\":\"2026-09-17T00:00:00Z\",\"change\":\"$change\"," +
      s"\"spec\":\"$spec\",\"ring\":\"manual\",\"obligation\":\"$obligation\"," +
      s"\"artifact\":\"$artifact\",\"command\":\"true\",\"exit\":$exit," +
      s"\"baseline\":\"$baseline\"}"

  /** A spec with one requirement bound by an exact-title obligation row. */
  private def oneReqSpec(fx: Path): Unit =
    writeSpec(
      fx,
      "only",
      specHeader + reqBlock("Solo Req") + poHeader +
        s"| obl one | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
    )

  // ── the pre-pass stubs the environment declares ──────────────────────
  //
  // A "completing" stub reproduces the completion marker spec-lint emits:
  // in JSON mode a JSON array; otherwise `spec-lint: N spec file(s),
  // F FAIL, W WARN` where N is the spec-file count it observes — the
  // cross-check the predecessor applies (its own enumeration must agree).
  // Deterministic: recorded output, no wall-clock.

  private val completingStubBody: String =
    """#!/usr/bin/env bash
      |target=""
      |json=0
      |while [ $# -gt 0 ]; do
      |  case "$1" in
      |    --format) json=1; shift 2 ;;
      |    --*) shift ;;
      |    *) target="$1"; shift ;;
      |  esac
      |done
      |if [ "$json" -eq 1 ]; then echo '[]'; exit 0; fi
      |n="$(find "$target/specs" -name spec.md 2>/dev/null | grep -c .)"
      |if [ "$n" -eq 0 ]; then
      |  echo "spec-lint: no spec files to lint under $target"
      |  exit 0
      |fi
      |echo "spec-lint: $n spec file(s), 0 FAIL, 0 WARN"
      |exit 0
      |""".stripMargin

  private def writeStub(fx: Path, name: String, body: String, executable: Boolean): Path =
    val p: Path = fx.resolve(name)
    Files.writeString(p, body, StandardCharsets.UTF_8)
    p.toFile.setExecutable(executable)
    p

  private def completingStub(fx: Path): Path =
    writeStub(fx, "spec-lint-ok.sh", completingStubBody, executable = true)

  private def exit42Stub(fx: Path): Path =
    writeStub(
      fx,
      "spec-lint-crash.sh",
      "#!/usr/bin/env bash\nexit 42\n",
      executable = true
    )

  /** exit 1 — a declared lint-finding exit — but no completion marker. */
  private def noMarkerStub(fx: Path): Path =
    writeStub(
      fx,
      "spec-lint-nomarker.sh",
      "#!/usr/bin/env bash\necho 'spec-lint died inside find; no summary line'\nexit 1\n",
      executable = true
    )

  /** The marker, but a count that disagrees with the enumerated tree. */
  private def wrongCountStub(fx: Path): Path =
    writeStub(
      fx,
      "spec-lint-wrongcount.sh",
      "#!/usr/bin/env bash\necho 'spec-lint: 7 spec file(s), 0 FAIL, 0 WARN'\nexit 0\n",
      executable = true
    )

  /** spec-lint's own legitimate-zero message — while specs DO exist. */
  private def claimsNoSpecsStub(fx: Path): Path =
    writeStub(
      fx,
      "spec-lint-nospecs.sh",
      "#!/usr/bin/env bash\necho 'spec-lint: no spec files to lint under x'\nexit 0\n",
      executable = true
    )

  /** Graph mode: exit 0 but stdout is not a JSON array. */
  private def nonJsonStub(fx: Path): Path =
    writeStub(
      fx,
      "spec-lint-nonjson.sh",
      "#!/usr/bin/env bash\necho 'not a json array'\nexit 0\n",
      executable = true
    )

  // ── invocation helpers ───────────────────────────────────────────────

  private def noOpenspecRoot(fx: Path): Path =
    val root: Path = fx.resolve("no-openspec-root")
    Files.createDirectories(root)
    root

  private def scannerDir: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(fail(s"could not locate the repository root from $start"))
      .resolve("openspec/schemas/verified-scala3/scanner")

  private def degradedEnv(fx: Path, prePass: Path): Map[String, String] =
    Map(
      "OPENSPEC_ROOT"      -> noOpenspecRoot(fx).toString,
      "SPEC_LINT_OVERRIDE" -> prePass.toString
    )

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

  private val countKeys: List[String] = List("total", "bound", "resolved", "discharged")

  /** The emitted undetermined report carries no count field — null or absent. */
  private def assertNoCounts(stdout: String): Unit =
    val parsed: ujson.Value = ujson.read(stdout.trim)
    assertEquals(parsed("undetermined").bool, true, s"the report must be undetermined: $stdout")
    countKeys.foreach { (k: String) =>
      assert(
        !parsed.obj.contains(k) || parsed(k) == ujson.Null,
        s"could-not-determine must not carry a $k figure: $stdout"
      )
    }

  /** The emitted undetermined report names the input that could not run. */
  private def assertReasonNames(stdout: String, needle: String): Unit =
    val parsed: ujson.Value = ujson.read(stdout.trim)
    val reason: String      = parsed("reason").str
    assert(reason.nonEmpty, s"the reason is never empty: $stdout")
    assert(
      reason.contains(needle),
      s"the reason names the unavailable input ('$needle'), got: $reason"
    )

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Happy path — a completed pre-pass yields a verdict with counts
  // spec: chain-state-undetermined-fidelity — Scenario: Happy path — a completed pre-pass yields a verdict with counts
  // ════════════════════════════════════════════════════════════════════

  test("a completed pre-pass yields a verdict with counts"):
    LiveFactFixtures.withTempDir("cs-und-completed") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, completingStub(fx)))
      outcome match
        case Outcome.Ran(_) => ()
        case other          => fail(s"a completed pre-pass + discharged evidence is clean: $other")
      val parsed: ujson.Value = ujson.read(stdout.trim)
      assertEquals(parsed("total").num.toInt, 1)
      assertEquals(parsed("bound").num.toInt, 1)
      assertEquals(parsed("resolved").num.toInt, 1)
      assertEquals(parsed("discharged").num.toInt, 1)
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Error path — a pre-pass that cannot be executed is could-not-determine
  // spec: chain-state-undetermined-fidelity — Scenario: Error path — a pre-pass that cannot be executed is could-not-determine
  // ════════════════════════════════════════════════════════════════════

  test("a pre-pass that cannot be executed is could-not-determine"):
    LiveFactFixtures.withTempDir("cs-und-noexec") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      // absent — the resolved path does not exist
      val absent: Path = fx.resolve("does-not-exist.sh")
      val (s1, _, o1)  = runCmd(fx, degradedEnv(fx, absent))
      o1 match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"an absent pre-pass must be could-not-determine, got: $other")
      assertNoCounts(s1)
      assertReasonNames(s1, "does-not-exist.sh")
      // present but not executable — the predecessor's exec fails (126)
      val nonExec: Path = writeStub(
        fx,
        "spec-lint-nonexec.sh",
        "#!/usr/bin/env bash\nexit 0\n",
        executable = false
      )
      val (s2, _, o2) = runCmd(fx, degradedEnv(fx, nonExec))
      o2 match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"a non-executable pre-pass must be could-not-determine, got: $other")
      assertNoCounts(s2)
      assertReasonNames(s2, "spec-lint-nonexec.sh")
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts
  // spec: chain-state-undetermined-fidelity — Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts
  // ════════════════════════════════════════════════════════════════════

  test("a pre-pass exiting outside its outcome range emits no counts"):
    LiveFactFixtures.withTempDir("cs-und-exit42") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, exit42Stub(fx)))
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"an exit-42 pre-pass must be could-not-determine, got: $other")
      assertNoCounts(stdout)
      assertReasonNames(stdout, "spec-lint")
    }

  // ── requirement-level termination shapes (the predecessor's marker checks) ──
  // spec: chain-state-undetermined-fidelity — Requirement: A verdict is produced only from a completed pre-pass

  test("a pre-pass producing no recognised completion marker is could-not-determine"):
    LiveFactFixtures.withTempDir("cs-und-nomarker") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, noMarkerStub(fx)))
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => fail(s"an exit-1 pre-pass with no completion marker is a crash, not findings: $other")
      assertNoCounts(stdout)
    }

  test("a pre-pass whose reported spec count disagrees with enumeration is could-not-determine"):
    LiveFactFixtures.withTempDir("cs-und-wrongcount") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, wrongCountStub(fx)))
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"a disagreeing file count is could-not-determine, got: $other")
      assertNoCounts(stdout)
    }

  test("a pre-pass reporting no specs while specs exist is could-not-determine"):
    LiveFactFixtures.withTempDir("cs-und-claimsnospecs") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, claimsNoSpecsStub(fx)))
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"a bogus 'no specs' claim is could-not-determine, got: $other")
      assertNoCounts(stdout)
    }

  test("a graph-mode pre-pass that produces no JSON array is could-not-determine"):
    LiveFactFixtures.withTempDir("cs-und-graph") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      // Force the graph arm: an openspec/ tree under OPENSPEC_ROOT plus the
      // real scanner dir so openspec-graph.py export succeeds.
      val graphRoot: Path = fx.resolve("graph-root")
      Files.createDirectories(graphRoot.resolve("openspec"))
      val env: Map[String, String] = Map(
        "OPENSPEC_ROOT"        -> graphRoot.toString,
        "PROBATIO_SCANNER_DIR" -> scannerDir.toString,
        "SPEC_LINT_OVERRIDE"   -> nonJsonStub(fx).toString
      )
      val (stdout, _, outcome) = runCmd(fx, env)
      outcome match
        case Outcome.Undetermined(_) => ()
        case other => fail(s"a graph-mode pre-pass emitting non-JSON is could-not-determine, got: $other")
      assertNoCounts(stdout)
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Happy path — an unreadable evidence record names the evidence record
  // spec: chain-state-undetermined-fidelity — Scenario: Happy path — an unreadable evidence record names the evidence record
  // ════════════════════════════════════════════════════════════════════

  test("an unreadable evidence record names the evidence record"):
    LiveFactFixtures.withTempDir("cs-und-ledger") { (fx: Path) =>
      oneReqSpec(fx)
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
            fx.resolve("missing-ledger.jsonl").toString
          ),
          degradedEnv(fx, completingStub(fx))
        )
      )
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"an unreadable evidence record is could-not-determine, got: $other")
      assertNoCounts(stdout)
      assertReasonNames(stdout, "missing-ledger.jsonl")
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown
  // spec: chain-state-undetermined-fidelity — Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown
  // ════════════════════════════════════════════════════════════════════

  test("an empty but readable evidence record is a measurement, not an unknown"):
    LiveFactFixtures.withTempDir("cs-und-empty-ledger") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, Nil)
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, completingStub(fx)))
      outcome match
        case Outcome.Finding(_) => ()
        case other => fail(s"an empty ledger measures zero discharged — a finding, not undetermined: $other")
      val parsed: ujson.Value = ujson.read(stdout.trim)
      assert(!parsed.obj.get("undetermined").exists(_.bool), "a measurement carries no undetermined flag")
      assertEquals(parsed("discharged").num.toInt, 0)
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Adversarial — a reason that names nothing is not emitted
  // spec: chain-state-undetermined-fidelity — Scenario: Adversarial — a reason that names nothing is not emitted
  // ════════════════════════════════════════════════════════════════════

  test("a reason that names nothing is not emitted"):
    LiveFactFixtures.withTempDir("cs-und-reasons") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      // Every termination path produces a non-empty reason naming the
      // unavailable input — never a bare failure marker.
      val cases: List[(String, Path)] = List(
        "absent"         -> fx.resolve("does-not-exist.sh"),
        "non-executable" -> writeStub(fx, "ne.sh", "#!/usr/bin/env bash\nexit 0\n", executable = false),
        "outside-range"  -> exit42Stub(fx),
        "no-marker"      -> noMarkerStub(fx)
      )
      cases.foreach { case (label, tool) =>
        val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, tool))
        outcome match
          case Outcome.Undetermined(reason) =>
            assert(reason.nonEmpty, s"$label: reason must be non-empty")
            assert(
              reason.contains("spec-lint") || reason.contains(tool.getFileName.toString),
              s"$label: the reason names the unavailable input, got: $reason"
            )
          case other => fail(s"$label: must be could-not-determine, got: $other")
        assertNoCounts(stdout)
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Happy path — a satisfied change exits clean
  // spec: chain-state-undetermined-fidelity — Scenario: Happy path — a satisfied change exits clean
  // ════════════════════════════════════════════════════════════════════

  test("a satisfied change exits clean"):
    LiveFactFixtures.withTempDir("cs-und-clean") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, completingStub(fx)))
      outcome match
        case Outcome.Ran(0) => ()
        case other          => fail(s"a satisfied change exits with the clean status, got: $other")
      val parsed: ujson.Value = ujson.read(stdout.trim)
      assertEquals(parsed("unresolved").arr.toList.length, 0)
      assertEquals(parsed("unmapped_obligations").arr.toList.length, 0)
    }

  // ════════════════════════════════════════════════════════════════════
  // Scenario: Adversarial — an uncomputable verdict does not exit with the finding status
  // spec: chain-state-undetermined-fidelity — Scenario: Adversarial — an uncomputable verdict does not exit with the finding status
  // ════════════════════════════════════════════════════════════════════

  test("an uncomputable verdict does not exit with the finding status"):
    LiveFactFixtures.withTempDir("cs-und-status") { (fx: Path) =>
      oneReqSpec(fx)
      writeLedger(fx, List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0)))
      val (_, _, outcome) = runCmd(fx, degradedEnv(fx, fx.resolve("does-not-exist.sh")))
      assertEquals(
        Outcome.toExitCode(outcome),
        2,
        s"a pre-pass that did not run exits with the could-not-determine status, got: $outcome"
      )
      outcome match
        case Outcome.Finding(_) => fail("an uncomputable verdict collapsed into the finding status")
        case _                  => ()
    }

  // ════════════════════════════════════════════════════════════════════
  // Property: outcome-status-is-total-and-disjoint
  // spec: chain-state-undetermined-fidelity — Property: outcome-status-is-total-and-disjoint
  //
  // genVerdictInput — constructive over (pre-pass outcome, evidence record
  // state, baseline state) triples, each drawn from its own closed variant
  // set rather than filtered. The pre-pass variants are realised as stub
  // executables under SPEC_LINT_OVERRIDE; the evidence-record variants as
  // ledger-file states; the baseline variants as the ledger rows' baseline.
  // ════════════════════════════════════════════════════════════════════

  /** How the declared pre-pass executable terminates. */
  private enum StubKind:
    case Completing, Absent, NonExecutable, BadExit, NoMarker

  /** The evidence-record state. */
  private enum LedgerKind:
    case AbsentFile, Empty, Populated, Corrupt

  /** Whether generated ledger rows carry the matching baseline. */
  private enum BaseKind:
    case Matching, Stale

  private def stubFor(fx: Path, kind: StubKind): Path = kind match
    case StubKind.Completing => completingStub(fx)
    case StubKind.Absent     => fx.resolve("does-not-exist.sh")
    case StubKind.NonExecutable =>
      writeStub(fx, "spec-lint-ne.sh", "#!/usr/bin/env bash\nexit 0\n", executable = false)
    case StubKind.BadExit  => exit42Stub(fx)
    case StubKind.NoMarker => noMarkerStub(fx)

  private lazy val genVerdictInput: Gen[(StubKind, LedgerKind, BaseKind)] =
    for
      stub <- Gen.frequency1(
        60 -> Gen.constant(StubKind.Completing),
        10 -> Gen.constant(StubKind.Absent),
        10 -> Gen.constant(StubKind.NonExecutable),
        10 -> Gen.constant(StubKind.BadExit),
        10 -> Gen.constant(StubKind.NoMarker)
      )
      ledger <- Gen.frequency1(
        22 -> Gen.constant(LedgerKind.AbsentFile),
        18 -> Gen.constant(LedgerKind.Empty),
        38 -> Gen.constant(LedgerKind.Populated),
        22 -> Gen.constant(LedgerKind.Corrupt)
      )
      base <- Gen.frequency1(
        78 -> Gen.constant(BaseKind.Matching),
        22 -> Gen.constant(BaseKind.Stale)
      )
    yield (stub, ledger, base)

  property("exit status is total and disjoint", coverConfig):
    for input <- genVerdictInput.forAll
        .cover(25, "pre-pass-did-not-run", (t: (StubKind, LedgerKind, BaseKind)) => t._1 != StubKind.Completing)
        .cover(40, "pre-pass-completed", (t: (StubKind, LedgerKind, BaseKind)) => t._1 == StubKind.Completing)
        .cover(
          15,
          "evidence-unreadable",
          (t: (StubKind, LedgerKind, BaseKind)) =>
            t._1 == StubKind.Completing && (t._2 == LedgerKind.AbsentFile || t._2 == LedgerKind.Corrupt)
        )
        .cover(
          10,
          "verdict-clean",
          (t: (StubKind, LedgerKind, BaseKind)) =>
            t._1 == StubKind.Completing && t._2 == LedgerKind.Populated && t._3 == BaseKind.Matching
        )
    yield
      val (stubKind, ledgerKind, baseKind) = input
      // A verdict can exist only when the pre-pass completes AND the
      // evidence record is readable — the input's ground truth.
      val prePassRan: Boolean      = stubKind == StubKind.Completing
      val evidenceRead: Boolean    = ledgerKind == LedgerKind.Empty || ledgerKind == LedgerKind.Populated
      val verdictComputed: Boolean = prePassRan && evidenceRead
      val unresolvedExpected: Boolean =
        verdictComputed && (ledgerKind == LedgerKind.Empty || baseKind == BaseKind.Stale)
      val expectedStatus: Int =
        if !verdictComputed then 2 else if unresolvedExpected then 1 else 0
      val result: Result = LiveFactFixtures.withTempDir("cs-und-prop") { (fx: Path) =>
        oneReqSpec(fx)
        ledgerKind match
          case LedgerKind.AbsentFile => ()
          case LedgerKind.Empty      => writeLedger(fx, Nil)
          case LedgerKind.Populated =>
            writeLedger(
              fx,
              List(
                ledgerJson(
                  "only",
                  "obl one",
                  resolvesArtifact,
                  exit = 0,
                  baseline = if baseKind == BaseKind.Stale then staleBaseline else fullBaseline
                )
              )
            )
          case LedgerKind.Corrupt => writeLedger(fx, List("{not json"))
        val tool: Path           = stubFor(fx, stubKind)
        val (stdout, _, outcome) = runCmd(fx, degradedEnv(fx, tool))
        val status: Int          = Outcome.toExitCode(outcome)
        val parsed: ujson.Value  = ujson.read(stdout.trim)
        val isUndeterminedReport: Boolean =
          parsed.obj.get("undetermined").exists(_.bool)
        Result
          .assert(status == expectedStatus)
          .log(
            s"status $status != expected $expectedStatus " +
              s"(stub=$stubKind ledger=$ledgerKind baseline=$baseKind)\n  stdout: $stdout"
          )
          .and(
            Result
              .assert(List(0, 1, 2).contains(status))
              .log(s"exit status is outside the declared set: $status")
          )
          .and(
            Result
              .assert(isUndeterminedReport == (status == 2))
              .log(s"report flag disagrees with status: flag=$isUndeterminedReport status=$status")
          )
          .and(
            Result
              .assert(
                status != 1 || parsed("unresolved").arr.nonEmpty ||
                  parsed("unmapped_obligations").arr.nonEmpty
              )
              .log(s"a finding status requires a non-empty unresolved or unmapped list: $stdout")
          )
          .and(
            Result
              .assert(
                status != 0 || (parsed("unresolved").arr.isEmpty &&
                  parsed("unmapped_obligations").arr.isEmpty)
              )
              .log(s"a clean status requires empty unresolved and unmapped lists: $stdout")
          )
      }
      result
