# DESIGN — `AgentMiddleware[F]` and `HarnessState`

**Project:** deepagents4s — Phase 0 (foundation)
**Status:** Draft for review
**Depends on:** ADK4S `adk4s-core`, `adk4s-orchestration` (refactor target), llm4s message model
**Informed by:** langchain-ai/deepagents 0.6.12 (`langchain.agents.middleware.types.AgentMiddleware`, `deepagents/graph.py`, `deepagents/middleware/*`), MIT-licensed

> **Update note (post `migrate-json-codec`, 2026-08-06).** This design was
> originally written when `ujson.Value`/`upickle` was ADK4S's JSON currency.
> The `migrate-json-codec` change introduced `JsonValue`
> (= `smithy4s.Document`) as ADK4S's internal, immutable JSON value type and
> confined `ujson.Value` to the llm4s boundary via Scalafix rules
> (`NoUjsonInCore`/`NoUjsonInOrchestration`/…). The code sketches below have
> been updated to use `JsonValue` where they previously said `ujson.Obj`/
> `ujson.Value` (§3.2 `StateCell.rw`, §3.3 `HarnessState.snapshot`, §3.4
> `snapshot`/`restore`, §6.3 `CheckpointStateV2.harnessState`). The codec
> typeclass remains `upickle.default.ReadWriter` (bridged for `JsonValue` via
> `JsonValueReadWriter`); a future revision may switch the codec currency
> entirely to `smithy4s.json.Json` once `CheckpointState` legacy is retired.

---

## 1. Motivation

deepagents is not an agent runtime; it is a *middleware stack plus an assembly
function*. Every headline feature — planning, filesystem, sub-agents,
summarization, skills, memory, HITL, profiles — is a middleware that can:

1. run effects **before** and **after** an agent run,
2. **wrap the model call**, rewriting the request (tool list, system prompt,
   message history) and observing/transforming the response,
3. **wrap tool execution**, and
4. contribute **typed state** to a shared harness state that flows through the
   loop, survives checkpoints, and is selectively visible to sub-agents.

ADK4S already owns the runtime half of this picture (`ReactAgent`,
`AgentRunner`, `InterruptSignal`, `AgentEvent`, `CheckpointStore`,
workflows4s durability) and a *tool-level* middleware
(`ToolMiddleware = ToolEndpoint => ToolEndpoint`, a Kleisli endomorphism).
What it lacks is the *agent-loop-level* middleware abstraction. Phase 0 adds
exactly that, plus the typed state substrate it requires. Everything in later
phases (FilesystemMiddleware, SubAgentMiddleware, SummarizationMiddleware, …)
is a value of the type designed here.

### Goals

- An effect-polymorphic `AgentMiddleware[F]` with hook parity to the four
  hooks deepagents actually uses: `before_agent`, `after_agent`,
  `wrap_model_call`, `wrap_tool_call` — plus tool and prompt contribution.
- A typed, enumerable, serializable `HarnessState` replacing Python's
  `TypedDict`-merging and runtime name-mangled `PrivateStateAttr`.
- Sub-agent state isolation with *three* visibility levels (deepagents has
  two), with lattice-based merge for parallel delegation.
- A lawful composition story: the middleware stack is a monoid, with
  Hedgehog-testable laws packaged as a downstream-consumable testkit
  (`AgentMiddlewareLaws`, following the `AgentMemoryLaws` precedent).
- A `ReactAgent` refactor plan that is observationally conservative: the
  empty stack must be provably equivalent to today's agent.
- Checkpoint integration: harness state snapshots ride inside
  `CheckpointState`, and `CheckpointStore` generalizes to `F[_]`.

### Non-goals (Phase 0)

- No concrete harness middlewares (todos, filesystem, sub-agents, …) — those
  are Phases 1–3. Phase 0 ships only `AgentMiddleware.id` and one or two
  trivial reference middlewares for the test suite.
- No F-generalization of `ToolsNode` or `ChatModel` call sites beyond what the
  loop refactor forces. The *API* is `F`-polymorphic; the Phase 0 *runtime*
  instantiates `F = IO`.
- No `createDeepAgent` assembly function (Phase 1) and no prompt pack.

---

## 2. Where the code lives

Following the `adk4s-memory-api` / `adk4s-memory-testkit` precedent — a small,
dependency-light capability API inside the ADK4S repo, consumed by both the
orchestration layer and external projects:

| Module | Contents | Depends on |
|---|---|---|
| `adk4s-harness-api` (new, in ADK4S) | `AgentMiddleware`, `HarnessState`, `StateCell`, `MiddlewareStack`, `ModelRequest`/`ModelResponse`, `ToolStep`, visibility/merge machinery | `adk4s-core` (for `InvokableTool`, `ToolInput`/`ToolOutput`, `JsonValue`/`JsonValueCodec`), llm4s model types, smithy4s-json (`JsonValue` = `smithy4s.Document`; `ujson.Value` confined to the llm4s boundary) |
| `adk4s-harness-testkit` (new, in ADK4S) | `AgentMiddlewareLaws`, deterministic `ChatModel` double, generators | `adk4s-harness-api`, Hedgehog |
| `adk4s-orchestration` (refactor) | `HarnessAgent` loop; `ReactAgent` re-expressed as the empty-stack harness; `CheckpointStore[F]`; `CheckpointState` v2 | `adk4s-harness-api` |
| `deepagents4s` (new repo, Phase 1+) | concrete middlewares, backends, `createDeepAgent`, prompt pack | `adk4s-harness-api`, `adk4s-orchestration`, `adk4s-memory-api` |

