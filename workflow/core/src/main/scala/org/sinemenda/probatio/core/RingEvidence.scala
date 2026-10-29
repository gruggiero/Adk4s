package org.sinemenda.probatio.core

/**
 * The predecessor's per-ring checkpoint statuses — the exact tokens the
 * generated report emits. `Unevidenced` is not `skipped`: an unevidenced
 * ring is a finding, not a measurement of absence.
 *
 * spec: ledger-checkpoint-parity — Requirement: Every requested ring SHALL be represented exactly once in the generated checkpoint
 */
enum RingStatus:
  case Green, Failed, Unevidenced, SameSession, UnverifiedSession

object RingStatus:
  /** The wire token the report JSON emits for each status. */
  def token(status: RingStatus): String = status match
    case RingStatus.Green             => "green"
    case RingStatus.Failed            => "failed"
    case RingStatus.Unevidenced       => "unevidenced"
    case RingStatus.SameSession       => "same-session"
    case RingStatus.UnverifiedSession => "unverified-session"

/**
 * One requested ring's evidence in the checkpoint: the ring, its verdict,
 * the ledger record the verdict traces to (`None` when the ring is
 * unevidenced), and an optional explanatory note (the same-session and
 * unverified-session statuses carry the predecessor's note text).
 *
 * The producing session lives on the record itself
 * (`record.optional.session`) — evidence never invents a session a row
 * did not record.
 *
 * The raw constructor is private: an outcome-bearing entry requires its
 * evidence — `RingEvidence(Green, record = None)` is unconstructible,
 * which is the defect the checkpoint exists to prevent (a ring outcome
 * written from memory). The only construction paths are
 * [[RingEvidence.unevidenced]] (no outcome, no record) and
 * [[RingEvidence.evidenced]] (a status plus the record it rests on).
 * A `final class`, not a case class: a private case-class constructor
 * still emits a PUBLIC `copy`, which would let a caller clone an entry
 * with the record stripped — sealing `copy` is the codebase's own
 * convention (see ArmTree).
 *
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): RingEvidence
 * spec: ledger-checkpoint-cutover — Compile-Negative: A checkpoint entry asserting an outcome with no evidence
 */
final class RingEvidence private (
  val ring: Ring,
  val status: RingStatus,
  val record: Option[LedgerRecord],
  val note: Option[String]
)

object RingEvidence:

  /** An unevidenced ring — no outcome, no record, no note. */
  def unevidenced(ring: Ring): RingEvidence =
    new RingEvidence(ring, RingStatus.Unevidenced, None, None)

  /**
   * An evidenced ring: a status with the record it rests on. The record
   * is a required `LedgerRecord`, not an `Option` — an outcome-bearing
   * entry without evidence cannot be constructed.
   */
  def evidenced(
    ring: Ring,
    status: RingStatus,
    record: LedgerRecord,
    note: Option[String]
  ): RingEvidence =
    new RingEvidence(ring, status, Some(record), note)

end RingEvidence
