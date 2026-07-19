# Proposal: Add memory hook to orchestration (`MemoryHook` / `MemoryAwareRunner`)

## Why

The `adk4s-memory-api` module (shipped in the archived `2026-07-05-add-memory-api`
change) provides the `AgentMemory[F]` capability, value types (`Episode`,
`MemoryHit`, `TemporalScope`), the `InMemoryAgentMemory` test double, the
`MemoryRetriever` bridge, and the `AgentMemoryLaws` contract. That change
**deliberately deferred** the orchestration hook (source design
`docs/adk4s-memory-api.md` §6) so the interface could ship without waiting on
verification of the live `ReactAgent` / `AgentRunner` signatures.

This change is that deferred follow-up. It makes an agent **memory-aware** in
an **opt-in, additive, non-breaking** way: when an `AgentMemory` is supplied,
the agent recalls relevant facts before the LLM call and persists the turn
after it completes. When no memory is supplied, agents behave byte-for-byte as
today. This closes the loop from "memory capability exists" to "agents
actually use it without each caller re-implementing the recall/remember
plumbing."

**Design goal:** the memory hook lives at the `AgentRunner` layer (not inside
the core `ReactAgent` ReAct loop), so the core loop stays untouched and the
hook is constructed only when a caller explicitly opts in.

## What Changes

A new package `org.adk4s.orchestration.memory` is added to
`adk4s-orchestration`, which gains a dependency on `adk4s-memory-api`. It
introduces two new public types — `MemoryPolicy` (immutable config) and
`MemoryHook` (the recall/remember wrapper) — plus a `MemoryAwareRunner`
decorator that wraps an existing `AgentRunner`. No existing `ReactAgent` or
`AgentRunner` source is modified; the decorator composes from the outside.

### Affected Capabilities

- `specs/memory-orchestration-hook/spec.md` — NEW. `MemoryPolicy`,
  `MemoryHook` (pre-turn recall + post-turn remember, pure no-op when memory
  is `None`), `MemoryAwareRunner` decorator over `AgentRunner`, and the
  opt-in wiring contract (default agents unchanged; memory active only when
  explicitly constructed).
- `specs/memory-orchestration-events/spec.md` — NEW (optional, small). Two
  `AgentEvent` variants — `MemoryRecalled` and `MemoryWritten` — for
  observability parity with the rest of the event stream. This requires
  extending the sealed `AgentEvent` hierarchy in `adk4s-core`, so it is
  isolated in its own spec and may be deferred independently.

### Out of Scope

- No storage engine, embeddings, or graph logic — `AgentMemory`
  implementations live elsewhere (e.g. GraphStore).
- No change to the `ReactAgent` ReAct loop itself. The hook is at the
  `AgentRunner` layer only.
- No change to default `AgentRunner` behavior. `MemoryAwareRunner` is never
  constructed unless the caller opts in; existing examples run identically.
- No F-polymorphic refactor of `AgentRunner`. The live `AgentRunner` is
  concrete and `IO`-based (`run(...): IO[RunResult]`), NOT `F[_]`-polymorphic
  as the source doc's §6 sketch assumed. The hook is therefore `IO`-based to
  match, not `Async[F]`-polymorphic. (Generalizing `AgentRunner` to `F[_]` is
  a separate, larger change.)
- No new transitive heavy dependencies beyond `adk4s-memory-api` (which itself
  pulls only `cats-core` on main scope).

## Approach

Two seams around a ReAct turn, implemented as pure wrappers:

- **Pre-turn recall (read):** before the run, `recall` the user's latest input
  and inject the hits as an additional context block prepended to the input.
- **Post-turn write (remember):** after a turn completes, `remember` the user
  input and/or the assistant's final message as `Episode`s.

