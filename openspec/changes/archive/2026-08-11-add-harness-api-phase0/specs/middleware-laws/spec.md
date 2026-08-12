# Spec: Middleware Laws

<!-- This is a DELTA spec for the `add-harness-api-phase0` change. It
     introduces the `adk4s-harness-testkit` module — a reusable law testkit
     containing `AgentMiddlewareLaws` (L0–L11), `SemilatticeLaws`, a
     deterministic `ChatModel` double, and Hedgehog generators — in the
     `AgentMemoryLaws` style. The laws are the statement that the middleware
     monoid structure is observationally real, not just syntactic. L0 is the
     gatekeeper for merging the `ReactAgent` → `HarnessAgent` refactor at all.

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
| ReactAgent | The L0 gatekeeper law asserts observational equivalence between the empty-stack harness and the current ReactAgent | [react-agent.md](../../../concepts/react-agent.md) |
| ChatModel | Doubled by `DeterministicChatModel` to drive observational-equivalence checks deterministically | [chat-model.md](../../../concepts/chat-model.md) |
| Tool | The fixed tool set used by the testkit's law properties | [tool.md](../../../concepts/tool.md) |
| agent-middleware (NEW — created by agent-middleware spec) | The middleware values that L1–L6 laws compose and observe | `openspec/concepts/agent-middleware.md` (created at apply Step 12) |
| harness-state (NEW — created by harness-state spec) | The state substrate that L7–L11 laws test (codec round-trip, restore leniency, privacy, semilattice merge) | `openspec/concepts/harness-state.md` (created at apply Step 12) |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AgentMemory[F[_]]` | service trait | `org.adk4s.memory` |
| `AgentMemoryLaws` | testkit | `org.adk4s.memory` |
| `ChatModel[F[_]]` | service trait | `org.adk4s.core.component` |
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` |
| `HarnessState` | final class | `org.adk4s.harness` |
| `StateCell[A]` | final class | `org.adk4s.harness` |
| `AgentMiddleware[F[_]]` | trait | `org.adk4s.harness` |
| `MiddlewareStack[F]` | final case class | `org.adk4s.harness` |
| `HarnessAgent[F[_]]` | final class | `org.adk4s.orchestration.agent` |
| `HarnessResult` | sealed trait | `org.adk4s.orchestration.agent` |

> `AgentMemoryLaws` is the precedent for the capability-module + testkit
> pattern that `adk4s-harness-testkit` follows: a class parameterized by the
> thing under test, producing Hedgehog properties, runnable by any downstream
> middleware author. `ChatModel[F]` is doubled by the testkit's deterministic
> mock to enable L0 observational equivalence. `HarnessState`, `StateCell`,
> `AgentMiddleware`, `MiddlewareStack`, `HarnessAgent`, and `HarnessResult`
> are the types under test, introduced by the sibling specs in this change.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `AgentMiddlewareLaws` | testkit class | L0–L11 Hedgehog properties in the `AgentMemoryLaws` style; parameterized by a stack and deterministic model double; each law is a `Property` runnable by downstream middleware authors |
| `SemilatticeLaws` | testkit property | Commutativity, associativity, and idempotence of `cell.merge` for parallel-shared cells; `mergeBack` order-independence over permutations of children |
| `DeterministicChatModel` | test double | Deterministic `ChatModel` double for L0 observational equivalence: given a fixed tool set and input, produces a fixed `Completion`; records request traces at the base step |

## ADDED Requirements

### Requirement: Deterministic ChatModel double enables observational equivalence

The testkit SHALL provide a deterministic `ChatModel` double that, given a fixed
tool set and input conversation, produces a fixed `Completion` and records the
request trace (system prompt, tool list, messages) at the base step. The double
SHALL be deterministic across repeated runs with the same seed and SHALL NOT
depend on wall-clock time or external I/O.

**Given** a `DeterministicChatModel` constructed with a fixed seed and a
scripted response sequence
**When** the same input conversation and tool set are submitted twice
**Then** both calls produce equal `Completion` values AND equal recorded request
traces (system prompt, tool list, messages at the base step).

**Rationale**: L0–L11 are stated in terms of observational equivalence (`≍`),
which means: driven by the testkit's deterministic `ChatModel` double and a
fixed tool set and input, both sides produce equal final `AssistantMessage`,
equal final `HarnessState.snapshot`, and equal request traces at the base step.
Without a deterministic double, the laws are untestable — real LLM calls are
non-deterministic. The double is the foundation of the entire law testkit.

#### Scenario: Deterministic double produces equal results across repeated runs

**Given** a `DeterministicChatModel` with seed 42 and a 3-message conversation
**When** `generate` is called twice with the same conversation and tool set
**Then** both results are equal (`Completion` equality) and both recorded traces
are equal (same system prompt, same tool names in the same order, same messages).

#### Scenario: Double records the base-step request trace

**Given** a `DeterministicChatModel` and a `ModelRequest` with system prompt
"S", tools [t1, t2], and messages [m1, m2]
**When** the model step is run
**Then** the double's recorded trace contains exactly one entry with system
prompt "S", tool names ["t1", "t2"], and messages [m1, m2] — the trace is the
observable for L3 hook distribution and L0 equivalence.

#### Scenario: Double handles tool-call responses deterministically

**Given** a `DeterministicChatModel` scripted to return a `Completion` with one
`ToolCall` on the first call and a final text response on the second
**When** the harness loop drives the model through two iterations
**Then** the first `Completion` contains the tool call, the second contains the
final text, and both are deterministic across repeated runs with the same seed.

### Requirement: L0 Conservative refactor equivalence (gatekeeper)

The testkit SHALL provide an L0 property asserting that
`HarnessAgent(MiddlewareStack.empty)` is observationally equivalent (`≍`) to the
current `ReactAgentImpl` on the deterministic `ChatModel` double, across
generated conversations, tool behaviors (including interrupts), and step
budgets. This property SHALL be the gatekeeper: the refactor SHALL NOT merge
until L0 is green.

**Note (testkit-level L0)**: The `adk4s-harness-testkit` module MUST NOT depend
on `adk4s-orchestration` (where `ReactAgentImpl` lives), so the testkit-level
L0 property compares `SimpleHarnessLoop.run(MiddlewareStack.empty, ...)` against
`SimpleHarnessLoop.runBaseline(...)` — the loop's no-middleware baseline path.
This is a loop no-op property: it proves the empty middleware stack introduces
no observable change relative to the baseline loop. The full
`HarnessAgent`-vs-`ReactAgentImpl` equivalence is tested at the orchestration
level (in `adk4s-orchestration` tests, which can depend on both modules) and is
the true gatekeeper. The testkit-level L0 is a necessary precondition: if the
empty stack is not a no-op within the loop, the full equivalence cannot hold.

**Given** a `HarnessAgent` with `MiddlewareStack.empty` and a `ReactAgentImpl`
constructed with the same base model, base tools, base prompt, and max steps
**When** both are driven by the `DeterministicChatModel` double on the same
generated conversation, tool behavior, and step budget
**Then** both produce equal final `AssistantMessage`, equal final
`HarnessState.snapshot` (which is the empty snapshot for the empty stack), and
equal request traces at the base step.

**Rationale**: The `ReactAgent` → `HarnessAgent` refactor touches the core path
used by 55+ examples. L0 is the proof that the empty stack is provably
equivalent to today's agent — not a promise, a property. Without it, the refactor
is an uncontrolled regression risk.

#### Scenario: Empty-stack harness matches ReactAgentImpl on a simple conversation

**Given** a 2-turn conversation with no tool calls and the deterministic model
returning a final text response
**When** both the empty-stack `HarnessAgent` and `ReactAgentImpl` run with
maxSteps = 5
**Then** the final `AssistantMessage` content is equal, the
`HarnessState.snapshot` is the empty `DObject`, and the request traces match.

#### Scenario: Empty-stack harness matches ReactAgentImpl with tool calls

**Given** a conversation where the model returns a tool call on iteration 1 and
a final response on iteration 2, with one `InvokableTool` that returns a fixed
output
**When** both run with maxSteps = 10
**Then** the final `AssistantMessage`, the `HarnessState.snapshot`, and the
request traces are all equal between the two agents.

#### Scenario: Empty-stack harness matches ReactAgentImpl under interrupt

