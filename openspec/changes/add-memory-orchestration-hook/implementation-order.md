# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept.

     This file is generated from the specs, spec-lint (all PASS required),
     and design artifacts. The checkbox list at the bottom is the progress
     tracker used by the apply phase (tracks: implementation-progress.md). -->

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/memory-orchestration-hook/spec.md` | `MemoryPolicy`, `MemoryHook`, `MemoryAwareRunner` (the decorator — created WITHOUT event emission in this spec) | `AgentMemory`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope` (all shipped by archived `2026-07-05-add-memory-api`), `AgentRunner`, `RunResult`, `UserMessage`/`Message` | medium — new types AND a decorator with `RunResult`-gated control flow, but no Ring 6/7/9 |
| 2 | `specs/memory-orchestration-events/spec.md` | `MemoryRecalled`, `MemoryWritten` (two `AgentEvent` variants) | `AgentEvent`, `AgentEventEmitter`, `RunPath`, `RunStep` (all pre-existing), AND `MemoryAwareRunner` (introduced by spec 1 — the events spec adds the two `emit` calls inside the decorator) | simple — two new case-class variants + two emit calls; the only structural risk is the sealed-ADT exhaustiveness impact, which is type-enforced |

Spec 2 depends on spec 1: the events spec's emission points live inside
`MemoryAwareRunner` (the decorator created by spec 1). Spec 1 is implemented
FIRST without event emission (its `runWithEvents` scenario asserts event-stream
identity WITHOUT the memory events); spec 2 THEN adds the two `AgentEvent`
variants and the two `emit` calls. This keeps the two specs independently
verifiable and lets the events spec be deferred without blocking the hook.

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | memory-orchestration-hook | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 2 | memory-orchestration-events | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |

- R0/R1: always apply.
- R2: both — the hook spec introduces a new package with layer rules; the events spec extends a sealed ADT in `adk4s-core.interrupt` (arch boundary: must not import `adk4s-orchestration`).
- R3: mandatory (both specs are code-changing). The hook spec touches interrupt/resume semantics → `TestControl` scenarios.
- R4: not applicable (no serialization/wire/checkpoint-format change — see design §7).
- R5: both — Stryker4s on the changed production files (the hook spec's three new files; the events spec's `AgentEvent.scala` new `withPrependedStep` impls).
- R6: not applicable (no PureScala module — the hook is effectful `IO` wiring; the pure `render` is property-tested).
- R7: not applicable (no TLA+/Apalache).
- R8: mandatory (fresh-context, before R5/R6/R7).
- R9: not applicable (no telemetry stack).
- Typed Contract: **full** for both — the hook spec introduces new public types and a decorator API; the events spec extends a sealed ADT (error/event algebra change). Per the proposal's Typed Contract Decision table, both are "Full".

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | memory-orchestration-hook | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryPolicy.scala` (NEW), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryHook.scala` (NEW), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryAwareRunner.scala` (NEW), `build.sbt` (add `adk4s-orchestration → adk4s-memory-api` edge), `openspec/concepts/memory-aware-runner.md` (NEW — concept file) |
| 2 | memory-orchestration-events | `adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala` (add `MemoryRecalled` + `MemoryWritten` variants + their `withPrependedStep`), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryAwareRunner.scala` (add the two `emit` calls — cross-spec coupling), `openspec/concepts/agent-event-stream.md` (UPDATE — add the two variants to the Implementation map) |