`MemoryHook` is a pure no-op when `memory` is `None`. `MemoryAwareRunner`
decorates `AgentRunner`: it runs `preTurn`, delegates to the underlying
runner's `run` / `runWithEvents`, then runs `postTurn`. The final assistant
output is extracted from `RunResult.Completed(output, _)` (where `output` is a
`String`).

**Verified deltas from the source doc** (the doc's `⚠ VERIFY` items in §6,
resolved against the live sources):

- `AgentRunner` is **concrete and `IO`-based**, not `F[_]`-polymorphic.
  `run(messages: List[Message], maxSteps: Int = 10): IO[RunResult]`,
  `runWithEvents(...): (IO[RunResult], Stream[IO, AgentEvent])`,
  `resume(checkpointId, results, maxSteps): IO[RunResult]`. The hook is
  `IO`-based, not `Async[F]`-polymorphic.
- `AgentRunner.run` takes `List[Message]` (llm4s
  `org.llm4s.llmconnect.model.Message`), not a single `String`. Context
  injection prepends a `UserMessage` (or appends to the latest user message)
  rather than string concatenation.
- `RunResult.Completed(output: String, messages: List[Message])` — the final
  assistant text is directly available as `output: String`; no message
  extraction helper is needed.
- `AgentEvent` is a **sealed trait** with variants in its companion object in
  `adk4s-core/interrupt/AgentEvent.scala`. Adding `MemoryRecalled` /
  `MemoryWritten` requires editing that sealed hierarchy in `adk4s-core`, so
  the event variants are isolated in a separate spec and may be deferred.

## Correctness Risk Level

**Risk**: medium — the hook wraps an existing `IO`-based runner and injects a
context message + persists episodes; the risk is in (a) preserving
interrupt/resume semantics (a `RunResult.Interrupted` must NOT trigger a
spurious `postTurn` write of incomplete output) and (b) keeping default
behavior byte-for-byte identical when memory is absent. No money/persistence
schema/evaluator logic; pure orchestration wiring with fallback/default paths.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan
- [x] Ring 2: Architecture — project-specific layer dependencies, sealed domain types, effect discipline (new `org.adk4s.orchestration.memory` package must depend only on `adk4s-orchestration` + `adk4s-memory-api`; the events spec extends the sealed `AgentEvent` ADT in `adk4s-core`)
- [x] Ring 3: Property-based tests — MANDATORY (waiver only for docs/formatting/build-metadata/test-only changes; state waiver + rationale here if claimed). This change touches INTERRUPT/RESUME semantics (a `RunResult.Interrupted` must not trigger a `postTurn` write), so its concurrency scenarios MUST use the detected deterministic test kit (cats-effect `TestControl` via munit-cats-effect), never wall-clock sleeps.
- [ ] Ring 4: Wire/persistence compatibility — round-trips, old fixtures, snapshots (REQUIRED if serialization/persistence/wire data is touched)
- [x] Ring 5: Mutation testing — Stryker4s on changed files, threshold 90% (pure orchestration wiring with default/no-op paths)
- [ ] Ring 6: Formal verification — Stainless, PureScala modules only
- [ ] Ring 7: Model checking — TLA+/Apalache for distributed/event-driven invariants
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY for code changes (fresh-context reviewer; runs BEFORE Rings 5/6/7 in the apply sequence)
- [ ] Ring 9: Telemetry — span contracts, temporal monitors (only if telemetry stack detected)

