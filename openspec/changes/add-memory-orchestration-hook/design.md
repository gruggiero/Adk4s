# Design: Memory Orchestration Hook

<!-- Technical design for the `add-memory-orchestration-hook` change.
     Covers package structure, effect boundaries, type-driven invalid-state
     prevention, refined-type strategy, IDL layout, error strategy,
     compatibility story, and the per-module verification map. -->

## 1. Package Structure

A single new package `org.adk4s.orchestration.memory` is added to
`adk4s-orchestration`. It contains exactly three public top-level types
(`MemoryPolicy`, `MemoryHook`, `MemoryAwareRunner`) and no nested packages.

```
adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/
├── MemoryPolicy.scala        # final case class + companion (default, defaultRender)
├── MemoryHook.scala          # final class — preTurn / postTurn
└── MemoryAwareRunner.scala   # final class (decorator) — run / runWithEvents / resume
```

The events spec adds two variants to the existing sealed `AgentEvent` trait in
`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala` — no
new package is created in `adk4s-core`.

### Layer dependency rules (Ring 2, project-specific from openspec/capability-profile.md)

| Layer | May import | Must NOT import |
|-------|------------|-----------------|
| `org.adk4s.orchestration.memory` | `cats-effect`, `fs2`, `org.adk4s.orchestration.agent` (`AgentRunner`, `RunResult`), `org.adk4s.core.interrupt` (`AgentEvent`, `AgentEventEmitter`, `RunPath`, `RunStep`), `org.adk4s.memory` (`AgentMemory`, `Episode`, `SourceType`, `MemoryHit`, `TemporalScope`), `org.llm4s.llmconnect.model` (`Message`, `UserMessage` — for context-injection message construction only) | `workflows4s`, the llm4s `LLMClient`/`Conversation`/`CompletionOptions`, `org.adk4s.core.tools`, `org.adk4s.core.component` (other than via `AgentRunner`'s public surface), `logback`, any HTTP/DB/persistence client |
| `org.adk4s.core.interrupt` (extended) | unchanged — gains two new case-class variants inside the existing `AgentEvent` companion | unchanged (still must not import `adk4s-orchestration` — the new variants carry only `RunPath`/`String`/`Int`, no orchestration types) |

These rules are advisory (no custom scalafix arch rule is installed — see
openspec/capability-profile.md Ring 2 ⚠️). They are enforced by code review + an
import audit in the hook spec's Ring 2 check. The key invariant: the new
package reaches `adk4s-memory-api` and `adk4s-orchestration.agent` only — it
does NOT reach into `workflows4s` or the llm4s LLM client, keeping the
memory seam at the runner layer (not the ReAct loop).

### Build wiring

`adk4s-orchestration` gains a `.dependsOn(adk4s-memory-api)` line in
`build.sbt`. `adk4s-memory-api` already depends on `adk4s-core` (for
`Retriever`/`Document`), so the new edge `adk4s-orchestration →
adk4s-memory-api → adk4s-core` introduces no cycle. No other module's
dependencies change.

## 2. Effect Boundaries

| Code | Pure / Effectful | Notes |
|------|------------------|-------|
| `MemoryPolicy.render: List[MemoryHit] => String` | **Pure** | a pure function of its input; the default `defaultRender` is a pure string builder. Safe to call inside `IO` and to property-test as a plain function. |
| `MemoryPolicy` (the case class itself) | **Pure** | immutable config; no behavior beyond `render`. |
| `MemoryHook.preTurn` | **Effectful (`IO`)** | calls `AgentMemory.recall` (an `F` effect) and renders hits; returns `IO[Option[String]]`. No-op (`IO.pure(None)`) when `memory = None`. |
| `MemoryHook.postTurn` | **Effectful (`IO`)** | calls `AgentMemory.remember` (an `F` effect) per the policy; returns `IO[Unit]`. No-op (`IO.unit`) when `memory = None`. |
| `MemoryAwareRunner.run` / `runWithEvents` / `resume` | **Effectful (`IO`)** | composes `preTurn` (IO) + underlying `AgentRunner.run` (IO) + `postTurn` (IO); pattern-matches on `RunResult` to gate `postTurn`. |

There is NO pure computation module in this change — the hook is effectful
`IO` wiring by design (it wraps an `IO`-based runner and calls an
`F[_]`-effectful memory). Ring 6 (Stainless) therefore does NOT apply; the
pure `render` function is covered by a Hedgehog purity/totality property
instead.

The `MemoryHook` is deliberately split out from `MemoryAwareRunner` so the
recall/remember seam is independently testable without a real `AgentRunner`
— `MemoryHookSpec` tests `preTurn`/`postTurn` against `InMemoryAgentMemory`
directly, while `MemoryAwareRunnerSpec` tests the decorator composition and
the `RunResult`-gated write.

## 3. Type Strategy — Invalid-State Prevention

| Invariant | Placement | Justification |
|-----------|-----------|---------------|
| `MemoryAwareRunner` cannot be constructed without an `AgentRunner` | **Best — unrepresentable**: the constructor takes a non-optional `agentRunner: AgentRunner` (no `Option`, no `null` — `null` is forbidden by WartRemover `Null`). | the decorator is meaningless without a runner to decorate; the type makes "no runner" unconstructible. |
| `MemoryPolicy.recallK` is a non-negative `Int` | **Good — smart constructor**: `MemoryPolicy.apply` requires `recallK >= 0` and throws `IllegalArgumentException` otherwise (a `require`); the default factory `MemoryPolicy.default` sets `recallK = 5`. Negative `recallK` is not a meaningful state. | a plain `Int` allows negatives; the smart constructor rejects them at construction. (No Iron/refined in the stack — see openspec/capability-profile.md.) |
| `postTurn` runs only on `Completed` | **Best — unrepresentable via sealed dispatch**: `MemoryAwareRunner` pattern-matches on the sealed `RunResult` and calls `postTurn` only in the `Completed` arm; the `Interrupted`/`Failed` arms skip it. The exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) guarantees the match covers all three variants. | the sealed `RunResult` ADT + exhaustiveness escalation makes "forget to handle `Interrupted`" a compile error, not a runtime bug. |
| `MemoryHook` is a no-op when `memory = None` | **Best — unrepresentable**: `preTurn`/`postTurn` pattern-match on `Option[AgentMemory[IO]]`; the `None` arm returns `IO.pure(None)` / `IO.unit` without touching `recall`/`remember`. | the `Option` type makes "no memory" a first-class state the compiler forces the hook to handle. |
| `render` is total (does not throw on empty list) | **Good — validator (property test)**: the Hedgehog "render is pure and total" property asserts `render(Nil) == ""` and never throws. | totality over an arbitrary `List[MemoryHit]` cannot be encoded in the type; a property test pins it. |
| `groupId` shared across the turn's episodes | **Good — smart constructor**: `postTurn` generates one `groupId` (a UUID or a turn counter string) and passes it to every `Episode` it builds; the Hedgehog "groupId shared" property pins the invariant. | the `Episode.groupId: Option[String]` field allows divergence; the hook's construction logic enforces sharing. |
| New `AgentEvent` variants implement `withPrependedStep` | **Best — unrepresentable**: `withPrependedStep` is an abstract method on the `AgentEvent` trait; a new variant that does not implement it does not compile. | the type system enforces the obligation. |

