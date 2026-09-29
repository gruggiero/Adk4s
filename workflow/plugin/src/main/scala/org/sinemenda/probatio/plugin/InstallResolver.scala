package org.sinemenda.probatio.plugin

/**
 * Models the `probatioInstall` resolution order (R-S3).
 *
 * The resolution order is fixed: prebuilt binary → assembly JAR → (opt-in)
 * local native-image. Each fallback emits exactly one distinct sbt log line
 * so the consumer is never left wondering which resolution path is active.
 * No fallback is silent.
 *
 * - PrebuiltAvailable → `[info]` line: "probatio binary resolved (prebuilt: <platform>)"
 * - PrebuiltChecksumInvalid → `[warn]` line: JAR fallback (checksum failed)
 * - JarFallback → `[warn]` line: "probatio binary resolved (JAR fallback: startup latency active until native binary available)"
 * - NativeImage → `[info]` line: "probatio binary resolved (native-image: local build)"
 *
 * This is a pure model — the actual download/checksum/native-image work is
 * done by the `probatioInstall` sbt task, which calls this function to
 * determine the resolution strategy and log line.
 *
 * spec: sbt-plugin — Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image
 * spec: sbt-plugin — Property: install-resolution-order
 */
object InstallResolver {

  /**
   * The plugin's cache paths — the same locations `probatioInstall`
   * writes to (`~/.probatio/bin/...`). A resolved path names the artifact
   * the install produces, not a fictional `/usr/local/bin` location.
   */
  private val probatioBinDir: String =
    sys.props.getOrElse("user.home", ".") + "/.probatio/bin"
  private val resolvedBinaryPath: String = probatioBinDir + "/probatio"
  private val launcherScriptPath: String = probatioBinDir + "/probatio-jar-launcher.sh"

  /**
   * Resolves the binary for the given scenario, returning the resolved path
   * and exactly one log line.
   *
   * This is a pure function — it does not perform network I/O or filesystem
   * mutation. The `probatioInstall` sbt task inspects the real-world state
   * (binary cached? checksum valid? GraalVM home set?) to determine which
   * scenario applies, then calls this function to get the log line.
   */
  def resolve(scenario: ResolutionScenario): ResolutionResult = scenario match {
    case ResolutionScenario.PrebuiltAvailable =>
      ResolutionResult(
        path = Some(resolvedBinaryPath),
        logLines = List("[info] probatio binary resolved (prebuilt: linux-x86_64)")
      )
    case ResolutionScenario.PrebuiltChecksumInvalid =>
      ResolutionResult(
        path = Some(launcherScriptPath),
        logLines = List(
          "[warn] probatio binary resolved (JAR fallback: checksum mismatch, startup latency active until native binary available)"
        )
      )
    case ResolutionScenario.JarFallback =>
      ResolutionResult(
        path = Some(launcherScriptPath),
        logLines =
          List("[warn] probatio binary resolved (JAR fallback: startup latency active until native binary available)")
      )
    case ResolutionScenario.NativeImage =>
      ResolutionResult(
        path = Some(resolvedBinaryPath),
        logLines = List("[info] probatio binary resolved (native-image: local build)")
      )
  }

  /**
   * The per-turn subcommands — mirrors
   * `BinaryResolution.perTurnSubcommands` in `probatio-cli`. The plugin
   * links no probatio-core/cli code (R-S1), so the set is restated here;
   * both name exactly `gate`.
   */
  private val perTurnSubcommands: Set[String] = Set("gate")

  /**
   * Resolves the shim target for a subcommand on a platform
   * (native-gate-delivery).
   *
   * On a platform that produces a native artifact, a launcher resolution
   * (`JarFallback` or `PrebuiltChecksumInvalid`) is BLOCKED for a per-turn
   * subcommand: the returned `ResolutionResult` carries `path = None` and
   * a single reason line naming that a native artifact is required. For a
   * once-per-ring subcommand, or on a platform with no native artifact,
   * the launcher resolution stands with its warning line. A native
   * resolution (`PrebuiltAvailable`, `NativeImage`) always stands.
   *
   * This is a pure function — `probatioGateShim` supplies the scenario and
   * the platform fact. The `path` on an allowed result is the model's
   * artifact locator (which artifact delivers the tool); the task rebinds
   * it to the real file `probatioInstall` produced before generating the
   * shim, so the exec line always names an artifact the delivery wrote.
   *
   * spec: native-gate-delivery — Requirement: The per-turn tool is delivered as a native artifact where one exists for the platform
   */
  def resolveForShim(
    scenario: ResolutionScenario,
    subcommand: String,
    platformHasNative: Boolean
  ): ResolutionResult = {
    val launcherResolution: Boolean = scenario match {
      case ResolutionScenario.PrebuiltChecksumInvalid | ResolutionScenario.JarFallback => true
      case ResolutionScenario.PrebuiltAvailable | ResolutionScenario.NativeImage     => false
    }
    if (launcherResolution && platformHasNative && perTurnSubcommands.contains(subcommand)) {
      ResolutionResult(
        path = None,
        logLines = List(
          s"[warn] probatio $subcommand: a native artifact is required on this platform — " +
            "the launcher is not accepted for a per-turn subcommand; no shim is written"
        )
      )
    } else if (launcherResolution) {
      val detail: String =
        if (scenario == ResolutionScenario.PrebuiltChecksumInvalid)
          "checksum mismatch — startup latency active until a native binary is available"
        else "startup latency active until a native binary is available"
      ResolutionResult(
        path = Some(launcherScriptPath),
        logLines = List(s"[warn] probatio $subcommand: resolved to the JAR launcher — $detail")
      )
    } else resolve(scenario)
  }
}

/**
 * The availability states that determine the resolution path.
 *
 * Sealed enum with exactly 4 cases — no fifth state is expressible.
 */
sealed trait ResolutionScenario extends Product with Serializable
object ResolutionScenario {
  case object PrebuiltAvailable       extends ResolutionScenario
  case object PrebuiltChecksumInvalid extends ResolutionScenario
  case object JarFallback             extends ResolutionScenario
  case object NativeImage             extends ResolutionScenario

  /** All cases — used by the scenario test that verifies no fallback is silent. */
  val values: Array[ResolutionScenario] =
    Array(PrebuiltAvailable, PrebuiltChecksumInvalid, JarFallback, NativeImage)
}

/**
 * The result of a resolution: an optional resolved path and exactly one
 * log line.
 *
 * The path is `Some(path)` when a binary or launcher is available, and
 * `None` when the resolution is blocked — `resolveForShim` produces
 * `None` for a launcher resolution of a per-turn subcommand on a
 * native-artifact platform (native-gate-delivery).
 */
final case class ResolutionResult(
  path: Option[String],
  logLines: List[String]
)
