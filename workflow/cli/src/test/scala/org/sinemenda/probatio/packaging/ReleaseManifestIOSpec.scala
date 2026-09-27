package org.sinemenda.probatio.packaging

import hedgehog.Gen
import hedgehog.Result
import org.sinemenda.probatio.cli.ProbatioCliSuite
import upickle.default.write as writeJson

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Direct tests for `ReleaseManifestIO.fromDirectory` and `ReleaseCheck` —
 * the wired release-step gate (native-gate-delivery).
 *
 * Covers the filename → artifact mapping, the recorded-digest ↔
 * artifact-bytes reconciliation (a stale sidecar must fail the build),
 * and the malformed/missing input paths.
 *
 * spec: native-gate-delivery — Requirement: A release SHALL be complete before it is delivered
 * spec: native-gate-delivery — Scenario: Error path — a manifest whose recorded checksum differs from the artifact's is detected
 * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
 * spec: finish-probatio-replacement/delivery-verified — Property: toolchain-check-accepts-iff-identical
 */
final class ReleaseManifestIOSpec extends ProbatioCliSuite:

  /** The identity the synthetic release binaries are stamped with. */
  private val testedToolchain: ToolchainIdentity =
    ToolchainIdentity("GraalVM CE", version("21.0.2"))

  /** `testedToolchain` in embedded-marker form — the same toolchain in both vocabularies. */
  private val testedMarker: String = "GraalVM 21.0.2 Java 21 CE"

  /** A version that must parse — the None arm is a test failure, never a default. */
  private def version(s: String): ToolchainIdentity.Version =
    ToolchainIdentity.Version.parse(s) match
      case Some(v) => v
      case None    => fail(s"'$s' must parse as a toolchain version")

  // The closed sets `genToolchainPair` draws from (declared before the
  // first test block so the init checker sees them as initialized).
  private val toolchainDistributions: List[String] = List("GraalVM CE", "GraalVM EE", "Temurin")
  private val toolchainVersions: List[String]      = List("21.0.2", "22.3.1", "17.0.9")
  private val binaryNames: List[String] =
    Platform.committedNativePlatforms.toList.map(p => s"probatio-${p.artifactSuffix}")

  private def withTempDir(f: Path => Unit): Unit =
    val dir: Path = Files.createTempDirectory("probatio-release")
    try f(dir)
    finally // scalafix:ok DisableSyntax.NoKeywordFinally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => { val _: Boolean = p.toFile.delete(); () })

  private def write(dir: Path, name: String, content: String): Unit =
    Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8)
    ()

  private def writeSidecar(dir: Path, artifactName: String): Unit =
    val bytes: Array[Byte] = Files.readAllBytes(dir.resolve(artifactName))
    write(dir, s"$artifactName.sha256", s"${ChecksumVerifier.computeSha256(bytes)}  $artifactName\n")

  /**
   * Writes a complete release directory: 5 content artifacts + sbom +
   * 5 sidecars. A complete release is toolchain-conformant — the
   * native binaries carry the tested toolchain's embedded marker; a
   * marker-free release is written by `writeMarkerFreeRelease`.
   */
  private def writeCompleteRelease(dir: Path, version: String): Unit =
    writeReleaseWithMarker(dir, version, testedMarker)

  /** Writes a complete release whose native binaries carry no toolchain marker. */
  private def writeMarkerFreeRelease(dir: Path, version: String): Unit =
    val contentNames: List[String] = List(
      "probatio-linux-x86_64",
      "probatio-macos-aarch64",
      "probatio-macos-x86_64",
      "probatio-assembly.jar",
      "probatio-sources.jar"
    )
    contentNames.foreach(n => write(dir, n, s"content-of-$n"))
    val sbom: Sbom = Sbom.forRelease(version, List(SbomPackage("upickle", "4.4.3", "Maven")))
    write(dir, "probatio-sbom.spdx.json", writeJson[Sbom](sbom))
    contentNames.foreach(n => writeSidecar(dir, n))

  test("a complete release directory builds a manifest with all artifacts and recorded checksums"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Right(manifest) =>
          assert(
            ReleaseManifest.expectedArtifacts.forall(manifest.artifacts.contains),
            s"missing artifacts: ${ReleaseManifest.expectedArtifacts.diff(manifest.artifacts)}"
          )
          assertEquals(manifest.checksums.size, 5, "one recorded digest per content artifact")
          assert(manifest.sbom.isDefined, "SBOM must be parsed")
          assert(ReleaseValidator.validateAll(manifest, testedToolchain).isEmpty,
                 s"complete release must validate: ${ReleaseValidator.validateAll(manifest, testedToolchain)}")
        case Left(err) => fail(s"complete release must build: $err")
    }

  test("a sidecar whose recorded digest differs from the artifact's bytes is detected"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      // Corrupt the content after its sidecar was recorded
      write(dir, "probatio-assembly.jar", "corrupted-content")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Left(err) =>
          assert(err.contains("probatio-assembly.jar.sha256"), s"must name the stale sidecar: $err")
        case Right(_) =>
          fail("a stale sidecar must fail the manifest build — the mismatch is undetectable downstream")
    }

  test("a sidecar with a non-digest first token fails the build"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      write(dir, "probatio-sources.jar.sha256", "SHA256 (probatio-sources.jar) = abcd")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Left(err) =>
          assert(err.contains("probatio-sources.jar.sha256"), s"must name the malformed sidecar: $err")
        case Right(_) => fail("a non-digest sidecar token must not be recorded as a checksum")
    }

  test("a missing directory reports could-not-build"):
    val missing: Path = Path.of("/nonexistent-probatio-release-dir")
    ReleaseManifestIO.fromDirectory(missing, "v14.0.0", builtFromCI = true) match
      case Left(err) => assert(err.contains("does not exist"), s"must name the directory: $err")
      case Right(_)  => fail("a missing directory must not build a manifest")

  test("an undecodable sidecar reports could-not-be-read"):
    withTempDir { dir =>
      // Invalid UTF-8: readString throws inside the manifest build, which
      // must surface as a named Left, not an exception.
      val _: Path = Files.write(
        dir.resolve("probatio-assembly.jar.sha256"),
        Array[Byte](0xff.toByte, 0xfe.toByte, 0x00.toByte)
      )
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Left(err) =>
          assert(err.contains("could not be read"), s"the read failure is named: $err")
        case Right(_) =>
          fail("an undecodable sidecar must not produce a manifest")
    }

  test("an orphan sidecar builds but the manifest is incomplete"):
    withTempDir { dir =>
      write(dir, "probatio-ghost.bin.sha256", s"${"a" * 64}  probatio-ghost.bin\n")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Right(manifest) =>
          assert(manifest.artifacts.contains(ReleaseArtifact.Checksum("probatio-ghost.bin")))
          assert(manifest.checksums.contains("probatio-ghost.bin"))
          assert(
            ReleaseValidator.validateAll(manifest, testedToolchain).nonEmpty,
            "an orphan sidecar leaves the release incomplete"
          )
        case Left(err) => fail(s"orphan sidecar is a completeness issue, not a build failure: $err")
    }

  test("ReleaseCheck fails on an incomplete release directory"):
    withTempDir { dir =>
      write(dir, "probatio-assembly.jar", "content")
      val thrown: Boolean =
        try
          ReleaseCheck.main(Array(dir.toString, "v14.0.0", "GraalVM CE/21.0.2"))
          false
        catch case e: Exception => e.getMessage.contains("release")
      assert(thrown, "an incomplete release must fail the release step")
    }

  test("ReleaseCheck rejects wrong argument counts"):
    val thrown: Boolean =
      try
        ReleaseCheck.main(Array("only-one-arg"))
        false
      catch case e: Exception => e.getMessage.contains("usage")
    assert(thrown, "wrong arity must report usage")

  // ── Digest-token boundary table ─────────────────────────────────────
  // The recorded token must be exactly 64 hex chars. Boundary mutants on
  // the char predicate (`<=`→`<`, `>=`→`>`, `&&`→`||`, `forall`→`exists`)
  // survive unless each boundary character is exercised both ways.

  private def sidecarOnly(dir: Path, token: String): Either[String, ReleaseManifest] =
    write(dir, "probatio-ghost.bin.sha256", s"$token  probatio-ghost.bin\n")
    ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true)

  test("a sidecar records a digest only at the exact 64-hex boundary"):
    withTempDir { dir =>
      List("f" * 64, "F" * 64, "a" * 64, "A" * 64, "0" * 64, "9" * 64).foreach { token =>
        sidecarOnly(dir, token) match
          case Right(manifest) =>
            assertEquals(
              manifest.checksums.get("probatio-ghost.bin"),
              Some(token),
              s"valid boundary digest must be recorded: $token"
            )
          case Left(err) => fail(s"a valid 64-hex token must be accepted ('$token'): $err")
      }
    }

  test("a sidecar token outside the hex alphabet or off the length boundary is rejected"):
    withTempDir { dir =>
      List(
        "g" * 64,                    // just past 'f'
        "G" * 64,                    // just past 'F'
        "`" * 64,                    // just below 'a'
        "@" * 64,                    // just below 'A'
        "/" * 64,                    // just below '0'
        ":" * 64,                    // just above '9'
        "z" * 64,                    // lowercase non-hex
        "0" + "g" * 63,              // one hex char among non-hex (forall≠exists)
        "a" * 63,                    // under the length boundary
        "a" * 65                     // over the length boundary
      ).foreach { token =>
        sidecarOnly(dir, token) match
          case Left(err) =>
            assert(
              err.contains("does not record a sha256 digest"),
              s"an invalid token must be named ('$token'): $err"
            )
          case Right(_) =>
            fail(s"a non-hex/off-length token must not be recorded as a checksum: '$token'")
      }
    }

  test("a digest mismatch names that the recorded checksum differs from the artifact's"):
    withTempDir { dir =>
      write(dir, "probatio-assembly.jar", "real-content")
      write(dir, "probatio-assembly.jar.sha256", s"${"0" * 64}  probatio-assembly.jar\n")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Left(err) =>
          assert(
            err.contains("differs from the artifact's"),
            s"the mismatch must be named as a recorded-checksum divergence: $err"
          )
        case Right(_) => fail("a recorded digest that differs from the bytes must fail the build")
    }

  test("a filename that merely equals a platform suffix is not a native binary"):
    withTempDir { dir =>
      write(dir, "linux-x86_64", "content")
      write(dir, "probatio-unknown-suffix", "content")
      write(dir, "README.md", "notes")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Right(manifest) =>
          assertEquals(
            manifest.artifacts,
            List.empty,
            "bare suffixes, unknown probatio- names, and non-artifacts are all ignored"
          )
        case Left(err) => fail(s"non-artifact files must be ignored, not fatal: $err")
    }

  test("a present-but-unparseable SBOM fails the manifest build"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      write(dir, "probatio-sbom.spdx.json", "this is not spdx json")
      ReleaseManifestIO.fromDirectory(dir, "v14.0.0", builtFromCI = true) match
        case Left(err) =>
          assert(err.contains("unparseable"), s"the unparseable SBOM must be named: $err")
        case Right(_) => fail("a corrupt SBOM file must not produce a manifest")
    }

  // ── ReleaseValidator issue text — each issue names its defect ───────

  test("validateAll issues name their defect, not a blank string"):
    // Missing everything.
    val empty: ReleaseManifest =
      ReleaseManifest(
        "v1",
        artifacts = List.empty,
        checksums = Map.empty,
        sbom = None,
        builtFromCI = false,
        toolchains = List.empty
      )
    val issues: List[String] = ReleaseValidator.validateAll(empty, testedToolchain)
    assert(issues.exists(_.contains("missing required artifact: probatio-linux-x86_64")), s"$issues")
    assert(issues.exists(_.contains("missing native binary for committed platform: linux-x86_64")), s"$issues")
    assert(issues.exists(_.contains("SBOM is missing from release manifest")), s"$issues")
    assert(issues.exists(_.contains("built from CI")), s"$issues")
    // A content artifact present without its sidecar.
    val noSidecar: ReleaseManifest = empty.copy(
      artifacts = List(ReleaseArtifact.AssemblyJar),
      builtFromCI = true
    )
    val sidecarIssues: List[String] = ReleaseValidator.validateChecksums(noSidecar)
    assert(
      sidecarIssues.exists(_.contains("missing checksum for artifact: probatio-assembly.jar")),
      s"$sidecarIssues"
    )
    // Orphan sidecar + orphan recorded digest.
    val orphaned: ReleaseManifest = empty.copy(
      artifacts = List(ReleaseArtifact.Checksum("ghost.bin")),
      checksums = Map("phantom.bin" -> "a" * 64),
      builtFromCI = true
    )
    val orphanIssues: List[String] = ReleaseValidator.validateChecksums(orphaned)
    assert(
      orphanIssues.exists(_.contains("checksum file has no matching content artifact: ghost.bin")),
      s"$orphanIssues"
    )
    assert(
      orphanIssues.exists(_.contains("checksum artifact has no recorded checksum: ghost.bin")),
      s"$orphanIssues"
    )
    assert(
      orphanIssues.exists(_.contains("recorded checksum has no checksum artifact: phantom.bin")),
      s"$orphanIssues"
    )
    // A native binary for a JAR-fallback-only platform.
    val extra: ReleaseManifest = empty.copy(
      artifacts = List(ReleaseArtifact.NativeBinary(Platform.WindowsX86_64)),
      builtFromCI = true
    )
    val coverageIssues: List[String] = ReleaseValidator.validatePlatformCoverage(extra)
    assert(
      coverageIssues.exists(_.contains("unexpected native binary for non-committed platform: windows-x86_64")),
      s"$coverageIssues"
    )

  // ── ReleaseCheck seams ──────────────────────────────────────────────

  test("isCIEnvironment observes CI=true exactly"):
    assert(ReleaseCheck.isCIEnvironment(Map("CI" -> "true").get), "CI=true is a CI environment")
    assert(!ReleaseCheck.isCIEnvironment(Map.empty[String, String].get), "absent CI is not a CI environment")
    assert(!ReleaseCheck.isCIEnvironment(Map("CI" -> "1").get), "CI=1 is not the CI=true contract")
    assert(!ReleaseCheck.isCIEnvironment(Map("CI" -> "TRUE").get), "the value match is exact")

  test("ReleaseCheck.run reports completion for a valid CI-built release"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = true, testedToolchain) match
        case Right(report) =>
          assert(report.contains("release manifest complete"), s"the completion is reported: $report")
          assert(report.contains("v14.0.0"), s"the version is named: $report")
        case Left(err) => fail(s"a complete CI release must pass: $err")
    }

  test("ReleaseCheck.run blocks a complete release not built from CI"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = false, testedToolchain) match
        case Left(err) =>
          assert(err.contains("release manifest incomplete"), s"the refusal is named: $err")
          assert(err.contains("built from CI"), s"the provenance issue is listed: $err")
          assert(err.contains("  - "), s"each issue is listed as a bullet: $err")
        case Right(_) => fail("a locally-built manifest must not be delivered")
    }

  test("ReleaseCheck.run lists every issue on its own line"):
    withTempDir { dir =>
      write(dir, "probatio-assembly.jar", "content")
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = true, testedToolchain) match
        case Left(err) =>
          assert(
            err.count(_ == '\n') >= 2,
            s"multiple issues must be joined by newlines, not concatenated: $err"
          )
        case Right(_) => fail("a partial release must not be delivered")
    }

  test("ReleaseCheck.run names an unbuildable manifest"):
    ReleaseCheck.run(Path.of("/nonexistent-probatio-release-dir"), "v1", builtFromCI = true, testedToolchain) match
      case Left(err) =>
        assert(err.contains("release manifest could not be built"), s"the build failure is named: $err")
      case Right(_) => fail("a missing directory must not produce a report")

  // ══════════════════════════════════════════════════════════════════════
  // delivery-verified (spec 7) — the release check accepts a candidate iff
  // every native binary's embedded toolchain identity equals the tested one
  // spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
  // ══════════════════════════════════════════════════════════════════════

  /**
   * Synthetic binary bytes carrying a GraalVM embedded-toolchain marker —
   * the observed format is `GraalVM <version> Java <major> <edition>`
   * (measured on the local build: `GraalVM 22.3.1 Java 17 CE`).
   */
  private def binaryWithMarker(marker: String): String =
    s"\u0000\u0001binary-bytes\u0001 $marker \u0001more-binary-bytes"

  /** Writes a complete release whose native binaries all carry `marker`. */
  private def writeReleaseWithMarker(dir: Path, version: String, marker: String): Unit =
    val binaryNames: List[String] = List(
      "probatio-linux-x86_64",
      "probatio-macos-aarch64",
      "probatio-macos-x86_64"
    )
    binaryNames.foreach(n => write(dir, n, binaryWithMarker(marker)))
    List("probatio-assembly.jar", "probatio-sources.jar")
      .foreach(n => write(dir, n, s"content-of-$n"))
    val sbom: Sbom = Sbom.forRelease(version, List(SbomPackage("upickle", "4.4.3", "Maven")))
    write(dir, "probatio-sbom.spdx.json", writeJson[Sbom](sbom))
    (binaryNames ++ List("probatio-assembly.jar", "probatio-sources.jar"))
      .foreach(n => writeSidecar(dir, n))

  // spec: finish-probatio-replacement/delivery-verified — Scenario: Happy path — a candidate on the tested toolchain is accepted
  test("a candidate built with the tested toolchain is accepted"):
    withTempDir { dir =>
      // testedToolchain is GraalVM CE/21.0.2 — the marker the release
      // workflow's toolchain embeds.
      writeReleaseWithMarker(dir, "v14.0.0", "GraalVM 21.0.2 Java 21 CE")
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = true, testedToolchain) match
        case Right(report) =>
          assert(report.contains("release manifest complete"), s"the completion is reported: $report")
        case Left(err) =>
          fail(s"a candidate on the tested toolchain must be accepted: $err")
    }

  // spec: finish-probatio-replacement/delivery-verified — Scenario: Adversarial — a candidate on a different toolchain is rejected
  test("a candidate built with a different toolchain is rejected naming both"):
    withTempDir { dir =>
      // The 22.3.1 toolchain is exactly what the unpinned local build
      // produced — the real regression this spec guards against.
      writeReleaseWithMarker(dir, "v14.0.0", "GraalVM 22.3.1 Java 17 CE")
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = true, testedToolchain) match
        case Left(err) =>
          assert(err.contains("toolchain"), s"the toolchain must be named as the defect: $err")
          assert(err.contains("21.0.2"), s"the tested identity must be named: $err")
          assert(err.contains("22.3.1"), s"the candidate identity must be named: $err")
        case Right(report) =>
          fail(s"a candidate on 22.3.1 must not be accepted when 21.0.2 was tested: $report")
    }

  // spec: finish-probatio-replacement/delivery-verified — Scenario: Error path — an unreadable toolchain identity is could-not-determine
  test("a binary whose toolchain identity cannot be read is could-not-determine"):
    withTempDir { dir =>
      // No marker at all — plain content, as writeMarkerFreeRelease produces.
      writeMarkerFreeRelease(dir, "v14.0.0")
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = true, testedToolchain) match
        case Left(err) =>
          assert(err.contains("could not determine"), s"the outcome is could-not-determine: $err")
          assert(err.contains("probatio-linux-x86_64"), s"the binary is named: $err")
        case Right(report) =>
          fail(s"a candidate with no readable toolchain identity must not be accepted: $report")
    }
    // A native binary present in the manifest with NO recorded read is
    // likewise could-not-determine — the check cannot silently skip it.
    val noRead: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts,
      checksums = ReleaseManifest.expectedArtifacts
        .collect { case ReleaseArtifact.Checksum(n) => n -> ("a" * 64) }
        .toMap,
      sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = true,
      toolchains = List.empty
    )
    val noReadIssues: List[String] = ReleaseValidator.validateAll(noRead, testedToolchain)
    assert(
      noReadIssues.exists(i => i.contains("could not determine") || i.contains("no toolchain")),
      s"a binary with no toolchain read must be reported: $noReadIssues"
    )

  // spec: finish-probatio-replacement/delivery-verified — Scenario: Adversarial — a candidate missing one artifact is rejected
  test("a candidate missing its bill of materials is rejected naming it"):
    withTempDir { dir =>
      writeCompleteRelease(dir, "v14.0.0")
      val _: Boolean = Files.deleteIfExists(dir.resolve("probatio-sbom.spdx.json"))
      ReleaseCheck.run(dir, "v14.0.0", builtFromCI = true, testedToolchain) match
        case Left(err) =>
          assert(
            err.contains("probatio-sbom.spdx.json"),
            s"the missing bill of materials must be named: $err"
          )
        case Right(_) => fail("a candidate missing an artifact must not be delivered")
    }

  // ── Property: toolchain-check-accepts-iff-identical ─────────────────────
  // spec: finish-probatio-replacement/delivery-verified — Property: toolchain-check-accepts-iff-identical

  /**
   * `genToolchainPair` — constructive over identities drawn from closed
   * sets of distributions and versions, paired so that identical,
   * differing-version, differing-distribution and unreadable cases all
   * arise by construction (no filtering).
   */
  private def genToolchainPair: Gen[(ToolchainIdentity, ToolchainIdentity.Embedded)] =
    for
      dist     <- Gen.element1("GraalVM CE", toolchainDistributions.drop(1)*)
      ver      <- Gen.element1("21.0.2", toolchainVersions.drop(1)*)
      altDist  <- Gen.element1("GraalVM CE", toolchainDistributions.drop(1)*)
      altVer   <- Gen.element1("21.0.2", toolchainVersions.drop(1)*)
      kind     <- Gen.element1("identical", "version-differs", "distribution-differs", "unreadable")
      bin      <- binaryNames match
                    case h :: t => Gen.element1(h, t*)
                    case Nil    => Gen.constant("probatio-linux-x86_64")
      reason   <- Gen.element1("no embedded GraalVM marker", "marker truncated mid-version")
    yield
      val tested: ToolchainIdentity = ToolchainIdentity(dist, version(ver))
      val candidate: ToolchainIdentity.Embedded =
        kind match
          case "identical" =>
            ToolchainIdentity.Embedded.Found(bin, tested)
          case "version-differs" =>
            val differing: String = if altVer == ver then s"$altVer-next" else altVer
            ToolchainIdentity.Embedded.Found(bin, ToolchainIdentity(dist, version(differing)))
          case "distribution-differs" =>
            val differing: String = if altDist == dist then s"$altDist-alt" else altDist
            ToolchainIdentity.Embedded.Found(bin, ToolchainIdentity(differing, tested.version))
          case "unreadable" =>
            ToolchainIdentity.Embedded.Unreadable(bin, reason)
          case other => // danger-scan:allow generator-invariant — `kind` is drawn from a closed 4-element set; an unknown draw is a generator bug, never a candidate
            fail(s"genToolchainPair drew an unknown kind: $other")
      (tested, candidate)

  // spec: finish-probatio-replacement/delivery-verified — Property: toolchain-check-accepts-iff-identical
  property("toolchain-check-accepts-iff-identical"):
    for
      pair <- genToolchainPair.forAll
        .cover(20, "identical", (p: (ToolchainIdentity, ToolchainIdentity.Embedded)) =>
          p._2.identityOption.contains(p._1)
        )
        .cover(20, "readable-differs", (p: (ToolchainIdentity, ToolchainIdentity.Embedded)) =>
          p._2.readable && !p._2.identityOption.contains(p._1)
        )
        .cover(20, "unreadable", (p: (ToolchainIdentity, ToolchainIdentity.Embedded)) =>
          !p._2.readable
        )
      (tested, candidate) = pair
    yield
      val verdict: ToolchainVerdict = ReleaseValidator.toolchainVerdict(tested, candidate)
      val expectedAccepted: Boolean = candidate.identityOption.contains(tested)
      Result
        .assert(verdict.accepted == expectedAccepted)
        .log(s"verdict=$verdict tested=$tested candidate=$candidate")
        .and(
          verdict match
            case ToolchainVerdict.Accepted(bin, identity) =>
              Result.assert(bin == candidate.binary && identity == tested)
            case ToolchainVerdict.Rejected(bin, t, c) =>
              Result.assert(
                bin == candidate.binary && t == tested && candidate.identityOption.contains(c)
              )
            case ToolchainVerdict.Undetermined(bin, reason) =>
              Result.assert(bin == candidate.binary && !candidate.readable && reason.nonEmpty)
        )
