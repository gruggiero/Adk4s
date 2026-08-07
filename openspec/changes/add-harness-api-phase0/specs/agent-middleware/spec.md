# Spec: Agent Middleware

<!-- This is a DELTA spec for the `add-harness-api-phase0` change. It
     introduces the effect-polymorphic `AgentMiddleware[F]` trait with four
     hooks (`beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`),
     plus `tools` and state-aware `promptSections(state)` contributions, and
     the request/response model that the hooks operate on
     (`ModelRequest`/`ModelResponse`, `ToolCallCtx`/`ToolCallOut`,
     `SystemPrompt`/`PromptSection`, `MiddlewareName`).

     Grounded in `docs/deepagents4s-phase0-agent-middleware-DESIGN.md` §4.
     The `HarnessState` and `StateCell` types this spec references are
     introduced by the companion `harness-state` spec in this same change.

     ALTITUDE: requirements and scenarios use behavioral vocabulary only
     (Concept/action references, domain terms, test vectors). Code
     identifiers — class names, type aliases, opaque types — live in
     Implementation Anchors and the Concepts Introduced table. The full
     typed contract (Scala signatures) lives in design.md §4. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| ChatModel | The model call that `wrapModelCall` wraps; `ModelRequest` carries the conversation and tools that `ChatModel/generate` consumes | [chat-model.md](../../../concepts/chat-model.md) |
| Tool | Harness tools are `InvokableTool[F]` values contributed by `tools`; `ToolStep` threads state through tool execution | [tool.md](../../../concepts/tool.md) |
| agent-middleware (NEW — created by this spec) | The four-hook middleware abstraction (`beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`) plus `tools` and state-aware `promptSections` | `openspec/concepts/agent-middleware.md` (created at apply Step 12) |

Creating the `agent-middleware.md` concept file is PART OF implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` |
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` |
| `ToolInput` | case class | `org.adk4s.core.tools` |
| `ToolOutput` | case class | `org.adk4s.core.tools` |
| `Completion` | llm4s type | `org.llm4s.llmconnect.model` |
| `CompletionOptions` | llm4s type | `org.llm4s.llmconnect.model` |
| `Message` | llm4s type | `org.llm4s.llmconnect.model` |
| `HarnessState` | final class (from harness-state spec) | `org.adk4s.harness` |
| `StateCell[A]` | final class (from harness-state spec) | `org.adk4s.harness` |
| `Kleisli` | type (cats) | `cats.data` |
| `Applicative` | type class | `cats` |
| `Functor` | type class | `cats` |
| Hedgehog `property` / `Gen` / `Range` | property test kit | `hedgehog` / `hedgehog.munit` |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `AgentMiddleware[F[_]]` | trait | Effect-polymorphic agent-loop middleware with four hooks (`beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`) plus `tools`, `stateCells`, and state-aware `promptSections(state)` |
| `ModelRequest[F]` | case class | Per-request model call payload: system prompt, messages, tools, options, and harness state |
| `ModelResponse` | case class | Model call response: completion and harness state |
| `ModelStep[F]` | type alias | `Kleisli[F, ModelRequest[F], ModelResponse]` — the base step `wrapModelCall` wraps |
| `ToolCallCtx` | case class | State-threading tool execution payload: tool input and harness state |
| `ToolCallOut` | case class | Tool execution response: tool output and harness state |
| `ToolStep[F]` | type alias | `Kleisli[F, ToolCallCtx, ToolCallOut]` — the base step `wrapToolCall` wraps |
| `SystemPrompt` | case class | Composed prompt: optional base string plus a list of named sections, rendered in order |
| `PromptSection` | case class | Named prompt section: a name and a body string |
| `MiddlewareName` | opaque type (`String`) | Middleware identity for cell-id namespacing and error attribution |

## ADDED Requirements

### Requirement: AgentMiddleware trait provides four hooks with deepagents parity

The system SHALL provide an effect-polymorphic middleware trait parameterized
by `F[_]` with exactly four hooks — before-agent, after-agent, wrap-model-call,
and wrap-tool-call — corresponding one-to-one to the deepagents hooks
`before_agent`, `after_agent`, `wrap_model_call`, and `wrap_tool_call`. Each
hook SHALL have a default implementation that is a no-op (returns the input
state unchanged for the before/after hooks, returns the next step unchanged for
the wrap hooks). The trait SHALL NOT provide node-level before-model or
after-model hooks (LangGraph-graph-shaped constructs the loop does not need),
and SHALL NOT provide a separate modify-model-request hook (subsumed by
wrap-model-call).

**Given** a middleware value implementing the trait with all default hook
implementations
**When** each hook is invoked with a representative input
**Then** before-agent returns the input state unchanged, after-agent returns
the input state unchanged, wrap-model-call returns the next step unchanged, and
wrap-tool-call returns the next step unchanged.

**Rationale**: The four hooks are the exact set deepagents uses in practice —
every Phase 1–3 middleware maps to a hook with no residue. Pinning the hook set
now means later phases write middlewares against a fixed contract.

#### Scenario: Default before-agent is a no-op

**Given** a middleware with the default `beforeAgent` implementation and an
initial harness state `s0`
**When** `beforeAgent(s0)` is called
**Then** the result is `s0` (unchanged).

#### Scenario: Default wrap-model-call is pass-through

**Given** a middleware with the default `wrapModelCall` implementation and a
base model step `base`
**When** `wrapModelCall(base)` is called
**Then** the result is `base` (the same step reference).

#### Scenario: Forbidden before-model hook does not exist

**Given** the middleware trait's public API
**When** the API surface is inspected for a before-model or after-model hook
**Then** no such hook exists — the trait exposes only the four named hooks.

### Requirement: Middleware declares state cells, tools, and state-aware prompt sections

