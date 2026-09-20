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

    /** Arguments `probatioSpecLint` passes to `probatio spec-lint`. None = the task reports the missing value instead of invoking the tool. */
    val probatioSpecLintArgs: SettingKey[Option[Seq[String]]] =
      settingKey[Option[Seq[String]]]("Arguments for `probatio spec-lint` (e.g. Seq(\"openspec\")); unset = task reports rather than invoking")

    /** Arguments `probatioChainState` passes to `probatio chain-state`. None = the task reports the missing value instead of invoking the tool. */
    val probatioChainStateArgs: SettingKey[Option[Seq[String]]] =
      settingKey[Option[Seq[String]]]("Arguments for `probatio chain-state` (e.g. Seq(\"--change-dir\",\"openspec/changes/x\",\"--change\",\"x\",\"--baseline\",\"<sha>\")); unset = task reports rather than invoking")

    /** Arguments `probatioCheckpoint` passes to `probatio checkpoint`. None = the task reports the missing value instead of invoking the tool. */
    val probatioCheckpointArgs: SettingKey[Option[Seq[String]]] =
      settingKey[Option[Seq[String]]]("Arguments for `probatio checkpoint` (e.g. Seq(\"report\",\"--spec\",\"<spec>\",\"--rings\",\"R0\")); unset = task reports rather than invoking")

    /** Arguments `probatioLedgerAppend` passes to `probatio ledger`. None = the task reports the missing value instead of invoking the tool. */
    val probatioLedgerAppendArgs: SettingKey[Option[Seq[String]]] =
      settingKey[Option[Seq[String]]]("Arguments for `probatio ledger` (the \"append\" operation and its fields); unset = task reports rather than invoking")

    /** Recorded SHA-256 the cached prebuilt binary must match. None = a present binary is not trusted (checksum-invalid). */
    val probatioExpectedSha256: SettingKey[Option[String]] =
      settingKey[Option[String]](
        "The recorded SHA-256 digest the cached probatio binary must match before it may execute; unset = a present binary is treated as checksum-invalid"
      )

    /** The concrete assembly JAR the JAR-fallback launcher binds to. None = no launcher is written. */
    val probatioAssemblyJar: SettingKey[Option[File]] =
      settingKey[Option[File]](
        "The assembly JAR the JAR-fallback launcher execs; unset = the launcher cannot be written bound to a concrete artifact"
      )

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

  /** The JAR-fallback launcher path within the cache directory. */
  private val launcherScriptPath: File =
    probatioHomeDir / "bin" / "probatio-jar-launcher.sh"

  override def projectSettings: Seq[Setting[_]] = Seq(
    // ── Settings (no side effects at load time — R-S6) ────────────────────
    probatioVersion          := "14.0.0",
    graalVMHome              := None,
    probatioSpecLintArgs     := None,
    probatioChainStateArgs   := None,
    probatioCheckpointArgs   := None,
    probatioLedgerAppendArgs := None,
    probatioExpectedSha256   := None,
    probatioAssemblyJar      := None,

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
      // (1) Prebuilt binary: cached AND verified against the recorded
      //     SHA-256 (probatioExpectedSha256) → PrebuiltAvailable
      // (2) Prebuilt binary cached but not checksum-valid — digest
      //     mismatch OR no recorded digest configured → PrebuiltChecksumInvalid
      // (3) No prebuilt binary, no GraalVM home → JarFallback
      // (4) No prebuilt binary, GraalVM home provided → NativeImage
      // A present binary is never assumed checksum-valid (R-N3); the
      // recorded digest is the only evidence the check accepts.
      val scenario: ResolutionScenario =
        detectScenario(resolvedBinaryPath, graalvmOpt, probatioExpectedSha256.value)

      // Use the pure model to get the log line — no fallback is silent.
      val result: ResolutionResult = InstallResolver.resolve(scenario)
      result.logLines.headOption match {
        case Some(line) => emitLogLines(log, List(line))
        case None => sys.error("probatioInstall: no log line produced (silent fallback — R-S3 violation)")
      }

      // Perform the actual side effect based on the scenario.
      scenario match {
        case ResolutionScenario.PrebuiltAvailable | ResolutionScenario.NativeImage =>
          resolvedBinaryPath
        case ResolutionScenario.PrebuiltChecksumInvalid | ResolutionScenario.JarFallback =>
          // Assembly JAR fallback: emit a java -jar launcher bound to the
          // concrete configured artifact — never an environment variable
          // that may be unset.
          probatioAssemblyJar.value match {
            case Some(jar) => writeLauncherScript(launcherScriptPath, jar)
            case None =>
              sys.error(
                "probatioAssemblyJar is unset — the JAR-fallback launcher must bind to a " +
                  "concrete artifact; set probatioAssemblyJar to the assembly JAR path"
              )
          }
      }
    }.value,

    // ── Delegating tasks — exit-code mapping (R-S4) ───────────────────────
    // Exit 0 → success, Exit 1 → sys.error(finding), Exit 2 → sys.error(undetermined)
    probatioSpecLint := Def.task {
      val binary: File = probatioInstall.value
      probatioSpecLintArgs.value match {
        case Some(args) => runDelegatingTask(binary, "spec-lint", args, streams.value.log)
        case None =>
          sys.error(
            "probatioSpecLintArgs is unset — `probatio spec-lint` requires its arguments; the tool was not invoked"
          )
      }
    }.value,
    probatioChainState := Def.task {
      val binary: File = probatioInstall.value
      probatioChainStateArgs.value match {
        case Some(args) => runDelegatingTask(binary, "chain-state", args, streams.value.log)
        case None =>
          sys.error(
            "probatioChainStateArgs is unset — `probatio chain-state` requires its arguments; the tool was not invoked"
          )
      }
    }.value,
    probatioCheckpoint := Def.task {
      val binary: File = probatioInstall.value
      probatioCheckpointArgs.value match {
        case Some(args) => runDelegatingTask(binary, "checkpoint", args, streams.value.log)
        case None =>
          sys.error(
            "probatioCheckpointArgs is unset — `probatio checkpoint` requires its arguments; the tool was not invoked"
          )
      }
    }.value,
    probatioLedgerAppend := Def.task {
      val binary: File = probatioInstall.value
      probatioLedgerAppendArgs.value match {
        case Some(args) => runDelegatingTask(binary, "ledger", Seq("append") ++ args, streams.value.log)
        case None =>
          sys.error(
            "probatioLedgerAppendArgs is unset — `probatio ledger append` requires its arguments; the tool was not invoked"
          )
      }
    }.value,

    // ── probatioGateShim — idempotent 3-line shim generation (R-S5) ───────
    // The shim's target is bound to the resolution result, never to a
    // literal path (native-gate-delivery). A blocked resolution writes no
    // shim and the reason is reported.
    probatioGateShim := Def.task {
      val log: Logger = streams.value.log
      // Resolve BEFORE installing: a blocked per-turn resolution performs
      // no install side-effects, and the block reason — not a downstream
      // install error — is what the task reports.
      val scenario: ResolutionScenario = detectScenario(
        resolvedBinaryPath,
        graalVMHome.value,
        probatioExpectedSha256.value
      )
      val resolution: ResolutionResult = InstallResolver.resolveForShim(
        scenario,
        "gate",
        currentPlatformHasNative
      )
      emitLogLines(log, resolution.logLines)
      resolution.path match {
        case None =>
          sys.error(
            s"probatioGateShim: ${resolution.logLines.map(stripTag).mkString(" ")} — no shim written"
          )
        case Some(_) =>
          // The resolution decides WHICH artifact delivers the tool; the
          // install supplies its real path — the shim execs what the
          // delivery actually wrote.
          val installed: File = probatioInstall.value
          val bound: ResolutionResult =
            resolution.copy(path = Some(installed.getAbsolutePath))
          writeShim(bound, "gate", gateShimPath) match {
            case Right(file)  => file
            case Left(reason) => sys.error(s"probatioGateShim: $reason — no shim written")
          }
      }
    }.value,

    // ── probatioUninstall — clean teardown ────────────────────────────────
    probatioUninstall := Def.task {
      val log: Logger = streams.value.log
      val _: Int = uninstallArtifacts(
        Seq(gateShimPath, resolvedBinaryPath, launcherScriptPath)
      )
      log.info("probatio uninstalled (shims, binaries, and launchers removed)")
    }.value
  )

  /**
   * Determines the install-resolution scenario from real-world state
   * (R-S3). A present binary is `PrebuiltAvailable` only when its SHA-256
   * matches the recorded digest (`probatioExpectedSha256`); a present
   * binary that cannot be verified — digest mismatch or no recorded
   * digest configured — is `PrebuiltChecksumInvalid`, never assumed
   * valid.
   */
  private[plugin] def detectScenario(
    binary: File,
    graalvmOpt: Option[String],
    expectedSha256: Option[String]
  ): ResolutionScenario =
    if (binary.exists) {
      val verified: Boolean = expectedSha256.exists { expected =>
        sha256Hex(binary) == expected.trim.toLowerCase(java.util.Locale.ROOT)
      }
      if (verified) ResolutionScenario.PrebuiltAvailable
      else ResolutionScenario.PrebuiltChecksumInvalid
    } else if (graalvmOpt.isDefined) {
      ResolutionScenario.NativeImage
    } else {
      ResolutionScenario.JarFallback
    }

  /** The lowercase hex SHA-256 of a file's contents. */
  private def sha256Hex(file: File): String = {
    val digest: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
    digest
      .digest(java.nio.file.Files.readAllBytes(file.toPath))
      .map(b => f"${b & 0xff}%02x")
      .mkString
  }

  /**
   * Whether the current platform produces a native artifact. Mirrors
   * `Platform.hasNativeBinary` (packaging): linux and macOS produce one,
   * Windows does not. The plugin cannot import packaging code (R-S1), so
   * the fact is detected from `os.name` here.
   */
  private[plugin] def currentPlatformHasNative: Boolean =
    platformHasNative(sys.props.getOrElse("os.name", ""))

  /**
   * Whether `osName` names a platform that produces a native artifact —
   * the pure half of `currentPlatformHasNative`, so the decision is
   * testable on every OS name rather than only the host's.
   */
  private[plugin] def platformHasNative(osName: String): Boolean = {
    val os: String = osName.toLowerCase(java.util.Locale.ROOT)
    os.contains("linux") || os.contains("mac") || os.contains("darwin")
  }

  /**
   * Writes the JAR-fallback launcher bound to a concrete artifact and
   * marks it executable. `private[plugin]` so the seam is unit-testable.
   */
  private[plugin] def writeLauncherScript(target: File, jar: File): File = {
    IO.write(
      target,
      s"""#!/usr/bin/env bash
         |exec java -jar "${jar.getAbsolutePath}" "$$@"
         |""".stripMargin
    )
    val _: Boolean = target.setExecutable(true)
    target
  }

  /** Deletes each file that exists; returns the number removed. */
  private[plugin] def uninstallArtifacts(files: Seq[File]): Int =
    files.foldLeft(0) { (removed, f) =>
      if (f.exists) { IO.delete(Seq(f)); removed + 1 }
      else removed
    }

  /**
   * Emits each resolution log line at the level its `[info]`/`[warn]`
   * tag names; untagged lines log at info. Every line a resolution
   * produced reaches the consumer exactly once.
   */
  private[plugin] def emitLogLines(log: Logger, lines: List[String]): Unit =
    lines.foreach { line =>
      if (line.startsWith("[warn]")) log.warn(stripTag(line))
      else log.info(stripTag(line))
    }

  /** Removes a leading `[info] `/`[warn] ` tag from a model log line. */
  private[plugin] def stripTag(line: String): String =
    if (line.startsWith("[warn] ")) line.stripPrefix("[warn] ")
    else if (line.startsWith("[info] ")) line.stripPrefix("[info] ")
    else line

  /**
   * Generates the shim content for a resolution result and writes it to
   * the target path (native-gate-delivery).
   *
   * `Left(reason)` when the resolution is blocked — no shim is written.
   * `Left(could-not-determine naming the location)` when the location
   * cannot be written. `Right(target)` on success; generation is
   * repeatable — the same resolution yields byte-identical content.
   */
  private[plugin] def writeShim(
    resolution: ResolutionResult,
    subcommand: String,
    target: File
  ): Either[String, File] =
    ShimGenerator.generateShim(resolution, subcommand) match {
      case Left(reason) => Left(reason)
      case Right(content) =>
        val parent: File = target.getParentFile
        if (parent != null && parent.exists && !parent.isDirectory) {
          Left(
            s"probatio $subcommand shim could not determine: " +
              s"${target.getAbsolutePath} is not writable — its parent is not a directory"
          )
        } else {
          try {
            // IO.write creates the parent directories itself.
            IO.write(target, content)
            val _: Boolean = target.setExecutable(true)
            Right(target)
          } catch {
            case e: Exception =>
              Left(
                s"probatio $subcommand shim could not determine: cannot write " +
                  s"${target.getAbsolutePath} (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})"
              )
          }
        }
    }

  /**
   * Invokes the resolved binary with the given subcommand and argument
   * list, and maps the exit code using `ExitCodeMapping`. Both exit 1 and
   * exit 2 fail the build via `sys.error` with distinguishable messages
   * (R-S4). The argument list is a required parameter — a task that
   * cannot supply its tool's arguments reports that fact rather than
   * invoking the tool without them (native-gate-delivery).
   *
   * This helper is called from `Def.task` bodies only — never at build
   * load time (R-S6). It is `private[plugin]` so the plugin's own test
   * suites can pin its signature; it is not part of the consumer-facing
   * task surface.
   */
  private[plugin] def runDelegatingTask(
    binary: File,
    subcommand: String,
    args: Seq[String],
    log: Logger
  ): Unit = {
    val command: Seq[String] = Seq(binary.getAbsolutePath, subcommand) ++ args
    val stdoutFile: File     = java.io.File.createTempFile("probatio-stdout", ".txt")
    stdoutFile.deleteOnExit()
    val writer: java.io.PrintWriter = new java.io.PrintWriter(stdoutFile)
    val exitCode: Int =
      try {
        scala.sys.process
          .Process(command)
          .!(
            scala.sys.process.ProcessLogger(
              out => writer.println(out),
              err => log.error(err)
            )
          )
      } finally {
        writer.close()
      }
    val stdout: String = {
      val source: scala.io.BufferedSource = scala.io.Source.fromFile(stdoutFile)
      try source.mkString.trim
      finally source.close()
    }
    val _: Boolean = stdoutFile.delete()
    ExitCodeMapping.mapExitCode(subcommand, exitCode, stdout) match {
      case Right(()) => () // success
      case Left(msg) => sys.error(msg)
    }
  }
}
