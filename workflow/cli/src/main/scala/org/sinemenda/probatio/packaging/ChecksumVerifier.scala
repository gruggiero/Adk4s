package org.sinemenda.probatio.packaging

import java.security.MessageDigest

/**
 * SHA-256 checksum computation and verification (R-N3).
 *
 * The installation task SHALL verify the SHA-256 checksum of the
 * downloaded binary before executing it for the first time; if the
 * checksum does not match, installation SHALL fail and the binary
 * SHALL NOT be executed.
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 * spec: native-packaging — Property: SHA-256 checksum round-trip
 * spec: native-packaging — Property: Checksum verification gates first execution
 */
object ChecksumVerifier:

  /**
   * Computes the SHA-256 hex digest of the given bytes.
   *
   * Returns a lowercase hex string (64 characters, no separators).
   * Edge cases: empty array → SHA-256 of empty input (a valid hash).
   */
  def computeSha256(bytes: Array[Byte]): String =
    val md: MessageDigest   = MessageDigest.getInstance("SHA-256")
    val digest: Array[Byte] = md.digest(bytes)
    digest.map(b => f"$b%02x").mkString

  /**
   * Verifies that the SHA-256 of the given bytes matches the expected
   * checksum. The expected checksum is trimmed before comparison
   * (handles trailing whitespace/newlines in checksum files).
   *
   * Returns true iff `computeSha256(bytes) == expected.trim`.
   */
  def verify(bytes: Array[Byte], expected: String): Boolean =
    computeSha256(bytes) == expected.trim

  /**
   * Verifies a checksum and returns a result indicating whether the
   * binary may be executed. A mismatch always results in a failure
   * with no execution — the binary SHALL NOT be executed.
   *
   * spec: native-packaging — Scenario: checksum mismatch blocks first execution (adversarial)
   */
  def verifyForExecution(
    bytes: Array[Byte],
    expected: String,
    artifactName: String
  ): ChecksumResult =
    val actual: String = computeSha256(bytes)
    if actual == expected.trim then ChecksumResult.Proceed
    else ChecksumResult.Mismatch(artifactName, expected, actual)

/**
 * Result of a checksum verification for execution gating.
 *
 * `Proceed` means the checksum matched and the binary may be executed.
 * `Mismatch` means the checksum did not match — the binary SHALL NOT
 * be executed, and the error names the expected and actual checksums.
 */
enum ChecksumResult:
  case Proceed
  case Mismatch(artifactName: String, expected: String, actual: String)
