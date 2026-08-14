# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.

     NOTE: This is a workflow-self-change. "Concepts" here are bash/jq tooling
     capabilities, not Scala domain types. The dependency graph is based on
     which scripts/state each spec modifies and whether a spec's mechanism
     depends on another spec's mechanism being in place. -->

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | oracle-ordering-lock | `gate.sh --event tool-call`, oracle phase state, oracle-polarity predicate | `ledger.sh run` rows (shipped, D3) + `git merge-base`; the gate event is new but self-contained | medium |
| 2 | human-grant-lock | grant token, checkpoint-presentation record | spec 1's `tool-call` event (grant check is another clause in the same gate) | medium |
| 3 | ambient-evidence-capture | `gate.sh --event post-bash`, ring-shape match table | `ledger.sh` (shipped); independent of specs 1–2's `tool-call` clauses (different event) | medium |
| 4 | judgment-ring-provenance | `session` field on adversarial-review rows, checkpoint provenance check | D4 artifact requirement (shipped); `ledger.sh`/`checkpoint.sh` (shipped) | simple |
| 5 | harness-install-verification | `PreToolUse`/`tool_call` adapter wiring, apply-Step-0 `--check-installed`, schema v13 changelog | specs 1–2's `tool-call` event (the wiring makes it real on every harness); Devin three-level verification | medium |

### Topological sort rationale

The user set the priority: **test-oracle inversion is MANDATORY and first.**
Spec 1 (oracle-ordering-lock) is therefore first — it is the foundation of
the schema, and enforcing it is non-negotiable. The dependency analysis
confirms this order and adds the rest:

1. **oracle-ordering-lock** — the mandatory priority. Adds the
   `tool-call` event (the universal blocking tier) and the phase state
   machine. Must come first: every later spec's mechanism is either a
   clause in the same `tool-call` gate (spec 2) or wiring that makes it
   real (spec 5). The gate event is new but self-contained.
2. **human-grant-lock** — grant check is another clause in the same
   `tool-call` gate; depends on spec 1's event existing. Fixes the
   "tacit approval" / "user can't intervene if it never stops" failure.
3. **ambient-evidence-capture** — the `post-bash` event is a *different*
   gate event (post-execution observation, not pre-execution block), so it
   is independent of specs 1–2's `tool-call` clauses in code terms, but
   logically third: it makes the *absence* of evidence visible, which is
   what makes the completion gate's block (the backstop) actually fire.
4. **judgment-ring-provenance** — adds the `session` field and the
   checkpoint provenance check. Simplest (no new gate event; a ledger
   field + a checkpoint predicate). Depends on D4 (shipped).
5. **harness-install-verification** — wires the `tool-call` event into all
   three adapter configs (making specs 1–2 real on every harness), makes
   `--check-installed` an apply-Step-0 requirement, verifies Devin's
   blocking claim, and records the v13 changelog entry. Last because it
   is the wiring + verification layer on top of the mechanisms.

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | oracle-ordering-lock | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | full |
| 2 | human-grant-lock | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | full |
| 3 | ambient-evidence-capture | ✅ | ✅ | — | ✅ | ✅ | — | — | — | ✅ | — | full |
| 4 | judgment-ring-provenance | ✅ | ✅ | — | ✅ | ✅ | — | — | — | ✅ | — | full |
| 5 | harness-install-verification | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | full |

**Ring notes**:
- R0 (compile) = bats tests run + shellcheck/shfmt pass
- R1 (lint) = shellcheck + shfmt (declared prerequisites, CI-enforced)
- R2 (architecture) = N/A for all specs (no Scala module dependencies)
- R3 (property/scenario) = bats scenario tests — MANDATORY
- R4 (wire/persistence) = ledger JSONL format compat (specs 3, 4 modify the
  ledger record — `session` field on adversarial-review rows, ambient rows
  in the existing format) and git-dir state files (specs 1, 2 add phase/grant
  state — new, never committed)
- R5 (mutation) = N/A (Stryker4s targets Scala only; no bash mutation tool)
- R6 (formal) = N/A (no PureScala kernel; bash/jq tested via bats)
- R7 (model checking) = N/A
- R8 (adversarial review) = MANDATORY — fresh-context reviewer checks each
  mechanism against the failure report's drift pattern and the
  harness-agnostic blocking-surface analysis
- R9 (telemetry) = N/A (no telemetry stack)

