package org.sinemenda.probatio.packaging

import upickle.default._

/**
 * A complete release manifest — the set of artifacts published for a
 * release version (R-N3, R-N4).
 *
 * Every release SHALL include: one native binary per committed platform,
 * one assembly JAR, one SHA-256 checksum file per artifact, one SBOM,
 * and one sources JAR. The release pipeline SHALL be CI-reproducible —
 * the same git tag with the same toolchain produces byte-identical
 * binaries and therefore byte-identical checksums.
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 * spec: native-packaging — Requirement: The release pipeline SHALL be CI-reproducible
 * spec: native-packaging — Property: Platform coverage is complete for committed platforms
 */
case class ReleaseManifest(
    version: String,
    artifacts: List[ReleaseArtifact],
    checksums: Map[String, String],
    sbom: Option[Sbom],
    builtFromCI: Boolean
) derives ReadWriter

object ReleaseManifest:

  /**
   * The expected artifact set for a complete release: three native
   * binaries (linux-x86_64, macos-aarch64, macos-x86_64), one assembly
   * JAR, one sources JAR, one SBOM, and one checksum file per content
   * artifact (5 checksums: 3 binaries + 1 JAR + 1 sources).
   *
   * Windows-x86_64 is NOT in this set — it is JAR-fallback-only.
   *
   * spec: native-packaging — Scenario: all required artifacts are present in a release
   */
  def expectedArtifacts: List[ReleaseArtifact] =
    val binaries: List[ReleaseArtifact] =
      Platform.committedNativePlatforms.toList.map(ReleaseArtifact.NativeBinary.apply)
    val contentArtifacts: List[ReleaseArtifact] =
      binaries ++ List(ReleaseArtifact.AssemblyJar, ReleaseArtifact.SourcesJar, ReleaseArtifact.Sbom)
    val checksumArtifacts: List[ReleaseArtifact] =
      contentArtifacts.filterNot(_ == ReleaseArtifact.Sbom).map { a =>
        ReleaseArtifact.Checksum(a.fileName)
      }
    contentArtifacts ++ checksumArtifacts

  /**
   * The expected checksum files — one per content artifact (not per
   * checksum, since checksums don't have checksums).
   */
  def expectedChecksums: List[ReleaseArtifact] =
    expectedArtifacts.filter(_.isChecksum)
