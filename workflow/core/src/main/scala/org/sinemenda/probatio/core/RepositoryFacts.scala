package org.sinemenda.probatio.core

/**
 * The result of reading one repository fact during a run.
 *
 * Three disjoint states: the fact was read and is present (with its value),
 * the fact was read and is absent, or the fact could not be read at all.
 * `Unreadable` is a distinct state from `Absent` — reporting an unreadable
 * fact as absent is the fabrication defect this type exists to prevent.
 *
 * spec: live-fact-banner — Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent
 * spec: live-fact-banner — Formal Contract: bannerClaims (fact codes -1/0/n)
 */
enum FactRead[+A]:
  /** The fact was read and is present, carrying the read value. */
  case Present(value: A)

  /** The fact was read and is absent. */
  case Absent

  /** The fact could not be read; carries the reason. */
  case Unreadable(reason: String)

/**
 * One artifact in the schema's artifact DAG: the artifact id and the file
 * it generates inside a change directory.
 *
 * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
 */
final case class ArtifactRef(id: String, generates: String)

/**
 * The artifact state of one active change: which DAG artifacts are present
 * in the change directory and which artifact is next.
 *
 * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
 */
final case class ArtifactScan(
  present: List[String],
  next: Option[ArtifactRef]
)

/**
 * An active change with the facts read for it during this invocation:
 * the artifact state (a `FactRead` — the artifact DAG lives in schema.yaml,
 * so an absent schema means the state is unknown, never "all present") and
 * the chain-state result obtained through the live tool seam.
 *
 * `chainState` is always a result of the computation attempted during this
 * run: a failed or absent tool is `Left(undetermined)`, never a fabricated
 * clean report. "Not attempted" is unrepresentable — the reader always
 * attempts the computation for every active change.
 *
 * spec: live-fact-banner — Requirement: Active-change facts carry live chain-state, not a placeholder
 * spec: live-fact-banner — Scenario: Error path — undetermined chain state is rendered as undetermined, never as zero unresolved
 */
final case class ActiveChangeWithChainState(
  name: String,
  artifacts: FactRead[ArtifactScan],
  chainState: Either[ChainStateUndetermined, ChainStateReport]
)

/**
 * The facts a gate run reads from the repository, as a single record.
 *
 * Every field is produced by `RepositoryFactsReader.read` during the
 * invocation that states it. Nothing in this record is remembered state —
 * a field whose read failed is `Unreadable`, never silently `Absent`.
 *
 * Fields:
 * - `schemaVersion` — the `version:` field of
 *   `openspec/schemas/verified-scala3/schema.yaml`
 * - `registry` — `openspec/concepts/` presence + concept-document count
 * - `inventory` — `openspec/concept-inventory.md` presence + typed-row count
 * - `profile` — `openspec/capability-profile.md` presence + the detected
 *   deterministic test kit inside it
 * - `installRoots` — one `InstallRootScan` per searched install root
 *   (exactly `DriftScan.installRoots.length` entries)
 * - `activeChanges` — each active (non-archived) change with its artifact
 *   state and the chain-state result obtained during this invocation; a
 *   `FactRead` because an `openspec/changes/` directory that cannot be
 *   listed must not collapse to a fabricated empty list (spec SHALL-NOT)
 *
 * spec: live-fact-banner — Concepts Introduced: RepositoryFacts
 * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
 */
final case class RepositoryFacts(
  schemaVersion: FactRead[Int],
  registry: FactRead[Int],
  inventory: FactRead[Int],
  profile: FactRead[Option[String]],
  installRoots: List[InstallRootScan],
  activeChanges: FactRead[List[ActiveChangeWithChainState]]
):

  /**
   * A canonical encoding of the whole record, used as the suppression
   * fingerprint: two runs state the same facts iff their fingerprints are
   * equal. Every field is encoded — including change names, artifact
   * states, chain-state counts, unresolved requirement names and reasons,
   * unmapped obligations, and undetermined reasons — so a changed fact
   * always changes the fingerprint.
   *
   * spec: live-fact-banner — Property: suppression-tracks-facts
   */
  def fingerprint: String =
    ujson.write(RepositoryFacts.factsJson(this))

