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
| 1 | `specs/agent-memory/spec.md` | `AgentMemory[F]`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory` | (none — foundational; references `Retriever`/`Document`/`RetrieverConfig` for context only, not for implementation) | medium — new types (case classes + enum + trait + class) but no complex logic (`naiveScore` is substring/term-count); no Ring 6/7/9 |
| 2 | `specs/memory-retriever-bridge/spec.md` | `MemoryRetriever` | `AgentMemory[F]`, `MemoryHit`, `TemporalScope` (from spec 1); `Retriever[F]`, `Document`, `RetrieverConfig` (from inventory) | medium — new adapter with mapping logic + `Sync` constraint; no Ring 6/7/9 |
| 3 | `specs/memory-testkit/spec.md` | `AgentMemoryLaws`, `adk4s-memory-testkit` (sbt module) | `AgentMemory[F]`, `Episode`, `SourceType`, `MemoryHit`, `TemporalScope` (from spec 1) | simple — no new domain types beyond a laws-runner case class; new sbt module wiring; no Ring 6/7/9 |

**Topological sort**: 1 → 2 → 3. Spec 2 depends on spec 1's types (`AgentMemory`, `MemoryHit`, `TemporalScope`). Spec 3 depends on spec 1's types. Specs 2 and 3 are independent of each other, but spec 2 is ordered before spec 3 because it is the production bridge (higher value, faster confidence on the core interface) while spec 3 is the testkit export. A build-wiring task (creating the `adk4s-memory-api` sbt module) must precede spec 1's implementation.

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections.
     R3 and R8 are MANDATORY for every code-changing spec.
     The Typed Contract column is full / minimal / waiver (waiver requires
     explicit human approval; only for docs/formatting/test-only specs). -->

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | agent-memory | ✅ | ✅ | ✅ (advisory: no heavy deps via `dependencyTree`) | ✅ (6 Hedgehog properties + scenarios) | — (no persisted/wire data) | ✅ (Stryker4s on `InMemoryAgentMemory.scala`) | — (waived: `naiveScore` too thin; Hedgehog covers it) | — (no distributed/event-driven invariants) | ✅ (spec-lint PASS) | — (no telemetry stack; no `AgentEvent` variants) | full — new domain types (ADT enum, case classes, service trait, implementation class) |
| 2 | memory-retriever-bridge | ✅ | ✅ | ✅ (advisory: bridge imports only adk4s-core component + cats-effect + fs2 + ujson) | ✅ (4 Hedgehog properties + scenarios) | — (no persisted/wire data) | ✅ (Stryker4s on `MemoryRetriever.scala`) | — (waived: pure mapping too thin; Hedgehog covers it) | — | ✅ (spec-lint PASS) | — | full — new public adapter signature + `Sync` constraint + mapping logic |
| 3 | memory-testkit | ✅ | ✅ | ✅ (advisory: testkit imports only memory-api + cats-effect + munit) | ✅ (2 Hedgehog properties + scenarios) | — | — (test harness, not production logic — no mutation target) | — | — | ✅ (spec-lint PASS) | — | full — new `AgentMemoryLaws` public API + new sbt module wiring |

> **Ring 5 note**: spec 3 (`memory-testkit`) does NOT get Ring 5 because
> `AgentMemoryLaws` is a test harness (it runs assertions), not production
> logic with branches to mutate. Specs 1 and 2 retarget `stryker4s.conf` to
> their respective production files.

## Expected Changed Production Files (Ring 5 targeting)

<!-- Per spec, the production files expected to change. Ring 5 dynamically
     retargets the Stryker mutate list to the files ACTUALLY changed by the
     spec (git diff), using this column as the starting estimate. NEVER rely
     on a fixed mutate list in stryker4s.conf. -->

| # | Spec | Expected Files |
|---|------|----------------|
| 0 | (build wiring — precedes spec 1) | `build.sbt` (add `adk4s-memory-api` lazy val + deps), `project/Dependencies.scala` (no new deps — reuses `catsEffect`, `fs2Core`, `testDeps`) |
| 1 | agent-memory | `adk4s-memory-api/src/main/scala/org/adk4s/memory/Episode.scala`, `adk4s-memory-api/src/main/scala/org/adk4s/memory/MemoryHit.scala`, `adk4s-memory-api/src/main/scala/org/adk4s/memory/AgentMemory.scala`, `adk4s-memory-api/src/main/scala/org/adk4s/memory/InMemoryAgentMemory.scala` |
| 2 | memory-retriever-bridge | `adk4s-memory-api/src/main/scala/org/adk4s/memory/MemoryRetriever.scala` |
| 3 | memory-testkit | `build.sbt` (add `adk4s-memory-testkit` lazy val + deps), `adk4s-memory-testkit/src/main/scala/org/adk4s/memory/testkit/AgentMemoryLaws.scala` |

**Ring 5 retarget plan**:
- Before spec 1: `stryker4s.conf` `mutate = ["**/memory/InMemoryAgentMemory.scala"]`
- Before spec 2: `stryker4s.conf` `mutate = ["**/memory/MemoryRetriever.scala"]`
- Spec 3: no Ring 5 (test harness).

## Complexity Guide

<!-- Complexity determines review depth.

     SIMPLE: No new types, ≤1 new method on existing trait, no new error variants.
             Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

     HIGH:   New types AND complex logic AND involves Ring 6/7 or Ring 9.
             Typed contract: full. All applicable rings. -->

| Spec | Complexity | Justification |
|------|-----------|---------------|
| agent-memory | medium | New types (enum + 4 case classes + trait + class) but no complex logic (`naiveScore` is substring/term-count); no Ring 6/7/9. |
| memory-retriever-bridge | medium | New adapter with mapping logic + `Sync` constraint + two-method trait implementation; no Ring 6/7/9. |
| memory-testkit | simple | No new domain types beyond a laws-runner case class; new sbt module wiring; no production logic. Typed contract still `full` because `AgentMemoryLaws` is a public API. |

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Read concept-inventory.md — import existing concepts; verify the
        spec's Proof Obligations table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE
     3. Test oracle from spec + contract only (before implementation)
        → human review GATE
     4. Implement through all applicable rings (see table above), including
        the mandatory adversarial spec-compliance review (Ring 8)
     5. Concept delta check + update concept-inventory.md
     6. Mark checkbox below
     7. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [ ] 0. Build wiring — add `adk4s-memory-api` lazy val to `build.sbt` (`dependsOn(adk4s-core)`, main deps `catsEffect` + `fs2Core`, test deps `testDeps`, `scalacOptions ++= scala3Options`); verify `sbt "adk4s-memory-api/compile"` succeeds on an empty src tree
- [ ] 1. `specs/agent-memory/spec.md` — `AgentMemory[F]` trait, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory` + typed contract + 6 Hedgehog properties + scenarios + Ring 5 (Stryker4s on `InMemoryAgentMemory.scala`) + concept-inventory update
- [ ] 2. `specs/memory-retriever-bridge/spec.md` — `MemoryRetriever` adapter + typed contract + 4 Hedgehog properties + scenarios + Ring 5 (Stryker4s on `MemoryRetriever.scala`) + concept-inventory update
- [ ] 3. `specs/memory-testkit/spec.md` — `adk4s-memory-testkit` sbt module + `AgentMemoryLaws` + typed contract + 2 Hedgehog properties + scenarios + concept-inventory update

<!-- The checkbox list length matches the number of spec files (3) plus the
     build-wiring prerequisite (1). The apply phase tracks progress here. -->