Ring 5 retargets `stryker4s.conf`'s `mutate` list to the files ACTUALLY
changed by each spec (git diff against the spec's Step 0 baseline SHA),
using this column as the starting estimate. The current fixed
`stryker4s.conf` `mutate = ["**/memory/MemoryRetriever.scala"]` is stale
(left over from the archived memory-api change) and MUST be repointed.

## Public-Type-Change Impact Scans (Step 0, schema v7)

Spec 2 is a PUBLIC-TYPE-CHANGE: it extends the sealed `AgentEvent` ADT in
`adk4s-core.interrupt` with two new variants (`MemoryRecalled`,
`MemoryWritten`). Per schema v7 Step 0, this triggers the
PUBLIC-TYPE-CHANGE IMPACT SCAN BEFORE any code is written:

- PRIMARY METHOD:
  `openspec/schemas/verified-scala3/scanner/impact-scan.sh org.adk4s.core.interrupt.AgentEvent`
  — computes the compiler-resolved reference set (Metals `get-usages` on
  the running :8394 endpoint) and reports catch-all arms near a usage; it
  falls back to a whole-tree grep by itself when no Metals endpoint is
  running.
- For each catch-all site found, require one of (recorded in the spec's
  Proof Obligations): made exhaustive over the new variant set; explicitly
  rejects the new variants; or a justified `case` with rationale.
- The capability-check.md already records the pre-scan result (Metals on
  :8394 surfaced catch-alls in `EventStreamExample:89`,
  `HierarchicalEventStreamExample:242/262`, `AgentTool:122`); the spec 2
  Step 0 task re-runs the scan against the baseline and records the
  resolution per site in the checkpoint.

Spec 1 is NOT a public-type-change (it adds new types, does not widen an
existing sealed ADT), so no impact scan is required for it.

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | memory-orchestration-hook | separate | complexity=medium (new types AND decorator control flow) AND proposal risk=medium → two gates (typed contract, then test oracle). |
| 2 | memory-orchestration-events | separate | complexity=simple BUT proposal risk=medium (the change extends a sealed ADT in `adk4s-core`, a cross-module event-algebra change) → two gates. The `combined` tier requires complexity=simple AND risk=low; risk=medium forces `separate` even for a simple spec. |

Both specs use `separate` — two human gates each (typed contract gate, then
test-oracle gate). The proposal's correctness risk is `medium`, which
disqualifies `combined` regardless of complexity.

## Complexity Guide

- **SIMPLE**: No new types, ≤1 new method on existing trait, no new error variants. Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.
- **MEDIUM**: New types OR complex business logic OR new error handling paths. Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.
- **HIGH**: New types AND complex logic AND involves Ring 6/7 or Ring 9. Typed contract: full. All applicable rings.

Spec 1 = medium (new types + decorator control flow + interrupt-safety
gating). Spec 2 = simple (two case-class variants + two emit calls; the
exhaustiveness obligation is type-enforced, not complex logic).

## Implementation Sequence

- [ ] 1. `specs/memory-orchestration-hook/spec.md` — create `MemoryPolicy` / `MemoryHook` / `MemoryAwareRunner` in `org.adk4s.orchestration.memory`; wire `adk4s-orchestration → adk4s-memory-api`; create `openspec/concepts/memory-aware-runner.md`; implement WITHOUT event emission (the hook spec is observability-neutral).
- [ ] 2. `specs/memory-orchestration-events/spec.md` — add `MemoryRecalled` + `MemoryWritten` to the sealed `AgentEvent` ADT in `adk4s-core.interrupt`; add the two `emit` calls inside `MemoryAwareRunner`; update `openspec/concepts/agent-event-stream.md`.

<!-- Process each spec in this exact order. For each spec:
     0. STEP 0 — BASELINE + CONCEPT CHECK (schema v7):
        - working tree clean; record `git rev-parse HEAD` as BASELINE SHA in
          implementation-progress.md (every per-spec diff is `git diff <baseline>`)
        - INVENTORY SNAPSHOT: run the semantic scanner into
          `openspec/changes/<change>/inventory-snapshots/<spec>-before.md`
          (Step 12 re-runs it and machine-diffs the two)
        - read openspec/concept-inventory.md — import existing concepts; verify
          the spec's Proof Obligations table is complete
        - CODE INTELLIGENCE (v7): the Metals MCP endpoint on :8394 is running
          (see openspec/capability-profile.md "Code Intelligence"). PREFER the
          schema's semantic recipes (scanner/metals-call.sh resolve/inspect/
          get-usages, impact-scan.sh, removal-audit.sh — see the
          openspec-code-intel skill) over grep for symbol questions; every
          recipe degrades to git grep on its own when the endpoint is down.
          Semantic answers are index-based: trust them only after a successful
          compile.
        - PUBLIC-TYPE-CHANGE IMPACT SCAN (v7, spec 2 ONLY — extends the sealed
          `AgentEvent` ADT): run
          `scanner/impact-scan.sh org.adk4s.core.interrupt.AgentEvent` and
          resolve every catch-all site (see "Public-Type-Change Impact Scans"
          above); record the resolution in the checkpoint
        - REGISTRY GATE: run scanner/registry-check.sh (must pass); verify
          every MUST-CONFIRM item with the human before coding
     1. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE (separate tier: gate 1 of 2)
     2. Test oracle from spec + contract only (before implementation),
        run once for ORACLE POLARITY (red / green-by-design)
        → human review GATE (separate tier: gate 2 of 2)
     3. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6/7
     4. STEP 12 — CONCEPT DELTA CHECK + UPDATE INVENTORY (schema v7):
        - MECHANICAL: re-run the semantic scanner into
          `openspec/changes/<change>/inventory-snapshots/<spec>-after.md` and
          diff against the Step 0 `-before` snapshot — that diff IS the concept
          delta; compare it line-by-line against the spec's Concepts Introduced
          table + approved extensions
        - BUILD-DEPENDENCY DELTA: `git diff <baseline-SHA> -- build.sbt project/`;
          any NEW library dependency must be named in the proposal/design or
          get explicit human approval at the checkpoint
        - REMOVAL AUDIT (v7): for every field/type REMOVED from a refactored
          concept, run `scanner/removal-audit.sh --suggest <baseline-SHA>` then
          `removal-audit.sh <suspect-fqcn>...` for who-still-uses verdicts
          (ORPHAN-CANDIDATE = only self-references remain). Each orphan MUST be
          deleted in this change or explicitly retained with rationale. (This
          change is purely additive — no removals — so the audit is expected to
          yield "none removed", but it MUST be run and recorded.)
        - append verified NEW concepts to openspec/concept-inventory.md
          (provenance `spec:<change>/<spec>` accumulates across changes)
        - REGISTRY UPDATE: if the spec altered a concept's actions/state/syncs,
          update openspec/concepts/*.md NOW and re-run scanner/registry-check.sh
     5. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     6. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->