object RepositoryFacts:

  private def factJson[A](f: FactRead[A], encode: A => ujson.Value): ujson.Value =
    f match
      case FactRead.Present(v)    => ujson.Obj("state" -> "present", "value" -> encode(v))
      case FactRead.Absent        => ujson.Obj("state" -> "absent")
      case FactRead.Unreadable(r) => ujson.Obj("state" -> "unreadable", "reason" -> r)

  private def intFactJson(f: FactRead[Int]): ujson.Value =
    factJson(f, (i: Int) => ujson.Num(i.toDouble))

  private def strFactJson(f: FactRead[Option[String]]): ujson.Value =
    factJson(
      f,
      (o: Option[String]) =>
        o match
          case Some(s) => ujson.Str(s)
          case None    => ujson.Null
    )

  private def rootJson(r: InstallRootScan): ujson.Value =
    val stateJson: ujson.Value = r.state match
      case InstallRootState.Absent =>
        ujson.Obj("state" -> "absent")
      case InstallRootState.PresentNoStamp =>
        ujson.Obj("state" -> "present-no-stamp")
      case InstallRootState.Stamped(v, fmt) =>
        ujson.Obj("state" -> "stamped", "version" -> v, "format" -> fmt.toString)
      case InstallRootState.Unreadable(reason) =>
        ujson.Obj("state" -> "unreadable", "reason" -> reason)
    ujson.Obj("root" -> r.rootPath, "read" -> stateJson)

  private def artifactScanJson(a: FactRead[ArtifactScan]): ujson.Value =
    factJson(
      a,
      (s: ArtifactScan) =>
        ujson.Obj(
          "present" -> ujson.Arr(s.present.map(ujson.Str(_))*),
          "next" -> (s.next match
            case Some(r) =>
              ujson.Obj("id" -> ujson.Str(r.id), "generates" -> ujson.Str(r.generates))
            case None => ujson.Null
          )
        )
    )

  private def unresolvedEntryJson(e: UnresolvedEntry): ujson.Value =
    ujson.Obj(
      "spec"        -> e.spec,
      "requirement" -> e.requirement,
      "reasons"     -> ujson.Arr(e.reasons.map(r => ujson.Str(UnresolvedReason.asString(r)))*)
    )

  private def unmappedJson(o: UnmappedObligation): ujson.Value =
    ujson.Obj("spec" -> o.spec, "line" -> o.line, "artifact" -> o.artifact)

  private def changeJson(c: ActiveChangeWithChainState): ujson.Value =
    val chainJson: ujson.Value = c.chainState match
      case Left(u) =>
        ujson.Obj(
          "state"    -> "undetermined",
          "change"   -> u.change,
          "baseline" -> u.baseline,
          "reason"   -> u.reason.text
        )
      case Right(report) =>
        ujson.Obj(
          "state"               -> "report",
          "change"              -> report.change,
          "baseline"            -> report.baseline,
          "total"               -> report.total,
          "bound"               -> report.bound,
          "resolved"            -> report.resolved,
          "discharged"          -> report.discharged,
          "unresolved"          -> ujson.Arr(report.unresolved.map(unresolvedEntryJson)*),
          "unmappedObligations" -> ujson.Arr(report.unmappedObligations.map(unmappedJson)*)
        )
    ujson.Obj(
      "name"      -> c.name,
      "artifacts" -> artifactScanJson(c.artifacts),
      "chain"     -> chainJson
    )

  private def factsJson(f: RepositoryFacts): ujson.Value =
    ujson.Obj(
      "schemaVersion" -> intFactJson(f.schemaVersion),
      "registry"      -> intFactJson(f.registry),
      "inventory"     -> intFactJson(f.inventory),
      "profile"       -> strFactJson(f.profile),
      "installRoots"  -> ujson.Arr(f.installRoots.map(rootJson)*),
      "activeChanges" -> factJson(
        f.activeChanges,
        (cs: List[ActiveChangeWithChainState]) => ujson.Arr(cs.map(changeJson)*)
      )
    )

end RepositoryFacts