No invalid state is allowed to reach a "silent mapping" (the forbidden
tier). The one `require` in `MemoryPolicy.apply` is the only runtime
rejection; everything else is type-level or exhaustiveness-level.

## 4. Refined Type Strategy

No Iron/refined library is present (openspec/capability-profile.md). The constrained
values in this change are:

| Value | Constraint | Mechanism |
|-------|-----------|-----------|
| `MemoryPolicy.recallK` | `>= 0` | smart constructor `require` (no Iron) |
| `MemoryPolicy.writeUserInput`, `writeAssistantOutput` | booleans (no constraint needed) | plain `Boolean` |
| `MemoryPolicy.scope` | `Option[TemporalScope]` (no constraint) | plain `Option` |
| `MemoryRecalled.hitCount`, `MemoryWritten.episodes` | `>= 0` (a count) | plain `Int`; non-negativity is a consequence of the producing call (`List.size`, `remember` call count), pinned by properties |

`MemoryPolicy` crosses an API boundary (callers construct it), so it gets
the smart constructor for `recallK`. The event count fields do not cross a
caller-facing construction boundary (the hook emits them; callers only
read them), so plain `Int` plus a property is sufficient.

## 5. IDL Model Layout

**N/A.** This change introduces no Smithy/protobuf operations or structures.
`MemoryPolicy`, `MemoryHook`, and `MemoryAwareRunner` are Scala-only types;
`AgentMemory` (reused) is a Scala trait, not an IDL service. The two new
`AgentEvent` variants are Scala case classes; `AgentEvent` does not derive
`ReadWriter` (verified by grep) and is not serialized.

## 6. Error Strategy

This change introduces NO new error variants. The error algebra is unchanged:

- `MemoryAwareRunner.run` returns `IO[RunResult]`, where `RunResult.Failed`
  carries an existing `AdkError` produced by the underlying `AgentRunner`.
  The decorator does NOT catch, wrap, or swallow errors — it forwards the
  underlying `RunResult.Failed` verbatim and skips `postTurn`.
- `MemoryHook.preTurn`/`postTurn` return `IO[Option[String]]` / `IO[Unit]`.
  If `AgentMemory.recall` or `remember` fails, the `IO` short-circuits and
  the failure propagates as a `Throwable` to `MemoryAwareRunner.run`, which
  lets it surface as `RunResult.Failed` via the underlying runner's
  `handleErrorWith` (the decorator does not add its own handler). This is a
  deliberate decision: a memory backend failure should fail the run loudly,
  not silently degrade to no-memory behavior. The spec calls this out as a
  risk (see Risks below) and the adversarial review must confirm there is no
  silent fallback.
- No `null`, no `throw`, no `var` (WartRemover-enforced). No catch-all
  default branch returning a valid domain value.

