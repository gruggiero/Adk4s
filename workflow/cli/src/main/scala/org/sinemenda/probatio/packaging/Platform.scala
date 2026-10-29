package org.sinemenda.probatio.packaging

/**
 * Committed release platforms (R-N3).
 *
 * Three platforms receive native binaries: linux-x86_64, macos-aarch64,
 * macos-x86_64. Windows-x86_64 is JAR-fallback-only — no native binary
 * is produced for it, documented as such.
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 */
enum Platform:
  case LinuxX86_64
  case MacosAarch64
  case MacosX86_64
  case WindowsX86_64

  /**
   * Whether this platform receives a native binary in the release.
   * Linux/macos (all three) → true; Windows → false (JAR-fallback-only).
   *
   * spec: native-packaging — Scenario: windows-x86_64 release has no native binary, documented (adversarial)
   */
  def hasNativeBinary: Boolean =
    this match
      case Platform.LinuxX86_64   => true
      case Platform.MacosAarch64  => true
      case Platform.MacosX86_64   => true
      case Platform.WindowsX86_64 => false

  /**
   * The canonical artifact-name suffix for this platform's binary.
   * e.g. "linux-x86_64", "macos-aarch64", "macos-x86_64".
   * Windows returns "windows-x86_64" for documentation purposes even
   * though no binary is produced.
   */
  def artifactSuffix: String =
    this match
      case Platform.LinuxX86_64   => "linux-x86_64"
      case Platform.MacosAarch64  => "macos-aarch64"
      case Platform.MacosX86_64   => "macos-x86_64"
      case Platform.WindowsX86_64 => "windows-x86_64"

  /**
   * The GitHub Actions `runs-on` label for this platform's native runner.
   * Cross-compilation / Rosetta is forbidden (R-N4).
   *
   * spec: native-packaging — Scenario: each platform builds on its native runner
   */
  def runnerLabel: String =
    this match
      case Platform.LinuxX86_64   => "ubuntu-latest"
      case Platform.MacosAarch64  => "macos-14"
      case Platform.MacosX86_64   => "macos-13"
      case Platform.WindowsX86_64 => "windows-latest"

object Platform:

  /** The set of platforms that receive a native binary. */
  val committedNativePlatforms: Set[Platform] =
    Set(Platform.LinuxX86_64, Platform.MacosAarch64, Platform.MacosX86_64)

  /** All known platforms (including JAR-fallback-only). */
  val allPlatforms: Set[Platform] =
    Set(Platform.LinuxX86_64, Platform.MacosAarch64, Platform.MacosX86_64, Platform.WindowsX86_64)
