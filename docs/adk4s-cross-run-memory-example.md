# ADK4S Spec: Cross-Run Memory Example

**Status:** Draft
**Deliverable type:** Runnable examples + tests + docs (no library code changes expected)
**Target module:** `adk4s-examples` (new `memory/` category)
**Depends on:** `adk4s-memory-api`, `adk4s-memory-testkit`, `adk4s-orchestration` (`MemoryAwareRunner`, `MemoryPolicy`, `AgentRunner`, `ReactAgent`)

> **Why this exists.** ADK4S ships a complete durable-memory stack — the
> `AgentMemory[F]` capability, the `AgentMemoryLaws` contract, and the
> `MemoryAwareRunner` orchestration hook — but no example exercises it.
> `ReactMemoryExample` demonstrates only in-conversation memory (the message
> list), which dies with the process. This deliverable is the proof of life
> for durable memory: an agent taught a fact in one run recalls it in a
> completely separate run.
>
> **Verification note.** This spec was written against the README's
> documented APIs. Signatures inferred rather than read from source are
> marked **`⚠ VERIFY`** with the file to check. Resolve all of them before
> merge.

---

## 1. Goal and non-goals

### Goal

Demonstrate, in runnable code, that an ADK4S agent can:

1. Be told a fact in **run 1** (a complete process: start → converse → exit).
2. Recall that fact in **run 2** (a fresh process, fresh agent, empty
   conversation history), purely via the `AgentMemory` machinery — post-turn
   remember having persisted it in run 1, pre-turn recall injecting it into
   the prompt in run 2.

The two-run structure is the essence. A single-process demo proves nothing
beyond what `ReactMemoryExample` already shows; **cross-process persistence
is the entire point**.

### Secondary goals

- Show **both** integration seams: `MemoryAwareRunner` (automatic
  recall/remember around each turn) and `MemoryRetriever` (memory adapted
  into the existing `Retriever` interface).
- Runnable with **zero external dependencies**: no API key, no Docker, no
  network. Follows the existing example convention of a deterministic mock
  `ChatModel` that auto-switches to OpenAI when `OPENAI_API_KEY` is set.

### Non-goals

- No new library features. If the example cannot be written without changing
  `adk4s-memory-api` or `adk4s-orchestration`, that is a finding to report as
  its own small proposal, not a license to extend the API here. The one
  deliberate, contained exception is the file-backed memory in §3 — and it
  stays inside `adk4s-examples` precisely so no library module is touched.
- No performance or load characteristics.
- No multi-user / multi-group scoping demo.

---

## 2. Scenario script

The dialogue is deliberately boring so the memory behavior is unmistakable.

**Run 1 — teach:**

```
user:  My favorite color is blue, and I work at Acme Corp.
agent: (any acknowledgment)
[process exits]
```

Expected side effect: `MemoryAwareRunner` post-turn remember has persisted
the user turn as an `Episode` (sourceType `Conversation`, groupId `"demo"`).

**Run 2 — recall (fresh process):**

```
user:  What is my favorite color?
agent: ...blue...
```

Expected mechanics: pre-turn recall on the query returns at least one
`MemoryHit` containing "blue"; `MemoryPolicy`'s render function injects it as
context; the model answers from that context. The example **must print the
injected memory block** so the mechanism is visible, not just the final
answer (§5).

**Pass criteria (assertable, not just eyeballed):**

- **A1:** run 2's final assistant message contains `blue` (case-insensitive).
- **A2:** run 2's printed injected-context block is non-empty and contains
  `blue`.

These are what the smoke test in §6 checks.

---

## 3. `FileBackedAgentMemory`

`InMemoryAgentMemory` cannot demonstrate cross-**process** recall: its `Ref`
dies with the JVM. The example therefore needs a persistence twist — a small
`AgentMemory[F]` that survives process exit.

### 3.1 Design

A ~60-line implementation that:

- Persists episodes as JSON lines to a file under the example's working
  directory (default `./.memory-demo/episodes.jsonl`); the storage path is
  injectable via constructor so tests can use temp dirs.