## 7. Compatibility Story (Ring 4)

**N/A.** This change touches no serialization, persisted event/snapshot
format, wire format, or checkpoint state:

- `AgentRunner`'s `CheckpointState` upickle serialization is unchanged —
  `MemoryAwareRunner.resume` delegates to `AgentRunner.resume`, which owns
  the checkpoint read/write. The decorator does not serialize anything.
- `AgentEvent` does not derive `ReadWriter` (verified by grep), so adding
  two variants has no serialization compatibility impact.
- No Smithy schemas change.

Ring 4 is therefore unchecked (per the proposal's verification strategy).
If a future change makes `AgentEvent` serializable, that change owns the
compatibility story for the then-existing variants including these two.

## 8. Verification Map

| Module / Package | Rings applying | Why |
|------------------|----------------|-----|
| `org.adk4s.orchestration.memory` (NEW — `MemoryPolicy`, `MemoryHook`, `MemoryAwareRunner`) | 0, 1, 2, 3, 5, 8 | 0 compile (exhaustiveness escalation on the `RunResult` match); 1 lint (WartRemover `Var`/`Null`/`Throw`, scalafmt); 2 arch (the new package's import boundaries — advisory, import-audited); 3 Hedgehog properties + `TestControl` scenarios; 5 Stryker4s on the three new files; 8 adversarial spec-compliance review (fresh-context, before 5). |
| `org.adk4s.core.interrupt` (EXTENDED — two new `AgentEvent` variants) | 0, 1, 2, 3, 5, 8 | 0 compile (the new variants must implement `withPrependedStep`; every existing match over `AgentEvent` must handle them or have a catch-all — exhaustiveness escalation); 1 lint; 2 arch (`adk4s-core.interrupt` still must not import `adk4s-orchestration`); 3 Hedgehog properties on event emission; 5 Stryker4s on `AgentEvent.scala`'s new `withPrependedStep` impls; 8 adversarial. |
| `build.sbt` (new `adk4s-orchestration → adk4s-memory-api` edge) | 0 | compile only — no behavior. |
| `openspec/concepts/memory-aware-runner.md` (NEW) | — (registry-check) | the concept file is verified by `registry-check.sh` (symbols file-scoped, actions cited by specs match the actions block). |
| `openspec/concepts/agent-event-stream.md` (UPDATED) | — (registry-check) | the updated Implementation map is verified by `registry-check.sh`. |

Rings NOT applying to any module in this change: 4 (no serialization/wire),
6 (no PureScala module — the hook is effectful `IO` wiring; the pure
`render` is property-tested instead), 7 (no TLA+/Apalache), 9 (no
telemetry stack).

## Risks and Mitigations (design-level)

- **Memory-backend failure surfaces as `RunResult.Failed`.** A `recall` or
  `remember` `IO` failure propagates and fails the run. Mitigation: this is
  deliberate (loud failure over silent degradation); the adversarial review
  must confirm the decorator adds NO silent fallback to no-memory behavior
  on a memory error. If a caller wants graceful degradation, they construct
  a `MemoryAwareRunner` with `memory = None` — the opt-in gate is the
  intended escape hatch, not an automatic fallback.
- **Context injection shifts the LLM's interpretation.** Prepending a
  separate `UserMessage` with the recalled block could change the model's
  behavior vs. appending to the existing user message. Mitigation: the
  default `render` produces a clearly labeled "Relevant memory:" block; the
  injection strategy (prepend a separate `UserMessage`) is part of the
  hook spec's typed contract and validated by a scenario test asserting the
  message-list shape. A future change could make the strategy configurable
  without breaking the contract.
- **Cross-spec coupling: the events spec's emission points live in the
  hook's decorator file.** `MemoryAwareRunner` emits `MemoryRecalled`/
  `MemoryWritten`, so the events spec's behavior is implemented inside the
  hook spec's primary file. Mitigation: implementation-order.md sequences
  the hook spec FIRST (it creates `MemoryAwareRunner` without event
  emission) and the events spec SECOND (it adds the two `emit` calls and
  the two `AgentEvent` variants). The two specs are independently
  verifiable — the hook spec's `runWithEvents` scenario asserts event-stream
  identity WITHOUT the memory events; the events spec adds them.

## Reference to existing patterns

- The decorator-over-runner pattern mirrors how `AgentTool` wraps an `Agent`
  as an `InvokableTool` — a pure wrapper that composes from the outside
  without modifying the wrapped type's source. (See
  `openspec/concepts/agent-tool.md`.)
- The `Option[AgentMemory[IO]]` no-op-when-absent pattern mirrors
  `AgentEventEmitter.scoped`'s `Option[RunStep]` handling — the `None` arm
  is a pure no-op, the `Some` arm does the work.
- The `RunResult`-gated write mirrors `AgentRunner.run`'s own
  `handleErrorWith` pattern-matching on `AgentInterruptedException` vs
  `AdkError` vs `Throwable` — the sealed ADT + exhaustiveness escalation is
  the established way to make "forget a case" a compile error in this
  codebase.
