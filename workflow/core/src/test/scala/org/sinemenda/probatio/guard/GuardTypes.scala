package org.sinemenda.probatio.guard

/**
 * The feature-freeze contract types (R-X1; spec 6,
 * feature-freeze-guard-integrity).
 *
 * These are the guard's vocabulary: the closed check-identifier set the
 * port must not extend, the violations the freeze forbids, the verdict a
 * review produces, and one fixture's measured verdict. They live outside
 * `NonGoalsGuardSpec` so the whole guard — types, corpus, decisions — is
 * a self-contained unit: the Ring-5 move-to-main procedure moves
 * `GuardTypes`, `GuardCorpus`, and `FeatureFreezeGuard` together.
 *
 * `FeatureFreezeVerdict.Accepted` carries the resolved corpus the verdict
 * was earned against — freeze-upheld is unconstructible without one.
 *
 * spec: port-scanner-to-probatio/non-goals-guard — Requirement: The port does not extend checks, alter verdicts, or add workflow features
 * spec: feature-freeze-guard-integrity — Concepts Used: FeatureFreezeVerdict
 * spec: feature-freeze-guard-integrity — Concepts Used: FeatureFreezeViolation
 * spec: feature-freeze-guard-integrity — Concepts Used: KnownCheckId
 * spec: feature-freeze-guard-integrity — Concepts Used: FixtureVerdict
 */

/** The feature-freeze contract (R-X1). */
enum FeatureFreezeViolation:
  case NewLintCheck(checkId: String)
  case VerdictAlteration(fixture: String, expected: String, actual: String)
  case NewWorkflowFeature(featureDescription: String)

enum FeatureFreezeVerdict:
  case Accepted(corpus: FixtureCorpus)
  case Rejected(violation: FeatureFreezeViolation, reason: String)

enum KnownCheckId:
  case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10

object KnownCheckId:
  val allIds: Set[String]          = values.map(_.toString).toSet
  def isKnown(id: String): Boolean = allIds.contains(id)

final case class FixtureVerdict(
  fixture: String,
  verdict: String,
  warnings: Set[String]
)
