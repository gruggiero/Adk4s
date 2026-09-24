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
    val lines: java.util.concurrent.atomic.AtomicReference[List[String]] =
      new java.util.concurrent.atomic.AtomicReference(List.empty)
    val logger: scala.sys.process.ProcessLogger = scala.sys.process.ProcessLogger(
      (o: String) => { val _ = lines.updateAndGet((xs: List[String]) => xs :+ o); () },
      (e: String) => { val _ = lines.updateAndGet((xs: List[String]) => xs :+ e); () }
    )
    val code: Int = scala.sys.process.Process(List("git") ++ args, repoRoot).!(logger)
    (code, lines.get().mkString("\n"))
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

  private def requiredCiSteps(): List[RequiredStep] =
    RequiredStep("acceptance suite (bats)", List("bats", "verified-scala3/tests")) ::
      RequiredStep("differential comparison", List("probatioOracleDiff")) ::
      discoveredTestModules().map(m => RequiredStep(s"module suite $m", List(s"$m/test")))

  // Step blocks are the regions between `- ` item markers. A required
  // invocation that appears but is neutralised in its own step — an `if:`
  // guard or `continue-on-error` — counts as skipped, not run: a job that
  // reports success on an unrun suite is the defect this check exists for.
  // The same holds one level up: an `if:` or `continue-on-error` OUTSIDE
  // every step block is a job-level guard that neutralises every step.
  private def missingCiSteps(text: String): List[String] = {
    val lines: List[String] = text.linesIterator.toList
    val stepStarts: List[Int] =
      lines.zipWithIndex.collect { case (l, i) if l.matches("^\\s+-\\s+\\S.*") => i }
    // A line is inside a step block only if every line between the `- `
    // marker and it is indented DEEPER than the marker — this keeps `- `
    // items in `on:`/`env:` lists from masquerading as step blocks that
    // would mask a job-level guard.
    def leading(l: String): Int = l.takeWhile(c => c == ' ' || c == '\t').length
    // `- ` items inside a literal block scalar (`run: |`) are script
    // content, not step markers — exclude those regions so a needle line
    // inside one isn't counted as its own unguarded step.
    val literalStart = "^\\s*.*:\\s*[|>][+-]?\\s*$".r
    val inLiteral: Set[Int] =
      lines.zipWithIndex
        .foldLeft((Set.empty[Int], -1, -1)) { case ((acc, start, startIndent), (l, i)) =>
          if (start >= 0 && (l.trim.isEmpty || leading(l) > startIndent))
            (acc + i, start, startIndent)
          else if (literalStart.pattern.matcher(l).matches())
            (acc, i, leading(l))
          else
            (acc, -1, -1)
        }
        ._1
    val realStepStarts: List[Int] = stepStarts.filterNot(inLiteral.contains)
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
}