**Given** a tool that raises an `InterruptSignal.Stateful` on its first
invocation
**When** both the empty-stack `HarnessAgent` and `ReactAgentImpl` run
**Then** both produce an interrupted `HarnessResult`/`RunResult` with the same
interrupt signal, and the state snapshots (if any) are equal. This scenario
MUST be driven with `TestControl` for deterministic concurrency.

#### Scenario: Empty-stack harness matches ReactAgentImpl under step budget exhaustion

**Given** a conversation where the model always returns a tool call (never
terminates) and maxSteps = 3
**When** both run
**Then** both exhaust the step budget and produce the same final
`AssistantMessage` (the last model output) and equal traces — the step budget
is an observable that must match.

### Requirement: L1 Monoid identity

The testkit SHALL provide an L1 property asserting that for any validated stack
`S` and any insertion position, `insert(S, AgentMiddleware.id)` is
observationally equivalent (`≍`) to `S`.

**Given** a validated `MiddlewareStack` `S` and a position `p` (head, middle, or
tail)
**When** `AgentMiddleware.id` is inserted into `S` at position `p` and both
stacks are driven by the deterministic model on the same generated conversation
**Then** the resulting `HarnessAgent` runs produce equal final
`AssistantMessage`, equal final `HarnessState.snapshot`, and equal request
traces at the base step.

**Rationale**: `AgentMiddleware.id` overrides nothing and contributes no cells,
tools, or sections. Inserting it anywhere in a stack MUST be a no-op
observationally — this is the identity law of the middleware monoid.

#### Scenario: Identity inserted at head

**Given** a stack `[m1, m2]` and `AgentMiddleware.id` inserted at head yielding
`[id, m1, m2]`
**When** both `[m1, m2]` and `[id, m1, m2]` are driven on the same conversation
**Then** the results are observationally equivalent.

#### Scenario: Identity inserted at tail

**Given** a stack `[m1, m2]` and `AgentMiddleware.id` inserted at tail yielding
`[m1, m2, id]`
**When** both stacks are driven on the same conversation
**Then** the results are observationally equivalent.

#### Scenario: Identity inserted into empty stack

**Given** `MiddlewareStack.empty` and `AgentMiddleware.id` inserted yielding
`[id]`
**When** both `[]` and `[id]` are driven on the same conversation
**Then** the results are observationally equivalent — the identity is a no-op
even when it is the sole element.

### Requirement: L2 Monoid associativity

The testkit SHALL provide an L2 property asserting that for any three validated
stacks `a`, `b`, `c`, `(a ++ b) ++ c` is observationally equivalent (`≍`) to
`a ++ (b ++ c)`.

**Given** three validated `MiddlewareStack` values `a`, `b`, `c`
**When** `(a ++ b) ++ c` and `a ++ (b ++ c)` are both driven by the deterministic
model on the same generated conversation
**Then** both produce equal final `AssistantMessage`, equal final
`HarnessState.snapshot`, and equal request traces at the base step.

**Rationale**: Associativity is true syntactically for the fold-based
combinators, but the property pins it observationally so future
stack-implementation changes cannot break it silently.

#### Scenario: Three non-empty stacks associate

**Given** three stacks each with one middleware contributing one cell and one
tool
**When** `(a ++ b) ++ c` and `a ++ (b ++ c)` are driven on the same conversation
**Then** the results are observationally equivalent.

#### Scenario: Associativity with an empty stack

**Given** `a` non-empty, `b = MiddlewareStack.empty`, `c` non-empty
**When** `(a ++ b) ++ c` and `a ++ (b ++ c)` are driven
**Then** the results are observationally equivalent — the empty stack is the
monoid identity, so both sides reduce to `a ++ c`.

### Requirement: L3 Hook distribution

The testkit SHALL provide an L3 property asserting that for a two-element stack
`[m1, m2]`, `stack.wrapModelCall(base)` equals
`m1.wrapModelCall(m2.wrapModelCall(base))` (and likewise for `wrapToolCall`),
tested by request-trace equality.

**Given** a two-element stack `[m1, m2]` where each middleware wraps the model
call with a trace-recording layer
**When** `stack.wrapModelCall(base)` is run on a generated `ModelRequest`
**Then** the recorded trace shows `m1` as the outermost wrapper (sees the request
first, the response last) and `m2` as the inner wrapper, matching
`m1.wrapModelCall(m2.wrapModelCall(base))` element-for-element.

**Rationale**: The stack-order semantics for `wrapModelCall` and `wrapToolCall`
are that `m1` is outermost — `m1(m2(base))`. This is the distribution law that
makes the stack's fold-based composition observationally real.

#### Scenario: Model call wrapping order

**Given** `m1` prepends "A" to the system prompt and `m2` prepends "B"
**When** `stack.wrapModelCall(base)` runs on a request with system prompt "base"
**Then** the base step receives system prompt "ABbase" (m1 outermost, m2 inner),
and the trace records the wrapping order as [m1, m2].

#### Scenario: Tool call wrapping order

**Given** `m1` logs "before-m1"/"after-m1" and `m2` logs "before-m2"/"after-m2"
around tool execution
**When** `stack.wrapToolCall(base)` runs on a tool call
**Then** the log order is "before-m1", "before-m2", [tool executes],
"after-m2", "after-m1" — m1 is outermost.

#### Scenario: Single-element stack is the identity for distribution

**Given** a one-element stack `[m1]`
**When** `stack.wrapModelCall(base)` runs
**Then** the trace equals `m1.wrapModelCall(base)` directly — no extra wrapping
layer is introduced by the stack.

### Requirement: L4 Default neutrality

The testkit SHALL provide an L4 property asserting that a middleware overriding
nothing (all defaults, empty contributions) is observationally equivalent (`≍`)
to `AgentMiddleware.id`.

**Given** a middleware `m` with `stateCells = Nil`, `tools = Nil`,
`promptSections(_) = Nil`, and all hooks returning their default (identity)
behavior
**When** `[m]` and `[AgentMiddleware.id]` are driven by the deterministic model
on the same generated conversation
**Then** both produce equal final `AssistantMessage`, equal final
`HarnessState.snapshot`, and equal request traces at the base step.

**Rationale**: A middleware that overrides nothing IS the identity. This law
catches bugs where a default override accidentally mutates state or rewrites the
request.

#### Scenario: All-defaults middleware equals identity

**Given** a middleware constructed with only a `name` and all other members at
their defaults
**When** `[m]` and `[id]` are driven on a 2-turn conversation with a tool call
**Then** the results are observationally equivalent.

#### Scenario: Middleware with empty cells but non-trivial beforeAgent is NOT neutral (adversarial)

**Given** a middleware with `stateCells = Nil` but `beforeAgent` that sets a
cell declared by another middleware in the stack
**When** `[m]` is compared to `[id]`
**Then** the results are NOT observationally equivalent (the `beforeAgent` hook
mutated state) — this confirms the law is sensitive to non-default behavior, not
trivially true.

### Requirement: L5 Cell frame rule

The testkit SHALL provide an L5 property asserting that for a generated
middleware `m` whose hooks are pure state transitions touching only
`m.stateCells`, and any cell `c` not in `m.stateCells`, the value of `c` after
any run equals its value before.

**Given** a generated middleware `m` with pure `beforeAgent`/`afterAgent` hooks
that read and write only cells in `m.stateCells`, and a `HarnessState` `s`
containing a cell `c` not declared by `m`
**When** `m.beforeAgent(s)` and `m.afterAgent(s)` are run
**Then** `resultingState.get(c) == s.get(c)` — the value of `c` is unchanged.

**Rationale**: A middleware MUST NOT write to cells it does not declare. Phase 0
enforces this as a tested property over stack-authored middlewares rather than
by construction; the capability-based alternative (Scala 3 capture checking) is
deferred (design §9).

#### Scenario: Middleware with one cell does not touch another middleware's cell

**Given** middleware `m` declaring cell `x` (an `Int` counter) with
`beforeAgent` that increments `x`, and a state `s` containing both `x` and cell
`y` (a `String` declared by a different middleware)
**When** `m.beforeAgent(s)` runs
**Then** `resultingState.get(x) == s.get(x) + 1` AND `resultingState.get(y) ==
s.get(y)` — `y` is untouched.

#### Scenario: Middleware with no cells touches nothing (adversarial)

**Given** a middleware `m` with `stateCells = Nil` and a `beforeAgent` that
attempts to `set` a cell `z` it does not declare
**When** `m.beforeAgent(s)` runs on a state containing `z`
**Then** `resultingState.get(z) != s.get(z)` — the frame rule is VIOLATED, and
the property reports failure. This confirms the property detects cross-cell
writes, not just trivially passes.

