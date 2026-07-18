# Concept Registry

Behavioral concepts for **adk4s** (Agent Development Kit for Scala 3), in the
sense of Meng & Jackson, *"What You See Is What It Does: A Structural Pattern
for Legible Software"* (arXiv:2508.14511): a **concept** is an independent
unit of user-facing functionality with a well-defined purpose, its own
state, a set of actions, and an operational principle. Concepts never
reference each other; cross-concept behavior is expressed as
**synchronizations** — declarative `when / where / then` rules.

This registry is **descriptive, not prescriptive**: it names the concepts
already latent in the code and maps them to their current implementation. It
does not require the code to be restructured. Where the implementation
violates the pattern's design rules, the concept file records the deviation
instead of hiding it.

## Note on library concepts

adk4s is a **library/SDK**, not a persistent-entity application. The Meng &
Jackson concept pattern is adapted here as follows:

- **State** is the in-flight or configured state of an abstraction a user
  composes (a `Conversation`, a `Runnable` graph, a checkpoint, an event
  topic), not rows in a database. Relational declarations describe the
  shape of that state.
- **Actions** are the operations a library user invokes on the abstraction
  (`generate`, `run`, `parse`, `resume`, `emit`). Completion cases list the
  success reply and each rejection/error the code actually returns.
- **Computation concepts** (pure services with no persistent state of their
  own — e.g. `SchemaAlignedParser`) have empty or argument-only state; their
  inputs become action arguments with honest signatures.
- **Bootstrap concepts** are not applicable (no HTTP servers or topic
  consumers ship in the library). Examples in `adk4s-examples` are not
  catalogued here — they are usage demos, not library concepts.

## Relation to `concept-inventory.md`

The per-change `concept-inventory.md` (from `openspec-scan-concepts`)
catalogs **types** — it answers "does this type already exist?" during
apply. This registry catalogs **behavior** — it answers "what is the stable
name for the thing this spec changes?". Spec deltas refer to behavior as
`Concept/action`; code identifiers appear in exactly one place per concept —
the **Implementation map** — so a refactor updates one table, not every
spec that ever mentioned a type.

## File format

One file per concept, `openspec/concepts/<kebab-name>.md` (see
`concept.md` template):

1. **Concept specification** — paper format: `concept Name [TypeParams]`,
   `purpose`, `state` (relational, Alloy-style), `actions` (named args,
   success + error completion cases), `operational principle`.
2. **Implementation map** — table binding each state component and action
   to the code realizing it today. The *only* place code identifiers live.
3. **Synchronizations** — named `when / where / then` rules with an `impl:`
   pointer and noted deviations (e.g. best-effort instead of
   transactional).
4. **Deviations from the pattern** — where the code breaks concept
   independence, recorded as observations.

## Rules for spec authors

- **Where code may appear.** Requirements and scenarios use behavioral
  vocabulary only: `Concept/action` references, the project's user-facing
  surface language (DSL paths, API fields), new domain terms, and concrete
  test vectors. Module names, error class names, and build commands belong
  in the spec's `## Implementation Anchors` section — never in a
  Given/When/Then.
- **Negative requirements need adversarial scenarios.** Every requirement
  containing "only", "never", or "must not" gets at least one scenario
  whose input the requirement forbids. Implementations that only pass the
  positive examples do not satisfy the requirement.
- **`MUST-CONFIRM` for external data.** Any classification table, code
  mapping, or value domain whose authoritative source is outside the repo
  is marked `MUST-CONFIRM` in the spec. The apply phase MUST stop and ask
  for the real data — inventing plausible values is a schema violation.
  Concept files record the *provenance* of every value domain they cite.
- **Living document.** When a change alters a concept's actions, state, or
  syncs, updating the concept file is part of implementing that change.

## Machine check

`openspec/schemas/verified-scala3/scanner/registry-check.sh` runs three
passes and must be wired into CI (it is dependency-free — bash + git grep —
so it runs anywhere):

1. **Symbols** — every backticked symbol in every Implementation map and
   `impl:` line still exists in the source tree.
2. **Fold fields** — a state-fold row may declare the exact fields the fold
   populates with the convention `maps field1, field2, ...` next to a
   backticked path to the fold's file. Every listed field must appear in
   that file.
3. **Spec references** — every `Concept` / `Concept/action` cited in an
   active change spec's "## Concepts Used (behavioral)" table must be
   declared by a registry file, and the action must appear in that file.

## Catalog

