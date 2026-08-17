package org.adk4s.record

// ── Redaction — payload redaction function type ────────────────────────
// spec: add-adk4s-record/recorded-wrappers — Requirement: Redaction applies after key computation
//
// A Redaction function transforms a RecordPayload before it reaches the
// sink. It is applied AFTER key computation so that redaction does not
// change hit rates (two requests differing only in redacted content still
// share a key if their unredacted canonical forms are equal).
//
// The function operates on the typed RecordPayload union (generated from
// Smithy IDL), not untyped JSON — type-safe, compile-time checked.
type Redaction = RecordPayload => RecordPayload

object Redaction:
  /** The identity redaction — no-op. */
  val identity: Redaction = payload => payload