Rationale: the loop refactor lives in `adk4s-orchestration`, so the middleware
trait it consumes must sit at or below it. Putting it in a dedicated API module
(rather than `adk4s-core`) keeps `adk4s-core` free of harness concepts and
gives deepagents4s a minimal compile-time surface — the same reasoning that
produced `adk4s-memory-api`.

---

## 3. `HarnessState` — a typed, enumerable, serializable heterogeneous map

### 3.1 Requirements

Derived from how deepagents actually uses graph state:

- **R1 — typed cells.** Each middleware declares state fields with a static
  type (`TodoState`, `SkillsState`, `MemoryState`, …). Reads and writes must
  be type-safe without casts at use sites.
- **R2 — enumerable.** The whole state must be snapshotable for checkpoints
  (`AgentRunner` persists on interrupt) and restorable on resume.
- **R3 — visibility.** deepagents' `PrivateStateAttr` prevents middleware
  state leaking into sub-agents. We need at least that, and we need a merge
  story for state that *should* cross the boundary (e.g. the virtual
  filesystem in `StateBackend`, which sub-agents share with the parent).
- **R4 — mergeable.** deepagents runs `task` calls in parallel; LangGraph
  resolves concurrent writes with channel reducers. Independent sub-agent
  results must merge deterministically.
- **R5 — forward compatible.** Restoring a checkpoint written by an older
  stack (fewer cells) or newer stack (more cells) must not fail.

### 3.2 `StateCell[A]`

A cell is the unit of declaration, ownership, typing, serialization,
visibility, and merging:

```scala
package org.adk4s.harness

import upickle.default.ReadWriter
import org.adk4s.core.json.JsonValue

enum CellVisibility:
  /** Never crosses a sub-agent boundary; the child sees `initial`. */
  case Private
  /** Copied into the child's initial state; child writes are discarded. */
  case Inherited
  /** Copied into the child; child's final value merges back via `merge`. */
  case Shared

final class StateCell[A] private (
  val id: CellId,                    // stable "owner/name", unique per stack
  val visibility: CellVisibility,
  val initial: A,
  val merge: (A, A) => A,            // (parent, child) => merged; see 3.5
  val rw: ReadWriter[A]              // checkpoint codec (JsonValue currency;
                                     //   bridged via JsonValueReadWriter when
                                     //   A is/contains JsonValue)
):
  override def equals(other: Any): Boolean = other match
    case that: StateCell[?] => this.id == that.id
    case _                  => false
  override def hashCode: Int = id.hashCode

object StateCell:
  opaque type CellId = String
  object CellId:
    def apply(owner: MiddlewareName, name: String): CellId =
      s"${owner.value}/$name"

  def apply[A: ReadWriter](
    owner: MiddlewareName,
    name: String,
    initial: A,
    visibility: CellVisibility = CellVisibility.Private,
    merge: (A, A) => A = (_: A, child: A) => child   // last-write-wins default
  ): StateCell[A] =
    new StateCell(CellId(owner, name), visibility, initial, merge,
      summon[ReadWriter[A]])
```

Notes:

- The **codec is mandatory at declaration**. A cell that cannot round-trip
  through the codec cannot exist, which makes R2 unconditionally true — there
  is no "oops, this middleware's state isn't checkpointable" failure mode at
  runtime. The codec typeclass is `upickle.default.ReadWriter`, chosen because
  it is already the serialization currency of `InterruptSignal.Stateful`
  (whose `state: JsonValue` is bridged via `JsonValueReadWriter`) and
  `AgentRunner.CheckpointState`; the value currency is `JsonValue`
  (= `smithy4s.Document`), not `ujson.Value` (which is confined to the llm4s
  boundary by the `migrate-json-codec` Scalafix rules). A smithy4s
  `Schema[A] => ReadWriter[A]` bridge can be added for cells whose types
  already live in Smithy IDL, but is not required.
- `id` is a *stable string*, not object identity. Object-identity keys (the
  `org.typelevel.vault.Vault` approach) were rejected — see §8.
- Equality is by `id`. Uniqueness of ids within a stack is enforced at stack
  construction (§5.2), so `id` collisions are a construction-time error, not a
  runtime surprise.

### 3.3 `HarnessState`

```scala
final class HarnessState private (
  private val cells: Map[StateCell.CellId, (StateCell[?], Any)]
):
  /** Total: absent cells read as their declared initial value. */
  def get[A](cell: StateCell[A]): A =
    cells.get(cell.id) match
      case Some((_, value)) => value.asInstanceOf[A]   // safe, see below
      case None             => cell.initial

  def set[A](cell: StateCell[A])(value: A): HarnessState =
    HarnessState(cells.updated(cell.id, (cell, value)))

  def update[A](cell: StateCell[A])(f: A => A): HarnessState =
    set(cell)(f(get(cell)))

  def snapshot: JsonValue = ...            // §3.4 (DObject)
  private[harness] def entries: Iterable[(StateCell[?], Any)] = cells.values

object HarnessState:
  val empty: HarnessState = new HarnessState(Map.empty)

  def initial(declared: List[StateCell[?]]): HarnessState =
    declared.foldLeft(empty) { (s, c) =>
      // widen-and-narrow through the existential; safe: value is c.initial
      s.setUnsafe(c, c.initial)
    }
```

