package org.sinemenda.probatio.core

/**
 * A `LedgerRecord` paired with its `ProvenanceFields`, produced by the
 * 15-clause validator when all clauses pass.
 *
 * The core `LedgerRecord` is unchanged (10 required fields); provenance
 * is layered on top as a companion value.
 *
 * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
 * spec: provenance-validation — Concepts Introduced: ValidatedRecord
 */
final case class ValidatedRecord(
  record: LedgerRecord,
  provenance: ProvenanceFields
)
