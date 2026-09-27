package org.sinemenda.probatio.packaging

import org.sinemenda.probatio.cli.ProbatioCliSuite

import java.nio.file.Path

/**
 * Typed contract for spec: finish-probatio-replacement/delivery-verified
 * (Step 1).
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * public signatures via eta-expanded references and evaluates the pure
 * projections. Zero runtime cost; any later signature drift breaks
 * `probatio-cli/Test/compile`. The spec-7 compile-negatives live in
 * `NativeGateDeliveryTypeContract` (the spec's proof-obligation table
 * names it the compile-negative artifact).
 *
 * spec: finish-probatio-replacement/delivery-verified — Step 1: typed contract
 * spec: finish-probatio-replacement/delivery-verified — Concepts Introduced: ToolchainIdentity
 * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
 */
final class DeliveryVerifiedTypeContract extends ProbatioCliSuite:

  // ── Signature pins (eta-expanded against the real implementation) ───────
  // These pins make "signatures stay as approved" compiler-checked.

  // ToolchainIdentity.parse: String => Option[ToolchainIdentity]
  val parseSig: String => Option[ToolchainIdentity] =
    ToolchainIdentity.parse

  // ToolchainIdentity.Version.parse: String => Option[Version]
  val versionParseSig: String => Option[ToolchainIdentity.Version] =
    ToolchainIdentity.Version.parse

  // ToolchainIdentity.readEmbedded: (String, Array[Byte]) => Embedded
  val readEmbeddedSig: (String, Array[Byte]) => ToolchainIdentity.Embedded =
    ToolchainIdentity.readEmbedded

  // ReleaseValidator.toolchainVerdict: (ToolchainIdentity, Embedded) => ToolchainVerdict
  val toolchainVerdictSig
      : (ToolchainIdentity, ToolchainIdentity.Embedded) => ToolchainVerdict =
    ReleaseValidator.toolchainVerdict

  // ReleaseValidator.validateToolchain: (ReleaseManifest, ToolchainIdentity) => List[String]
  val validateToolchainSig: (ReleaseManifest, ToolchainIdentity) => List[String] =
    ReleaseValidator.validateToolchain

  // ReleaseValidator.validateAll: (ReleaseManifest, ToolchainIdentity) => List[String]
  // — the tested identity is mandatory; there is no 1-arg overload.
  val validateAllSig: (ReleaseManifest, ToolchainIdentity) => List[String] =
    ReleaseValidator.validateAll

  // ReleaseCheck.run: (Path, String, Boolean, ToolchainIdentity) => Either[String, String]
  // — the release check cannot run without the tested identity.
  val runSig: (Path, String, Boolean, ToolchainIdentity) => Either[String, String] =
    ReleaseCheck.run

  /** A version that must parse — the None arm is a test failure, never a default. */
  private def version(s: String): ToolchainIdentity.Version =
    ToolchainIdentity.Version.parse(s) match
      case Some(v) => v
      case None    => fail(s"'$s' must parse as a toolchain version")

  // ── ToolchainIdentity — Concepts Introduced ─────────────────────────────
  // spec: finish-probatio-replacement/delivery-verified — Concepts Introduced: ToolchainIdentity
  test("ToolchainIdentity is a final case class carrying distribution and version"):
    val v: ToolchainIdentity.Version = version("21.0.2")
    val t: ToolchainIdentity = ToolchainIdentity("GraalVM CE", v)
    val d: String                    = t.distribution
    val ver: ToolchainIdentity.Version = t.version
    assertEquals(d, "GraalVM CE")
    assertEquals(ver.value, "21.0.2")
    assertEquals(t, ToolchainIdentity("GraalVM CE", v))

  test("Version.parse rejects only the empty string"):
    assertEquals(
      ToolchainIdentity.Version.parse("").isEmpty,
      true,
      "an empty version is not a version"
    )
    assertEquals(
      ToolchainIdentity.Version.parse("21.0.2").map(_.value),
      Some("21.0.2")
    )
    assertEquals(
      ToolchainIdentity.Version.parse(" ").map(_.value),
      Some(" "),
      "non-empty is the only constraint — whitespace is a recorded version, not absence"
    )

  // ── Embedded — the read result, carrying the binary name ────────────────
  test("Embedded projections: Found is readable and carries identity + binary"):
    val v: ToolchainIdentity.Version = version("21.0.2")
    val t: ToolchainIdentity = ToolchainIdentity("GraalVM CE", v)
    val found: ToolchainIdentity.Embedded =
      ToolchainIdentity.Embedded.Found("probatio-linux-x86_64", t)
    assertEquals(found.binary, "probatio-linux-x86_64")
    assertEquals(found.readable, true)
    assertEquals(found.identityOption, Some(t))

  test("Embedded projections: Unreadable names the binary and carries no identity"):
    val un: ToolchainIdentity.Embedded =
      ToolchainIdentity.Embedded.Unreadable("probatio-macos-aarch64", "no GraalVM marker found")
    assertEquals(un.binary, "probatio-macos-aarch64")
    assertEquals(un.readable, false)
    assertEquals(un.identityOption, None)

  // ── ToolchainVerdict — the verdict that names both halves ───────────────
  test("ToolchainVerdict: only Accepted is accepted; Rejected names tested and candidate"):
    val v: ToolchainIdentity.Version = version("21.0.2")
    val t: ToolchainIdentity      = ToolchainIdentity("GraalVM CE", v)
    val other: ToolchainIdentity  = ToolchainIdentity("GraalVM CE", version("22.3.1"))

    val accepted: ToolchainVerdict     = ToolchainVerdict.Accepted("p-linux", t)
    val rejected: ToolchainVerdict     = ToolchainVerdict.Rejected("p-linux", t, other)
    val undetermined: ToolchainVerdict = ToolchainVerdict.Undetermined("p-linux", "no marker")

    assertEquals(accepted.accepted, true)
    assertEquals(rejected.accepted, false)
    assertEquals(undetermined.accepted, false)

    def name(vd: ToolchainVerdict): String = vd match
      case ToolchainVerdict.Accepted(_, _)      => "accepted"
      case ToolchainVerdict.Rejected(_, _, _)   => "rejected"
      case ToolchainVerdict.Undetermined(_, _)  => "undetermined"

    assertEquals(name(accepted), "accepted")
    assertEquals(name(rejected), "rejected")
    assertEquals(name(undetermined), "undetermined")

  // ── ReleaseManifest carries the toolchain reads ──────────────────────────
  test("ReleaseManifest carries one Embedded read list alongside the artifact set"):
    val v: ToolchainIdentity.Version = version("21.0.2")
    val t: ToolchainIdentity = ToolchainIdentity("GraalVM CE", v)
    val reads: List[ToolchainIdentity.Embedded] =
      List(ToolchainIdentity.Embedded.Found("probatio-linux-x86_64", t))
    val m: ReleaseManifest = ReleaseManifest(
      version = "v1",
      artifacts = List.empty,
      checksums = Map.empty,
      sbom = None,
      builtFromCI = false,
      toolchains = reads
    )
    val ts: List[ToolchainIdentity.Embedded] = m.toolchains
    assertEquals(ts, reads)
    assertEquals(
      m.copy(toolchains = List.empty).toolchains,
      List.empty,
      "the toolchain field is part of the manifest's value"
    )