The single `asInstanceOf` is the classic typed-map argument, and it is worth
writing down because it is the only unchecked cast in the whole design:
*the only path that writes the entry keyed by `cell.id` is `set(cell)(value:
A)` for a cell equal (by id) to this one, and id uniqueness within a stack is
validated at construction, so the stored value is always the `A` of the
declaring cell.* The invariant is local, auditable, and — if you want to go
further — a Stainless target: `HarnessState` is small enough to verify the
get/set coherence property (`get(c)(set(c)(v)(s)) == v` and
`get(c)(set(d)(v)(s)) == get(c)(s)` for `c.id != d.id`) mechanically.

`get` being **total** (falling back to `initial`) is deliberate: it removes
`Option` noise from every middleware, makes R5 trivial (missing cells after
restore just read as initial), and makes the lens laws in §7 clean.

### 3.4 Snapshot / restore

```scala
def snapshot: JsonValue =
  smithy4s.Document.DObject(
    cells.values.map { case (cell, value) =>
      cell.id.value -> writeJs(value)(using cell.rw.asInstanceOf[ReadWriter[Any]])
    }.toMap
  )

object HarnessState:
  /** Unknown ids in `json` are ignored; declared-but-absent cells read as initial. */
  def restore(
    declared: List[StateCell[?]],
    json: JsonValue            // expected: DObject; non-DObject is a decode error
  ): Either[StateDecodeError, HarnessState] = ...
```

Restore is *lenient by construction* (R5): the declared cell list — obtained
from the middleware stack — drives decoding; anything else in the snapshot is
ignored, anything missing defaults. A cell that fails to decode is a hard
`Left` (corrupted checkpoint beats silent data loss), reported with the cell
id and the codec failure.

This snapshot becomes a new field of `CheckpointState` (§6.3).

### 3.5 Merging and the semilattice requirement

The default merge is last-write-wins, which is correct for *sequential*
delegation (one `task` call at a time). deepagents, however, explicitly
encourages **parallel** `task` calls, and Phase 1's `SubAgentMiddleware` will
support them via `parTraverse`. Merging N concurrently produced child states
into the parent must not depend on completion order.

Design rule, stated now so Phase 1 inherits it: **a `Shared` cell that will be
written under parallel delegation must supply a `merge` that is a commutative,
associative, idempotent binary operation — a join semilattice.** Examples:
todo sets under union; the virtual filesystem as a map merged per-path with a
deterministic per-path resolution; counters as max. This is the CRDT
discipline applied to harness state, and it is checkable: the testkit ships a
`SemilatticeLaws` property (`merge(a,b) == merge(b,a)`,
`merge(a, merge(b,c)) == merge(merge(a,b), c)`, `merge(a,a) == a`) that
Phase 1 middlewares run against their shared cells. deepagents has no
equivalent guarantee — LangGraph reducers are order-sensitive unless the
author happens to pick a commutative one — so this is a place where the port
is deliberately stronger than the original.

Sequential-only shared cells may keep last-write-wins; the constraint applies
where parallelism does.

---

## 4. `AgentMiddleware[F]`

### 4.1 Request/response model

Grounded in the real llm4s/ADK4S types. Two changes versus today's
`ReactAgent`: the tool list and system prompt become **per-request** values
(currently `ReactAgentImpl` bakes `CompletionOptions` once from static
config), and harness state travels alongside.

```scala
package org.adk4s.harness

import org.llm4s.llmconnect.model.{Completion, CompletionOptions, Message}
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.tools.{ToolInput, ToolOutput}
import cats.data.Kleisli

final case class ModelRequest[F[_]](
  systemPrompt: Option[SystemPrompt],
  messages: List[Message],
  tools: List[InvokableTool[F]],
  options: CompletionOptions,       // temperature etc.; tools injected by the loop
  state: HarnessState
)

final case class ModelResponse(
  completion: Completion,
  state: HarnessState
)

type ModelStep[F[_]] = Kleisli[F, ModelRequest[F], ModelResponse]

final case class ToolCallCtx(input: ToolInput, state: HarnessState)
final case class ToolCallOut(output: ToolOutput, state: HarnessState)

type ToolStep[F[_]] = Kleisli[F, ToolCallCtx, ToolCallOut]
```

`SystemPrompt` is a small composed value rather than a raw `String`:

```scala
final case class PromptSection(name: String, body: String)
final case class SystemPrompt(base: Option[String], sections: List[PromptSection]):
  def render: String = ...   // base ++ sections in stack order
```

This is what makes deepagents' prompt assembly reproducible: filesystem,
skills, sub-agent, and memory middlewares each contribute a named section, and
the final prompt is a deterministic fold in stack order — inspectable and
testable section-by-section instead of string-concatenated in the dark.

