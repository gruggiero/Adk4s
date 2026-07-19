# Spec: Memory Orchestration Hook

<!-- This is a DELTA spec. Use ## ADDED Requirements for new content.
     Each spec is implemented and verified INDEPENDENTLY through the full
     ring pipeline. Keep specs self-contained — one capability per spec.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by Given/When/Then
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words without a concrete definition next to them
     - ADVERSARIAL RULE: every requirement containing "only", "never", or
       "must not" needs at least one scenario whose INPUT the requirement forbids
     - CONCURRENCY RULE: requirements about interrupt/resume state a
       DETERMINISTIC observable, tested with cats-effect TestControl. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [AgentRunner](../../../../concepts/agent-runner.md) | decorated by `MemoryAwareRunner`; its `run`/`runWithEvents`/`resume` actions are wrapped with a pre-turn recall and post-turn remember seam | `openspec/concepts/agent-runner.md` |
| [AgentEventStream](../../../../concepts/agent-event-stream.md) | the hook emits no events itself (events are the separate events spec), but `MemoryAwareRunner.runWithEvents` must forward the underlying runner's event stream unchanged | `openspec/concepts/agent-event-stream.md` |
| `MemoryAwareRunner` (NEW — created by this spec) | the new behavioral unit: a memory-aware decorator over `AgentRunner` with `run`/`runWithEvents`/`resume` actions and `RecallToContext` / `WriteEpisode` synchronizations | `openspec/concepts/memory-aware-runner.md` (to be created as part of this spec) |

