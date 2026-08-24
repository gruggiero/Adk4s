package org.sinemenda.probatio.packaging

import upickle.default.*

/**
 * A single artifact in a release (R-N3).
 *
 * Every release SHALL include: one native binary per committed platform,
 * one assembly JAR, one SHA-256 checksum file per artifact, one SBOM in
 * SPDX JSON format, and one sources JAR.
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 */
enum ReleaseArtifact:
  /** A native binary for a committed platform (linux/macos). */
  case NativeBinary(platform: Platform)

  /** The assembly (fat) JAR — used for JAR-fallback resolution. */
  case AssemblyJar

  /** The sources JAR — for source-availability. */
  case SourcesJar

  /** The SBOM in SPDX JSON format. */
  case Sbom

  /** A SHA-256 checksum file for a named artifact. */
  case Checksum(artifactName: String)

  /**
   * The canonical filename for this artifact in a release.
   * e.g. "probatio-linux-x86_64", "probatio-assembly.jar",
   * "probatio-sbom.spdx.json", "probatio-linux-x86_64.sha256".
   */
  def fileName: String =
    this match
      case ReleaseArtifact.NativeBinary(p) => s"probatio-${p.artifactSuffix}"
      case ReleaseArtifact.AssemblyJar     => "probatio-assembly.jar"
      case ReleaseArtifact.SourcesJar      => "probatio-sources.jar"
      case ReleaseArtifact.Sbom            => "probatio-sbom.spdx.json"
      case ReleaseArtifact.Checksum(name)  => s"$name.sha256"

  /**
   * Whether this artifact is a checksum file (used to distinguish
   * content artifacts from their checksum sidecars).
   */
  def isChecksum: Boolean =
    this match
      case ReleaseArtifact.Checksum(_) => true
      case _                           => false // danger-scan:allow type-rejection — non-matching variant returns false, never a valid value

object ReleaseArtifact:
  given ReadWriter[ReleaseArtifact] = readwriter[String].bimap(
    (a: ReleaseArtifact) => a.toString,
    (s: String) =>
      s match
        case "AssemblyJar" => ReleaseArtifact.AssemblyJar
        case "SourcesJar"  => ReleaseArtifact.SourcesJar
        case "Sbom"        => ReleaseArtifact.Sbom
        case str if str.startsWith("NativeBinary(") =>
          val platStr: String = str.stripPrefix("NativeBinary(").stripSuffix(")")
          ReleaseArtifact.NativeBinary(Platform.valueOf(platStr))
        case str if str.startsWith("Checksum(") =>
          val name: String = str.stripPrefix("Checksum(").stripSuffix(")")
          ReleaseArtifact.Checksum(name)
        case other => sys.error(s"unknown artifact: $other") // danger-scan:allow type-rejection — unknown variant crashes, never maps to valid value
  )