On `ToolStep`: state-threading tool execution is required because the harness
tools *are* state manipulators (`write_todos` writes the todo cell; the
`StateBackend` filesystem tools read and write the files cell). Note the
isomorphism `ToolStep[F] ≅ Kleisli[StateT[F, HarnessState, _], ToolInput,
ToolOutput]` — the explicit product form is chosen over `StateT` only because
it keeps the checkpoint/interrupt paths (which need the state value in hand)
non-monadic and obvious. Plain, state-oblivious tools and the entire existing
`ToolMiddleware` combinator set lift for free:

```scala
object ToolStep:
  /** Lift a state-oblivious endpoint (today's ToolEndpoint at F = IO). */
  def passthrough[F[_]: Functor](ep: Kleisli[F, ToolInput, ToolOutput]): ToolStep[F] =
    Kleisli { ctx => ep.run(ctx.input).map(out => ToolCallOut(out, ctx.state)) }
```

At `F = IO`, `Kleisli[IO, ToolInput, ToolOutput]` *is* `ToolEndpoint`
definitionally, so `ToolMiddleware.logging/timing/retry/validation` compose
under the new abstraction unchanged.

### 4.2 The trait

```scala
opaque type MiddlewareName = String
object MiddlewareName:
  def apply(s: String): MiddlewareName = s
  extension (n: MiddlewareName) def value: String = n

trait AgentMiddleware[F[_]]:
  def name: MiddlewareName

  /** Declared state ownership — drives initial state, restore, isolation. */
  def stateCells: List[StateCell[?]] = Nil

  /** Static contributions, concatenated in stack order by the loop. */
  def tools: List[InvokableTool[F]] = Nil

  /** Per-request prompt contributions, concatenated in stack order by the loop.
   *
   *  State-aware by design: the section text is folded per-request from the
   *  current `HarnessState`, not fixed at construction. This is what lets a
   *  middleware recall into a cell in `beforeAgent` (load AGENTS.md, query a
   *  GraphStore, read a DACE notebook) and surface that recalled content as an
   *  auditable, named `PromptSection` — rather than smuggling it into
   *  `req.systemPrompt` from `wrapModelCall`, which would bypass the
   *  ordered, section-by-section, testable prompt assembly that `SystemPrompt`
   *  exists to provide. See §4.3 (Skills / Memory / GraphStore recall) and
   *  §4.4 for the rationale. */
  def promptSections(state: HarnessState): List[PromptSection] = Nil

  /** Runs once, before the first model call (and after checkpoint restore). */
  def beforeAgent(state: HarnessState)(using Applicative[F]): F[HarnessState] =
    state.pure[F]

  /** Runs once, after the loop terminates normally. */
  def afterAgent(state: HarnessState)(using Applicative[F]): F[HarnessState] =
    state.pure[F]

  /** Wrap every model call. May rewrite the request; sees the response. */
  def wrapModelCall(next: ModelStep[F]): ModelStep[F] = next

  /** Wrap every tool execution. */
  def wrapToolCall(next: ToolStep[F]): ToolStep[F] = next

object AgentMiddleware:
  def id[F[_]]: AgentMiddleware[F] = new AgentMiddleware[F]:
    val name: MiddlewareName = MiddlewareName("identity")
```

Hook-to-deepagents mapping, for reviewers tracking parity:

| deepagents / langchain | here | notes |
|---|---|---|
| `before_agent(state, runtime)` | `beforeAgent` | runtime config (thread id, emitter) reaches middlewares via constructor injection, not an ambient `Runtime` — explicit over implicit |
| `after_agent` | `afterAgent` | |
| `wrap_model_call(request, handler)` | `wrapModelCall` | identical shape; `handler` is `next` |
| `wrap_tool_call(request, handler)` | `wrapToolCall` | state-threading is explicit here, injected via `ToolRuntime` there |
| `state_schema` + `PrivateStateAttr` | `stateCells` + `CellVisibility` | §3 |
| `middleware.tools` | `tools` | |
| prompt string surgery | `promptSections(state)` | structured, ordered, named; **state-aware** so recall-loaded content (Skills/Memory/GraphStore/DACE notebook) flows through the same auditable assembly as static sections — see §4.4 |

Deliberately omitted: langchain's `modify_model_request` (subsumed by
`wrapModelCall`, as deepagents' own code demonstrates — all four of its
request rewrites go through `wrap_model_call`) and node-level `before_model`/
`after_model` (LangGraph-graph-shaped; our loop is not a graph and doesn't
need them; `WIOGraph` covers graph-shaped orchestration separately).

### 4.3 What each hook is *for* (Phase 1 preview, as design validation)

To confirm the four hooks are sufficient, here is where each Phase 1–3
middleware lands — every deepagents behavior maps to a hook with no residue:

- **TodoListMiddleware** — `tools` (write_todos as a `ToolStep` updating its
  `Shared` todo cell), `promptSections(state)` (static tool instructions;
  ignores `state`).
- **FilesystemMiddleware** — `tools` (six fs tools over the backend),
  `promptSections(state)` (static tool instructions; ignores `state`),
  `wrapToolCall` (evict oversized tool outputs to files), one `Shared` files
  cell when the backend is `StateBackend`.
