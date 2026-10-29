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
      val a: Either[String, String] =
        ShimGenerator.generateShim(input.resolution, scopeOf(input.resolution), input.subcommand)
      val b: Either[String, String] =
        ShimGenerator.generateShim(input.resolution, scopeOf(input.resolution), input.subcommand)
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
    val dir: File    = Files.createTempDirectory("probatio-shim-write").toFile
    val target: File = new File(dir, "gate")
    val resolution   = ResolutionResult(Some("/usr/local/bin/probatio"), Nil)
    val first: Either[String, File] =
      ProbatioPlugin.writeShim(resolution, ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"), "gate", target)
    val second: Either[String, File] =
      ProbatioPlugin.writeShim(resolution, ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"), "gate", target)
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
      ProbatioPlugin.writeShim(
        ResolutionResult(Some("/usr/local/bin/probatio"), Nil),
        ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"),
        "gate",
        target
      )
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
      ProbatioPlugin.writeShim(
        ResolutionResult(Some("/usr/local/bin/probatio"), Nil),
        ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"),
        "gate",
        target
      )
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
      ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"),
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
      ShimGenerator.generateShim(
        ResolutionResult(Some(path), Nil),
        ShimTargetScope.AbsoluteInstall(path),
        "gate"
      ) match {
        case Left(reason) =>
          assert(reason.contains("cannot be safely quoted"), s"unquotable path must be reported: $reason")
        case Right(shim) => fail(s"a corrupt shim must never be emitted for $path: $shim")
      }
    }
  }

  test("a blocked resolution with no recorded reason reports the absence") {
    List(List.empty[String], List("", "")).foreach { lines =>
      ShimGenerator.generateShim(
        ResolutionResult(None, lines),
        ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"),
        "gate"
      ) match {
        case Left(reason) =>
          assert(
            reason.contains("no target and no reason"),
            s"empty/absent reason lines must produce the fallback message: $reason"
          )
        case Right(_) => fail("a blocked resolution must not produce a shim")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // workflow-delivery-hygiene (spec 9) — the generated script states the
  // scope it resolved, and unsafe targets are refused under either scope
  // spec: workflow-delivery-hygiene — Requirement: The generated script states which scope it resolved
  // spec: workflow-delivery-hygiene — Requirement: An in-repository forwarding script resolves its target relative to itself
  // ══════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — a repository-relative request produces a
  //    relative target
  // spec: workflow-delivery-hygiene — Scenario: Happy path — a repository-relative request produces a relative target
  test("a repository-relative request produces a target relative to the script's own location") {
    val rel: ShimTargetScope.RelPath =
      ShimTargetScope.RelPath.from("../bin/probatio") match {
        case Right(r)     => r
        case Left(reason) => fail(s"a relative path must construct: $reason")
      }
    // Under RepositoryRelative the resolution still reports the artifact's
    // location in THIS checkout — the generated script must not embed it.
    val resolution: ResolutionResult = ResolutionResult(
      Some("/home/dev/checkouts/proj/openspec/schemas/verified-scala3/bin/probatio"),
      Nil
    )
    ShimGenerator.generateShim(resolution, ShimTargetScope.RepositoryRelative(rel), "spec-lint") match {
      case Right(shim) =>
        assert(shim.startsWith("#!/usr/bin/env bash\n"), s"missing shebang: $shim")
        assert(
          shim.contains("SCRIPT_DIR") || shim.contains("BASH_SOURCE"),
          s"a repository-relative shim must resolve its own location at runtime: $shim"
        )
        assert(
          shim.contains("../bin/probatio"),
          s"the exec target must carry the declared relative path: $shim"
        )
        assert(
          shim.contains("spec-lint \"$@\""),
          s"the subcommand and forwarded arguments must survive: $shim"
        )
        assert(
          !shim.contains("/home/dev/checkouts"),
          s"the resolution's absolute location must not be embedded: $shim"
        )
      case Left(reason) =>
        fail(s"a repository-relative request must generate, got Left($reason)")
    }
  }

  // ── Scenario: Error path — a target that cannot be safely quoted is
  //    refused
  // spec: workflow-delivery-hygiene — Scenario: Error path — a target that cannot be safely quoted is refused
  test("a target that cannot be safely quoted is refused naming the character") {
    List(
      ("/opt/pro\"batio/probatio", '"'),
      ("/opt/pro$batio/probatio", '$'),
      ("/opt/pro`batio/probatio", '`'),
      ("/opt/pro\\batio/probatio", '\\')
    ).foreach { case (path: String, ch: Char) =>
      ShimGenerator.generateShim(
        ResolutionResult(Some(path), Nil),
        ShimTargetScope.AbsoluteInstall(path),
        "gate"
      ) match {
        case Left(reason) =>
          assert(
            reason.contains(ch.toString),
            s"the refusal must name the unsafe character '$ch': $reason"
          )
        case Right(shim) =>
          fail(s"a corrupt shim must never be emitted for $path: $shim")
      }
    }
    // The same naming obligation holds under the repository-relative
    // scope — the refusal there must name the unsafe character too.
    List(
      ("pro\"batio/probatio", '"'),
      ("pro$batio/probatio", '$'),
      ("pro`batio/probatio", '`'),
      ("pro\\batio/probatio", '\\')
    ).foreach { case (path: String, ch: Char) =>
      val rel: ShimTargetScope.RelPath = ShimTargetScope.RelPath.from(path) match {
        case Right(r)     => r
        case Left(reason) => fail(s"a relative path with an unsafe char must still construct as a RelPath: $reason")
      }
      ShimGenerator.generateShim(
        ResolutionResult(Some("/resolved/here/bin/probatio"), Nil),
        ShimTargetScope.RepositoryRelative(rel),
        "gate"
      ) match {
        case Left(reason) =>
          assert(
            reason.contains(ch.toString),
            s"the refusal must name the unsafe character '$ch': $reason"
          )
        case Right(shim) =>
          fail(s"a corrupt shim must never be emitted for $path: $shim")
      }
    }
    // Control characters are refused as unsafe even though their printed
    // form in the reason is described rather than literal.
    List("/opt/probatio\nprobatio", "/opt/probatio\rprobatio").foreach { path =>
      ShimGenerator.generateShim(
        ResolutionResult(Some(path), Nil),
        ShimTargetScope.AbsoluteInstall(path),
        "gate"
      ) match {
        case Left(reason) =>
          assert(reason.nonEmpty, s"a control-character refusal must still report a reason: $reason")
        case Right(shim) =>
          fail(s"a corrupt shim must never be emitted for $path: $shim")
      }
    }
  }

  // ── Step-1 contract pins — the scope supplies the target; the
  //    resolution supplies the blocked/present verdict
  // spec: workflow-delivery-hygiene — typed contract: an install scope and a
  // resolution naming different artifacts are refused, never silently resolved
  test("an install scope disagreeing with the resolution is refused") {
    ShimGenerator.generateShim(
      ResolutionResult(Some("/usr/local/bin/probatio"), Nil),
      ShimTargetScope.AbsoluteInstall("/opt/other/probatio"),
      "gate"
    ) match {
      case Left(reason) =>
        assert(reason.nonEmpty, s"a scope/resolution disagreement must report a reason: $reason")
      case Right(shim) =>
        fail(s"two different targets must never produce one shim: $shim")
    }
  }

  test("an install scope carrying a non-absolute path is refused") {
    ShimGenerator.generateShim(
      ResolutionResult(Some("bin/probatio"), Nil),
      ShimTargetScope.AbsoluteInstall("bin/probatio"),
      "gate"
    ) match {
      case Left(reason) =>
        assert(reason.nonEmpty, s"a non-absolute install target must report a reason: $reason")
      case Right(shim) =>
        fail(s"an install shim must exec an absolute target: $shim")
    }
  }

  // ── Property: generation-refuses-unquotable-targets
  // spec: workflow-delivery-hygiene — Property: generation-refuses-unquotable-targets
  //
  // Generator: genTargetPath — constructive over the union of safe paths and
  // paths seeded with each unsafe character independently, in both scopes,
  // so both branches are covered by construction rather than by filtering.
  // Edge cases: the empty path (refused at RelPath construction — a scope
  // that cannot be constructed cannot produce a script; refusal at scope
  // construction IS refusal to generate), a path of only an unsafe
  // character, and an unsafe character at the boundary (lead for relative
  // paths, tail/mid for absolute ones so the absolute anchor survives).
  final private case class TargetCase(
    path: String,
    repoScope: Boolean,
    expectRefusal: Boolean,
    label: String
  )

  // The character set is the same one the generator guards: `"` and `\`
  // alter quoting; `$`, `` ` `` and the line breaks invite expansion or
  // injection.
  private def genUnsafeChar: Gen[Char] =
    Gen.element1('"', '\\', '$', '`', '\n', '\r')

  private def insertAt(path: String, ch: Char, pos: String): String =
    pos match {
      case "lead" => ch.toString + path
      case "tail" => path + ch.toString
      case _ =>
        val mid: Int = path.length / 2
        path.substring(0, mid) + ch.toString + path.substring(mid)
    }

  private def genTargetPath: Gen[TargetCase] = {
    val safeAbsolute: Gen[String] =
      Gen.string(Gen.alphaNum, linear(1, 20)).map(s => s"/opt/probatio/$s")
    val safeRelative: Gen[String] =
      Gen.string(Gen.alphaNum, linear(1, 20)).map(s => s"bin/$s")
    val unsafeAbsolute: Gen[String] =
      for {
        ch   <- genUnsafeChar
        pos  <- Gen.element1("mid", "tail")
        base <- safeAbsolute
      } yield insertAt(base, ch, pos)
    val unsafeRelative: Gen[String] =
      for {
        ch   <- genUnsafeChar
        pos  <- Gen.element1("lead", "mid", "tail")
        base <- safeRelative
      } yield insertAt(base, ch, pos)
    Gen.frequency1(
      3 -> safeAbsolute.map(p => TargetCase(p, repoScope = false, expectRefusal = false, label = "safe-absolute")),
      4 -> unsafeAbsolute.map(p => TargetCase(p, repoScope = false, expectRefusal = true, label = "unsafe-absolute")),
      3 -> safeRelative.map(p => TargetCase(p, repoScope = true, expectRefusal = false, label = "safe-relative")),
      4 -> unsafeRelative.map(p => TargetCase(p, repoScope = true, expectRefusal = true, label = "unsafe-relative")),
      2 -> Gen.constant(TargetCase("", repoScope = true, expectRefusal = true, label = "edge-empty")),
      2 -> genUnsafeChar
        .map(ch => TargetCase(ch.toString, repoScope = true, expectRefusal = true, label = "edge-only-unsafe"))
    )
  }

  property("generation refuses unquotable targets", coverConfig) {
    for {
      tc <- genTargetPath.forAll
        .cover(10, "safe-absolute", (t: TargetCase) => t.label == "safe-absolute")
        .cover(10, "unsafe-absolute", (t: TargetCase) => t.label == "unsafe-absolute")
        .cover(10, "safe-relative", (t: TargetCase) => t.label == "safe-relative")
        .cover(10, "unsafe-relative", (t: TargetCase) => t.label == "unsafe-relative")
        .cover(3, "edge", (t: TargetCase) => t.label.startsWith("edge"))
    } yield {
      val out: Either[String, String] =
        if (tc.repoScope)
          ShimTargetScope.RelPath.from(tc.path) match {
            case Left(reason) => Left(reason)
            case Right(rel) =>
              ShimGenerator.generateShim(
                // the repository artifact's absolute location — legitimately
                // different from the script-relative reference it emits
                ResolutionResult(
                  Some("/abs/repo/openspec/schemas/verified-scala3/bin/probatio"),
                  Nil
                ),
                ShimTargetScope.RepositoryRelative(rel),
                "gate"
              )
          }
        else
          ShimGenerator.generateShim(
            ResolutionResult(Some(tc.path), Nil),
            ShimTargetScope.AbsoluteInstall(tc.path),
            "gate"
          )
      Result
        .assert(out.isLeft == tc.expectRefusal)
        .log(s"${tc.label}: path=${tc.path} expectedRefusal=${tc.expectRefusal} got=$out")
    }
  }

  // ── Helper: bind a resolved path through the resolution result ──────────
  // The shim's target is the artifact the resolution returned — a resolved
  // (non-blocked) result carries `path = Some(...)`.
  private def shimFor(path: String): String =
    ShimGenerator.generateShim(ResolutionResult(Some(path), Nil), ShimTargetScope.AbsoluteInstall(path)) match {
      case Right(content) => content
      case Left(reason)   => fail(reason)
    }

  /** The install scope naming the resolution's own path (or a stand-in when blocked). */
  private def scopeOf(resolution: ResolutionResult): ShimTargetScope =
    ShimTargetScope.AbsoluteInstall(resolution.path.getOrElse("/usr/local/bin/probatio"))
}
