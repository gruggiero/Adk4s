package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._

/**
 * Tests for InstallResolver — the pure function that models the probatioInstall
 * resolution order: prebuilt binary → assembly JAR → (opt-in) local native-image.
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image
 * spec: port-scanner-to-probatio/sbt-plugin — Property: install-resolution-order
 */
final class InstallResolverSpec extends ProbatioPluginSuite {

  // ── Property: install-resolution-order ──────────────────────────────────
  // spec: sbt-plugin — Property: install-resolution-order
  property("install-resolution-order: each fallback emits exactly one distinct log line") {
    for {
      scenario <- Gen
        .element1(
          ResolutionScenario.PrebuiltAvailable,
          ResolutionScenario.PrebuiltChecksumInvalid,
          ResolutionScenario.JarFallback,
          ResolutionScenario.NativeImage
        )
        .forAll
    } yield {
      val result: ResolutionResult = InstallResolver.resolve(scenario)
      val firstLine: String        = result.logLines.headOption.getOrElse("")
      Result
        .assert(result.logLines.nonEmpty)
        .log("no fallback should be silent — logLines must be nonEmpty")
        .and(
          Result
            .assert(result.logLines.length == 1)
            .log(s"expected exactly 1 log line, got ${result.logLines.length}: ${result.logLines}")
        )
        .and(
          scenario match {
            case ResolutionScenario.PrebuiltAvailable | ResolutionScenario.NativeImage =>
              Result
                .assert(firstLine.startsWith("[info]"))
                .log(s"prebuilt/native-image should emit [info], got: $firstLine")
            case ResolutionScenario.PrebuiltChecksumInvalid | ResolutionScenario.JarFallback =>
              Result
                .assert(firstLine.startsWith("[warn]"))
                .log(s"JAR fallback should emit [warn], got: $firstLine")
          }
        )
    }
  }

