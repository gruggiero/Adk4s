# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis and the bootstrapping
     strategy from design.md (lowest risk of gate disruption first). -->

## Dependency Analysis

<!-- For each spec, list what it introduces and what it consumes.
     This determines the topological sort order.

     NOTE: This is a workflow-self-change. "Concepts" here are bash/jq
     tooling capabilities, not Scala domain types. The dependency graph
     is based on which scripts each spec modifies and whether a spec's
     fix depends on another spec's fix being in place. -->

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | workflow-hygiene | (none — hygiene fixes only) | (none — independent) | simple |
| 2 | discharge-fidelity | `failed` reason-code, `--forgive-unchanged` flag | (none — modifies chain-state.sh and ledger.sh independently) | medium |
| 3 | judgment-ring-integrity | command/exit disagreement lint, resolvable report artifact requirement | evidence-capture's `sha256` field (for hashing manual artifacts) — BUT can ship without it (artifact existence check first, hash when available) | simple |
| 4 | fact-extraction-unification | `openspec-graph.py export`, `spec-lint.sh --format json`, python3 prerequisite | (none — modifies spec-lint.sh and chain-state.sh; chain-state changes are independent of discharge-fidelity's changes to chain-state's discharge clause) | medium |
| 5 | evidence-capture | `ledger.sh run`, `ledger.sh verify`, `sha256`/`digest`/`wallTime` fields | judgment-ring-integrity uses `sha256` for manual artifacts (evidence-capture introduces the field); discharge-fidelity's `--forgive-unchanged` is in ledger.sh (evidence-capture adds modes to the same script) | high |

### Topological sort rationale

The design's bootstrapping strategy sequences specs by risk of gate
disruption (lowest first). The dependency analysis confirms this order:

1. **workflow-hygiene** — no behavioral changes to verdict logic; pure
   hygiene (dangling refs, dead code, heartbeat ordering, YAML anchors,
   payload cap). Cannot break the gate.
2. **discharge-fidelity** — fixes verdict logic (D1: `.exit == 0` filter,
   D2: per-spec baselines). The gate will produce *different* verdicts
   after this commit, but they are *more* correct. Must come before
   evidence-capture (both modify ledger.sh).
3. **judgment-ring-integrity** — adds lint rules (command/exit disagreement)
   and a requirement (manual rows name artifacts). No verdict logic
   changes. Depends on evidence-capture's `sha256` field for hashing
   manual artifacts, but can ship with existence-check-only (hash added
   when evidence-capture lands). Placed before evidence-capture because
   it's simpler and lower risk.
4. **fact-extraction-unification** — structural change to fact extraction
   (chain-state consumes graph JSON instead of awk). Gate path changes but
   degraded mode preserves old behavior. Independent of discharge-fidelity's
   changes to chain-state's discharge clause (different code paths in the
   same script).
5. **evidence-capture** — new `ledger.sh run`/`verify` subcommands and new
   ledger fields. Highest complexity (new modes, new fields, backward-compat
   constraints, apply-instruction change). Last because it's the hardest
   case for bootstrapping (shipped but not consumed by this change).

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections.
     R3 and R8 are MANDATORY for every code-changing spec.
     The Typed Contract column is full / minimal / waiver. -->

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | workflow-hygiene | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | minimal |
| 2 | discharge-fidelity | ✅ | ✅ | — | ✅ | ✅ | — | — | — | ✅ | — | full |
| 3 | judgment-ring-integrity | ✅ | ✅ | — | ✅ | ✅ | — | — | — | ✅ | — | full |
| 4 | fact-extraction-unification | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | full |
| 5 | evidence-capture | ✅ | ✅ | — | ✅ | ✅ | — | — | — | ✅ | — | full |

**Ring notes**:
- R0 (compile) = bats tests run + shellcheck/shfmt pass
- R1 (lint) = shellcheck + shfmt (declared prerequisites, CI-enforced)
- R2 (architecture) = N/A for all specs (no Scala module dependencies)
- R3 (property/scenario) = bats scenario tests — MANDATORY
- R4 (wire/persistence) = ledger JSONL format compat (specs 2, 3, 5 modify
  the ledger format or its validation)
