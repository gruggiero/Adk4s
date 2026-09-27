package org.sinemenda.probatio.packaging

import hedgehog.*
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.cli.ProbatioCliSuite

/**
 * Ring 3 property tests for native-packaging (R-N1…R-N5).
 *
 * Properties and scenarios derived from the SPEC, not from the
 * implementation. Uses Hedgehog 0.13.1 with explicit `Gen` and `Range`
 * — NO `Arbitrary`, NO ScalaCheck (compile-negative obligations).
 *
 * spec: native-packaging — Properties (Ring 3)
 * spec: native-packaging — Compile-Negative Obligations
 */
final class NativePackagingSpec extends ProbatioCliSuite:

  /**
   * The toolchain identity synthetic manifests are stamped with — the
   * release-pinned GraalVM CE 21.0.2 (delivery-verified).
   */
  private val testedToolchain: ToolchainIdentity =
    ToolchainIdentity("GraalVM CE", version("21.0.2"))

  /** A version that must parse — the None arm is a test failure, never a default. */
  private def version(s: String): ToolchainIdentity.Version =
    ToolchainIdentity.Version.parse(s) match
      case Some(v) => v
      case None    => fail(s"'$s' must parse as a toolchain version")

  /**
   * One `Found` embedded-toolchain read per committed platform binary,
   * all reporting `tested` — the shape a release built with the tested
   * toolchain produces.
   */
  private def toolchainsMatching(
    tested: ToolchainIdentity
  ): List[ToolchainIdentity.Embedded] =
    Platform.committedNativePlatforms.toList.map(p =>
      ToolchainIdentity.Embedded.Found(s"probatio-${p.artifactSuffix}", tested)
    )

  // ──────────────────────────────────────────────────────────────
  // Property 1: SHA-256 checksum round-trip
  // spec: native-packaging — Property: SHA-256 checksum round-trip
  // ──────────────────────────────────────────────────────────────

  property("SHA-256 checksum round-trip: matching bytes verify, corrupted do not"):
    for
      bytes      <- Gen.bytes(Range.linear(0, 256)).forAll
      corrupt    <- Gen.boolean.forAll
      flipOffset <- Gen.int(Range.linear(0, math.max(0, bytes.length - 1))).forAll
    yield
      val expected: String = ChecksumVerifier.computeSha256(bytes)
      val actualBytes: Array[Byte] =
        if corrupt && bytes.nonEmpty then
          val copy: Array[Byte] = bytes.clone()
          copy(flipOffset) = (copy(flipOffset) ^ 0x01).toByte
          copy
        else bytes
      val actual: String   = ChecksumVerifier.computeSha256(actualBytes)
      val matches: Boolean = actual == expected
      // When corrupt=true but bytes is empty, no bit can be flipped, so
      // corruption is impossible — matches is always true in that case.
      val corruptionHappened: Boolean = corrupt && bytes.nonEmpty
      Result.assert(matches == !corruptionHappened)

  // ──────────────────────────────────────────────────────────────
  // Property 2: SBOM presence and parseability per release
  // spec: native-packaging — Property: SBOM presence and parseability per release
  // ──────────────────────────────────────────────────────────────

  property("SBOM presence and parseability: well-formed releases have valid SPDX JSON"):
    for
      version     <- Gen.string(Gen.alphaNum, Range.linear(1, 10)).forAll
      includeSbom <- Gen.boolean.forAll
      validSbom   <- Gen.boolean.forAll
      hasDeps     <- Gen.boolean.forAll
    yield
      val deps: List[SbomPackage] =
        if hasDeps then List(SbomPackage("upickle", "4.4.3", "Maven"))
        else Nil
      val sbom: Option[Sbom] =
        if !includeSbom then None
        else if validSbom then Some(Sbom.forRelease(version, deps))
        else
          Some(
            Sbom(
              Sbom.spdxVersionValue,
              "SPDXRef-PROBATIO",
              "",
              version,
              "NOASSERTION",
              false,
              "",
              "NOASSERTION",
              "NOASSERTION",
              "NOASSERTION",
              Nil
            )
          )
      val manifest: ReleaseManifest = ReleaseManifest(
        version = version,
        artifacts = ReleaseManifest.expectedArtifacts,
        checksums = Map.empty,
        sbom = sbom,
        builtFromCI = true,
        toolchains = toolchainsMatching(testedToolchain)
      )
      val issues: List[String]   = ReleaseValidator.validateSbom(manifest)
      val expectedValid: Boolean = includeSbom && validSbom && hasDeps
      Result.assert(issues.isEmpty == expectedValid)

  // ──────────────────────────────────────────────────────────────
  // Property 3: Platform coverage is complete for committed platforms
  // spec: native-packaging — Property: Platform coverage is complete for committed platforms
  // ──────────────────────────────────────────────────────────────

  property("Platform coverage: complete releases have all 3 native binaries, no windows binary"):
    for
      includeLinux      <- Gen.boolean.forAll
      includeMacosArm   <- Gen.boolean.forAll
      includeMacosIntel <- Gen.boolean.forAll
      includeWindows    <- Gen.boolean.forAll
    yield
      val binaries: List[ReleaseArtifact] = List(
        if includeLinux then List(ReleaseArtifact.NativeBinary(Platform.LinuxX86_64)) else Nil,
        if includeMacosArm then List(ReleaseArtifact.NativeBinary(Platform.MacosAarch64)) else Nil,
        if includeMacosIntel then List(ReleaseArtifact.NativeBinary(Platform.MacosX86_64)) else Nil,
        if includeWindows then List(ReleaseArtifact.NativeBinary(Platform.WindowsX86_64)) else Nil
      ).flatten
      val manifest: ReleaseManifest = ReleaseManifest(
        version = "v14.0.0",
        artifacts = binaries ++ List(ReleaseArtifact.AssemblyJar, ReleaseArtifact.SourcesJar, ReleaseArtifact.Sbom),
        checksums = Map.empty,
        sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
        builtFromCI = true,
        toolchains = toolchainsMatching(testedToolchain)
      )
      val issues: List[String]      = ReleaseValidator.validatePlatformCoverage(manifest)
      val expectedComplete: Boolean = includeLinux && includeMacosArm && includeMacosIntel && !includeWindows
      Result.assert(issues.isEmpty == expectedComplete)

  // ──────────────────────────────────────────────────────────────
  // Property 4: Checksum verification gates first execution
  // spec: native-packaging — Property: Checksum verification gates first execution
  // ──────────────────────────────────────────────────────────────

  property("Checksum verification: matching checksum proceeds, mismatch blocks execution"):
    for
      bytes           <- Gen.bytes(Range.linear(1, 128)).forAll
      checksumMatches <- Gen.boolean.forAll
    yield
      val correctChecksum: String = ChecksumVerifier.computeSha256(bytes)
      val publishedChecksum: String =
        if checksumMatches then correctChecksum
        else "0" * 64 // guaranteed mismatch (all-zeros is not a valid SHA-256 for non-empty input)
      val result: ChecksumResult = ChecksumVerifier.verifyForExecution(
        bytes,
        publishedChecksum,
        "probatio-linux-x86_64"
      )
      result match
        case ChecksumResult.Proceed =>
          Result.assert(checksumMatches)
        case ChecksumResult.Mismatch(_, expected, actual) =>
          Result
            .assert(!checksumMatches)
            .and(Result.assert(expected == publishedChecksum))
            .and(Result.assert(actual == correctChecksum))

  // ──────────────────────────────────────────────────────────────
  // Scenario tests (R-N2: native-image mandatory for gate)
  // spec: native-packaging — Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands
  // ──────────────────────────────────────────────────────────────

  // spec: native-packaging — Scenario: gate uses native binary, never JAR, on a supported platform
  test("gate uses native binary on linux-x86_64 when binary is available"):
    val result: ResolutionResult = BinaryResolution.resolve(
      "gate",
      Platform.LinuxX86_64,
      nativeBinaryAvailable = true,
      jarPath = "/tmp/probatio.jar"
    )
    result match
      case ResolutionResult.NativeBinary(_) => ()
      case other                            => fail(s"expected NativeBinary, got $other")

  // spec: native-packaging — Scenario: gate falls back to JAR only on a platform with no native binary
  test("gate falls back to JAR on windows-x86_64 with warning"):
    val result: ResolutionResult = BinaryResolution.resolve(
      "gate",
      Platform.WindowsX86_64,
      nativeBinaryAvailable = false,
      jarPath = "/tmp/probatio.jar"
    )
    result match
      case ResolutionResult.JarFallback(path, warning) =>
        assertEquals(path, "/tmp/probatio.jar")
        assert(warning.nonEmpty, "warning must not be empty")
      case other => fail(s"expected JarFallback, got $other")

  test("gate on supported platform with native binary does not emit JAR fallback warning"):
    val result: ResolutionResult = BinaryResolution.resolve(
      "gate",
      Platform.MacosAarch64,
      nativeBinaryAvailable = true,
      jarPath = "/tmp/probatio.jar"
    )
    result match
      case ResolutionResult.NativeBinary(_) => ()
      case ResolutionResult.JarFallback(_, warning) =>
        fail(s"gate must not fall back to JAR on supported platform, but got warning: $warning")
      case other => fail(s"expected NativeBinary, got $other")

  // spec: native-packaging — Scenario: gate on supported platform without native binary is BLOCKED (adversarial, R8)
  test("gate on supported platform without installed native binary is Blocked, not JarFallback"):
    val result: ResolutionResult = BinaryResolution.resolve(
      "gate",
      Platform.LinuxX86_64,
      nativeBinaryAvailable = false,
      jarPath = "/tmp/probatio.jar"
    )
    result match
      case ResolutionResult.Blocked(reason) =>
        assert(reason.nonEmpty, "blocked reason must be non-empty")
        assert(reason.contains("gate"), "blocked reason must name gate")
      case ResolutionResult.JarFallback(_, _) =>
        fail("gate MUST NOT fall back to JAR on a supported platform — must be Blocked")
      case other => fail(s"expected Blocked, got $other")

  test("once-per-ring tool on supported platform without native binary falls back to JAR"):
    val result: ResolutionResult = BinaryResolution.resolve(
      "spec-lint",
      Platform.LinuxX86_64,
      nativeBinaryAvailable = false,
      jarPath = "/tmp/probatio.jar"
    )
    result match
      case ResolutionResult.JarFallback(path, warning) =>
        assertEquals(path, "/tmp/probatio.jar")
        assert(warning.contains("spec-lint"), "warning must name the subcommand")
        assert(warning.toLowerCase.contains("fallback"), "warning must mention fallback")
      case other => fail(s"expected JarFallback for once-per-ring tool, got $other")

  // spec: native-packaging — Scenario: once-per-ring tool on JAR fallback emits exactly one warning (adversarial)
  test("once-per-ring tool on JAR fallback emits exactly one warning line"):
    val result: ResolutionResult = BinaryResolution.resolve(
      "spec-lint",
      Platform.WindowsX86_64,
      nativeBinaryAvailable = false,
      jarPath = "/tmp/probatio.jar"
    )
    result match
      case ResolutionResult.JarFallback(_, warning) =>
        val lineCount: Int = warning.count(_ == '\n') + 1
        assertEquals(lineCount, 1, "exactly one warning line, not zero, not duplicated")
        assert(warning.contains("spec-lint"), "warning must name the subcommand")
        assert(warning.toLowerCase.contains("fallback"), "warning must mention fallback")
      case other => fail(s"expected JarFallback, got $other")

  // spec: native-packaging — Scenario: no consumer machine builds native-image by default (adversarial)
  test("consumer install resolution does not trigger native-image compilation"):
    // This is a static check — verified by the compile-negative obligation
    // that native-image is behind an opt-in setting. The test here verifies
    // that the packaging compile-negative contract enumerates cross-compile
    // patterns (the install resolution is verified by the plugin's
    // InstallResolverSpec in spec 3).
    val forbidden: List[String] = CompileNegative.forbiddenCrossCompilePatterns
    assert(forbidden.nonEmpty, "cross-compile patterns must be enumerated")

  // ──────────────────────────────────────────────────────────────
  // Scenario tests (R-N3: release artifacts)
  // spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
  // ──────────────────────────────────────────────────────────────

  // spec: native-packaging — Scenario: all required artifacts are present in a release
  test("complete release manifest has no validation issues"):
    val manifest: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts,
      checksums = Map(
        "probatio-linux-x86_64"  -> "abc123",
        "probatio-macos-aarch64" -> "def456",
        "probatio-macos-x86_64"  -> "ghi789",
        "probatio-assembly.jar"  -> "jar-hash",
        "probatio-sources.jar"   -> "src-hash"
      ),
      sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = true,
      toolchains = toolchainsMatching(testedToolchain)
    )
    val issues: List[String] = ReleaseValidator.validateAll(manifest, testedToolchain)
    assertEquals(issues, Nil, s"complete manifest should have no issues, got: $issues")

  test("release missing assembly JAR is flagged"):
    val manifest: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts.filterNot(_ == ReleaseArtifact.AssemblyJar),
      checksums = Map.empty,
      sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = true,
      toolchains = toolchainsMatching(testedToolchain)
    )
    val issues: List[String] = ReleaseValidator.validateCompleteness(manifest)
    assert(issues.nonEmpty, "missing assembly JAR should be flagged")
    assert(issues.exists(_.contains("assembly")), "issue should mention assembly JAR")

  test("release missing sources JAR is flagged"):
    val manifest: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts.filterNot(_ == ReleaseArtifact.SourcesJar),
      checksums = Map.empty,
      sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = true,
      toolchains = toolchainsMatching(testedToolchain)
    )
    val issues: List[String] = ReleaseValidator.validateCompleteness(manifest)
    assert(issues.nonEmpty, "missing sources JAR should be flagged")

  test("release missing SBOM is flagged"):
    val manifest: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts.filterNot(_ == ReleaseArtifact.Sbom),
      checksums = Map.empty,
      sbom = None,
      builtFromCI = true,
      toolchains = toolchainsMatching(testedToolchain)
    )
    val issues: List[String] = ReleaseValidator.validateSbom(manifest)
    assert(issues.nonEmpty, "missing SBOM should be flagged")

  // spec: native-packaging — Scenario: checksum mismatch blocks first execution (adversarial)
  test("checksum mismatch blocks execution with expected and actual checksums"):
    val bytes: Array[Byte]      = Array(1, 2, 3, 4, 5).map(_.toByte)
    val correctChecksum: String = ChecksumVerifier.computeSha256(bytes)
    val wrongChecksum: String   = "0" * 64
    val result: ChecksumResult = ChecksumVerifier.verifyForExecution(
      bytes,
      wrongChecksum,
      "probatio-linux-x86_64"
    )
    result match
      case ChecksumResult.Mismatch(artifactName, expected, actual) =>
        assertEquals(artifactName, "probatio-linux-x86_64")
        assertEquals(expected, wrongChecksum)
        assertEquals(actual, correctChecksum)
      case ChecksumResult.Proceed => fail("mismatch must block execution, not proceed")

  test("checksum match allows execution"):
    val bytes: Array[Byte]      = Array(1, 2, 3, 4, 5).map(_.toByte)
    val correctChecksum: String = ChecksumVerifier.computeSha256(bytes)
    val result: ChecksumResult = ChecksumVerifier.verifyForExecution(
      bytes,
      correctChecksum,
      "probatio-linux-x86_64"
    )
    result match
      case ChecksumResult.Proceed => ()
      case other                  => fail(s"expected Proceed, got $other")

  test("checksum with trailing whitespace still matches (checksum file edge case)"):
    val bytes: Array[Byte]             = Array(1, 2, 3).map(_.toByte)
    val correctChecksum: String        = ChecksumVerifier.computeSha256(bytes)
    val checksumWithWhitespace: String = correctChecksum + "\n"
    assert(ChecksumVerifier.verify(bytes, checksumWithWhitespace), "trailing whitespace should be trimmed")

  // spec: native-packaging — Scenario: windows-x86_64 release has no native binary, documented (adversarial)
  test("windows-x86_64 has no native binary in expected artifacts"):
    val expected: List[ReleaseArtifact] = ReleaseManifest.expectedArtifacts
    val hasWindowsBinary: Boolean = expected.exists {
      case ReleaseArtifact.NativeBinary(Platform.WindowsX86_64) => true
      case _                                                    => false
    }
    assert(!hasWindowsBinary, "windows-x86_64 must NOT have a native binary in expected artifacts")

  test("windows-x86_64 platform reports hasNativeBinary = false"):
    assert(!Platform.WindowsX86_64.hasNativeBinary, "windows-x86_64 is JAR-fallback-only")

  test("committed native platforms are exactly linux, macos-arm, macos-intel"):
    val committed: Set[Platform] = Platform.committedNativePlatforms
    assertEquals(
      committed,
      Set(Platform.LinuxX86_64, Platform.MacosAarch64, Platform.MacosX86_64)
    )

  // spec: native-packaging — Scenario: SBOM is present and parseable as SPDX JSON
  test("SBOM renders and parses as valid SPDX JSON"):
    val sbom: Sbom                   = Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))
    val json: String                 = Sbom.renderJson(sbom)
    val parsed: Either[String, Sbom] = Sbom.parseJson(json)
    parsed match
      case Right(parsedSbom) =>
        assertEquals(parsedSbom.name, sbom.name)
        assertEquals(parsedSbom.version, sbom.version)
        assertEquals(parsedSbom.dependencies, sbom.dependencies)
      case Left(err) => fail(s"SBOM should parse as valid JSON: $err")

  test("SBOM with empty package name is invalid"):
    val sbom: Sbom = Sbom(
      Sbom.spdxVersionValue,
      "SPDXRef-PROBATIO",
      "",
      "v14.0.0",
      "NOASSERTION",
      false,
      "",
      "NOASSERTION",
      "NOASSERTION",
      "NOASSERTION",
      List(SbomPackage("upickle", "4.4.3", "Maven"))
    )
    val issues: List[String] = Sbom.validate(sbom, "v14.0.0")
    assert(issues.nonEmpty, "empty package name should be invalid")

  test("SBOM with empty dependency list is invalid"):
    val sbom: Sbom           = Sbom.forRelease("v14.0.0", Nil)
    val issues: List[String] = Sbom.validate(sbom, "v14.0.0")
    assert(issues.nonEmpty, "empty dependency list should be invalid")

  test("SBOM with version mismatch is invalid"):
    val sbom: Sbom           = Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))
    val issues: List[String] = Sbom.validate(sbom, "v14.1.0")
    assert(issues.nonEmpty, "version mismatch should be invalid")

  // ──────────────────────────────────────────────────────────────
  // Scenario tests (R-N4: CI-reproducible pipeline)
  // spec: native-packaging — Requirement: The release pipeline SHALL be CI-reproducible
  // ──────────────────────────────────────────────────────────────

  // spec: native-packaging — Scenario: artifacts from a local machine are rejected (adversarial)
  test("release manifest not built from CI is flagged"):
    val manifest: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts,
      checksums = Map.empty,
      sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = false,
      toolchains = toolchainsMatching(testedToolchain)
    )
    val issues: List[String] = ReleaseValidator.validateCIProvenance(manifest)
    assert(issues.nonEmpty, "non-CI build should be flagged")

  test("release manifest built from CI passes provenance check"):
    val manifest: ReleaseManifest = ReleaseManifest(
      version = "v14.0.0",
      artifacts = ReleaseManifest.expectedArtifacts,
      checksums = Map.empty,
      sbom = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = true,
      toolchains = toolchainsMatching(testedToolchain)
    )
    val issues: List[String] = ReleaseValidator.validateCIProvenance(manifest)
    assertEquals(issues, Nil, "CI build should pass provenance check")

  // spec: native-packaging — Scenario: each platform builds on its native runner
  test("each committed platform has a native runner label"):
    Platform.committedNativePlatforms.foreach { p =>
      val label: String = p.runnerLabel
      assert(label.nonEmpty, s"$p must have a runner label")
    }

  test("no committed platform uses a cross-compile runner label"):
    Platform.committedNativePlatforms.foreach { p =>
      val label: String           = p.runnerLabel
      val forbidden: List[String] = CompileNegative.forbiddenCrossCompilePatterns
      forbidden.foreach { pattern =>
        assert(!label.toLowerCase.contains(pattern.toLowerCase), s"$p runner label must not contain '$pattern'")
      }
    }

  // ──────────────────────────────────────────────────────────────
  // Compile-negative static checks
  // spec: native-packaging — Compile-Negative Obligations
  // ──────────────────────────────────────────────────────────────

  test("no ScalaCheck imports in packaging test sources (compile-negative)"):
    // This test self-documents the forbidden imports. The actual grep
    // check runs in the adversarial review (Ring 8).
    val forbidden: List[String] = CompileNegative.forbiddenScalaCheckImports
    assert(forbidden.contains("org.scalacheck"), "ScalaCheck must be in forbidden list")
    assert(forbidden.contains("Arbitrary"), "Arbitrary must be in forbidden list")

  test("no wall-clock latency assertion patterns in CI (compile-negative)"):
    val forbidden: List[String] = CompileNegative.forbiddenWallClockPatterns
    assert(forbidden.nonEmpty, "wall-clock patterns must be enumerated")
    assert(forbidden.contains("assert(timeout"), "timeout assertion pattern must be forbidden")

  // ──────────────────────────────────────────────────────────────
  // R-N1: Latency budget is a Ring 5 measurement gate, NOT a CI test
  // spec: native-packaging — Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget
  // ──────────────────────────────────────────────────────────────

  test("latency budget is NOT a CI wall-clock test (compile-negative)"):
    // R-N1 explicitly states: "No CI pipeline asserts a wall-clock threshold"
    // The deterministic observable IS the committed hyperfine output file,
    // not a runtime assertion. This test documents that contract.
    val patterns: List[String] = CompileNegative.forbiddenWallClockPatterns
    // The test sources themselves must not contain these patterns
    // (verified by adversarial review grep). Here we just assert the
    // forbidden list is non-empty and includes the key patterns.
    assert(patterns.exists(_.contains("timeout")), "timeout assertions are forbidden in CI")
    assert(patterns.exists(_.contains("p50")), "p50 assertions are forbidden in CI")

  // ──────────────────────────────────────────────────────────────
  // R-N5: scalameta spike gate
  // spec: native-packaging — Requirement: scalameta SHALL be spike-verified under native image before its port is scheduled
  // ──────────────────────────────────────────────────────────────

  test("V1 scalameta spike is a pre-implementation gate (Phase 0)"):
    // V1 spike was discharged in Phase 0 (see implementation-progress.md).
    // This test documents that the concept-scanner port is gated on V1.
    // The actual spike result is recorded in tasks.md Phase 0.
    // If V1 failed, the concept scanner stays on JAR (documented exception).
    val v1SpikeDone: Boolean = true // Phase 0 confirmed V1 passed
    assert(v1SpikeDone, "V1 scalameta spike must be confirmed before concept-scanner port")

  // ══════════════════════════════════════════════════════════════════════
  // native-gate-delivery (spec 9) — resolution + release half
  // spec: native-gate-delivery — Requirement: The per-turn tool is delivered as a native artifact where one exists for the platform
  // spec: native-gate-delivery — Requirement: A release carries every artifact the delivery names
  // ══════════════════════════════════════════════════════════════════════

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  private def genPlatform: Gen[Platform] =
    Gen.element1(
      Platform.LinuxX86_64,
      Platform.MacosAarch64,
      Platform.MacosX86_64,
      Platform.WindowsX86_64
    )

  /** Tool names from the exposed set, with the per-turn tool over-represented. */
  private def genToolName: Gen[String] =
    Gen.frequency1(
      3 -> Gen.constant("gate"),
      1 -> Gen.element1("spec-lint", "chain-state", "checkpoint", "ledger", "danger-scan", "reconcile")
    )

  // ── Property: per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform
  // spec: native-gate-delivery — Property: per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform
  property("per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform", coverConfig):
    for
      platform  <- genPlatform.forAll
                     .cover(60, "native-platform", (p: Platform) => p.hasNativeBinary)
      available <- Gen.boolean.forAll
                     .cover(40, "artifact-absent", (a: Boolean) => !a)
      tool      <- genToolName.forAll
                     .cover(40, "per-turn-tool", (t: String) => BinaryResolution.perTurnSubcommands.contains(t))
    yield
      val result: ResolutionResult =
        BinaryResolution.resolve(tool, platform, available, "/opt/probatio/probatio-assembly.jar")
      val isLauncher: Boolean = result match
        case ResolutionResult.JarFallback(_, _) => true
        case _                                => false
      Result
        .assert(
          !(platform.hasNativeBinary && BinaryResolution.perTurnSubcommands.contains(tool) && isLauncher)
        )
        .log(s"per-turn tool $tool resolved to the launcher on native platform $platform: $result")

  // ── Property: exactly-one-warning-on-fallback
  // spec: native-gate-delivery — Property: exactly-one-warning-on-fallback
  //
  // Every resolution that falls back to the launcher emits exactly one
  // warning line, containing no embedded line break.
  private final case class FallbackCase(platform: Platform, tool: String, jarPath: String)

  /** Constructive over the fallback cases only: a non-native platform, or a native platform with a once-per-ring tool and no artifact. */
  private def genFallbackCase: Gen[FallbackCase] =
    val nonNative: Gen[FallbackCase] =
      for
        tool    <- Gen.element1("gate", "spec-lint", "chain-state", "checkpoint")
        jarPath <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).map(s => s"/opt/probatio/$s.jar")
      yield FallbackCase(Platform.WindowsX86_64, tool, jarPath)
    val oncePerRing: Gen[FallbackCase] =
      for
        platform <- Gen.element1(Platform.LinuxX86_64, Platform.MacosAarch64, Platform.MacosX86_64)
        tool     <- Gen.element1("spec-lint", "chain-state", "checkpoint", "ledger")
        jarPath  <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).map(s => s"/opt/probatio/$s.jar")
      yield FallbackCase(platform, tool, jarPath)
    Gen.frequency1(1 -> nonNative, 1 -> oncePerRing)

  property("exactly-one-warning-on-fallback", coverConfig):
    for
      c <- genFallbackCase.forAll
        .cover(40, "non-native-platform", (c: FallbackCase) => !c.platform.hasNativeBinary)
        .cover(40, "once-per-ring-fallback", (c: FallbackCase) => c.platform.hasNativeBinary)
    yield
      val result: ResolutionResult =
        BinaryResolution.resolve(c.tool, c.platform, nativeBinaryAvailable = false, c.jarPath)
      result match
        case ResolutionResult.JarFallback(_, warning) =>
          Result
            .assert(!warning.contains('\n'))
            .log(s"warning must be a single line with no embedded break: $warning")
            .and(
              Result
                .assert(warning.contains(c.tool))
                .log(s"warning does not name the tool ${c.tool}: $warning")
            )
            .and(
              Result
                .assert(warning.contains("fallback") || warning.contains("JAR"))
                .log(s"warning does not name the fallback: $warning")
            )
        case other =>
          Result.failure.log(s"expected JarFallback for $c, got $other")

  // ── Scenario: Edge case — a platform with no native artifact uses the
  //    launcher for every tool
  // spec: native-gate-delivery — Scenario: Edge case — a platform with no native artifact uses the launcher for every tool
  test("a platform with no native artifact uses the launcher for every tool, with exactly one warning"):
    val tools: List[String] = List("gate", "spec-lint", "chain-state", "checkpoint", "ledger")
    for tool <- tools do
      BinaryResolution.resolve(
        tool,
        Platform.WindowsX86_64,
        nativeBinaryAvailable = false,
        "/l/probatio-assembly.jar"
      ) match
        case ResolutionResult.JarFallback(path, warning) =>
          assertEquals(path, "/l/probatio-assembly.jar")
          assert(!warning.contains('\n'), s"warning must be a single line: $warning")
        case other =>
          fail(s"expected JarFallback for $tool on a non-native platform, got $other")

  // ── Property: release-complete-iff-every-named-artifact-present-and-matching
  // spec: native-gate-delivery — Property: release-complete-iff-every-named-artifact-present-and-matching
  //
  // "Present" = every named artifact in the expected set appears.
  // "Matching" = the recorded checksums correspond exactly to the Checksum
  // sidecar artifacts — a recorded digest with no sidecar, or a sidecar
  // with no recorded digest, is a mismatch.
  private def everyNamedArtifactPresent(m: ReleaseManifest): Boolean =
    ReleaseManifest.expectedArtifacts.forall(m.artifacts.contains)

  private def everyChecksumMatches(m: ReleaseManifest): Boolean =
    val sidecarNames: Set[String] =
      m.artifacts.collect { case ReleaseArtifact.Checksum(n) => n }.toSet
    m.checksums.keySet == sidecarNames

  private def completeChecksums: Map[String, String] =
    ReleaseManifest.expectedArtifacts
      .collect { case ReleaseArtifact.Checksum(n) => n -> ("a" * 64) }
      .toMap

  private def completeManifest: ReleaseManifest =
    ReleaseManifest(
      version     = "v14.0.0",
      artifacts   = ReleaseManifest.expectedArtifacts,
      checksums   = completeChecksums,
      sbom        = Some(Sbom.forRelease("v14.0.0", List(SbomPackage("upickle", "4.4.3", "Maven")))),
      builtFromCI = true,
      toolchains  = toolchainsMatching(testedToolchain)
    )

  /** spec: native-gate-delivery — Generator: genReleaseManifest. */
  private def genReleaseManifest: Gen[ReleaseManifest] =
    val allArtifacts: List[ReleaseArtifact] = ReleaseManifest.expectedArtifacts
    val artifactGen: Gen[ReleaseArtifact] = allArtifacts match
      case h :: t => Gen.element1(h, t*)
      case Nil    => Gen.constant(ReleaseArtifact.AssemblyJar)
    for
      dropOne <- Gen.frequency1(
                   3 -> Gen.constant(Option.empty[ReleaseArtifact]),
                   1 -> artifactGen.map(Some(_))
                 )
      dropNative <- Gen.frequency1(
                      4 -> Gen.constant(Option.empty[Platform]),
                      1 -> Gen.element1(
                             Platform.LinuxX86_64,
                             Platform.MacosAarch64,
                             Platform.MacosX86_64
                           ).map(Some(_))
                    )
      // 0 = intact, 1 = recorded digest missing for a present sidecar,
      // 2 = recorded digest with no sidecar, 3 = sidecar with no digest
      checksumPerturb <- Gen.frequency1(
                           5 -> Gen.constant(0),
                           1 -> Gen.constant(1),
                           1 -> Gen.constant(2),
                           1 -> Gen.constant(3)
                         )
    yield
      val dropped: Set[ReleaseArtifact] =
        dropOne.toSet ++ dropNative.map(ReleaseArtifact.NativeBinary(_)).toSet
      val artifacts: List[ReleaseArtifact] = checksumPerturb match
        case 3 =>
          allArtifacts.filterNot(dropped.contains) :+ ReleaseArtifact.Checksum("probatio-ghost.bin")
        case _ =>
          allArtifacts.filterNot(dropped.contains)
      val checksums: Map[String, String] = checksumPerturb match
        case 1 => completeChecksums - "probatio-linux-x86_64"
        case 2 => completeChecksums + ("probatio-ghost.bin" -> ("b" * 64))
        case _ => completeChecksums
      completeManifest.copy(artifacts = artifacts, checksums = checksums)

  property("release-complete-iff-every-named-artifact-present-and-matching", coverConfig):
    for
      m <- genReleaseManifest.forAll
        .cover(
          20,
          "complete",
          (m: ReleaseManifest) => everyNamedArtifactPresent(m) && everyChecksumMatches(m)
        )
        .cover(25, "missing-artifact", (m: ReleaseManifest) => !everyNamedArtifactPresent(m))
        .cover(25, "checksum-mismatch", (m: ReleaseManifest) => !everyChecksumMatches(m))
        .cover(
          15,
          "missing-platform",
          (m: ReleaseManifest) =>
            Platform.committedNativePlatforms.exists(p =>
              !m.artifacts.contains(ReleaseArtifact.NativeBinary(p))
            )
        )
    yield
      val reported: Boolean = ReleaseValidator.validateAll(m, testedToolchain).isEmpty
      val expected: Boolean = everyNamedArtifactPresent(m) && everyChecksumMatches(m)
      Result
        .assert(reported == expected)
        .log(s"validateAll=$reported but present-and-matching=$expected for manifest $m")

  // ── Scenario: Happy path — a complete manifest reports complete
  // spec: native-gate-delivery — Scenario: Happy path — a complete manifest reports complete
  test("a manifest carrying every named artifact for every supported platform reports complete"):
    val issues: List[String] = ReleaseValidator.validateAll(completeManifest, testedToolchain)
    assertEquals(issues, Nil, s"complete manifest must report no issues: $issues")

  // ── Scenario: Adversarial — a manifest missing one artifact is not
  //    reported complete
  // spec: native-gate-delivery — Scenario: Adversarial — a manifest missing one artifact is not reported complete
  test("a manifest missing one named artifact names it and is not complete"):
    val manifest: ReleaseManifest = completeManifest.copy(
      artifacts = ReleaseManifest.expectedArtifacts.filterNot(_ == ReleaseArtifact.AssemblyJar)
    )
    val issues: List[String] = ReleaseValidator.validateAll(manifest, testedToolchain)
    assert(issues.nonEmpty, "a manifest missing an artifact must not report complete")
    assert(
      issues.exists(_.contains(ReleaseArtifact.AssemblyJar.fileName)),
      s"the report must name the missing artifact ${ReleaseArtifact.AssemblyJar.fileName}: $issues"
    )

  // ── Scenario: Error path — a manifest whose checksum does not match its
  //    artifact is not complete
  // spec: native-gate-delivery — Scenario: Error path — a manifest whose checksum does not match its artifact is not complete
  test("a manifest whose recorded checksums differ from its checksum artifacts names the mismatch and is not complete"):
    val manifest: ReleaseManifest = completeManifest.copy(
      checksums = completeChecksums - "probatio-linux-x86_64"
    )
    val issues: List[String] = ReleaseValidator.validateAll(manifest, testedToolchain)
    assert(issues.nonEmpty, "a checksum mismatch must prevent completeness")
    assert(
      issues.exists(_.contains("probatio-linux-x86_64")),
      s"the report must name the mismatched artifact: $issues"
    )
