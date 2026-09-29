package org.sinemenda.probatio.core

/**
 * The repository applicability facts a lint run depends on, supplied as
 * data (R-C5a — the engine reads no files and no environment).
 *
 * Every fact is a `FactRead`: `Absent` disables the dependent check (the
 * predecessor's `-d`/`-f` guards), `Unreadable` makes the whole run
 * `Outcome.Undetermined` — a fact that could not be read is never
 * silently treated as absent.
 *
 * `installRoots` carries per-root read state (not `FactRead`) because the
 * drift scan already distinguishes `Absent`/`Unreadable` per root and
 * reports each unreadable root as a drift finding — a root scan never
 * blocks the run.
 *
 * spec: spec-lint-engine — Concepts Introduced (new): LintContext
 * spec: spec-lint-engine — Requirement: A check whose applicability depends on a repository fact reports inapplicability when the fact is absent and could-not-determine when the fact cannot be read
 */
final case class LintContext(
  schemaVersion: FactRead[Int],
  registry: FactRead[Int],
  registryConcepts: FactRead[List[String]],
  inventoryTypes: FactRead[List[String]],
  profile: FactRead[Option[String]],
  installRoots: List[InstallRootScan]
):

  /** The registry directory is present — gates F10 and the W7 scan. */
  def hasRegistry: Boolean = registry match
    case FactRead.Present(_) => true
    case _ => // danger-scan:allow reject-to-false — only a Present registry enables the F10/W7 checks
      false

  /**
   * The workflow's named-type inventory that no registry concept
   * documents — `inventory ∖ registry`, the predecessor's `comm -23`
   * over `# Concept:` headings. Empty when either side was not read;
   * the run is `Undetermined` in that case before this is consulted.
   */
  def codeIdentifiers: Set[String] =
    (inventoryTypes, registryConcepts) match
      case (FactRead.Present(types), FactRead.Present(names)) =>
        types.toSet -- names.toSet
      case _ => // danger-scan:allow reject-to-empty — a non-Present side yields no identifiers
        Set.empty