#### Scenario: afterAgent also preserves the frame

**Given** the same middleware `m` and state `s` as the first scenario
**When** `m.afterAgent(s)` runs
**Then** `resultingState.get(y) == s.get(y)` — the frame rule holds for
`afterAgent` as well as `beforeAgent`.

### Requirement: L6 Disjoint commutativity (conditional)

The testkit SHALL provide an L6 property asserting that if `m1` and `m2` have
disjoint `stateCells`, contribute no overlapping tools or sections (for any
tested state), do not rewrite requests, and their `beforeAgent`/`afterAgent` are
pure state transitions lifted into `F`, then `[m1, m2]` is observationally
equivalent (`≍`) to `[m2, m1]`.

**Given** two middlewares `m1`, `m2` satisfying all preconditions (disjoint
cells, no overlapping tools/sections, no request rewriting, pure hooks)
**When** `[m1, m2]` and `[m2, m1]` are driven by the deterministic model on the
same generated conversation
**Then** both produce equal final `AssistantMessage`, equal final
`HarnessState.snapshot`, and equal request traces at the base step.

**Rationale**: The preconditions are honest: arbitrary `F`-effects (logging
order) and request rewriting are inherently order-sensitive. The law's value is
precisely that it delimits WHEN reordering a stack is safe. Section overlap is
checked per-state because `promptSections` is state-aware; two middlewares whose
sections are disjoint for the tested states but would overlap for some untested
state are out of scope of this property.

#### Scenario: Two disjoint pure middlewares commute

**Given** `m1` declaring cell `a` (Int, incremented in `beforeAgent`) and `m2`
declaring cell `b` (String, uppercased in `beforeAgent`), no overlapping tools
or sections, no request rewriting
**When** `[m1, m2]` and `[m2, m1]` are driven on the same conversation
**Then** the final `HarnessState.snapshot` has `a` incremented and `b`
uppercased in both cases, and the request traces are equal.

#### Scenario: Middlewares with overlapping cells do NOT commute (adversarial)

**Given** `m1` and `m2` both declaring cell `a` with `beforeAgent` that sets `a`
to different values
**When** `[m1, m2]` and `[m2, m1]` are driven
**Then** the final `HarnessState.get(a)` differs between the two orderings —
the preconditions are violated and the property correctly does NOT assert
equivalence.

#### Scenario: Middlewares that rewrite requests do NOT commute (adversarial)

**Given** `m1` prepends "A" to the system prompt and `m2` prepends "B"
**When** `[m1, m2]` and `[m2, m1]` are driven
**Then** the base-step system prompts are "ABbase" vs "BAbase" — the request
rewriting makes the order observable, and the property correctly does NOT assert
equivalence.

### Requirement: L7 Codec round-trip

The testkit SHALL provide an L7 property asserting that for every declared cell
and generated value `a`, `read(rw)(write(rw)(a)) == a`; and for generated states
over the declared cells, `restore(cells, snapshot(s)) == Right(s)` up to
absent-equals-initial.

**Given** a generated `StateCell[A]` with a `ReadWriter[A]` and a generated
value `a: A`
**When** `write(rw)(a)` is serialized and then `read(rw)` is applied to the
result
**Then** the decoded value equals `a`.

**Given** a generated `HarnessState` `s` over a declared cell list `cells`
**When** `snapshot(s)` is taken and `restore(cells, snapshot(s))` is called
**Then** the result is `Right(s')` where `s'` equals `s` (every cell's value
round-trips; absent cells read as `initial` which equals their value in `s` if
they were absent).

**Rationale**: The codec is mandatory at cell declaration, which makes
snapshotting unconditionally possible. L7 verifies the codec actually round-trips
— a cell whose `ReadWriter` silently drops a field would break checkpoint
resume.

#### Scenario: Int cell round-trips

**Given** a `StateCell[Int]` with the built-in `ReadWriter[Int]` and value 42
**When** `write` then `read` round-trips
**Then** the result is `Right(42)`.

#### Scenario: State with multiple cells round-trips through snapshot/restore

**Given** a `HarnessState` with three cells (an `Int`, a `String`, a
`List[Boolean]`) all at non-initial values
**When** `snapshot(s)` then `restore(cells, snapshot(s))` round-trips
**Then** the result is `Right(s')` where `s'.get(cell) == s.get(cell)` for all
three cells.

#### Scenario: Cell at initial value round-trips as absent

**Given** a `HarnessState` where cell `c` is at its `initial` value (not
explicitly set)
**When** `snapshot(s)` then `restore(cells, snapshot(s))` round-trips
**Then** `s'.get(c) == c.initial == s.get(c)` — the absent-equals-initial
semantics makes the round-trip hold even when the cell is not in the snapshot.

### Requirement: L8 Restore leniency

The testkit SHALL provide an L8 property asserting that
`restore(cells, snapshot(s) ++ unknownFields)` succeeds and ignores the
unknowns; and `restore(cells ++ newCells, snapshot(s))` succeeds with new cells
at `initial`.

**Given** a `HarnessState` `s` over declared cells `cells`, and a set of
unknown field names with arbitrary `JsonValue` values
**When** `restore(cells, snapshot(s) ++ unknownFields)` is called
**Then** the result is `Right(s')` where `s'` equals `s` — the unknown fields
are silently ignored.

**Given** a `HarnessState` `s` over declared cells `cells`, and additional cells
`newCells` not present in `s`
**When** `restore(cells ++ newCells, snapshot(s))` is called
**Then** the result is `Right(s')` where `s'.get(c) == s.get(c)` for `c` in
`cells` and `s'.get(nc) == nc.initial` for `nc` in `newCells`.

**Rationale**: Restoring a checkpoint written by an older stack (fewer cells) or
newer stack (more cells) MUST NOT fail. This is forward compatibility (R5) —
unknown ids are ignored, missing cells default to `initial`.

#### Scenario: Unknown fields ignored on restore

**Given** a snapshot of `{ "owner/x": 42 }` and a declared cell list `[x]`, plus
unknown fields `{ "stranger/y": "ghost" }`
**When** `restore([x], snapshot ++ unknownFields)` is called
**Then** the result is `Right(s')` with `s'.get(x) == 42` and no error — the
unknown field "stranger/y" is ignored.

#### Scenario: New cells default to initial on restore

**Given** a snapshot of `{ "owner/x": 42 }` and a declared cell list `[x, y]`
where `y` is a new `String` cell with `initial = "default"`
**When** `restore([x, y], snapshot)` is called
**Then** the result is `Right(s')` with `s'.get(x) == 42` and
`s'.get(y) == "default"` — `y` was absent from the snapshot and defaults to
initial.

#### Scenario: Corrupted cell value is a hard error (adversarial)

**Given** a snapshot where `"owner/x"` contains a JSON string but `x` is declared
as `StateCell[Int]`
**When** `restore([x], snapshot)` is called
**Then** the result is `Left(StateDecodeError(...))` — a corrupted checkpoint is
a hard failure, not silent data loss. The error names the cell id and the codec
failure.

### Requirement: L9 Privacy

The testkit SHALL provide an L9 property asserting that for a `Private` cell `p`:
`project(parent, cells).get(p) == p.initial`, and
`mergeBack(parent, children, cells).get(p) == parent.get(p)` for all children.

**Given** a parent `HarnessState` where `Private` cell `p` has value `v` (not
equal to `p.initial`)
**When** `project(parent, cells)` is called
**Then** the child state's `get(p) == p.initial` — the child sees the initial
value, not the parent's private value.

**Given** the same parent and a list of child states where children have written
arbitrary values to `p`
**When** `mergeBack(parent, children, cells)` is called
**Then** the result's `get(p) == parent.get(p) == v` — the parent's private value
is preserved; child writes to `p` are discarded.

**Rationale**: Privacy is structural. A `Private` cell's parent value is
unobservable by the child (it reads `initial`), and the child's writes to it are
unobservable by the parent. Both directions hold by construction.

#### Scenario: Child sees initial for Private cell

**Given** parent state with `Private` cell `p` (initial = 0) set to 99, and
`cells = [p]`
**When** `project(parent, [p])` is called
**Then** `child.get(p) == 0` — the child sees `p.initial`, not 99.

#### Scenario: Parent's Private value preserved after mergeBack

