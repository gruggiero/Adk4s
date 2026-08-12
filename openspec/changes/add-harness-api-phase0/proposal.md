# Proposal: `AgentMiddleware[F]` and `HarnessState` — deepagents4s Phase 0 foundation

## Why

deepagents is not an agent runtime; it is a *middleware stack plus an assembly
function*. Every headline feature — planning, filesystem, sub-agents,
summarization, skills, memory, HITL, profiles — is a middleware that can run
effects before/after an agent run, wrap the model call (rewriting request and
observing response), wrap tool execution, and contribute typed state to a
shared harness that flows through the loop, survives checkpoints, and is
selectively visible to sub-agents.

ADK4S already owns the runtime half (`ReactAgent`, `AgentRunner`,
`InterruptSignal`, `AgentEvent`, `CheckpointStore`, workflows4s durability)
and a *tool-level* middleware (`ToolMiddleware = ToolEndpoint => ToolEndpoint`,
a Kleisli endomorphism). What it lacks is the *agent-loop-level* middleware
abstraction. Phase 0 adds exactly that, plus the typed state substrate it
requires. Everything in later phases (FilesystemMiddleware,
SubAgentMiddleware, SummarizationMiddleware, …) is a value of the type
designed here.

This change is grounded in `docs/deepagents4s-phase0-agent-middleware-DESIGN.md`
and informed by langchain-ai/deepagents 0.6.12 (`AgentMiddleware`,
`deepagents/graph.py`, `deepagents/middleware/*`), MIT-licensed.

> **Note on design-doc drift.** The design doc predates the `migrate-json-codec`
> change (archived 2026-08-06), which introduced `JsonValue`
> (= `smithy4s.Document`) as ADK4S's internal JSON currency and confined
> `ujson.Value` to the llm4s boundary via Scalafix rules
> (`NoUjsonInCore`/`NoUjsonInOrchestration`/…). Wherever the design doc's
> §3.2–3.4 and §6.3 say `ujson.Obj`/`ujson.Value` or "ujson currency"
> (e.g. `StateCell.rw`, `HarnessState.snapshot`, `CheckpointStateV2.harnessState`),
> this proposal and its derived specs read them as `JsonValue`. The design doc
> itself has not yet been updated.

## What Changes

Phase 0 ships the *foundation* — the middleware trait, the typed state model,
the composition monoid, the loop refactor that consumes them, the
F-polymorphic checkpoint store, and the law testkit. No concrete harness
middlewares (todos, filesystem, sub-agents, …) — those are Phases 1–3.

### Affected Capabilities

- `specs/harness-state/spec.md` — typed, enumerable, serializable
  heterogeneous map (`HarnessState`, `StateCell[A]`, `CellVisibility`, mandatory
  `JsonValue` codec, snapshot/restore with forward-compatible leniency,
  semilattice merge discipline for parallel-shared cells).
- `specs/agent-middleware/spec.md` — effect-polymorphic `AgentMiddleware[F]`
  trait with hook parity to deepagents (`beforeAgent`, `afterAgent`,
  `wrapModelCall`, `wrapToolCall`) plus `tools` and state-aware
  `promptSections(state)` contributions; `ModelRequest`/`ModelResponse`,
  `ToolStep`/`ToolCallCtx`/`ToolCallOut`, `SystemPrompt`/`PromptSection`.
- `specs/middleware-stack/spec.md` — `MiddlewareStack[F]` monoid composition
  (stack-order semantics for each hook), validated construction
  (`StackError` for duplicate cell ids / tool names), and the sub-agent
  boundary operation (`HarnessState.project` / `mergeBack` with three
  visibility levels).
- `specs/harness-agent/spec.md` — `HarnessAgent[F]` loop re-expressing the
  ReAct loop against the stack; `ReactAgent.create` as sugar for the
  empty-stack harness (source-compatible); per-request tool list and prompt
  folding; effect polymorphism at `F = IO` for Phase 0.