The middleware trait SHALL expose three contribution members in addition to the
four hooks: a state-cell declaration list (defaulting to empty), a static tools
list (defaulting to empty), and a state-aware prompt-sections function taking a
harness state and returning a list of named prompt sections (defaulting to
empty). The prompt-sections function SHALL take the current harness state as a
parameter so that section text is folded per-request from live state, not fixed
at construction time.

**Given** a middleware that declares one state cell, two tools, and a
prompt-sections function that reads the declared cell
**When** the middleware's `stateCells`, `tools`, and `promptSections(state)`
are each queried
**Then** `stateCells` has one entry, `tools` has two entries, and
`promptSections(state)` returns sections whose content reflects the cell's
current value in `state`.

**Rationale**: State-aware prompt sections let a middleware recall content in
before-agent (load AGENTS.md, query a graph store, read a notebook) and surface
that recalled content as an auditable, named prompt section — rather than
smuggling it into the request from wrap-model-call, which would bypass the
ordered, section-by-section, testable prompt assembly.

#### Scenario: Static-section middleware ignores the state parameter

**Given** a filesystem middleware whose prompt text is fixed tool instructions
independent of state
**When** `promptSections(state)` is called with any harness state
**Then** the returned sections are the same regardless of the state argument
(the parameter is ignored).

#### Scenario: State-aware middleware reflects recalled content

**Given** a skills middleware that loads a skills document in `beforeAgent`
into a private cell, and whose `promptSections(state)` reads that cell
**When** `beforeAgent` runs (populating the cell), then `promptSections(state)`
is called with the post-before-agent state
**Then** the returned section body contains the loaded skills document — the
section text reflects the recalled content, not a construction-time constant.

#### Scenario: Default contributions are all empty

**Given** a middleware overriding nothing (all defaults)
**When** `stateCells`, `tools`, and `promptSections(state)` are queried
**Then** all three return empty lists.

### Requirement: ModelRequest carries per-request model call payload with harness state

The system SHALL provide a per-request model call payload as an immutable case
class carrying an optional system prompt, a list of messages, a list of
invokable tools, completion options, and the current harness state. The tool
list and system prompt SHALL be per-request values (not baked once at agent
construction), so that middleware can rewrite them per call.

**Given** a model request constructed with system prompt `Some(p)`, messages
`[m1, m2]`, tools `[t1, t2, t3]`, options `opts`, and state `s`
**When** the request's fields are read
**Then** the system prompt is `Some(p)`, the messages are `[m1, m2]`, the tools
are `[t1, t2, t3]`, the options are `opts`, and the state is `s`.

**Rationale**: Today's `ReactAgentImpl` bakes `CompletionOptions` once from
static config. Making the tool list and system prompt per-request values is
what lets `wrapModelCall` rewrite them — the whole point of the hook.

#### Scenario: Request with no system prompt

**Given** a model request constructed with `systemPrompt = None`
**When** the system prompt field is read
**Then** it is `None` (a request without a system prompt is a valid value).

#### Scenario: Request with empty tool list

**Given** a model request constructed with `tools = Nil`
**When** the tools field is read
**Then** it is empty (a request with no tools is valid — the model is called
without tool-calling for that step).

#### Scenario: Request is immutable

**Given** a model request value
**When** code attempts to mutate a field in place
**Then** compilation fails — the payload is a case class with no in-place
mutation methods.

### Requirement: ModelResponse carries completion and harness state

The system SHALL provide a model call response as an immutable case class
carrying a completion and the harness state resulting from the call. The
response state SHALL be the state that flows into the next loop iteration.

**Given** a model response constructed with completion `c` and state `s1`
**When** the response's fields are read
**Then** the completion is `c` and the state is `s1`.

**Rationale**: `wrapModelCall` observes and may transform the response. Carrying
state in the response lets a middleware update state based on the model's output
(e.g. summarization bookkeeping) and have that update flow forward.

#### Scenario: Response state flows forward

**Given** a wrap-model-call that updates state in the response from `s0` to `s1`
**When** the loop reads the response state
**Then** the next iteration's request state is `s1` (the response state is the
input to the next iteration).

#### Scenario: Response with unchanged state

**Given** a base model step that does not modify state
**When** the response is produced
**Then** the response state equals the request state (state passes through
unchanged when no middleware updates it).

### Requirement: ModelStep is a Kleisli from ModelRequest to ModelResponse

The system SHALL define the model step as a type alias
`Kleisli[F, ModelRequest[F], ModelResponse]` — a function from a model request
to an effectful model response. The wrap-model-call hook SHALL accept and
return a model step, composing as a Kleisli endomorphism.

**Given** a base model step `base: ModelStep[F]` and a middleware `m`
**When** `m.wrapModelCall(base)` is called
**Then** the result is a `ModelStep[F]` — a `Kleisli[F, ModelRequest[F],
ModelResponse]` — that may rewrite the request before delegating to `base` and
may transform the response after.

**Rationale**: The Kleisli shape is identical to deepagents' `wrap_model_call`
where `handler` is `next`. It makes the wrap a composable endomorphism:
`m1.wrapModelCall(m2.wrapModelCall(base))` nests naturally.

#### Scenario: Base step delegates to ChatModel

**Given** a base model step constructed from a `ChatModel[F]`
**When** the step is run with a model request
**Then** it delegates to `ChatModel.generate` with the request's conversation
and options, returning a `ModelResponse` carrying the completion and the
request's state.

#### Scenario: Wrapped step rewrites the tool list

**Given** a middleware whose `wrapModelCall` removes a tool from the request
and a base step that records the tools it received
**When** the wrapped step is run
**Then** the base step receives the rewritten tool list (without the removed
tool), confirming the request rewrite propagates.

### Requirement: ToolCallCtx and ToolCallOut thread harness state through tool execution

The system SHALL provide a tool-call context as an immutable case class
carrying a tool input and the current harness state, and a tool-call output as
an immutable case class carrying a tool output and the resulting harness state.
State-threading tool execution SHALL be explicit in these product types rather
than buried in a `StateT` monad, so that checkpoint and interrupt paths have the
state value reified at suspension points.

