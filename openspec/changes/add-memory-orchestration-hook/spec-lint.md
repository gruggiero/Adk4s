# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Mechanical pre-pass

**openspec validate --strict**: `Change 'add-memory-orchestration-hook' is valid` (run 2026-07-18).

**spec-lint.sh**: `2 spec file(s), 0 FAIL, 8 WARN` (run 2026-07-18). All 8 WARNs are W3 (negative-requirement adversarial-confirmation reminders), which are human-judgment checks resolved in check 15 below. No F1–F5 failures. Full W3 list:

- `memory-orchestration-events/spec.md:55` MemoryRecalled event — negative ("MUST NOT emit" when memory is `None`)
- `memory-orchestration-events/spec.md:89` MemoryWritten event — negative ("MUST NOT" on Interrupted/Failed)
- `memory-orchestration-hook/spec.md:65` Opt-in memory activation — negative ("ONLY when … MUST be byte-for-byte identical")
- `memory-orchestration-hook/spec.md:93` Pre-turn recall injects context — negative ("MUST NOT inject")
- `memory-orchestration-hook/spec.md:121` Post-turn write only on Completed — negative ("MUST NOT write")
- `memory-orchestration-hook/spec.md:155` runWithEvents forwards the underlying stream unchanged — negative ("MUST NOT inject, drop, or reorder")
- `memory-orchestration-hook/spec.md:177` resume delegates without a spurious write — negative ("MUST NOT")
- `memory-orchestration-hook/spec.md:199` MemoryPolicy render default — negative ("MUST be a pure function … no I/O")

## Checks

Each spec is checked against the 18 schema checks (1, 1b, 1c, 2–17, 18).

## Results