- `specs/checkpoint-store-fpoly/spec.md` — `CheckpointStore[F]`
  generalization (today's trait becomes `CheckpointStore[IO]`) and
  `CheckpointStateV2` with full-fidelity messages + `HarnessState.snapshot`,
  including v1-read compatibility.
- `specs/middleware-laws/spec.md` — `AgentMiddlewareLaws` (L0–L11) and
  `SemilatticeLaws` packaged in `adk4s-harness-testkit` in the
  `AgentMemoryLaws` style, with a deterministic `ChatModel` double and
  Hedgehog generators.

### Out of Scope

- No concrete harness middlewares (TodoList, Filesystem, SubAgent,
  Summarization, Skills, Memory, HITL policy, Profiles) — Phases 1–3. Phase 0
  ships only `AgentMiddleware.id` and one trivial reference middleware
  (a run-counter exercising every hook and both visibility paths) for the
  test suite.
- No `createDeepAgent` assembly function or prompt pack (Phase 1).
- No F-generalization of `ToolsNode` or `ChatModel` call sites beyond what
  the loop refactor forces. The *API* is `F`-polymorphic; the Phase 0
  *runtime* instantiates `F = IO`.
- No streaming through `wrapModelCall` (a `Stream`-shaped hook variant is
  deferred until a concrete middleware needs it; none of the deepagents set
  does — summarization acts pre-call).
- No workflows4s-as-checkpoint-substrate migration (deferred; `CheckpointStore[F]`
  is shaped so both substrates can sit behind it).
- No capability-checked cell ownership via Scala 3 capture checking (deferred;
  L5 is a tested property in Phase 0).

## Approach

Two new dependency-light modules in the ADK4S repo (following the
`adk4s-memory-api` / `adk4s-memory-testkit` precedent), plus a conservative
refactor of `adk4s-orchestration`:

- `adk4s-harness-api` (new) — `AgentMiddleware`, `HarnessState`, `StateCell`,
  `MiddlewareStack`, `ModelRequest`/`ModelResponse`, `ToolStep`,
  visibility/merge machinery. Depends on `adk4s-core` (for `InvokableTool`,
  `ToolInput`/`ToolOutput`, `JsonValue`/`JsonValueCodec`), llm4s model types,
  smithy4s-json (`JsonValue` = `smithy4s.Document` is the serialization
  currency; `ujson.Value` is confined to the llm4s boundary by the
  `migrate-json-codec` Scalafix rules).
- `adk4s-harness-testkit` (new) — `AgentMiddlewareLaws`, `SemilatticeLaws`,
  deterministic `ChatModel` double, generators. Depends on
  `adk4s-harness-api`, Hedgehog.
- `adk4s-orchestration` (refactor) — `HarnessAgent` loop; `ReactAgent`
  re-expressed as the empty-stack harness; `CheckpointStore[F]`;
  `CheckpointStateV2`.

The middleware trait sits in a dedicated API module (not `adk4s-core`) to keep
`adk4s-core` free of harness vocabulary and give deepagents4s a minimal
compile-time surface — the same reasoning that produced `adk4s-memory-api`.

The loop refactor is *observationally conservative*: the empty stack must be
provably equivalent to today's `ReactAgentImpl` (law **L0**). `ReactAgent.create`
becomes sugar for a `HarnessAgent` with `MiddlewareStack.empty`, keeping the
existing trait and every current call site (55+ examples, `AgentTool`,
graphStore) source-compatible.

`HarnessState` is a typed heterogeneous map keyed by stable string `CellId`s
(not object identity — rejected because Vault is non-enumerable, breaking
checkpoints, and identity keys break stable ids across process restarts). The
single `asInstanceOf` in `get` is the classic typed-map argument; its safety
rests on construction-time id uniqueness (`MiddlewareStack.validated`), is
local and auditable, and is a named Stainless target. `get` is total (absent
cells read as `initial`), which makes forward-compatible restore trivial and
the lens laws clean.

`promptSections` is **state-aware** (`promptSections(state: HarnessState)`)
and folded per-request from the current `HarnessState`, so recall/memory/
notebook state flows into the prompt through the same ordered, named,
section-by-section, testable assembly as static sections — rather than being
smuggled into `wrapModelCall`. (A separate narrower change
`state-aware-prompt-sections` was stubbed for this slice; this change
subsumes it.)

## Correctness Risk Level

**Risk**: high — introduces a typed heterogeneous map with a single unchecked
cast whose safety rests on a construction-time uniqueness invariant; refactors
the core agent loop (`ReactAgent`) with a conservative-equivalence gate (L0)
across 55+ examples; changes the persisted checkpoint format
(`CheckpointStateV2`) with backward-compat read obligations and a fidelity
defect fix (today's `SerializableCheckpointMessage` flattens `toolCalls` and
tool-call ids away, breaking resume for providers that validate
tool-call/tool-result pairing); introduces merge semantics for parallel
sub-agent state where order-independence requires a join-semilattice
discipline that must be checked per-cell.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan
- [x] Ring 2: Architecture — project-specific layer dependencies (new
  `adk4s-harness-api` sits below `adk4s-orchestration`; `adk4s-core` must stay
  free of harness vocabulary), sealed domain types, effect discipline
- [x] Ring 3: Property-based tests — MANDATORY. Laws L0–L11 are Hedgehog
  properties; L0 is the gatekeeper for merging the refactor at all. **This
  change involves CONCURRENT behavior**: parallel tool calls within one
  iteration merge per-cell via `cell.merge`, and parallel sub-agent
  delegation requires order-independent `mergeBack` (L11 semilattice).
  Concurrency scenarios MUST use the detected deterministic test kit
  (deterministic `ChatModel` double, seed capture, repeat runs) — never
  wall-clock sleeps.
- [x] Ring 4: Wire/persistence compatibility — `CheckpointStateV2` wire format
  with v1-read compatibility test; `HarnessState.snapshot`/`restore`
  round-trip (L7) and restore leniency against unknown/missing fields (L8).
- [x] Ring 5: Mutation testing — Stryker4s on changed production logic
  (`HarnessState`, `MiddlewareStack`, `HarnessAgent` loop, `CheckpointStateV2`
  codec), threshold 90% (pure domain logic in `harness-api`) / 85% (loop +
  checkpoint adapters in `orchestration`). `stryker4s.conf` mutate list
  retargeted per spec.
- [x] Ring 6: Formal verification — the verified-mirror pattern applies:
  `HarnessState` get/set coherence (`get(c)(set(c)(v)(s)) == v` and
  `get(c)(set(d)(v)(s)) == get(c)(s)` for `c.id != d.id`) is a pure kernel
  expressible in PureScala and is explicitly named as a Stainless target in
  the design (§3.3); the semilattice merge laws (L11) are likewise pure. A
  leaf mirror module pinned to the Stainless frontend version, with a
  mandatory bridge property test running shipped code and model on the same
  generated inputs.
- [ ] Ring 7: Model checking — not selected; parallel-merge commutativity is
  covered by Ring 3 property tests (L11) over permutations of children, not
  distributed/event-driven invariants requiring TLA+/Apalache.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY (fresh-context
  reviewer; runs BEFORE Rings 5/6/7 in the apply sequence).
- [ ] Ring 9: Telemetry — not applicable; no telemetry stack detected
  (otel4s NOT PRESENT per capability-profile). The change does not affect API
  operations or event sequences in a telemetry sense; `AgentEvent` emission
  stays in the loop unchanged.

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
| `specs/harness-state/spec.md` | Full | Introduces new ADT (`CellVisibility`, `StateDecodeError`), new types (`StateCell[A]`, `HarnessState`, `CellId`), and serialization (`snapshot`/`restore` over `JsonValue` = `smithy4s.Document`). |
| `specs/agent-middleware/spec.md` | Full | Introduces the `AgentMiddleware[F]` trait (public API), `ModelRequest`/`ModelResponse`/`ToolStep`/`ToolCallCtx`/`ToolCallOut`, `SystemPrompt`/`PromptSection`, `MiddlewareName` opaque type. |
| `specs/middleware-stack/spec.md` | Full | Introduces `MiddlewareStack[F]` (public composition API), `StackError` ADT, and the `project`/`mergeBack` boundary operation. |
| `specs/harness-agent/spec.md` | Full | Public API signature change (`HarnessAgent[F]`), `ReactAgent` refactor (loop re-expression), per-request tool/prompt derivation. |
| `specs/checkpoint-store-fpoly/spec.md` | Full | Persistence/serialization change: `CheckpointStore[F]` generalization + `CheckpointStateV2` wire format + v1-read compatibility. |
| `specs/middleware-laws/spec.md` | Full | New testkit API (`AgentMiddlewareLaws`, `SemilatticeLaws`, deterministic `ChatModel` double) that downstream middleware authors compile against. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` | Reuse as-is; harness tools are `InvokableTool[F]`. |
| `StreamableTool[F[_]]` | trait | `org.adk4s.core.component` | Reuse as-is. |
| `ToolInput` / `ToolOutput` | case class | `org.adk4s.core.tools` | Reuse as-is; `ToolCallCtx`/`ToolCallOut` thread `HarnessState` alongside. |
| `ToolEndpoint` | type alias (`Kleisli[IO, ToolInput, ToolOutput]`) | `org.adk4s.core.tools.ToolTypes` | `ToolStep.passthrough` lifts it; at `F = IO` `Kleisli[IO, ToolInput, ToolOutput]` *is* `ToolEndpoint`, so `ToolMiddleware.logging/timing/retry/validation` compose unchanged. |
| `ToolMiddleware` | type alias (`ToolEndpoint => ToolEndpoint`) | `org.adk4s.core.tools` | Reuse the existing combinator set; lifts for free under `ToolStep.passthrough`. |
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` | `baseModelStep` delegates to `ChatModel.generate`; `wrapModelCall` wraps it. |
| `ReactAgent` | class | `org.adk4s.orchestration.agent` | Refactor target; re-expressed as empty-stack `HarnessAgent`. |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | Owns `CheckpointStore`; `resume` gains `HarnessState.restore`. |
| `CheckpointStore` | trait (`InMemoryCheckpointStore`) | `org.adk4s.orchestration.interrupt` | Generalize to `CheckpointStore[F]`; today's becomes `CheckpointStore[IO]`. |
| `InterruptSignal` | sealed trait (derives ReadWriter: `Simple`/`Stateful`/`Composite`) | `org.adk4s.core.interrupt` | Reuse as-is; HITL middleware raises `InterruptSignal.Stateful` via existing machinery. |
| `AgentEvent` / `AgentEventEmitter` | sealed trait / class | `org.adk4s.core.interrupt` | Reuse as-is; event emission stays in the loop, middlewares observe via wrapping. |
| `RunResult` | sealed trait (`Completed`/`Interrupted`/`Failed`) | `org.adk4s.orchestration.agent` | Reuse; `HarnessAgent.generate` returns a `HarnessResult` carrying state. |
| `CheckpointState` | case class (private[agent]) | `org.adk4s.orchestration.agent` | Replaced by `CheckpointStateV2` (full-fidelity messages + `harnessState`). |
| `AdkError` / `AgentInterruptedException` | sealed trait / variant | `org.adk4s.core.error` | Reuse; harness errors extend the sealed hierarchy (`StateDecodeError`). |
| `CompletionOptions`, `Message`, `Completion`, `StreamedChunk` | llm4s types | `org.llm4s.llmconnect.model` | Reuse as-is; `ModelRequest` carries `CompletionOptions` and `List[Message]`. |
| `AgentMemory[F[_]]` / `AgentMemoryLaws` | trait / testkit | `org.adk4s.memory` | Precedent for the capability-module + testkit pattern that `adk4s-harness-api` / `adk4s-harness-testkit` follows. |
| upickle/ujson `ReadWriter` | typeclass | `upickle.default` | Codec typeclass for `StateCell[A]` (mandatory at declaration). NOTE: since `migrate-json-codec`, the value currency is `JsonValue` (= `smithy4s.Document`), not `ujson.Value` — `InterruptSignal.Stateful.state` is now `JsonValue` (bridged via `JsonValueReadWriter`), and `HarnessState.snapshot`/`restore` + `CheckpointStateV2.harnessState` use `JsonValue`. `CheckpointState` (legacy, replaced by V2) still `derives ReadWriter`. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `AgentMiddleware[F[_]]` | trait | Effect-polymorphic agent-loop middleware with four hooks + `tools` + state-aware `promptSections`. |
| `HarnessState` | final class | Typed, enumerable, serializable heterogeneous map; total `get` (absent reads `initial`). |
| `StateCell[A]` | final class | Unit of state declaration/ownership/typing/serialization/visibility/merging; equality by `CellId`. |
| `CellVisibility` | enum | `Private` / `Inherited` / `Shared` — three sub-agent visibility levels. |
| `StateCell.CellId` | opaque type (`String`) | Stable `"owner/name"` key; uniqueness enforced at stack construction. |
| `MiddlewareName` | opaque type (`String`) | Middleware identity for cell-id namespacing and error attribution. |
| `ModelRequest[F]` / `ModelResponse` | case class | Per-request model call payload (system prompt, messages, tools, options, state) and response (completion, state). |
| `ModelStep[F]` | type alias (`Kleisli[F, ModelRequest[F], ModelResponse]`) | The base step `wrapModelCall` wraps. |
| `ToolCallCtx` / `ToolCallOut` | case class | State-threading tool execution payload/response. |
| `ToolStep[F]` | type alias (`Kleisli[F, ToolCallCtx, ToolCallOut]`) | The base step `wrapToolCall` wraps; `passthrough` lifts state-oblivious endpoints. |
| `SystemPrompt` / `PromptSection` | case class | Composed, named, ordered prompt assembly (base ++ sections in stack order). |
| `MiddlewareStack[F]` | final case class | Monoid of middlewares; stack-order semantics per hook; `allCells`/`allTools`/`allSections(state)`. |
| `StackError` | enum | `DuplicateCellId` / `DuplicateToolName` — construction-time validation failures. |
| `StateDecodeError` | sealed trait / `AdkError` variant | Hard failure for a cell that fails to decode on restore (corrupted checkpoint beats silent data loss). |
| `HarnessAgent[F[_]]` | final class | ReAct loop re-expressed against the stack; `generate`/`stream`. |
| `HarnessResult` | sealed trait / case class | Loop outcome carrying final `AssistantMessage`, messages, and `HarnessState`. |
| `CheckpointStore[F[_]]` | trait (generalized) | F-polymorphic checkpoint store; `inMemory[F[_]: Sync]` factory. |
| `CheckpointStateV2` | case class (derives ReadWriter) | Full-fidelity messages + `harnessState: JsonValue` + version field for v1-read compat. |
| `AgentMiddlewareLaws` | testkit class | L0–L11 Hedgehog properties in the `AgentMemoryLaws` style. |
| `SemilatticeLaws` | testkit property | Commutativity/associativity/idempotence of `cell.merge` for parallel-shared cells. |

## Risks and Mitigations

- **Loop refactor regression.** The `ReactAgent` → `HarnessAgent` refactor
  touches the core path used by 55+ examples, `AgentTool`, and graphStore.
  Mitigation: L0 (conservative equivalence on the deterministic `ChatModel`
  double across generated conversations, tool behaviors including interrupts,
  and step budgets) is the gatekeeper — the refactor does not merge until L0
  is green, and all existing examples must run unmodified through the
  empty-stack path.
- **Unchecked cast in `HarnessState.get`.** Safety rests on construction-time
  `CellId` uniqueness. Mitigation: `MiddlewareStack.validated` makes duplicate
  ids a construction-time `Either` error (discharging the cast argument); the
  get/set coherence property is a named Stainless target (Ring 6 verified
  mirror); WartRemover's `AsInstanceOf` wart is currently excluded repo-wide
  but this single auditable cast is documented in the design and re-enabled as
  a scoped exclusion.
- **Checkpoint fidelity defect + format change.** `CheckpointStateV2` fixes
  the lossy `SerializableCheckpointMessage` and adds `harnessState`.
  Mitigation: Ring 4 v1-read compatibility test; version field; restore is
  lenient by construction (unknown ids ignored, missing cells default to
  `initial` — L8).
- **Parallel merge order-sensitivity.** `mergeBack` over concurrently
  produced child states must not depend on completion order. Mitigation: the
  semilattice requirement is stated now so Phase 1 inherits it; `SemilatticeLaws`
  (L11) checks commutativity/associativity/idempotence and `mergeBack`
  order-independence over permutations of children directly.
- **`promptSections` state-awareness blast radius.** Making `promptSections`
  take `state` touches the composition rule, the loop fold site, and the L4/L6
  law wording. Mitigation: this is Phase 0 (the cheapest moment to fix it);
  static-section middlewares simply ignore the parameter (`_ =>`).
- **Effect-polymorphism vs IO-fixed runtime.** The API is `F`-generic but
  Phase 0 ships the loop at `F = IO` because `ToolsNode`/`AgentRunner` are
  IO-fixed. Mitigation: nothing in the API forecloses the later core-wide
  F-generalization; `CheckpointStore[F]` lands now so Phase 1+ never binds to
  the IO-fixed version.
