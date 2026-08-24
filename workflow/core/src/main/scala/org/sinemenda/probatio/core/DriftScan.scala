package org.sinemenda.probatio.core

/**
 * A skill install scan result for one of the six install roots.
 *
 * `stampVersion` is the schema version found in the `generatedBy` stamp,
 * or None if no skill is installed at that root.
 *
 * spec: probatio-core — Requirement: Instruction drift is detected across all install roots
 */
final case class InstallRootScan(
  rootPath: String,
  stampVersion: Option[Int],
  isPreRename: Boolean = false
)

/** The six install roots scanned for drift. */
val installRoots: List[String] = List(
  ".agents/skills",
  ".claude/skills",
  ".pi/skills"
)

/** A drift warning for a single root. */
sealed trait DriftWarning:
  def rootPath: String
  def message: String

object DriftWarning:

  /** A root whose stamp version differs from the schema version. */
  final case class VersionMismatch(rootPath: String, expected: Int, found: Int) extends DriftWarning:
    val message: String = s"drift: root $rootPath expected $expected found $found"

  /** A root carrying a pre-rename stamp (verified-scala3-schema → probatio-schema). */
  final case class PreRenameStamp(rootPath: String, expected: Int, found: Int) extends DriftWarning:
    val message: String =
      s"drift: root $rootPath has pre-rename stamp (verified-scala3-schema/$found) — " +
        s"migrate to probatio-schema/$expected"

/** The drift scan result. */
final case class DriftScanResult(
  warnings: List[DriftWarning],
  noSkillInstalled: Boolean
)

/**
 * The drift scan as a pure function (R-C5b component).
 *
 * Compares the schema version against `generatedBy` stamps across the six
 * install roots. Returns drift warnings + re-install instruction, or an
 * explicit "no skill installed" line when no root matches.
 *
 * spec: probatio-core — Requirement: Instruction drift is detected across all install roots
 * spec: probatio-core — Scenario: silence about drift is never emitted (adversarial)
 * spec: probatio-core — Scenario: silence about checking is never emitted (adversarial)
 */
object DriftScan:

  /**
   * Scan the install roots for drift against the schema version.
   *
   * spec: probatio-core — Scenario: a root with a matching stamp produces no drift warning
   * spec: probatio-core — Scenario: a root with a mismatched stamp produces a drift warning
   * spec: probatio-core — Scenario: no installed skills produces an explicit line
   * spec: probatio-core — Scenario: a pre-rename stamp is treated as drift with a migration message
   */
  def scan(schemaVersion: Int, roots: List[InstallRootScan]): DriftScanResult =
    val warnings: List[DriftWarning] = roots.flatMap { root =>
      root.stampVersion match
        case None => None
        case Some(found) =>
          if root.isPreRename then Some(DriftWarning.PreRenameStamp(root.rootPath, schemaVersion, found))
          else if found != schemaVersion then Some(DriftWarning.VersionMismatch(root.rootPath, schemaVersion, found))
          else None
    }
    val anyInstalled: Boolean = roots.exists(_.stampVersion.isDefined)
    DriftScanResult(warnings = warnings, noSkillInstalled = !anyInstalled)
