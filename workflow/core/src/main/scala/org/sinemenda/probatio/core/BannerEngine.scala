package org.sinemenda.probatio.core

/**
 * Inputs to the banner/drift engine (R-C5b).
 *
 * All file I/O is performed by the CLI layer and passed as values. The
 * banner engine is a pure function over these declared inputs.
 *
 * The only construction path is `BannerInputs.from(RepositoryFacts)` — the
 * primary constructor is private and the class is not a case class, so no
 * `apply` or `copy` exists. The banner can therefore never be assembled from
 * hand-written literals; that path was the fabrication defect.
 *
 * spec: probatio-core — Requirement: The drift, context, and banner engine is a pure function
 * spec: live-fact-banner — Compile-Negative: BannerInputs constructed from literals rather than from a RepositoryFacts value
 */
final class BannerInputs private (
  val facts: RepositoryFacts
)

object BannerInputs:

  /**
   * The only public constructor: banner inputs are the facts the reader
   * obtained during this invocation.
   */
  def from(facts: RepositoryFacts): BannerInputs =
    new BannerInputs(facts)

/** The assembled banner output. */
final case class BannerOutput(
  lines: List[String],
  payload: String
)

/**
 * The banner/drift engine as a pure function (R-C5b).
 *
 * `RepositoryFacts → banner text`. Byte-identical output for identical
 * inputs. Reads no files, consults no environment variables, queries no
 * wall-clock time.
 *
 * spec: probatio-core — Requirement: The drift, context, and banner engine is a pure function
 * spec: probatio-core — Requirement: The banner is assembled from live reads, not remembered state
 * spec: probatio-core — Property: Banner engine produces byte-identical output for identical inputs
 * spec: live-fact-banner — Property: banner-states-only-read-facts
 */