**Given** a tool-call context constructed with input `ti` and state `s0`
**When** the context's fields are read
**Then** the input is `ti` and the state is `s0`.

**Rationale**: Harness tools are state manipulators (write-todos writes the todo
cell; filesystem tools read and write the files cell). The explicit product form
keeps the checkpoint/interrupt paths non-monadic and obvious. The isomorphism
`ToolStep[F] ≅ Kleisli[StateT[F, HarnessState, _], ToolInput, ToolOutput]` is
documented for authors who prefer `StateT` internally.

#### Scenario: Context carries state into a stateful tool

**Given** a write-todos tool that updates the todo cell in the context state
**When** the tool runs with `ToolCallCtx(input, s0)`
**Then** the output is `ToolCallOut(toolOutput, s1)` where `s1` has the updated
todo cell.

#### Scenario: Output state is the input to the next tool call

**Given** two sequential tool calls where the first updates state from `s0` to
`s1`
**When** the second tool call's context is built
**Then** its state is `s1` (the first call's output state flows into the second
call's context).

#### Scenario: Context and output are immutable

**Given** a `ToolCallCtx` and a `ToolCallOut` value
**When** code attempts to mutate a field in place
**Then** compilation fails — both are case classes with no in-place mutation.

### Requirement: ToolStep is a Kleisli from ToolCallCtx to ToolCallOut

The system SHALL define the tool step as a type alias
`Kleisli[F, ToolCallCtx, ToolCallOut]` — a function from a tool-call context to
an effectful tool-call output. The wrap-tool-call hook SHALL accept and return a
tool step, composing as a Kleisli endomorphism.

**Given** a base tool step `base: ToolStep[F]` and a middleware `m`
**When** `m.wrapToolCall(base)` is called
**Then** the result is a `ToolStep[F]` that may intercept the call (e.g. raise
an interrupt signal instead of calling `next`) or transform the output after.

**Rationale**: The Kleisli shape matches `wrap_tool_call` where `handler` is
`next`. State-threading is explicit here (injected via `ToolRuntime` in
deepagents), making the state value available at every wrap layer.

#### Scenario: HITL middleware raises an interrupt instead of calling next

**Given** a HITL policy middleware whose `wrapToolCall` matches a tool name
against a policy, and a call to a tool that requires approval
**When** the wrapped step is run with a context for that tool
**Then** the middleware raises an interrupt signal and does NOT call `next`
(the base step is never invoked).

#### Scenario: Logging middleware observes input and output

**Given** a logging middleware whose `wrapToolCall` records the input before
and the output after delegating to `next`
**When** the wrapped step is run
**Then** both the input and output are recorded, and the base step is called
exactly once.

### Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints

The system SHALL provide a `passthrough` lift on the tool step companion that
converts a state-oblivious tool endpoint (`Kleisli[F, ToolInput, ToolOutput]`)
into a state-threading tool step by threading the context's state through
unchanged. At `F = IO`, the state-oblivious endpoint is definitionally the
existing `ToolEndpoint`, so the entire existing `ToolMiddleware` combinator set
(logging, timing, retry, validation) SHALL compose under the new abstraction
unchanged.

**Given** a state-oblivious endpoint `ep: Kleisli[F, ToolInput, ToolOutput]`
and a tool-call context `ToolCallCtx(input, state)`
**When** `ToolStep.passthrough(ep).run(ToolCallCtx(input, state))` is called
**Then** the result is `ep.run(input).map(out => ToolCallOut(out, state))` —
the endpoint's output is wrapped with the context's state unchanged.

**Rationale**: Plain, state-oblivious tools and the existing `ToolMiddleware`
combinator set must lift for free under the new abstraction. Without
`passthrough`, every existing tool would need a manual state-threading wrapper,
breaking the 55+ examples and the `ToolMiddleware` ecosystem.

#### Scenario: Existing ToolEndpoint lifts unchanged

**Given** an existing `ToolEndpoint` (= `Kleisli[IO, ToolInput, ToolOutput]`)
and a `ToolMiddleware.logging` wrapper applied to it
**When** `ToolStep.passthrough(loggingWrappedEndpoint)` is constructed and run
**Then** logging fires, the endpoint executes, and the output carries the
context's state through unchanged — the existing combinator works without
modification.

#### Scenario: passthrough preserves state exactly

**Given** a state-oblivious endpoint `ep` and a context with state `s`
**When** `ToolStep.passthrough(ep)` runs on `ToolCallCtx(input, s)`
**Then** the output state is `s` (bit-identical — passthrough never modifies
state).

#### Scenario: passthrough with a failing endpoint

**Given** a state-oblivious endpoint that raises an error
**When** `ToolStep.passthrough(ep)` runs on a context with state `s`
**Then** the error propagates (the state is not returned — the effect fails
before `ToolCallOut` is constructed).

### Requirement: SystemPrompt composes a base string and named sections in order

The system SHALL provide a composed system prompt as an immutable case class
carrying an optional base string and a list of named prompt sections. The
rendered prompt SHALL be the base string (if present) followed by each section's
body concatenated in list order. Each prompt section SHALL be an immutable case
class carrying a name and a body string.

**Given** a system prompt with base `Some("You are a helpful assistant.")` and
sections `[PromptSection("tools", "Use these tools..."), PromptSection("skills",
"Skills: ...")]`
**When** the prompt is rendered
**Then** the result is the base string followed by the two section bodies in
order: `"You are a helpful assistant.\nUse these tools...\nSkills: ..."`.

**Rationale**: This is what makes deepagents' prompt assembly reproducible:
filesystem, skills, sub-agent, and memory middlewares each contribute a named
section, and the final prompt is a deterministic fold in stack order —
inspectable and testable section-by-section instead of string-concatenated in
the dark.

#### Scenario: Prompt with no base

**Given** a system prompt with `base = None` and sections `[s1, s2]`
**When** the prompt is rendered
**Then** the result is the concatenation of `s1.body` and `s2.body` (no base
prefix).

#### Scenario: Prompt with no sections

**Given** a system prompt with `base = Some("base")` and `sections = Nil`
**When** the prompt is rendered
**Then** the result is `"base"` (no section suffixes).

#### Scenario: Empty prompt

**Given** a system prompt with `base = None` and `sections = Nil`
**When** the prompt is rendered
**Then** the result is the empty string (an empty prompt is a valid value).

#### Scenario: Section name is preserved for inspection

**Given** a system prompt with sections `[PromptSection("filesystem", "...")]`
**When** the sections are inspected
**Then** the section name `"filesystem"` is readable — sections are named so
that prompt assembly is auditable section-by-section, not just a blob of text.

### Requirement: promptSections is folded per-request from current harness state

The loop SHALL fold each middleware's `promptSections(state)` per request from
the current harness state, concatenating the results in stack order. The fold
SHALL NOT be a one-time operation at stack construction; it SHALL re-evaluate
`promptSections(state)` on every model call with the state current at that call.

**Given** a stack `[m1, m2]` where `m1.promptSections(state)` returns `[a]` and
`m2.promptSections(state)` returns `[b]`, and a loop that makes two model calls
with states `s0` then `s1`
**When** the loop builds the system prompt for each call
**Then** the first call's sections are `[a(s0), b(s0)]` and the second call's
sections are `[a(s1), b(s1)]` — the sections are re-folded from the current
state on each call, in stack order.

**Rationale**: A static `promptSections` (no state parameter) is sufficient for
middlewares whose text is fixed at construction, but not for any middleware
whose prompt contribution is state (recalled content, a running notebook, memory
hits). Folding per-request from live state makes the memory/skills/recall story
work as designed, through the same auditable assembly as static sections.

#### Scenario: Sections change between iterations

**Given** a middleware whose `promptSections(state)` reads a cell that is
updated by a tool call, and a loop that makes two model calls (before and after
the tool call)
**When** the sections are folded for each call
**Then** the second call's sections differ from the first call's — the section
text reflects the updated cell value.

#### Scenario: Sections are in stack order

**Given** a stack `[m1, m2]` where `m1` contributes section `"first"` and `m2`
contributes section `"second"`
**When** the sections are folded
**Then** the order is `["first", "second"]` — m1's sections appear before m2's,
matching stack order.

#### Scenario: Static-section middleware is unaffected by re-folding

**Given** a filesystem middleware whose `promptSections(state)` returns a
constant list regardless of state
**When** the sections are folded on two different states
**Then** both folds produce the same sections — re-folding is harmless for
static-section middlewares (the cost is a `_ =>` in the override, not a
structural change).

### Requirement: MiddlewareName is an opaque string type for middleware identity

The system SHALL provide a middleware name as an opaque type backed by `String`,
with a constructor and a value accessor. The name SHALL be used for state-cell
id namespacing (forming `"owner/name"` cell ids) and for error attribution in
stack construction validation.

**Given** a middleware name constructed from the string `"todo-list"`
**When** the name's value is read
**Then** the result is `"todo-list"`.

**Rationale**: A named opaque type prevents accidental confusion of middleware
names with arbitrary strings (tool names, agent names) at the type level, while
remaining a plain string at runtime for cell-id formation and error messages.

#### Scenario: Name used in cell-id formation

**Given** a middleware named `"todo-list"` that declares a state cell named
`"items"`
**When** the cell's id is formed
**Then** the id is `"todo-list/items"` — the middleware name namespaces the
cell id.

#### Scenario: Name used in stack-error attribution

**Given** two middlewares named `"m1"` and `"m2"` that both declare a cell named
`"data"`, causing a duplicate cell-id collision
**When** stack validation detects the collision
**Then** the error names both owners (`"m1"` and `"m2"`) — the middleware name
is the attribution key.

#### Scenario: Opaque type prevents string confusion

**Given** a function expecting a `MiddlewareName` and a raw `String` value
`"todo-list"`
**When** the raw string is passed where a `MiddlewareName` is expected
**Then** compilation fails — the opaque type prevents passing a plain string
where a middleware name is required.

### Requirement: AgentMiddleware.id is the identity middleware

The system SHALL provide an identity middleware (`AgentMiddleware.id`) whose
name is `"identity"` and whose every hook and contribution is the default
no-op/empty value. The identity middleware SHALL be the neutral element of
middleware composition: inserting it into any stack position SHALL be
observationally equivalent to the stack without it.

**Given** the identity middleware `AgentMiddleware.id[F]`
**When** its `stateCells`, `tools`, `promptSections(state)`, `beforeAgent`,
`afterAgent`, `wrapModelCall`, and `wrapToolCall` are each invoked
**Then** `stateCells` is empty, `tools` is empty, `promptSections(state)` is
empty for any state, `beforeAgent(s)` returns `s`, `afterAgent(s)` returns `s`,
`wrapModelCall(next)` returns `next`, and `wrapToolCall(next)` returns `next`.

**Rationale**: The identity middleware is the monoid unit of the middleware
stack. It must be observationally inert so that `MiddlewareStack.empty` (the
empty list) and a stack containing only `id` are indistinguishable — this is the
L1 law (monoid identity) and the foundation of the conservative-refactor
guarantee (L0).

#### Scenario: Identity name

**Given** `AgentMiddleware.id[F]`
**When** the name is read
**Then** it is `"identity"`.

#### Scenario: Identity inserted at any position is inert

**Given** a stack `[m1, m2]` and the identity middleware `id`
**When** the stacks `[id, m1, m2]`, `[m1, id, m2]`, and `[m1, m2, id]` are each
run with the same input
**Then** all three produce the same final state, the same final message, and the
same request trace as `[m1, m2]` — the identity is inert at every position.

### Requirement: Default-neutrality — all-defaults middleware is observationally equivalent to identity

A middleware overriding nothing (all default hooks, empty `stateCells`, empty `tools`, empty `promptSections`) SHALL be observationally equivalent to `AgentMiddleware.id`.

Observational equivalence means: driven by the testkit's deterministic ChatModel double and a fixed tool set and input, both produce equal final assistant message, equal final harness state snapshot, and equal request traces at the base step.

**Given** a middleware `m` that overrides no member (all defaults) and the
identity middleware `id`
**When** both are individually inserted into an otherwise-empty stack and run
on the same generated conversation
**Then** the final assistant messages are equal, the final state snapshots are
equal, and the request traces (the sequence of `ModelRequest` values seen by
the base step) are equal.

**Rationale**: This is law L4. It ensures that a middleware that contributes
nothing is truly a no-op — not accidentally observably different due to a
non-default default. It is the property that makes "override only what you need"
safe.

#### Scenario: All-defaults middleware matches identity on traces

**Given** an all-defaults middleware `m` and `AgentMiddleware.id`, both run
individually on a 3-turn generated conversation with the deterministic ChatModel
double
**When** the request traces at the base step are compared
**Then** the traces are element-for-element equal (same requests in the same
order).

#### Scenario: All-defaults middleware matches identity on final state

**Given** an all-defaults middleware `m` and `AgentMiddleware.id`, run on a
conversation that includes a tool call
**When** the final harness state snapshots are compared
**Then** the snapshots are equal (no state divergence).

### Requirement: wrap-model-call composes as outermost-first nesting

For a stack `[m1, m2]`, the composed `wrapModelCall(base)` SHALL equal
`m1.wrapModelCall(m2.wrapModelCall(base))` — m1 is outermost (sees the request
first, the response last). The same nesting order SHALL apply to
`wrapToolCall`. This SHALL be tested by trace equality: the sequence of
requests seen by the base step and the sequence of responses seen by each
middleware SHALL match the nesting order.

**Given** a stack `[m1, m2]` where `m1` prepends `"m1-"` to the system prompt
and `m2` prepends `"m2-"` to the system prompt
**When** `stack.wrapModelCall(base)` is run with a request whose system prompt
is `Some(p)`
**Then** the base step receives a request whose system prompt is
`Some("m1-m2-" + p)` — m1's rewrite is outermost (applied first to the request),
m2's is inner (applied second), and the base sees the composition.

**Rationale**: This is law L3 (hook distribution). The outermost-first order
matches deepagents' stack semantics exactly: the first middleware in the list
sees the request first and the response last. Pinning it by trace equality
ensures future stack-implementation changes cannot break the ordering.

#### Scenario: Two-middleware nesting order by trace

**Given** a stack `[m1, m2]` where each middleware records the request it
forwards to `next` and the response it receives back
**When** `stack.wrapModelCall(base)` is run
**Then** m1's recorded request is the original request (before m2's rewrite),
m2's recorded request is m1's rewritten request, and the base receives m2's
rewritten request — confirming `m1(m2(base))` nesting.