| Concept | Status | One-line purpose |
|---|---|---|
| [ChatModel](chat-model.md) | ✅ | Effect-polymorphic LLM interface: generate a completion or stream chunks for a conversation |
| [Tool](tool.md) | ✅ | Three-tier tool abstraction (metadata / invokable / streamable) exposed to LLMs and executed by the runtime |
| [ToolsNode](tools-node.md) | ✅ | Execute a batch of LLM tool calls with middleware, parallel/sequential strategy, and interrupt propagation |
| [Agent](agent.md) | ✅ | Minimal agent interface: produce an assistant message from input messages |
| [ReactAgent](react-agent.md) | ✅ | ReAct loop — call LLM, execute tools, feed results back, until final response or maxSteps |
| [AgentTool](agent-tool.md) | ✅ | Wrap an Agent as an InvokableTool for hierarchical delegation with state persistence across interrupts |
| [AgentRunner](agent-runner.md) | ✅ | Manage interrupt/resume lifecycle of an agent run against a CheckpointStore |
| [Runnable](runnable.md) | ✅ | Universal computation with four modes (invoke, stream, collect, transform) and composable combinators |
| [AgentEventStream](agent-event-stream.md) | ✅ | Hierarchical event emission for observable agent execution |
| [InterruptSignal](interrupt-signal.md) | ✅ | Route and compose interruption requests with address targeting across agent/tool hierarchies |
| [StructuredLLM](structured-llm.md) | ✅ | Produce a typed value `A` from a prompt by injecting a schema and parsing the LLM response |
| [Schema](schema.md) | ✅ | Typeclass bridging Smithy IDL (for prompt injection) and smithy4s Schema (for JSON decoding) |
| [SchemaAlignedParser](schema-aligned-parser.md) | ✅ | Lenient JSON recovery — parse LLM output into a typed value despite markdown fences, trailing commas, quotes, comments, truncation |
| [Prompt](prompt.md) | ✅ | Immutable conversation representation with system/user messages and schema injection |
| [WIOGraph](wio-graph.md) | ✅ | Type-safe DAG built from WIONodes; validates edges, entry/end nodes; compiles to WIO or Runnable |
| [Graph](graph.md) | ✅ | Generic graph structure with nodes, edges, and validation |
| [Branch](branch.md) | ✅ | Conditional branching and routing across alternatives |
| [Chain](chain.md) | ✅ | Linear chain execution of composed steps |
| [Workflow](workflow.md) | ✅ | Higher-level DSL for composing Lambda nodes with field mappings |
| [StatefulNode](stateful-node.md) | ✅ | Node with pre/post state handlers and event-sourced state management |
| [CheckpointStore](checkpoint-store.md) | ✅ | Persist checkpoint state for interrupt/resume across runs |
| [StreamingLLM](streaming-llm.md) | ✅ | Bridge llm4s callback-based streaming into fs2.Stream and accumulate chunks into a Completion |
| [ChatTemplate](chat-template.md) | ✅ | Prompt templates with variable substitution |
| [Retriever](retriever.md) | ✅ | Document retrieval and embedding abstractions |
| [BatchExecutor](batch-executor.md) | ✅ | Batch multiple operations for throughput |

Extraction recipe, should new concepts appear: persistent entity
command/event enums → actions; persisted state structures → state; message
consumers, producers, and HTTP middleware → synchronizations; transport
entry points (HTTP, topics) → bootstrap concepts (§6.7 of the paper).

## Synchronization index

| Sync | Trigger | Effect | Defined in |
|---|---|---|---|
| ToolCallsToToolsNode | ReactAgent/iterate produces tool calls | ToolsNode/execute dispatches each call | [react-agent.md](react-agent.md) |
| ToolResultToConversation | ToolsNode/execute returns ToolExecutionResult | ReactAgent/iterate appends tool messages and re-enters the loop | [react-agent.md](react-agent.md) |
| InterruptToCheckpoint | AgentRunner/run catches AgentInterruptedException | CheckpointStore/save persists the signal and run state | [agent-runner.md](agent-runner.md) |
| AgentToolStateWrap | AgentTool/executeInnerAgent catches an interrupt | InterruptSignal/Composite wraps the inner signal with the agent-tool's own state | [agent-tool.md](agent-tool.md) |
| EventScopeEmission | ReactAgent/iterate emits an AgentEvent | AgentEventEmitter/scoped attaches the current RunPath | [agent-event-stream.md](agent-event-stream.md) |
| SchemaInjection | Prompt/withOutputFormat is called with a Schema | StructuredLLM/complete appends the Smithy IDL block to the last user message | [structured-llm.md](structured-llm.md) |
| ResponseToParser | StructuredLLM/completeRawSingle has LLM response content | SchemaAlignedParser/parse decodes the response into A | [structured-llm.md](structured-llm.md) |
