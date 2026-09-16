package org.sinemenda.probatio.core

/**
 * The heartbeat record written on every gate invocation that passes the
 * relevance guard: when the gate last ran, for which event, in which format.
 *
 * The installation probe (`--check-installed`) reads this record and reports
 * whether the gate has run in this repository.
 *
 * Forward reference (recorded at spec 3 Step 0): `HeartbeatRecord` is listed
 * as a spec-8 concept; spec 3 introduces the minimal form because the
 * heartbeat and probe are exercised by `gate-payload.bats`, spec 3's Ring 3
 * parity obligation. Spec 8 records it as modified, not introduced.
 *
 * spec: gate-event-completeness — Concepts Introduced: HeartbeatRecord
 * spec: gate-event-completeness — Requirement: The installation probe reports whether the gate has run
 */
final case class HeartbeatRecord(
  ts: String,
  event: String,
  format: String
)
