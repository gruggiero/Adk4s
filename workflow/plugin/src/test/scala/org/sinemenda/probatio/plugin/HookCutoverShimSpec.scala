package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

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
    ShimGenerator.generateShim(resolution, ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"), "gate") match {
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
    ShimGenerator.generateShim(resolution, ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"), "gate") match {
      case Left(_)  => ()
      case Right(s) => fail(s"a blocked resolution must write no shim, got: $s")
    }
  }

  // ── Scenario: Adversarial — a blocked resolution writes no shim
  // spec: native-gate-delivery — Scenario: Adversarial — a blocked resolution writes no shim
  test("a blocked resolution writes no shim and the reason is reported") {
    val blocked: ResolutionResult =
      ResolutionResult(None, List("[warn] probatio gate: native artifact required on this platform"))
    ShimGenerator.generateShim(blocked, ShimTargetScope.AbsoluteInstall("/usr/local/bin/probatio"), "gate") match {
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
        ShimGenerator.generateShim(resolution, ShimTargetScope.AbsoluteInstall(nativePath), "gate") match {
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

  /**
   * A swapped seam is a forwarding shim: a shebang, optional `VAR=...`
   * self-location lines (the repository-relative form), and a single
   * trailing `exec` line.
   */
  private def isForwardingShim(f: java.io.File): Boolean = {
    val src: scala.io.Source = scala.io.Source.fromFile(f, "UTF-8")
    try
      src.getLines().toList.filter(_.nonEmpty) match {
        case shebang :: rest if shebang.startsWith("#!") && rest.nonEmpty =>
          rest.lastOption.exists(_.startsWith("exec ")) &&
          rest.dropRight(1).forall(l => l.matches("[A-Za-z_][A-Za-z0-9_]*=.*"))
        case _ => false
      }
    finally src.close()
  }

  private def listFiles(dir: java.io.File): List[java.io.File] =
    dir.listFiles.toList.flatMap(f => if (f.isDirectory) listFiles(f) else List(f))

  // ══════════════════════════════════════════════════════════════════════
  // workflow-delivery-hygiene (spec 9) — in-repository forwarding scripts
  // resolve their target relative to themselves, so a clone, worktree or CI
  // runner reaches the tool inside its own copy — never another checkout's.
  // spec: workflow-delivery-hygiene — Requirement: An in-repository forwarding script resolves its target relative to itself
  // ══════════════════════════════════════════════════════════════════════

  // The probe stands in for the built tool at exactly the location the
  // committed launcher execs (`bin/probatio` prefers the native image). It
  // reports its own directory and exits with a distinctive status, so a test
  // can tell WHICH copy's tool was reached and that the tool's own status
  // propagated through both execs.
  private val probeMarker: String  = "PROBE_REACHED"
  private val probeExitCode: Int   = 42
  private val probeRelPath: String = "workflow/cli/target/native-image/probatio"

  private def repoRootDir: java.io.File = {
    val fromRepoRoot: java.io.File =
      new java.io.File("openspec/schemas/verified-scala3")
    if (fromRepoRoot.isDirectory) new java.io.File(".").getCanonicalFile
    else new java.io.File("../..").getCanonicalFile
  }

  private def readFileUtf8(f: java.io.File): String = {
    val src: scala.io.Source = scala.io.Source.fromFile(f, "UTF-8")
    try src.mkString
    finally src.close()
  }

  private def runProcess(args: List[String], cwd: java.io.File): (Int, String) = {
    // spec: hermetic-test-processes — all test processes go through the
    // shared helper, so the child sees only the fixed base plus declared
    // variables. The previous spawned child process inherited the
    // invoking shell's environment wholesale.
    val r: HermeticResult =
      HermeticEnv.capture(args, HermeticEnv.empty, cwd = Some(cwd))
    (r.exitCode, List(r.out, r.err).filter(_.nonEmpty).mkString("\n"))
  }

  // Every .sh file in the copy whose body forwards to bin/probatio —
  // discovered at test time, never listed, so a newly swapped seam is
  // covered automatically.
  private def forwardingShimsIn(root: java.io.File): List[java.io.File] = {
    val schemaDir: java.io.File =
      new java.io.File(root, "openspec/schemas/verified-scala3")
    listFiles(schemaDir)
      .filter(_.getName.endsWith(".sh"))
      .filter { f =>
        readFileUtf8(f).linesIterator.exists(line => line.startsWith("exec ") && line.contains("bin/probatio"))
      }
  }

  private def writeProbeTool(copyRoot: java.io.File): java.io.File = {
    val probe: java.io.File = new java.io.File(copyRoot, probeRelPath)
    val _dirs: Boolean      = probe.getParentFile.mkdirs()
    val _w: java.nio.file.Path = java.nio.file.Files.write(
      probe.toPath,
      ("#!/usr/bin/env bash\n" +
        "printf '" + probeMarker + " %s\\n' \"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n" +
        "exit " + probeExitCode.toString + "\n").getBytes("UTF-8")
    )
    val _x: Boolean = probe.setExecutable(true)
    probe
  }

  // Copy the tracked file set with working-tree content into dest —
  // the same files a fresh clone or linked worktree carries once this
  // change is committed. `git archive HEAD`/`git worktree add` see only
  // the committed tree, but the oracle must exercise the implementation
  // BEFORE the checkpoint commit; the tracked set is the clone's content.
  private def copyTrackedWorkingTree(dest: java.io.File): Unit = {
    val _d: Boolean = dest.mkdirs()
    val (code, out): (Int, String) = runProcess(
      List("git", "-C", repoRootDir.getAbsolutePath, "ls-files", "-z"),
      repoRootDir
    )
    assertEquals(code, 0, s"git ls-files must succeed: $out")
    out.split("\u0000").toList.filter(_.nonEmpty).foreach { rel =>
      val srcF: java.io.File = new java.io.File(repoRootDir, rel)
      val dstF: java.io.File = new java.io.File(dest, rel)
      val _pd: Boolean       = dstF.getParentFile.mkdirs()
      val _c: java.nio.file.Path = java.nio.file.Files.copy(
        srcF.toPath,
        dstF.toPath,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      if (srcF.canExecute) { val _x: Boolean = dstF.setExecutable(true); () }
      ()
    }
    assert(
      new java.io.File(dest, "openspec/schemas/verified-scala3/bin/probatio").isFile,
      s"the materialised copy must carry the launcher: ${dest.getAbsolutePath}"
    )
  }

  private def materialiseCopy(dest: java.io.File): Unit =
    copyTrackedWorkingTree(dest)

  // The placement property's domain is the placement SHAPE, not the
  // repository's contents — a copy needs only the schema subtree (the
  // forwarding scripts and the launcher) plus the probe slot the launcher
  // execs. Materialising the whole tree per generated case would spend
  // the test budget on copying files nothing resolves.
  private def materialiseSchemaCopy(dest: java.io.File): Unit = {
    val _d: Boolean = dest.mkdirs()
    val (code, out): (Int, String) = runProcess(
      List("git", "-C", repoRootDir.getAbsolutePath, "ls-files", "-z", "--", "openspec/schemas/verified-scala3"),
      repoRootDir
    )
    assertEquals(code, 0, s"git ls-files must succeed: $out")
    out.split("\u0000").toList.filter(_.nonEmpty).foreach { rel =>
      val srcF: java.io.File = new java.io.File(repoRootDir, rel)
      val dstF: java.io.File = new java.io.File(dest, rel)
      val _pd: Boolean       = dstF.getParentFile.mkdirs()
      val _c: java.nio.file.Path = java.nio.file.Files.copy(
        srcF.toPath,
        dstF.toPath,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      if (srcF.canExecute) { val _x: Boolean = dstF.setExecutable(true); () }
      ()
    }
    assert(
      new java.io.File(dest, "openspec/schemas/verified-scala3/bin/probatio").isFile,
      s"the materialised copy must carry the launcher: ${dest.getAbsolutePath}"
    )
  }

  // A real linked worktree (git-managed), then overlaid with working-tree
  // content for the same reason as materialiseCopy.
  private def materialiseWorktree(dest: java.io.File): Unit = {
    val (code, out): (Int, String) = runProcess(
      List("git", "-C", repoRootDir.getAbsolutePath, "worktree", "add", "--detach", dest.getAbsolutePath, "HEAD"),
      repoRootDir
    )
    assertEquals(code, 0, s"git worktree add must succeed: $out")
    copyTrackedWorkingTree(dest)
  }

  private def removeWorktree(dest: java.io.File): Unit = {
    val _ignored: (Int, String) = runProcess(
      List("git", "-C", repoRootDir.getAbsolutePath, "worktree", "remove", "--force", dest.getAbsolutePath),
      repoRootDir
    )
    ()
  }

  private def reachedWithin(output: String, copyRoot: String): Boolean =
    output.linesIterator.exists { line =>
      line.startsWith(probeMarker) &&
      line.substring(probeMarker.length).trim.startsWith(copyRoot)
    }

  private def assertCopyReachesOwnTool(copy: java.io.File): Unit = {
    val _probe: java.io.File      = writeProbeTool(copy)
    val shims: List[java.io.File] = forwardingShimsIn(copy)
    assert(shims.nonEmpty, s"no forwarding scripts discovered in ${copy.getAbsolutePath}")
    // Invoke from a directory unrelated to any copy — a forwarding script
    // that depended on the caller's cwd would resolve the wrong tree.
    val neutralCwd: java.io.File =
      java.nio.file.Files.createTempDirectory("probatio-neutral-cwd").toFile
    val copyRoot: String = copy.getCanonicalPath
    shims.foreach { shim =>
      val (code, out): (Int, String) =
        runProcess(List("bash", shim.getAbsolutePath), neutralCwd)
      assertEquals(
        code,
        probeExitCode,
        s"${shim.getName} must terminate with the tool's own status, got $code:\n$out"
      )
      assert(
        reachedWithin(out, copyRoot),
        s"${shim.getName} must reach the tool within its own copy $copyRoot, got:\n$out"
      )
    }
  }

  // ── Scenario: Happy path — a script invoked from a fresh clone reaches
  //    the tool
  // spec: workflow-delivery-hygiene — Scenario: Happy path — a script invoked from a fresh clone reaches the tool
  test("a forwarding script invoked from a fresh clone reaches the tool within that clone") {
    val parent: java.io.File =
      java.nio.file.Files.createTempDirectory("probatio-fresh-clone").toFile
    val clone: java.io.File = new java.io.File(parent, "clone")
    val (cloneCode, cloneOut): (Int, String) = runProcess(
      List("git", "clone", "--quiet", "--no-local", repoRootDir.getAbsolutePath, clone.getAbsolutePath),
      parent
    )
    assertEquals(cloneCode, 0, s"git clone must succeed: $cloneOut")
    // A clone carries HEAD only; overlay the working tree so the
    // oracle exercises the implementation ahead of the checkpoint commit.
    copyTrackedWorkingTree(clone)
    assertCopyReachesOwnTool(clone)
  }

  // ── Scenario: Happy path — a script invoked from a linked worktree
  //    reaches the tool
  // spec: workflow-delivery-hygiene — Scenario: Happy path — a script invoked from a linked worktree reaches the tool
  test("a forwarding script invoked from a linked worktree reaches the tool within that worktree") {
    val parent: java.io.File =
      java.nio.file.Files.createTempDirectory("probatio-linked-worktree").toFile
    val worktree: java.io.File = new java.io.File(parent, "linked")
    materialiseWorktree(worktree)
    try assertCopyReachesOwnTool(worktree)
    finally removeWorktree(worktree)
  }

  // ── Property: shim-resolves-from-any-location
  // spec: workflow-delivery-hygiene — Property: shim-resolves-from-any-location
  //
  // Generator: genRepositoryPlacement — constructive over the spec's closed
  // set of copy shapes: a sibling directory, a nested directory, a path
  // containing spaces, a path containing a non-ASCII character, a linked
  // worktree, and the two-copies edge case (a script reaching the wrong
  // copy is the failure that case exists to catch). The repository copy is
  // materialised at each; every discovered forwarding script in the copy is
  // invoked.
  sealed abstract private class Placement extends Product with Serializable
  private case object SiblingCopy         extends Placement
  private case object NestedCopy          extends Placement
  private case object SpacesCopy          extends Placement
  private case object NonAsciiCopy        extends Placement
  private case object LinkedWorktree      extends Placement
  private case object TwoCopies           extends Placement

  private def genRepositoryPlacement: Gen[Placement] =
    Gen.element1(SiblingCopy, NestedCopy, SpacesCopy, NonAsciiCopy, LinkedWorktree, TwoCopies)

  // Returns every materialised copy; the head is the copy whose scripts
  // are invoked (under TwoCopies the second copy exists only to be
  // reached-by-mistake).
  private def materialisePlacement(placement: Placement, base: java.io.File): List[java.io.File] = {
    val names: List[String] = placement match {
      case SiblingCopy    => List("sibling-copy")
      case NestedCopy     => List("nested/deep/copy")
      case SpacesCopy     => List("copy with spaces")
      case NonAsciiCopy   => List("cøpy-prøbatio")
      case LinkedWorktree => List("linked-worktree")
      case TwoCopies      => List("copy-a", "copy-b")
    }
    val copies: List[java.io.File] = names.map(n => new java.io.File(base, n))
    copies.foreach { c =>
      placement match {
        case LinkedWorktree => materialiseWorktree(c)
        case _              => materialiseSchemaCopy(c)
      }
    }
    copies
  }

  // Every declared placement is exercised deterministically — the domain
  // is a closed set of six, so coverage is enumerated, not sampled: the
  // property below samples for robustness, this test is the guarantee
  // (a sampled property cannot promise all-class coverage — a 36-run draw
  // missed `sibling` once in practice).
  // spec: workflow-delivery-hygiene — Property: shim-resolves-from-any-location
  test("every declared placement resolves within its own copy") {
    val allPlacements: List[Placement] =
      List(SiblingCopy, NestedCopy, SpacesCopy, NonAsciiCopy, LinkedWorktree, TwoCopies)
    allPlacements.foreach { placement =>
      val base: java.io.File =
        java.nio.file.Files.createTempDirectory("probatio-placement").toFile
      val copies: List[java.io.File] = materialisePlacement(placement, base)
      try {
        copies.foreach(c => { val _ = writeProbeTool(c) })
        val invoked: java.io.File =
          copies.headOption.getOrElse(fail("materialisePlacement produced no copies"))
        val shims: List[java.io.File] = forwardingShimsIn(invoked)
        assert(
          shims.nonEmpty,
          s"no forwarding scripts discovered in ${invoked.getAbsolutePath}"
        )
        val invokedRoot: String = invoked.getCanonicalPath
        shims.foreach { shim =>
          val (code, out): (Int, String) =
            runProcess(List("bash", shim.getAbsolutePath), base)
          assertEquals(
            code,
            probeExitCode,
            s"$placement/${shim.getName}: expected the tool's own status $probeExitCode, got $code\n$out"
          )
          assert(
            reachedWithin(out, invokedRoot),
            s"$placement/${shim.getName}: must reach the tool within its own copy $invokedRoot, got:\n$out"
          )
        }
      } finally if (placement == LinkedWorktree) copies.foreach(c => removeWorktree(c))
    }
  }

  // 36 runs over a closed 6-shape domain — each materialisation is a real
  // filesystem copy, so the run count is bounded. The cover labels are
  // report-only (threshold 0): coverage is guaranteed by the deterministic
  // test above; a random sample cannot promise all-class coverage and a
  // nonzero threshold made the property flaky (0% sibling observed once).
  private def placementConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(36))

  property("a shim resolves from any location", placementConfig) {
    for {
      placement <- genRepositoryPlacement.forAll
        .cover(0, "sibling", (p: Placement) => p == SiblingCopy)
        .cover(0, "nested", (p: Placement) => p == NestedCopy)
        .cover(0, "spaces", (p: Placement) => p == SpacesCopy)
        .cover(0, "non-ascii", (p: Placement) => p == NonAsciiCopy)
        .cover(0, "worktree", (p: Placement) => p == LinkedWorktree)
        .cover(0, "two-copies", (p: Placement) => p == TwoCopies)
    } yield {
      val base: java.io.File =
        java.nio.file.Files.createTempDirectory("probatio-placement").toFile
      val copies: List[java.io.File] = materialisePlacement(placement, base)
      copies.foreach(c => { val _ = writeProbeTool(c) })
      val invoked: java.io.File =
        copies.headOption.getOrElse(fail("materialisePlacement produced no copies"))
      val result: Result =
        try {
          val shims: List[java.io.File] = forwardingShimsIn(invoked)
          if (shims.isEmpty)
            Result.failure.log(s"no forwarding scripts discovered in ${invoked.getAbsolutePath}")
          else {
            val invokedRoot: String = invoked.getCanonicalPath
            shims
              .map { shim =>
                val (code, out): (Int, String) =
                  runProcess(List("bash", shim.getAbsolutePath), base)
                Result
                  .assert(code == probeExitCode)
                  .log(s"${shim.getName}: expected the tool's own status $probeExitCode, got $code\n$out")
                  .and(
                    Result
                      .assert(reachedWithin(out, invokedRoot))
                      .log(s"${shim.getName}: must reach the tool within its own copy $invokedRoot, got:\n$out")
                  )
              }
              .reduceOption((a: Result, b: Result) => a.and(b))
              .getOrElse(Result.failure.log("no forwarding scripts discovered"))
          }
        } finally if (placement == LinkedWorktree) copies.foreach(c => removeWorktree(c))
      result
    }
  }

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
    ShimGenerator.generateShim(ResolutionResult(Some(path), Nil), ShimTargetScope.AbsoluteInstall(path)) match {
      case Right(content) => content
      case Left(reason)   => fail(reason)
    }
}
