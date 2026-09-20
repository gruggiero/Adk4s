package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

import java.io.File
import java.nio.file.Files

/**
 * Tests for ShimGenerator — the pure function that generates 3-line hook
 * shims idempotently.
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: probatioGateShim regenerates hook shims idempotently
 * spec: port-scanner-to-probatio/sbt-plugin — Property: shim-idempotency
 */
final class ShimGeneratorSpec extends ProbatioPluginSuite {

  // ── Property: shim-idempotency ──────────────────────────────────────────
  // spec: sbt-plugin — Property: shim-idempotency
  property("shim-idempotency: running twice produces byte-identical output") {
    for {
      resolvedPath <- Gen.string(Gen.alphaNum, linear(1, 50)).map(p => s"/usr/local/bin/$p").forAll
    } yield {
      val shim1: String = shimFor(resolvedPath)
      val shim2: String = shimFor(resolvedPath)
      Result
        .assert(shim1 == shim2)
        .log(s"shim1 != shim2:\n$shim1\n---\n$shim2")
        .and(
          Result
            .assert(shim1.linesIterator.length == 2)
            .log(s"expected 2 lines (shebang + exec), got ${shim1.linesIterator.length}")
        )
        .and(
          Result
            .assert(shim1.startsWith("#!/usr/bin/env bash\n"))
            .log(s"missing shebang: $shim1")
        )
        .and(
          Result
            .assert(shim1.contains("gate \"$@\""))
            .log(s"missing gate subcommand: $shim1")
        )
    }
  }