  // ── Scenario: prebuilt binary resolves successfully ─────────────────────
  // spec: sbt-plugin — Scenario: prebuilt binary resolves successfully
  test("prebuilt binary available → [info] log line, binary path") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.PrebuiltAvailable)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[info]"), s"should be [info]: $line")
    assert(line.contains("prebuilt"), s"should mention 'prebuilt': $line")
    assert(result.path.nonEmpty, "should have a resolved path")
    assert(
      result.path.exists(_.endsWith(".probatio/bin/probatio")),
      s"the resolved path must name the cached binary the install produces: ${result.path}"
    )
  }

  // ── Scenario: assembly JAR fallback emits a distinct log line ───────────
  // spec: sbt-plugin — Scenario: assembly JAR fallback emits a distinct log line
  test("prebuilt unavailable → JAR fallback with [warn] log line") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.JarFallback)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[warn]"), s"should be [warn]: $line")
    assert(line.contains("JAR"), s"should mention 'JAR': $line")
    assert(
      result.path.exists(_.endsWith(".probatio/bin/probatio-jar-launcher.sh")),
      s"the fallback path must name the launcher the install writes: ${result.path}"
    )
  }

  // ── Scenario: checksum invalid → JAR fallback with [warn] ───────────────
  test("prebuilt checksum invalid → JAR fallback with [warn] log line") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.PrebuiltChecksumInvalid)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[warn]"), s"should be [warn]: $line")
    assert(line.contains("checksum"), s"the checksum-invalid fallback must name its cause: $line")
  }

  // ── Scenario: local native-image is opt-in only ─────────────────────────
  // spec: sbt-plugin — Scenario: local native-image is opt-in only
  test("native-image scenario emits [info] and has a path") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.NativeImage)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[info]"), s"should be [info]: $line")
    assert(result.path.nonEmpty, "should have a resolved path")
  }

  // ── Scenario: no fallback is silent (adversarial) ───────────────────────
  // spec: sbt-plugin — Scenario: no fallback is silent (adversarial)
  test("no resolution scenario produces zero log lines") {
    val scenarios: List[ResolutionScenario] = ResolutionScenario.values.toList
    for (scenario <- scenarios) {
      val result: ResolutionResult = InstallResolver.resolve(scenario)
      assert(result.logLines.nonEmpty, s"scenario $scenario should not be silent — logLines is empty")
    }
  }

  // ── Scenario: prebuilt [info] and JAR [warn] are distinguishable ────────
  test("prebuilt [info] and JAR [warn] log lines are distinguishable") {
    val prebuilt: String = InstallResolver
      .resolve(ResolutionScenario.PrebuiltAvailable)
      .logLines
      .headOption
      .getOrElse(fail("prebuilt logLines empty"))
    val jar: String =
      InstallResolver.resolve(ResolutionScenario.JarFallback).logLines.headOption.getOrElse(fail("jar logLines empty"))
    assertNotEquals(prebuilt, jar, "prebuilt and JAR log lines must be distinguishable")
    assert(prebuilt.startsWith("[info]"), s"prebuilt should be [info]: $prebuilt")
    assert(jar.startsWith("[warn]"), s"JAR should be [warn]: $jar")
  }

  // ── Scenario: ResolutionScenario is a sealed enum with exactly 4 cases ──
  test("ResolutionScenario has exactly 4 cases") {
    assertEquals(ResolutionScenario.values.length, 4, s"expected 4 cases, got ${ResolutionScenario.values.length}")
  }

  // ══════════════════════════════════════════════════════════════════════
  // native-gate-delivery (spec 9) — scenario detection + platform fact
  // spec: native-gate-delivery — Requirement: The plugin never assumes an existing binary is checksum-valid
  // ══════════════════════════════════════════════════════════════════════

  private def sha256Hex(content: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(content)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def withTempBinary(content: String)(f: java.io.File => Unit): Unit = {
    val dir: java.io.File  = java.nio.file.Files.createTempDirectory("probatio-scenario").toFile
    val binary: java.io.File = new java.io.File(dir, "probatio")
    val _w: java.nio.file.Path = java.nio.file.Files.write(binary.toPath, content.getBytes("UTF-8"))
    try f(binary)
    finally {
      val _deleted: Boolean = binary.delete() && dir.delete()
      ()
    }
  }

  test("detectScenario: present binary with matching recorded digest → PrebuiltAvailable") {
    withTempBinary("binary-bytes") { binary =>
      val digest: String = sha256Hex("binary-bytes".getBytes("UTF-8"))
      val scenario: ResolutionScenario =
        ProbatioPlugin.detectScenario(binary, None, Some(digest.toUpperCase(java.util.Locale.ROOT)))
      assertEquals(scenario, ResolutionScenario.PrebuiltAvailable)
    }
  }

  test("detectScenario: present binary with mismatched digest → PrebuiltChecksumInvalid") {
    withTempBinary("binary-bytes") { binary =>
      val scenario: ResolutionScenario =
        ProbatioPlugin.detectScenario(binary, None, Some("0" * 64))
      assertEquals(scenario, ResolutionScenario.PrebuiltChecksumInvalid)
    }
  }

  test("detectScenario: present binary with no recorded digest → PrebuiltChecksumInvalid (never assumed valid)") {
    withTempBinary("binary-bytes") { binary =>
      val scenario: ResolutionScenario =
        ProbatioPlugin.detectScenario(binary, None, None)
      assertEquals(scenario, ResolutionScenario.PrebuiltChecksumInvalid)
    }
  }

  test("detectScenario: absent binary with GraalVM home → NativeImage") {
    val missing: java.io.File = new java.io.File("/nonexistent-probatio-binary-path")
    assertEquals(
      ProbatioPlugin.detectScenario(missing, Some("/opt/graalvm"), None),
      ResolutionScenario.NativeImage
    )
  }

  test("detectScenario: absent binary with no GraalVM home → JarFallback") {
    val missing: java.io.File = new java.io.File("/nonexistent-probatio-binary-path")
    assertEquals(
      ProbatioPlugin.detectScenario(missing, None, None),
      ResolutionScenario.JarFallback
    )
  }

  test("resolveForShim: a native resolution for gate on a native platform is allowed") {
    List(ResolutionScenario.PrebuiltAvailable, ResolutionScenario.NativeImage).foreach { scenario =>
      val result: ResolutionResult =
        InstallResolver.resolveForShim(scenario, "gate", platformHasNative = true)
      assert(result.path.isDefined, s"a native resolution must not be blocked: $scenario → $result")
      assert(
        result.path.exists(_.endsWith(".probatio/bin/probatio")),
        s"the shim target names the cached binary: ${result.path}"
      )
    }
  }

  test("resolveForShim: a checksum-invalid fallback for a once-per-ring tool names checksum in its warning") {
    val result: ResolutionResult =
      InstallResolver.resolveForShim(ResolutionScenario.PrebuiltChecksumInvalid, "spec-lint", platformHasNative = true)
    assert(result.path.isDefined, "a once-per-ring launcher fallback is permitted")
    assert(
      result.path.exists(_.endsWith(".probatio/bin/probatio-jar-launcher.sh")),
      s"the shim target names the launcher: ${result.path}"
    )
    val warning: String = result.logLines.headOption.getOrElse("")
    assert(warning.contains("checksum"), s"the warning must name the checksum cause: $warning")
    assert(warning.contains("spec-lint"), s"the warning must name the tool: $warning")
  }

  test("resolveForShim: an ordinary fallback warning reports latency, not a checksum cause") {
    val result: ResolutionResult =
      InstallResolver.resolveForShim(ResolutionScenario.JarFallback, "spec-lint", platformHasNative = true)
    assert(result.path.isDefined, "a once-per-ring launcher fallback is permitted")
    val warning: String = result.logLines.headOption.getOrElse("")
    assert(warning.contains("startup latency"), s"the fallback warning names the latency cause: $warning")
    assert(!warning.contains("checksum"), s"an ordinary fallback must not claim a checksum cause: $warning")
  }

  test("resolveForShim: a blocked per-turn resolution reports the per-turn reason") {
    val result: ResolutionResult =
      InstallResolver.resolveForShim(ResolutionScenario.JarFallback, "gate", platformHasNative = true)
    assert(result.path.isEmpty, "a per-turn launcher resolution on a native platform is blocked")
    val reason: String = result.logLines.mkString(" ")
    assert(reason.contains("native artifact"), s"must name the native-artifact requirement: $reason")
    assert(reason.contains("per-turn"), s"must name the per-turn rule: $reason")
  }

  test("platformHasNative names linux and macOS, never windows") {
    assert(ProbatioPlugin.platformHasNative("Linux"))
    assert(ProbatioPlugin.platformHasNative("Mac OS X"))
    assert(ProbatioPlugin.platformHasNative("Darwin"))
    assert(!ProbatioPlugin.platformHasNative("Windows 11"))
    assert(!ProbatioPlugin.platformHasNative(""))
  }

  // spec: jar-launcher-dispatch — Scenario: Happy path — the plugin-installed launcher reaches the tool
  test("the plugin-installed launcher reaches the tool through the archive") {
    val repoRoot: java.io.File =
      if (new java.io.File("openspec/schemas/verified-scala3").isDirectory) new java.io.File(".").getCanonicalFile
      else new java.io.File("../..").getCanonicalFile
    val jarDir: java.io.File = new java.io.File(repoRoot, "workflow/cli/target/scala-3.8.4")
    val jars: Array[java.io.File] =
      Option(jarDir.listFiles())
        .map(_.filter(f => f.getName.matches("probatio-cli-assembly-.*\\.jar")))
        .getOrElse(Array.empty)
    assertEquals(jars.length, 1, s"exactly one built assembly archive under $jarDir — a missing archive is a failure, not a skip")
    val dir: java.io.File      = java.nio.file.Files.createTempDirectory("probatio-launcher-run").toFile
    val launcher: java.io.File = ProbatioPlugin.writeLauncherScript(new java.io.File(dir, "probatio-jar-launcher.sh"), jars(0))
    val r: HermeticResult      = HermeticEnv.capture(List("bash", launcher.getAbsolutePath, "--help"), HermeticEnv.empty)
    assertEquals(r.exitCode, 0, s"the launcher must reach the tool and exit clean, got ${r.exitCode}\n${r.out}\n${r.err}")
    assert(r.out.contains("gate"), s"the tool's help must list the gate subcommand:\n${r.out}")
    assert(r.out.contains("chain-state"), s"the tool's help must list the chain-state subcommand:\n${r.out}")
  }

  test("writeLauncherScript writes an executable launcher bound to the concrete jar") {
    val dir: java.io.File = java.nio.file.Files.createTempDirectory("probatio-launcher").toFile
    val jar: java.io.File = new java.io.File(dir, "probatio-assembly.jar")
    val target: java.io.File = new java.io.File(dir, "probatio-jar-launcher.sh")
    try {
      val written: java.io.File = ProbatioPlugin.writeLauncherScript(target, jar)
      val content: String = new String(java.nio.file.Files.readAllBytes(written.toPath), "UTF-8")
      assert(content.contains(s"""exec java -jar "${jar.getAbsolutePath}""""), s"launcher must bind the jar: $content")
      assert(written.canExecute, "launcher must be executable")
    } finally {
      val _deleted: Boolean = target.delete() && dir.delete()
      ()
    }
  }

  test("emitLogLines routes tagged lines to their level and strips tags") {
    val log = new RecordingLogger
    ProbatioPlugin.emitLogLines(log, List("[warn] w1", "[info] i1", "untagged"))
    assertEquals(
      log.events.get.map(_._1),
      List(sbt.util.Level.Warn, sbt.util.Level.Info, sbt.util.Level.Info)
    )
    assertEquals(log.events.get.map(_._2), List("w1", "i1", "untagged"))
  }

  test("stripTag removes the [warn]/[info] tag and leaves untagged lines") {
    assertEquals(ProbatioPlugin.stripTag("[warn] x"), "x")
    assertEquals(ProbatioPlugin.stripTag("[info] y"), "y")
    assertEquals(ProbatioPlugin.stripTag("z"), "z")
  }

  // ══════════════════════════════════════════════════════════════════════
  // workflow-delivery-hygiene (spec 9) — the install scope produces an
  // absolute target
  // spec: workflow-delivery-hygiene — Requirement: The generated script states which scope it resolved
  // ══════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — an install request produces an absolute
  //    target
  // spec: workflow-delivery-hygiene — Scenario: Happy path — an install request produces an absolute target
  test("an install request produces an absolute target") {
    val resolution: ResolutionResult = InstallResolver.resolveForShim(
      ResolutionScenario.PrebuiltAvailable,
      "spec-lint",
      platformHasNative = true
    )
    val installed: String =
      resolution.path.getOrElse(fail("a prebuilt resolution must carry a target"))
    assert(
      new java.io.File(installed).isAbsolute,
      s"the install resolution must yield an absolute path: $installed"
    )
    ShimGenerator.generateShim(
      resolution,
      ShimTargetScope.AbsoluteInstall(installed),
      "spec-lint"
    ) match {
      case Right(shim) =>
        assert(
          shim.contains(s"""exec "$installed" spec-lint "$$@""""),
          s"the install shim must exec the absolute installed path verbatim: $shim"
        )
      case Left(reason) =>
        fail(s"an install request must produce a shim, got Left($reason)")
    }
  }

  test("uninstallArtifacts removes existing files and reports the count") {
    val dir: java.io.File = java.nio.file.Files.createTempDirectory("probatio-uninstall").toFile
    val present: java.io.File = new java.io.File(dir, "present")
    val absent: java.io.File  = new java.io.File(dir, "absent")
    val _w: java.nio.file.Path = java.nio.file.Files.write(present.toPath, "x".getBytes("UTF-8"))
    try {
      val removed: Int = ProbatioPlugin.uninstallArtifacts(Seq(present, absent))
      assertEquals(removed, 1)
      assert(!present.exists, "present file must be deleted")
    } finally {
      val _deleted: Boolean = dir.delete()
      ()
    }
  }
}

/** A logger that records (level, message) pairs for assertions. */
private final class RecordingLogger extends sbt.util.AbstractLogger {
  val events: java.util.concurrent.atomic.AtomicReference[List[(sbt.util.Level.Value, String)]] =
    new java.util.concurrent.atomic.AtomicReference(List.empty)
  def getLevel: sbt.util.Level.Value = sbt.util.Level.Info
  def setLevel(level: sbt.util.Level.Value): Unit = ()
  def setTrace(flag: Int): Unit = ()
  def getTrace: Int = 0
  def successEnabled: Boolean = false
  def setSuccessEnabled(flag: Boolean): Unit = ()
  def trace(t: => Throwable): Unit = ()
  override def ansiCodesSupported: Boolean = false
  def log(level: sbt.util.Level.Value, message: => String): Unit = {
    val _updated: List[(sbt.util.Level.Value, String)] =
      events.updateAndGet((es: List[(sbt.util.Level.Value, String)]) => es :+ (level -> message))
    ()
  }
  def success(message: => String): Unit = ()
  def logAll(evts: Seq[sbt.util.LogEvent]): Unit = evts.foreach(e => log(sbt.util.Level.Info, e.toString))
  def control(event: sbt.util.ControlEvent.Value, message: => String): Unit = ()
}