- R5 (mutation) = N/A (Stryker4s targets Scala only; no bash mutation tool)
- R6 (formal) = N/A (no PureScala kernel; bash/jq tested via bats)
- R7 (model checking) = N/A
- R8 (adversarial review) = MANDATORY — fresh-context reviewer checks each
  fix against the review document's defect description
- R9 (telemetry) = N/A (no telemetry stack)

## Expected Changed Production Files (Ring 5 targeting)

<!-- Ring 5 is N/A for all specs (no Stryker4s for bash). This section
     lists the files each spec modifies for reference. -->

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | workflow-hygiene | `scanner/spec-lint.sh` (D7: drift ref), `scanner/ledger.sh` (D8: dead arm), `hooks/gate.sh` (D8: cwd jq, heartbeat, payload cap), `schema.yaml` (D8: YAML anchors) |
| 2 | discharge-fidelity | `scanner/chain-state.sh` (D1: exit filter, D2: baselines), `scanner/ledger.sh` (D2: --forgive-unchanged), `hooks/gate.sh` (D2: per-spec baselines), `scanner/checkpoint.sh` (D2: shared forgiveness), `scanner/chain-state-report-contract.jq` (D1: discharge clause) |
| 3 | judgment-ring-integrity | `scanner/ledger.sh` (D4: disagreement lint in verify), `scanner/ledger-record-contract.jq` (D4: artifact requirement for manual rows) |
| 4 | fact-extraction-unification | `scanner/openspec-graph.py` (D5: export subcommand), `scanner/spec-lint.sh` (D5: --format json), `scanner/chain-state.sh` (D5: consume graph JSON, degraded mode), `scanner/install-skills.sh` (D5: python3 prerequisite), `schema.yaml` (D5: python3 declared) |
| 5 | evidence-capture | `scanner/ledger.sh` (D3: run/verify subcommands, new fields), `scanner/ledger-record-contract.jq` (D3: sha256/digest/wallTime fields), `schema.yaml` (D3: ledger.sh run in apply Steps 3-11 — shipped but not consumed by this change) |

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | workflow-hygiene | combined | complexity=simple, risk=low (hygiene fixes, no verdict logic changes) |
| 2 | discharge-fidelity | separate | complexity=medium, risk=high (critical defects D1/D2, verdict logic changes) |
| 3 | judgment-ring-integrity | combined | complexity=simple, risk=medium (adds lint rules, no verdict changes) |
| 4 | fact-extraction-unification | separate | complexity=medium, risk=medium (structural change to fact extraction, gate path changes) |
| 5 | evidence-capture | separate | complexity=high, risk=high (new modes, new ledger fields, backward-compat constraints, apply-instruction change) |

## Complexity Guide

<!-- Complexity determines review depth.

     SIMPLE: No new types, ≤1 new method on existing trait, no new error variants.
             Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

     HIGH:   New types AND complex logic AND involves Ring 6/7 or Ring 9.
             Typed contract: full. All applicable rings.

     For bash/jq workflow changes, "types" = jq contract fields and
     reason-codes; "logic" = discharge verdict logic and replay semantics. -->

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md — import existing concepts; verify the spec's
        Proof Obligations table is complete
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

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time.

     BOOTSTRAPPING NOTE (from design.md): bats-only verification for this
     change. The live gate's verdicts are NOT evidence during this change
     (the gate is being modified). The bats suite is the evidence. Re-install
     hooks and re-run the full gate only after all 5 specs are complete. -->

- [ ] 1. `specs/workflow-hygiene/spec.md` — D7/D8 hygiene fixes (dangling ref, dead code, heartbeat, YAML anchors, payload cap)
- [ ] 2. `specs/discharge-fidelity/spec.md` — D1/D2 critical fixes (exit==0 filter, per-spec baselines, --forgive-unchanged)
- [ ] 3. `specs/judgment-ring-integrity/spec.md` — D4 manual artifact resolution + command/exit disagreement lint
- [ ] 4. `specs/fact-extraction-unification/spec.md` — D5 promote openspec-graph.py to single fact extractor + spec-lint --format json
- [ ] 5. `specs/evidence-capture/spec.md` — D3/D6 ledger.sh run (capture) + verify (replay) + sha256/digest/wallTime fields