- **SubAgentMiddleware** — `tools` (`task`), `promptSections(state)` (static
  `task`-tool instructions; ignores `state`); performs the §5.3
  projection/merge at the boundary; builds on the existing `AgentTool`.
- **SummarizationMiddleware** — `wrapModelCall` (count tokens, compact
  `messages`, offload history via backend before delegating to `next`),
  private bookkeeping cell.
- **HITL policy (`interrupt_on`)** — `wrapToolCall`: match tool name against
  policy, raise `InterruptSignal.Stateful` (existing ADK4S machinery) instead
  of calling `next`; resume data re-enters via `AgentRunner.resume`.
- **Skills / Memory (AGENTS.md) / GraphStore recall** — `beforeAgent` (load,
  recall) writes recalled content into a `Private` cell; `promptSections(state)`
  reads that cell per-request and emits it as a named section. GraphStore
  `remember` runs in `afterAgent`. This is the canonical state-aware
  `promptSections` use case — see §4.4.
- **Profiles** — `wrapModelCall` (tool exclusion, provider quirks).

### 4.4 Why `promptSections` is state-aware

An earlier draft had `def promptSections: List[PromptSection] = Nil` — a
static, no-argument contribution. That is sufficient for middlewares whose
prompt text is fixed at construction (FilesystemMiddleware's tool
instructions, SubAgentMiddleware's `task`-tool instructions). It is *not*
sufficient for any middleware whose prompt contribution is **state**:

- **Skills / Memory (AGENTS.md) / GraphStore recall** — §4.3 loads and recalls
  in `beforeAgent`, writing the recalled content into a cell. With a static
  `promptSections`, that content has nowhere to go: the section text was fixed
  at construction, before the recall ran.
- **DACE's notebook** — the running notebook is state mutated across
  iterations; its prompt rendering must reflect the current notebook, not the
  one captured at stack construction.
- Any future middleware that summarizes/recalls/computes its prompt slice from
  harness state.

The natural workaround under a static `promptSections` is to inject via
`wrapModelCall`, rewriting `req.systemPrompt` from `req.state` (`ModelRequest`
carries both, so it works). But that bypasses the ordered, named,
section-by-section, testable prompt assembly that §4.1 sells as the whole
point of `SystemPrompt` — and it bypasses it for *exactly* the middlewares
whose prompt contributions most need to be auditable (recall, memory,
notebook state).

The fix is one parameter:

```scala
def promptSections(state: HarnessState): List[PromptSection] = Nil
```

`MiddlewareStack.allSections` becomes `allSections(state)`, and the loop folds
it **per request** from the current `HarnessState` instead of once at stack
construction. Blast radius is small (one signature, one fold site, the §5.1
composition rule, the §6.1 loop step 3, and the L4/L6 laws' "contributes no
overlapping … sections" wording), it is Phase 0 (the cheapest moment to fix
it), and it makes the memory/skills/recall story work *as designed* rather
than as an exception grafted onto `wrapModelCall`.

Static-section middlewares (Filesystem, SubAgent) simply ignore the parameter;
the cost is a `_ =>` in their override, not a structural change. The win is
that the auditable-prompt-assembly property of `SystemPrompt` holds for *every*
middleware, not just the ones whose text happens to be constant.

---

## 5. Composition

### 5.1 Stack semantics

A stack `[m1, m2, m3]` composes exactly as deepagents orders it:

- `beforeAgent`: m1 → m2 → m3 (Kleisli sequencing);
- `afterAgent`: m3 → m2 → m1 (reverse — teardown mirrors setup);
- `wrapModelCall` and `wrapToolCall`: m1 **outermost** — m1 sees the request
  first and the response last: `m1(m2(m3(base)))`;
- `tools`, `stateCells`: concatenation in stack order;
- `promptSections(state)`: concatenation in stack order, **folded per request**
  from the current `HarnessState` (see §4.4) — not a one-time fold at stack
  construction.

```scala
final case class MiddlewareStack[F[_]] private (
  middlewares: List[AgentMiddleware[F]]
):
  def allCells: List[StateCell[?]]        = middlewares.flatMap(_.stateCells)
  def allTools: List[InvokableTool[F]]    = middlewares.flatMap(_.tools)
  def allSections(state: HarnessState): List[PromptSection] =
    middlewares.flatMap(_.promptSections(state))

  def beforeAgent(s: HarnessState)(using Monad[F]): F[HarnessState] =
    middlewares.foldLeftM(s)((st, m) => m.beforeAgent(st))

  def afterAgent(s: HarnessState)(using Monad[F]): F[HarnessState] =
    middlewares.reverse.foldLeftM(s)((st, m) => m.afterAgent(st))

  def wrapModelCall(base: ModelStep[F]): ModelStep[F] =
    middlewares.foldRight(base)((m, acc) => m.wrapModelCall(acc))

  def wrapToolCall(base: ToolStep[F]): ToolStep[F] =
    middlewares.foldRight(base)((m, acc) => m.wrapToolCall(acc))
```

This makes `MiddlewareStack[F]` a monoid under list concatenation with
identity `MiddlewareStack.empty`, and the §7 laws are the statement that the
monoid structure is *observationally* real, not just syntactic.

### 5.2 Validated construction

```scala
enum StackError:
  case DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])
  case DuplicateToolName(name: String, owners: List[MiddlewareName])

object MiddlewareStack:
  def empty[F[_]]: MiddlewareStack[F] = MiddlewareStack(Nil)

  def validated[F[_]](
    ms: List[AgentMiddleware[F]]
  ): Either[NonEmptyList[StackError], MiddlewareStack[F]] = ...
```

Duplicate cell ids and duplicate tool names are construction-time errors —
this is the guarantee that discharges the §3.3 cast argument and matches
deepagents' behavior of failing fast on tool collisions, but at `Either`
rather than at exception.

### 5.3 Sub-agent boundary: projection and merge

The boundary operation lives here, not in `SubAgentMiddleware`, because its
correctness is a property of the state model:

```scala
object HarnessState:
  /** Parent → child initial state. */
  def project(parent: HarnessState, declared: List[StateCell[?]]): HarnessState =
    declared.foldLeft(HarnessState.initial(declared)) { (child, cell) =>
      cell.visibility match
        case CellVisibility.Private   => child                       // child sees initial
        case CellVisibility.Inherited => copyCell(parent, child, cell)
        case CellVisibility.Shared    => copyCell(parent, child, cell)
    }

  /** Child final states → parent, after (possibly parallel) delegation. */
  def mergeBack(
    parent: HarnessState,
    children: List[HarnessState],
    declared: List[StateCell[?]]
  ): HarnessState =
    declared.foldLeft(parent) { (acc, cell) =>
      cell.visibility match
        case CellVisibility.Shared =>
          mergeShared(acc, children, cell)   // fold cell.merge over children
        case _ => acc                        // Private/Inherited: child writes dropped
    }
```

Consequences worth stating explicitly:

- **Privacy is structural.** A `Private` cell's parent value is unobservable
  by the child (it reads `initial`), and the child's writes to it are
  unobservable by the parent. deepagents enforces the second half only, via
  name filtering; here both directions hold by construction.
- **`Inherited` is read-only inheritance** — configuration flowing down
  (skills sources, permissions) without a write-back channel.
- **`mergeBack` over multiple children folds `cell.merge`**, which is where
  the §3.5 semilattice requirement earns its keep: for semilattice merges the
  fold is order-independent, so parallel `task` results commute.
- A sub-agent may run a *different* stack than the parent (deepagents allows
  per-subagent middleware). `project`/`mergeBack` take the *intersection* of
  parent-declared and child-declared cells; cells unknown to the child are
  untouched in the parent.

---

## 6. Integration with the existing runtime

### 6.1 `HarnessAgent` and the `ReactAgent` refactor

Today `ReactAgentImpl.generateLoop` is a tail-recursive
`Conversation => IO[AssistantMessage]` with `ToolsNode` and
`CompletionOptions` fixed at construction. The refactor re-expresses the loop
against the stack, in `adk4s-orchestration`:

```scala
final class HarnessAgent[F[_]: Async](
  config: HarnessAgent.Config[F]      // name, description, model, stack,
):                                     // baseTools, basePrompt, maxSteps, emitter
  def generate(messages: List[Message], maxSteps: Int): F[HarnessResult]
  def stream(messages: List[Message], maxSteps: Int): fs2.Stream[F, StreamedChunk]
```

Loop shape (per `generate`):

1. `state0 = HarnessState.initial(stack.allCells)` — or `restore` on resume;
2. `state1 <- stack.beforeAgent(state0)`;
3. iterate: build `ModelRequest` from the conversation, `stack.allTools ++
   baseTools`, and the rendered `SystemPrompt(basePrompt,
   stack.allSections(state))` — sections folded **per request** from the
   current `HarnessState` (§4.4), so recall/memory/notebook state flows into
   the prompt; run `stack.wrapModelCall(baseModelStep)` where `baseModelStep`
   delegates to `ChatModel.generate` with `CompletionOptions` derived from the
   *request's* tool list (per-request, fixing the current static baking);
4. for each returned `ToolCall`, execute through
   `stack.wrapToolCall(baseToolStep)`; `baseToolStep` performs lookup and
   dispatch via `ToolsNode` (preserving its parallel/sequential strategies,
   unknown-tool handling, and `ToolMiddleware` pipeline) lifted with
   `ToolStep.passthrough`, while harness-owned tools run as native
   state-threading `ToolStep`s. State updates from parallel tool calls within
   one iteration merge per-cell via `cell.merge` (same rule as sub-agents);
5. append `AssistantMessage` and `ToolMessage`s, recurse until no tool calls
   or `maxSteps`;
6. `stack.afterAgent` on normal termination; on
   `AgentInterruptedException`, snapshot state (§6.3) *without* running
   `afterAgent` (it runs when the resumed run eventually terminates).

Event emission (`ToolCallRequested`/`Completed`, `IterationCompleted`,
`MessageOutput`) stays in the loop exactly as today — middlewares observe via
wrapping, they do not replace the observability channel.

**Backwards compatibility.** `ReactAgent.create(...)` becomes sugar for a
`HarnessAgent` with `MiddlewareStack.empty`, keeping the existing trait and
every current call site (55+ examples, `AgentTool`, graphStore) source-
compatible. The regression guarantee is a property, not a promise — see law
**L0** in §7.

**Effect polymorphism.** The trait and stack are `F[_]`-generic, consistent
with `ChatModel[F]`, `InvokableTool[F]`, `AgentMemory[F]`. Phase 0 ships the
loop at `F = IO` only (because `ToolsNode` and `AgentRunner` are IO-fixed),
via `type IOHarnessAgent = HarnessAgent[IO]`; nothing in the API forecloses
the later core-wide F-generalization.

### 6.2 `CheckpointStore[F]`

```scala
trait CheckpointStore[F[_]]:
  def get(checkpointId: CheckpointId): F[Option[Array[Byte]]]
  def set(checkpointId: CheckpointId, data: Array[Byte]): F[Unit]
  def delete(checkpointId: CheckpointId): F[Unit]
  def keys: F[List[CheckpointId]]

object CheckpointStore:
  def inMemory[F[_]: Sync]: F[CheckpointStore[F]] = ...   // Ref-backed, as today
```

Straight generalization of the existing trait; today's `CheckpointStore`
becomes `CheckpointStore[IO]`. A durable Skunk/Postgres implementation is
Phase 3, but the signature lands now so Phase 1+ code never binds to the
IO-fixed version. (A design alternative — replacing byte-array checkpoints
with workflows4s event-sourced state entirely — is deferred; see §9.)

### 6.3 `CheckpointState` v2

Two changes to `AgentRunner`'s persisted state, one required by this design
and one existing defect that resume correctness requires fixing while we are
here:

```scala
private[agent] final case class CheckpointStateV2(
  version: Int,                        // = 2; v1 payloads remain readable
  messages: List[CheckpointMessage],   // full-fidelity: role, content,
                                       //   toolCalls, toolCallId — NOT the
                                       //   current lossy role+content pair
  harnessState: JsonValue,            // HarnessState.snapshot (DObject)
  interruptSignalJson: String,
  agentName: String
) derives ReadWriter
```

The defect: today `SerializableCheckpointMessage(role, content)` flattens
`AssistantMessage.toolCalls` and `ToolMessage` call-ids away, so a resumed
conversation is not the conversation that was interrupted — providers that
validate tool-call/tool-result pairing will reject it. Harness middlewares
(summarization especially) depend on faithful history, so v2 fixes fidelity
and carries the state snapshot together. `AgentRunner.resume` gains
`HarnessState.restore(stack.allCells, cp.harnessState)` before re-entering
the loop.

---

## 7. Laws — `AgentMiddlewareLaws`

Packaged in `adk4s-harness-testkit` in the `AgentMemoryLaws` style: a class
parameterized by the thing under test, producing Hedgehog properties, runnable
by any downstream middleware author. Observational equivalence `p ≍ q` below
means: driven by the testkit's deterministic `ChatModel` double and a fixed
tool set and input, both produce equal final `AssistantMessage`, equal final
`HarnessState.snapshot`, and equal request traces at the base step.

**L0 — Conservative refactor.** `HarnessAgent(MiddlewareStack.empty) ≍`
current `ReactAgentImpl` on the mock model, across generated conversations,
tool behaviors (including interrupts), and step budgets. This is the
gatekeeper property for merging the refactor at all.

**L1 — Monoid identity.** For any stack `S` and any position,
`insert(S, AgentMiddleware.id) ≍ S`.

**L2 — Monoid associativity.** `(a ++ b) ++ c ≍ a ++ (b ++ c)` — true
syntactically for the fold-based combinators; the property pins it
observationally so future stack-implementation changes cannot break it.

**L3 — Hook distribution.** For the two-element stack `[m1, m2]`:
`stack.wrapModelCall(base) == m1.wrapModelCall(m2.wrapModelCall(base))`
(and likewise for `wrapToolCall`), tested by trace equality.

**L4 — Default neutrality.** A middleware overriding *nothing* (all defaults,
empty contributions) `≍ AgentMiddleware.id`.

**L5 — Cell frame rule.** For a generated middleware `m` whose hooks are pure
state transitions touching only `m.stateCells`, and any cell
`c ∉ m.stateCells`: the value of `c` after any run equals its value before.
Phase 0 enforces this as a *tested* property over stack-authored middlewares
rather than by construction; §9 records the capability-based alternative.

**L6 — Disjoint commutativity (conditional).** If `m1`, `m2` have disjoint
`stateCells`, contribute no overlapping tools/sections (for any `state`),
do not rewrite requests, and their `beforeAgent`/`afterAgent` are pure state
transitions lifted into `F`, then `[m1, m2] ≍ [m2, m1]`. The preconditions are
honest: arbitrary `F`-effects (logging order) and request rewriting are
inherently order-sensitive, and the law's value is precisely that it delimits
*when* reordering a stack is safe. (Section overlap is checked per-`state`
because `promptSections` is state-aware — §4.4; two middlewares whose sections
disjoint for the tested states but would overlap for some untested state are
out of scope of this property, and flagged as a known limitation of
input-space coverage rather than a soundness claim.)

**L7 — Codec round-trip.** For every declared cell and generated value `a`:
`read(rw)(write(rw)(a)) == a`; and for generated states over the declared
cells: `restore(cells, snapshot(s)) == Right(s)` up to absent-equals-initial.

**L8 — Restore leniency.** `restore(cells, snapshot(s) ++ unknownFields)`
succeeds and ignores the unknowns; `restore(cells ++ newCells, snapshot(s))`
succeeds with new cells at `initial`.

**L9 — Privacy.** For a `Private` cell `p`:
`project(parent, cells).get(p) == p.initial`, and
`mergeBack(parent, children, cells).get(p) == parent.get(p)` for all children.

**L10 — Merge-back neutrality.** For untouched children:
`mergeBack(parent, List(project(parent, cells)), cells) ≍ parent` — which
holds for `Shared` cells exactly when `merge` is idempotent, tying L10 to:

**L11 — Semilattice (for parallel-shared cells).** commutativity,
associativity, idempotence of `cell.merge` — order-independence of
`mergeBack` over permutations of children follows and is tested directly.

---

## 8. Alternatives considered

**`org.typelevel.vault.Vault` for `HarnessState`.** The natural Typelevel
reflex (it is http4s' request-attribute mechanism) and it eliminates the cast
by construction. Rejected on R2: Vault is deliberately non-enumerable — you
cannot iterate its entries — so snapshotting for checkpoints is impossible
without maintaining a parallel registry, at which point the registry *is* the
typed map and Vault is dead weight. Keys are also identity-based
(`Unique`-generated), which breaks stable ids across process restarts —
fatal for checkpoint restore.

**Fully static state typing** (the stack carries its state as a tuple/match
type: `MiddlewareStack[F, (TodoState, FsState, …)]`). Maximum compile-time
safety, and Scala 3 makes the encoding expressible. Rejected for Phase 0
because `createDeepAgent` assembles stacks *dynamically* from runtime
configuration (which backends, which sub-agents, which skills), so the static
type is unknowable at the assembly site without heavy existential plumbing;
and checkpoint restore across code versions inherently requires the lenient,
id-keyed representation anyway. The id-keyed map with construction-time
validation and an S-verifiable core is the honest equilibrium. Nothing
prevents a typed facade later.

**`StateT[F, HarnessState, _]` as the hook monad.** Isomorphic to the chosen
explicit-product encoding (§4.1) and prettier for pure middlewares. Rejected
as the *primary* API because interrupt and checkpoint handling need the state
value reified at suspension points, and because `wrapModelCall`'s request
*rewriting* (tools, prompt, messages) is about the request payload, not the
state — a `StateT` signature buries the request transformations that are the
whole point of the hook. The isomorphism is documented so authors who prefer
`StateT` internally can convert at the edge.

**Putting the middleware trait in `adk4s-core`.** Rejected to keep core free
of harness vocabulary and to preserve the memory-api precedent of small
capability modules; see §2.

**Ambient runtime access** (langchain's `get_runtime()` / `get_config()`).
Rejected; ADK4S passes emitters and stores explicitly today and the FP cost
of ambient context (untestability, hidden coupling) is exactly what the port
is meant to escape. Middlewares receive their collaborators by constructor.

---

## 9. Open questions / deferred

1. **Capability-checked cell ownership.** L5 is a tested property; Scala 3
   capture checking (the TACIT direction already explored for ADK4S) could
   make "middleware writes only its declared cells" a compile-time fact by
   handing each middleware a write-capability scoped to its cells. Deferred:
   worth prototyping once the stack is stable, not while it is forming.
2. **workflows4s as the checkpoint substrate.** `HarnessAgent` runs could be
   modeled as WIO workflows, replacing byte-array checkpoints with event
   sourcing and inheriting signal routing for resume — likely the right
   long-term answer for the composite-interrupt routing gap documented in
   `AgentRunner.resume`'s TODO. Deferred to keep Phase 0's blast radius small;
   `CheckpointStore[F]` is shaped so both substrates can sit behind it.
3. **Token accounting location.** Summarization (Phase 2) needs token counts
   in `ModelRequest` context. Options: a jtokkit-backed `TokenCounter[F]`
   injected into that middleware, or usage propagated from llm4s
   `Completion`. Decide in Phase 2; no Phase 0 surface depends on it.
4. **F-generalization of `ToolsNode`/`AgentRunner`.** The API here is ready;
   the core refactor is a separate, mechanical change best done after L0
   locks behavior down.
5. **Streaming through `wrapModelCall`.** Phase 0 wraps `generate`; wrapping
   `ChatModel.stream` (so middlewares can transform token streams) needs a
   `Stream`-shaped hook variant. Deferred until a concrete middleware needs
   it (none of the deepagents set does — summarization acts pre-call).

---

## 10. Phase 0 exit criteria

- `adk4s-harness-api` compiles under the full verified-scala3 ring set
  (Iron where applicable, Scalafix/WartRemover clean, Hedgehog properties
  L1–L11 green, Stryker4s threshold met on `HarnessState`).
- `HarnessAgent` merged in `adk4s-orchestration` with **L0 green** and all
  existing examples running unmodified through the empty-stack path.
- `CheckpointState` v2 lands with a v1-read compatibility test.
- One reference middleware (a trivial run-counter with one `Private` cell and
  one `Shared` semilattice cell) exercises every hook and both visibility
  paths end-to-end, and doubles as the testkit's worked example.
