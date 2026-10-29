package org.sinemenda.probatio.packaging

import upickle.default.*

/**
 * SPDX JSON SBOM model (R-N3).
 *
 * Every release SHALL include one SBOM in SPDX JSON format with a
 * non-empty package name, a version matching the release tag, and a
 * non-empty dependency list.
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 * spec: native-packaging — Property: SBOM presence and parseability per release
 * spec: native-packaging — Scenario: SBOM is present and parseable as SPDX JSON
 */
case class Sbom(
  spdxVersion: String,
  spdxId: String,
  name: String,
  version: String,
  downloadLocation: String,
  filesAnalyzed: Boolean,
  packageVerificationCode: String,
  licenseConcluded: String,
  licenseDeclared: String,
  copyrightText: String,
  dependencies: List[SbomPackage]
) derives ReadWriter

case class SbomPackage(
  name: String,
  version: String,
  downloadLocation: String
) derives ReadWriter

object Sbom:

  /** The SPDX version string used by all probatio SBOMs. */
  val spdxVersionValue: String = "SPDX-2.3"

  /**
   * Renders this SBOM as SPDX JSON text.
   *
   * The output is valid JSON parseable by any SPDX-2.3 consumer.
   */
  def renderJson(sbom: Sbom): String =
    write(sbom)

  /**
   * Parses SPDX JSON text into an Sbom model.
   *
   * Returns Left(error) if the JSON is malformed or missing required fields.
   */
  def parseJson(json: String): Either[String, Sbom] =
    try Right(read[Sbom](json))
    catch case e: Exception => Left(e.getMessage)

  /**
   * Validates that an Sbom is well-formed per R-N3:
   * - non-empty package name
   * - version matching the release tag
   * - non-empty dependency list
   *
   * spec: native-packaging — Scenario: SBOM is present and parseable as SPDX JSON
   */
  def validate(sbom: Sbom, releaseVersion: String): List[String] =
    val nameIssue: List[String] =
      if sbom.name.isEmpty then List("SBOM package name is empty") else Nil
    val versionIssue: List[String] =
      if sbom.version != releaseVersion then
        List(s"SBOM version '${sbom.version}' does not match release tag '$releaseVersion'")
      else Nil
    val depsIssue: List[String] =
      if sbom.dependencies.isEmpty then List("SBOM dependency list is empty") else Nil
    val spdxVersionIssue: List[String] =
      if sbom.spdxVersion != spdxVersionValue then
        List(s"SBOM spdxVersion '${sbom.spdxVersion}' does not match expected '$spdxVersionValue'")
      else Nil
    val spdxIdIssue: List[String] =
      if sbom.spdxId.isEmpty then List("SBOM spdxId is empty") else Nil
    nameIssue ++ versionIssue ++ depsIssue ++ spdxVersionIssue ++ spdxIdIssue

  /**
   * Constructs a valid SBOM for a release version with the given
   * dependency list. Convenience factory for CI pipeline use.
   */
  def forRelease(
    releaseVersion: String,
    dependencies: List[SbomPackage]
  ): Sbom =
    Sbom(
      spdxVersion = spdxVersionValue,
      spdxId = "SPDXRef-PROBATIO",
      name = "probatio",
      version = releaseVersion,
      downloadLocation = "NOASSERTION",
      filesAnalyzed = false,
      packageVerificationCode = "NOASSERTION",
      licenseConcluded = "NOASSERTION",
      licenseDeclared = "NOASSERTION",
      copyrightText = "NOASSERTION",
      dependencies = dependencies
    )
