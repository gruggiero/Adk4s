package org.sinemenda.probatio.packaging

/**
 * Binary resolution logic — native binary vs JAR fallback (R-N2).
 *
 * The `gate` subcommand SHALL be distributed as a GraalVM native-image
 * binary; it MUST NOT fall back to a JAR launcher on platforms where a
 * native binary is available. Once-per-ring subcommands MAY run from
 * an assembly JAR where a native binary is unavailable, and the
 * resolution SHALL emit exactly one distinct warning line to stderr
 * when the JAR fallback is active.
 *
 * spec: native-packaging — Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands
 */
object BinaryResolution:

  /** The set of subcommands that are per-turn (latency-critical). */
  val perTurnSubcommands: Set[String] = Set("gate")

  /**
   * Resolves the execution path for a subcommand on a given platform.
   *
   * Resolution rules (R-N2):
   *
   * 1. Platform HAS a native binary (linux/macos) AND binary is available:
   *    → `NativeBinary` (no warning, for any subcommand)
   *
   * 2. Platform HAS a native binary (linux/macos) AND binary is NOT available:
   *    - If subcommand is `gate` (per-turn): → `Blocked` — gate MUST NOT
   *      fall back to JAR on a platform where a native binary exists.
   *      This is the hard-blocker: the binary must be installed.
   *    - If subcommand is NOT `gate` (once-per-ring): → `JarFallback`
   *      with exactly one warning line
   *
   * 3. Platform has NO native binary (windows-x86_64):
   *    → `JarFallback` with exactly one warning line (for any subcommand,
   *      including gate — this is the documented windows exception)
   *
   * spec: native-packaging — Scenario: gate uses native binary, never JAR, on a supported platform
   * spec: native-packaging — Scenario: gate falls back to JAR only on a platform with no native binary
   * spec: native-packaging — Scenario: once-per-ring tool on JAR fallback emits exactly one warning (adversarial)
   */
  def resolve(
    subcommand: String,
    platform: Platform,
    nativeBinaryAvailable: Boolean,
    jarPath: String
  ): ResolutionResult =
    if platform.hasNativeBinary then
      if nativeBinaryAvailable then ResolutionResult.NativeBinary(s"probatio-${platform.artifactSuffix}")
      else if perTurnSubcommands.contains(subcommand) then
        ResolutionResult.Blocked(
          s"gate requires a native binary on ${platform.artifactSuffix} — " +
            s"JAR fallback is forbidden for per-turn subcommands on supported platforms. " +
            s"Run probatioInstall to download the native binary."
        )
      else
        val warning: String = buildWarning(subcommand, platform)
        ResolutionResult.JarFallback(jarPath, warning)
    else
      val warning: String = buildWarning(subcommand, platform)
      ResolutionResult.JarFallback(jarPath, warning)

  /**
   * Builds the single warning line emitted when JAR fallback is active.
   *
   * The warning names the subcommand and the fallback, and is exactly
   * one line (no embedded newlines).
   */
  private def buildWarning(subcommand: String, platform: Platform): String =
    s"probatio: $subcommand fallback to JAR launcher — native-image startup latency is active until a native binary is available for ${platform.artifactSuffix}"

/**
 * The result of binary resolution.
 *
 * `NativeBinary` — the native binary is used (no warning).
 * `JarFallback` — the JAR launcher is used with exactly one warning line.
 * `Blocked` — the subcommand cannot run (gate on a supported platform
 * where the native binary is not installed — JAR fallback is forbidden
 * for per-turn subcommands on supported platforms).
 */
enum ResolutionResult:
  case NativeBinary(path: String)
  case JarFallback(path: String, warning: String)
  case Blocked(reason: String)
