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
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): RingEvidence
 */
final case class RingEvidence(
  ring: Ring,
  status: RingStatus,
  record: Option[LedgerRecord],
  note: Option[String]
)
