package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._

/**
 * Test oracle for the hook-cutover spec — shim-idempotency property and
 * the shim-with-logic compile-negative.
 *
 * These tests live in the sbt-probatio plugin test sources because
 * `ShimGenerator` is defined in the plugin module, which is not visible
 * to probatio-core tests. The core-side tests (dependency order, oracle
 * gating, SwapOrder compile-negative) are in `HookCutoverSpec.scala`.
 *
 * spec: hook-cutover — Requirement: Each bash hook is replaced by a 3-line exec shim pointing to the probatio binary
 * spec: hook-cutover — Property: shim-idempotency
 * spec: hook-cutover — Compile-Negative: shim with logic beyond shebang + exec + newline
 */
final class HookCutoverShimSpec extends ProbatioPluginSuite {

  // ── Requirement: Each bash hook is replaced by a 3-line exec shim
  // spec: hook-cutover — Scenario: The gate shim is generated idempotently
  test("gate shim is generated idempotently — byte-identical on repeat") {
    val path: String  = "/path/to/probatio"
    val shim1: String = shimFor(path)
    val shim2: String = shimFor(path)
    assertEquals(shim1, shim2, "shim must be byte-identical when regenerated")
    assertEquals(shim1, "#!/usr/bin/env bash\nexec \"/path/to/probatio\" gate \"$@\"\n")
  }

  // ── Requirement: Each bash hook is replaced by a 3-line exec shim
  // spec: hook-cutover — Scenario: A changed binary path changes the shim content
  test("changed binary path changes shim content") {
    val pathA: String = "/old/probatio"
    val pathB: String = "/new/probatio"
    val shimA: String = shimFor(pathA)
    val shimB: String = shimFor(pathB)
    assertNotEquals(shimA, shimB, "shim must differ when binary path changes")
    assert(shimB.contains(pathB), s"shim must reference new path: $shimB")
    assert(!shimB.contains(pathA), s"shim must not reference old path: $shimB")
  }

  // ── Property: shim-idempotency
  // spec: hook-cutover — Property: shim-idempotency
  // For every resolved binary path, generateShim produces byte-identical
  // output on every call. The shim content is a pure function of the
  // binary path.
  property("shim idempotency") {
    for {
      path <- genBinaryPath.forAll
    } yield {
      val shim1: String = shimFor(path)
      val shim2: String = shimFor(path)
      Result
        .assert(shim1 == shim2)
        .log(s"shim1 != shim2 for path=$path:\n$shim1\n---\n$shim2")
        .and(
          Result
            .assert(shim1.startsWith("#!/usr/bin/env bash\n"))
            .log(s"missing shebang: $shim1")
        )
        .and(
          Result
            .assert(shim1.endsWith("\n"))
            .log(s"missing trailing newline: $shim1")
        )
        .and(
          Result
            .assert(shim1.contains("gate \"$@\""))
            .log(s"missing gate subcommand: $shim1")
        )
    }
  }

  // ══════════════════════════════════════════════════════════════════════
  // native-gate-delivery (spec 9) — a blocked resolution writes no shim,
  // a native resolution is what the shim names
  // spec: native-gate-delivery — Requirement: The per-turn tool is delivered as a native artifact where one exists for the platform
  // spec: native-gate-delivery — Requirement: The resolution result determines the shim's target
  // ══════════════════════════════════════════════════════════════════════

  // ── Scenario: Adversarial — the launcher is not accepted for the
  //    per-turn tool on a native platform
  // spec: native-gate-delivery — Scenario: Adversarial — the launcher is not accepted for the per-turn tool on a native platform
  test("the launcher is blocked for the per-turn tool on a native platform — no shim written") {
    val resolution: ResolutionResult =
      InstallResolver.resolveForShim(ResolutionScenario.JarFallback, "gate", platformHasNative = true)
    assert(
      resolution.path.isEmpty,
      s"a launcher resolution for the per-turn tool on a native platform must be blocked: $resolution"
    )
    assert(
      resolution.logLines.exists(_.contains("native")),
      s"the block must name that a native artifact is required: ${resolution.logLines}"
    )
    ShimGenerator.generateShim(resolution, "gate") match {
      case Left(reason) =>
        assert(reason.nonEmpty, "the reported reason must be non-empty")
      case Right(shim) =>
        fail(s"a blocked resolution must write no shim, got: $shim")
    }
  }

