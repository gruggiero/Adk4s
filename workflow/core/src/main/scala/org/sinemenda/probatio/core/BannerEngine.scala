package org.sinemenda.probatio.core

/** Inputs to the banner/drift engine (R-C5b).
  *
  * All file I/O is performed by the CLI layer and passed as values. The
  * banner engine is a pure function over these declared inputs.
  *
  * spec: probatio-core — Requirement: The drift, context, and banner engine is a pure function
  */
final case class BannerInputs(
  schemaVersion: Int,
  skillInstallScan: List[InstallRootScan],
  registryPresent: Boolean,
  registryConceptCount: Int,
  inventoryPresent: Boolean,
  inventoryTypeCount: Int,
  profilePresent: Boolean,
  detectedTestKit: Option[String],
  activeChanges: List[ActiveChangeWithChainState]
)

/** An active change with its live chain-state data. */
final case class ActiveChangeWithChainState(
  name: String,
  artifactsPresent: List[String],
  nextArtifact: Option[String],
  chainState: Option[Either[ChainStateUndetermined, ChainStateReport]]
)

/** The assembled banner output. */
final case class BannerOutput(
  lines: List[String],
  payload: String
)

/** The banner/drift engine as a pure function (R-C5b).
  *
  * `(schemaVersion, skillInstallScan, registry/inventory/profile presence,
  * detectedTestKit, activeChanges) → banner text`. Byte-identical output
  * for identical inputs. Reads no files, consults no environment variables,
  * queries no wall-clock time.
  *
  * spec: probatio-core — Requirement: The drift, context, and banner engine is a pure function
  * spec: probatio-core — Requirement: The banner is assembled from live reads, not remembered state
  * spec: probatio-core — Property: Banner engine produces byte-identical output for identical inputs
  */
object BannerEngine:

  /** Render the banner from declared inputs.
    *
    * spec: probatio-core — Scenario: identical inputs produce byte-identical output
    * spec: probatio-core — Scenario: different inputs produce different output
    * spec: probatio-core — Scenario: the invariant block is verbatim-match text
    * spec: probatio-core — Scenario: the session-context block reflects live chain state
    * spec: probatio-core — Scenario: the trailer states facts are read from disk (adversarial)
    */
  def render(inputs: BannerInputs): BannerOutput =
    val invariant: String = invariantText(inputs.schemaVersion)
    val contextLines: List[String] = buildContextLines(inputs)
    val chainStateLines: List[String] = buildChainStateLines(inputs)
    val driftLines: List[String] = buildDriftLines(inputs)
    val allLines: List[String] =
      List(invariant) ++
        contextLines ++
        chainStateLines ++
        driftLines ++
        List(trailerText)
    BannerOutput(lines = allLines, payload = allLines.mkString("\n"))

  /** The verbatim invariant block text (schema-version-dependent). */
  def invariantText(schemaVersion: Int): String =
    s"""verified-scala3 — invariant (schema v$schemaVersion)
       |  NEVER LET A CLAIM OUTRUN ITS EVIDENCE.
       |  "N/A" / "passes" / "already handled" are CLAIMS, not verdicts.""".stripMargin

  /** The trailer text — "READ FROM DISK … facts, not recollection". */
  val trailerText: String =
    """The lines above were READ FROM DISK just now; they are facts, not
      |recollection. Where a conditional check is marked APPLIES, "N/A" is not a
      |valid verdict for it — see the spec-lint artifact instruction. An unresolved
      |requirement listed above is a finding, not a formality.""".stripMargin

  /** Build the context-facts lines from the declared inputs. */
  private def buildContextLines(inputs: BannerInputs): List[String] =
    val driftResult: DriftScanResult =
      DriftScan.scan(inputs.schemaVersion, inputs.skillInstallScan)

    val schemaLine: String =
      s"  schema                openspec/schemas/verified-scala3  v${inputs.schemaVersion}"

    val driftWarningLines: List[String] =
      if driftResult.noSkillInstalled then
        List("  no skill installed across any of the searched roots")
      else
        driftResult.warnings.map(w => s"  !! INSTRUCTION DRIFT: ${w.message}")

    val registryLine: String =
      if inputs.registryPresent then
        s"  behavioural registry  openspec/concepts/             PRESENT (${inputs.registryConceptCount} concepts)"
      else
        "  behavioural registry  openspec/concepts/             ABSENT"

    val inventoryLine: String =
      if inputs.inventoryPresent then
        s"  type inventory        openspec/concept-inventory.md  PRESENT (${inputs.inventoryTypeCount} typed rows)"
      else
        "  type inventory        openspec/concept-inventory.md  ABSENT"

    val profileLine: String =
      if inputs.profilePresent then
        s"  capability profile    openspec/capability-profile.md PRESENT"
      else
        "  capability profile    openspec/capability-profile.md ABSENT"

    val testKitLine: String = inputs.detectedTestKit match
      case Some(kit) => s"       deterministic test kit detected: $kit"
      case None      => "       no deterministic test kit detected"

    List(schemaLine) ++
      driftWarningLines ++
      List(registryLine, inventoryLine, profileLine, testKitLine)

  /** Build the chain-state lines from active changes. */
  private def buildChainStateLines(inputs: BannerInputs): List[String] =
    inputs.activeChanges.flatMap { change =>
      val nameLine: String = s"  active change        ${change.name}"
      val artifactsLine: String =
        s"    artifacts present  ${change.artifactsPresent.mkString(", ")}"
      val nextLine: String = change.nextArtifact match
        case Some(next) => s"    next artifact      $next"
        case None       => "    next artifact      none — all planning artifacts exist"

      val chainStateLines: List[String] = change.chainState match
        case None => List("    chain state         (not computed)")
        case Some(Left(u)) =>
          List(s"    chain state         UNDETERMINED — ${u.reason}")
        case Some(Right(report)) =>
          val header: String =
            s"    chain state         ${change.name}"
          val counts: String =
            s"    total ${report.total}  bound ${report.bound}  resolved ${report.resolved}  discharged ${report.discharged}  unresolved ${report.unresolved.length}"
          val unresolvedLines: List[String] = report.unresolved.map { entry =>
            s"    ${entry.requirement} (${entry.reasons.map(UnresolvedReason.asString).mkString(", ")})"
          }
          List(header, counts) ++ unresolvedLines

      List(nameLine, artifactsLine, nextLine) ++ chainStateLines
    }

  /** Build drift lines from the skill install scan. */
  private def buildDriftLines(inputs: BannerInputs): List[String] =
    val driftResult: DriftScanResult =
      DriftScan.scan(inputs.schemaVersion, inputs.skillInstallScan)
    if driftResult.noSkillInstalled then
      List("  no skill installed — re-install to enable drift checking")
    else if driftResult.warnings.nonEmpty then
      driftResult.warnings.map(w => s"  ${w.message}")
    else
      List.empty