## Expected Changed Production Files (Ring 5 targeting)

Ring 5 is N/A (no Stryker4s for bash). This section lists the files each
spec modifies for reference.

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | oracle-ordering-lock | `hooks/gate.sh` (new `tool-call` event, phase-state read/transition, polarity predicate), `scanner/chain-state.sh` (polarity predicate helper, optional), `tests/oracle-ordering-lock.bats` (new) |
| 2 | human-grant-lock | `hooks/gate.sh` (grant-token read/write on prompt-submit, grant clause in tool-call, checkpoint-presentation record), `tests/human-grant-lock.bats` (new) |
| 3 | ambient-evidence-capture | `hooks/gate.sh` (new `post-bash` event, ring-shape match table, ledger append), `tests/ambient-evidence-capture.bats` (new) |
| 4 | judgment-ring-provenance | `scanner/ledger.sh` + `scanner/ledger-record-contract.jq` (`session` field on adversarial-review rows), `scanner/checkpoint.sh` (provenance check), `tests/judgment-ring-provenance.bats` (new) |
| 5 | harness-install-verification | `hooks/adapters/claude.settings.json` (PreToolUse), `hooks/adapters/devin.hooks.v1.json` (PreToolUse), `hooks/adapters/pi/verified-scala3-gate.ts` (tool_call handler), `hooks/README.md` (Devin verification result), `schema.yaml` (v13 changelog, apply-Step-0 --check-installed), `tests/harness-install-verification.bats` (new) |

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | oracle-ordering-lock | separate | complexity=medium, risk=high (mandatory priority; false-positive strand vs false-negative inversion is the critical tradeoff) |
| 2 | human-grant-lock | separate | complexity=medium, risk=high (changes the approval model from agent-attested to harness-observed) |
| 3 | ambient-evidence-capture | separate | complexity=medium, risk=medium (new evidence-production path; format compat) |
| 4 | judgment-ring-provenance | combined | complexity=simple, risk=medium (additive field + checkpoint predicate) |
| 5 | harness-install-verification | separate | complexity=medium, risk=high (wiring that makes the control plane real on every harness; Devin blocking claim verified or corrected) |

## Complexity Guide

For bash/jq workflow changes, "types" = jq contract fields, state-file
values, and reason-codes; "logic" = the gate's decision function and the
phase/grant state machines. SIMPLE: no new gate event, ≤1 new state file.
MEDIUM: new gate event or new state machine. HIGH: new event + new state +
format change. No spec here is HIGH (evidence-capture's ledger format
change was the HIGH spec in the substratum-review change; this change's
ledger changes are additive optional fields).

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md; verify the spec's Proof Obligations
        table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE (combined-tier specs: merged into gate 3)
     3. Test oracle from spec + contract only (before implementation),
        run once for ORACLE POLARITY (red / green-by-design)
        → human review GATE
     4. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6/7
     5. Concept delta check (scanner diff) + build-dependency delta +
        update openspec/concept-inventory.md
     6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     7. STOP for human validation before next spec

     BOOTSTRAPPING NOTE (from design.md): bats-only verification for this
     change. The live gate's verdicts are NOT evidence during this change
     (the gate is being modified — spec 1 adds tool-call, spec 3 adds
     post-bash). The bats suite is the evidence. Re-install hooks and
     re-run the full gate only after all 5 specs are complete. -->

- [ ] 1. `specs/oracle-ordering-lock/spec.md` — MANDATORY first: `gate.sh --event tool-call` + oracle phase state + polarity predicate (oracle inversion impossible-at-write-time)
- [ ] 2. `specs/human-grant-lock/spec.md` — grant tokens on prompt-submit + checkpoint-presentation records (tacit-approval fix; approval as a harness-observed input)
- [ ] 3. `specs/ambient-evidence-capture/spec.md` — `gate.sh --event post-bash` + ring-shape match (ambient ledger capture; absence of evidence becomes visible)
- [ ] 4. `specs/judgment-ring-provenance/spec.md` — `session` field on adversarial-review rows + checkpoint provenance check (fresh-context enforcement; honest limitation where harness can't observe)
- [ ] 5. `specs/harness-install-verification/spec.md` — `tool-call` adapter wiring (Claude/Devin/pi) + apply-Step-0 `--check-installed` + Devin three-level verification + schema v13 changelog
