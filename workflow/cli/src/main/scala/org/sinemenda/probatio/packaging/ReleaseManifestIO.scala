package org.sinemenda.probatio.packaging

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow typed-catch — a corrupt release directory maps to a named Left, never a fake manifest

/**
 * Builds a `ReleaseManifest` from a directory of downloaded release
 * artifacts (R-N3). This is the bridge between the release step —
 * which produces files on disk — and `ReleaseValidator`, which
 * validates a typed manifest.
 *
 * Filename → artifact mapping:
 *  - `X.sha256`                → `Checksum(X)`, and `checksums(X)` =
 *    the digest recorded in the file
 *  - `probatio-assembly.jar`   → `AssemblyJar`
 *  - `probatio-sources.jar`    → `SourcesJar`
 *  - `probatio-sbom.spdx.json` → `Sbom` (file parsed via `Sbom.parseJson`)
 *  - `probatio-<suffix>` where `<suffix>` is a platform artifact suffix
 *    → `NativeBinary(platform)`
 *  - anything else             → not an artifact; ignored
 *
 * A `.sha256` file's recorded digest is the first whitespace-separated
 * token — the `sha256sum` output format is `<digest>  <filename>`.
 * The token must be a 64-character hex digest, and when the content
 * file `X` is present its recomputed SHA-256 must equal the recorded
 * digest — a stale or corrupted sidecar fails the build rather than
 * constructing a manifest that cannot detect the mismatch.
 *
 * spec: native-gate-delivery — Requirement: A release SHALL be complete before it is delivered
 * spec: native-gate-delivery — Scenario: Error path — a manifest whose recorded checksum differs from the artifact's is detected
 */
object ReleaseManifestIO:

  /**
   * Reads `dir` and builds the release manifest for `version`.
   *
   * Returns `Left` when the directory does not exist, an artifact file
   * cannot be read, a sidecar records no hex digest, a recorded digest
   * does not match its content file's bytes, or the SBOM file is
   * present but unparseable — failures that mean the manifest itself
   * cannot be trusted. Completeness issues (a missing artifact, an
   * orphan sidecar) are reported by `ReleaseValidator.validateAll` on
   * the returned manifest, not here.
   */
  def fromDirectory(
    dir: Path,
    version: String,
    builtFromCI: Boolean
  ): Either[String, ReleaseManifest] =
    if !Files.isDirectory(dir) then
      Left(s"release artifacts directory does not exist: ${dir.toAbsolutePath}")
    else
      try
        val files: List[Path] =
          Using.resource(Files.list(dir)) { stream =>
            stream.iterator().asScala.toList.filter(Files.isRegularFile(_))
          }
        val artifacts: List[ReleaseArtifact] =
          files.flatMap(f => artifactFor(f.getFileName.toString))
        val checksums: Either[String, Map[String, String]] =
          artifacts
            .collect { case c: ReleaseArtifact.Checksum => c }
            .foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) { (acc, c) =>
              acc.flatMap { m =>
                recordedDigest(dir.resolve(c.fileName), dir.resolve(c.artifactName))
                  .map(digest => m + (c.artifactName -> digest))
              }
            }
        val sbomPath: Path = dir.resolve(ReleaseArtifact.Sbom.fileName)
        val sbom: Either[String, Option[Sbom]] =
          if Files.isRegularFile(sbomPath) then
            Sbom
              .parseJson(Files.readString(sbomPath, StandardCharsets.UTF_8))
              .map(sbom => Some(sbom))
              .left
              .map(err => s"SBOM file present but unparseable: $err")
          else Right(None)
        for
          s <- sbom
          c <- checksums
        yield ReleaseManifest(
          version = version,
          artifacts = artifacts,
          checksums = c,
          sbom = s,
          builtFromCI = builtFromCI
        )
      catch case NonFatal(e) => // danger-scan:allow typed-catch — a corrupt release directory maps to a named Left
        Left(s"release artifacts directory could not be read: ${e.getMessage}")

  private def artifactFor(fileName: String): Option[ReleaseArtifact] =
    if fileName.endsWith(".sha256") then
      Some(ReleaseArtifact.Checksum(fileName.stripSuffix(".sha256")))
    else
      fileName match
        case "probatio-assembly.jar"   => Some(ReleaseArtifact.AssemblyJar)
        case "probatio-sources.jar"    => Some(ReleaseArtifact.SourcesJar)
        case "probatio-sbom.spdx.json" => Some(ReleaseArtifact.Sbom)
        case binary if binary.startsWith("probatio-") =>
          val suffix: String = binary.stripPrefix("probatio-")
          Platform.allPlatforms
            .find(_.artifactSuffix == suffix)
            .map(ReleaseArtifact.NativeBinary.apply)
        case _ => None // danger-scan:allow type-rejection — a non-artifact filename is filtered out, never mapped to a valid artifact

  /**
   * The digest a `.sha256` sidecar records — the first whitespace token,
   * verified to be a 64-char hex string and, when the content file is
   * present, to equal the recomputed SHA-256 of its bytes. A sidecar
   * whose recorded digest differs from the artifact's is detected here
   * rather than published.
   */
  private def recordedDigest(sidecar: Path, content: Path): Either[String, String] =
    val token: String =
      Files
        .readString(sidecar, StandardCharsets.UTF_8)
        .takeWhile(c => !c.isWhitespace)
    if !isSha256Hex(token) then
      Left(s"${sidecar.getFileName} does not record a sha256 digest (first token: '$token')")
    else if Files.isRegularFile(content) then
      val actual: String = ChecksumVerifier.computeSha256(Files.readAllBytes(content))
      if actual == token then Right(token)
      else
        Left(
          s"${sidecar.getFileName} records $token but ${content.getFileName} hashes to $actual — " +
            "a recorded checksum differs from the artifact's"
        )
    else Right(token) // absent content file — the validator reports the orphan

  private def isSha256Hex(s: String): Boolean =
    s.length == 64 && s.forall(c => c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