Ring 4 is not checked: the hook does not touch serialization, persisted
checkpoint state, or wire formats. `AgentRunner`'s existing checkpoint
serialization is unchanged; the decorator only forwards `RunResult` values.

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| `specs/memory-orchestration-hook/spec.md` | Full | Introduces new public types (`MemoryPolicy`, `MemoryHook`, `MemoryAwareRunner`) and a decorator API over `AgentRunner`; defines the no-op-when-absent invariant and the interrupt-safety invariant. |
| `specs/memory-orchestration-events/spec.md` | Full | Extends the sealed `AgentEvent` ADT in `adk4s-core` with two new variants (`MemoryRecalled`, `MemoryWritten`); error/event algebra change. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `AgentMemory[F]` | trait | `org.adk4s.memory` | The capability the hook calls into; `remember` / `recall` / `rememberAll`. |
| `Episode` / `SourceType` / `EpisodeOutcome` | case class / enum | `org.adk4s.memory` | Value types for `remember`; `SourceType.Conversation` / `ToolResult` for the two write paths. |
| `MemoryHit` / `TemporalScope` | case class | `org.adk4s.memory` | Recall result + optional point-in-time scope. |
| `AgentRunner` | final class | `org.adk4s.orchestration.agent` | Decorated by `MemoryAwareRunner`; `run` / `runWithEvents` / `resume` are `IO`-based. |
| `RunResult` (sealed) | sealed trait | `org.adk4s.orchestration.agent` | `Completed(output: String, messages)` provides the final assistant text for `postTurn`. |
| `Message` / `UserMessage` / `SystemMessage` | case class | `org.llm4s.llmconnect.model` | Context injection prepends/appends to user message list. |
| `AgentEvent` / `AgentEventEmitter` / `RunPath` | sealed trait / class | `org.adk4s.core.interrupt` | Event stream the new variants plug into (events spec only). |
| `Retriever` / `RetrieverConfig` / `Document` | trait / case class | `org.adk4s.core.component` | Already bridged by `MemoryRetriever`; not re-implemented here. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `MemoryPolicy` | final case class | Immutable config: `recallK`, `scope`, `writeUserInput`, `writeAssistantOutput`, `render: List[MemoryHit] => String`. Defaults to a sensible no-frills render. |
| `MemoryHook` | final class | Pure recall/remember wrapper over `Option[AgentMemory[IO]]`. `preTurn(latestUserInput): IO[Option[String]]` and `postTurn(groupId, userInput, assistantOutput, at): IO[Unit]`. No-op when memory is `None`. |
| `MemoryAwareRunner` | final class (decorator) | Wraps `AgentRunner`: `run` / `runWithEvents` / `resume` delegate after `preTurn` and before `postTurn`. Skips `postTurn` on `RunResult.Interrupted` / `RunResult.Failed`. |
| `MemoryRecalled` | AgentEvent variant | `MemoryRecalled(runPath: RunPath, query: String, hitCount: Int)` — observability for pre-turn recall (events spec only). |
| `MemoryWritten` | AgentEvent variant | `MemoryWritten(runPath: RunPath, episodes: Int)` — observability for post-turn write (events spec only). |

## Risks and Mitigations

- **Interrupt-safety:** a `RunResult.Interrupted` must not trigger a
  `postTurn` write of partial/incomplete output. Mitigation: `MemoryAwareRunner`
  pattern-matches on `RunResult` and only calls `postTurn` on
  `RunResult.Completed`; `Interrupted` / `Failed` skip the write. Covered by a
  Ring 3 property test.
- **Default behavior preservation:** when `memory = None`, the agent must be
  byte-for-byte identical to running the underlying `AgentRunner` directly.
  Mitigation: `MemoryHook.preTurn` returns `IO.pure(None)` and `postTurn`
  returns `IO.unit`; `MemoryAwareRunner` only prepends a context message when
  `preTurn` yields `Some(...)`. Covered by a Ring 3 property test asserting
  identical `RunResult` and event stream shape.
- **Context injection shape:** prepending a separate `UserMessage` with the
  recalled context could shift the LLM's interpretation vs. appending to the
  existing user message. Mitigation: default `render` produces a clearly
  labeled "Relevant memory:" block; the injection strategy is part of the
  spec's typed contract and validated by a scenario test.
- **Sealed `AgentEvent` extension:** adding variants in `adk4s-core` is a
  cross-module change. Mitigation: isolated in `memory-orchestration-events`
  spec so it can be deferred without blocking the core hook.
