package org.sinemenda.probatio.guard

import org.sinemenda.probatio.core.Outcome

/**
 * The feature-freeze guard's decisions (spec 6,
 * feature-freeze-guard-integrity).
 *
 * Two decisions share the guard:
 *
 *   - `reviewFeatureFreeze` judges a proposed change: any constructed
 *     violation is a feature-freeze breach and is rejected. Acceptance is
 *     not reachable here — an accept verdict names the corpus it was earned
 *     against, and reviewing a violation has no corpus.
 *   - `guardOutcome` judges a corpus run: the corpus resolution and the
 *     per-fixture disagreements produce the guard's three-way report —
 *     `Ran(Accepted(corpus))` when the freeze held, `Finding` naming every
 *     disagreeing fixture and both verdicts when it did not, and
 *     `Undetermined` naming the corpus when it could not be resolved.
 *     Could-not-determine is never freeze-upheld.
 *
 * The report reuses `Outcome` — the workflow's three-way error algebra —
 * rather than introducing a parallel hierarchy.
 *
 * spec: feature-freeze-guard-integrity — Requirement: An empty corpus is could-not-determine, never a pass
 * spec: feature-freeze-guard-integrity — Contract: guardOutcome
 */
object FeatureFreezeGuard:

  /**
   * The change whose specification fixtures are the guard's corpus. The
   * corpus belongs to the port that shipped the ported implementation; it
   * is resolved by name so that archiving the change — a normal lifecycle
   * step — does not empty it.
   */
  val corpusChangeName: String = "complete-probatio-cutover"

  /**
   * Reviews a constructed violation against the feature-freeze contract:
   * every violation class (`NewLintCheck`, `VerdictAlteration`,
   * `NewWorkflowFeature`) is out of scope for a port, so the verdict is
   * always `Rejected` carrying the violation and its reason.
   *
   * spec: feature-freeze-guard-integrity — Requirement: The check-identifier set stays closed across the port
   */
  def reviewFeatureFreeze(violation: FeatureFreezeViolation): FeatureFreezeVerdict =
    val reason: String = violation match
      case FeatureFreezeViolation.NewLintCheck(id) =>
        s"new lint check $id is a workflow feature, not a port — file separately"
      case FeatureFreezeViolation.VerdictAlteration(fixture, expected, actual) =>
        s"verdict on $fixture changed from $expected to $actual — behavior delta, not a port bug"
      case FeatureFreezeViolation.NewWorkflowFeature(desc) =>
        s"$desc is a new workflow feature, not a port — file separately"
    FeatureFreezeVerdict.Rejected(violation, reason)

  /**
   * The emitted check identifiers outside the recorded closed set — the
   * freeze violation a ported implementation commits by emitting a check
   * the predecessor never had.
   *
   * spec: feature-freeze-guard-integrity — Requirement: The check-identifier set stays closed across the port
   */
  def unknownCheckIds(emitted: Set[String]): Set[String] =
    emitted.filterNot(KnownCheckId.isKnown)

  /**
   * The guard's three-way report for one corpus run.
   *
   *   - `NotFound` — `Outcome.Undetermined` naming the corpus and every
   *     location searched; never freeze-upheld.
   *   - `Resolved` with no disagreements — `Ran(Accepted(corpus))`: the
   *     freeze is upheld, and the verdict carries the corpus that earned it.
   *   - `Resolved` with disagreements — `Finding` naming each fixture and
   *     both the predecessor's and the ported verdict.
   *
   * `disagreements` refines the contract's `List[FixtureVerdict]`: a
   * disagreement must name the fixture and both verdicts, which is exactly
   * `VerdictAlteration`.
   *
   * spec: feature-freeze-guard-integrity — Contract: guardOutcome
   * spec: feature-freeze-guard-integrity — Scenario: Adversarial — a differing verdict is reported with both values
   */
  def guardOutcome(
    resolution: CorpusResolution,
    disagreements: List[FeatureFreezeViolation.VerdictAlteration]
  ): Outcome[FeatureFreezeVerdict] =
    resolution match
      case CorpusResolution.NotFound(searched) =>
        val where: String = searched.map((p: os.Path) => p.toString).mkString(", ")
        Outcome.Undetermined(s"specification corpus not located; searched: $where")
      case CorpusResolution.Resolved(corpus) =>
        disagreements match
          case Nil => Outcome.Ran(FeatureFreezeVerdict.Accepted(corpus))
          case ds =>
            val detail: String = ds
              .map((d: FeatureFreezeViolation.VerdictAlteration) =>
                s"${d.fixture}: expected ${d.expected}, got ${d.actual}"
              )
              .mkString("; ")
            Outcome.Finding(s"verdict disagreements against the predecessor — $detail")
