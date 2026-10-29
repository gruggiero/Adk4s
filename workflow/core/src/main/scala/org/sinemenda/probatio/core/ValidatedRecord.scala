package org.sinemenda.probatio.core

/**
 * A `LedgerRecord` that has passed all 15 contract clauses.
 *
 * The record carries its provenance group itself (`record.optional`), so
 * the validated wrapper cannot disagree with it — `provenance` is a
 * derived view, never a second source of truth.
 *
 * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
 * spec: provenance-validation — Concepts Introduced: ValidatedRecord
 * spec: ledger-checkpoint-parity — Requirement: The record type SHALL carry the recorder's observation fields as first-class data
 */
final case class ValidatedRecord(
  record: LedgerRecord
):
  /** The record's own provenance group (sha256/digest/wallTime/source/session). */
  def provenance: LedgerRecordOptional = record.optional
