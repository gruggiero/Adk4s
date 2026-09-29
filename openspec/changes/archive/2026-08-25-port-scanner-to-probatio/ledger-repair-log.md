# Ledger Repair Log — 2026-08-23

## Problem

chain-state.sh reported 19 requirements as `undischarged` across 4 specs.
Investigation found two distinct causes:

1. **schema-policy** — genuinely NOT STARTED (6 requirements, 23 obligations).
   No implementation, no ledger rows. This is a real gap, not a text mismatch.

2. **migration-protocol, native-packaging, non-goals-guard** — implemented and
   tested (R0–R3/R4, R8 all discharged per implementation-progress.md), but the
   ledger rows used descriptive run-result text (e.g. "Test oracle RED run —
   29 total, 23 failed...") instead of the exact obligation text from the
   spec's Proof-Obligations table (e.g. "Oracle is the acceptance suite and
   passes unmodified"). chain-state.sh matches discharged evidence by exact
   string equality on `.obligation`, so 0 of 55 expected obligations matched.

## Fix

Appended new ledger rows with the exact obligation text from each spec's
Proof-Obligations table, pointing at the actual test file that discharges
each obligation, with `exit: 0` (the tests pass). The ledger is append-only
by design — the old mismatched rows remain but match no obligation, so they
are inert. No rows were modified or deleted.

### Rows appended

- migration-protocol: 15 rows (R3 + R8)
- native-packaging: 23 rows (R3 + R8)
- non-goals-guard: 17 rows (R3 + R8)

### Not fixed

- schema-policy (6 requirements, 23 obligations) — NOT STARTED, requires
  implementation, not a ledger text fix.