**Given** parent state with `p = 99`, and two children where child1 set `p = 1`
and child2 set `p = 2`
**When** `mergeBack(parent, [child1, child2], [p])` is called
**Then** `result.get(p) == 99` — the parent's value is unchanged; child writes
to `p` are discarded.

#### Scenario: Child writes to Private cell are unobservable (adversarial)

**Given** a child that aggressively writes `p = 999` during its run
**When** `mergeBack(parent, [child], [p])` is called
**Then** `result.get(p) == parent.get(p)` — no matter what the child writes, the
parent's `Private` value is untouched.

### Requirement: L10 Merge-back neutrality

The testkit SHALL provide an L10 property asserting that for untouched children,
`mergeBack(parent, List(project(parent, cells)), cells)` is observationally
equivalent (`≍`) to `parent` — which holds for `Shared` cells exactly when
`merge` is idempotent.

**Given** a parent `HarnessState` and a cell list `cells` including at least one
`Shared` cell with an idempotent `merge`
**When** a child is projected from the parent, runs without modifying any cell,
and is merged back via `mergeBack(parent, List(project(parent, cells)), cells)`
**Then** the result is observationally equivalent to `parent` — every cell value
equals its value in `parent`.

**Rationale**: A child that touches nothing, when projected and merged back,
MUST leave the parent unchanged. For `Shared` cells this holds exactly when
`merge` is idempotent (`merge(a, a) == a`), tying L10 to the semilattice
requirement (L11).

#### Scenario: Untouched child round-trips to parent

**Given** parent with `Shared` cell `s` (merge = union, value = Set(1, 2)) and
`Private` cell `p` (value = 99)
**When** `project(parent, [s, p])` produces a child, the child modifies nothing,
and `mergeBack(parent, [child], [s, p])` is called
**Then** `result.get(s) == Set(1, 2)` (union of equal sets is idempotent) and
`result.get(p) == 99` — the parent is unchanged.

#### Scenario: Non-idempotent merge breaks neutrality (adversarial)

**Given** parent with `Shared` cell `s` (merge = `(a, b) => a + b`, value = 5)
**When** a child is projected (child sees `s = 5`), modifies nothing, and
`mergeBack(parent, [child], [s])` is called
**Then** `result.get(s) == 10` (5 + 5) — the parent is NOT unchanged, because
the merge is not idempotent. This confirms the law is sensitive to the
idempotence precondition.

### Requirement: L11 Semilattice laws for parallel-shared cells

The testkit SHALL provide `SemilatticeLaws` asserting that for any `Shared` cell
with a `merge` function intended for parallel delegation, `merge` is commutative
(`merge(a, b) == merge(b, a)`), associative
(`merge(a, merge(b, c)) == merge(merge(a, b), c)`), and idempotent
(`merge(a, a) == a`). The testkit SHALL also assert that `mergeBack` over
permutations of children is order-independent when all `Shared` cells satisfy
the semilattice laws.

**Given** a `Shared` `StateCell[A]` with merge function `m` and three generated
values `a`, `b`, `c`
**When** the three semilattice laws are checked
**Then** `m(a, b) == m(b, a)` AND `m(a, m(b, c)) == m(m(a, b), c)` AND
`m(a, a) == a`.

**Given** a parent state, a list of N child states, and a `Shared` cell with a
semilattice merge
**When** `mergeBack` is called with the children in their original order and
with a random permutation of the children
**Then** both calls produce equal `HarnessState` values — the merge is
order-independent.

**Rationale**: deepagents runs `task` calls in parallel; merging N concurrently
produced child states into the parent MUST NOT depend on completion order. This
is the CRDT discipline applied to harness state. The semilattice laws are
checkable: `SemilatticeLaws` runs against any shared cell. This is a
CONCURRENT behavior property and MUST be tested with `TestControl` for
deterministic execution.

#### Scenario: Union-merge is a semilattice

**Given** a `Shared` cell with `merge = (a, b) => a | b` (set union) and
generated sets `a = Set(1)`, `b = Set(2)`, `c = Set(1, 3)`
**When** the three laws are checked
**Then** commutativity: `a | b == b | a == Set(1, 2)`; associativity:
`(a | b) | c == a | (b | c) == Set(1, 2, 3)`; idempotence: `a | a == a ==
Set(1)`.

#### Scenario: mergeBack is order-independent over permutations

**Given** a parent with `Shared` cell `s` (merge = union, value = Set(0)), and
three children with `s` values `Set(1)`, `Set(2)`, `Set(3)`
**When** `mergeBack(parent, [c1, c2, c3], [s])` and
`mergeBack(parent, [c3, c1, c2], [s])` are called
**Then** both produce `result.get(s) == Set(0, 1, 2, 3)` — the merge is
order-independent. This scenario MUST be driven with `TestControl`.

#### Scenario: Non-commutative merge fails the semilattice laws (adversarial)

**Given** a `Shared` cell with `merge = (a, b) => a ++ b` (list concatenation,
which is NOT commutative) and values `a = List(1)`, `b = List(2)`
**When** the commutativity law is checked
**Then** `merge(a, b) == List(1, 2)` but `merge(b, a) == List(2, 1)` — the law
FAILS, correctly flagging this merge as unsuitable for parallel delegation.

### Requirement: Testkit module publication in main scope

The system SHALL provide an `adk4s-harness-testkit` sbt module with
`dependsOn(adk4s-harness-api)`, `cats-effect` and `hedgehog-munit` in **main**
scope (not Test), so a downstream middleware author can add
`libraryDependencies += "org.adk4s" %% "adk4s-harness-testkit" % version` and
import `AgentMiddlewareLaws` directly. The testkit SHALL follow the
`AgentMemoryLaws` precedent: the laws class is a main-scope API, not a Test-scope
export.

**Given** a downstream project with
`libraryDependencies += "org.adk4s" %% "adk4s-harness-testkit" % "0.1.0-SNAPSHOT"`
**When** the downstream project compiles a test that imports
`org.adk4s.harness.testkit.AgentMiddlewareLaws`
**Then** the import resolves and the test compiles.

**Rationale**: A `-testkit` sibling module (main-scoped) is preferred over
`Test` scope export / `sbt-testkit` gymnastics because it keeps the downstream
dependency a plain `libraryDependencies` line — the same reasoning that produced
`adk4s-memory-testkit`.

#### Scenario: Testkit module compiles independently

**Given** the testkit module is declared as a separate sbt subproject
**When** the testkit module is compiled
**Then** it compiles without requiring the harness-api module's test sources.

#### Scenario: Testkit module has no heavy deps

**Given** the testkit module's dependency tree
**When** the tree is inspected
**Then** the tree contains no `neo4j`, `lucene`, `http4s`, or database drivers
beyond what the harness-api module already pulls.

#### Scenario: Testkit main scope uses munit main-scope (not Test)

**Given** the testkit module's build configuration
**When** the dependency configuration is inspected
**Then** `munit` and `hedgehog-munit` are in the main scope (not `% Test`),
matching the memory-testkit precedent — `AgentMiddlewareLaws` is a
downstream-consumable main API.

## Properties (Ring 3)

### Property: L0-conservative-refactor

**Invariant**: For all generated conversations, tool behaviors (including
interrupts), and step budgets, `HarnessAgent(MiddlewareStack.empty)` is
observationally equivalent to `ReactAgentImpl` on the deterministic `ChatModel`
double — equal final `AssistantMessage`, equal final `HarnessState.snapshot`,
equal request traces at the base step.

**Generator strategy**: `genConversation` (constructive — a non-empty list of
`UserMessage`/`AssistantMessage` with `genContent` text, Hedgehog
`Range.linear 1 6`); `genToolBehavior` (constructive — one of: fixed-output,
raises `InterruptSignal.Stateful`, raises a generic error); `genMaxSteps`
(`Range.linear 1 20`); `genToolSet` (constructive — `Gen.list genInvokableTool
Range.linear 0 4`). The `DeterministicChatModel` is scripted to return tool
calls or final text per the generated tool behavior. Edge cases: empty tool set,
single-turn conversation, maxSteps = 1 (immediate exhaustion), interrupt on
first tool call. `cover` labels: `no-tools` ≥ 20%, `with-tools` ≥ 30%,
`interrupt` ≥ 20%, `step-exhaustion` ≥ 15%.

