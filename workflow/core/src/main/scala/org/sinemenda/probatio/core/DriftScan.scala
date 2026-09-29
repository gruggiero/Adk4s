package org.sinemenda.probatio.core

/**
 * The base an install root is resolved against.
 *
 * - `RepoRoot` — repository-local root (`$REPO/.agents/skills` etc.)
 * - `UserHome` — user-scoped root (`$HOME/.agents/skills` etc.)
 */
enum RootBase:
  case RepoRoot, UserHome

/**
 * A reference to one searched install root: which base it resolves against
 * and its path relative to that base. Pure data — resolution to an absolute
 * path is the CLI reader's job (R-ARCH1).
 *
 * The instruction document under each root is
 * `<relativePath>/openspec-spec-lint/SKILL.md`.
 */
final case class InstallRootRef(base: RootBase, relativePath: String):
  /** The instruction document's path relative to the base. */
  def skillDocument: String = s"$relativePath/openspec-spec-lint/SKILL.md"

/**
 * The six install roots the workflow searches, as a fixed-arity record.
 *
 * Fixed arity is the point: the drift-blindness defect was a root list that
 * silently omitted the three user-scoped roots. A record with six named
 * fields cannot be narrowed without a compile error, and no `List` literal
 * exists to shorten.
 *
 * spec: live-fact-banner — Requirement: Instruction drift is scanned across every install root the workflow searches
 * spec: live-fact-banner — Compile-Negative: DriftScan.installRoots referenced as a list of fewer roots than the workflow searches
 */
final case class InstallRoots(
  repoAgents: InstallRootRef,
  repoClaude: InstallRootRef,
  repoPi: InstallRootRef,
  homeAgents: InstallRootRef,
  homeClaude: InstallRootRef,
  homeZcode: InstallRootRef
):
  /** All searched roots, in scan order. */
  val all: List[InstallRootRef] =
    List(repoAgents, repoClaude, repoPi, homeAgents, homeClaude, homeZcode)

  /** The number of roots the workflow searches — always six. */
  def length: Int = all.length

/**
 * The read state of one install root.
 *
 * - `Absent`          — no instruction document at the root
 * - `PresentNoStamp`  — document present but declares no schema version
 *                       (pre-v7 install)
 * - `Stamped`         — document present with a `generatedBy` stamp:
 *                       `probatio-schema/<N>` (New) or the pre-rename
 *                       `verified-scala3-schema/<N>` (Legacy)
 * - `Unreadable`      — the root or its document could not be read;
 *                       never collapsible to `Absent`
 *
 * spec: live-fact-banner — Scenario: Edge case — a root carrying a pre-rename stamp is reported as pre-rename
 * spec: live-fact-banner — Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent
 */
enum InstallRootState:
  case Absent
  case PresentNoStamp
  case Stamped(version: Int, format: StampFormat)
  case Unreadable(reason: String)

/**
 * A skill install scan result for one of the six install roots.
 *
 * spec: live-fact-banner — Requirement: Instruction drift is scanned across every install root the workflow searches
 */
final case class InstallRootScan(
  rootPath: String,
  state: InstallRootState
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
  final case class PreRenameStamp(rootPath: String, expected: Option[Int], found: Int) extends DriftWarning:
    val message: String = expected match
      case Some(e) =>
        s"drift: root $rootPath has pre-rename stamp (verified-scala3-schema/$found) — " +
          s"migrate to probatio-schema/$e"
      case None =>
        s"drift: root $rootPath has pre-rename stamp (verified-scala3-schema/$found) — " +
          s"repository schema version unknown"

  /** A root whose instruction document declares no schema version (pre-v7 install). */
  final case class NoStampDeclared(rootPath: String) extends DriftWarning:
    val message: String =
      s"drift: root $rootPath declares no schema version — pre-v7 install"

  /** A root that could not be read — reported, never treated as absent. */
  final case class Unreadable(rootPath: String, reason: String) extends DriftWarning:
    val message: String = s"drift: root $rootPath could not be read — $reason"

/** The drift scan result. */
final case class DriftScanResult(
  warnings: List[DriftWarning],
  noSkillInstalled: Boolean
)

/**
 * The drift scan as a pure function (R-C5b component).
 *
 * Compares the schema version against `generatedBy` stamps across the six
 * install roots — three repository-local and three user-scoped. Returns
 * drift warnings + re-install instruction, or an explicit "no skill
 * installed" line when no root carries an instruction document.
 *
 * spec: probatio-core — Requirement: Instruction drift is detected across all install roots
 * spec: probatio-core — Scenario: silence about drift is never emitted (adversarial)
 * spec: probatio-core — Scenario: silence about checking is never emitted (adversarial)
 * spec: live-fact-banner — Property: every-searched-root-is-scanned
 */
object DriftScan:

  /**
   * The six install roots the workflow searches: `.agents/skills`,
   * `.claude/skills`, `.pi/skills` under the repository root, and
   * `.agents/skills`, `.claude/skills`, `.zcode/skills` under the user's
   * home — the predecessor's own list, verbatim.
   */
  val installRoots: InstallRoots = InstallRoots(
    repoAgents = InstallRootRef(RootBase.RepoRoot, ".agents/skills"),
    repoClaude = InstallRootRef(RootBase.RepoRoot, ".claude/skills"),
    repoPi = InstallRootRef(RootBase.RepoRoot, ".pi/skills"),
    homeAgents = InstallRootRef(RootBase.UserHome, ".agents/skills"),
    homeClaude = InstallRootRef(RootBase.UserHome, ".claude/skills"),
    homeZcode = InstallRootRef(RootBase.UserHome, ".zcode/skills")
  )

  /**
   * Scan the install roots for drift against the schema version.
   *
   * spec: probatio-core — Scenario: a root with a matching stamp produces no drift warning
   * spec: probatio-core — Scenario: a root with a mismatched stamp produces a drift warning
   * spec: probatio-core — Scenario: no installed skills produces an explicit line
   * spec: probatio-core — Scenario: a pre-rename stamp is treated as drift with a migration message
   * spec: live-fact-banner — Scenario: Adversarial — no root is silently skipped
   */
  def scan(schemaVersion: Option[Int], roots: List[InstallRootScan]): DriftScanResult =
    val warnings: List[DriftWarning] = roots.flatMap { root =>
      root.state match
        case InstallRootState.Absent =>
          None
        case InstallRootState.PresentNoStamp =>
          Some(DriftWarning.NoStampDeclared(root.rootPath))
        case InstallRootState.Unreadable(reason) =>
          Some(DriftWarning.Unreadable(root.rootPath, reason))
        case InstallRootState.Stamped(found, format) =>
          format match
            case StampFormat.Legacy =>
              // A pre-rename stamp is reported unconditionally — the naming
              // is a fact of the document, not a version comparison, so no
              // baseline is needed.
              Some(DriftWarning.PreRenameStamp(root.rootPath, schemaVersion, found))
            case StampFormat.New =>
              // A version comparison requires the repository's own version;
              // without one no drift claim is made either way.
              schemaVersion match
                case Some(expected) if found != expected =>
                  Some(DriftWarning.VersionMismatch(root.rootPath, expected, found))
                case _ => None // danger-scan:allow no-drift-claim — equal versions or no baseline make no claim
    }
    val anyDocumentFound: Boolean = roots.exists(_.state != InstallRootState.Absent)
    DriftScanResult(warnings = warnings, noSkillInstalled = !anyDocumentFound)

end DriftScan
