# Spec: Harness Agent

<!-- This is a DELTA spec for the `add-harness-api-phase0` change. It covers
     the `HarnessAgent[F[_]]` loop — the ReAct loop re-expressed against the
     middleware stack — and the `ReactAgent.create` sugar that keeps every
     existing call site source-compatible. Use ## ADDED Requirements for new
     content. Each spec is implemented and verified INDEPENDENTLY through the
     full ring pipeline.

     ALTITUDE: requirements and scenarios use behavioral vocabulary only
     (Concept/action references, domain terms, test vectors). Code identifiers
     — class names, error variants, build commands — live in Implementation
     Anchors and the Concepts Introduced table. The full typed contract (Scala
     signatures) lives in the design doc §6.1.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by Given/When/Then
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words without a concrete definition next to them
     - ADVERSARIAL RULE: every requirement containing "only", "never", or
       "must not" needs at least one scenario whose INPUT the requirement forbids
     - CONCURRENCY RULE: requirements about concurrent execution state a
       DETERMINISTIC observable, tested with cats-effect TestControl. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| ReactAgent | The loop being refactored; `HarnessAgent` with an empty stack MUST be observationally equivalent to the current ReactAgent (L0 gatekeeper) | [react-agent.md](../../../concepts/react-agent.md) |
| AgentRunner | The interrupt/resume lifecycle owner; `resume` gains harness-state restore; `CheckpointStateV2` replaces the persisted state | [agent-runner.md](../../../concepts/agent-runner.md) |
| AgentEventStream | Event emission (`ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `MessageOutput`) stays in the loop; middlewares observe via wrapping, not by replacing the observability channel | [agent-event-stream.md](../../../concepts/agent-event-stream.md) |
| InterruptSignal | Raised by HITL middlewares via `wrapToolCall`; on interrupt, the loop snapshots state without running `afterAgent` | [interrupt-signal.md](../../../concepts/interrupt-signal.md) |
| ChatModel | The model call delegated to by the base model step; `wrapModelCall` wraps it per-request | [chat-model.md](../../../concepts/chat-model.md) |
| Tool | Tools from the stack and base tools are executed through `wrapToolCall`; parallel tool-call state merges per-cell | [tool.md](../../../concepts/tool.md) |
| agent-middleware (NEW — created by agent-middleware spec) | The middleware stack that `HarnessAgent` runs against | `openspec/concepts/agent-middleware.md` (created at apply Step 12) |
| harness-state (NEW — created by harness-state spec) | The state substrate that flows through the loop, survives checkpoints, and is snapshotted on interrupt | `openspec/concepts/harness-state.md` (created at apply Step 12) |

Updating the `react-agent.md` and `agent-runner.md` concept files (state gains harness-state field; actions gain per-request tool/prompt folding) is PART OF implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package | Role here |
|---------|------|---------|-----------|
| `ReactAgent` | class | `org.adk4s.orchestration.agent` | refactor target — re-expressed as the empty-stack harness; `ReactAgent.create` becomes sugar |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | owns `CheckpointStore`; `resume` gains `HarnessState.restore`; consumes `HarnessResult` |
| `RunResult` | sealed trait (`Completed`/`Interrupted`/`Failed`) | `org.adk4s.orchestration.agent` | `HarnessResult` carries the same outcome shape plus final `HarnessState` |
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` | `baseModelStep` delegates to `ChatModel.generate`; `wrapModelCall` wraps it |
| `AgentEvent` | sealed trait | `org.adk4s.core.interrupt` | event variants emitted by the loop (`ToolCallRequested`/`Completed`, `IterationCompleted`, `MessageOutput`) stay in the loop |
| `AgentEventEmitter` | class | `org.adk4s.core.interrupt` | the loop's observability channel; middlewares observe via wrapping, never replace it |
| `InterruptSignal` | sealed trait (`Simple`/`Stateful`/`Composite`) | `org.adk4s.core.interrupt` | raised by HITL middlewares via `wrapToolCall`; the loop catches it and snapshots state |
| `AgentInterruptedException` | `AdkError` variant | `org.adk4s.core.error` | the exception the loop catches to enter the interrupt-snapshot path |
| `Completion` | llm4s type | `org.llm4s.llmconnect.model` | the model response the base step produces |
| `Message` | llm4s type | `org.llm4s.llmconnect.model` | conversation messages carried in `ModelRequest` and the loop history |
| `StreamedChunk` | llm4s type | `org.llm4s.llmconnect.model` | the element type of `HarnessAgent.stream` |
| `AgentMiddleware[F[_]]` | trait | `org.adk4s.harness` | (from agent-middleware spec) the four-hook middleware the stack composes |
| `MiddlewareStack[F]` | final case class | `org.adk4s.harness` | (from middleware-stack spec) the monoid the loop consumes; `empty` is the refactor identity |
| `HarnessState` | final class | `org.adk4s.harness` | (from harness-state spec) the typed state substrate threaded through the loop |
| `ModelRequest[F]` / `ModelResponse` | case class | `org.adk4s.harness` | (from agent-middleware spec) per-request model-call payload and response |
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` | base + stack-contributed tools executed via `wrapToolCall` |
| `ToolInput` / `ToolOutput` | case class | `org.adk4s.core.tools` | carried in `ToolCallCtx`/`ToolCallOut` alongside `HarnessState` |
| `AdkError` | sealed trait | `org.adk4s.core.error` | error hierarchy; loop failures surface as `HarnessResult.Failed` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `HarnessAgent[F[_]]` | final class | ReAct loop re-expressed against the stack; `generate(messages, maxSteps): F[HarnessResult]` and `stream(messages, maxSteps): fs2.Stream[F, StreamedChunk]`. Parameterized `F[_]: Async`. |
| `HarnessResult` | sealed trait / case class | Loop outcome carrying the final `AssistantMessage`, the full message list, and the final `HarnessState`. Variants mirror `RunResult` (`Completed`/`Interrupted`/`Failed`) with state attached. |
| `HarnessAgent.Config[F]` | case class | Construction config: `name`, `description`, `model: ChatModel[F]`, `stack: MiddlewareStack[F]`, `baseTools: List[InvokableTool[F]]`, `basePrompt: Option[SystemPrompt]`, `maxSteps: Int`, `emitter: Option[AgentEventEmitter]`. |
| `IOHarnessAgent` | type alias | `HarnessAgent[IO]` — the Phase 0 runtime instantiation, because `ToolsNode` and `AgentRunner` are IO-fixed. |

> **Commitment.** Introducing `HarnessAgent[F[_]]` and `HarnessResult` is a
> binding commitment: the apply phase MUST create these types in
> `org.adk4s.orchestration.agent` and update the concept inventory at apply
> Step 12. `ReactAgent` is NOT removed — it is re-expressed as sugar (see
> Requirement: ReactAgent.create is source-compatible sugar).

## ADDED Requirements

### Requirement: Empty-stack conservative equivalence

The harness constructed with the empty middleware stack SHALL be
observationally equivalent to the current `ReactAgentImpl` on a deterministic
model double, across generated conversations, tool behaviors (including
interrupts), and step budgets. This is the gatekeeper: the refactor SHALL NOT
merge until this equivalence is green.

**Given** a `HarnessAgent` with `MiddlewareStack.empty` and a `ReactAgentImpl`
built from the same name, description, model, base tools, base prompt, max
steps, and emitter
**When** both are driven by the testkit's deterministic `ChatModel` double over
the same generated message list and step budget
**Then** both produce an equal final assistant message, an equal final message
list, and equal request traces observed at the base model step — for every
generated conversation and tool-behavior branch (no-tool-call termination,
single-tool-call, multi-tool-call, and an interrupt raised mid-iteration).

**Rationale**: the refactor touches the core path used by 55+ examples,
`AgentTool`, and graphStore. The empty stack must be provably equivalent to
today's agent, not merely promised. Observational equivalence `p ≍ q` means:
driven by the deterministic double and a fixed tool set and input, both produce
equal final `AssistantMessage`, equal final `HarnessState.snapshot`, and equal
request traces at the base step.

#### Scenario: No-tool-call termination

**Given** a generated conversation where the model's first response has no tool
calls and `maxSteps = 5`
**When** both the empty-stack harness and the current agent run
**Then** both return after one model call with equal final assistant messages
and equal single-element request traces.

#### Scenario: Multi-tool-call then terminate

**Given** a generated conversation where the model returns two tool calls, then
a final response with no tool calls, and `maxSteps = 5`
**When** both run
**Then** both produce equal final assistant messages, equal message lists
(assistant + tool results + final assistant), and equal two-element request
traces (the second request carries both tool results).

#### Scenario: Interrupt raised mid-iteration (adversarial edge)

**Given** a generated conversation where a tool raises an interrupt during the
first iteration and `maxSteps = 5`
**When** both run
**Then** both produce an interrupted outcome carrying the same interrupt signal,
the same partial message list up to the interrupt, and neither runs the
post-termination hook (the current agent has none; the empty-stack harness's
`afterAgent` is the identity).

#### Scenario: Step budget exhausted

**Given** a generated conversation where the model always returns a tool call
(never terminates) and `maxSteps = 3`
**When** both run
**Then** both terminate after exactly 3 iterations with a max-steps-exceeded
outcome and equal partial message lists.

### Requirement: Per-request tool list derivation

The loop SHALL derive the tool list for each model request as
`stack.allTools ++ baseTools` at request-build time, NOT baked once at agent
construction. The `CompletionOptions` SHALL be derived from the request's tool
list per request, fixing the current static baking.

**Given** a `HarnessAgent` whose stack contributes a tool `T_stack` and whose
base tools include `T_base`, and a middleware that adds a tool to its
`tools` contribution only after `beforeAgent` runs
**When** the loop builds the first `ModelRequest`
**Then** the request's `tools` field equals `List(T_stack, T_base)` (stack order
then base order), and if the middleware's contribution changed after
`beforeAgent`, the request reflects the post-`beforeAgent` contribution — not a
list captured at construction.

**Rationale**: today `ReactAgentImpl` bakes `CompletionOptions` once from static
config. Per-request derivation is what lets a middleware contribute tools
dynamically (the `createWithToolProvider` pattern, generalized) and is required
for the empty-stack equivalence to hold when tool sets vary.

#### Scenario: Stack tool appears before base tool

**Given** a stack contributing `T_stack` and base tools `List(T_base)`
**When** the loop builds a request
**Then** `request.tools == List(T_stack, T_base)`.

#### Scenario: Tool added by middleware after beforeAgent

**Given** a middleware whose `tools` list is empty at construction but returns
`List(T_late)` from a mutable source read at request-build time, and
`beforeAgent` flips that source on
**When** the loop builds the first request
**Then** `request.tools` contains `T_late` — the tool list is not a
construction-time snapshot.

#### Scenario: Empty stack yields only base tools (adversarial)

**Given** `MiddlewareStack.empty` and base tools `List(T_base)`
**When** the loop builds a request
**Then** `request.tools == List(T_base)` and no stack-contributed tool appears —
the empty stack contributes no tools, matching the current agent.

### Requirement: Per-request prompt folding from current state

The loop SHALL fold `promptSections(state)` per request from the current
`HarnessState`, producing `SystemPrompt(basePrompt, stack.allSections(state))`
at request-build time — NOT a one-time fold at stack construction. When a
middleware's section text depends on state mutated during the run, each request
SHALL reflect the state as of that request.

**Given** a middleware whose `promptSections(state)` reads a `Private` cell
that `beforeAgent` populates with recalled content, and a second middleware
whose section text is static
**When** the loop builds the first `ModelRequest` (after `beforeAgent`)
**Then** the rendered system prompt contains the recalled content from the
cell, in stack order after the base prompt and before/after the static section
per stack position — the section text is the post-`beforeAgent` state, not the
pre-`beforeAgent` (construction-time) state.

**Rationale**: an earlier design had a no-argument `promptSections` fixed at
construction. That breaks any middleware whose prompt contribution is state
(recall, memory, notebook). Folding per request from current state makes the
auditable, ordered, named section assembly hold for every middleware. (Design
§4.4.)

#### Scenario: Recalled content appears in the first request's prompt

**Given** a middleware `M_recall` with a `Private` cell `c`, where
`beforeAgent` sets `c` to `"AGENTS.md content"`, and
`promptSections(state)` returns `List(PromptSection("skills", state.get(c)))`
**When** the loop builds the first request
**Then** the rendered system prompt contains the substring
`"AGENTS.md content"` inside a section named `"skills"`.

#### Scenario: State mutation between iterations changes the prompt

**Given** a middleware whose `promptSections(state)` reads a cell that a tool
updates mid-run, and a two-iteration run
**When** the loop builds the second request
**Then** the second request's system prompt reflects the post-tool-update cell
value, which differs from the first request's prompt — proving the fold is
per-request, not construction-time.

#### Scenario: Empty stack yields only the base prompt (adversarial)

**Given** `MiddlewareStack.empty` and `basePrompt = Some("base")`
**When** the loop builds a request
**Then** the rendered system prompt equals `"base"` with no appended sections —
the empty stack contributes no sections, matching the current agent.

### Requirement: Loop orchestration order

The loop SHALL execute in the order: (1) initialize state from
`HarnessState.initial(stack.allCells)` or `restore` on resume; (2) run
`stack.beforeAgent(state)` once; (3) iterate — build `ModelRequest`, run
`stack.wrapModelCall(baseModelStep)`, execute each returned tool call through
`stack.wrapToolCall(baseToolStep)`, merge state, append messages, recurse until
no tool calls or `maxSteps`; (4) run `stack.afterAgent(state)` on normal
termination. `beforeAgent` SHALL run before the first model call; `afterAgent`
SHALL run only on normal termination.

**Given** a `HarnessAgent` with a stack of `[M1, M2]` and a two-iteration run
that terminates normally
**When** `generate` is called
**Then** the observed hook trace is: `M1.beforeAgent → M2.beforeAgent →
(M1.wrapModelCall ∘ M2.wrapModelCall)(base) for request 1 → tool executions via
(M1.wrapToolCall ∘ M2.wrapToolCall)(base) → (wrap model call for request 2) →
M2.afterAgent → M1.afterAgent` — `beforeAgent` in stack order, `afterAgent` in
reverse (teardown mirrors setup), wraps with M1 outermost.

**Rationale**: the stack-order semantics (design §5.1) make `MiddlewareStack` a
monoid whose structure is observationally real. Pinning the order observationally
prevents future loop changes from silently reordering hooks.

#### Scenario: Normal termination runs afterAgent in reverse order

**Given** a stack `[M1, M2]` where both record their `afterAgent` invocation
into a trace list
**When** a run terminates normally (no tool calls on the final response)
**Then** the trace list is `List("M2.afterAgent", "M1.afterAgent")` — reverse
stack order.

#### Scenario: beforeAgent runs exactly once

**Given** a stack `[M1]` with a counting `beforeAgent`
**When** a three-iteration run completes normally
**Then** `beforeAgent` was invoked exactly once, before any model call — not
once per iteration.

#### Scenario: maxSteps exhausted still runs afterAgent

**Given** a run where the model always returns a tool call and `maxSteps = 2`
**When** the loop terminates by step-budget exhaustion
**Then** `afterAgent` runs once on the exhausted state — step-budget exhaustion
is normal termination, not an interrupt.

### Requirement: Event emission stays in the loop

The loop SHALL emit `ToolCallRequested`, `ToolCallCompleted`,
`IterationCompleted`, and `MessageOutput` events exactly as the current
`ReactAgentImpl` does. Middlewares SHALL observe these via wrapping
(`wrapModelCall`/`wrapToolCall`), and MUST NOT replace, drop, or reorder the
loop's event channel. The event sequence from the empty-stack harness SHALL
equal the current agent's event sequence for the same inputs.

**Given** a `HarnessAgent` with `MiddlewareStack.empty` and an `AgentEventEmitter`,
and the current agent with the same emitter config
**When** both run over the same generated conversation
**Then** the emitted event sequences are equal element-wise (same variants, same
order, same payloads up to run-path scoping).

**Rationale**: events are the observability channel that consumers (UIs,
`AgentTool`, graphStore) depend on. Moving event emission into middlewares
would break every consumer and make observability optional-by-accident. The
loop owns it; middlewares wrap.

#### Scenario: Tool-call events emitted in order

**Given** a one-iteration run where the model returns one tool call
**When** the loop runs with an emitter
**Then** the emitter records `ToolCallRequested` then `ToolCallCompleted` then
`IterationCompleted` (or `MessageOutput` on termination) — the same order the
current agent produces.

#### Scenario: Middleware cannot suppress a ToolCallRequested event (adversarial)

**Given** a middleware whose `wrapToolCall` returns without calling `next` (it
short-circuits to a synthetic output) and an emitter recording events
**When** the loop runs
**Then** `ToolCallRequested` was still emitted by the loop before the wrap was
invoked — the loop emits the request event before delegating to the wrap, so a
short-circuiting middleware cannot suppress it. (`ToolCallCompleted` reflects
the synthetic output the middleware returned.)

### Requirement: Interrupt snapshots state without afterAgent

On `AgentInterruptedException`, the loop SHALL snapshot the current
`HarnessState` (via `HarnessState.snapshot`) and the partial message list, and
SHALL NOT run `stack.afterAgent`. `afterAgent` SHALL run only when the resumed
run eventually terminates normally. The interrupted outcome SHALL carry the
snapshot, the interrupt signal, and the partial messages.

**Given** a `HarnessAgent` with a stack `[M1]` whose `afterAgent` increments a
counter, and a tool that raises `AgentInterruptedException` mid-iteration
**When** `generate` runs and catches the interrupt
**Then** the returned outcome is interrupted, carrying the interrupt signal and
the partial message list up to the interrupt; `HarnessState.snapshot` of the
carried state equals the state at the interrupt point; and `M1.afterAgent` was
NOT invoked (the counter is unchanged).

**Rationale**: `afterAgent` is teardown for a normally-completed run. An
interrupted run is not complete — its `afterAgent` must run when the resumed
run terminates, not twice (once on interrupt, once on resume). Running it on
interrupt would double-count (e.g. a GraphStore `remember` in `afterAgent`
would fire on partial output).

#### Scenario: Interrupt mid-iteration snapshots partial state

**Given** a middleware with a `Shared` cell set to `v1` before the interrupting
tool call, and a tool that raises `AgentInterruptedException`
**When** the loop catches the interrupt
**Then** the interrupted outcome's state snapshot decodes to `v1` for that cell,
and `afterAgent` was not called.

#### Scenario: Resumed run runs afterAgent once

**Given** an interrupted run whose checkpoint is resumed (via `AgentRunner.resume`
which calls `HarnessState.restore` then re-enters the loop), and the resumed run
terminates normally
**When** the resumed run completes
**Then** `afterAgent` runs exactly once — on the resumed run's final state, not
on the interrupted state.

#### Scenario: afterAgent never runs on interrupt (adversarial)

**Given** a stack `[M1, M2]` where both `afterAgent` hooks increment a shared
counter, and a tool that raises `AgentInterruptedException`
**When** the loop catches the interrupt
**Then** the counter is zero — neither `M2.afterAgent` nor `M1.afterAgent` ran.
The interrupt path MUST NOT invoke any `afterAgent` hook.

### Requirement: Parallel tool-call state merge is order-independent

When the model returns multiple tool calls in one iteration and the loop executes them in parallel, each tool's state updates SHALL merge per-cell via `cell.merge`, and the merged state SHALL be independent of tool completion order for any `Shared` cell whose `merge` is a join semilattice (commutative, associative, idempotent).

The loop SHALL use the deterministic test kit (`TestControl`, seeded model double) for this scenario — never wall-clock sleeps.

**Given** an iteration with two tool calls `T_a`, `T_b` that each write a
`Shared` cell `c` with a semilattice `merge` (e.g. set-union), and a model double
that returns both calls in one response
**When** the loop executes them in parallel and merges their resulting states
**Then** the merged value of `c` equals `merge(c_before, merge(T_a.write,
T_b.write))` which equals `merge(c_before, merge(T_b.write, T_a.write))` —
order-independent — and the final `HarnessState.snapshot` is identical across
repeated runs under `TestControl`.

**Rationale**: deepagents runs `task` calls in parallel; LangGraph resolves
concurrent writes with channel reducers that are order-sensitive unless the
author picks a commutative one. ADK4S requires the semilattice discipline for
parallel-shared cells (design §3.5, L11), so the loop's per-iteration merge is
order-independent by construction for compliant cells. This is a CONCURRENCY
requirement: the observable is deterministic.

#### Scenario: Two parallel tools writing a union-merge cell

**Given** cell `c: StateCell[Set[String]]` with `merge = _ union _`, `c` starts
as `Set("x")`, `T_a` writes `Set("a")`, `T_b` writes `Set("b")`
**When** the loop runs the iteration under `TestControl`
**Then** the merged `c` is `Set("x", "a", "b")` regardless of which tool
completes first, and repeated runs produce byte-identical snapshots.

#### Scenario: Non-semilattice merge is order-sensitive (honest edge)

**Given** cell `c` with a last-write-wins `merge` (NOT a semilattice) and two
parallel tools writing different values
**When** the loop runs the iteration
**Then** the merged `c` reflects whichever tool's state the fold visited last —
this is documented order-sensitivity, NOT a bug, and the loop does not
guarantee determinism for non-semilattice parallel-shared cells. (The
semilattice discipline is the middleware author's obligation; the loop
preserves it when held.)

#### Scenario: Sequential execution (parallelism = 1) is trivially deterministic

**Given** the same two tools and union-merge cell, but the loop configured for
sequential tool execution
**When** the loop runs
**Then** the merged `c` is `Set("x", "a", "b")` — sequential execution is a
degenerate case of the parallel merge.

### Requirement: ReactAgent.create is source-compatible sugar

`ReactAgent.create(...)` SHALL be re-expressed as sugar that constructs a
`HarnessAgent` with `MiddlewareStack.empty`, preserving the existing trait and
every current call site (55+ examples, `AgentTool`, graphStore) source-
compatible. The return type of `ReactAgent.create` SHALL remain assignable
wherever the current `ReactAgent` is expected.

**Given** an existing call site `ReactAgent.create(name, description, model,
tools, systemPrompt, maxSteps, emitter)` compiled against the current codebase
**When** the call site is recompiled against the refactored codebase without
source edits
**Then** it compiles unchanged and behaves identically (per the empty-stack
equivalence requirement).

**Rationale**: the refactor is observationally conservative. Source
compatibility means the 55+ examples, `AgentTool.fromReactAgent`, and graphStore
integrations run unmodified through the empty-stack path — the refactor is
verifiable by L0, not by editing every call site.

#### Scenario: Existing example compiles unchanged

**Given** an existing example that calls the agent factory with the current signature
**When** the example is compiled after the refactor
**Then** compilation succeeds with no source edits to the example.

#### Scenario: AgentTool.fromReactAgent accepts the refactored agent

**Given** `AgentTool.fromReactAgent` called with a `ReactAgent` produced by the
refactored `create`
**When** the agent tool is invoked
**Then** it delegates to the underlying harness and produces the same
interrupt/resume behavior as before the refactor.

#### Scenario: A caller passing a non-empty stack MUST use HarnessAgent directly (adversarial)

**Given** a caller who wants middleware behavior
**When** they attempt to pass a `MiddlewareStack` to `ReactAgent.create`
**Then** this does not compile — `ReactAgent.create`'s signature does not
accept a stack; the caller MUST construct a `HarnessAgent` directly. The sugar
exposes ONLY the empty-stack path; the stack-bearing path is the new API.

### Requirement: Effect polymorphism with IO for Phase 0

The `HarnessAgent` API SHALL be `F[_]: Async`-generic, consistent with
`ChatModel[F]`, `InvokableTool[F]`, and `AgentMemory[F]`. Phase 0 SHALL ship
the loop instantiated at `F = IO` only (because `ToolsNode` and `AgentRunner`
are IO-fixed), via the `IOHarnessAgent` alias. Nothing in the API SHALL
foreclose the later core-wide F-generalization.

**Given** a `HarnessAgent[F]` declared with `F[_]: Async`
**When** a caller constructs `HarnessAgent[IO]` with an `IO`-bound model and
`IO`-bound tools
**Then** `generate` returns `IO[HarnessResult]` and `stream` returns
`fs2.Stream[IO, StreamedChunk]`, and the loop executes under `IO` runtime.

**Rationale**: the API is F-polymorphic so Phase 1+ code never binds to the
IO-fixed version. Phase 0 ships IO only because the underlying `ToolsNode` and
`AgentRunner` are IO-fixed; the `Async[F]` bound is the minimal constraint the
loop needs (concurrency, state via `Ref`).

#### Scenario: IO instantiation compiles and runs

**Given** an `IOHarnessAgent` constructed with an `IO`-bound `ChatModel`
**When** `generate(messages, maxSteps)` is called and `unsafeRunSync`'d
**Then** it yields a `HarnessResult` — the Phase 0 runtime path.

#### Scenario: Non-IO F is not instantiated in Phase 0 (honest edge)

**Given** a caller who attempts to construct `HarnessAgent[SomeOtherF]` in
Phase 0
**When** compilation runs
**Then** it compiles only if `SomeOtherF` has an `Async` instance AND the
model/tools/store are `SomeOtherF`-bound; in Phase 0 the testkit ships only
`IO`-bound doubles, so a non-IO instantiation is API-legal but unsupported by
the shipped runtime — documented, not forbidden by the type system.

## Properties (Ring 3)

### Property: empty-stack-equivalence (L0 gatekeeper)

**Invariant**: For all generated conversations, tool-behavior branches
(including an interrupt), and step budgets, `HarnessAgent(MiddlewareStack.empty)`
≍ current `ReactAgentImpl` on the deterministic model double — equal final
assistant message, equal final message list, equal request traces at the base
step, and equal event sequences.

**Generator strategy**: `genConversation` (constructive — a non-empty list of
`UserMessage`/`AssistantMessage` with `genContent` text, Hedgehog
`Range.linear 1 6`); `genToolBehavior` (one of: no-tool termination,
single-tool, two-tool, interrupt-on-first-tool); `genMaxSteps`
(`Range.linear 1 5`). The deterministic `ChatModel` double is parameterized by
a generated script (a list of `Completion` responses keyed by turn). Edge
cases: `maxSteps = 1`, empty tool set, interrupt on the only tool call. `cover`
labels: `no-tool` ≥ 20%, `single-tool` ≥ 20%, `multi-tool` ≥ 20%,
`interrupt` ≥ 20%, `maxSteps-exhausted` ≥ 10%.

```
forAll { (conv: List[Message], behavior: ToolBehavior, maxSteps: Int) =>
  val script = genScript(behavior, maxSteps)
  val model = DeterministicChatModel[IO](script)
  val harness = HarnessAgent[IO](config(model, MiddlewareStack.empty, baseTools, maxSteps))
  val current = ReactAgentImpl(name, desc, model, baseTools, basePrompt, maxSteps, emitter)
  for {
    hRes <- harness.generate(conv, maxSteps)
    cRes <- current.generate(conv, maxSteps)
    hTrace <- model.requestTrace
    cTrace <- model.requestTrace2  // separate trace per agent instance
  } yield hRes.message == cRes.message &&
    hRes.assistantMessage == cRes.assistantMessage &&
    hTrace == cTrace
}
```

### Property: per-request-prompt-folding

**Invariant**: For any middleware whose `promptSections(state)` reads a cell
that is mutated during the run, the rendered system prompt in request `i > 1`
differs from request 1's prompt iff the cell's value changed between requests —
proving the fold is per-request from current state, not a construction-time
snapshot.

**Generator strategy**: `genStatefulMiddleware` (constructive — a middleware
with a `Private` cell of type `String`, `beforeAgent` sets it to a generated
value, a tool updates it to a second generated value mid-run);
`genContent` (`Range.linear 1 20` chars). The model double returns one tool
call then a final response. Edge cases: cell unchanged between requests (prompts
equal), `beforeAgent` sets the cell to its initial (no change). `cover`:
`prompt-changed` ≥ 50%, `prompt-unchanged` ≥ 30%.

```
forAll { (beforeVal: String, toolVal: String) =>
  val cell = StateCell[String](owner, "note", initial = "", visibility = Private)
  val mw = statefulMiddleware(cell, beforeVal, toolVal)
  val stack = MiddlewareStack.validated(List(mw)).toOption.get
  val model = RecordingChatModel[IO](script = List(oneToolCall, finalResponse))
  val harness = HarnessAgent[IO](config(model, stack, baseTools, maxSteps = 5))
  for {
    _ <- harness.generate(List(UserMessage("go")), 5)
    prompts <- model.capturedSystemPrompts
  } yield prompts.length == 2 &&
    prompts(0).contains(beforeVal) &&
    prompts(1).contains(toolVal) &&
    (beforeVal != toolVal ==> (prompts(0) != prompts(1)))
}
```

### Property: per-request-tool-list

**Invariant**: For any stack whose `allTools` changes after `beforeAgent` (a
middleware contributes a tool only post-`beforeAgent`), the first request's
tool list reflects the post-`beforeAgent` contribution — the tool list is
`stack.allTools ++ baseTools` at request-build time, not a construction snapshot.

**Generator strategy**: `genLateToolMiddleware` (constructive — a middleware
whose `tools` reads a `Ref[IO, List[InvokableTool[IO]]]` that is empty at
construction and set by `beforeAgent` to a generated tool list);
`genToolName` (`Gen.string Range.linear 3 12`). Edge cases: empty late
contribution (request tools == baseTools), one late tool, many late tools.

```
forAll { (lateToolNames: List[String]) =>
  val lateTools = lateToolNames.map(n => Tool.invokable[IO](n, "", _ => IO.pure("ok")))
  val ref = Ref.of[IO](List.empty[InvokableTool[IO]])
  val mw = lateToolMiddleware(ref, lateTools)
  val stack = MiddlewareStack.validated(List(mw)).toOption.get
  val model = RecordingChatModel[IO](script = List(finalResponse))
  val harness = HarnessAgent[IO](config(model, stack, baseTools, maxSteps = 5))
  for {
    _ <- harness.generate(List(UserMessage("go")), 5)
    reqs <- model.capturedRequests
  } yield reqs.head.tools.map(_.info.name).takeRight(lateTools.length) == lateToolNames &&
    reqs.head.tools.length == baseTools.length + lateTools.length
}
```

### Property: afterAgent-skipped-on-interrupt

**Invariant**: For any stack and any tool that raises
`AgentInterruptedException`, `afterAgent` is invoked zero times and the
interrupted outcome carries a state snapshot equal to the state at the
interrupt point.

**Generator strategy**: `genStack` (constructive — `List` of 1–3 counting
middlewares, each with a `beforeAgent`/`afterAgent` counter, `Range.linear 1
3`); `genInterruptSignal` (one of `Simple`, `Stateful` with generated
`JsonValue`, `Composite` of generated children). Edge cases: single middleware,
interrupt on the first tool call, interrupt on a non-existent tool (unknown-
tool path). `cover`: `stack-size-1` ≥ 30%, `stack-size-3` ≥ 20%,
`stateful-signal` ≥ 30%.

```
forAll { (stackSize: Int, signal: InterruptSignal) =>
  val mws = (1 to stackSize).map(i => countingMiddleware(s"M$i")).toList
  val stack = MiddlewareStack.validated(mws).toOption.get
  val model = DeterministicChatModel[IO](script = List(oneToolCall(interruptingTool(signal))))
  val harness = HarnessAgent[IO](config(model, stack, List(interruptingTool(signal)), maxSteps = 5))
  for {
    res <- harness.generate(List(UserMessage("go")), 5).attempt
    afterCalls <- mws.traverse(_.afterAgentCount.get)
  } yield res match {
    case Right(r: HarnessResult.Interrupted) =>
      afterCalls.forall(_ == 0) && r.signal == signal
    case _ => false
  }
}
```

### Property: parallel-tool-merge-order-independence (CONCURRENCY)

**Invariant**: For a `Shared` cell with a semilattice `merge`, the merged
state after parallel execution of N tool calls in one iteration is independent
of completion order, and repeated runs under `TestControl` produce byte-identical
`HarnessState.snapshot`s.

**Generator strategy**: `genSemilatticeCell` (constructive — a `Set[String]`
cell with `merge = _ union _`); `genParallelToolWrites` (1–3 tools each writing
a generated `Set[String]`, `Range.linear 1 3` tools, each set size
`Range.linear 0 4` with `Gen.string Range.linear 1 5` elements). The model
double returns all tool calls in one response. Run under `TestControl` with a
fixed seed; repeat each generated case 3× and assert snapshot equality.
`cover`: `2-tools` ≥ 30%, `3-tools` ≥ 20%.

```
forAll { (writes: List[Set[String]]) =>
  val cell = StateCell[Set[String]](owner, "s", Set.empty, Shared, merge = _ union _)
  val tools = writes.zipWithIndex.map { case (w, i) =>
    Tool.invokable[IO](s"T$i", "", _ => IO.pure(w.mkString))
  }
  // each tool's ToolStep writes its set into the cell
  val stack = MiddlewareStack.empty[IO]
  val model = DeterministicChatModel[IO](script = List(oneResponseWithCalls(tools), finalResponse))
  TestControl.execute(embedsIntoHarness(cell, tools, model)) { ctl =>
    ctl.tickAll
    for {
      res <- harnessResult
      snap <- res.state.snapshot
    } yield snap == expectedUnion(cell.initial, writes)
  }
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `HarnessResult` match missing a variant | `HarnessResult` is a sealed trait with `Completed`/`Interrupted`/`Failed`; exhaustiveness escalation makes a non-exhaustive match a compile error | `assertDoesNotCompile("r match { case HarnessResult.Completed(_, _, _) => () }")` |
| `ReactAgent.create` called with a `MiddlewareStack` argument | The sugar signature exposes ONLY the empty-stack path; a stack argument is a type mismatch | `assertDoesNotCompile("ReactAgent.create(name, desc, model, tools, prompt, 5, None, Some(stack))")` (no such overload) |
| `HarnessAgent` constructed without an `Async[F]` in scope | The loop requires `Async[F]` for concurrency and `Ref`-based state; a missing instance is a compile error | `assertDoesNotCompile("HarnessActor[Option](config)")` (no `Async[Option]`) |
| `afterAgent` invoked directly by a caller on an interrupted result | `afterAgent` is a stack hook called by the loop, not a public method on `HarnessResult`; calling it on an interrupted result would double-fire on resume | `assertDoesNotCompile("result.afterAgent(state)")` (no such member on `HarnessResult`) |

## Formal Contracts (Ring 6)

The `HarnessAgent` loop is effectful (model calls, tool execution, event
emission, interrupt handling) and is therefore NOT a PureScala-expressible
kernel. The verified-mirror pattern applies to the loop's PURE sub-kernels,
which live in sibling specs and are referenced here for completeness:

| Sub-kernel | Location | Formal target |
|------------|----------|---------------|
| `HarnessState` get/set coherence | `specs/harness-state/spec.md` | Stainless: `get(c)(set(c)(v)(s)) == v` and `get(c)(set(d)(v)(s)) == get(c)(s)` for `c.id != d.id` |
| `cell.merge` semilattice laws (commutativity, associativity, idempotence) | `specs/harness-state/spec.md` / `specs/middleware-laws/spec.md` | Stainless: `merge(a,b) == merge(b,a)`, `merge(a, merge(b,c)) == merge(merge(a,b), c)`, `merge(a,a) == a` |
| `MiddlewareStack` monoid identity & associativity | `specs/middleware-stack/spec.md` / `specs/middleware-laws/spec.md` | Stainless-expressible for the pure fold combinators; observational equivalence (L1/L2) is a Ring 3 property |

For `HarnessAgent` itself, the formal contract is the typed signature contract
(Scala signatures of touched code) plus the L0 observational-equivalence
property (Ring 3, not Ring 6 — it is tested, not proven). A leaf mirror module
for the loop is NOT pursued in Phase 0 because the loop's effectfulness places
it outside PureScala's fragment; the pure sub-kernels above carry the formal
guarantees the loop relies on.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Empty-stack harness ≍ current agent on mock model | Requirement: Empty-stack conservative equivalence | Hedgehog property (empty-stack-equivalence) across generated conversations, tool behaviors, interrupts, step budgets | `HarnessAgentSpec` (L0 gatekeeper) |
| Empty-stack event sequence equals current agent's | Requirement: Event emission stays in the loop | Scenario test + L0 property event-sequence equality | `HarnessAgentSpec` |
| Tool list is `stack.allTools ++ baseTools` per request | Requirement: Per-request tool list derivation | Hedgehog property (per-request-tool-list) + scenario test | `HarnessAgentSpec` |
| Prompt folded per request from current `HarnessState` | Requirement: Per-request prompt folding from current state | Hedgehog property (per-request-prompt-folding) + scenario test | `HarnessAgentSpec` |
| `beforeAgent` once, `afterAgent` reverse-order on normal termination | Requirement: Loop orchestration order | Scenario test (hook trace) | `HarnessAgentSpec` |
| `afterAgent` runs on step-budget exhaustion (normal termination) | Requirement: Loop orchestration order + Scenario: maxSteps exhausted still runs afterAgent | Scenario test | `HarnessAgentSpec` |
| `afterAgent` NOT run on interrupt | Requirement: Interrupt snapshots state without afterAgent | Hedgehog property (afterAgent-skipped-on-interrupt) + adversarial scenario | `HarnessAgentSpec` |
| Interrupted outcome carries state snapshot + partial messages | Requirement: Interrupt snapshots state without afterAgent | Scenario test (snapshot decodes to interrupt-point state) | `HarnessAgentSpec` |
| Resumed run runs `afterAgent` exactly once | Requirement: Interrupt snapshots state without afterAgent + Scenario: Resumed run runs afterAgent once | Scenario test via `AgentRunner.resume` | `HarnessAgentSpec`, `AgentRunnerSpec` |
| Parallel tool merge order-independent for semilattice cells | Requirement: Parallel tool-call state merge is order-independent | Hedgehog property (parallel-tool-merge-order-independence) under `TestControl` | `HarnessAgentSpec` |
| Non-semilattice merge is honestly order-sensitive (not a bug) | Requirement: Parallel tool-call state merge is order-independent + Scenario: Non-semilattice merge | Scenario test documenting order-sensitivity | `HarnessAgentSpec` |
| `ReactAgent.create` source-compatible (55+ examples compile unchanged) | Requirement: ReactAgent.create is source-compatible sugar | `sbt adk4s-examples/compile` after refactor + scenario test | `adk4s-examples` build |
| `ReactAgent.create` does not accept a stack (adversarial) | Requirement: ReactAgent.create is source-compatible sugar + Scenario: caller passing a non-empty stack | Compile-negative test | `HarnessAgentTypeContract` |
| `HarnessResult` exhaustiveness | Compile-Negative: non-exhaustive match over `HarnessResult` | `assertDoesNotCompile` test | `HarnessAgentTypeContract` |
| `HarnessAgent` requires `Async[F]` | Requirement: Effect polymorphism with IO for Phase 0 + Compile-Negative | Compile-negative test (no `Async[Option]`) | `HarnessAgentTypeContract` |
| Concurrency scenarios use `TestControl`, not wall-clock | Requirement: Parallel tool-call state merge is order-independent + Requirement: Interrupt snapshots state without afterAgent | Static: grep tests for `Thread.sleep`/`TimeUnit.sleep` absence; `TestControl` used for parallel-merge and interrupt scenarios | `HarnessAgentSpec` |
| Loop does not bake `CompletionOptions` at construction | Requirement: Per-request tool list derivation | Adversarial review (check request-build site derives options from request tools) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `HarnessAgent` | final class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala` | new file; `F[_]: Async`; `generate`/`stream`; loop per design §6.1 |
| `HarnessResult` | sealed trait + case classes | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessResult.scala` | new file; `Completed`/`Interrupted`/`Failed` carrying final `AssistantMessage`, `List[Message]`, `HarnessState` |
| `HarnessAgent.Config` | case class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala` (companion) | `name`, `description`, `model: ChatModel[F]`, `stack: MiddlewareStack[F]`, `baseTools`, `basePrompt: Option[SystemPrompt]`, `maxSteps`, `emitter` |
| `IOHarnessAgent` | type alias | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala` | `type IOHarnessAgent = HarnessAgent[IO]` |
| `ReactAgent` (refactored) | class (sugar) | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala` | `create(...)` delegates to `HarnessAgent[IO]` with `MiddlewareStack.empty`; preserves existing signature |
| `ReactAgentImpl` (refactored) | class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgentImpl.scala` | loop body moves to `HarnessAgent`; `ReactAgentImpl` either delegates or is retired (L0 proves equivalence) |
| `AgentRunner` (refactored) | class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala` | `resume` gains `HarnessState.restore(stack.allCells, cp.harnessState)` before re-entering the loop; consumes `HarnessResult` |
| `adk4s-orchestration → adk4s-harness-api` | build dependency | `build.sbt` (`adk4s-orchestration` `.dependsOn`) | add `adk4s-harness-api` to the `.dependsOn(...)` list |
| `HarnessAgentSpec` | munit + Hedgehog + TestControl | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/HarnessAgentSpec.scala` | new test file; L0 gatekeeper + per-request + interrupt + parallel-merge properties |
| `HarnessAgentTypeContract` | typed contract | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/typecontract/HarnessAgentTypeContract.scala` | compile-negative + signature contract |
| `Generators` (harness-agent) | Hedgehog `Gen` | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/Generators.scala` | new — `genConversation`, `genToolBehavior`, `genMaxSteps`, `genStatefulMiddleware`, `genLateToolMiddleware`, `genSemilatticeCell`, `genParallelToolWrites`, `genInterruptSignal` |
| `stryker4s.conf` `mutate` retarget | build config | `stryker4s.conf` | point `mutate` at `**/agent/HarnessAgent.scala`, `**/agent/HarnessResult.scala` for Ring 5 (threshold 85% — loop + checkpoint adapters in orchestration) |
| Compile | build step | `adk4s-orchestration` | `sbt adk4s-orchestration/compile` |
| Test | build step | `adk4s-orchestration` | `sbt adk4s-orchestration/test` |
| Examples compile | build step | `adk4s-examples` | `sbt adk4s-examples/compile` — verifies `ReactAgent.create` source compatibility across 55+ examples |