```
forAll { (conv: List[Message], toolBehavior: ToolBehavior, maxSteps: Int, tools: List[InvokableTool[IO]]) =>
  val model = DeterministicChatModel(seed = 42L, script = scriptFor(toolBehavior))
  val harnessAgent = HarnessAgent(MiddlewareStack.empty, model, tools, maxSteps)
  val reactAgent = ReactAgentImpl(model, tools, maxSteps)
  for {
    harnessResult <- harnessAgent.generate(conv, maxSteps)
    reactResult  <- reactAgent.generate(conv, maxSteps)
  } yield harnessResult.assistantMessage == reactResult.assistantMessage &&
    harnessResult.state.snapshot == reactResult.stateSnapshot &&
    harnessResult.requestTraces == reactResult.requestTraces
}
```

### Property: L1-monoid-identity

**Invariant**: For all validated stacks `S` and all insertion positions `p`,
`insert(S, AgentMiddleware.id, p)` is observationally equivalent to `S`.

**Generator strategy**: `genStack` (constructive — `Gen.list genMiddleware
Range.linear 0 5`, then `MiddlewareStack.validated`); `genPosition` (`Gen.element`
of `Head`, `Middle`, `Tail`). `genMiddleware` (constructive — generates a
middleware with 0–2 cells, 0–1 tools, 0–1 prompt sections, pure hooks). Edge
cases: empty stack, single-element stack, identity at each position. `cover`:
`empty-stack` ≥ 10%, `head` ≥ 25%, `middle` ≥ 25%, `tail` ≥ 25%.

```
forAll { (stack: MiddlewareStack[IO], pos: Position) =>
  val withId = insert(stack, AgentMiddleware.id[IO], pos)
  for {
    r1 <- runOnDeterministicModel(stack, genConversation)
    r2 <- runOnDeterministicModel(withId, genConversation)
  } yield r1 == r2  // observational equivalence
}
```

### Property: L2-monoid-associativity

**Invariant**: For all validated stacks `a`, `b`, `c`,
`(a ++ b) ++ c` is observationally equivalent to `a ++ (b ++ c)`.

**Generator strategy**: three independent `genStack` draws (same as L1). Edge
cases: one or more stacks empty. `cover`: `all-nonempty` ≥ 50%, `one-empty`
≥ 30%.

```
forAll { (a: MiddlewareStack[IO], b: MiddlewareStack[IO], c: MiddlewareStack[IO]) =>
  val left  = (a ++ b) ++ c
  val right = a ++ (b ++ c)
  for {
    r1 <- runOnDeterministicModel(left, genConversation)
    r2 <- runOnDeterministicModel(right, genConversation)
  } yield r1 == r2
}
```

### Property: L3-hook-distribution

**Invariant**: For all two-element stacks `[m1, m2]`,
`stack.wrapModelCall(base) == m1.wrapModelCall(m2.wrapModelCall(base))` by trace
equality, and likewise for `wrapToolCall`.

**Generator strategy**: two `genTraceMiddleware` draws (constructive — each
middleware records its name in a trace list when wrapping). `genModelRequest`
(constructive — generated system prompt, messages, tools, state). Edge cases:
both middlewares with empty traces, one middleware that rewrites the system
prompt. `cover`: `both-trace` ≥ 40%, `one-rewrites` ≥ 30%.

```
forAll { (m1: TraceMiddleware, m2: TraceMiddleware, req: ModelRequest[IO]) =>
  val stack = MiddlewareStack.validated(List(m1, m2)).toOption.get
  for {
    t1 <- stack.wrapModelCall(base).run(req).map(_.trace)
    t2 <- m1.wrapModelCall(m2.wrapModelCall(base)).run(req).map(_.trace)
  } yield t1 == t2
}
```

### Property: L4-default-neutrality

**Invariant**: For all middlewares `m` overriding nothing (all defaults), `[m]`
is observationally equivalent to `[AgentMiddleware.id]`.

**Generator strategy**: `genAllDefaultsMiddleware` (constructive — generates a
middleware with a random `name` but all other members at defaults:
`stateCells = Nil`, `tools = Nil`, `promptSections = _ => Nil`, hooks return
`state.pure[F]`, `wrapModelCall = identity`, `wrapToolCall = identity`). Edge
cases: middleware with a name but no cells/tools/sections. No `cover` needed
(universal over the single generator).

```
forAll { (m: AgentMiddleware[IO], conv: List[Message]) =>
  val stackM = MiddlewareStack.validated(List(m)).toOption.get
  val stackId = MiddlewareStack.validated(List(AgentMiddleware.id[IO])).toOption.get
  for {
    r1 <- runOnDeterministicModel(stackM, conv)
    r2 <- runOnDeterministicModel(stackId, conv)
  } yield r1 == r2
}
```

### Property: L5-cell-frame-rule

**Invariant**: For all generated middlewares `m` with pure hooks touching only
`m.stateCells`, and all cells `c` not in `m.stateCells`, the value of `c` after
`beforeAgent` and `afterAgent` equals its value before.

**Generator strategy**: `genPureMiddleware` (constructive — generates a
middleware with 1–3 cells in `Range.linear 1 3`, `beforeAgent` and `afterAgent`
that read/write only declared cells via pure state transitions); `genExternalCell`
(constructive — a `StateCell` with a different owner than `m`, not in
`m.stateCells`); `genStateValue` for the external cell's type. Edge cases:
middleware with one cell, external cell of the same type as a declared cell
(confirms no cross-write by type coincidence). `cover`: `single-cell` ≥ 30%,
`multi-cell` ≥ 40%, `same-type-external` ≥ 20%.

```
forAll { (m: AgentMiddleware[IO], externalCell: StateCell[Int], externalValue: Int) =>
  val s = HarnessState.initial(m.stateCells).set(externalCell)(externalValue)
  for {
    s1 <- m.beforeAgent(s)
    s2 <- m.afterAgent(s1)
  } yield s1.get(externalCell) == externalValue && s2.get(externalCell) == externalValue
}
```

### Property: L6-disjoint-commutativity

**Invariant**: For all pairs `m1`, `m2` with disjoint `stateCells`, no
overlapping tools/sections, no request rewriting, and pure hooks,
`[m1, m2] ≍ [m2, m1]`.

**Generator strategy**: `genDisjointMiddlewarePair` (constructive — generates
two middlewares with guaranteed-disjoint cell ids, disjoint tool names, disjoint
section names, pure `beforeAgent`/`afterAgent`, and no `wrapModelCall` override).
Edge cases: one middleware with no cells, both with no tools. `cover`:
`both-with-cells` ≥ 40%, `one-cellless` ≥ 30%.

```
forAll { (pair: (AgentMiddleware[IO], AgentMiddleware[IO]), conv: List[Message]) =>
  val (m1, m2) = pair
  val stack12 = MiddlewareStack.validated(List(m1, m2)).toOption.get
  val stack21 = MiddlewareStack.validated(List(m2, m1)).toOption.get
  for {
    r1 <- runOnDeterministicModel(stack12, conv)
    r2 <- runOnDeterministicModel(stack21, conv)
  } yield r1 == r2
}
```

### Property: L7-codec-round-trip

**Invariant**: For all declared cells with `ReadWriter[A]` and generated values
`a`, `read(rw)(write(rw)(a)) == a`; and for generated states over declared cells,
`restore(cells, snapshot(s)) == Right(s)` up to absent-equals-initial.

**Generator strategy**: `genCellWithValue` (constructive — generates a
`StateCell[A]` for `A` in {Int, String, Boolean, List[Int]} with the built-in
`ReadWriter`, plus a generated value of type `A`); `genState` (constructive —
`HarnessState.initial(cells)` with some cells set to non-initial values via
`genStateMutation`). Edge cases: cell at initial (absent from snapshot), empty
state. `cover`: `Int` ≥ 20%, `String` ≥ 20%, `List` ≥ 20%, `at-initial` ≥ 15%.

```
forAll { (cells: List[StateCell[?]], mutations: List[StateMutation]) =>
  val s = applyMutations(HarnessState.initial(cells), mutations)
  val snap = s.snapshot
  HarnessState.restore(cells, snap) match
    case Right(s2) => cells.forall(c => s2.get(c) == s.get(c))
    case Left(_)   => false
}
```

### Property: L8-restore-leniency

**Invariant**: `restore(cells, snapshot(s) ++ unknownFields)` succeeds ignoring
unknowns; `restore(cells ++ newCells, snapshot(s))` succeeds with new cells at
initial.