### Spec: specs/memory-orchestration-hook/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | all 6 requirements have Given/When/Then; every scenario has specific setup/action/assertion |
| 1b | SHALL/MUST normative opener | ✅ | F1 passed mechanically; each `### Requirement:` body opens with a SHALL/MUST sentence before `**Given**` |
| 1c | Per-variant behavior-preservation scenarios | ✅ | "Opt-in memory activation" asserts identity across all three `RunResult` variants (`Completed`, `Interrupted`, `Failed`) — each scenario asserts the discriminating observable (result equality, no-write, no-context-injection) |
| 2 | Then observable | ✅ | every `Then` is a return value (`RunResult` equality), a call count (`remember`/`recall` calls), a message-list equality, or an emitted-event absence — all observable |
| 3 | Scenarios testable | ✅ | all scenarios use `InMemoryAgentMemory` (test double), stub runners, and `TestControl` for the interrupt/Failed paths — no LLM, no network, no wall-clock |
| 4 | Error paths specified | ✅ | `Interrupted` and `Failed` paths are explicit adversarial scenarios under "Post-turn write only on Completed" and "resume delegates without a spurious write"; the no-memory path is explicit under "Opt-in memory activation" |
| 5 | New concepts declared | ✅ | `MemoryPolicy`, `MemoryHook`, `MemoryAwareRunner` all listed in "Concepts Introduced (new)" |
| 6 | Reused concepts resolved | ✅ | `AgentMemory`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `AgentRunner`, `RunResult`, `UserMessage`/`Message`, `InMemoryAgentMemory` all present in openspec/concept-inventory.md with matching packages |
| 7 | Generator strategies | ✅ | F3 passed; all 5 properties declare `**Generator strategy**` with named `Gen`, constructive/filtered note, edge cases, and `cover` labels |
| 8 | Temporal trigger/response | N/A | no `### Temporal:` blocks (Ring 9 not in scope — no telemetry stack) |
| 9 | No vague words | ✅ | no "valid/fast/reasonable/correct/appropriate" without a concrete definition; "byte-for-byte identical" is concretely defined as "same `RunResult` value AND same event sequence AND zero `remember` calls" |
| 10 | Unreachable claims proven | ✅ | no "unreachable" claims; the "no write on Interrupted/Failed" claim is enforced by a runtime pattern match (Hedgehog property + adversarial scenarios), not by an unreachability assertion |
| 11 | Enum extension / type-widening behavior | ✅ (N/A for this spec) | this spec does NOT extend a sealed ADT (the events spec does); no type-widening here — `MemoryAwareRunner` decorates, not extends |
| 12 | Proof obligations complete | ✅ | F4 passed; the Proof Obligations table has 13 rows covering every requirement, scenario, invariant, and the compile-negative obligations, each with a declared enforcement mechanism (Hedgehog property / scenario test / type system / WartRemover / static grep) |
| 13 | Consumer-facing surface asserted | ✅ | `MemoryPolicy`'s fields and `MemoryHook`'s method signatures are the consumer-facing surface; "MemoryPolicy render default" asserts exactly what the consumer observes (the rendered string's substrings and determinism) |
| 14 | Error variants type-feasible | ✅ | the only asserted error variants are `RunResult.Interrupted` and `RunResult.Failed`, both real variants of the sealed `RunResult` the underlying `AgentRunner.run` returns — type-feasible |
| 15 | Adversarial scenarios for negatives | ✅ | every negative requirement has a forbidden-input scenario: "Opt-in" → "Adversarial — a caller cannot force a write without opting in" (`memory = None` + write flags true); "Pre-turn recall" → "Empty recall injects nothing" + "recallK = 0 skips recall"; "Post-turn write" → "Interrupted writes nothing" + "Failed writes nothing"; "runWithEvents" → "Stream identity under no memory"; "resume" → "Resume that interrupts again writes nothing"; "render" → totality on empty list + "Custom render is honored" (forbids the default being forced) |
| 16 | MUST-CONFIRM marks present | ✅ | no externally-sourced classification tables or value domains in this spec; the `MemoryPolicy` defaults are repo-defined, not external |
| 17 | Altitude respected | ✅ | no module names, error class names, or build commands inside Given/When/Then; code identifiers (`MemoryPolicy.scala`, `sbt adk4s-orchestration/compile`, `stryker4s.conf`) are all in "## Implementation Anchors"; "Concepts Used (behavioral)" cites `AgentRunner`, `AgentEventStream`, and the new `MemoryAwareRunner` concept with links |
| 18 | Concurrency deterministic | ✅ | the interrupt/Failed scenarios name deterministic observables (zero `remember` calls, `RunResult` equality) and are testable with `TestControl`; no wall-clock assertions; the Proof Obligations table pins a static grep for `Thread.sleep`/`TimeUnit.sleep` absence |

**Verdict: PASS**

### Spec: specs/memory-orchestration-events/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | all 3 requirements have Given/When/Then; every scenario has specific setup/action/assertion |
| 1b | SHALL/MUST normative opener | ✅ | F1 passed; each requirement opens with SHALL/MUST before `**Given**` |
| 1c | Per-variant behavior-preservation scenarios | ✅ | "MemoryWritten event" asserts the discriminating observable (`episodes` count, event presence/absence) across all three `RunResult` variants (`Completed`, `Interrupted`, `Failed`) |
| 2 | Then observable | ✅ | every `Then` is an emitted-event count/presence/absence or a `runPath` equality — all observable on the event stream |
| 3 | Scenarios testable | ✅ | scenarios use `runWithEvents` to capture the `Stream[IO, AgentEvent]` and assert on it with `TestControl`; no LLM, no wall-clock |
| 4 | Error paths specified | ✅ | `Interrupted` and `Failed` are explicit adversarial scenarios under "MemoryWritten event"; the no-memory path is explicit under "MemoryRecalled event" |
| 5 | New concepts declared | ✅ | `MemoryRecalled`, `MemoryWritten` listed in "Concepts Introduced (new)" |
| 6 | Reused concepts resolved | ✅ | `AgentEvent`, `AgentEventEmitter`, `RunPath`, `RunStep`, `InterruptSignal` all present in openspec/concept-inventory.md |
| 7 | Generator strategies | ✅ | F3 passed; all 3 properties declare `**Generator strategy**` with named `Gen`, edge cases, and `cover` labels |
| 8 | Temporal trigger/response | N/A | no `### Temporal:` blocks (Ring 9 not in scope) |
| 9 | No vague words | ✅ | no vague words; "exactly one" and "zero" are concrete counts |
| 10 | Unreachable claims proven | ✅ | no "unreachable" claims; the "no event when memory = None / Interrupted / Failed" claims are enforced by adversarial scenarios + Hedgehog properties |
| 11 | Enum extension / type-widening behavior | ✅ | the "Exhaustiveness Impact (sealed ADT extension)" section lists every existing `AgentEvent` match site (`withPrependedStep`, test/example consumers, serialization) and states required behavior for the two new variants per site; the `withPrependedStep` obligation is type-enforced (abstract method); the consumer-match obligation is `-Wconf`-enforced; serialization is N/A (`AgentEvent` does not derive `ReadWriter`, verified by grep) |
| 12 | Proof obligations complete | ✅ | F4 passed; the Proof Obligations table has 10 rows covering every requirement, the exhaustiveness obligations, the `ReadWriter` static check, and the concurrency-test-kit check |
| 13 | Consumer-facing surface asserted | ✅ | the two new event variants ARE the consumer-facing surface (event-stream consumers observe them); the scenarios assert exactly what the consumer observes (the `query`/`hitCount`/`episodes` fields and the `runPath`) |
| 14 | Error variants type-feasible | ✅ | no error variants asserted; the events are emitted on the event stream, not returned as errors |
| 15 | Adversarial scenarios for negatives | ✅ | "MemoryRecalled event" → "No memory emits no MemoryRecalled (adversarial)"; "MemoryWritten event" → "Interrupted emits no MemoryWritten" + "Failed emits no MemoryWritten"; the forbidden inputs are `memory = None` and `Interrupted`/`Failed` outcomes |
| 16 | MUST-CONFIRM marks present | ✅ | no externally-sourced data in this spec |
| 17 | Altitude respected | ✅ | no module names / error class names / build commands in Given/When/Then; the variant names (`MemoryRecalled`, `MemoryWritten`) appear in requirements because they ARE the user-facing observability surface (the spec's own introduced concepts), which is permitted; code identifiers (`AgentEvent.scala`, `sbt adk4s-core/compile`, `stryker4s.conf`) are in "## Implementation Anchors"; "Concepts Used (behavioral)" cites `AgentEventStream` (extended), `AgentRunner`, `MemoryAwareRunner` with links |
| 18 | Concurrency deterministic | ✅ | the event-stream assertions name deterministic observables (event counts, `runPath` equality) and use `TestControl`; no wall-clock assertions |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| `specs/memory-orchestration-hook/spec.md` | PASS | 0 — all 18 checks pass |
| `specs/memory-orchestration-events/spec.md` | PASS | 0 — all 18 checks pass |

Overall: both specs PASS. implementation-order.md may be generated. The 8 W3 warnings from the mechanical pre-pass are resolved by the human-judgment adversarial check (15) — every negative requirement has a forbidden-input scenario, named above.