#### Scenario: Single middleware is trivially nested

**Given** a stack `[m1]`
**When** `stack.wrapModelCall(base)` is run
**Then** the result is `m1.wrapModelCall(base)` — a single-element stack has no
nesting to compose.

#### Scenario: Empty stack is the base step

**Given** an empty stack `[]`
**When** `stack.wrapModelCall(base)` is called
**Then** the result is `base` — the empty stack does not wrap.

## Properties (Ring 3)

### Property: default-neutrality

**Invariant**: For any middleware `m` overriding nothing (all defaults), `m` is
observationally equivalent to `AgentMiddleware.id`: both produce equal final
assistant messages, equal final state snapshots, and equal request traces when
run individually on the same generated conversation.

**Generator strategy**: `genConversation` (constructive — generates N messages,
N ∈ Range.linear 1 10, each a user message with string content from
`Gen.string(Gen.alpha, Range.linear 1 50)`); `genAllDefaultsMiddleware`
(constructive — a middleware instance overriding no member, with a generated
name from `Gen.string(Gen.alpha, Range.linear 3 20)`). Uses the deterministic
ChatModel double from the testkit. Classify by conversation length (1, 2–5, 6+).

```
forAll { (conv: List[Message], mwName: String) =>
  val m = new AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName(mwName)
    // all defaults — no overrides
  val stackWithM = MiddlewareStack.validated(List(m)).toOption.get
  val stackWithId = MiddlewareStack.validated(List(AgentMiddleware.id[IO])).toOption.get
  val (traceM, stateM, msgM) = runOnDouble(stackWithM, conv)
  val (traceId, stateId, msgId) = runOnDouble(stackWithId, conv)
  traceM == traceId && stateM == stateId && msgM == msgId
}
```