  test("a checksum-invalid prebuilt resolves to the launcher and is blocked for the per-turn tool") {
    val resolution: ResolutionResult = InstallResolver.resolveForShim(
      ResolutionScenario.PrebuiltChecksumInvalid,
      "gate",
      platformHasNative = true
    )
    assert(resolution.path.isEmpty, s"a checksum-invalid prebuilt must not resolve a shim target: $resolution")
    ShimGenerator.generateShim(resolution, "gate") match {
      case Left(_)  => ()
      case Right(s) => fail(s"a blocked resolution must write no shim, got: $s")
    }
  }

  // ── Scenario: Adversarial — a blocked resolution writes no shim
  // spec: native-gate-delivery — Scenario: Adversarial — a blocked resolution writes no shim
  test("a blocked resolution writes no shim and the reason is reported") {
    val blocked: ResolutionResult =
      ResolutionResult(None, List("[warn] probatio gate: native artifact required on this platform"))
    ShimGenerator.generateShim(blocked, "gate") match {
      case Left(reason) =>
        assert(reason.nonEmpty, s"a blocked resolution must report a reason: $reason")
      case Right(shim) =>
        fail(s"a blocked resolution must write no shim, got: $shim")
    }
  }

  // ── Scenario: Happy path — the native artifact is resolved and the shim
  //    points at it
  // spec: native-gate-delivery — Scenario: Happy path — the native artifact is resolved and the shim points at it
  test("the native artifact resolves and the shim points at it") {
    val resolution: ResolutionResult = InstallResolver.resolveForShim(
      ResolutionScenario.PrebuiltAvailable,
      "gate",
      platformHasNative = true
    )
    resolution.path match {
      case Some(nativePath) =>
        ShimGenerator.generateShim(resolution, "gate") match {
          case Right(shim) =>
            assert(shim.contains(nativePath), s"shim must point at the native artifact $nativePath: $shim")
          case Left(reason) =>
            fail(s"a native resolution must produce a shim, got Left($reason)")
        }
      case None =>
        fail(s"a native resolution must carry a target: $resolution")
    }
  }

  // ── Scenario: Edge case — a once-per-ring tool may use the launcher
  //    with exactly one warning
  // spec: native-gate-delivery — Scenario: Edge case — a once-per-ring tool may use the launcher with exactly one warning
  test("a once-per-ring tool on a native platform resolves to the launcher with exactly one warning") {
    val resolution: ResolutionResult =
      InstallResolver.resolveForShim(ResolutionScenario.JarFallback, "spec-lint", platformHasNative = true)
    assert(
      resolution.path.isDefined,
      s"a once-per-ring tool may use the launcher on a native platform: $resolution"
    )
    assertEquals(
      resolution.logLines.length,
      1,
      s"exactly one warning line must be emitted, got ${resolution.logLines.length}: ${resolution.logLines}"
    )
    val warning: String = resolution.logLines.headOption.getOrElse("")
    assert(warning.contains("spec-lint"), s"the warning must name the tool: $warning")
    assert(!warning.contains('\n'), s"the warning must be a single line: $warning")
  }

  // ── Compile-Negative: a literal shim target is unconstructible
  // spec: native-gate-delivery — Compile-Negative: ShimGenerator.generateShim called with a literal path rather than a resolution result
  test("compile-negative: generateShim with a literal path is unconstructible") {
    val err: String = compileErrors("ShimGenerator.generateShim(\"/literal/path\", \"gate\")")
    assert(
      err.nonEmpty,
      "generateShim(\"/literal/path\", \"gate\") should not compile — a hardcoded shim target bypasses the resolution rule"
    )
  }