> This spec creates a NEW behavioral concept `MemoryAwareRunner`. Creating
> `openspec/concepts/memory-aware-runner.md` (purpose / state / actions /
> operational principle / Implementation map / synchronizations) is PART OF
> implementing this spec. The concept file's Implementation map is the single
> place the code identifiers (`MemoryPolicy`, `MemoryHook`,
> `MemoryAwareRunner`) live; the requirements below use behavioral vocabulary
> only.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AgentMemory[F]` | service trait | `org.adk4s.memory` |
| `Episode` | case class | `org.adk4s.memory` |
| `SourceType` | enum | `org.adk4s.memory` |
| `EpisodeOutcome` | case class | `org.adk4s.memory` |
| `MemoryHit` | case class | `org.adk4s.memory` |
| `TemporalScope` | case class | `org.adk4s.memory` |
| `AgentRunner` | final class | `org.adk4s.orchestration.agent` |
| `RunResult` (Completed / Interrupted / Failed) | sealed trait | `org.adk4s.orchestration.agent` |
| `UserMessage` / `Message` | case class / trait | `org.llm4s.llmconnect.model` |
| `InMemoryAgentMemory` | class (test double) | `org.adk4s.memory` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `MemoryPolicy` | final case class | Immutable config: `recallK: Int`, `scope: Option[TemporalScope]`, `writeUserInput: Boolean`, `writeAssistantOutput: Boolean`, `render: List[MemoryHit] => String`. Defaults to a labeled "Relevant memory:" block render. |
| `MemoryHook` | final class | Pure recall/remember wrapper over `Option[AgentMemory[IO]]`. `preTurn(latestUserInput: String): IO[Option[String]]` and `postTurn(groupId: String, userInput: String, assistantOutput: String, at: Instant): IO[Unit]`. No-op when memory is `None`. |
| `MemoryAwareRunner` | final class (decorator) | Wraps `AgentRunner`: `run` / `runWithEvents` / `resume` delegate after `preTurn` and before `postTurn`. Skips `postTurn` on `RunResult.Interrupted` / `RunResult.Failed`. |

## ADDED Requirements

<!-- ALTITUDE RULE: requirements and scenarios use behavioral vocabulary only —
     Concept/action references, the project's user-facing surface language
     (DSL paths, API fields), new domain terms, concrete test vectors. Module
     names, error class names, and build commands belong in "## Implementation
     Anchors" at the bottom, never in a Given/When/Then. -->

### Requirement: Opt-in memory activation

The system SHALL make an agent memory-aware ONLY when a caller explicitly constructs a `MemoryAwareRunner` with a non-`None` memory; when no memory is supplied, the agent's behavior MUST be byte-for-byte identical to running the underlying `AgentRunner` directly.

**Given** a `MemoryAwareRunner` constructed with `memory = None`
**When** the caller invokes `run` / `runWithEvents` / `resume` with any message list
**Then** the returned `RunResult` and the emitted event stream are identical to those the underlying `AgentRunner` would produce for the same inputs (no extra messages injected, no episodes written, no extra events emitted by the hook)

**Rationale**: the hook is opt-in and additive; default agents must not change behavior, so existing examples and callers run unchanged.

#### Scenario: No-op runner matches underlying runner exactly

**Given** a `MemoryAwareRunner` wrapping an `AgentRunner` whose inner agent returns a fixed `Completed("done", msgs)`, with `memory = None` and any `MemoryPolicy`
**When** the caller invokes `run(messages)`
**Then** the returned `RunResult` equals `Completed("done", msgs)` AND the message list passed to the underlying runner equals the original `messages` (no prepended context) AND `AgentMemory.remember` is never called (zero episodes written)

#### Scenario: No-op runner forwards interrupt unchanged

**Given** a `MemoryAwareRunner` with `memory = None` wrapping a runner whose inner agent raises an interrupt that yields `Interrupted(checkpointId, signal)`
**When** the caller invokes `run(messages)`
**Then** the returned `RunResult` is `Interrupted(checkpointId, signal)` with the same `checkpointId` and `signal` the underlying runner would produce, AND no `postTurn` write occurs

#### Scenario: Adversarial — a caller cannot force a write without opting in

**Given** a `MemoryAwareRunner` constructed with `memory = None` but a `MemoryPolicy` that has `writeUserInput = true` and `writeAssistantOutput = true`
**When** the caller invokes `run(messages)` and the run completes with `Completed`
**Then** zero episodes are written to any memory (the policy's write flags are inert when memory is absent) — the `memory = None` gate is the sole authority on whether writes happen

### Requirement: Pre-turn recall injects context

The system SHALL, when memory is present, recall the `recallK` most relevant facts for the latest user input before the agent turn and inject the rendered hits as a labeled context block prepended to the input; when recall yields no hits, the system MUST NOT inject any context block.

**Given** a `MemoryAwareRunner` constructed with a non-`None` `AgentMemory[IO]` and a `MemoryPolicy` with `recallK = k > 0`
**When** the caller invokes `run(messages)` where the last message is a user message with text `q`
**Then** `AgentMemory.recall(q, k, policy.scope)` is called once before the underlying runner's turn, and if it returns a non-empty hit list the rendered block is prepended as a separate `UserMessage` before `messages` (or appended to the latest user message per the design's chosen injection strategy), and if it returns an empty hit list no context message is added

**Rationale**: the agent must see recalled memory before reasoning, but only when there is something to recall — an empty "Relevant memory:" block would waste prompt budget and could mislead the model.

#### Scenario: Non-empty recall injects a labeled block

**Given** memory contains episodes such that `recall("Alice's role", 3, None)` returns two hits with texts "Alice works at Acme" and "Alice is an engineer"
**When** the caller invokes `run([UserMessage("What is Alice's role?")])`
**Then** the message list handed to the underlying runner begins with a `UserMessage` whose content contains the substring "Relevant memory:" AND contains both hit texts AND the original `UserMessage("What is Alice's role?")` is preserved as the subsequent user message

#### Scenario: Empty recall injects nothing

**Given** memory is present but `recall(q, k, scope)` returns `Nil`
**When** the caller invokes `run([UserMessage(q)])`
**Then** the message list handed to the underlying runner equals the original `messages` exactly (no context message prepended or appended)

#### Scenario: recallK = 0 skips recall entirely

**Given** a `MemoryPolicy` with `recallK = 0` and a non-`None` memory
**When** the caller invokes `run(messages)`
**Then** `AgentMemory.recall` is never called AND no context message is injected

### Requirement: Post-turn write only on Completed

The system SHALL write episodes via `AgentMemory.remember` after a turn ONLY when the `RunResult` is `Completed`; on `Interrupted` or `Failed` the system MUST NOT write any episode, so partial or incomplete output is never persisted as memory.

**Given** a `MemoryAwareRunner` with a non-`None` memory and a `MemoryPolicy` with `writeUserInput = true` and/or `writeAssistantOutput = true`
**When** the underlying runner's `run` returns a `RunResult`
**Then** `AgentMemory.remember` is called (with `SourceType.Conversation` episodes for the user input and/or the assistant output, sharing a `groupId`) if and only if the `RunResult` is `Completed`; for `Interrupted` and `Failed`, `remember` is never called

**Rationale**: an interrupted run has no final assistant output, and a failed run's output is not trustworthy; persisting either would corrupt the memory store with partial/erroneous facts.

#### Scenario: Completed writes the configured episodes

**Given** a `MemoryPolicy` with `writeUserInput = true` and `writeAssistantOutput = true`, memory present, and the underlying runner returns `Completed("Alice is an engineer", msgs)`
**When** the caller invokes `run([UserMessage("What is Alice's role?")])`
**Then** `AgentMemory.remember` is called exactly twice: once with an `Episode` whose `content` is the user input and `sourceType` is `Conversation`, and once with an `Episode` whose `content` is "Alice is an engineer" and `sourceType` is `Conversation`, and both episodes share the same non-`None` `groupId`

#### Scenario: Interrupted writes nothing (adversarial)

**Given** memory present and `writeUserInput = true`, `writeAssistantOutput = true`, and the underlying runner returns `Interrupted(checkpointId, signal)`
**When** the caller invokes `run(messages)`
**Then** `AgentMemory.remember` is never called (zero episodes), AND the returned `RunResult` is `Interrupted(checkpointId, signal)` unchanged

#### Scenario: Failed writes nothing (adversarial)

**Given** memory present and the underlying runner returns `Failed(error)`
**When** the caller invokes `run(messages)`
**Then** `AgentMemory.remember` is never called AND the returned `RunResult` is `Failed(error)` unchanged

#### Scenario: writeUserInput = false suppresses the user episode

**Given** a `MemoryPolicy` with `writeUserInput = false`, `writeAssistantOutput = true`, memory present, and the underlying runner returns `Completed(out, msgs)`
**When** the caller invokes `run([UserMessage(q)])`
**Then** `AgentMemory.remember` is called exactly once, with an `Episode` whose `content` is `out` (the assistant output), and no episode whose `content` is `q`

### Requirement: runWithEvents forwards the underlying stream unchanged

The system SHALL, when `runWithEvents` is invoked, return the underlying `AgentRunner`'s event stream verbatim AND the same `IO[RunResult]` (after pre-turn recall and post-turn write are applied to the result); the hook MUST NOT inject, drop, or reorder events in this spec (event variants for memory are the separate events spec).

**Given** a `MemoryAwareRunner` wrapping an `AgentRunner` whose `runWithEvents` produces a stream `S` of `AgentEvent`s and an `IO[RunResult]` `R`
**When** the caller invokes `runWithEvents(messages)`
**Then** the returned event stream is exactly `S` (same events, same order, no memory events added by this spec) AND the returned `IO[RunResult]` runs `preTurn` before `R` and `postTurn` (only on `Completed`) after `R`

**Rationale**: the hook spec is observability-neutral; memory observability is added by the events spec, not here, so the two concerns can be deferred independently.

#### Scenario: Stream identity under no memory

**Given** a `MemoryAwareRunner` with `memory = None`
**When** the caller invokes `runWithEvents(messages)`
**Then** the returned `(IO[RunResult], Stream[IO, AgentEvent])` is behaviorally identical to calling the underlying `AgentRunner.runWithEvents(messages)` directly — same result value, same event sequence

#### Scenario: Stream identity under memory (no memory events in this spec)

**Given** a `MemoryAwareRunner` with memory present
**When** the caller invokes `runWithEvents(messages)`
**Then** the returned event stream contains exactly the events the underlying runner would emit (no `MemoryRecalled`/`MemoryWritten` — those are the events spec) AND the result `IO` performs the recall/write seams

### Requirement: resume delegates without a spurious write

The system SHALL, when `resume(checkpointId, results)` is invoked, delegate to the underlying `AgentRunner.resume` after applying `preTurn` to the latest user input in the resumed messages, and MUST apply `postTurn` only if the resumed run completes with `Completed`.

**Given** a `MemoryAwareRunner` with memory present and a checkpoint `checkpointId` previously saved by an interrupted run
**When** the caller invokes `resume(checkpointId, results)`
**Then** the underlying `AgentRunner.resume(checkpointId, results)` is called, AND `preTurn` is applied to the latest user input in the reconstructed messages, AND `postTurn` runs only if the resumed `RunResult` is `Completed`

**Rationale**: resume is a continuation of the same logical turn; the interrupt-safety invariant (no write on `Interrupted`/`Failed`) must hold on resume exactly as on the initial run.

#### Scenario: Resume that completes writes the episode

**Given** a checkpoint from a prior interrupted run, memory present, and `AgentRunner.resume` returns `Completed(out, msgs)`
**When** the caller invokes `resume(checkpointId, results)`
**Then** `postTurn` runs exactly once with the resumed run's user input and `out`, AND `remember` is called per the policy

#### Scenario: Resume that interrupts again writes nothing (adversarial)

**Given** a checkpoint and `AgentRunner.resume` returns `Interrupted(checkpointId2, signal2)`
**When** the caller invokes `resume(checkpointId, results)`
**Then** `postTurn` does not run AND `remember` is never called AND the returned `RunResult` is `Interrupted(checkpointId2, signal2)`

### Requirement: MemoryPolicy render default

The system SHALL provide a default `render: List[MemoryHit] => String` that produces a labeled "Relevant memory:" block listing each hit's `text` on its own line, and MUST be a pure function of its input (no side effects, no I/O).

**Given** the default `MemoryPolicy` (no custom `render` supplied)
**When** `render` is applied to a hit list `[MemoryHit("Alice works at Acme", 0.9, ...), MemoryHit("Alice is an engineer", 0.8, ...)]`
**Then** the output is a single `String` that contains the substring "Relevant memory:" AND contains both "Alice works at Acme" and "Alice is an engineer" AND is deterministic (calling `render` twice on the same input yields character-identical output)

**Rationale**: a sensible default removes the need for every caller to write a renderer; purity makes it testable as a plain function and safe to call inside `IO`.

#### Scenario: Default render formats two hits

**Given** the default `MemoryPolicy.render`
**When** applied to `[MemoryHit("a", 0.9), MemoryHit("b", 0.8)]`
**Then** the output contains "Relevant memory:", "a", and "b" each on a distinct line

#### Scenario: Default render on empty list

**Given** the default `MemoryPolicy.render`
**When** applied to `Nil`
**Then** the output is the empty `String` (the caller checks emptiness before injecting, but the render itself must not throw on empty input)

#### Scenario: Custom render is honored

**Given** a `MemoryPolicy` constructed with `render = _.map(_.text).mkString("[", ",", "]")`
**When** `preTurn` renders a non-empty hit list
**Then** the injected context block uses the custom bracket format, NOT the default "Relevant memory:" block

## Properties (Ring 3)

### Property: No-memory decorator is the identity

**Invariant**: For all message lists `msgs`, all `MemoryPolicy` `p`, and all underlying-runner behaviors, a `MemoryAwareRunner` with `memory = None` produces a `RunResult` equal to the underlying runner's `RunResult` and never calls `AgentMemory.remember`.

**Generator strategy**: `genMessages` (constructive — a non-empty list of `UserMessage`/`AssistantMessage` with `genContent` text, Hedgehog `Range.linear 1 8`); `genPolicy` (constructive — `MemoryPolicy` with `recallK` in `Range.linear 0 5`, booleans for the write flags, default render). Underlying runner is a stub that returns a fixed `Completed`/`Interrupted`/`Failed` drawn from `genRunResult`. Edge cases: empty user input string, `recallK = 0`, both write flags false. `cover` labels: `Completed`/`Interrupted`/`Failed` each ≥ 30%.

```
forAll { (msgs: List[Message], policy: MemoryPolicy, outcome: RunResult) =>
  val memory: Option[AgentMemory[IO]] = None
  val stub = stubRunnerReturning(outcome)
  val decorator = MemoryAwareRunner(stub, memory, policy)
  for {
    direct <- stub.run(msgs)
    wrapped <- decorator.run(msgs)
    rememberCalls <- memoryCallCounter.get
  } yield wrapped == direct && rememberCalls == 0
}
```

### Property: postTurn fires iff Completed

**Invariant**: For all `RunResult` outcomes and all non-`None` memories, `AgentMemory.remember` is called a positive number of times (per the policy's write flags) if and only if the outcome is `Completed`; for `Interrupted` and `Failed`, `remember` is called zero times.

**Generator strategy**: `genRunResult` (constructive — one of `Completed(genContent, genMessages)`, `Interrupted(genCheckpointId, genSignal)`, `Failed(genAdkError)`); `genPolicy` with `writeUserInput`/`writeAssistantOutput` booleans; memory = `Some(InMemoryAgentMemory[IO])`. Edge cases: both write flags false (zero writes even on Completed), `recallK = 0`. `cover` labels: `Completed` ≥ 33%, `Interrupted` ≥ 33%, `Failed` ≥ 33%.

```
forAll { (outcome: RunResult, policy: MemoryPolicy) =>
  val memory = InMemoryAgentMemory[IO]
  val decorator = MemoryAwareRunner(stubRunnerReturning(outcome), Some(memory), policy)
  for {
    _ <- decorator.run(msgs)
    writes <- memory.rememberCount
  } yield (outcome match {
    case _: Completed => writes > 0 == (policy.writeUserInput || policy.writeAssistantOutput)
    case _: Interrupted | _: Failed => writes == 0
  })
}
```

### Property: recall is called exactly once per run when recallK > 0

**Invariant**: For all non-`None` memories and all `MemoryPolicy` with `recallK > 0`, `AgentMemory.recall` is called exactly once per `run` invocation, with `k = policy.recallK` and `scope = policy.scope`; for `recallK = 0`, `recall` is called zero times.

**Generator strategy**: `genRecallK` (`Range.linear 0 10`); `genScope` (existing — `Option[TemporalScope]`); `genContent` for the latest user input. Edge cases: `recallK = 0`, `recallK = 1`, empty user input. `cover`: `recallK == 0` ≥ 10%, `recallK > 0` ≥ 80%.

```
forAll { (recallK: Int, scope: Option[TemporalScope], q: String) =>
  val policy = MemoryPolicy(recallK = recallK, scope = scope, writeUserInput = true, writeAssistantOutput = true)
  val memory = RecordingMemory  // records recall(q, k, scope) calls
  val decorator = MemoryAwareRunner(stubRunnerReturning(Completed("out", msgs)), Some(memory), policy)
  for {
    _ <- decorator.run([UserMessage(q)])
    recallCalls <- memory.recallCalls
  } yield (recallK > 0 && recallCalls.length == 1 && recallCalls.head.k == recallK && recallCalls.head.scope == scope)
      || (recallK == 0 && recallCalls.isEmpty)
}
```

### Property: render is pure and total

**Invariant**: For all hit lists `hs`, `MemoryPolicy.defaultRender(hs) == MemoryPolicy.defaultRender(hs)` (deterministic) and never throws (totality), and for `Nil` the output is the empty string.

**Generator strategy**: `genHitList` (constructive — `Gen.list genHit Range.linear 0 6`). Edge cases: empty list, single hit, hits with empty `text`. No `cover` (universal).

```
forAll { (hs: List[MemoryHit]) =>
  val r1 = MemoryPolicy.defaultRender(hs)
  val r2 = MemoryPolicy.defaultRender(hs)
  r1 == r2 && (hs.isEmpty ==> r1.isEmpty) && r1 != null
}
```

### Property: groupId is shared across the turn's episodes

**Invariant**: For all `Completed` outcomes with `writeUserInput = true` and `writeAssistantOutput = true`, the two `Episode`s written by `postTurn` share the same non-`None` `groupId`; with only one write flag true, the single episode's `groupId` is `Some(_)`.

**Generator strategy**: `genPolicy` (booleans for the two write flags); `genContent` for user input and assistant output; `genInstant` for `at`. Edge cases: both flags false (no episodes — excluded from the groupId assertion). `cover`: both-true ≥ 40%, one-true ≥ 40%.

```
forAll { (policy: MemoryPolicy, q: String, out: String, at: Instant) =>
  val memory = InMemoryAgentMemory[IO]
  val hook = MemoryHook(Some(memory), policy)
  for {
    _ <- hook.postTurn("turn-1", q, out, at)
    episodes <- memory.recordedEpisodes
    groupIds = episodes.flatMap(_.groupId).toSet
  } yield (policy.writeUserInput || policy.writeAssistantOutput) ==> (
    episodes.nonEmpty && groupIds.size == 1 && groupIds.head.nonEmpty
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `MemoryAwareRunner` constructed without an underlying `AgentRunner` | The decorator MUST wrap a real runner — there is no "memory-only" runner. | `assertDoesNotCompile("MemoryAwareRunner(null, None, policy)")` (null is forbidden by WartRemover `Null` anyway; the constructor signature takes a non-optional `AgentRunner`) |
| `MemoryHook.postTurn` callable on a `RunResult` directly | `postTurn` takes already-extracted strings, not a `RunResult` — the `Completed`-only decision lives in `MemoryAwareRunner`, not the hook. | `assertDoesNotCompile("hook.postTurn(runResult)")` (type mismatch: `postTurn` accepts `(String, String, String, Instant)`, not `RunResult`) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No-memory decorator is identity on `RunResult` and event stream | Requirement: Opt-in memory activation + scenarios | Hedgehog property (No-memory decorator is the identity) + scenario test | `MemoryAwareRunnerSpec`, `MemoryHookSpec` |
| `remember` never called when `memory = None` | Requirement: Opt-in memory activation (adversarial scenario) | Hedgehog property + adversarial scenario test | `MemoryAwareRunnerSpec` |
| `recall` called exactly once when `recallK > 0`, zero when `recallK = 0` | Requirement: Pre-turn recall injects context | Hedgehog property (recall called exactly once) | `MemoryAwareRunnerSpec` |
| Empty recall injects no context message | Requirement: Pre-turn recall injects context (empty scenario) | Scenario test asserting message list equality | `MemoryAwareRunnerSpec` |
| `postTurn` runs iff `Completed` | Requirement: Post-turn write only on Completed | Hedgehog property (postTurn fires iff Completed) + adversarial scenarios | `MemoryAwareRunnerSpec` |
| `Interrupted`/`Failed` produce zero writes | Requirement: Post-turn write only on Completed (adversarial) | Hedgehog property + adversarial scenario tests | `MemoryAwareRunnerSpec` |
| `runWithEvents` stream identity | Requirement: runWithEvents forwards the underlying stream unchanged | Scenario test comparing event streams element-wise | `MemoryAwareRunnerSpec` |
| `resume` honors the same `Completed`-only write rule | Requirement: resume delegates without a spurious write | Adversarial scenario test (resume that interrupts again) | `MemoryAwareRunnerSpec` |
| `render` is pure, total, deterministic, empty-on-Nil | Requirement: MemoryPolicy render default | Hedgehog property (render is pure and total) | `MemoryPolicySpec` |
| `groupId` shared across the turn's episodes | Requirement: Post-turn write only on Completed (Completed scenario) | Hedgehog property (groupId shared) | `MemoryHookSpec` |
| `MemoryPolicy` fields are immutable | Type strategy (final case class, no `var`) | Type system (case class) + WartRemover `Var` | compile-time |
| `MemoryAwareRunner` constructor requires an `AgentRunner` | Compile-Negative obligation | `assertDoesNotCompile` test | `MemoryAwareRunnerTypeContract` |
| Concurrency scenarios use `TestControl`, not wall-clock | CONCURRENCY RULE | Static: grep tests for `Thread.sleep`/`TimeUnit.sleep` absence; `TestControl` used for the interrupt-skip scenarios | `MemoryAwareRunnerSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `MemoryPolicy` | final case class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryPolicy.scala` | new file; `recallK: Int`, `scope: Option[TemporalScope]`, `writeUserInput: Boolean`, `writeAssistantOutput: Boolean`, `render: List[MemoryHit] => String`; companion `default` and `defaultRender` |
| `MemoryHook` | final class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryHook.scala` | new file; `preTurn`, `postTurn`; holds `Option[AgentMemory[IO]]` + `MemoryPolicy` |
| `MemoryAwareRunner` | final class (decorator) | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryAwareRunner.scala` | new file; wraps `AgentRunner`; `run`/`runWithEvents`/`resume` |
| `org.adk4s.orchestration.memory` | package | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/` | new package; Ring 2 layer rules in openspec/capability-profile.md |
| `adk4s-orchestration → adk4s-memory-api` | build dependency | `build.sbt` (`adk4s-orchestration` `.dependsOn`) | add `adk4s-memory-api` to the `.dependsOn(...)` list |
| `openspec/concepts/memory-aware-runner.md` | concept file | `openspec/concepts/` | NEW — created as part of this spec; Implementation map binds the three code anchors above |
| `MemoryAwareRunnerSpec` | munit + Hedgehog + TestControl | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/MemoryAwareRunnerSpec.scala` | new test file |
| `MemoryHookSpec` | munit + Hedgehog | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/MemoryHookSpec.scala` | new test file |
| `MemoryPolicySpec` | munit + Hedgehog | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/MemoryPolicySpec.scala` | new test file |
| `MemoryAwareRunnerTypeContract` | typed contract | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/typecontract/MemoryAwareRunnerTypeContract.scala` | compile-negative + signature contract |
| `Generators` (memory-orch) | Hedgehog `Gen` | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/Generators.scala` | new — `genPolicy`, `genHitList`, `genMessages`, `genRunResult` |
| `stryker4s.conf` `mutate` retarget | build config | `stryker4s.conf` | point `mutate` at `**/memory/MemoryHook.scala`, `**/memory/MemoryAwareRunner.scala`, `**/memory/MemoryPolicy.scala` for Ring 5 |
| Compile | build step | `adk4s-orchestration` | `sbt adk4s-orchestration/compile` |
| Test | build step | `adk4s-orchestration` | `sbt adk4s-orchestration/test` |
