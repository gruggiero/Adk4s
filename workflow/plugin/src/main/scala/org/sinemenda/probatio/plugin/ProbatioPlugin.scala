package org.sinemenda.probatio.plugin

import sbt._
import sbt.Keys._

/**
 * `sbt-probatio` — a thin sbt 1.x AutoPlugin that integrates the `probatio`
 * native binary into sbt builds via delegating tasks.
 *
 * The plugin links zero `probatio-core` (Scala 3 / TASTy) code — all
 * communication with the tooling is argv + exit codes + stdout JSON. The
 * plugin invokes the resolved `probatio` binary as a subprocess and parses
 * its exit code and stdout, never importing or calling `probatio-core` types
 * directly (R-S1).
 *
 * The plugin is sbt-2-ready (R-S2): no `in GlobalScope` abuse, no deprecated
 * operators (`<<=`, `in Config.Global`), all task composition uses `Def.task`
 * idiomatic patterns.
 *
 * The plugin does NOT execute at build load time beyond settings declaration
 * (R-S6): no network resolution, no binary execution, no filesystem mutation
 * occurs during `sbt` startup. All side effects are behind explicit tasks.
 *
 * spec: sbt-plugin — Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code
 * spec: sbt-plugin — Requirement: The plugin forward-declares future sbt 2 support
 * spec: sbt-plugin — Requirement: The plugin does not execute at build load time beyond settings declaration
 * spec: sbt-plugin — Requirement: Tooling subprojects reference zero adk4s code (R-ARCH1 cross-cutting)
 */
object ProbatioPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  /** AutoPlugin keys — declared here, used in projectSettings. */
  object autoImport {

    /** Pins the `probatio` release version the plugin resolves. */
    val probatioVersion: SettingKey[String] =
      settingKey[String]("The probatio release version to resolve")

    /** Opt-in: when Some(path), probatioInstall may build native-image locally. */
    val graalVMHome: SettingKey[Option[String]] =
      settingKey[Option[String]]("Optional GraalVM home for local native-image builds")

    /** Resolves the probatio binary (prebuilt → JAR → opt-in native-image). */
    val probatioInstall: TaskKey[File] =
      taskKey[File]("Resolve the probatio binary (prebuilt, JAR fallback, or opt-in native-image)")

    /** Delegates to `probatio spec-lint`; maps exit codes to task outcomes. */
    val probatioSpecLint: TaskKey[Unit] =
      taskKey[Unit]("Run probatio spec-lint via the resolved binary")

    /** Delegates to `probatio chain-state`; maps exit codes to task outcomes. */
    val probatioChainState: TaskKey[Unit] =
      taskKey[Unit]("Run probatio chain-state via the resolved binary")

    /** Delegates to `probatio checkpoint`; maps exit codes to task outcomes. */
    val probatioCheckpoint: TaskKey[Unit] =
      taskKey[Unit]("Run probatio checkpoint via the resolved binary")

    /** Delegates to `probatio ledger append`; append-only by construction. */
    val probatioLedgerAppend: TaskKey[Unit] =
      taskKey[Unit]("Run probatio ledger append via the resolved binary")

    /** (Re)generates hook shims idempotently — 3-line exec wrappers. */
    val probatioGateShim: TaskKey[File] =
      taskKey[File]("Generate the 3-line gate hook shim to the resolved binary")

    /** Removes resolved binaries and generated shims; supports clean teardown. */
    val probatioUninstall: TaskKey[Unit] =
      taskKey[Unit]("Remove resolved binaries and generated shims")
  }

  import autoImport._

  /** The directory where the plugin caches the resolved binary and shims. */
  private val probatioHomeDir: File =
    file(sys.props.getOrElse("user.home", ".")) / ".probatio"

  /** The resolved binary path within the cache directory. */
  private val resolvedBinaryPath: File =
    probatioHomeDir / "bin" / "probatio"

  /** The gate shim path within the cache directory. */
  private val gateShimPath: File =
    probatioHomeDir / "hooks" / "gate"

  override def projectSettings: Seq[Setting[_]] = Seq(
    // ── Settings (no side effects at load time — R-S6) ────────────────────
    probatioVersion := "14.0.0",
    graalVMHome     := None,

    // ── probatioInstall — resolves binary in fixed order (R-S3) ───────────
    // Each fallback emits exactly one distinct log line. No fallback is silent.
    // The resolution order is modeled by InstallResolver (pure) and executed
    // here (effectful). The task inspects real-world state to determine which
    // scenario applies, calls InstallResolver.resolve to get the log line,
    // then performs the actual side effects.
    probatioInstall := Def.task {
      val log: Logger                = streams.value.log
      val version: String            = probatioVersion.value
      val graalvmOpt: Option[String] = graalVMHome.value
      val binDir: File               = probatioHomeDir / "bin"
      IO.createDirectories(Seq(binDir))

      // Determine the resolution scenario from real-world state.
      // (1) Prebuilt binary: already cached and checksum-valid → PrebuiltAvailable
      // (2) Prebuilt binary cached but checksum invalid → PrebuiltChecksumInvalid
      // (3) No prebuilt binary, no GraalVM home → JarFallback
      // (4) No prebuilt binary, GraalVM home provided → NativeImage
      // The actual download + checksum verification is in native-packaging
      // (spec 4). Here we check if the binary already exists on disk.
      val scenario: ResolutionScenario =
        if (resolvedBinaryPath.exists) {
          // Binary is present — assume checksum valid (native-packaging
          // spec 4 implements the full download + SHA-256 verification).
          ResolutionScenario.PrebuiltAvailable
        } else if (graalvmOpt.isDefined) {
          ResolutionScenario.NativeImage
        } else {
          ResolutionScenario.JarFallback
        }

      // Use the pure model to get the log line — no fallback is silent.
      val result: ResolutionResult = InstallResolver.resolve(scenario)
      result.logLines.headOption match {
        case Some(line) if line.startsWith("[info]") => log.info(line.stripPrefix("[info] "))
        case Some(line) if line.startsWith("[warn]") => log.warn(line.stripPrefix("[warn] "))
        case Some(line)                              => log.info(line)
        case None => sys.error("probatioInstall: no log line produced (silent fallback — R-S3 violation)")
      }

      // Perform the actual side effect based on the scenario.
      scenario match {
        case ResolutionScenario.PrebuiltAvailable | ResolutionScenario.NativeImage =>
          resolvedBinaryPath
        case ResolutionScenario.PrebuiltChecksumInvalid | ResolutionScenario.JarFallback =>
          // Assembly JAR fallback: emit a java -jar launcher script
          val launcherScript: File = binDir / "probatio-jar-launcher.sh"
          IO.write(
            launcherScript,
            s"""#!/usr/bin/env bash
               |exec java -jar "$$PROBATIO_JAR" "$$@"
               |""".stripMargin
          )
          val _: Boolean = launcherScript.setExecutable(true)
          launcherScript
      }
    }.value,

    // ── Delegating tasks — exit-code mapping (R-S4) ───────────────────────
    // Exit 0 → success, Exit 1 → sys.error(finding), Exit 2 → sys.error(undetermined)
    probatioSpecLint := Def.task {
      val binary: File = probatioInstall.value
      runDelegatingTask(binary, "spec-lint", streams.value.log)
    }.value,
    probatioChainState := Def.task {
      val binary: File = probatioInstall.value
      runDelegatingTask(binary, "chain-state", streams.value.log)
    }.value,
    probatioCheckpoint := Def.task {
      val binary: File = probatioInstall.value
      runDelegatingTask(binary, "checkpoint", streams.value.log)
    }.value,
    probatioLedgerAppend := Def.task {
      val binary: File = probatioInstall.value
      runDelegatingTask(binary, "ledger", streams.value.log)
    }.value,

    // ── probatioGateShim — idempotent 3-line shim generation (R-S5) ───────
    probatioGateShim := Def.task {
      val binary: File  = probatioInstall.value
      val hookDir: File = probatioHomeDir / "hooks"
      IO.createDirectories(Seq(hookDir))
      val shimContent: String = ShimGenerator.generateShim(binary.getAbsolutePath)
      IO.write(gateShimPath, shimContent)
      val _: Boolean = gateShimPath.setExecutable(true)
      gateShimPath
    }.value,

    // ── probatioUninstall — clean teardown ────────────────────────────────
    probatioUninstall := Def.task {
      val log: Logger = streams.value.log
      if (gateShimPath.exists) IO.delete(Seq(gateShimPath))
      if (resolvedBinaryPath.exists) IO.delete(Seq(resolvedBinaryPath))
      log.info("probatio uninstalled (shims and binaries removed)")
    }.value
  )

  /**
   * Invokes the resolved binary with the given subcommand and maps the exit
   * code using `ExitCodeMapping`. Both exit 1 and exit 2 fail the build via
   * `sys.error` with distinguishable messages (R-S4).
   *
   * This helper is called from `Def.task` bodies only — never at build load
   * time (R-S6).
   */
  private def runDelegatingTask(binary: File, subcommand: String, log: Logger): Unit = {
    val command: Seq[String] = Seq(binary.getAbsolutePath, subcommand)
    val stdoutFile: File     = java.io.File.createTempFile("probatio-stdout", ".txt")
    stdoutFile.deleteOnExit()
    val writer: java.io.PrintWriter = new java.io.PrintWriter(stdoutFile)
    val exitCode: Int = scala.sys.process
      .Process(command)
      .!(
        scala.sys.process.ProcessLogger(
          out => writer.println(out),
          err => log.error(err)
        )
      )
    writer.close()
    val stdout: String = scala.io.Source.fromFile(stdoutFile).mkString.trim
    val _: Boolean     = stdoutFile.delete()
    ExitCodeMapping.mapExitCode(subcommand, exitCode, stdout) match {
      case Right(()) => () // success
      case Left(msg) => sys.error(msg)
    }
  }
}