- `remember` appends one line; `recall` reads the full file and scores exactly
  like `InMemoryAgentMemory` (substring / term-overlap). If that scoring
  logic is private, consider extracting it into a small shared helper inside
  the examples package rather than copying — **⚠ VERIFY**
  `adk4s-memory-api`'s `InMemoryAgentMemory` source for visibility.
- Ignores `TemporalScope` (permitted by law 4, temporal ignorability).
- Reports `EpisodeOutcome.empty`-style counts (no extraction happens).

### 3.2 Placement decision

**Example-scoped, inside `adk4s-examples`** — not in `adk4s-memory-api`. The
API module's design boundary is "no storage engines"; a file is a storage
engine, however small. If it later proves broadly useful, promoting it is a
separate decision.

### 3.3 Contract obligation

`FileBackedAgentMemory` must pass
`AgentMemoryLaws(indexesContent = true).all` — add `FileBackedAgentMemorySpec`
mirroring `InMemoryAgentMemorySpec`. Even the demo double honors the contract;
this keeps the example honest and gives the testkit a second consumer inside
the repo.

---

## 4. Files and wiring

### 4.1 Layout

```
adk4s-examples/src/main/scala/org/adk4s/examples/memory/
├── FileBackedAgentMemory.scala   // §3
├── CrossRunMemoryExample.scala   // main: run mode selected by CLI arg
└── MemoryRetrieverExample.scala  // the second seam, single-run
adk4s-examples/src/test/scala/org/adk4s/examples/memory/
├── FileBackedAgentMemorySpec.scala
└── CrossRunMemorySmokeSpec.scala
```

Register in `run-example.sh` (keys: `crossrunmemory`, `memoryretriever`) and
in the README example catalog under a new **Memory (`memory/`)** category.

### 4.2 `CrossRunMemoryExample`

Single main class; the run is selected by CLI arg so the cross-process nature
is driven by *invocations*, not by anything clever inside:

```
./adk4s-examples/run-example.sh crossrunmemory teach
./adk4s-examples/run-example.sh crossrunmemory recall
./adk4s-examples/run-example.sh crossrunmemory reset    # deletes ./.memory-demo
```

Wiring sketch:

```scala
// ⚠ VERIFY exact signatures against adk4s-orchestration source:
//   ReactAgent.create, AgentRunner.create, MemoryAwareRunner, MemoryPolicy.default,
//   InMemoryCheckpointStore
val memory: AgentMemory[IO] = FileBackedAgentMemory[IO](dataDir)

val agent = ReactAgent.create(
  name = "memory-demo",
  description = "Assistant with durable memory",
  model = modelFromEnvOrMock(),        // existing example convention
  tools = Nil,
  systemPrompt = Some("You are a helpful assistant. Use any provided memory context."),
  maxSteps = 4
)

for
  runner  <- AgentRunner.create(agent, InMemoryCheckpointStore())
  mRunner  = MemoryAwareRunner(runner, Some(memory), MemoryPolicy.default)
  result  <- mRunner.run(List(UserMessage(inputForMode(mode))))
  _       <- printReport(result)       // §5
yield ()
```

### 4.3 Honest mock-model behavior

