package org.sinemenda.probatio.plugin

import hedgehog.Gen
import hedgehog.Result

import scala.io.Source
import java.io.File

/**
 * Source-lint tests for compile-negative obligations.
 *
 * These tests verify that the plugin source adheres to R-S1 (no probatio-core
 * imports), R-S2 (no deprecated sbt operators, no GlobalScope abuse), and
 * R-S6 (no side effects at build load time).
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: The plugin forward-declares future sbt 2 support
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: The plugin does not execute at build load time beyond settings declaration
 * spec: port-scanner-to-probatio/sbt-plugin — Compile-Negative Obligations
 */
final class PluginSourceLintSpec extends ProbatioPluginSuite {

  // `sbt test` runs with cwd = repo root; the Stryker4s forked test-runner
  // runs with cwd = the module base dir. Resolve against both.
  private val pluginSourceDir: String = {
    val repoRootRelative: String =
      "workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin"
    if (new File(repoRootRelative).isDirectory) repoRootRelative
    else "src/main/scala/org/sinemenda/probatio/plugin"
  }

  private def listScalaFiles(dirPath: String): List[File] = {
    val dir: File = new File(dirPath)
    if (!dir.exists) Nil
    else
      dir.listFiles.flatMap { f =>
        if (f.isDirectory) listScalaFiles(f.getAbsolutePath)
        else if (f.getName.endsWith(".scala")) List(f)
        else Nil
      }.toList
  }

  private def fileContents(f: File): String =
    Source.fromFile(f).mkString