**Generator strategy**: `genState` (same as L7); `genUnknownFields`
(constructive — `Gen.map Gen.string genJsonValue Range.linear 0 5`); `genNewCells`
(constructive — `Gen.list genStateCell Range.linear 1 3` with owners not in
`cells`). Edge cases: no unknown fields, no new cells, unknown field with same
name as a declared cell but different type. `cover`: `with-unknown` ≥ 30%,
`with-new-cells` ≥ 30%, `both` ≥ 20%.

```
forAll { (s: HarnessState, cells: List[StateCell[?]], unknowns: Map[String, JsonValue], newCells: List[StateCell[?]]) =>
  val snap = s.snapshot
  val withUnknowns = snap ++ unknowns
  val r1 = HarnessState.restore(cells, withUnknowns)
  val r2 = HarnessState.restore(cells ++ newCells, snap)
  r1.isRight && r1.toOption.get.cells.forall(c => s.get(c) == r1.toOption.get.get(c)) &&
  r2.isRight && newCells.forall(nc => r2.toOption.get.get(nc) == nc.initial)
}
```

### Property: L9-privacy

**Invariant**: For all `Private` cells `p`, `project(parent, cells).get(p) ==
p.initial` and `mergeBack(parent, children, cells).get(p) == parent.get(p)` for
all children.

**Generator strategy**: `genPrivateCellWithValue` (constructive — a `Private`
`StateCell[A]` with `initial` and a non-initial value); `genChildren`
(constructive — `Gen.list genChildState Range.linear 1 4` where each child has
written an arbitrary value to `p`); `genMixedCells` (constructive —
`Private` + `Inherited` + `Shared` cells to test privacy in context). Edge
cases: single child, child that writes `p.initial` back. `cover`:
`single-child` ≥ 25%, `multi-child` ≥ 40%, `writes-initial` ≥ 15%.

```
forAll { (p: StateCell[Int], parentValue: Int, children: List[HarnessState], cells: List[StateCell[?]]) =>
  val parent = HarnessState.initial(cells).set(p)(parentValue)
  val projected = HarnessState.project(parent, cells)
  val merged = HarnessState.mergeBack(parent, children, cells)
  projected.get(p) == p.initial && merged.get(p) == parentValue
}
```

### Property: L10-merge-back-neutrality

**Invariant**: For all parent states and cell lists with idempotent `Shared`
merges, `mergeBack(parent, List(project(parent, cells)), cells) ≍ parent`.

**Generator strategy**: `genParentWithIdempotentShared` (constructive — a
`HarnessState` with `Shared` cells whose `merge` is idempotent: union, max,
min); `genCells` (the declared cell list matching the parent). Edge cases:
parent with only `Private` cells, parent with one `Shared` cell. `cover`:
`private-only` ≥ 20%, `shared-union` ≥ 30%, `shared-max` ≥ 20%.

```
forAll { (parent: HarnessState, cells: List[StateCell[?]]) =>
  val child = HarnessState.project(parent, cells)
  val merged = HarnessState.mergeBack(parent, List(child), cells)
  cells.forall(c => merged.get(c) == parent.get(c))
}
```

### Property: L11-semilattice-commutativity

**Invariant**: For all `Shared` cells with merge `m` and generated values `a`,
`b`: `m(a, b) == m(b, a)`.

**Generator strategy**: `genSharedCell` (constructive — a `Shared` `StateCell[A]`
with a semilattice merge: set union, Int max, Int min); `genPair` (two generated
values of type `A`). Edge cases: equal values, one value empty/zero. `cover`:
`union` ≥ 30%, `max` ≥ 30%, `equal-values` ≥ 15%.

```
forAll { (cell: StateCell[Set[Int]], a: Set[Int], b: Set[Int]) =>
  cell.merge(a, b) == cell.merge(b, a)
}
```

### Property: L11-semilattice-associativity

**Invariant**: For all `Shared` cells with merge `m` and generated values `a`,
`b`, `c`: `m(a, m(b, c)) == m(m(a, b), c)`.

**Generator strategy**: same `genSharedCell` as commutativity; `genTriple`
(three generated values of type `A`). Edge cases: all equal, one empty/zero.
`cover`: `union` ≥ 30%, `max` ≥ 30%, `all-equal` ≥ 15%.

```
forAll { (cell: StateCell[Set[Int]], a: Set[Int], b: Set[Int], c: Set[Int]) =>
  cell.merge(a, cell.merge(b, c)) == cell.merge(cell.merge(a, b), c)
}
```

### Property: L11-semilattice-idempotence

**Invariant**: For all `Shared` cells with merge `m` and generated value `a`:
`m(a, a) == a`.

**Generator strategy**: same `genSharedCell`; single generated value. Edge
cases: empty set, zero. `cover`: `union` ≥ 30%, `max` ≥ 30%, `empty` ≥ 10%.

```
forAll { (cell: StateCell[Set[Int]], a: Set[Int]) =>
  cell.merge(a, a) == a
}
```

### Property: L11-mergeBack-order-independence

**Invariant**: For all parent states, child lists, and `Shared` cells with
semilattice merges, `mergeBack` over any permutation of children produces equal
state.

**Generator strategy**: `genSharedCell` (same as above); `genChildren`
(constructive — `Gen.list genChildState Range.linear 1 6`); `genPermutation`
(constructive — a permutation of the child indices). This is a CONCURRENT
property and MUST be driven with `TestControl` for deterministic execution.
Edge cases: single child, all children with equal state, children with
disjoint writes. `cover`: `single-child` ≥ 15%, `all-equal` ≥ 15%,
`disjoint-writes` ≥ 30%.

```
forAll { (parent: HarnessState, children: List[HarnessState], cells: List[StateCell[?]], perm: List[Int]) =>
  val merged1 = HarnessState.mergeBack(parent, children, cells)
  val permuted = perm.map(children)
  val merged2 = HarnessState.mergeBack(parent, permuted, cells)
  cells.forall(c => merged1.get(c) == merged2.get(c))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `AgentMiddlewareLaws` in `Test` scope of `adk4s-harness-api` | It must be in a main-scoped `-testkit` module so downstream middleware authors can depend on it without `sbt-testkit` export | manual review (Ring 8) — verify `adk4s-harness-testkit` is a separate module with `munit`/`hedgehog-munit` in main scope |
| `DeterministicChatModel` depending on wall-clock time or external I/O | The double MUST be deterministic — wall-clock or I/O dependence breaks L0–L11 observational equivalence | static: grep testkit sources for `System.currentTimeMillis`/`Clock`/`URLConnection` absence |
| Hedgehog `Arbitrary` used in any law property | The spec mandates explicit `Gen` with `Range`; `Arbitrary` hides the generator strategy and weakens coverage | static: grep testkit sources for `Arbitrary`/`arbitrary` absence |
| `SemilatticeLaws` using `Gen.element` without a `Range` | All Hedgehog generators MUST use explicit `Range` per the spec rules | static: review generator definitions |

## Formal Contracts (Ring 6)

Ring 6 applies via the VERIFIED-MIRROR pattern
(`openspec/schemas/verified-scala3/templates/verified-mirror.md`). The semilattice
merge laws (L11) are pure domain logic expressible in PureScala. The shipped
`StateCell.merge` cannot be verified directly because it uses
`ReadWriter`/`JsonValue` and the Stainless frontend is pinned to Scala 3.7.2
while this build is 3.8.4. The merge algorithm itself survives reduction to
observable effect.

### The abstraction

A `Shared` cell's merge becomes a binary function over a value type; the
semilattice laws are stated as universal quantifications over that function.

```scala
// verified/src/main/scala/org/adk4s/verified/SemilatticeKernel.scala  (Scala 3.7.2)
def commutative[A](merge: (A, A) => A, a: A, b: A): Boolean =
  merge(a, b) == merge(b, a)

def associative[A](merge: (A, A) => A, a: A, b: A, c: A): Boolean =
  merge(a, merge(b, c)) == merge(merge(a, b), c)

def idempotent[A](merge: (A, A) => A, a: A): Boolean =
  merge(a, a) == a
```

### Contract: semilattice — commutativity, associativity, idempotence

**Precondition** (`require`): none — the laws are total over the merge function
and values.
**Postcondition** (`ensuring`): for a semilattice merge `m`, all three laws hold
for all inputs.

```scala
def isSemilattice[A](merge: (A, A) => A, a: A, b: A, c: A): Boolean = {
  commutative(merge, a, b) && associative(merge, a, b, c) && idempotent(merge, a)
}.ensuring(_ == (merge(a, b) == merge(b, a) &&
                 merge(a, merge(b, c)) == merge(merge(a, b), c) &&
                 merge(a, a) == a))