For the recall run, the mock must answer **from injected context**, not from
a canned script — otherwise the demo is a lie. Simplest honest approach: the
mock's response function scans the conversation for the memory-context block
and echoes the remembered value found there; it only "knows" blue because the
context contains it. **⚠ VERIFY** how existing examples construct their
deterministic mock `ChatModel` and follow that pattern. If
`MemoryPolicy.default`'s render prefix differs from the assumed
`"Relevant memory:"`, read the marker from the policy rather than hard-coding
it (**⚠ VERIFY** `MemoryPolicy` source for the render function's shape).

### 4.4 `MemoryRetrieverExample`

Small, single-run, shows the second seam: seed three episodes via
`memory.rememberAll`, build `MemoryRetriever[IO](memory, k = 2)`, invoke the
retriever with `"favorite color"` (**⚠ VERIFY** the `Retriever` method name),
and print the returned `Document`s with their metadata (score, provenance,
deterministic id) to show exactly what an agent consuming this retriever
would see.

---

## 5. Observability requirements

The example must narrate its mechanism. Minimum printed output for a recall
run:

```
── Pre-turn recall ─────────────────────────────
query : "What is my favorite color?"
hits  : 1
  [0.87] "My favorite color is blue, and I work at Acme Corp."
         provenance=demo  validFrom=2026-07-19T…
── Injected context ────────────────────────────
Relevant memory:
- My favorite color is blue… (since 2026-07-19T…)
── Agent answer ────────────────────────────────
Your favorite color is blue.
── Post-turn remember ──────────────────────────
episodes written: 1
```

Implementation note: if `MemoryAwareRunner` does not expose the recall hits /
injected context for printing (**⚠ VERIFY** its return type, and whether
`MemoryRecalled` / `MemoryWritten` `AgentEvent`s were implemented), the
example should call `memory.recall(...)` itself once, print the hits, then
run `MemoryAwareRunner` normally — a duplicated recall is acceptable in a
demo and avoids demanding new library surface. If the events *do* exist,
subscribe and print from the event stream instead; that is the better
version.

---

## 6. Tests

| Test | What it asserts |
|---|---|
| `FileBackedAgentMemorySpec` | `AgentMemoryLaws(indexesContent = true).all` green against the file-backed double (temp dir per test). |
| `CrossRunMemorySmokeSpec` | Drives teach-then-recall **in-process but across two fully rebuilt agent/runner/memory stacks** that share only the temp storage directory; asserts A1 + A2. True cross-JVM behavior is exercised by the shell invocations (and by CI running them, if configured); the in-process rebuild is the unit-testable approximation and catches any accidental in-memory state sharing. |

Both tests run with the mock model — no secrets, no network.

---

## 7. Documentation deliverables

- **README**: new *Memory* category in the examples table with three rows
  (`CrossRunMemoryExample`, `MemoryRetrieverExample`,
  `FileBackedAgentMemory` noted as the demo backend).
- **README orchestration section**: `MemoryAwareRunner` and `MemoryPolicy`
  added to the `adk4s-orchestration` module bullet plus a short subsection
  with the wrap-the-runner snippet — currently the memory hook is not
  documented in the orchestration docs at all.
- Both example files carry a top-of-file comment stating the two-run contract
  and the pass criteria (A1, A2), so each file is self-explaining when found
  via the catalog.

---

## 8. Acceptance checklist

- [ ] `run-example.sh crossrunmemory teach` then (new invocation) `… recall`
      answers "blue" on a clean checkout with no API key and no Docker.
- [ ] `… recall` printed output shows hits, injected context, answer, and
      write report (§5).
- [ ] The mock model demonstrably answers from injected context (§4.3), not
      from a script.
- [ ] `FileBackedAgentMemory` passes all four `AgentMemoryLaws`.
- [ ] `CrossRunMemorySmokeSpec` green without network access.
- [ ] `MemoryRetrieverExample` prints retrieved `Document`s with metadata.
- [ ] All `⚠ VERIFY` items resolved against source.
- [ ] README updated per §7.

## 9. Sequencing

1. Resolve the `⚠ VERIFY` items by reading `adk4s-orchestration` and
   `adk4s-memory-api` source (`MemoryAwareRunner`, `MemoryPolicy`,
   `AgentRunner.create`, mock-model convention).
2. `FileBackedAgentMemory` + its laws spec.
3. `CrossRunMemoryExample` + smoke test.
4. `MemoryRetrieverExample`.
5. README updates, pointing at working code.

Estimated size: ~300 lines including tests. No library changes expected; any
discovered need for one (e.g. exposing recall hits from `MemoryAwareRunner`)
is reported back as its own small proposal rather than smuggled in here.