### Property: hook-distribution-wrap-model-call

**Invariant**: For a stack `[m1, m2]`, `stack.wrapModelCall(base)` composes as
`m1.wrapModelCall(m2.wrapModelCall(base))` — tested by trace equality: the
request trace at the base step and the response traces at each middleware
match the outermost-first nesting.

**Generator strategy**: `genTraceMiddleware` (constructive — generates a
middleware that prepends a unique tag to the system prompt and records the
request it forwards and the response it receives; tag from
`Gen.string(Gen.alpha, Range.linear 1 5)`); `genStackSize` (constant 2 — the
property is about two-element nesting; a separate property covers general
depth); `genConversation` (constructive, N ∈ Range.linear 1 5). Classify by
whether the stack has request-rewriting middlewares.

```
forAll { (tag1: String, tag2: String, conv: List[Message]) =>
  val m1 = traceMiddleware(tag1)
  val m2 = traceMiddleware(tag2)
  val stack = MiddlewareStack.validated(List(m1, m2)).toOption.get
  val composed = stack.wrapModelCall(baseStep)
  val manual = m1.wrapModelCall(m2.wrapModelCall(baseStep))
  val traceComposed = runAndCaptureTrace(composed, conv)
  val traceManual = runAndCaptureTrace(manual, conv)
  traceComposed == traceManual
}
```