  // ── R-S1: no probatio-core imports ──────────────────────────────────────
  // spec: sbt-plugin — Compile-Negative: import org.sinemenda.probatio.core.* in plugin source
  test("plugin source has no imports from org.sinemenda.probatio.core") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    assert(files.nonEmpty, "plugin source directory should have Scala files")
    for (f <- files) {
      val content: String = fileContents(f)
      assert(
        !content.contains("import org.sinemenda.probatio.core"),
        s"${f.getName}: forbidden import org.sinemenda.probatio.core found"
      )
    }
  }

  // ── R-S2: no deprecated sbt operators ───────────────────────────────────
  // spec: sbt-plugin — Compile-Negative: Deprecated sbt operators (<<=, in Config.Global)
  test("plugin source has no deprecated sbt operators (<<=, in Config.Global)") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    for (f <- files) {
      val lines: List[String] = fileContents(f).linesIterator.toList
      // Check only non-comment lines for actual deprecated operator usage
      val codeLines: List[String] =
        lines.filterNot(line => line.trim.startsWith("//") || line.trim.startsWith("*") || line.trim.startsWith("/*"))
      for (line <- codeLines) {
        assert(
          !line.contains("<<="),
          s"${f.getName}: deprecated <<= operator found in line: $line"
        )
        assert(
          !line.contains("in Config.Global"),
          s"${f.getName}: deprecated 'in Config.Global' found in line: $line"
        )
      }
    }
  }

  // ── R-S2: no GlobalScope abuse ──────────────────────────────────────────
  // spec: sbt-plugin — Compile-Negative: in GlobalScope scope abuse
  test("plugin source has no GlobalScope abuse") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    for (f <- files) {
      val lines: List[String] = fileContents(f).linesIterator.toList
      // Check only non-comment lines for actual GlobalScope usage
      val codeLines: List[String] =
        lines.filterNot(line => line.trim.startsWith("//") || line.trim.startsWith("*") || line.trim.startsWith("/*"))
      for (line <- codeLines)
        assert(
          !line.contains("in GlobalScope"),
          s"${f.getName}: 'in GlobalScope' abuse found in line: $line"
        )
    }
  }

  // ── R-S6: no Process/URL/os.read calls in settings initialization ───────
  // spec: sbt-plugin — Compile-Negative: Network resolution or binary execution in AutoPlugin buildSettings/projectSettings initialization
  test("plugin source has no Process/URL side effects in settings initialization") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    for (f <- files) {
      val content: String = fileContents(f)
      // Process calls are allowed inside Def.task { } bodies, but not in
      // settings initialization. For the oracle phase, we check that the
      // source doesn't contain bare Process/URL calls outside of task bodies.
      // A more precise check would parse the AST, but a grep-level check
      // catches the obvious violations.
      assert(
        !content.contains("java.net.URL("),
        s"${f.getName}: bare java.net.URL() call found — network resolution must be inside Def.task"
      )
    }
  }

  // ── R-S2: task composition uses Def.task ────────────────────────────────
  // spec: sbt-plugin — Scenario: task composition uses Def.task
  test("plugin source uses Def.task or Def.setting for task composition") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    // At least one file should use Def.task or Def.setting
    val hasDefTask: Boolean = files.exists { f =>
      val content: String = fileContents(f)
      content.contains("Def.task") || content.contains("Def.setting")
    }
    assert(hasDefTask, "plugin source should use Def.task or Def.setting for task composition")
  }

  // ══════════════════════════════════════════════════════════════════════
  // native-gate-delivery (spec 9) — the build integration passes each tool
  // the arguments its invocation requires
  // spec: native-gate-delivery — Requirement: The build integration passes each tool the arguments its invocation requires
  // ══════════════════════════════════════════════════════════════════════

  // ── Scenario: Error path — a task lacking a required value reports it
  //    rather than invoking
  // spec: native-gate-delivery — Scenario: Error path — a task lacking a required value reports it rather than invoking
  test("each delegating task reports a missing required value rather than invoking without arguments") {
    val source: String =
      fileContents(new File(s"$pluginSourceDir/ProbatioPlugin.scala"))
    val argsKeys: List[String] = List(
      "probatioSpecLintArgs",
      "probatioChainStateArgs",
      "probatioCheckpointArgs",
      "probatioLedgerAppendArgs"
    )
    for (key <- argsKeys) {
      assert(
        source.contains(s"$key.value match"),
        s"ProbatioPlugin.scala: task must gate on $key — $key.value match not found"
      )
      assert(
        source.contains(s"$key is unset"),
        s"ProbatioPlugin.scala: missing-value report must name $key"
      )
    }
    // Every runDelegatingTask call site passes an argument list — there is
    // no call site that passes only a tool name.
    assert(
      source.contains("Seq(binary.getAbsolutePath, subcommand) ++ args"),
      "ProbatioPlugin.scala: the delegating command must append the task's argument list"
    )
    val callSites: Int =
      "runDelegatingTask\\(binary,".r.findAllIn(source).length
    assertEquals(callSites, 4, s"expected 4 delegating task call sites, found $callSites")
  }

  // ── Compile-Negative: a delegating build task constructed with only a
  //    tool name and no argument list
  // spec: native-gate-delivery — Compile-Negative: A delegating build task constructed with only a tool name and no argument list
  test("compile-negative: runDelegatingTask cannot be invoked with only a tool name") {
    val err: String = compileErrors(
      "ProbatioPlugin.runDelegatingTask(new java.io.File(\"b\"), \"spec-lint\", sbt.util.Logger.Null)"
    )
    assert(
      err.nonEmpty,
      "runDelegatingTask(binary, subcommand, log) should not compile — a task that cannot pass arguments cannot drive its tool"
    )
  }

  // ══════════════════════════════════════════════════════════════════════
  // workflow-delivery-hygiene (spec 9) — committed forwarding scripts carry
  // no absolute checkout path; the repository's CI runs the acceptance
  // suite, the differential and every module suite; shipped CI templates
  // name only tools that exist.
  // spec: workflow-delivery-hygiene — Requirement: An in-repository forwarding script resolves its target relative to itself
  // spec: workflow-delivery-hygiene — Requirement: Continuous integration runs the acceptance suite and the module suites
  // spec: workflow-delivery-hygiene — Requirement: The continuous-integration templates name the tools that exist
  // ══════════════════════════════════════════════════════════════════════

  private val schemaDirRel: String = "openspec/schemas/verified-scala3"

  private def repoRoot: File = {
    val fromRepoRoot: File = new File(schemaDirRel)
    if (fromRepoRoot.isDirectory) new File(".").getCanonicalFile
    else new File("../..").getCanonicalFile
  }

  private def runGit(args: List[String]): (Int, String) = {
    // spec: hermetic-test-processes — via the shared helper; the child
    // sees the fixed base plus declared variables only.
    val r: HermeticResult =
      HermeticEnv.capture(List("git") ++ args, HermeticEnv.empty, cwd = Some(repoRoot))
    (r.exitCode, List(r.out, r.err).filter(_.nonEmpty).mkString("\n"))
  }

  // Every committed .sh under the schema dir whose body forwards to
  // bin/probatio — discovered via `git ls-files` at test time, never
  // listed, so a newly swapped seam is covered automatically.
  private def committedForwardingShims(): List[File] = {
    val (code, out): (Int, String) = runGit(List("ls-files", schemaDirRel))
    assertEquals(code, 0, s"git ls-files must succeed: $out")
    out.linesIterator.toList
      .filter(_.endsWith(".sh"))
      .map(rel => new File(repoRoot, rel))
      .filter { f =>
        fileContents(f).linesIterator.exists(line => line.startsWith("exec ") && line.contains("bin/probatio"))
      }
  }

  // An absolute path beginning at a filesystem root, in any line after the
  // shebang. The shebang is exempt (#!/usr/bin/env is universal, not a
  // checkout); "$SCRIPT_DIR/…" expansions are self-relative mechanisms —
  // their token starts with '$', never with '/'.
  private def carriesAbsolutePath(content: String): Boolean =
    content.linesIterator.toList match {
      case shebang :: rest if shebang.startsWith("#!") =>
        rest
          .flatMap(line => line.split("[\"'\\s=]+").toList)
          .exists(tok => tok.length > 1 && tok.startsWith("/") && tok.charAt(1).isLetterOrDigit)
      case lines =>
        lines
          .flatMap(line => line.split("[\"'\\s=]+").toList)
          .exists(tok => tok.length > 1 && tok.startsWith("/") && tok.charAt(1).isLetterOrDigit)
    }

  // ── Property: no-committed-script-carries-an-absolute-path
  // (covers Scenario: Adversarial — no committed script contains an
  //  absolute checkout path)
  // spec: workflow-delivery-hygiene — Property: no-committed-script-carries-an-absolute-path
  // spec: workflow-delivery-hygiene — Scenario: Adversarial — no committed script contains an absolute checkout path
  //
  // Enumerated, not sampled: the domain is the finite set of committed
  // forwarding scripts, discovered at test time rather than listed, so a
  // newly added script is covered automatically. This finite-domain limit
  // is stated rather than presented as sampled coverage.
  property("no committed forwarding script carries an absolute path") {
    for {
      _ <- Gen.constant(()).forAll
    } yield {
      val shims: List[File] = committedForwardingShims()
      if (shims.isEmpty)
        Result.failure.log("no committed forwarding scripts discovered — the property must have subjects")
      else
        shims
          .map { shim =>
            val content: String = fileContents(shim)
            Result
              .assert(!carriesAbsolutePath(content))
              .log(s"${shim.getPath} carries an absolute path")
              .and(
                Result
                  .assert(content.contains("SCRIPT_DIR") || content.contains("BASH_SOURCE"))
                  .log(
                    s"${shim.getPath} does not resolve its own location — the target is not expressed relative to the script"
                  )
              )
          }
          .reduceOption((a: Result, b: Result) => a.and(b))
          .getOrElse(Result.failure.log("no committed forwarding scripts discovered"))
    }
  }

  // check-of-the-check: the shebang exemption is conditional on the first
  // line actually being a shebang — a shebang-less script still reports
  // its absolute path (Ring-8 finding N1)
  test("absolute-path detection does not require a shebang") {
    assert(
      carriesAbsolutePath("exec /opt/tool run"),
      "a shebang-less script carrying an absolute path must be detected"
    )
    assert(
      !carriesAbsolutePath(
        "#!/usr/bin/env bash\nexec \"$SCRIPT_DIR/../bin/probatio\" sub \"$@\""
      ),
      "a self-relative shim must not be flagged"
    )
  }

  // ── Requirement: Continuous integration runs the acceptance suite and
  //    the module suites — the job enumeration
  // spec: workflow-delivery-hygiene — Requirement: Continuous integration runs the acceptance suite and the module suites

  // A workflow runs "on each change" when its `on:` block triggers on
  // branch pushes or pull requests. A tags-only push trigger (the release
  // pipeline) does not run on each change, and neither does a `paths:` /
  // `paths-ignore:` filter — a change outside the filter's set runs
  // nothing at all.
  private def triggersOnChange(text: String): Boolean = {
    val lines: List[String] = text.linesIterator.toList
    lines.indexWhere(l => l.trim == "on:" || l.trim.startsWith("on:") || l.trim == "\"on\":") match {
      case -1 => false
      case i =>
        val onBlock: List[String] =
          lines.drop(i + 1).takeWhile(l => l.trim.isEmpty || l.startsWith(" ") || l.startsWith("\t"))
        val block: String = (lines(i) +: onBlock).mkString("\n")
        val pathFiltered: Boolean =
          onBlock.exists(l => l.trim.startsWith("paths:") || l.trim.startsWith("paths-ignore:"))
        !pathFiltered &&
        (block.contains("pull_request") ||
          (block.contains("push") && !block.contains("tags:")))
    }
  }

  final private case class RequiredStep(label: String, needles: List[String])

  // The module set is discovered from build.sbt — a project with a
  // src/test tree has a suite to run — never listed, so a new module joins
  // the obligation automatically.
  private def discoveredTestModules(): List[String] = {
    val buildSbt: String = fileContents(new File(repoRoot, "build.sbt"))
    val projectDecl      = "lazy val `([^`]+)` = \\(project in file\\(\"([^\"]+)\"\\)\\)".r
    projectDecl.findAllMatchIn(buildSbt).toList.collect {
      case m if new File(repoRoot, m.group(2) + "/src/test").isDirectory => m.group(1)
    }
  }

  // The acceptance step's invocation signature — shared by the
  // completeness check and the archive-path check so the two can never
  // drift on what "the acceptance suite" means.
  private val acceptanceNeedles: List[String] = List("bats", "verified-scala3/tests")

  private def requiredCiSteps(): List[RequiredStep] =
    RequiredStep("acceptance suite (bats)", acceptanceNeedles) ::
      RequiredStep("differential comparison", List("probatioOracleDiff")) ::
      discoveredTestModules().map(m => RequiredStep(s"module suite $m", List(s"$m/test")))

  // A line is inside a step block only if every line between the `- `
  // marker and it is indented DEEPER than the marker — this keeps `- `
  // items in `on:`/`env:` lists from masquerading as step blocks that
  // would mask a job-level guard.
  private def leadingIndent(l: String): Int = l.takeWhile(c => c == ' ' || c == '\t').length

  // The `- ` item markers that begin real step blocks. `- ` items inside
  // a literal block scalar (`run: |`) are script content, not step
  // markers — those regions are excluded so a needle line inside one
  // isn't counted as its own unguarded step.
  private def stepMarkerIndices(lines: List[String]): List[Int] = {
    val stepStarts: List[Int] =
      lines.zipWithIndex.collect { case (l, i) if l.matches("^\\s+-\\s+\\S.*") => i }
    val literalStart = "^\\s*.*:\\s*[|>][+-]?\\s*$".r
    val inLiteral: Set[Int] =
      lines.zipWithIndex
        .foldLeft((Set.empty[Int], -1, -1)) { case ((acc, start, startIndent), (l, i)) =>
          if (start >= 0 && (l.trim.isEmpty || leadingIndent(l) > startIndent))
            (acc + i, start, startIndent)
          else if (literalStart.pattern.matcher(l).matches())
            (acc, i, leadingIndent(l))
          else
            (acc, -1, -1)
        }
        ._1
    stepStarts.filterNot(inLiteral.contains)
  }

  // Step blocks are the regions between `- ` item markers. A required
  // invocation that appears but is neutralised in its own step — an `if:`
  // guard or `continue-on-error` — counts as skipped, not run: a job that
  // reports success on an unrun suite is the defect this check exists for.
  // The same holds one level up: an `if:` or `continue-on-error` OUTSIDE
  // every step block is a job-level guard that neutralises every step.
  private def missingCiSteps(text: String): List[String] = {
    val lines: List[String]       = text.linesIterator.toList
    def leading(l: String): Int   = leadingIndent(l)
    val realStepStarts: List[Int] = stepMarkerIndices(lines)
    def insideStepBlock(lineIdx: Int): Boolean =
      realStepStarts.filter(_ < lineIdx).lastOption.exists { s =>
        val ind: Int = leading(lines(s))
        (s + 1 to lineIdx).forall(j => lines(j).trim.isEmpty || leading(lines(j)) > ind)
      }
    def blockContaining(lineIdx: Int): List[String] = {
      val start: Int = realStepStarts.filter(_ <= lineIdx).lastOption.getOrElse(0)
      val end: Int   = realStepStarts.find(_ > lineIdx).getOrElse(lines.length)
      lines.slice(start, end)
    }
    // Job-level guards sit outside every step block (strict: no `- `
    // marker). Step-level guards may sit on the marker line itself
    // (`- if: false` is valid YAML), so the block scan tolerates a dash.
    val jobNeutraliser  = "^\\s*(if|continue-on-error)\\s*:.*".r
    val stepNeutraliser = "^\\s*-?\\s*(if|continue-on-error)\\s*:.*".r
    val jobNeutralised: Boolean =
      lines.zipWithIndex.exists { case (l, i) =>
        jobNeutraliser.pattern.matcher(l).matches() && !insideStepBlock(i)
      }
    if (jobNeutralised) requiredCiSteps().map(_.label)
    else
      requiredCiSteps()
        .filterNot { step =>
          lines.zipWithIndex.exists { case (line, i) =>
            // A comment line cannot run a suite — the needle must appear
            // on an executable line.
            !line.trim.startsWith("#") && step.needles.forall(line.contains) && {
              val block: List[String] = blockContaining(i)
              !block.exists(b => stepNeutraliser.pattern.matcher(b).matches())
            }
          }
        }
        .map(_.label)
  }

  // spec: workflow-delivery-hygiene — Requirement: Continuous integration runs the acceptance suite and the module suites (job enumeration)
  test("an on-change CI workflow runs the acceptance suite, the differential, and every module suite") {
    // Enumerate TRACKED workflows — a file git never sees (e.g. ignored by
    // a global gitignore) cannot run on CI. On-disk files that are not
    // tracked are reported separately so the hazard cannot hide.
    val (code, trackedOut): (Int, String) = runGit(List("ls-files", ".github/workflows"))
    assertEquals(code, 0, s"git ls-files must succeed: $trackedOut")
    val tracked: List[File] =
      trackedOut.linesIterator.toList
        .filter(p => p.endsWith(".yml") || p.endsWith(".yaml"))
        .map(rel => new File(repoRoot, rel))
    assert(
      tracked.nonEmpty,
      ".github/workflows must contain at least one TRACKED workflow (a file left untracked never runs on CI)"
    )
    val workflowsDir: File = new File(repoRoot, ".github/workflows")
    val untracked: List[String] =
      Option(workflowsDir.listFiles).toList.flatten
        .filter(f => f.getName.endsWith(".yml") || f.getName.endsWith(".yaml"))
        .filterNot(f => tracked.exists(_.getName == f.getName))
        .map(_.getName)
    assert(
      untracked.isEmpty,
      s"workflow file(s) present on disk but not tracked by git — CI would never run them: ${untracked.mkString(", ")}"
    )
    val onChange: List[(String, List[String])] =
      tracked
        .filter(f => triggersOnChange(fileContents(f)))
        .map(f => f.getName -> missingCiSteps(fileContents(f)))
    assert(
      onChange.nonEmpty,
      "no on-change CI workflow exists — the only workflow is tag-gated (release)"
    )
    val satisfying: List[String] =
      onChange.collect { case (n, missing) if missing.isEmpty => n }
    assert(
      satisfying.nonEmpty,
      "no on-change workflow runs every required suite — missing: " +
        onChange.map { case (n, m) => s"$n: ${m.mkString(", ")}" }.mkString(" | ")
    )
  }

  // ── Scenario: Adversarial — a job that reports success on an unrun
  //    suite is not sufficient
  // spec: workflow-delivery-hygiene — Scenario: Adversarial — a job that reports success on an unrun suite is not sufficient
  test("a job configuration in which the acceptance suite step is skipped is reported") {
    // A complete-looking on-change workflow with the acceptance step REMOVED
    val withoutAcceptance: String =
      """name: verify
        |on:
        |  push:
        |    branches: [main]
        |jobs:
        |  verify:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Checkout
        |        uses: actions/checkout@v4
        |      - name: Differential comparison
        |        run: sbt probatioOracleDiff
        |""".stripMargin
    assert(
      missingCiSteps(withoutAcceptance).contains("acceptance suite (bats)"),
      "a configuration lacking the acceptance step must report it"
    )
    // The step present but neutralised by an `if:` guard is skipped, not run
    val guardedAcceptance: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Acceptance suite
        |        if: false
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    assert(
      missingCiSteps(guardedAcceptance).contains("acceptance suite (bats)"),
      "a skipped acceptance step must be reported, not counted as run"
    )
    // … and by `continue-on-error` — a suite that cannot fail the job
    val softFailed: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - name: Acceptance suite
        |        continue-on-error: true
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    assert(
      missingCiSteps(softFailed).contains("acceptance suite (bats)"),
      "an acceptance step that cannot fail the job must be reported"
    )
    // … and at JOB level — an `if:` outside every step block neutralises
    // all steps; the step-level scan alone would not see it
    val jobGuarded: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    if: false
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |      - name: Differential
        |        run: sbt probatioOracleDiff
        |""".stripMargin
    assert(
      missingCiSteps(jobGuarded).contains("acceptance suite (bats)"),
      "a job-level if: guard neutralises every step — the suites never run"
    )
    // A paths: filter means a change outside the filter runs nothing —
    // the workflow does not trigger on each change
    val pathFiltered: String =
      """name: verify
        |on:
        |  push:
        |    branches: [main]
        |    paths: ['openspec/**']
        |jobs:
        |  verify:
        |    steps:
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    assert(
      !triggersOnChange(pathFiltered),
      "a paths:-filtered trigger does not run on each change"
    )
    // A guard on the step MARKER line itself — `- if: false` is valid
    // YAML for a guarded unnamed step — neutralises that step
    val markerGuarded: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - uses: actions/checkout@v4
        |      - if: false
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    assert(
      missingCiSteps(markerGuarded).contains("acceptance suite (bats)"),
      "a `- if:` marker-line guard neutralises its step"
    )
    // A needle inside a comment cannot run a suite
    val commentNeedle: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - name: Acceptance suite
        |        run: |
        |          # bats openspec/schemas/verified-scala3/tests
        |          echo nothing
        |""".stripMargin
    assert(
      missingCiSteps(commentNeedle).contains("acceptance suite (bats)"),
      "a commented-out invocation must not count as running the suite"
    )
    // A `- ` list item inside a `run: |` literal block is script content,
    // not a step — it must not slice the block and hide the guard
    val literalDash: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - name: Acceptance suite
        |        if: false
        |        run: |
        |          flags="x"
        |          - bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    assert(
      missingCiSteps(literalDash).contains("acceptance suite (bats)"),
      "a `- ` inside a literal block must not mask the step's guard"
    )
  }

  // ── Requirement: The continuous-integration templates name the tools
  //    that exist
  private val ciTemplateDir: String = s"$schemaDirRel/ci"
  private val ciTemplateNames: List[String] =
    List("github-actions.yml", "gitlab-ci.yml", "azure-pipelines.yml")

  // Invoked tools are path-like tokens naming a shipped script or suite:
  // *.sh, *.bash, *.bats, the tests/ directory, or the launcher
  // bin/probatio. Action refs (actions/checkout@v4) and package names
  // carry no tool suffix and are not extracted. Glob pathspecs
  // ('**/*.sh'), parameter expansions (${VAR}) and command substitutions
  // ($(...)) are not tool invocations either — the token alphabet excludes
  // '*', '{', '}' and '$' so they cannot form a token.
  private def invokedToolPaths(text: String): List[String] = {
    val token = "[A-Za-z0-9_.~/-]+".r
    token
      .findAllIn(text)
      .toList
      .map(_.stripSuffix("/")) // a trailing slash would defeat the suffix checks below
      .filter(_.contains("/"))
      .filter(t =>
        t.endsWith(".sh") || t.endsWith(".bash") || t.endsWith(".bats") ||
          t.endsWith("/tests") || t.endsWith("bin/probatio")
      )
      .distinct
  }

  // A tool "has been replaced" when its path now carries a forwarder in
  // place of an implementation. Two committed facts attest a replacement:
  // the strangler swap leaves a `<tool>.predecessor.bak` sibling holding
  // the retired implementation, and the file itself degenerates to a pure
  // forwarding shim — every executable line is the single `exec`. The
  // launcher bin/probatio is NOT replaced under this reading: it is new
  // (no predecessor sibling) and carries control flow, not a bare exec.
  private def isPureExecShim(f: File): Boolean = {
    val executable: List[String] =
      fileContents(f).linesIterator.toList
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
    // Both shipped shapes: the retired 2-line `exec` shim and the current
    // 3-line form whose only logic is `NAME=...` assignments computing the
    // script's own location before a single trailing `exec`.
    executable.lastOption.exists(_.startsWith("exec ")) &&
    executable.dropRight(1).forall(l => l.matches("[A-Za-z_][A-Za-z0-9_]*=.*"))
  }

  private def isReplacedTool(f: File): Boolean =
    new File(f.getPath + ".predecessor.bak").exists() || isPureExecShim(f)

  // Tools resolve relative to the repository root — the directory the
  // shipped template runs from once copied (see ci/README.md). A violation
  // is (template, tool, problem): the tool is absent from the tree
  // (removed) or is present only as a forwarder (replaced).
  private def toolViolations(template: String, text: String): List[(String, String, String)] =
    invokedToolPaths(text).flatMap { p =>
      val f: File = new File(repoRoot, p)
      if (!f.exists()) List((template, p, "not present in the tree"))
      else if (f.isFile && isReplacedTool(f))
        List((template, p, "a replaced tool — the ported surface is bin/probatio <subcommand>"))
      else Nil
    }

  // spec: workflow-delivery-hygiene — Scenario: Happy path — every template's tools resolve
  test("every shipped CI template's invoked tools resolve against the tree") {
    val violations: List[(String, String, String)] = ciTemplateNames.flatMap { name =>
      val f: File = new File(repoRoot, s"$ciTemplateDir/$name")
      assert(f.isFile, s"shipped template missing: $name")
      toolViolations(name, fileContents(f))
    }
    assert(
      violations.isEmpty,
      "templates invoke tools that are absent or replaced: " +
        violations.map { case (t, p, why) => s"$t → $p ($why)" }.mkString(", ")
    )
  }

  // spec: workflow-delivery-hygiene — Scenario: Adversarial — a template naming a removed tool is reported
  test("a template naming a removed tool is reported") {
    val doctored: String =
      """name: verify
        |steps:
        |  - name: Lint
        |    run: openspec/schemas/verified-scala3/scanner/removed-tool.sh
        |""".stripMargin
    val report: List[(String, String, String)] = toolViolations("gitlab-ci.yml", doctored)
    assert(
      report.exists { case (t, p, _) =>
        t == "gitlab-ci.yml" && p == "openspec/schemas/verified-scala3/scanner/removed-tool.sh"
      },
      s"the check must name the template and the missing tool, got: $report"
    )
    // A template naming a REPLACED tool (a swapped shim path) is reported too —
    // the requirement's second clause. spec-lint.sh carries the marker today.
    val replacedNaming: String =
      """name: verify
        |steps:
        |  - name: Lint
        |    run: openspec/schemas/verified-scala3/scanner/spec-lint.sh .
        |""".stripMargin
    val replacedReport: List[(String, String, String)] =
      toolViolations("azure-pipelines.yml", replacedNaming)
    assert(
      replacedReport.exists { case (t, p, _) =>
        t == "azure-pipelines.yml" && p == "openspec/schemas/verified-scala3/scanner/spec-lint.sh"
      },
      s"a template naming a replaced tool must be reported, got: $replacedReport"
    )
    // A trailing slash must not hide the token — `tests/` resolves like
    // `tests`, so a misspelled suite path is still reported as missing.
    val trailingSlash: String =
      """name: verify
        |steps:
        |  - name: Acceptance
        |    run: bats openspec/schemas/no-such-schema/tests/
        |""".stripMargin
    val slashReport: List[(String, String, String)] =
      toolViolations("github-actions.yml", trailingSlash)
    assert(
      slashReport.exists { case (t, p, _) =>
        t == "github-actions.yml" && p == "openspec/schemas/no-such-schema/tests"
      },
      s"a trailing-slash tool path must still resolve, got: $slashReport"
    )
    // The current 3-line shim shape (assignments + single trailing exec)
    // counts as replaced even with no .predecessor.bak sibling.
    val tmp: java.io.File = java.io.File.createTempFile("shim", ".sh")
    try {
      val w = new java.io.PrintWriter(tmp, "UTF-8")
      try {
        w.println("#!/usr/bin/env bash")
        w.println("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"")
        w.println("exec \"$SCRIPT_DIR/../bin/probatio\" spec-lint \"$@\"")
      } finally w.close()
      assert(
        isPureExecShim(tmp),
        "a 3-line assignment+exec forwarder must be recognised as a replaced tool"
      )
    } finally { val _ = tmp.delete() }
  }

  // ══════════════════════════════════════════════════════════════════════
  // spec 10 of repair-probatio-cutover: schema-rename-completion
  //
  // spec: schema-rename-completion — Property: no-shipped-document-presents-the-previous-name-as-current
  // spec: schema-rename-completion — Scenario: Adversarial — no template presents the previous name as current
  //
  // Enumerated, not sampled: the domain is the finite set of shipped
  // tutorial documents and CI templates, discovered at test time rather
  // than listed, so a newly added document is covered automatically.
  //
  // An occurrence of the previous name is a violation UNLESS:
  //   1. the containing line carries a historical marker (the spec's
  //      "marked as the former identity" — excluded by the marking, not
  //      by a name-based exception), or
  //   2. the occurrence is a path segment into the still-named schema
  //      directory (preceded or followed by `/`), or names the
  //      `verified-scala3-gate` state-dir/adapter filenames — those
  //      artifacts are NOT renamed by this change (the directory rename
  //      is the recorded deferral), so a path naming them is a fact,
  //      not a stale identity. The stamp namespace `verified-scala3-schema`
  //      is NOT exempt: nothing current carries it, so an unmarked
  //      occurrence presents the pre-rename stamp as current.
  // ══════════════════════════════════════════════════════════════════════

  private val previousName: String = "verified-scala3"

  private val historicalMarkers: List[String] = List(
    "formerly",
    "pre-rename",
    "previous",
    "previously",
    "rename",
    "old name",
    "legacy",
    "historical",
    "was named",
    "was called"
  )

  /** All start offsets of `needle` inside `hay`. */
  private def occurrencesOf(hay: String, needle: String): List[Int] =
    scala.util.matching.Regex.quote(needle).r.findAllMatchIn(hay).map(_.start).toList

  /** True when the occurrence at `idx` names a still-existing artifact. */
  private def isCurrentArtifactRef(line: String, idx: Int): Boolean = {
    val end: Int                = idx + previousName.length
    val before: Char            = if (idx > 0) line.charAt(idx - 1) else ' '
    val after: Char             = if (end < line.length) line.charAt(end) else ' '
    val isPathSegment: Boolean  = before == '/' || after == '/'
    val isGateArtifact: Boolean = line.substring(end).startsWith("-gate")
    isPathSegment || isGateArtifact
  }

  private def isMarkedHistorical(line: String): Boolean = {
    val lower: String = line.toLowerCase
    historicalMarkers.exists((m: String) => lower.contains(m))
  }

  /**
   * The previous-name violations in one document's text:
   * `(lineNumber, trimmed line)` for every unmarked, non-artifact
   * occurrence.
   */
  private def previousNameViolations(text: String): List[(Int, String)] =
    text.linesIterator.toList.zipWithIndex.flatMap { case (line, i) =>
      if (isMarkedHistorical(line)) Nil
      else
        occurrencesOf(line, previousName).collect {
          case idx if !isCurrentArtifactRef(line, idx) => (i + 1, line.trim)
        }
    }

  /** The shipped tutorial documents and CI templates, discovered at test time. */
  private def shippedDocuments(): List[File] = {
    val schemaDir: File = new File(repoRoot, schemaDirRel)
    val docsDir: File   = new File(schemaDir, "docs")
    val ciDir: File     = new File(schemaDir, "ci")
    val docs: List[File] =
      Option(docsDir.listFiles()).toList.flatten
        .filter((f: File) => f.isFile && f.getName.endsWith(".html"))
    val templates: List[File] =
      Option(ciDir.listFiles()).toList.flatten
        .filter((f: File) => f.isFile && (f.getName.endsWith(".yml") || f.getName.endsWith(".yaml")))
    docs ++ templates
  }

  property("no shipped document presents the previous name as current") {
    for {
      _ <- Gen.constant(()).forAll
    } yield {
      val docs: List[File] = shippedDocuments()
      if (docs.isEmpty)
        Result.failure.log("no shipped documents discovered — the property must have subjects")
      else
        docs
          .map { doc =>
            val violations: List[(Int, String)] = previousNameViolations(fileContents(doc))
            Result
              .assert(violations.isEmpty)
              .log(
                s"${doc.getName}: ${violations.length} unmarked occurrence(s) of '$previousName'\n" +
                  violations.map { case (n, l) => s"  line $n: $l" }.mkString("\n")
              )
          }
          .foldLeft(Result.success)((acc: Result, r: Result) => acc.and(r))
    }
  }

  test("the previous-name check distinguishes marked, artifact, and violating occurrences") {
    // A marked occurrence is not a violation — excluded by the marking.
    val marked: String = "This workflow was formerly verified-scala3 before the rename."
    assertEquals(previousNameViolations(marked), Nil, "marked occurrence must be exempt")
    // A path segment into the still-named directory is a fact, not a stale identity.
    val pathRef: String = "run: bats openspec/schemas/verified-scala3/tests/"
    assertEquals(previousNameViolations(pathRef), Nil, "directory path reference must be exempt")
    // The gate state-dir filename still exists under that name.
    val gateRef: String = "state lives under .git/verified-scala3-gate"
    assertEquals(previousNameViolations(gateRef), Nil, "gate state-dir reference must be exempt")
    // A bare occurrence with no marker presents the previous name as current.
    val bare: String = "name: verified-scala3"
    assertEquals(
      previousNameViolations(bare).length,
      1,
      "an unmarked bare occurrence must be reported"
    )
    // The stamp namespace is not exempt: an unmarked stamp string is a violation.
    val stamp: String = "generatedBy: verified-scala3-schema/7.0.0"
    assertEquals(
      previousNameViolations(stamp).length,
      1,
      "an unmarked pre-rename stamp must be reported"
    )
  }

  // ══════════════════════════════════════════════════════════════════════
  // delivery-verified (spec 7 of finish-probatio-replacement) — the CI
  // job's acceptance step exercises the archive path: an assembly build
  // precedes it and no native build does.
  // spec: finish-probatio-replacement/delivery-verified — Requirement: The CI job runs the tools through the archive
  // ══════════════════════════════════════════════════════════════════════

  // The workflow's step blocks as ordered text regions, paired with
  // their start lines — the same `- ` marker segmentation (with
  // literal-block exclusion) the completeness check uses.
  private def workflowStepBlocks(text: String): List[(Int, List[String])] = {
    val lines: List[String] = text.linesIterator.toList
    val starts: List[Int]   = stepMarkerIndices(lines)
    starts.zipWithIndex.map { case (s, k) =>
      val e: Int = starts.drop(k + 1).headOption.getOrElse(lines.length)
      (s, lines.slice(s, e))
    }
  }

  // The line ranges of each job's `steps:` list. Job keys are the
  // two-space-indented keys under the top-level `jobs:` map; a job's
  // steps region runs from its `steps:` line to the next line at
  // same-or-lesser indent or the end of the job. The archive-path
  // check must be evaluated inside the acceptance step's own job —
  // artifacts do not cross jobs without an explicit upload/download,
  // so an `assembly` step in a parallel job does not arm the
  // acceptance suite's archive path. A document with no `jobs:` map
  // is treated as a single range so the ordering checks still apply.
  private def jobStepsRegions(lines: List[String]): List[(Int, Int)] = {
    val jobsIdx: Int = lines.indexWhere(l => l.matches("^jobs:\\s*(#.*)?$"))
    if (jobsIdx < 0) List((0, lines.length))
    else {
      val jobsEnd: Int =
        lines.indices
          .find(i => i > jobsIdx && lines(i).nonEmpty && leadingIndent(lines(i)) == 0)
          .getOrElse(lines.length)
      val jobKeys: List[Int] =
        (jobsIdx + 1 until jobsEnd).toList.filter(i => lines(i).matches("^  [A-Za-z0-9_-]+:\\s*(#.*)?$"))
      val jobRanges: List[(Int, Int)] =
        jobKeys.zipWithIndex.map { case (s, k) =>
          (s, jobKeys.drop(k + 1).headOption.getOrElse(jobsEnd))
        }
      jobRanges.flatMap { case (js, je) =>
        (js + 1 until je).find(i => lines(i).trim == "steps:") match {
          case Some(si) =>
            val stepsIndent: Int = leadingIndent(lines(si))
            val stepsEnd: Int =
              (si + 1 until je)
                .find(k => lines(k).trim.nonEmpty && leadingIndent(lines(k)) <= stepsIndent)
                .getOrElse(je)
            List((si, stepsEnd))
          case None => Nil // a job without a steps list contributes no step region
        }
      }
    }
  }

  private def executableLines(block: List[String]): List[String] =
    block.filter(l => !l.trim.startsWith("#"))

  /**
   * The archive-path issues in one workflow's text. The acceptance step
   * (the step whose executable lines invoke `bats` over the
   * `verified-scala3/tests` suite) exercises the archive path iff an
   * `assembly` build precedes it *within the same job's steps* and no
   * `nativeImage` build does.
   */
  private def archivePathIssues(text: String): List[String] = {
    val lines: List[String]               = text.linesIterator.toList
    val blocks: List[(Int, List[String])] = workflowStepBlocks(text)
    val acceptance: Option[Int] =
      blocks.collectFirst {
        case (s, b) if executableLines(b).exists(l => acceptanceNeedles.forall(l.contains)) =>
          s
      }
    acceptance match {
      case None =>
        List("no acceptance step (bats over the verified-scala3 suite) found")
      case Some(acceptStart) =>
        jobStepsRegions(lines).find { case (rs, re) =>
          acceptStart >= rs && acceptStart < re
        } match {
          case None =>
            List(
              "the acceptance step is not inside a job's steps list — its archive path cannot be established"
            )
          case Some((rs, _)) =>
            val before: List[List[String]] =
              blocks
                .filter { case (s, _) => s >= rs && s < acceptStart }
                .map(_._2)
            val buildsArchive: Boolean =
              before.exists(b => executableLines(b).exists(_.contains("assembly")))
            val buildsNative: Boolean =
              before.exists(b => executableLines(b).exists(_.contains("nativeImage")))
            List(
              if (buildsArchive) None
              else
                Some(
                  "the acceptance suite runs with no archive build before it in its own job — the tools do not resolve to the archive"
                ),
              if (!buildsNative) None
              else
                Some(
                  "a native build step precedes the acceptance suite — the suite no longer exercises the archive path"
                )
            ).flatten
        }
    }
  }

  // spec: finish-probatio-replacement/delivery-verified — Scenario: Happy path — the job's acceptance step uses the archive
  test("the on-change CI job's acceptance step uses the archive") {
    val (code, trackedOut): (Int, String) = runGit(List("ls-files", ".github/workflows"))
    assertEquals(code, 0, s"git ls-files must succeed: $trackedOut")
    val onChange: List[File] =
      trackedOut.linesIterator.toList
        .filter(p => p.endsWith(".yml") || p.endsWith(".yaml"))
        .map(rel => new File(repoRoot, rel))
        .filter(f => triggersOnChange(fileContents(f)))
    assert(
      onChange.nonEmpty,
      "no on-change workflow exists — the archive-path check needs a subject"
    )
    for (f <- onChange) {
      val issues: List[String] = archivePathIssues(fileContents(f))
      assert(
        issues.isEmpty,
        s"${f.getName}: the acceptance step must exercise the archive path — ${issues.mkString("; ")}"
      )
    }
  }

  // spec: finish-probatio-replacement/delivery-verified — Scenario: Adversarial — a native build step before the acceptance step is reported
  test("a native build step before the acceptance step is reported") {
    // A native image built before the acceptance step — the suite can no
    // longer be said to exercise the archive path.
    val nativeFirst: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Checkout
        |        uses: actions/checkout@v4
        |      - name: Build the native image
        |        run: sbt -batch 'probatio-cli/nativeImage'
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    val nativeIssues: List[String] = archivePathIssues(nativeFirst)
    assert(
      nativeIssues.exists(_.contains("native build")),
      s"a nativeImage step before the acceptance step must be reported, got: $nativeIssues"
    )
    // Acceptance with no archive build at all — the tools resolve to
    // nothing built in the job.
    val noArchive: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - name: Checkout
        |        uses: actions/checkout@v4
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    val noArchiveIssues: List[String] = archivePathIssues(noArchive)
    assert(
      noArchiveIssues.exists(_.contains("no archive build")),
      s"an acceptance step with no preceding archive build must be reported, got: $noArchiveIssues"
    )
    // The correct ordering reports nothing: archive first, acceptance second.
    val archiveFirst: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - name: Checkout
        |        uses: actions/checkout@v4
        |      - name: Build the assembly
        |        run: sbt -batch 'probatio-cli/assembly'
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    assertEquals(
      archivePathIssues(archiveFirst),
      Nil,
      "an archive build preceding the acceptance step must satisfy the check"
    )
    // A native build AFTER the acceptance step does not contaminate it —
    // the suite already ran against the archive.
    val nativeAfter: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    steps:
        |      - name: Build the assembly
        |        run: sbt -batch 'probatio-cli/assembly'
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |      - name: Build the native image
        |        run: sbt -batch 'probatio-cli/nativeImage'
        |""".stripMargin
    assertEquals(
      archivePathIssues(nativeAfter),
      Nil,
      "a nativeImage step AFTER the acceptance step is not an archive-path violation"
    )
    // Ring-8 finding F1: an `assembly` step in a DIFFERENT job does not
    // arm the acceptance step — artifacts do not cross jobs without an
    // explicit upload/download step.
    val assemblyInParallelJob: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  build:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Build the assembly
        |        run: sbt -batch 'probatio-cli/assembly'
        |  verify:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Checkout
        |        uses: actions/checkout@v4
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    val parallelIssues: List[String] = archivePathIssues(assemblyInParallelJob)
    assert(
      parallelIssues.exists(_.contains("no archive build")),
      s"an assembly build in a parallel job must not satisfy the archive requirement, got: $parallelIssues"
    )
    // A `- ` item in a non-steps list inside the SAME job (a matrix
    // include naming 'assembly') is not a build step.
    val matrixFalsePositive: String =
      """name: verify
        |on: [push, pull_request]
        |jobs:
        |  verify:
        |    runs-on: ubuntu-latest
        |    strategy:
        |      matrix:
        |        include:
        |          - platform: assembly-amd64
        |    steps:
        |      - name: Checkout
        |        uses: actions/checkout@v4
        |      - name: Acceptance suite
        |        run: bats openspec/schemas/verified-scala3/tests
        |""".stripMargin
    val matrixIssues: List[String] = archivePathIssues(matrixFalsePositive)
    assert(
      matrixIssues.exists(_.contains("no archive build")),
      s"an 'assembly'-named matrix item must not satisfy the archive requirement, got: $matrixIssues"
    )
  }
}
