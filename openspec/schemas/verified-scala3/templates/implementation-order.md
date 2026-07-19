# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept.

     This file is generated from the specs, spec-lint (all PASS required),
     and design artifacts. The checkbox list at the bottom is the progress
     tracker used by the apply phase (tracks: implementation-progress.md). -->

## Dependency Analysis

<!-- For each spec, list what it introduces and what it consumes.
     This determines the topological sort order. -->

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| <!-- 1 --> | <!-- specs/accounts/spec.md --> | <!-- AccountId, Account, Balance, Amount, AccountService --> | <!-- (none — foundational) --> | <!-- high --> |
| <!-- 2 --> | <!-- specs/transfers/spec.md --> | <!-- TransferResult --> | <!-- AccountId, Account, Balance, Amount --> | <!-- medium --> |
| <!-- 3 --> | <!-- specs/notifications/spec.md --> | <!-- (none — uses existing) --> | <!-- AccountId, TransferResult --> | <!-- simple --> |

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections.
     R3 and R8 are MANDATORY for every code-changing spec.
     The Typed Contract column is full / minimal / waiver (waiver requires
     explicit human approval; only for docs/formatting/test-only specs). -->

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| <!-- 1 --> | <!-- accounts --> | ✅ | ✅ | ✅ | ✅ | <!-- — --> | <!-- ✅ --> | <!-- — --> | <!-- — --> | ✅ | <!-- ✅ --> | <!-- full --> |
| <!-- 2 --> | <!-- transfers --> | ✅ | ✅ | ✅ | ✅ | <!-- ✅ --> | <!-- ✅ --> | <!-- ✅ --> | <!-- — --> | ✅ | <!-- — --> | <!-- full --> |
| <!-- 3 --> | <!-- notifications --> | ✅ | ✅ | <!-- — --> | ✅ | <!-- — --> | <!-- — --> | <!-- — --> | <!-- — --> | ✅ | <!-- ✅ --> | <!-- minimal --> |

## Expected Changed Production Files (Ring 5 targeting)

<!-- Per spec, the production files expected to change. Ring 5 dynamically
     retargets the Stryker mutate list to the files ACTUALLY changed by the
     spec (git diff against the spec's Step 0 baseline SHA), using this
     column as the starting estimate. NEVER rely on a fixed mutate list in
     stryker4s.conf. -->

| # | Spec | Expected Files |
|---|------|----------------|
| <!-- 1 --> | <!-- accounts --> | <!-- src/main/scala/domain/Account.scala, ... --> |

## Human Gate Tier

<!-- Per spec: `combined` (typed contract + test oracle presented at ONE
     gate) is allowed ONLY when complexity is `simple` AND the proposal's
     correctness risk is `low`. Everything else is `separate` (two gates —
     the default). Both steps are always executed in full either way; the
     tier changes only how many stops the human reviews. Rationale: human
     attention is the scarcest verification resource — a gate the human
     stops reading is worse than no gate. -->

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| <!-- 1 --> | <!-- accounts --> | <!-- separate --> | <!-- complexity=high --> |
| <!-- 3 --> | <!-- notifications --> | <!-- combined --> | <!-- complexity=simple, risk=low --> |

## Complexity Guide

<!-- Complexity determines review depth.

     SIMPLE: No new types, ≤1 new method on existing trait, no new error variants.
             Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

     HIGH:   New types AND complex logic AND involves Ring 6/7 or Ring 9.
             Typed contract: full. All applicable rings. -->

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

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [ ] 1. `specs/[first-spec]/spec.md` — [brief description]
- [ ] 2. `specs/[second-spec]/spec.md` — [brief description]
- [ ] 3. `specs/[third-spec]/spec.md` — [brief description]

<!-- Add more entries as needed. The checkbox list length must match
     the number of spec files. -->