### Property: prompt-sections-state-awareness

**Invariant**: For any stack and any harness state, the folded sections are the
concatenation of each middleware's `promptSections(state)` in stack order, and
folding on two different states produces different sections whenever at least
one middleware's sections depend on state.

**Generator strategy**: `genStateAwareMiddleware` (constructive — generates a
middleware with a private cell and a `promptSections(state)` that reads the
cell and emits a section with the cell's current value; cell value from
`Gen.string(Gen.alpha, Range.linear 1 20)`); `genState` (constructive — builds
a `HarnessState` with generated cell values); `genStackSize` (Range.linear 1 5).
Classify by stack size (1, 2–3, 4+).

```
forAll { (middlewares: List[AgentMiddleware[IO]], state: HarnessState) =>
  val stack = MiddlewareStack.validated(middlewares).toOption.get
  val folded = stack.allSections(state)
  val expected = middlewares.flatMap(_.promptSections(state))
  folded == expected
}
```

### Property: toolstep-passthrough-isomorphism

**Invariant**: For any state-oblivious endpoint `ep` and any tool-call context
`ToolCallCtx(input, state)`, `ToolStep.passthrough(ep).run(ToolCallCtx(input,
state))` equals `ep.run(input).map(out => ToolCallOut(out, state))` — the
endpoint output is wrapped with the context's state unchanged.

**Generator strategy**: `genToolInput` (constructive — generates name from
`Gen.string(Gen.alpha, Range.linear 3 10)`, arguments as a JSON string from
`Gen.string(Gen.alphaNum, Range.linear 1 30)`, callId from
`Gen.string(Gen.uuid, Range.linear 1 36)`); `genToolOutput` (constructive —
matching name, result string, isError flag from `Gen.boolean`); `genState`
(constructive — `HarnessState.empty` or a state with generated cells). The
endpoint is a pure function from the generated input to the generated output.
Classify by state (empty vs populated).

```
forAll { (input: ToolInput, output: ToolOutput, state: HarnessState) =>
  val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli(_ => IO.pure(output))
  val step = ToolStep.passthrough[IO](ep)
  val result = step.run(ToolCallCtx(input, state)).unsafeRunSync()
  result == ToolCallOut(output, state)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A raw `String` passed where `MiddlewareName` is expected | `MiddlewareName` is an opaque type; the type system prevents confusion with arbitrary strings | `assertDoesNotCompile("val n: MiddlewareName = \"todo\"")` |
| A `beforeModel` or `afterModel` method on `AgentMiddleware` | The trait deliberately omits node-level hooks (LangGraph-graph-shaped); adding them would break the four-hook contract | `assertDoesNotCompile("class M extends AgentMiddleware[IO] { def beforeModel = ??? }")` |
| A `modifyModelRequest` method on `AgentMiddleware` | Subsumed by `wrapModelCall`; a separate method would duplicate the hook and break composition | `assertDoesNotCompile("class M extends AgentMiddleware[IO] { def modifyModelRequest = ??? }")` |
| `promptSections` defined without a `HarnessState` parameter | The spec mandates state-aware `promptSections(state: HarnessState)`; a no-arg override does not match the trait signature | `assertDoesNotCompile("class M extends AgentMiddleware[IO] { def promptSections: List[PromptSection] = Nil }")` |
| In-place mutation of `ModelRequest`, `ModelResponse`, `ToolCallCtx`, `ToolCallOut`, `SystemPrompt`, or `PromptSection` fields | All are immutable case classes; no `update`/`+=`/mutable field exists | `assertDoesNotCompile("val r = ModelRequest[IO](...); r.messages = Nil")` |

## Formal Contracts (Ring 6)

Ring 6 applies via the VERIFIED-MIRROR pattern. The shipped `AgentMiddleware`
trait and `ToolStep.passthrough` cannot be verified directly — the shipped code
uses `cats.data.Kleisli` and `F[_]` effect types that Stainless cannot model,
and the build's Scala version (3.8.4) differs from the Stainless frontend
(3.7.2). The *algorithm* under `ToolStep.passthrough` is a pure state-threading
lift, and it survives reduction to observable effect.

### The abstraction

A tool endpoint becomes a total function `ToolInput => ToolOutput` (the effect is
trivial — `pure` — in the model). A harness state becomes a `BigInt` identity
(the state is opaque to the lift; only identity matters for the isomorphism). A
tool-call context becomes a pair `(ToolInput, BigInt)`, and a tool-call output
becomes a pair `(ToolOutput, BigInt)`.

```scala
// verified/src/main/scala/org/adk4s/verified/MiddlewareKernel.scala  (Scala 3.7.2)
case class ToolInputModel(name: String, args: String, callId: String)
case class ToolOutputModel(name: String, result: String, callId: String, isError: Boolean)

def passthrough(
  ep: ToolInputModel => ToolOutputModel,
  ctx: (ToolInputModel, BigInt)
): (ToolOutputModel, BigInt) =
  (ep(ctx._1), ctx._2)
