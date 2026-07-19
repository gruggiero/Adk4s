# memory-orchestration-events Specification

## Purpose
Add MemoryRecalled and MemoryWritten AgentEvent variants to the AgentEventStream, emitted by MemoryAwareRunner so consumers can trace what memory was recalled and what was written per turn.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [AgentEventStream](../../../../concepts/agent-event-stream.md) | EXTENDED — its `AgentEvent` variant set gains `MemoryRecalled` and `MemoryWritten`; the concept file's "AgentEvent variants" list and Implementation map MUST be updated as part of this spec | `openspec/concepts/agent-event-stream.md` |
| [AgentRunner](../../../../concepts/agent-runner.md) | the runner whose event stream the new variants flow through (the hook emits them via the runner's `AgentEventEmitter`) | `openspec/concepts/agent-runner.md` |
| `MemoryAwareRunner` (created by the hook spec) | the sole emitter of the two new event variants — it emits `MemoryRecalled` after `preTurn` and `MemoryWritten` after `postTurn` | `openspec/concepts/memory-aware-runner.md` |

> This spec EXTENDS an existing behavioral concept (`AgentEventStream`).
> Updating `openspec/concepts/agent-event-stream.md` — adding the two
> variants to its "AgentEvent variants" list and its Implementation map — is
> PART OF implementing this spec.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AgentEvent` | sealed trait | `org.adk4s.core.interrupt` |
| `AgentEventEmitter` | final class | `org.adk4s.core.interrupt` |
| `RunPath` | opaque type | `org.adk4s.core.interrupt` |
| `RunStep` | case class | `org.adk4s.core.interrupt` |
| `InterruptSignal` | sealed trait | `org.adk4s.core.interrupt` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `MemoryRecalled` | AgentEvent variant (final case class) | `MemoryRecalled(runPath: RunPath, query: String, hitCount: Int)` — emitted after `preTurn`'s `recall` returns, carrying the query and the number of hits (0 if recall yielded nothing or was skipped) |
| `MemoryWritten` | AgentEvent variant (final case class) | `MemoryWritten(runPath: RunPath, episodes: Int)` — emitted after `postTurn`'s `remember` calls complete, carrying the number of episodes written (0 if the policy wrote nothing) |

## Requirements

<!-- ALTITUDE RULE: requirements and scenarios use behavioral vocabulary only.
     The new event variants are named in the requirements because they ARE the
     user-facing observability surface; the code identifiers (file paths, the
     `AgentEvent` trait location) live in "## Implementation Anchors". -->

### Requirement: MemoryRecalled event

The system SHALL emit a `MemoryRecalled(runPath, query, hitCount)` event through the `AgentEventStream` after every `preTurn` recall when memory is present, where `query` is the latest user input text and `hitCount` is the number of hits returned (0 if recall was skipped or returned empty); when memory is `None`, the system MUST NOT emit `MemoryRecalled`.

**Given** a `MemoryAwareRunner` constructed with a non-`None` memory
**When** `preTurn` completes its `recall` call (or skips it because `recallK = 0`)
**Then** exactly one `MemoryRecalled` event is emitted on the run's `AgentEventStream`, with `query` equal to the latest user input text and `hitCount` equal to the number of hits returned (0 if `recallK = 0` or recall returned `Nil`)

**Rationale**: observability parity — every other seam in the run (tool call, iteration, interrupt) emits an event; memory recall should too, so consumers can trace what was injected.

#### Scenario: Non-empty recall emits hitCount > 0

**Given** memory present, `recallK = 3`, and `recall(q, 3, scope)` returns two hits
**When** the caller invokes `runWithEvents([UserMessage(q)])`
**Then** the event stream contains exactly one `MemoryRecalled` event with `query == q` and `hitCount == 2`, emitted before the underlying runner's first event

#### Scenario: Empty recall emits hitCount = 0

**Given** memory present, `recallK = 3`, and `recall(q, 3, scope)` returns `Nil`
**When** the caller invokes `runWithEvents([UserMessage(q)])`
**Then** the event stream contains exactly one `MemoryRecalled` event with `query == q` and `hitCount == 0`

#### Scenario: recallK = 0 emits hitCount = 0

**Given** memory present and `recallK = 0`
**When** the caller invokes `runWithEvents(messages)`
**Then** the event stream contains exactly one `MemoryRecalled` event with `hitCount == 0` (recall was skipped, but the event still fires so consumers see the seam ran)

#### Scenario: No memory emits no MemoryRecalled (adversarial)

**Given** a `MemoryAwareRunner` with `memory = None`
**When** the caller invokes `runWithEvents(messages)`
**Then** the event stream contains ZERO `MemoryRecalled` events (the seam is inert when memory is absent)

### Requirement: MemoryWritten event

The system SHALL emit a `MemoryWritten(runPath, episodes)` event through the `AgentEventStream` after every `postTurn` that runs (i.e. only on `Completed`), where `episodes` is the number of episodes actually written (0 if the policy's write flags were both false); on `Interrupted` or `Failed`, the system MUST NOT emit `MemoryWritten`.

**Given** a `MemoryAwareRunner` with a non-`None` memory
**When** the underlying runner returns a `RunResult`
**Then** a `MemoryWritten` event is emitted if and only if the `RunResult` is `Completed`, with `episodes` equal to the count of `remember` calls that succeeded (0 if both `writeUserInput` and `writeAssistantOutput` are false)

**Rationale**: symmetric to `MemoryRecalled`; lets consumers confirm the turn's facts were persisted and how many.

#### Scenario: Completed with both write flags emits episodes = 2

**Given** memory present, `writeUserInput = true`, `writeAssistantOutput = true`, and the underlying runner returns `Completed(out, msgs)`
**When** the caller invokes `runWithEvents(messages)`
**Then** the event stream contains exactly one `MemoryWritten` event with `episodes == 2`, emitted after the underlying runner's final event

#### Scenario: Completed with both write flags false emits episodes = 0

**Given** memory present, `writeUserInput = false`, `writeAssistantOutput = false`, and the underlying runner returns `Completed(out, msgs)`
**When** the caller invokes `runWithEvents(messages)`
**Then** the event stream contains exactly one `MemoryWritten` event with `episodes == 0` (the event fires to mark the seam ran, even though no episodes were written)

#### Scenario: Interrupted emits no MemoryWritten (adversarial)

**Given** memory present and the underlying runner returns `Interrupted(checkpointId, signal)`
**When** the caller invokes `runWithEvents(messages)`
**Then** the event stream contains ZERO `MemoryWritten` events

#### Scenario: Failed emits no MemoryWritten (adversarial)

**Given** memory present and the underlying runner returns `Failed(error)`
**When** the caller invokes `runWithEvents(messages)`
**Then** the event stream contains ZERO `MemoryWritten` events

### Requirement: New variants carry the run's RunPath

The system SHALL set each `MemoryRecalled` and `MemoryWritten` event's `runPath` to the same `RunPath` the underlying runner uses for its events (respecting `AgentEventEmitter.scoped` scoping), so the memory events nest correctly under the agent's `RunStep`.

**Given** a `MemoryAwareRunner` whose underlying `AgentRunner` emits events with `RunPath.of(agentName)`
**When** the new memory events are emitted
**Then** their `runPath` equals `RunPath.of(agentName)` (the same path the underlying runner's `MessageOutput`/`Interrupted` events carry), and `withPrependedStep` behaves identically to the other variants when the emitter is scoped

**Rationale**: the event stream's hierarchical scoping must remain consistent; memory events that dropped or invented a `RunPath` would break consumers that group events by path.

#### Scenario: RunPath matches the runner's other events

**Given** a `MemoryAwareRunner` wrapping a runner whose agent is named `"alice"` and which emits `MessageOutput` with `runPath = RunPath.of("alice")`
**When** the caller invokes `runWithEvents(messages)` and the run completes
**Then** the `MemoryRecalled` and `MemoryWritten` events both have `runPath == RunPath.of("alice")`

#### Scenario: Scoped emitter prepends the scope step

**Given** a `MemoryAwareRunner` whose emitter is `scoped(RunStep("outer"))`
**When** a `MemoryRecalled` event is emitted
**Then** the emitted event's `runPath` has `RunStep("outer")` prepended, identical to how `MessageOutput` would be scoped

## Exhaustiveness Impact (sealed ADT extension)

This spec adds two variants to the sealed `AgentEvent` trait. Per the
capability-profile, `-Wconf:name=PatternMatchExhaustivity:e` is active, so
every existing pattern match over `AgentEvent` that does not have a
catch-all MUST handle the two new variants or Ring 0 fails. The matches
identified in the codebase and their required behavior for the new variants:

| Match site | Current shape | Required behavior for `MemoryRecalled` / `MemoryWritten` | Justification |
|------------|---------------|----------------------------------------------------------|---------------|
| `AgentEvent.withPrependedStep` (the trait's abstract method, implemented per variant) | per-variant `copy(runPath = runPath.prepended(step))` | The two new variants MUST implement `withPrependedStep` by `copy(runPath = runPath.prepended(step))`, exactly like the other variants. | the trait is abstract; a new variant that does not implement it does not compile. |
| Event-stream consumers in tests/examples that pattern-match over `AgentEvent` for display or counting | typically `case _: MessageOutput => ...` etc., sometimes with a catch-all | If a catch-all exists, the new variants fall through it (acceptable for display/counting). If NO catch-all exists, the match MUST add `case MemoryRecalled(_, _, _) => ...` and `case MemoryWritten(_, _) => ...` arms. | exhaustiveness escalation makes a missing arm a compile error. |
| Any serialization of `AgentEvent` (if present) | (none found — `AgentEvent` does not derive `ReadWriter`; only `InterruptSignal` does) | N/A — no serialization match to update. | verified by grep: `AgentEvent` has no `derives ReadWriter`. |

> The `withPrependedStep` obligation is enforced by the type system (abstract
> method). The consumer-match obligation is enforced by `-Wconf` exhaustiveness
> escalation at Ring 0. Both are listed as compile-time proof obligations
> below.

## Properties (Ring 3)

### Property: MemoryRecalled fires iff memory is present

**Invariant**: For all `MemoryAwareRunner` configurations, exactly one `MemoryRecalled` event is emitted per `runWithEvents` invocation if and only if `memory` is non-`None`; when `memory = None`, zero `MemoryRecalled` events are emitted.

**Generator strategy**: `genOptMemory` (constructive — `Option[InMemoryAgentMemory[IO]]` with `Gen.frequency1(1 -> None, 3 -> Some(...))`); `genPolicy` (constructive); `genRunResult`. Edge cases: `memory = None`, `recallK = 0`. `cover`: `memory.isDefined` ≥ 60%, `memory.isEmpty` ≥ 30%.

```
forAll { (memory: Option[AgentMemory[IO]], policy: MemoryPolicy, outcome: RunResult) =>
  val decorator = MemoryAwareRunner(stubRunnerReturning(outcome), memory, policy, emitter)
  for {
    (_, events) = decorator.runWithEvents(msgs)
    recalled = events.collect { case e: MemoryRecalled => e }
  } yield memory.isDefined ==> (recalled.length == 1) && memory.isEmpty ==> (recalled.isEmpty)
}
```

### Property: MemoryWritten fires iff Completed and memory present

**Invariant**: For all configurations, exactly one `MemoryWritten` event is emitted if and only if `memory` is non-`None` AND the `RunResult` is `Completed`; otherwise zero. The `episodes` field equals the number of `remember` calls that succeeded.

**Generator strategy**: `genOptMemory`; `genPolicy` (booleans for write flags); `genRunResult`. Edge cases: `Interrupted`, `Failed`, `memory = None`, both write flags false (episodes = 0 but event still fires on Completed). `cover`: `Completed` ≥ 33%, `Interrupted` ≥ 33%, `Failed` ≥ 33%, `memory.isEmpty` ≥ 20%.

```
forAll { (memory: Option[AgentMemory[IO]], policy: MemoryPolicy, outcome: RunResult) =>
  val decorator = MemoryAwareRunner(stubRunnerReturning(outcome), memory, policy, emitter)
  for {
    (_, events) = decorator.runWithEvents(msgs)
    written = events.collect { case e: MemoryWritten => e }
  } yield (memory.isDefined && outcome.isInstanceOf[Completed]) ==> (
    written.length == 1 && written.head.episodes == expectedEpisodeCount(policy)
  ) && (!(memory.isDefined && outcome.isInstanceOf[Completed])) ==> (written.isEmpty)
}
```

### Property: New variants' RunPath equals the runner's other events

**Invariant**: For all runs, the `runPath` of every `MemoryRecalled` and `MemoryWritten` event equals the `runPath` of the `MessageOutput` (or `Interrupted`/`ErrorOccurred`) event the underlying runner emits for the same run.

**Generator strategy**: `genAgentName` (`Gen.string (Range.linear 1 12)`); `genRunResult`. Edge cases: empty agent name. No `cover` (universal over the generated names).

```
forAll { (agentName: String, outcome: RunResult) =>
  val decorator = MemoryAwareRunner(stubRunnerNamed(agentName, outcome), Some(memory), policy, emitter)
  for {
    (_, events) = decorator.runWithEvents(msgs)
    runnerEvents = events.filterNot(e => e.isInstanceOf[MemoryRecalled] || e.isInstanceOf[MemoryWritten])
    memoryEvents = events.filter(e => e.isInstanceOf[MemoryRecalled] || e.isInstanceOf[MemoryWritten])
    runnerPaths = runnerEvents.map(_.runPath).toSet
  } yield memoryEvents.forall(e => runnerPaths.contains(e.runPath))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A pattern match over `AgentEvent` that handles the seven existing variants but not `MemoryRecalled`/`MemoryWritten` and has no catch-all | Exhaustiveness escalation makes this a compile error; the test pins the obligation. | `assertDoesNotCompile("{ (e: AgentEvent) => e match { case _: MessageOutput => 1; case _: ToolCallRequested => 1; case _: ToolCallCompleted => 1; case _: IterationCompleted => 1; case _: Interrupted => 1; case _: ErrorOccurred => 1; case _: TokenDelta => 1 } }")` |
| `MemoryRecalled` / `MemoryWritten` without a `RunPath` | The variants MUST carry a `RunPath` (the trait requires it). | type system (the `AgentEvent` trait's `def runPath: RunPath` is abstract) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| `MemoryRecalled` fires exactly once iff memory present | Requirement: MemoryRecalled event | Hedgehog property (MemoryRecalled fires iff memory present) | `MemoryEventsSpec` |
| `MemoryWritten` fires exactly once iff Completed and memory present | Requirement: MemoryWritten event | Hedgehog property (MemoryWritten fires iff Completed and memory present) | `MemoryEventsSpec` |
| `hitCount` matches the recall result size | Requirement: MemoryRecalled event (non-empty/empty scenarios) | Scenario test | `MemoryEventsSpec` |
| `episodes` matches the `remember` call count | Requirement: MemoryWritten event (both-flags scenario) | Scenario test + Hedgehog property | `MemoryEventsSpec` |
| No `MemoryRecalled`/`MemoryWritten` when `memory = None` | Requirements (adversarial scenarios) | Adversarial scenario tests | `MemoryEventsSpec` |
| New variants' `runPath` matches the runner's other events | Requirement: New variants carry the run's RunPath | Hedgehog property (RunPath equals) | `MemoryEventsSpec` |
| `withPrependedStep` implemented for both new variants | Exhaustiveness Impact | Type system (abstract method) + compile test | `AgentEvent` compile |
| Existing `AgentEvent` matches handle the new variants or have a catch-all | Exhaustiveness Impact | `-Wconf:name=PatternMatchExhaustivity:e` at Ring 0 + `assertDoesNotCompile` | compile-time + `MemoryEventsTypeContract` |
| `AgentEvent` does not derive `ReadWriter` (no serialization match to update) | Exhaustiveness Impact | Static grep: `AgentEvent` line has no `derives ReadWriter` | spec-lint static check |
| Concurrency scenarios use `TestControl` | CONCURRENCY RULE | Static: `TestControl` used for event-stream assertions | `MemoryEventsSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `MemoryRecalled` | final case class (AgentEvent variant) | `adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala` | added to `AgentEvent` companion; `runPath: RunPath, query: String, hitCount: Int`; implements `withPrependedStep` via `copy` |
| `MemoryWritten` | final case class (AgentEvent variant) | `adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala` | added to `AgentEvent` companion; `runPath: RunPath, episodes: Int`; implements `withPrependedStep` via `copy` |
| `openspec/concepts/agent-event-stream.md` | concept file | `openspec/concepts/` | UPDATED — add the two variants to "AgentEvent variants" + Implementation map |
| `MemoryAwareRunner` event emission | code change | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryAwareRunner.scala` | emit `MemoryRecalled` after `preTurn`, `MemoryWritten` after `postTurn` (only on `Completed`) — this is the cross-spec coupling: the events spec's emission points live in the hook's decorator file |
| `MemoryEventsSpec` | munit + Hedgehog + TestControl | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/MemoryEventsSpec.scala` | new test file |
| `MemoryEventsTypeContract` | typed contract | `adk4s-core/src/test/scala/org/adk4s/core/interrupt/typecontract/MemoryEventsTypeContract.scala` | `assertDoesNotCompile` for the inexhaustive match |
| `stryker4s.conf` `mutate` retarget | build config | `stryker4s.conf` | add `**/interrupt/AgentEvent.scala` (the new variants' `withPrependedStep`) for Ring 5 |
| Compile | build step | `adk4s-core`, `adk4s-orchestration` | `sbt adk4s-core/compile`, `sbt adk4s-orchestration/compile` |
| Test | build step | `adk4s-orchestration` | `sbt adk4s-orchestration/test` |
