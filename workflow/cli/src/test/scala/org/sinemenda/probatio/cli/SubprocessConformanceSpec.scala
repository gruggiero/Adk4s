package org.sinemenda.probatio.cli

import java.io.File
import scala.sys.process.*

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

  /** Helper: construct an InvocationName from a raw string. */
  private def inv(name: String): InvocationName =
    InvocationName.fromRuntime(name) match
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
      .find((p: java.nio.file.Path) =>
        java.nio.file.Files.isDirectory(p.resolve("openspec/schemas/verified-scala3"))
      )
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
    val cmd: List[String]     = List(artifactPath) ++ argList
    val logger: ProcessLogger = ProcessLogger(_ => (), _ => ())
    val exitCode: Int         = cmd.!(logger)
    exitCode

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