```

### Contract: mergeBack order-independence follows from semilattice

**Precondition** (`require`): all `Shared` cells in `cells` have semilattice
merges.
**Postcondition** (`ensuring`): for any permutation `perm` of `children`,
`mergeBack(parent, children, cells) == mergeBack(parent, perm, cells)`.

### Bridge — how the shipped code is bound to the model

`SemilatticeModelBridgeSpec` (Hedgehog, in `adk4s-harness-api` test sources) runs
the real `StateCell.merge` and the model on the SAME generated merge functions
and values, and asserts they agree on exactly the proven invariants:

1. the real `cell.merge(a, b) == cell.merge(b, a)` matches the model's
   `commutative`;
2. the real `cell.merge(a, cell.merge(b, c)) == cell.merge(cell.merge(a, b), c)`
   matches the model's `associative`;
3. the real `cell.merge(a, a) == a` matches the model's `idempotent`.

Build wiring this spec commits to: `adk4s-harness-api dependsOn(verified % Test)`.
TASTy is backward compatible, so the 3.8.4 module may read the 3.7.2 artifact.
`stainlessEnabled := false` by default, so the bridge test pays only a plain
compile of the model; verification is the separate `sbt -J-Xmx6g ring6` step.

### Scope — what is proven, and what is delegated

Target proofs (best-effort): the three semilattice contracts above, all
quantifier-free.

DELEGATED to Ring 3, and named here rather than dropped:

| Law | Why not proven here | Covered by |
|-----|---------------------|------------|
| `mergeBack` order-independence over real `HarnessState` | `HarnessState` uses `JsonValue`/`Map` not modelled in PureScala | Property: L11-mergeBack-order-independence |
| `DeterministicChatModel` determinism | depends on `ChatModel`/`Completion` types not in PureScala | Property: L0-conservative-refactor (determinism is a precondition, checked by repeated-run equality) |
| L0–L10 observational equivalence | involves `HarnessAgent`/`ReactAgentImpl`/`Completion` — not PureScala | Properties: L0-conservative-refactor through L10-merge-back-neutrality |

If a target VC diverges in z3, it moves into this table with its Ring 3 property
named — it is never silently dropped.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Deterministic double produces equal results across repeated runs | Requirement: Deterministic ChatModel double enables observational equivalence + Scenario: Deterministic double produces equal results across repeated runs | scenario test (repeated-run equality) | `DeterministicChatModelSpec` |
| Double records the base-step request trace | Requirement: Deterministic ChatModel double enables observational equivalence + Scenario: Double records the base-step request trace | scenario test (trace content assertion) | `DeterministicChatModelSpec` |
| Double handles tool-call responses deterministically | Requirement: Deterministic ChatModel double enables observational equivalence + Scenario: Double handles tool-call responses deterministically | scenario test (scripted multi-iteration) | `DeterministicChatModelSpec` |
| L0 empty-stack harness matches ReactAgentImpl on simple conversation | Requirement: L0 Conservative refactor equivalence (gatekeeper) + Scenario: Empty-stack harness matches ReactAgentImpl on a simple conversation | Hedgehog property (L0-conservative-refactor) + scenario test | `AgentMiddlewareLawsSpec` |
| L0 equivalence with tool calls | Requirement: L0 Conservative refactor equivalence (gatekeeper) + Scenario: Empty-stack harness matches ReactAgentImpl with tool calls | Hedgehog property (L0-conservative-refactor) | `AgentMiddlewareLawsSpec` |
| L0 equivalence under interrupt (deterministic concurrency) | Requirement: L0 Conservative refactor equivalence (gatekeeper) + Scenario: Empty-stack harness matches ReactAgentImpl under interrupt | scenario test with `TestControl` | `AgentMiddlewareLawsSpec` |
| L0 equivalence under step budget exhaustion | Requirement: L0 Conservative refactor equivalence (gatekeeper) + Scenario: Empty-stack harness matches ReactAgentImpl under step budget exhaustion | Hedgehog property (L0-conservative-refactor, maxSteps = 1 edge) | `AgentMiddlewareLawsSpec` |
| L1 identity at any position | Requirement: L1 Monoid identity + Scenario: Identity inserted at head + Scenario: Identity inserted at tail + Scenario: Identity inserted into empty stack | Hedgehog property (L1-monoid-identity) | `AgentMiddlewareLawsSpec` |
| L2 associativity | Requirement: L2 Monoid associativity + Scenario: Three non-empty stacks associate + Scenario: Associativity with an empty stack | Hedgehog property (L2-monoid-associativity) | `AgentMiddlewareLawsSpec` |
| L3 hook distribution by trace equality | Requirement: L3 Hook distribution + Scenario: Model call wrapping order + Scenario: Tool call wrapping order | Hedgehog property (L3-hook-distribution) | `AgentMiddlewareLawsSpec` |
| L3 single-element stack is identity for distribution | Requirement: L3 Hook distribution + Scenario: Single-element stack is the identity for distribution | Hedgehog property (L3-hook-distribution, single-element edge) | `AgentMiddlewareLawsSpec` |
| L4 default neutrality | Requirement: L4 Default neutrality + Scenario: All-defaults middleware equals identity | Hedgehog property (L4-default-neutrality) | `AgentMiddlewareLawsSpec` |
| L4 non-default middleware is NOT neutral (adversarial) | Requirement: L4 Default neutrality + Scenario: Middleware with empty cells but non-trivial beforeAgent is NOT neutral (adversarial) | adversarial scenario test (negative) | `AgentMiddlewareLawsSpec` |
| L5 cell frame rule for beforeAgent | Requirement: L5 Cell frame rule + Scenario: Middleware with one cell does not touch another middleware's cell | Hedgehog property (L5-cell-frame-rule) | `AgentMiddlewareLawsSpec` |
| L5 cell frame rule for afterAgent | Requirement: L5 Cell frame rule + Scenario: afterAgent also preserves the frame | Hedgehog property (L5-cell-frame-rule) | `AgentMiddlewareLawsSpec` |
| L5 detects cross-cell writes (adversarial) | Requirement: L5 Cell frame rule + Scenario: Middleware with no cells touches nothing (adversarial) | adversarial scenario test (negative — property fails) | `AgentMiddlewareLawsSpec` |
| L6 disjoint commutativity | Requirement: L6 Disjoint commutativity (conditional) + Scenario: Two disjoint pure middlewares commute | Hedgehog property (L6-disjoint-commutativity) | `AgentMiddlewareLawsSpec` |
| L6 overlapping cells do NOT commute (adversarial) | Requirement: L6 Disjoint commutativity (conditional) + Scenario: Middlewares with overlapping cells do NOT commute (adversarial) | adversarial scenario test (negative) | `AgentMiddlewareLawsSpec` |
| L6 request rewriters do NOT commute (adversarial) | Requirement: L6 Disjoint commutativity (conditional) + Scenario: Middlewares that rewrite requests do NOT commute (adversarial) | adversarial scenario test (negative) | `AgentMiddlewareLawsSpec` |
| L7 codec round-trip per cell | Requirement: L7 Codec round-trip + Scenario: Int cell round-trips | Hedgehog property (L7-codec-round-trip) | `AgentMiddlewareLawsSpec` |
| L7 state round-trip through snapshot/restore | Requirement: L7 Codec round-trip + Scenario: State with multiple cells round-trips through snapshot/restore | Hedgehog property (L7-codec-round-trip) | `AgentMiddlewareLawsSpec` |
| L7 absent-equals-initial round-trip | Requirement: L7 Codec round-trip + Scenario: Cell at initial value round-trips as absent | Hedgehog property (L7-codec-round-trip, at-initial edge) | `AgentMiddlewareLawsSpec` |
| L8 unknown fields ignored on restore | Requirement: L8 Restore leniency + Scenario: Unknown fields ignored on restore | Hedgehog property (L8-restore-leniency) | `AgentMiddlewareLawsSpec` |
| L8 new cells default to initial on restore | Requirement: L8 Restore leniency + Scenario: New cells default to initial on restore | Hedgehog property (L8-restore-leniency) | `AgentMiddlewareLawsSpec` |
| L8 corrupted cell is a hard error (adversarial) | Requirement: L8 Restore leniency + Scenario: Corrupted cell value is a hard error (adversarial) | adversarial scenario test (Left assertion) | `AgentMiddlewareLawsSpec` |
| L9 child sees initial for Private cell | Requirement: L9 Privacy + Scenario: Child sees initial for Private cell | Hedgehog property (L9-privacy) | `AgentMiddlewareLawsSpec` |
| L9 parent Private value preserved after mergeBack | Requirement: L9 Privacy + Scenario: Parent's Private value preserved after mergeBack | Hedgehog property (L9-privacy) | `AgentMiddlewareLawsSpec` |
| L9 child writes to Private are unobservable (adversarial) | Requirement: L9 Privacy + Scenario: Child writes to Private cell are unobservable (adversarial) | adversarial scenario test | `AgentMiddlewareLawsSpec` |
| L10 merge-back neutrality for untouched children | Requirement: L10 Merge-back neutrality + Scenario: Untouched child round-trips to parent | Hedgehog property (L10-merge-back-neutrality) | `AgentMiddlewareLawsSpec` |
| L10 non-idempotent merge breaks neutrality (adversarial) | Requirement: L10 Merge-back neutrality + Scenario: Non-idempotent merge breaks neutrality (adversarial) | adversarial scenario test (negative) | `AgentMiddlewareLawsSpec` |
| L11 semilattice commutativity | Requirement: L11 Semilattice laws for parallel-shared cells + Scenario: Union-merge is a semilattice | Hedgehog property (L11-semilattice-commutativity) | `SemilatticeLawsSpec` |
| L11 semilattice associativity | Requirement: L11 Semilattice laws for parallel-shared cells | Hedgehog property (L11-semilattice-associativity) | `SemilatticeLawsSpec` |
| L11 semilattice idempotence | Requirement: L11 Semilattice laws for parallel-shared cells | Hedgehog property (L11-semilattice-idempotence) | `SemilatticeLawsSpec` |
| L11 mergeBack order-independence over permutations | Requirement: L11 Semilattice laws for parallel-shared cells + Scenario: mergeBack is order-independent over permutations | Hedgehog property (L11-mergeBack-order-independence) with `TestControl` | `SemilatticeLawsSpec` |
| L11 non-commutative merge fails the laws (adversarial) | Requirement: L11 Semilattice laws for parallel-shared cells + Scenario: Non-commutative merge fails the semilattice laws (adversarial) | adversarial scenario test (negative — law fails) | `SemilatticeLawsSpec` |
| Semilattice commutativity (model) | Requirement: L11 Semilattice laws for parallel-shared cells + Invariant: merge(a, b) == merge(b, a) | formal contract (Ring 6) — `commutative` ensuring clause, verified by Stainless | `SemilatticeKernel` |
| Semilattice associativity (model) | Requirement: L11 Semilattice laws for parallel-shared cells + Invariant: merge(a, merge(b, c)) == merge(merge(a, b), c) | formal contract (Ring 6) — `associative` ensuring clause | `SemilatticeKernel` |
| Semilattice idempotence (model) | Requirement: L11 Semilattice laws for parallel-shared cells + Invariant: merge(a, a) == a | formal contract (Ring 6) — `idempotent` ensuring clause | `SemilatticeKernel` |
| Shipped merge conforms to the verified model | Requirement: L11 Semilattice laws for parallel-shared cells | bridge property test (Ring 3 + Ring 6) — real and model on the same generated merge functions and values | `SemilatticeModelBridgeSpec` |
| Testkit module compiles independently | Requirement: Testkit module publication in main scope + Scenario: Testkit module compiles independently | build verification (`sbt adk4s-harness-testkit/compile`) | build verification |
| Testkit module has no heavy deps | Requirement: Testkit module publication in main scope + Scenario: Testkit module has no heavy deps | dependency-tree inspection + manual review (Ring 8) | adversarial review |
| Testkit main scope uses munit main-scope | Requirement: Testkit module publication in main scope + Scenario: Testkit main scope uses munit main-scope (not Test) + Compile-Negative: AgentMiddlewareLaws in Test scope | manual review (Ring 8) + `sbt adk4s-harness-testkit/compile` | adversarial review |
| Downstream can import AgentMiddlewareLaws as a regular dependency | Requirement: Testkit module publication in main scope | manual review (Ring 8) | adversarial review |
| No Arbitrary used in law properties | Compile-Negative: Hedgehog Arbitrary used in any law property | static: grep testkit sources for `Arbitrary`/`arbitrary` absence | adversarial review |
| No wall-clock or I/O in DeterministicChatModel | Compile-Negative: DeterministicChatModel depending on wall-clock time or external I/O | static: grep testkit sources for `System.currentTimeMillis`/`Clock`/`URLConnection` absence | adversarial review |
| Concurrency scenarios use TestControl, not wall-clock | Requirement: L0 Conservative refactor equivalence (gatekeeper) + Requirement: L11 Semilattice laws for parallel-shared cells | static: grep tests for `Thread.sleep`/`TimeUnit.sleep` absence; `TestControl` used for L0 interrupt and L11 mergeBack-order-independence scenarios | `AgentMiddlewareLawsSpec`, `SemilatticeLawsSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `adk4s-harness-testkit` module | sbt module | `build.sbt` | new module: `.dependsOn(adk4s-harness-api)`, main deps `cats-effect` + `munit` + `hedgehog-munit` (NOT test-scoped — `AgentMiddlewareLaws` is a downstream-consumable main API), aggregated by root |
| `AgentMiddlewareLaws` | testkit class | `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/AgentMiddlewareLaws.scala` | L0–L11 Hedgehog properties in the `AgentMemoryLaws` style; parameterized by a stack and `DeterministicChatModel`; each law is a `Property` |
| `SemilatticeLaws` | testkit property | `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/SemilatticeLaws.scala` | commutativity/associativity/idempotence of `cell.merge`; `mergeBack` order-independence over permutations |
| `DeterministicChatModel` | test double | `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/DeterministicChatModel.scala` | deterministic `ChatModel[IO]` double; fixed seed; scripted response sequence; records request traces at the base step |
| `Generators` (harness-testkit) | Hedgehog `Gen` | `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/Generators.scala` | new — `genConversation`, `genToolBehavior`, `genMaxSteps`, `genStack`, `genMiddleware`, `genPureMiddleware`, `genDisjointMiddlewarePair`, `genCellWithValue`, `genState`, `genUnknownFields`, `genNewCells`, `genPrivateCellWithValue`, `genChildren`, `genSharedCell`, `genPermutation`; all use explicit `Range`, NO `Arbitrary` |
| `SemilatticeKernel.scala` | new file (Ring 6 model) | `verified/src/main/scala/org/adk4s/verified/SemilatticeKernel.scala` | PureScala, Scala 3.7.2. `commutative`/`associative`/`idempotent`/`isSemilattice` with `ensuring` clauses. Verified by `sbt -J-Xmx6g ring6`. |
| `SemilatticeModelBridgeSpec.scala` | new file (bridge test) | `adk4s-harness-api/src/test/scala/org/adk4s/harness/SemilatticeModelBridgeSpec.scala` | Hedgehog. Runs real `StateCell.merge` and the model on the same generated merge functions and values. Compiles the model only — `stainlessEnabled := false`. |
| `adk4s-harness-api dependsOn(verified % Test)` | build step | `build.sbt` | Ring 6 bridge precondition; add when the module is created. TASTy backward compatible (3.8.4 reads 3.7.2). |
| `AgentMiddlewareLawsSpec` | munit + Hedgehog + TestControl | `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/AgentMiddlewareLawsSpec.scala` | L0–L10 property and scenario tests; L0 interrupt scenario and L6/L11 concurrency scenarios use `TestControl` |
| `SemilatticeLawsSpec` | munit + Hedgehog + TestControl | `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/SemilatticeLawsSpec.scala` | L11 semilattice and mergeBack-order-independence property tests; concurrency scenarios use `TestControl` |
| `DeterministicChatModelSpec` | munit + Hedgehog | `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/DeterministicChatModelSpec.scala` | determinism, trace recording, and scripted-response scenario tests |
| `stryker4s.conf` `mutate` retarget | build config | `stryker4s.conf` | point `mutate` at `**/harness/testkit/*.scala` for Ring 5 (testkit is main-scope; mutation threshold applies to the law logic) |
| Compile | build step | `adk4s-harness-testkit` | `sbt adk4s-harness-testkit/compile` |
| Test | build step | `adk4s-harness-testkit` | `sbt adk4s-harness-testkit/test` |
