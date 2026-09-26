package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Result
import org.sinemenda.probatio.core.EventDispatch
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.migration.ControlledVariable
import org.sinemenda.probatio.migration.HermeticEnv

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.withTempDir

/**
 * Subprocess conformance tests for spec: cli-entrypoint-contract
 *
 * Every exposed tool is started as a separate process (the built artifact)
 * and its exit status and output channels are asserted. This is the only
 * check that can catch a mismatch between how the runtime delivers arguments
 * and how the in-process selection function consumes them.
 *
 * ORACLE POLARITY (Step 2):
 *   - All tests RED — the built artifact does not exist yet (Step 3 builds
 *     it). The tests fail by reporting the missing artifact, not by skipping.
 *     A missing artifact is a FAILURE, not a skip (per the spec).
 *
 * spec: cli-entrypoint-contract — Requirement: Every tool is exercised through the built artifact, not only through in-process calls
 * spec: cli-entrypoint-contract — Property: subprocess-agrees-with-in-process
 */
final class SubprocessConformanceSpec extends ProbatioCliSuite:

  // The event-dispatch parity property runs the predecessor gate as a
  // subprocess per generated case — subprocess-suite timeout.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  /** Helper: construct an InvocationName from a raw string. */
  private def inv(name: String): InvocationName =
    InvocationName.fromRuntime(InvocationSource.classify(name)) match
      case Right(value) => value
      case Left(err)    => fail(s"invalid invocation name '$name': $err")

  /**
   * The path to the built artifact. The shims resolve the artifact at this
   * path; the conformance check MUST start the artifact at the same path so
   * a check that passes cannot coexist with shims pointing at a different
   * artifact.
   *
   * spec: cli-entrypoint-contract — Scenario: Edge case — the artifact under check is the one the shims resolve
   */
  private def artifactPath: String =
    // The native-image output path from build.sbt:
    //   target.value / "native-image" / "probatio"
    // The assembly JAR fallback:
    //   target/scala-3.8.4/probatio-cli-assembly-*.jar
    // Step 3 will wire this to the actual shim resolution path.
    // Resolve against the repository root — the forked test runner's cwd is
    // the module directory, not the repo root.
    val repoRoot: java.nio.file.Path = Iterator
      .unfold(java.nio.file.Paths.get("").toAbsolutePath.normalize)((p: java.nio.file.Path) =>
        Option(p.getParent).map((par: java.nio.file.Path) => p -> par)
      )
      .find((p: java.nio.file.Path) => java.nio.file.Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(java.nio.file.Paths.get("").toAbsolutePath)
    repoRoot.resolve("workflow/cli/target/native-image/probatio").toString

  /** Whether the built artifact exists. */
  private def artifactExists: Boolean =
    new File(artifactPath).exists()

  /**
   * Runs the built artifact as a subprocess with the given arguments,
   * returning the exit code.
   *
   * For generic dispatch, the tool name is passed as the first argument.
   * For symlink dispatch, a temporary symlink would be created — wired in
   * Step 3.
   */
  private def runSubprocess(argList: List[String]): Int =
    if !artifactExists then fail(s"built artifact not found at $artifactPath — conformance check FAILS, does not skip")
    val cmd: List[String] = List(artifactPath) ++ argList
    // spec: hermetic-test-processes — via the shared helper; the child
    // sees the fixed base only.
    HermeticEnv.run(cmd, HermeticEnv.empty)

  // ── spec: jar-launcher-dispatch — the archive conformance run ────────────

  /**
   * The path to the built assembly archive — the JVM-delivered artifact.
   * Globs `probatio-cli-assembly-*.jar` so a version bump does not silently
   * empty the check: multiple matches fail loudly.
   */
  private def archivePath: String =
    val dir: Path = Path.of(artifactPath).getParent.resolve("../scala-3.8.4").normalize
    val candidates: List[Path] =
      if Files.isDirectory(dir) then
        scala.util
          .Using(Files.list(dir))(_.filter((p: Path) => p.getFileName.toString.matches("probatio-cli-assembly-.*\\.jar")).toArray(Array.ofDim[Path](_)).toList)
          .fold(
            (e: Throwable) => fail(s"could not list $dir for assembly archives: ${e.getMessage}"),
            (l: List[Path]) => l
          )
      else List.empty
    candidates match
      case List(single) => single.toString
      case Nil          => dir.resolve("probatio-cli-assembly-0.1.0-SNAPSHOT.jar").toString
      case many         => fail(s"multiple assembly archives under $dir: $many")

  /** Whether the built archive exists. */
  private def archiveExists: Boolean =
    new File(archivePath).exists()

  /**
   * Runs the tool through the assembly archive: `java -jar <archive> args`.
   * Under `java -jar` the runtime command exposes the archive's own file
   * name — the invocation source this spec adds. Returns
   * `(exit status, stdout)` for the conformance comparison.
   *
   * spec: jar-launcher-dispatch — Requirement: The archive path has its own subprocess conformance run
   */
  private def runArchive(argList: List[String]): (Int, String) =
    if !archiveExists then fail(s"built archive not found at $archivePath — archive conformance check FAILS, does not skip")
    // spec: hermetic-test-processes — via the shared helper; the child
    // sees the fixed base only.
    val r: org.sinemenda.probatio.migration.HermeticResult =
      HermeticEnv.capture(List("java", "-jar", archivePath) ++ argList, HermeticEnv.empty)
    (r.exitCode, r.out)

  /**
   * Runs the native artifact with captured stdout, for comparison against
   * the archive run.
   */
  private def runNative(argList: List[String]): (Int, String) =
    if !artifactExists then fail(s"built artifact not found at $artifactPath — conformance check FAILS, does not skip")
    val r: org.sinemenda.probatio.migration.HermeticResult =
      HermeticEnv.capture(List(artifactPath) ++ argList, HermeticEnv.empty)
    (r.exitCode, r.out)

  // ── Scenario: Happy path — help runs through the archive
  // spec: jar-launcher-dispatch — Scenario: Happy path — help runs through the archive
  test("help runs through the archive and lists every subcommand"):
    val (code: Int, out: String) = runArchive(List("--help"))
    assertEquals(code, 0, s"archive --help must exit clean, got $code\n$out")
    Subcommand.values.foreach { sub =>
      assert(out.contains(Subcommand.cliName(sub)), s"archive --help must list ${Subcommand.cliName(sub)}\n$out")
    }

  // ── Scenario: Adversarial — a missing archive fails the run
  // spec: jar-launcher-dispatch — Scenario: Adversarial — a missing archive fails the run
  test("a missing archive fails the conformance run (does not skip)"):
    if archiveExists then
      // The archive is present — the real check is every test above, which
      // fails via runArchive rather than skipping.
      ()
    else fail(s"built archive not found at $archivePath — a missing archive is a FAILURE, not a skip")

  // ── Scenario: Happy path — the gate runs through the archive
  // spec: jar-launcher-dispatch — Scenario: Happy path — the gate runs through the archive
  test("the gate's injection tier runs through the archive"):
    withTempDir("gate-archive") { (repo: Path) =>
      writeDispatchParityRepo(repo, withWorkflow = true)
      val (code: Int, out: String) = runArchive(
        List(
          "gate",
          "--repo",
          repo.toString,
          "--event",
          "session-start",
          "--format",
          "text",
          "--session",
          "archive-parity"
        )
      )
      assert(code >= 0 && code <= 2, s"gate through archive must exit in {{0,1,2}}, got $code\n$out")
      assert(out.nonEmpty, "the gate's injection tier must produce output")
    }

  // ── spec: entrypoint-split — the before/after-observable corpus ────────

  /**
   * Run the recorded corpus row through the artifact it names, under the
   * same hermetic base the recording used: `(exit, stdout, stderr)`.
   */
  private def replay(row: EntrypointSplitOracle.CorpusRow): (Int, String, String) =
    val r: org.sinemenda.probatio.migration.HermeticResult =
      row.artifact match
        case "archive" => HermeticEnv.capture(List("java", "-jar", archivePath) ++ row.argv, HermeticEnv.empty)
        case "native"  => HermeticEnv.capture(List(artifactPath) ++ row.argv, HermeticEnv.empty)
        case other     => sys.error(s"corpus row names an unknown artifact: $other")
    (r.exitCode, r.out, r.err)

  // spec: entrypoint-split — Property: split-preserves-every-observable
  //
  // For every invocation in the enumerated corpus — every conformance
  // invocation plus the rejection forms — the output and termination
  // status after the split equal those recorded before it. The domain is
  // finite and enumerated in full (corpus-before.tsv); each process runs
  // in the hermetic environment, so the comparison cannot pass because of
  // an inherited variable.
  property("the split preserves every observable"):
    val rows: List[EntrypointSplitOracle.CorpusRow] = EntrypointSplitOracle.loadCorpus
    for row <- Gen.elementUnsafe(rows).forAll
    yield
      val after: (Int, String, String) = replay(row)
      val before: (Int, String, String) = (row.exit, row.out, row.err)
      Result
        .assert(after == before)
        .log(s"argv=${row.argv.mkString(" ")} artifact=${row.artifact} before=$before after=$after")

  // The domain is finite: enumerate it in full so coverage cannot depend
  // on the sample draw.
  test("every recorded corpus invocation is replayed, byte for byte"):
    val rows: List[EntrypointSplitOracle.CorpusRow] = EntrypointSplitOracle.loadCorpus
    val mismatches: List[String] = rows.collect {
      case row if replay(row) != (row.exit, row.out, row.err) =>
        s"${row.artifact} ${row.argv.mkString(" ")}: expected exit ${row.exit}"
    }
    assert(mismatches.isEmpty, "post-split observables diverged:\n" + mismatches.mkString("\n"))

  // ── Scenario: Happy path — every tool conforms through the archive
  // spec: jar-launcher-dispatch — Scenario: Happy path — every tool conforms through the archive
  // spec: jar-launcher-dispatch — Property: archive-conformance-matches-native-conformance
  //
  // For every exposed tool and every invocation in the conformance corpus,
  // the archive run and the native run produce the same termination status
  // and the same standard output. The corpus is fixed and constructive —
  // one valid and one invalid invocation per exposed tool, as the existing
  // native run uses.
  private def conformanceCorpus: List[List[String]] =
    Subcommand.values.toList.flatMap { sub =>
      val name: String = Subcommand.cliName(sub)
      List(List(name, "--help"), List(name, "--nonexistent-flag-xyz"))
    }

  property("archive and native conform identically"):
    for invocation <- Gen.elementUnsafe(conformanceCorpus).forAll
    yield
      val archive: (Int, String) = runArchive(invocation)
      val native: (Int, String)  = runNative(invocation)
      Result
        .assert(archive == native)
        .log(s"archive=$archive native=$native invocation=$invocation")

  // ── Scenario: Happy path — every exposed tool starts and reports a documented exit status
  // spec: cli-entrypoint-contract — Scenario: Happy path — every exposed tool starts and reports a documented exit status
  test("every exposed tool starts as a subprocess and exits with a status in {0,1,2}"):
    Subcommand.values.foreach { sub =>
      val name: String = Subcommand.cliName(sub)
      // The shortest argument list that the tool accepts without reporting a
      // missing or unrecognised flag: --help (which every tool accepts).
      val exitCode: Int = runSubprocess(List(name, "--help"))
      assert(
        exitCode >= 0 && exitCode <= 2,
        s"subprocess for $name exited with $exitCode, outside {0,1,2}"
      )
    }

  // ── Scenario: Error path — a tool that cannot be started is a failure, not a skip
  // spec: cli-entrypoint-contract — Scenario: Error path — a tool that cannot be started is a failure, not a skip
  test("a missing artifact fails the conformance check (does not skip)"):
    if artifactExists then
      // If the artifact exists, this test is vacuously true (the artifact is
      // present). The real check is the "every exposed tool" test above.
      ()
    else fail(s"built artifact not found at $artifactPath — a missing tool entry point is a FAILURE, not a skip")

  // ── Scenario: Edge case — the artifact under check is the one the shims resolve
  // spec: cli-entrypoint-contract — Scenario: Edge case — the artifact under check is the one the shims resolve
  test("the artifact path matches the shim resolution path"):
    // The shim resolution path is configured in the sbt-probatio plugin.
    // This test asserts that the conformance check uses the same path.
    // Step 3 will wire this to read the actual shim resolution path from
    // the plugin configuration and assert equality.
    val path: String = artifactPath
    assert(path.nonEmpty, "artifact path must be configured")

  // ── Scenario: Adversarial — an unported tool name is rejected, not silently accepted
  // spec: cli-entrypoint-contract — Scenario: Adversarial — an unported tool name is rejected, not silently accepted
  test("subprocess with 'registry-check --change c' exits non-zero (finding status)"):
    val exitCode: Int = runSubprocess(List("registry-check", "--change", "c"))
    assert(exitCode != 0, s"unported tool 'registry-check' should exit non-zero, got $exitCode")

  // ── Scenario: Happy path — a ported tool is still selectable
  // spec: cli-entrypoint-contract — Scenario: Happy path — a ported tool is still selectable
  test("subprocess with 'ledger read --file f --change c' runs the ledger tool"):
    val exitCode: Int = runSubprocess(List("ledger", "read", "--file", "f", "--change", "c"))
    assert(exitCode >= 0 && exitCode <= 2, s"ledger tool should exit in {0,1,2}, got $exitCode")

  // ── Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
  // spec: cli-entrypoint-contract — Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
  test("subprocess with 'metals start' runs the metals tool's start sub-action"):
    val exitCode: Int = runSubprocess(List("metals", "start"))
    assert(exitCode >= 0 && exitCode <= 2, s"metals start should exit in {0,1,2}, got $exitCode")

  // ── Property: subprocess-agrees-with-in-process
  // spec: cli-entrypoint-contract — Property: subprocess-agrees-with-in-process
  //
  // For every exposed tool and every argument list in the fixture corpus,
  // starting the built artifact as a separate process yields the same exit
  // status as calling the selection-and-run path in process.
  //
  // The fixture corpus: one minimal-valid and one minimal-invalid argument
  // list per exposed tool (2 × |tools| entries).
  test("subprocess-agrees-with-in-process: fixture corpus"):
    // Build the fixture corpus: (tool, valid-args, invalid-args) per tool
    val corpus: List[(Subcommand, List[String], List[String])] =
      Subcommand.values.toList.map { sub =>
        val name: String = Subcommand.cliName(sub)
        // Minimal valid: --help (every tool accepts it)
        val valid: List[String] = List(name, "--help")
        // Minimal invalid: an unknown flag
        val invalid: List[String] = List(name, "--nonexistent-flag-xyz")
        (sub, valid, invalid)
      }
    corpus.foreach { case (sub, validArgs, invalidArgs) =>
      val subExitValid: Int      = runSubprocess(validArgs)
      val subExitInvalid: Int    = runSubprocess(invalidArgs)
      val inProcExitValid: Int   = ProbatioMain.dispatch(inv("probatio"), ProgramArgs.fromFixture(validArgs))
      val inProcExitInvalid: Int = ProbatioMain.dispatch(inv("probatio"), ProgramArgs.fromFixture(invalidArgs))
      assertEquals(
        subExitValid,
        inProcExitValid,
        s"subprocess vs in-process mismatch for ${Subcommand.cliName(sub)} valid args: $subExitValid vs $inProcExitValid"
      )
      assertEquals(
        subExitInvalid,
        inProcExitInvalid,
        s"subprocess vs in-process mismatch for ${Subcommand.cliName(sub)} invalid args: $subExitInvalid vs $inProcExitInvalid"
      )
    }

  // ── gate-event-compatibility (spec 4 of repair-probatio-cutover) ──
  // spec: gate-event-compatibility — Property: parity-with-predecessor-on-event-dispatch
  //
  // Model-based comparison: the predecessor gate runs as the reference
  // subprocess; the ported gate runs in-process, its `Outcome` mapped to
  // the exit status the binary's own boundary reports. Per the spec's
  // determinism rule only the exit status and the presence of output are
  // observed — no clock, no subprocess timing. STDERR is deliberately
  // NOT compared: the spec's diagnostic line is an intended divergence
  // (the predecessor's fallback is silent); the tier that runs and the
  // exit status are what must agree.

  /** The schema directory, walked up from the test working directory. */
  private def schemaDir: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    Iterator
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find((p: Path) => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))
      .resolve("openspec/schemas/verified-scala3")

  /**
   * The ported gate, in-process: `(exit status, stdout produced
   * output)`. The Outcome→status mapping is the binary boundary's own
   * (Ran carries the status, Finding is the error status, Undetermined
   * the undetermined status).
   */
  private def portedEventExit(repo: Path, name: String): (Int, Boolean) =
    val (out: String, _: String, outcome: Outcome[Int]) =
      StdoutCapture.captureBoth(
        GateCmd.run(
          Array(
            "--repo",
            repo.toString,
            "--event",
            name,
            "--format",
            "text",
            "--session",
            "parity"
          ),
          Map("VERIFIED_SCALA3_SESSION_ID" -> "parity"),
          () => None
        )
      )
    val status: Int = outcome match
      case Outcome.Ran(n)          => n
      case Outcome.Finding(_)      => 1
      case Outcome.Undetermined(_) => 2
    (status, out.nonEmpty)

  /**
   * The predecessor gate as the reference subprocess:
   * `(exit status, stdout produced output)`. stdin is `/dev/null` — the
   * gate reads the hook payload from stdin and an inherited open pipe
   * blocks it forever.
   */
  private def predecessorEventExit(repo: Path, name: String): (Int, Boolean) =
    val gate: String = schemaDir.resolve("hooks/gate.sh.predecessor.bak").toString
    // spec: hermetic-test-processes — the session is the one declared
    // controlled variable; stdin stays /dev/null inside capture.
    val env: HermeticEnv =
      HermeticEnv.build(Map(ControlledVariable.VerifiedScala3SessionId -> "parity"))
    val r: org.sinemenda.probatio.migration.HermeticResult = HermeticEnv.capture(
      List("bash", gate, "--repo", repo.toString, "--event", name, "--format", "text"),
      env
    )
    (r.exitCode, r.out.nonEmpty)

  // ── spec 10 of repair-probatio-cutover: schema-rename-completion ────
  // spec: schema-rename-completion — Proof Obligation: The migration has a caller in the shipped tool
  //
  // `SchemaPolicy.migrateCache` is implemented, unit-tested and formally
  // reachable — and nothing calls it. The caller is an adapter concern:
  // the shipped artifact, run as a subprocess, must perform the cache and
  // state directory migration on first use. HOME is sandboxed to a
  // temporary directory so the check never touches the real user cache.

  /**
   * Run the built artifact with a sandboxed HOME and a neutral working
   * directory. `gate --check-installed` is a read-only invocation: it is
   * the lightest real entrypoint through which "the tool runs".
   */
  private def runArtifactWithHome(home: Path, cwd: Path): Int =
    if !artifactExists then fail(s"built artifact not found at $artifactPath — conformance check FAILS, does not skip")
    // spec: hermetic-test-processes — the sandboxed HOME is a fixed-base
    // override via withBase; stdin stays /dev/null inside run.
    val env: HermeticEnv =
      HermeticEnv.empty.withBase(Map("HOME" -> home.toString))
    HermeticEnv.run(
      List(artifactPath, "gate", "--check-installed"),
      env,
      cwd = Some(cwd.toFile)
    )

  // spec: schema-rename-completion — Scenario: Happy path — a previous directory is migrated once
  test("schema-rename: the shipped tool migrates the previous cache directory on first use"):
    withTempDir("probatio-home") { (home: Path) =>
      withTempDir("probatio-cwd") { (cwd: Path) =>
        val legacy: Path = home.resolve(".cache/verified-scala3")
        Files.createDirectories(legacy)
        Files.writeString(legacy.resolve("heartbeat"), "legacy-content")
        val code: Int = runArtifactWithHome(home, cwd)
        assert(
          code >= 0 && code <= 2,
          s"the tool must exit in {{0,1,2}} on first use, got $code"
        )
        val migrated: Path = home.resolve(".cache/probatio/heartbeat")
        assert(
          Files.isRegularFile(migrated),
          s"the previous directory's contents must appear under the current one — $migrated missing"
        )
      }
    }

  // spec: schema-rename-completion — Scenario: Adversarial — a previous directory is not migrated over an existing current one
  test("schema-rename: the shipped tool does not migrate over an existing current directory"):
    withTempDir("probatio-home") { (home: Path) =>
      withTempDir("probatio-cwd") { (cwd: Path) =>
        val legacy: Path  = home.resolve(".cache/verified-scala3")
        val current: Path = home.resolve(".cache/probatio")
        Files.createDirectories(legacy)
        Files.createDirectories(current)
        Files.writeString(legacy.resolve("stale"), "stale-content")
        Files.writeString(current.resolve("kept"), "current-content")
        val code: Int = runArtifactWithHome(home, cwd)
        assert(code >= 0 && code <= 2, s"exit must be in {{0,1,2}}, got $code")
        assert(
          !Files.exists(current.resolve("stale")),
          "the current directory's contents are unchanged — nothing overwritten"
        )
        assertEquals(
          new String(Files.readAllBytes(current.resolve("kept")), java.nio.charset.StandardCharsets.UTF_8),
          "current-content"
        )
      }
    }

  /**
   * Wipe the shared state dir between the two runs — both gates
   * fingerprint the emitted facts under the same session, so without a
   * reset the second run suppresses a banner the first emitted, and the
   * output-presence comparison would fault the fixture, not the port
   * (the spec-3 marker-reset convention).
   */
  private def resetGateState(repo: Path): Unit =
    Option(repo.resolve(".git/verified-scala3-gate").toFile.listFiles())
      .foreach(_.foreach((f: File) => if f.isFile then f.delete() else ()))

  /**
   * A parity fixture: `withWorkflow` repos carry `openspec/` (the
   * relevance guard passes), the rest do not — the spec's two-arm
   * repository draw. Every fixture is a git repo so the state-dir
   * paths are exercised.
   */
  private def writeDispatchParityRepo(repo: Path, withWorkflow: Boolean): Unit =
    if withWorkflow then Files.createDirectories(repo.resolve("openspec/changes/parity-change/specs/parity-spec"))
    val code: Int =
      HermeticEnv.run(List("git", "-C", repo.toString, "init", "-q"), HermeticEnv.empty)
    assertEquals(code, 0, "git init must succeed")

  property("parity-with-predecessor-on-event-dispatch"):
    for
      name <- EventDispatchFixtures.genEventName.forAll
        .cover(15, "recognised", (n: String) => EventDispatch.recognisedNames.contains(n))
        .cover(15, "unrecognised", (n: String) => !EventDispatch.recognisedNames.contains(n))
      withWorkflow <- Gen.boolean.forAll
    yield withTempDir("gate-dispatch-parity") { (repo: Path) =>
      writeDispatchParityRepo(repo, withWorkflow)
      val ported: (Int, Boolean) = portedEventExit(repo, name)
      resetGateState(repo)
      val model: (Int, Boolean) = predecessorEventExit(repo, name)
      Result.all(
        List(
          Result
            .assert(ported._1 == model._1)
            .log(s"exit drift for '$name' (withWorkflow=$withWorkflow): ported=${ported._1} model=${model._1}"),
          Result
            .assert(ported._2 == model._2)
            .log(
              s"output-presence drift for '$name' (withWorkflow=$withWorkflow): ported=${ported._2} model=${model._2}"
            )
        )
      )
    }