  // ── Scenario: shim is three lines ───────────────────────────────────────
  // spec: sbt-plugin — Scenario: shim is three lines
  test("shim contains exactly shebang, exec, and trailing newline") {
    val path: String         = "/usr/local/bin/probatio"
    val shim: String         = shimFor(path)
    val lines: Array[String] = shim.split("\n", -1)
    // 3 lines: shebang, exec, trailing empty (from trailing newline)
    assertEquals(lines.length, 3, s"expected 3 parts (shebang, exec, trailing), got ${lines.length}: $shim")
    assertEquals(lines(0), "#!/usr/bin/env bash")
    assertEquals(lines(1), s"""exec "$path" gate "$$@"""")
    assertEquals(lines(2), "")
  }

  // ── Scenario: running twice produces byte-identical output ──────────────
  // spec: sbt-plugin — Scenario: running twice produces byte-identical output
  test("running ShimGenerator twice produces byte-identical output") {
    val path: String  = "/opt/probatio/bin/probatio"
    val shim1: String = shimFor(path)
    val shim2: String = shimFor(path)
    assertEquals(shim1, shim2)
  }

  // ── Scenario: shim content changes when binary path changes ─────────────
  // spec: sbt-plugin — Scenario: shim content changes when binary path changes
  test("shim content changes when binary path changes") {
    val pathA: String = "/usr/local/bin/probatio"
    val pathB: String = "/opt/probatio/bin/probatio"
    val shimA: String = shimFor(pathA)
    val shimB: String = shimFor(pathB)
    assertNotEquals(shimA, shimB, "shim should differ when binary path changes")
    assert(shimB.contains(pathB), s"shim should reference path B ($pathB): $shimB")
    assert(!shimB.contains(pathA), s"shim should NOT reference path A ($pathA): $shimB")
  }

  // ── Scenario: shim with path containing spaces ──────────────────────────
  // Edge case from spec: path with spaces
  test("shim with path containing spaces is correctly quoted") {
    val path: String = "/opt/my tools/probatio"
    val shim: String = shimFor(path)
    assert(shim.contains(s"""exec "$path" gate "$$@""""), s"exec line should quote path with spaces: $shim")
  }

  // ── Scenario: shim always ends with trailing newline ────────────────────
  test("shim always ends with trailing newline") {
    val path: String = "/usr/local/bin/probatio"
    val shim: String = shimFor(path)
    assert(
      shim.endsWith("\n"),
      s"shim must end with trailing newline: repr=${shim.map(c => if (c == '\n') "\\n" else c.toString).mkString}"
    )
  }

  // ══════════════════════════════════════════════════════════════════════
  // native-gate-delivery (spec 9) — the resolution determines the shim's
  // target, generation is repeatable, and a failed write is
  // could-not-determine
  // spec: native-gate-delivery — Requirement: The resolution result determines the shim's target
  // ══════════════════════════════════════════════════════════════════════

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  // ── Property: shim-target-equals-resolution-and-is-repeatable
  // spec: native-gate-delivery — Property: shim-target-equals-resolution-and-is-repeatable
  //
  // For every non-blocked resolution, the generated shim names the resolved
  // artifact, and generating it twice yields identical content.
  private final case class ShimInput(resolution: ResolutionResult, subcommand: String) {
    def isLauncher: Boolean =
      resolution.path.exists(p => p.endsWith(".jar") || p.contains("launcher"))
    def hasSpacePath: Boolean =
      resolution.path.exists(_.contains(" "))
  }

  /** spec: native-gate-delivery — Generator: genResolutionResult (resolved variants only; blocked is exercised in HookCutoverShimSpec). */
  private def genResolvedResult: Gen[ShimInput] = {
    val nativePath: Gen[String] = Gen.frequency1(
      3 -> Gen.string(Gen.alphaNum, linear(1, 30)).map(p => s"/usr/local/bin/$p"),
      2 -> Gen.element1("/opt/my tools/probatio", "/opt/probatio bïn/probatio", "/pro batio/probatio")
    )
    val launcherPath: Gen[String] = Gen.frequency1(
      3 -> Gen.string(Gen.alphaNum, linear(1, 30)).map(p => s"/opt/probatio/$p.jar"),
      2 -> Gen.element1("/opt/probatio/probatio-assembly.jar", "/opt/my tools/probatio.jar")
    )
    for {
      path       <- Gen.frequency1(1 -> nativePath, 1 -> launcherPath)
      subcommand <- Gen.element1("gate", "spec-lint", "chain-state", "checkpoint")
    } yield ShimInput(ResolutionResult(Some(path), List(s"[info] resolved $path")), subcommand)
  }

  property("shim-target-equals-resolution-and-is-repeatable", coverConfig) {
    for {
      input <- genResolvedResult.forAll
        .cover(30, "native", (i: ShimInput) => !i.isLauncher)
        .cover(30, "launcher", (i: ShimInput) => i.isLauncher)
        .cover(15, "path-with-space", (i: ShimInput) => i.hasSpacePath)
    } yield {
      val a: Either[String, String] = ShimGenerator.generateShim(input.resolution, input.subcommand)
      val b: Either[String, String] = ShimGenerator.generateShim(input.resolution, input.subcommand)
      a match {
        case Right(content) =>
          Result
            .assert(b == a)
            .log(s"generation is not repeatable:\n$a\n---\n$b")
            .and(
              Result
                .assert(input.resolution.path.exists(content.contains))
                .log(s"shim does not name the resolved artifact ${input.resolution.path}: $content")
            )
        case Left(reason) =>
          Result.failure.log(s"a non-blocked resolution must generate a shim, got Left($reason)")
      }
    }
  }

  // ── Scenario: Happy path — a resolved target is written and the write
  //    is repeatable
  // spec: native-gate-delivery — Scenario: Happy path — a resolved target is written and the write is repeatable
  test("a resolved target is written and the write is repeatable") {
    val dir: File          = Files.createTempDirectory("probatio-shim-write").toFile
    val target: File       = new File(dir, "gate")
    val resolution         = ResolutionResult(Some("/usr/local/bin/probatio"), Nil)
    val first: Either[String, File]  = ProbatioPlugin.writeShim(resolution, "gate", target)
    val second: Either[String, File] = ProbatioPlugin.writeShim(resolution, "gate", target)
    (first, second) match {
      case (Right(f1), Right(_)) =>
        val content: String = new String(Files.readAllBytes(f1.toPath), "UTF-8")
        assert(content.contains("/usr/local/bin/probatio"), s"shim must name the resolved artifact: $content")
        assert(
          content == "#!/usr/bin/env bash\nexec \"/usr/local/bin/probatio\" gate \"$@\"\n",
          s"repeatable write must produce identical content: $content"
        )
      case _ =>
        fail(s"a resolved shim write must succeed, got first=$first second=$second")
    }
  }

  // ── Scenario: Error path — an unwritable shim location is
  //    could-not-determine
  // spec: native-gate-delivery — Scenario: Error path — an unwritable shim location is could-not-determine
  test("an unwritable shim location is could-not-determine naming the location") {
    val blocker: File = Files.createTempFile("probatio-blocker", ".tmp").toFile
    val target: File  = new File(blocker, "gate") // parent is a regular file — unwritable
    val result: Either[String, File] =
      ProbatioPlugin.writeShim(ResolutionResult(Some("/usr/local/bin/probatio"), Nil), "gate", target)
    result match {
      case Left(reason) =>
        assert(
          reason.contains("could not determine"),
          s"an unwritable location must report could-not-determine: $reason"
        )
        assert(
          reason.contains("not a directory"),
          s"a file-as-parent location must name the cause: $reason"
        )
        assert(
          reason.contains(target.getPath) || reason.contains(target.getAbsolutePath),
          s"the report must name the location ${target.getPath}: $reason"
        )
      case Right(_) =>
        fail("a shim write to an unwritable location must not succeed")
    }
  }

  // ── Scenario: a missing parent directory is created, and the written
  //    shim is executable
  // spec: native-gate-delivery — Scenario: Happy path — a resolved target is written and the write is repeatable
  test("a shim write creates missing parent directories and marks the shim executable") {
    val dir: File    = Files.createTempDirectory("probatio-shim-nested").toFile
    val target: File = new File(new File(dir, "hooks"), "gate") // parent absent
    val result: Either[String, File] =
      ProbatioPlugin.writeShim(ResolutionResult(Some("/usr/local/bin/probatio"), Nil), "gate", target)
    result match {
      case Right(file) =>
        assert(file.exists, s"shim must be written: $file")
        assert(file.canExecute, s"shim must be executable: $file")
      case Left(reason) =>
        fail(s"a nested shim write must create the parent and succeed, got Left($reason)")
    }
  }

  // ── Blocked-resolution Left reasons carry the recorded lines ───────────
  // spec: native-gate-delivery — Requirement: A blocked resolution reports its reason and writes no shim
  test("a blocked resolution's Left joins its reason lines") {
    ShimGenerator.generateShim(
      ResolutionResult(None, List("[warn] first reason", "second reason")),
      "gate"
    ) match {
      case Left(reason) =>
        assert(reason.contains("first reason"), s"must carry the first line: $reason")
        assert(reason.contains("second reason"), s"must carry the second line: $reason")
        assert(reason.contains("first reason second reason"), s"lines join with a space: $reason")
      case Right(_) => fail("a blocked resolution must not produce a shim")
    }
  }

  test("a resolved path that cannot be safely quoted is reported, not emitted") {
    List("/opt/pro\"batio/probatio", "/opt/pro$batio/probatio", "/opt/probatio\nprobatio").foreach { path =>
      ShimGenerator.generateShim(ResolutionResult(Some(path), Nil), "gate") match {
        case Left(reason) =>
          assert(reason.contains("cannot be safely quoted"), s"unquotable path must be reported: $reason")
        case Right(shim) => fail(s"a corrupt shim must never be emitted for $path: $shim")
      }
    }
  }

  test("a blocked resolution with no recorded reason reports the absence") {
    List(List.empty[String], List("", "")).foreach { lines =>
      ShimGenerator.generateShim(ResolutionResult(None, lines), "gate") match {
        case Left(reason) =>
          assert(
            reason.contains("no target and no reason"),
            s"empty/absent reason lines must produce the fallback message: $reason"
          )
        case Right(_) => fail("a blocked resolution must not produce a shim")
      }
    }
  }

  // ── Helper: bind a resolved path through the resolution result ──────────
  // The shim's target is the artifact the resolution returned — a resolved
  // (non-blocked) result carries `path = Some(...)`.
  private def shimFor(path: String): String =
    ShimGenerator.generateShim(ResolutionResult(Some(path), Nil)) match {
      case Right(content) => content
      case Left(reason)   => fail(reason)
    }
}
