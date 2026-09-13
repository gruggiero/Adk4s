package org.sinemenda.probatio.core

/**
 * Tests for the DriftScan pure function.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: Instruction drift is detected across all install roots
 */
final class DriftScanSpec extends ProbatioSuite:

  // ── Scenario: a root with a matching stamp produces no drift warning
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a root with a matching stamp produces no drift warning
  test("a root with a matching stamp produces no drift warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(14))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.warnings.isEmpty, s"Expected no warnings, got ${result.warnings}")

  // ── Scenario: a root with a mismatched stamp produces a drift warning
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a root with a mismatched stamp produces a drift warning
  test("a root with a mismatched stamp produces a drift warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.warnings.nonEmpty, "Expected a drift warning for mismatched stamp")
    result.warnings.headOption match
      case Some(w: DriftWarning.VersionMismatch) =>
        assertEquals(w.expected, 14)
        assertEquals(w.found, 13)
      case other => fail(s"Expected VersionMismatch, got $other")

  // ── Scenario: no installed skills produces an explicit line
  // spec: port-scanner-to-probatio/probatio-core — Scenario: no installed skills produces an explicit line
  test("no installed skills produces an explicit noSkillInstalled line"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = None),
      InstallRootScan(".agents/skills", stampVersion = None)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.noSkillInstalled, "Expected noSkillInstalled=true when no root has a stamp")

  // ── Scenario: a pre-rename stamp is treated as drift with a migration message
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a pre-rename stamp is treated as drift with a migration message
  test("a pre-rename stamp produces a PreRenameStamp warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13), isPreRename = true)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.warnings.nonEmpty, "Expected a drift warning for pre-rename stamp")
    result.warnings.headOption match
      case Some(w: DriftWarning.PreRenameStamp) =>
        assertEquals(w.expected, 14)
        assertEquals(w.found, 13)
        assert(
          w.message.contains("migrate") || w.message.contains("rename"),
          s"Expected migration message, got: ${w.message}"
        )
      case other => fail(s"Expected PreRenameStamp, got $other")

  // ── Scenario: silence about drift is never emitted (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: silence about drift is never emitted (adversarial)
  test("drift is never silently passed over — warnings non-empty when drift exists"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.warnings.nonEmpty, "drift must produce a non-empty warning list")

  // ── Scenario: silence about checking is never emitted (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: silence about checking is never emitted (adversarial)
  test("no skills installed is never silently passed over — noSkillInstalled is true"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = None)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.noSkillInstalled, "no skills installed must produce noSkillInstalled=true")

  // ── Mutation-killing: a matching stamp does NOT set noSkillInstalled
  test("a root with a matching stamp sets noSkillInstalled=false"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(14))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(!result.noSkillInstalled, "installed skill must set noSkillInstalled=false")

  // ── Mutation-killing: a mismatched stamp sets noSkillInstalled=false
  test("a root with a mismatched stamp sets noSkillInstalled=false"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(!result.noSkillInstalled, "installed (but mismatched) skill must set noSkillInstalled=false")

  // ── Mutation-killing: version mismatch message contains expected and found
  test("version mismatch warning message contains expected and found values"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.VersionMismatch) =>
        assert(w.message.contains("14"), s"message must contain expected=14, got: ${w.message}")
        assert(w.message.contains("13"), s"message must contain found=13, got: ${w.message}")
        assert(w.message.contains(".claude/skills"), s"message must contain root path, got: ${w.message}")
      case other => fail(s"Expected VersionMismatch, got $other")

  // ── Mutation-killing: pre-rename message contains migration text
  test("pre-rename warning message contains migration text with expected version"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13), isPreRename = true)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.PreRenameStamp) =>
        assert(w.message.contains("14"), s"message must contain expected=14, got: ${w.message}")
        assert(w.message.contains("13"), s"message must contain found=13, got: ${w.message}")
        assert(w.message.contains("probatio-schema"), s"message must contain probatio-schema, got: ${w.message}")
        assert(
          w.message.contains("verified-scala3-schema"),
          s"message must contain verified-scala3-schema, got: ${w.message}"
        )
      case other => fail(s"Expected PreRenameStamp, got $other")

  // ── Mutation-killing: a non-preRename mismatched stamp does NOT produce PreRenameStamp
  test("a non-preRename mismatched stamp produces VersionMismatch, not PreRenameStamp"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(13), isPreRename = false)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    result.warnings.headOption match
      case Some(_: DriftWarning.VersionMismatch) => assert(true)
      case other                                 => fail(s"Expected VersionMismatch for non-preRename, got $other")

  // ── Mutation-killing: a pre-rename stamp with matching version still produces a warning
  test("a pre-rename stamp with matching version still produces a PreRenameStamp warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(14), isPreRename = true)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(result.warnings.nonEmpty, "pre-rename stamp must produce a warning even when version matches")
    result.warnings.headOption match
      case Some(_: DriftWarning.PreRenameStamp) => assert(true)
      case other                                => fail(s"Expected PreRenameStamp, got $other")

  // ── Mutation-killing: mixed roots — one matching, one mismatched
  test("mixed roots produce warnings only for mismatched ones"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(14)),
      InstallRootScan(".agents/skills", stampVersion = Some(13))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assertEquals(result.warnings.length, 1)
    assert(!result.noSkillInstalled, "at least one installed skill must set noSkillInstalled=false")

  // ── Mutation-killing: mixed roots — one with stamp, one without (exists vs forall)
  test("mixed roots with one None stamp sets noSkillInstalled=false"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", stampVersion = Some(14)),
      InstallRootScan(".agents/skills", stampVersion = None)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = 14, roots)
    assert(
      !result.noSkillInstalled,
      "at least one installed skill must set noSkillInstalled=false even if another has None"
    )