object BannerEngine:

  /**
   * Render the banner from declared inputs.
   *
   * spec: probatio-core — Scenario: identical inputs produce byte-identical output
   * spec: probatio-core — Scenario: different inputs produce different output
   * spec: probatio-core — Scenario: the invariant block is verbatim-match text
   * spec: probatio-core — Scenario: the session-context block reflects live chain state
   * spec: probatio-core — Scenario: the trailer states facts are read from disk (adversarial)
   */
  def render(inputs: BannerInputs): BannerOutput =
    val facts: RepositoryFacts = inputs.facts
    val versionText: String = facts.schemaVersion match
      case FactRead.Present(v)    => s"v$v"
      case FactRead.Absent        => "v?"
      case FactRead.Unreadable(_) => "v?"
    val invariant: String           = invariantText(versionText)
    val contextLines: List[String]  = buildContextLines(facts)
    val positionLines: List[String] = buildPositionLines(facts)
    val chainLines: List[String]    = buildChainStateLines(facts)
    val allLines: List[String] =
      invariant.split("\n").toList ++
        List("") ++
        List(s"verified-scala3 — session context (schema $versionText, injected by hooks/gate.sh)") ++
        contextHeader ++
        contextLines ++
        positionLines ++
        chainLines ++
        List(
          "",
          "  gate checks          scanner/spec-lint.sh · registry-check.sh · danger-scan.sh · scanner/chain-state.sh",
          ""
        ) ++
        trailerText.split("\n").toList
    BannerOutput(lines = allLines, payload = allLines.mkString("\n"))

  /** The verbatim invariant block text (schema-version-dependent). */
  def invariantText(schemaVersion: Int): String = invariantText(s"v$schemaVersion")

  private def invariantText(versionText: String): String =
    s"""verified-scala3 — invariant (schema $versionText)
       |  NEVER LET A CLAIM OUTRUN ITS EVIDENCE.
       |  "N/A" / "passes" / "already handled" are CLAIMS, not verdicts.""".stripMargin

  /** The context-block header — the spec-lint applicability preamble. */
  val contextHeader: List[String] = List(
    "spec-lint: CONTEXT — repository facts. These decide each conditional check's",
    "           APPLICABILITY. Compliance remains yours; applicability does not."
  )

  /** The trailer text — "READ FROM DISK … facts, not recollection". */
  val trailerText: String =
    """The lines above were READ FROM DISK just now; they are facts, not
      |recollection. Where a conditional check is marked APPLIES, "N/A" is not a
      |valid verdict for it — see the spec-lint artifact instruction. An unresolved
      |requirement listed above is a finding, not a formality.""".stripMargin

  /** Build the context-facts lines (schema, drift, registry, inventory, profile). */
  private def buildContextLines(facts: RepositoryFacts): List[String] =
    val schemaLine: String = facts.schemaVersion match
      case FactRead.Present(v) =>
        s"  schema                openspec/schemas/verified-scala3  v$v"
      case FactRead.Absent =>
        "  schema                openspec/schemas/verified-scala3  ABSENT"
      case FactRead.Unreadable(reason) =>
        s"  schema                openspec/schemas/verified-scala3  UNREADABLE — $reason"

    val driftLines: List[String] =
      val baseline: Option[Int] = facts.schemaVersion match
        case FactRead.Present(v) => Some(v)
        case _ => None // danger-scan:allow no-baseline — without a repo schema version there is no drift baseline
      val driftResult: DriftScanResult = DriftScan.scan(baseline, facts.installRoots)
      val warningLines: List[String]   = driftResult.warnings.flatMap(driftWarningLines)
      if driftResult.noSkillInstalled then
        List(
          "  (no openspec-spec-lint skill installed in the searched roots)",
          "    -> drift checking is unavailable until the skill is installed."
        ) ++ warningLines
      else warningLines

    val registryLines: List[String] = facts.registry match
      case FactRead.Present(n) =>
        List(
          s"  behavioural registry  openspec/concepts/             PRESENT ($n concepts)",
          "    -> check 17 ALTITUDE **APPLIES**. \"N/A\" is not a valid verdict for it.",
          "       F10 checks the structural half; W7 lists code-identifier candidates;",
          "       reading the clause prose for behavioural altitude is still your job."
        )
      case FactRead.Absent =>
        List(
          "  behavioural registry  openspec/concepts/             ABSENT",
          "    -> check 17 ALTITUDE is N/A (attested by this run, not assumed)."
        )
      case FactRead.Unreadable(reason) =>
        List(
          s"  behavioural registry  openspec/concepts/             UNREADABLE — $reason",
          "    -> check 17 ALTITUDE cannot be judged — the registry could not be read."
        )

    val inventoryLines: List[String] = facts.inventory match
      case FactRead.Present(n) =>
        val zeroRows: List[String] =
          if n == 0 then
            List(
              "    !! parsed 0 type rows — the file exists but this run read nothing",
              "       from it. Fix the table shape before trusting W7 silence."
            )
          else Nil
        List(
          s"  type inventory        openspec/concept-inventory.md  PRESENT ($n typed rows)",
          "    -> check 6 (reused concepts exist) **APPLIES**."
        ) ++ zeroRows
      case FactRead.Absent =>
        List(
          "  type inventory        openspec/concept-inventory.md  ABSENT",
          "    -> check 6 is N/A; run the concept scanner before trusting reuse claims."
        )
      case FactRead.Unreadable(reason) =>
        List(
          s"  type inventory        openspec/concept-inventory.md  UNREADABLE — $reason",
          "    -> check 6 cannot be judged — the inventory could not be read."
        )

    val profileLines: List[String] = facts.profile match
      case FactRead.Present(kit) =>
        kit match
          case Some(k) =>
            List(
              "  capability profile    openspec/capability-profile.md PRESENT",
              "    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**",
              s"       deterministic test kit detected: $k"
            )
          case None =>
            List(
              "  capability profile    openspec/capability-profile.md PRESENT",
              "    -> check 3 **APPLIES**. Check 18: no deterministic test kit detected —",
              "       a concurrency requirement here is a capability gap, not an N/A."
            )
      case FactRead.Absent =>
        List(
          "  capability profile    openspec/capability-profile.md ABSENT",
          "    -> run detect-capabilities first; checks 3 and 18 cannot be judged."
        )
      case FactRead.Unreadable(reason) =>
        List(
          s"  capability profile    openspec/capability-profile.md UNREADABLE — $reason",
          "    -> checks 3 and 18 cannot be judged — the profile could not be read."
        )

    schemaLine :: driftLines ++ registryLines ++ inventoryLines ++ profileLines

  /** The predecessor's multi-line drift text for one warning. */
  private def driftWarningLines(w: DriftWarning): List[String] = w match
    case DriftWarning.VersionMismatch(rootPath, expected, found) =>
      List(
        s"  !! INSTRUCTION DRIFT: skill at $rootPath is schema v$found, this schema is v$expected.",
        s"     Checks added after v$found are NOT in the instructions you are following.",
        "     Re-install (scanner/install-skills.sh) before trusting this report."
      )
    case DriftWarning.PreRenameStamp(rootPath, expected, found) =>
      val tail: String = expected match
        case Some(e) => s"migrate to probatio-schema/$e"
        case None    => "repository schema version unknown"
      List(
        s"  !! PRE-RENAME STAMP: $rootPath carries verified-scala3-schema/$found — $tail"
      )
    case DriftWarning.NoStampDeclared(rootPath) =>
      List(
        s"  !! skill $rootPath/openspec-spec-lint declares no schema version — pre-v7 install"
      )
    case DriftWarning.Unreadable(rootPath, reason) =>
      List(
        s"  !! $rootPath could not be read — $reason — drift state at this root is unknown"
      )

  /** Build the per-change workflow-position lines (name, artifacts, next). */
  private def buildPositionLines(facts: RepositoryFacts): List[String] =
    facts.activeChanges match
      case FactRead.Present(changes) =>
        changes.flatMap { change =>
          val nameLine: String = s"  active change        ${change.name}"
          val artifactLines: List[String] = change.artifacts match
            case FactRead.Present(scan) =>
              val presentText: String =
                if scan.present.isEmpty then "none" else scan.present.mkString(", ")
              val nextText: String = scan.next match
                case Some(ref) => s"${ref.id} (${ref.generates})"
                case None      => "none — all planning artifacts exist"
              List(
                s"    artifacts present  $presentText",
                s"    next artifact      $nextText"
              )
            case FactRead.Absent =>
              List("    artifact state     unknown — no artifact DAG read")
            case FactRead.Unreadable(reason) =>
              List(s"    artifact state     UNREADABLE — $reason")
          nameLine :: artifactLines
        }
      case FactRead.Absent =>
        Nil // no openspec/changes directory — the predecessor prints nothing
      case FactRead.Unreadable(reason) =>
        List(s"  active changes       UNREADABLE — $reason")

  /** Build the chain-state lines from active changes. */
  private def buildChainStateLines(facts: RepositoryFacts): List[String] =
    val changes: List[ActiveChangeWithChainState] = facts.activeChanges match
      case FactRead.Present(cs) => cs
      case _ => Nil // danger-scan:allow no-changes — absent/unreadable changes list has no per-change chain state
    changes.flatMap { change =>
      val header: String = s"  chain state           ${change.name}"
      val body: List[String] = change.chainState match
        case Left(u) =>
          List(s"    undetermined — ${u.reason}")
        case Right(report) =>
          val counts: String =
            s"    total ${report.total}  bound ${report.bound}  resolved ${report.resolved}  discharged ${report.discharged}  unresolved ${report.unresolved.length}"
          val unresolvedLines: List[String] =
            if report.unresolved.length > 10 then
              report.unresolved.take(10).map { entry =>
                s"    ${entry.requirement} (${entry.reasons.map(UnresolvedReason.asString).mkString(",")})"
              } ++ List(s"    +${report.unresolved.length - 10} more")
            else
              report.unresolved.map { entry =>
                s"    ${entry.requirement} (${entry.reasons.map(UnresolvedReason.asString).mkString(",")})"
              }
          counts :: unresolvedLines
      header :: body
    }

end BannerEngine