  // ── Compile-Negative: A shim that contains logic beyond shebang + exec + newline
  // spec: hook-cutover — Compile-Negative: shim with logic beyond shebang + exec + newline
  test("compile-negative: ShimGenerator.generateShim takes only path and subcommand — no logic parameter") {
    // The function accepts (path, subcommand) — both are strings that control
    // the exec line content. No parameter exists for injecting arbitrary logic
    // into the shim. A call with a `logic` parameter must fail to compile.
    val err: String = compileErrors("ShimGenerator.generateShim(\"/path\", \"gate\", logic = true)")
    assert(
      err.nonEmpty,
      "ShimGenerator.generateShim must not accept a logic parameter — a shim with logic is a new code path"
    )
  }

  // ══════════════════════════════════════════════════════════════════════
  // spec 8 — ledger-checkpoint-cutover
  // ══════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — every swapped seam has its predecessor on
  //    disk ─────────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Scenario: Happy path — every swapped seam has its predecessor on disk
  // Repo inspection, derived from the tree not a hardcoded list: a file
  // whose content is a forwarding shim (shebang + a single `exec` line)
  // IS a swapped seam; each must keep its `.predecessor.bak` revert
  // target — non-empty, so the revert target is the real implementation,
  // not a placeholder.
  test("every swapped seam in the schema tree has its predecessor on disk") {
    val schemaDir: java.io.File = {
      val fromRepoRoot: java.io.File =
        new java.io.File("openspec/schemas/verified-scala3")
      if (fromRepoRoot.isDirectory) fromRepoRoot
      else new java.io.File("../../openspec/schemas/verified-scala3")
    }
    assert(
      schemaDir.isDirectory,
      s"schema directory must resolve from the test working directory: ${schemaDir.getPath}"
    )
    val shellFiles: List[java.io.File] = listFiles(schemaDir)
      .filter(f => f.getName.endsWith(".sh"))
    assert(shellFiles.nonEmpty, "the schema tree must contain shell seams")
    val swapped: List[java.io.File] = shellFiles.filter(isForwardingShim)
    assert(
      swapped.nonEmpty,
      "at least one seam is already swapped — the revert-target invariant must have subjects"
    )
    swapped.foreach { shim =>
      val predecessor: java.io.File =
        new java.io.File(shim.getPath + ".predecessor.bak")
      assert(
        predecessor.isFile,
        s"swapped seam ${shim.getPath} has no predecessor implementation on disk"
      )
      assert(
        predecessor.length() > 0,
        s"predecessor ${predecessor.getPath} is empty — it is not a usable revert target"
      )
    }
  }

  /** A swapped seam is a forwarding shim: shebang plus one `exec` line. */
  private def isForwardingShim(f: java.io.File): Boolean = {
    val src: scala.io.Source = scala.io.Source.fromFile(f, "UTF-8")
    try
      src.getLines().toList.filter(_.nonEmpty) match {
        case shebang :: exec :: Nil =>
          shebang.startsWith("#!") && exec.startsWith("exec ")
        case _ => false
      }
    finally src.close()
  }

  private def listFiles(dir: java.io.File): List[java.io.File] =
    dir.listFiles.toList.flatMap(f => if (f.isDirectory) listFiles(f) else List(f))

  // ── Generator: genBinaryPath
  // Constructive over absolute file paths with varying depths, special
  // characters (spaces, dots), and trailing slashes.
  // Edge cases: root path, path with spaces, path with dots.
  def genBinaryPath: Gen[String] =
    Gen.frequency(
      1 -> Gen.constant("/"),
      List(
        1 -> Gen.constant("/usr/local/bin/probatio"),
        1 -> Gen.constant("/opt/probatio/bin/probatio"),
        1 -> Gen.constant("/opt/my tools/probatio"),
        1 -> Gen.constant("/path/with.dots/./probatio"),
        1 -> Gen.string(Gen.alphaNum, linear(1, 30)).map(p => s"/usr/local/bin/$p"),
        1 -> Gen.string(Gen.alphaNum, linear(1, 20)).map(p => s"/opt/$p/probatio")
      )
    )

  // ── Helper: bind a resolved path through the resolution result ──────────
  // The shim's target is the artifact the resolution returned — a resolved
  // (non-blocked) result carries `path = Some(...)`.
  private def shimFor(path: String): String =
    ShimGenerator.generateShim(ResolutionResult(Some(path), Nil)) match {
      case Right(content) => content
      case Left(reason)   => fail(reason)
    }
}