```

### Contract: passthrough — state preserved, endpoint applied

**Precondition** (`require`): none — `passthrough` is total.
**Postcondition** (`ensuring`): the output's second component (state) equals
the context's second component (state), and the output's first component equals
`ep` applied to the context's first component (input).

```scala
def passthrough(
  ep: ToolInputModel => ToolOutputModel,
  ctx: (ToolInputModel, BigInt)
): (ToolOutputModel, BigInt) = {
  (ep(ctx._1), ctx._2)
}.ensuring(r => r._2 == ctx._2 && r._1 == ep(ctx._1))
```

### Bridge — how the shipped code is bound to the model

`MiddlewareKernelBridgeSpec` (Hedgehog, in `adk4s-harness-api` test sources)
runs the real `ToolStep.passthrough` and the model on the SAME generated
inputs and asserts they agree on the proven invariant:

1. the real `passthrough(ep).run(ctx)` output state equals the context state
   (state preservation);
2. the real `passthrough(ep).run(ctx)` output value equals `ep.run(ctx.input)`
   (endpoint applied correctly).

Build wiring this spec commits to: `adk4s-harness-api dependsOn(verified %
Test)`. TASTy is backward compatible, so the 3.8.4 module may read the 3.7.2
artifact. `stainlessEnabled := false` by default, so the bridge test pays only
a plain compile of the model; verification is the separate `sbt -J-Xmx6g ring6`
step.

### Scope — what is proven, and what is delegated

Target proofs (best-effort): the `passthrough` state-preservation contract
above (quantifier-free, structurally trivial).

DELEGATED to Ring 3, and named here rather than dropped:

| Law | Why not proven here | Covered by |
|-----|---------------------|------------|
| Default neutrality (all-defaults ≍ id) | Observational equivalence over `F[_]` effects and ChatModel traces is not expressible in PureScala | Property: default-neutrality |
| Hook distribution (m1(m2(base)) nesting) | Kleisli composition over `F[_]` is not modelled in PureScala | Property: hook-distribution-wrap-model-call |
| promptSections state-awareness | `HarnessState` heterogeneous map and `promptSections` function are not reducible to PureScala without the full state model | Property: prompt-sections-state-awareness |
| SystemPrompt render order | String concatenation is trivially correct but the render is a one-liner not worth a separate model | Scenario: Prompt with no base, Scenario: Sections are in stack order |

If a target VC diverges in z3, it moves into this table with its Ring 3
property named — it is never silently dropped.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Four hooks with deepagents parity, no before/after-model | Requirement: AgentMiddleware trait provides four hooks with deepagents parity | type system (trait signature) + compile-negative test (no beforeModel/afterModel) | AgentMiddlewareTypeContract |
| Default hooks are no-ops | Requirement: AgentMiddleware trait provides four hooks with deepagents parity + Scenario: Default before-agent is a no-op | scenario test (each default hook returns input unchanged) | AgentMiddlewareSpec |
| No modify-model-request hook | Requirement: AgentMiddleware trait provides four hooks with deepagents parity | compile-negative test (assertDoesNotCompile) | AgentMiddlewareTypeContract |
| stateCells, tools, promptSections(state) contributions | Requirement: Middleware declares state cells, tools, and state-aware prompt sections | scenario test (each contribution member) | AgentMiddlewareSpec |
| promptSections takes HarnessState parameter | Requirement: Middleware declares state cells, tools, and state-aware prompt sections | compile-negative test (no-arg override fails) | AgentMiddlewareTypeContract |
| State-aware promptSections reflects recalled content | Requirement: Middleware declares state cells, tools, and state-aware prompt sections + Scenario: State-aware middleware reflects recalled content | scenario test (beforeAgent populates cell, promptSections reads it) | AgentMiddlewareSpec |
| Default contributions are empty | Requirement: Middleware declares state cells, tools, and state-aware prompt sections + Scenario: Default contributions are all empty | scenario test | AgentMiddlewareSpec |
| ModelRequest carries per-request payload | Requirement: ModelRequest carries per-request model call payload with harness state | scenario test (field reads) | ModelRequestSpec |
| ModelRequest is immutable | Requirement: ModelRequest carries per-request model call payload with harness state + Scenario: Request is immutable | compile-negative test (no in-place mutation) | AgentMiddlewareTypeContract |
| ModelResponse carries completion and state | Requirement: ModelResponse carries completion and harness state | scenario test (field reads, state flows forward) | ModelRequestSpec |
| ModelStep is Kleisli | Requirement: ModelStep is a Kleisli from ModelRequest to ModelResponse | scenario test (base step delegates, wrapped step rewrites) | ModelStepSpec |
| ToolCallCtx/ToolCallOut thread state | Requirement: ToolCallCtx and ToolCallOut thread harness state through tool execution | scenario test (state in, state out) | ToolStepSpec |
| ToolCallCtx/ToolCallOut are immutable | Requirement: ToolCallCtx and ToolCallOut thread harness state through tool execution + Scenario: Context and output are immutable | compile-negative test (no in-place mutation) | AgentMiddlewareTypeContract |
| ToolStep is Kleisli | Requirement: ToolStep is a Kleisli from ToolCallCtx to ToolCallOut | scenario test (HITL raises interrupt, logging observes) | ToolStepSpec |
| ToolStep.passthrough lifts state-oblivious endpoints | Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints | property test (toolstep-passthrough-isomorphism) + scenario test (existing ToolEndpoint lifts) | ToolStepSpec |
| passthrough preserves state exactly | Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints + Scenario: passthrough preserves state exactly | property test (toolstep-passthrough-isomorphism) | ToolStepSpec |
| passthrough with failing endpoint propagates error | Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints + Scenario: passthrough with a failing endpoint | scenario test (error propagates, no state returned) | ToolStepSpec |
| SystemPrompt composes base + sections in order | Requirement: SystemPrompt composes a base string and named sections in order | scenario test (render with base, no base, no sections, empty) | SystemPromptSpec |
| Section name preserved for inspection | Requirement: SystemPrompt composes a base string and named sections in order + Scenario: Section name is preserved for inspection | scenario test (name readable) | SystemPromptSpec |
| promptSections folded per-request from current state | Requirement: promptSections is folded per-request from current harness state | property test (prompt-sections-state-awareness) + scenario test (sections change between iterations) | PromptSectionsSpec |
| Sections in stack order | Requirement: promptSections is folded per-request from current harness state + Scenario: Sections are in stack order | property test (prompt-sections-state-awareness) | PromptSectionsSpec |
| Static-section middleware unaffected by re-folding | Requirement: promptSections is folded per-request from current harness state + Scenario: Static-section middleware is unaffected by re-folding | scenario test | PromptSectionsSpec |
| MiddlewareName is opaque string type | Requirement: MiddlewareName is an opaque string type for middleware identity | type system (opaque type) + compile-negative test (raw String rejected) | AgentMiddlewareTypeContract |
| MiddlewareName used in cell-id formation | Requirement: MiddlewareName is an opaque string type for middleware identity + Scenario: Name used in cell-id formation | scenario test (id is "owner/name") | AgentMiddlewareSpec |
| MiddlewareName used in stack-error attribution | Requirement: MiddlewareName is an opaque string type for middleware identity + Scenario: Name used in stack-error attribution | scenario test (error names owners) | AgentMiddlewareSpec |
| AgentMiddleware.id is identity | Requirement: AgentMiddleware.id is the identity middleware | scenario test (all hooks/contributions are default) + property test (default-neutrality) | AgentMiddlewareSpec |
| Identity inert at any position | Requirement: AgentMiddleware.id is the identity middleware + Scenario: Identity inserted at any position is inert | property test (default-neutrality) | AgentMiddlewareSpec |
| All-defaults middleware ≍ identity | Requirement: Default-neutrality — all-defaults middleware is observationally equivalent to identity | property test (default-neutrality) | AgentMiddlewareSpec |
| All-defaults matches identity on traces | Requirement: Default-neutrality — all-defaults middleware is observationally equivalent to identity + Scenario: All-defaults middleware matches identity on traces | property test (default-neutrality) | AgentMiddlewareSpec |
| wrap-model-call composes outermost-first | Requirement: wrap-model-call composes as outermost-first nesting | property test (hook-distribution-wrap-model-call) | AgentMiddlewareSpec |
| Two-middleware nesting by trace | Requirement: wrap-model-call composes as outermost-first nesting + Scenario: Two-middleware nesting order by trace | property test (hook-distribution-wrap-model-call) | AgentMiddlewareSpec |
| Empty stack is the base step | Requirement: wrap-model-call composes as outermost-first nesting + Scenario: Empty stack is the base step | scenario test | AgentMiddlewareSpec |
| passthrough state-preservation (Ring 6) | Requirement: ToolStep.passthrough lifts state-oblivious tool endpoints | formal contract (Ring 6 verified mirror) + bridge property test | MiddlewareKernelBridgeSpec |
| Immutable case classes (ModelRequest etc.) | Compile-Negative: in-place mutation of ModelRequest/ModelResponse/ToolCallCtx/ToolCallOut/SystemPrompt/PromptSection | compile-negative test (assertDoesNotCompile) | AgentMiddlewareTypeContract |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `adk4s-harness-api` module | sbt module | `build.sbt` | new module: `.dependsOn(adk4s-core)`, deps catsCore/catsEffectKernel/catsData/hedgehogMunit (test), aggregated by root |
| `AgentMiddleware` | trait | `adk4s-harness-api/src/main/scala/org/adk4s/harness/AgentMiddleware.scala` | effect-polymorphic middleware trait with 4 hooks + tools + stateCells + promptSections(state); companion has `id` |
| `MiddlewareName` | opaque type | `adk4s-harness-api/src/main/scala/org/adk4s/harness/MiddlewareName.scala` | `opaque type MiddlewareName = String` with constructor + `.value` accessor |
| `ModelRequest` / `ModelResponse` | case class | `adk4s-harness-api/src/main/scala/org/adk4s/harness/ModelRequest.scala` | per-request model call payload + response; `ModelRequest[F[_]]` carries `F`-polymorphic tool list |
| `ModelStep` | type alias | `adk4s-harness-api/src/main/scala/org/adk4s/harness/ModelStep.scala` | `type ModelStep[F[_]] = Kleisli[F, ModelRequest[F], ModelResponse]` |
| `ToolCallCtx` / `ToolCallOut` | case class | `adk4s-harness-api/src/main/scala/org/adk4s/harness/ToolCallCtx.scala` | state-threading tool execution payload + response |
| `ToolStep` | type alias + companion | `adk4s-harness-api/src/main/scala/org/adk4s/harness/ToolStep.scala` | `type ToolStep[F[_]] = Kleisli[F, ToolCallCtx, ToolCallOut]`; companion has `passthrough[F: Functor]` |
| `SystemPrompt` / `PromptSection` | case class | `adk4s-harness-api/src/main/scala/org/adk4s/harness/SystemPrompt.scala` | composed prompt with `render: String`; `PromptSection(name: String, body: String)` |
| `MiddlewareKernel` | verified mirror | `verified/src/main/scala/org/adk4s/verified/MiddlewareKernel.scala` | PureScala model (Scala 3.7.2) for `passthrough` state-preservation contract |
| `MiddlewareKernelBridgeSpec` | bridge test | `adk4s-harness-api/src/test/scala/org/adk4s/harness/MiddlewareKernelBridgeSpec.scala` | Hedgehog bridge: runs real `passthrough` and model on same inputs |
| `AgentMiddlewareSpec` | test suite | `adk4s-harness-api/src/test/scala/org/adk4s/harness/AgentMiddlewareSpec.scala` | scenario tests + Hedgehog properties (default-neutrality, hook-distribution) |
| `AgentMiddlewareTypeContract` | compile-negative test | `adk4s-harness-api/src/test/scala/org/adk4s/harness/AgentMiddlewareTypeContract.scala` | `assertDoesNotCompile` for forbidden constructions |
| `SystemPromptSpec` | test suite | `adk4s-harness-api/src/test/scala/org/adk4s/harness/SystemPromptSpec.scala` | render-order scenario tests |
| `ToolStepSpec` | test suite | `adk4s-harness-api/src/test/scala/org/adk4s/harness/ToolStepSpec.scala` | passthrough isomorphism property + scenario tests |
