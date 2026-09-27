package org.sinemenda.probatio.packaging

/**
 * Validates a release manifest for completeness and correctness (R-N3).
 *
 * Checks:
 * - All required artifacts are present (3 binaries, JAR, sources, SBOM, checksums)
 * - No windows-x86_64 native binary
 * - SBOM is present and parseable as SPDX JSON
 * - Checksum files exist for every content artifact
 * - All artifacts are built from CI (not local machine)
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 * spec: native-packaging — Requirement: The release pipeline SHALL be CI-reproducible
 * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
 */
object ReleaseValidator:

  /**
   * Validates that a release manifest contains all required artifacts.
   *
   * Returns a list of validation issues (empty = valid). Each issue
   * names the missing or invalid artifact.
   *
   * spec: native-packaging — Scenario: all required artifacts are present in a release
   */
  def validateCompleteness(manifest: ReleaseManifest): List[String] =
    val artifactSet: Set[ReleaseArtifact] = manifest.artifacts.toSet
    val expected: List[ReleaseArtifact]   = ReleaseManifest.expectedArtifacts
    val missing: List[ReleaseArtifact]    = expected.filterNot(artifactSet.contains)
    missing.map(a => s"missing required artifact: ${a.fileName}")

  /**
   * Validates that platform coverage is complete: one native binary
   * for each committed platform (linux-x86_64, macos-aarch64,
   * macos-x86_64) and NO native binary for windows-x86_64.
   *
   * spec: native-packaging — Property: Platform coverage is complete for committed platforms
   * spec: native-packaging — Scenario: windows-x86_64 release has no native binary, documented (adversarial)
   */
  def validatePlatformCoverage(manifest: ReleaseManifest): List[String] =
    val nativeBinaries: List[Platform]  = manifest.artifacts.collect { case ReleaseArtifact.NativeBinary(p) => p }
    val nativeSet: Set[Platform]        = nativeBinaries.toSet
    val missingPlatforms: Set[Platform] = Platform.committedNativePlatforms.diff(nativeSet)
    val extraPlatforms: Set[Platform]   = nativeSet.diff(Platform.committedNativePlatforms)
    val missingIssues: List[String] =
      missingPlatforms.toList.map(p => s"missing native binary for committed platform: ${p.artifactSuffix}")
    val extraIssues: List[String] =
      extraPlatforms.toList.map(p =>
        s"unexpected native binary for non-committed platform: ${p.artifactSuffix} (JAR-fallback-only)"
      )
    missingIssues ++ extraIssues

  /**
   * Validates that the SBOM is present and parseable as SPDX JSON with
   * a non-empty package name, version matching the release tag, and a
   * non-empty dependency list.
   *
   * spec: native-packaging — Property: SBOM presence and parseability per release
   * spec: native-packaging — Scenario: SBOM is present and parseable as SPDX JSON
   */
  def validateSbom(manifest: ReleaseManifest): List[String] =
    manifest.sbom match
      case None =>
        List("SBOM is missing from release manifest")
      case Some(sbom) =>
        Sbom.validate(sbom, manifest.version)

  /**
   * Validates that every content artifact has a corresponding SHA-256
   * checksum file, that no checksum files exist for non-content
   * artifacts (i.e. no checksum-of-checksum), and that the manifest's
   * recorded checksum map reconciles with the checksum sidecar
   * artifacts: `checksums` maps each content filename to its declared
   * digest, so its key set must correspond exactly to the `Checksum`
   * sidecar artifact names. A checksum sidecar with no recorded digest
   * — or a recorded digest with no checksum sidecar — is an issue.
   *
   * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
   * spec: native-gate-delivery — Scenario: a release that misses an artifact is not complete
   * spec: native-gate-delivery — Scenario: a recorded checksum that differs from the artifact's is not matching
   */
  def validateChecksums(manifest: ReleaseManifest): List[String] =
    val checksumArtifacts: List[ReleaseArtifact.Checksum] = manifest.artifacts.collect {
      case c: ReleaseArtifact.Checksum => c
    }
    val checksumNames: Set[String]              = checksumArtifacts.map(_.artifactName).toSet
    val contentArtifacts: List[ReleaseArtifact] = manifest.artifacts.filterNot(_.isChecksum)
    val contentNames: Set[String]               = contentArtifacts.map(_.fileName).toSet
    val missingChecksums: List[String] = contentNames
      .filterNot(name => name == ReleaseArtifact.Sbom.fileName)
      .filterNot(checksumNames.contains)
      .toList
      .map(name => s"missing checksum for artifact: $name")
    val orphanChecksums: List[String] = checksumNames
      .filterNot(contentNames.contains)
      .toList
      .map(name => s"checksum file has no matching content artifact: $name")
    val recordedNames: Set[String] = manifest.checksums.keySet
    val unrecordedSidecars: List[String] = checksumNames
      .filterNot(recordedNames.contains)
      .toList
      .map(name => s"checksum artifact has no recorded checksum: $name")
    val orphanRecords: List[String] = recordedNames
      .filterNot(checksumNames.contains)
      .toList
      .map(name => s"recorded checksum has no checksum artifact: $name")
    missingChecksums ++ orphanChecksums ++ unrecordedSidecars ++ orphanRecords

  /**
   * Validates that all artifacts are built from CI, not from a local
   * machine. The release pipeline is the sole source of release artifacts.
   *
   * spec: native-packaging — Scenario: artifacts from a local machine are rejected (adversarial)
   */
  def validateCIProvenance(manifest: ReleaseManifest): List[String] =
    if manifest.builtFromCI then Nil
    else List("release artifacts must be built from CI, not a local machine")

  /**
   * Compares one candidate binary's embedded toolchain read against the
   * tested identity. `Found` with a different identity is a `Rejected`
   * naming both; `Unreadable` is an `Undetermined` naming the binary —
   * could-not-determine, never a pass.
   *
   * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
   * spec: finish-probatio-replacement/delivery-verified — Scenario: Error path — an unreadable toolchain identity is could-not-determine
   * spec: finish-probatio-replacement/delivery-verified — Property: toolchain-check-accepts-iff-identical
   */
  def toolchainVerdict(
      tested: ToolchainIdentity,
      candidate: ToolchainIdentity.Embedded
  ): ToolchainVerdict = ???

  /**
   * Validates that every native binary in the manifest carries an
   * embedded toolchain identity identical to the tested one. A binary
   * whose read is `Unreadable`, or a native binary with no read at all,
   * is an issue naming the binary — the candidate is not accepted.
   *
   * spec: finish-probatio-replacement/delivery-verified — Scenario: Happy path — a candidate built with the tested toolchain is accepted
   * spec: finish-probatio-replacement/delivery-verified — Scenario: Adversarial — a candidate built with a different toolchain is rejected
   */
  def validateToolchain(
      manifest: ReleaseManifest,
      tested: ToolchainIdentity
  ): List[String] = ???

  /**
   * Runs all validations and returns the combined list of issues.
   * An empty list means the manifest is fully valid. The release check
   * requires the tested toolchain identity — it cannot run without one,
   * and every `accepted`/`rejected`/`undetermined` verdict names the
   * identity it compared against.
   */
  def validateAll(manifest: ReleaseManifest, tested: ToolchainIdentity): List[String] =
    validateCompleteness(manifest) ++
      validatePlatformCoverage(manifest) ++
      validateSbom(manifest) ++
      validateChecksums(manifest) ++
      validateCIProvenance(manifest) ++
      validateToolchain(manifest, tested)
