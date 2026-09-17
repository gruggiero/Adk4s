package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.InstallRootScan
import org.sinemenda.probatio.core.InstallRootState
import org.sinemenda.probatio.core.LintContext
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.StampFormat

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import LiveFactFixtures.withTempDir

/**
 * Test oracle for the `spec-lint` subcommand surface (spec 4, Step 2):
 * the predecessor's invocation forms — a positional target, `--artifacts`,
 * `--context-only`, `--format json` — and the CONTEXT block the
 * `StdoutRenderer[LintContext]` emits.
 *
 * spec: spec-lint-engine — Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms
 * spec: spec-lint-engine — Requirement: The CLI emits the predecessor's context block before the findings
 */
final class SpecLintCmdSpec extends ProbatioCliSuite:

  /** Run the subcommand with stdout and stderr captured. */
  private def captureRun(args: Array[String]): (Outcome[Int], String, String) =
    val (out: String, err: String, outcome: Outcome[Int]) =
      StdoutCapture.captureBoth(SpecLintCmd.run(args))
    (outcome, out, err)

  /** Write `text` as `<dir>/specs/spec.md` — the predecessor's change-dir shape. */
  private def writeSpec(dir: Path, text: String): Path =
    val spec: Path = dir.resolve("specs/spec.md")
    Files.createDirectories(spec.getParent)
    Files.writeString(spec, text, StandardCharsets.UTF_8)
    spec

  /** A spec document that lints clean: no requirements, one typed source. */
  private val cleanSpec: String =
    """# Spec: Fixture
      |
      |## Concepts Used (behavioral)
      |
      || Concept | Role here | File |
      ||---------|-----------|------|
      || (none) | fixture | — |
      |
      |## ADDED Requirements
      |
      |## Proof Obligations
      |
      || Obligation | Source | Enforcement | Artifact |
      ||---|---|---|---|
      || o | Invariant: holds | manual | — |
      |""".stripMargin

  /** A spec with one covered requirement and a code-shaped artifact token. */
  private def artifactSpec(token: String): String =
    s"""# Spec: Fixture
       |
       |## Concepts Used (behavioral)
       |
       || Concept | Role here | File |
       ||---------|-----------|------|
       || (none) | fixture | — |
       |
       |## ADDED Requirements
       |
       |### Requirement: Req Alpha
       |
       |The system SHALL do the alpha thing.
       |
       |**Given** x
       |**When** y
       |**Then** z
       |
       |#### Scenario: s
       |
       |**Given** x
       |**When** y
       |**Then** z
       |
       |## Proof Obligations
       |
       || Obligation | Source | Enforcement | Artifact |
       ||---|---|---|---|
       || o | Requirement: Req Alpha | manual | `$token` |
       |""".stripMargin

  // ── Scenario: a positional change directory is linted ───────────────
  // spec: spec-lint-engine — Scenario: Happy path — a positional change directory is linted
  test("a positional change directory is linted and the summary names the document count"):
    withTempDir("speclint-positional") { (dir: Path) =>
      writeSpec(dir, cleanSpec)
      val (outcome, out, _) = captureRun(Array(dir.toString))
      outcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a clean lint must be Ran(0), got $other")
      assert(
        out.contains("1 spec file(s)"),
        s"the summary must name the number of documents examined: $out"
      )
    }

  // ── Scenario: the facts-only modifier prints the facts and stops ────
  // spec: spec-lint-engine — Scenario: Happy path — the facts-only modifier prints the applicability facts and stops
  test("--context-only prints the CONTEXT block, lints nothing, and exits clean"):
    val (outcome, out, _) = captureRun(Array("--context-only"))
    outcome match
      case Outcome.Ran(code) => assertEquals(code, 0)
      case other             => fail(s"--context-only must be Ran(0), got $other")
    assert(
      out.contains("CONTEXT") && out.contains("repository facts"),
      s"the facts-only run must print the CONTEXT block: $out"
    )
    assert(
      !out.contains("spec file(s)"),
      s"--context-only must not lint: $out"
    )

  // ── Scenario: an unreadable target is could-not-determine ───────────
  // spec: spec-lint-engine — Scenario: Error path — an unreadable target is could-not-determine
  test("a target path that does not exist is could-not-determine naming the path"):
    val missing: String   = "/nonexistent-spec-lint-target-xyz"
    val (outcome, _, err) = captureRun(Array(missing))
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains(missing),
          s"the undetermined reason must name the path: $reason"
        )
      case other => fail(s"a nonexistent target must be Undetermined, got $other")
    assert(
      err.contains(missing) || outcome.toString.contains(missing),
      s"the predecessor reports 'no specs found under' on stderr: $err"
    )

  // ── Scenario: the artifact-checking modifier is not silently ignored ─
  // spec: spec-lint-engine — Scenario: Adversarial — the artifact-checking modifier must not be silently ignored
  test("--artifacts surfaces F9 for an artifact naming no tracked file"):
    withTempDir("speclint-artifacts") { (dir: Path) =>
      writeSpec(dir, artifactSpec("ZzzNoSuchSpec.scala"))
      val (withFlag, outWith, _) = captureRun(Array(dir.toString, "--artifacts"))
      assert(
        outWith.contains("F9") && outWith.contains("ZzzNoSuchSpec.scala"),
        s"--artifacts must surface the F9 finding: $outWith"
      )
      withFlag match
        case Outcome.Finding(_) => ()
        case other              => fail(s"an F9 finding must be exit 1, got $other")

      val (_, outWithout, _) = captureRun(Array(dir.toString))
      assert(
        !outWithout.contains("F9"),
        s"without --artifacts no F9 runs: $outWithout"
      )
    }

  // ── The --format json invocation form ────────────────────────────────
  // spec: spec-lint-engine — Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms
  test("--format json emits findings as a JSON array, empty for a clean spec"):
    withTempDir("speclint-json") { (dir: Path) =>
      writeSpec(dir, cleanSpec)
      val (cleanOutcome, cleanOut, _) =
        captureRun(Array(dir.toString, "--format", "json"))
      val cleanJson: ujson.Value = ujson.read(cleanOut)
      assertEquals(cleanJson.arr.toList, List.empty[ujson.Value])
      cleanOutcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a clean JSON run must be Ran(0), got $other")

      val failing: String =
        """# Spec: F
          |
          |## Concepts Used (behavioral)
          |
          || Concept | Role here | File |
          ||---------|-----------|------|
          || (none) | f | — |
          |
          |## ADDED Requirements
          |
          |### Requirement: Req Alpha
          |
          |The system SHALL do it.
          |
          |**Given** x
          |**When** y
          |**Then** z
          |
          |#### Scenario: s
          |
          |**Given** x
          |**When** y
          |**Then** z
          |
          |## Proof Obligations
          |
          || Obligation | Source | Enforcement | Artifact |
          ||---|---|---|---|
          |""".stripMargin
      writeSpec(dir, failing)
      val (_, failOut, _)       = captureRun(Array(dir.toString, "--format", "json"))
      val failJson: ujson.Value = ujson.read(failOut)
      val checks: List[String]  = failJson.arr.toList.map(_("check").str)
      assert(checks.contains("F7"), s"the JSON findings must include the F7: $failOut")
      failJson.arr.toList.find(_("check").str == "F7") match
        case Some(f7) =>
          assert(f7.obj.contains("reason"), "each finding carries a reason")
          assert(f7.obj.contains("line"), "each finding carries a line")
        case None => fail(s"no F7 object in the JSON findings: $failOut")
    }

  // ── Discovery: `-path '*/specs/*'` — nested spec.md under a specs dir ──
  // spec: spec-lint-engine — Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms
  test("a spec.md nested below a specs/ component under openspec/changes is discovered"):
    withTempDir("speclint-nested") { (dir: Path) =>
      val spec: Path = dir.resolve("openspec/changes/change-x/specs/nested/spec.md")
      Files.createDirectories(spec.getParent)
      Files.writeString(spec, cleanSpec, StandardCharsets.UTF_8)
      val (outcome, out, _) = captureRun(Array(dir.toString))
      outcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a nested spec must be linted, got $other")
      assert(
        out.contains("1 spec file(s)"),
        s"the nested spec.md must be discovered and counted: $out"
      )
    }

  // ── The generic-F6 hint line — text mode only, never a finding ──────
  // spec: spec-lint-engine — Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms
  test("an unresolvable source emits the F6 hint line in text mode but not in JSON"):
    withTempDir("speclint-f6hint") { (dir: Path) =>
      val spec: String =
        """# Spec: F
          |
          |## Concepts Used (behavioral)
          |
          || Concept | Role here | File |
          ||---------|-----------|------|
          || (none) | f | — |
          |
          |## Proof Obligations
          |
          || Obligation | Source | Enforcement | Artifact |
          ||---|---|---|---|
          || o | see above | manual | — |
          |""".stripMargin
      writeSpec(dir, spec)
      val (_, textOut, _) = captureRun(Array(dir.toString))
      assert(
        textOut.contains("Source names no resolvable reference: see above"),
        s"the generic F6 must fire: $textOut"
      )
      assert(
        textOut.contains(
          "(use \"Requirement: <exact title>\", \"Requirement N\", " +
            "or a typed source like \"Property: <name>\")"
        ),
        s"the predecessor's hint line must follow the F6: $textOut"
      )
      val (_, jsonOut, _)       = captureRun(Array(dir.toString, "--format", "json"))
      val findings: ujson.Value = ujson.read(jsonOut)
      assert(
        !findings.arr.toList.exists(_("reason").str.contains("(use ")),
        s"the hint is never a JSON finding: $jsonOut"
      )
    }

  // ── The CONTEXT renderer — the predecessor's block, verbatim ────────
  // spec: spec-lint-engine — Requirement: The CLI emits the predecessor's context block before the findings
  test("StdoutRenderer[LintContext] renders the predecessor's CONTEXT block"):
    val ctx: LintContext = LintContext(
      schemaVersion = FactRead.Present(14),
      registry = FactRead.Present(5),
      registryConcepts = FactRead.Present(List("Schema")),
      inventoryTypes = FactRead.Present(List("Schema", "SpecLintEngine")),
      profile = FactRead.Present(Some("TestControl")),
      installRoots = List(
        InstallRootScan("/r/.agents/skills", InstallRootState.Stamped(14, StampFormat.New)),
        InstallRootScan("/r/.claude/skills", InstallRootState.Absent),
        InstallRootScan("/r/.pi/skills", InstallRootState.Absent),
        InstallRootScan("/h/.agents/skills", InstallRootState.Stamped(12, StampFormat.New)),
        InstallRootScan("/h/.claude/skills", InstallRootState.Absent),
        InstallRootScan("/h/.zcode/skills", InstallRootState.Absent)
      )
    )
    val out: String = StdoutRenderer[LintContext].render(ctx)
    assert(out.contains("CONTEXT"), "the CONTEXT header is emitted")
    assert(
      out.contains("openspec/concepts/") && out.contains("PRESENT (5 concepts)"),
      s"the registry fact is stated with its count: $out"
    )
    assert(
      out.contains("check 17") && out.contains("APPLIES"),
      s"a present registry marks the ALTITUDE check applicable: $out"
    )
    assert(
      out.contains("concept-inventory.md") && out.contains("PRESENT"),
      s"the inventory fact is stated: $out"
    )
    assert(
      out.contains("INSTRUCTION DRIFT") && out.contains("12") && out.contains("14"),
      s"a mismatched stamp renders the drift line naming both versions: $out"
    )

  test("StdoutRenderer[LintContext] states the negative cases as loudly as the positive"):
    val ctx: LintContext = LintContext(
      schemaVersion = FactRead.Absent,
      registry = FactRead.Absent,
      registryConcepts = FactRead.Absent,
      inventoryTypes = FactRead.Absent,
      profile = FactRead.Absent,
      installRoots = List.fill(DriftScan.installRoots.length)(
        InstallRootScan("root", InstallRootState.Absent)
      )
    )
    val out: String = StdoutRenderer[LintContext].render(ctx)
    assert(
      out.contains("ABSENT") && out.contains("N/A"),
      s"the absent registry is stated, and its check marked N/A: $out"
    )
    assert(
      out.contains("no openspec-spec-lint skill installed"),
      s"all-absent roots print the explicit no-skill line: $out"
    )

  // ── Scenario: a present registry is never reported absent ───────────
  // spec: spec-lint-engine — Scenario: Adversarial — a present registry is never reported absent
  test("the built artifact reports the registry present even from a subdirectory"):
    val artifact: Path =
      Paths.get("workflow/cli/target/native-image/probatio") match
        case p if Files.exists(p) => p
        case _ =>
          Paths.get("/home/gruggiero/git/rs/adk4s/workflow/cli/target/native-image/probatio")
    if !Files.exists(artifact) then
      fail(s"built artifact not found at $artifact — conformance check FAILS, does not skip")
    val subdir: Path = artifact.getParent.getParent // workflow/cli/target — inside the repo
    val pb: ProcessBuilder = new ProcessBuilder(
      artifact.toAbsolutePath.toString,
      "spec-lint",
      "--context-only"
    )
    pb.directory(subdir.toFile)
    val p: Process  = pb.start()
    val out: String = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val exit: Int   = p.waitFor()
    assertEquals(exit, 0, s"--context-only exits 0: $out")
    assert(
      out.contains("PRESENT") && out.contains("openspec/concepts"),
      s"invoked from a subdirectory, the registry must still be found: $out"
    )
    assert(
      !out.contains("openspec/concepts/             ABSENT"),
      s"a present registry is never reported absent: $out"
    )

end SpecLintCmdSpec
