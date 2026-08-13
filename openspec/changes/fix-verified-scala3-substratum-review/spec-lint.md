# Spec Lint Report

## Mechanical pre-pass

**openspec validate --strict**: PASS — 5 spec files, 0 validation errors.

**spec-lint.sh**: `5 spec file(s), 0 FAIL, 17 WARN` — all warnings are W1/W3 (non-blocking). No F-checks failed.

### F-check fixes (spec-lint.sh)

| Spec | Error | Fix |
|------|-------|-----|
| all 5 specs | F6: Source column in Proof Obligations table used bare obligation text, not `Requirement: <title>` format | Rewrote all 5 Proof Obligations tables to use `Requirement: <exact title> + Scenario: <exact heading>` in the Source column |
| all 5 specs | F7: Requirements not named by any proof obligation (table used `#` column instead of Obligation column with Source references) | Fixed by the same rewrite — every requirement now has at least one row naming it via `Requirement: <title>` |

## Checks

### 1. Given/When/Then completeness
**PASS** — All 28 requirements across 5 specs have concrete Given/When/Then clauses.

### 1b. SHALL/MUST placement
**PASS** — All requirements open with a normative SHALL/MUST statement on a single line before the first `**Given**` (verified by `openspec validate --strict` and spec-lint.sh F1).

### 1c. Enum/dispatch variant coverage
**PASS** — No requirement asserts "identical/same/preserved behavior" over an enum/dispatch parameter.

### 2. Then observability
**PASS** — Every Then clause asserts an observable result (exit code, ledger row field, JSON field, file existence, trace line, reason-code). No wall-clock expectations.

### 3. Error path coverage
**PASS** — Every requirement has at least one error/edge-case scenario (missing artifact, failed run, missing python3, diverging replay, missing baseline, changed artifact).

### 4. Vague words
**W1 (6 WARNs, accepted)** — The flagged instances use "valid" in concrete context:
- "the row is valid" — the Then asserts `exit: 0` (observable)
- "are still valid" — refers to ledger rows matching a baseline (concrete)
- "treats a ledger row as valid" — describes the forgiveness semantics, with concrete Then clauses
- "a valid JSON report" — the Then asserts the report shape (observable)
- "drift message names the correct script" — "correct" refers to `scanner/install-skills.sh` (concrete)

These are acceptable: the word appears in context with a concrete observable definition next to it.

### 5. Concept resolution
**PASS** — All specs declare `(none)` in Concepts Used tables. This is a workflow-self-change with no Scala domain concepts. No inventory references to verify.

### 6. Generator strategies
**N/A** — No property-based tests (Hedgehog/ScalaCheck). All tests are bats scenario tests with fixed fixtures, not generative properties. The bats suite uses enumerate-don't-claim-sampled conventions.

### 7. Temporal trigger/response
**N/A** — No temporal properties. Bash scripts are sequential; there are no event-driven or time-based invariants.

### 8. Unreachable claims
**N/A** — No "unreachable" claims. The specs describe observable behavior of bash/jq scripts.

### 9. Enum extension / type-widening
**N/A** — No Scala enums or ADTs extended. The `failed` reason-code is a new string value in a bash jq filter, not a Scala sealed trait variant.

### 10. Proof obligations complete
**PASS** — Every requirement is named by at least one Proof Obligations row with `Requirement: <exact title>` in the Source column. F6 (Source resolvable) and F7 (every requirement named) both pass. F8 (typed source exists) is N/A — there are no typed sources (no Scala types). F9 (artifact resolves at Step 12) will be verified at apply Step 12 — the artifacts are bats test files that will be created.

### 11. Consumer-facing surface
**PASS** — The consumer-facing surfaces are bash subcommands (`ledger.sh run`, `ledger.sh verify`, `openspec-graph.py export`, `spec-lint.sh --format json`). Each has scenarios asserting what the consumer observes (exit codes, JSON fields, error messages).

### 12. Error variants type-feasible
**PASS** — All error paths are bash exit codes (1, 2) or jq validation failures. No type feasibility concerns (no Scala return types).

### 13. Adversarial scenarios for negatives
**W3 (11 WARNs, accepted)** — All W3-flagged requirements have at least one scenario whose input is forbidden by the requirement:
- "Discharge requires a green run, not any run" — Scenario: a failed run does not discharge (input: exit 1, forbidden: discharging)
- "Capture mode observes the exit code" — Scenario: --exit is not accepted (input: --exit flag, forbidden: accepting it)
- "Verify replays recorded commands" — Scenario: a diverging replay (input: wrong recorded exit, forbidden: matching)
- "Verify exits non-zero on any divergence" — Scenario: one diverge exits non-zero (input: divergence, forbidden: exit 0)
- "New ledger fields are backward-compatible" — Scenario: legacy rows are readable (input: missing fields, forbidden: erroring)
- "Runnable commands must agree with their recorded exit" — Scenario: grep no-match disagreement (input: exit 1 recorded as 0, forbidden: agreeing)
- "Bash chain-state degrades gracefully without python3" — Scenario: degraded mode is documented (input: missing python3, forbidden: silent wrong verdicts)
- "Heartbeat is written after the relevance guard" — Scenario: non-openspec repo is not polluted (input: non-openspec repo, forbidden: creating .git/verified-scala3-gate/)
- "Payload named-unresolved list is capped" — Scenario: list capped at N (input: 60 entries, forbidden: listing all 60)
- "Unchanged artifacts forgive stale evidence" — Scenario: changed artifact is NOT forgiven (input: changed artifact, forbidden: forgiving)
- "INSTRUCTION DRIFT remediation references the correct script" — Scenario: drift message names the correct script (input: drift detected, forbidden: naming sync-skills.sh)

### 14. MUST-CONFIRM
**N/A** — No externally-sourced classification tables or code mappings. All values are defined by the specs themselves.

### 15. ALTITUDE
**PASS** — No Scala code identifiers in Given/When/Then. The specs reference bash script names (`ledger.sh`, `chain-state.sh`, `gate.sh`) and JSON field names (`.exit`, `.sha256`) — these are the domain concepts of the workflow-tooling layer, not Scala code identifiers. Concepts Used (behavioral) tables all declare `(none)`.

### 16. CONCURRENCY
**N/A** — No concurrent behavior. Bash scripts are sequential. No deterministic test kit needed.

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| `specs/discharge-fidelity/spec.md` | PASS | 0 — 4 W1/W3 warnings (accepted) |
| `specs/evidence-capture/spec.md` | PASS | 0 — 5 W1/W3 warnings (accepted) |
| `specs/fact-extraction-unification/spec.md` | PASS | 0 — 2 W1/W3 warnings (accepted) |
| `specs/judgment-ring-integrity/spec.md` | PASS | 0 — 1 W3 warning (accepted) |
| `specs/workflow-hygiene/spec.md` | PASS | 0 — 3 W1/W3 warnings (accepted) |

**Overall: PASS** — implementation-order may be generated.
