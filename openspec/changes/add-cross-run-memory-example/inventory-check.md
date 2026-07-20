# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-07-19
**Consistency check**: CLEAN — 0 stale rows fixed. Spot-verified all memory-api and orchestration-memory entries against source (package paths, method signatures, field shapes all match). Registry-check.sh: OK (611 tokens verified, 2 weak bindings in `react-agent.md` unrelated to this change).

## Verification procedure

The project inventory was seeded 2026-07-18 from the archived `add-memory-orchestration-hook` change and is comprehensive (469 rows per its own header). Rather than re-scan from scratch (which would lose `spec:` provenance), I spot-verified every entry this change reuses against the current source tree:

- `adk4s-memory-api/src/main/scala/org/adk4s/memory/` — `AgentMemory.scala`, `Episode.scala`, `MemoryHit.scala`, `InMemoryAgentMemory.scala`, `MemoryRetriever.scala` all present at the recorded package `org.adk4s.memory`.
- `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/` — `MemoryAwareRunner.scala`, `MemoryHook.scala`, `MemoryPolicy.scala` all present at `org.adk4s.orchestration.memory`.
- `adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala` — `MemoryRecalled` and `MemoryWritten` variants present at `org.adk4s.core.interrupt`.

No rows needed correction. The registry-check.sh script confirmed all 611 implementation-map tokens resolve against source.

## Stale rows fixed

None. The inventory's memory-related rows (lines 87, 128–141, 162–163, 217–221) match the current source exactly.

## Behavioral Concepts (registry pass)

**registry-check.sh**: `registry-check: OK (611 implementation-map tokens verified, 0 spec concept references checked, 2 weak binding(s) to tighten)`
**Stale implementation-map rows**: none (the 2 weak bindings are in `react-agent.md`, citing `isDefined`/`foreach` which exist elsewhere — unrelated to this change)
**Unregistered actions / syncs / state components**: none flagged by this change. The change consumes the existing `memory-aware-runner` concept (registry file `openspec/concepts/memory-aware-runner.md` exists) and the `agent-event-stream` concept (already extended with `MemoryRecalled`/`MemoryWritten` by the archived hook change). No new behavioral concept is introduced — `FileBackedAgentMemory` is a storage double, not a behavioral unit with its own operational principle.

## `⚠ VERIFY` items resolved against source

The source doc (`docs/adk4s-cross-run-memory-example.md`) flagged 5 signature inferences as `⚠ VERIFY`. All are resolved here against source — the spec can proceed with confirmed signatures:

| `⚠ VERIFY` item (source doc) | Resolution | Source evidence |
|-------------------------------|------------|-----------------|
| `MemoryAwareRunner` return type; whether `MemoryRecalled`/`MemoryWritten` events were implemented | **Events ARE available.** `runWithEvents(messages, maxSteps): (IO[RunResult], Stream[IO, AgentEvent])` returns the event stream. `MemoryAwareRunner` emits `MemoryRecalled(runPath, query, hitCount)` and `MemoryWritten(runPath, episodes)` via `emitMemoryRecalledIfMemoryPresent` / `emitMemoryWrittenIfMemoryPresentAndCompleted`. | `MemoryAwareRunner.scala:63, 179, 193`; `AgentEvent.scala:69–82` |
| `MemoryPolicy.default`'s render prefix (assumed `"Relevant memory:"`) | **Confirmed.** `MemoryPolicy.default = MemoryPolicy(recallK = 5)` with `render = defaultRender`. `defaultRender` produces `"Relevant memory:\n- ${h.text}"` per hit. The render function IS a field on the policy (`render: List[MemoryHit] => String`), so the example reads the marker from `policy.render(hits)` rather than hard-coding. | `MemoryPolicy.scala:18–24, 38, 43–47` |
| `Retriever` method name (for `MemoryRetrieverExample`) | **`retrieve(query: String, config: RetrieverConfig): F[List[Document]]`** (and `retrieveStream(query, config): Stream[F, Document]`). `MemoryRetriever.apply[F[_]: Sync](memory, k)` builds the retriever. | `MemoryRetriever.scala:23, 32, 38, 46` |
| `InMemoryAgentMemory` scoring visibility (private → extract helper?) | **Public — no extraction needed.** `InMemoryAgentMemory.naiveScore(contentLower: String, queryLower: String): Double` is a public method on the companion object. `FileBackedAgentMemory` calls it directly: `InMemoryAgentMemory.naiveScore(content, query)`. | `InMemoryAgentMemory.scala:52, 55` |
| Mock-model convention (how existing examples build the deterministic mock `ChatModel`) | **To be resolved in implementation step 1** by reading existing example sources (e.g. `ReactMemoryExample`). Not a concept-inventory item — it is an example-pattern lookup. The inventory confirms `ChatModel[F[_]]` is the trait to implement (`org.adk4s.core.component`). | `org.adk4s.core.component.ChatModel` (inventory row 161) |

**Observability consequence**: `MemoryRecalled` carries only `hitCount: Int`, not the hit texts. To print the full hit details (text, score, provenance) required by §5, the example calls `memory.recall(query, k, scope)` once itself, prints the hits, then runs `MemoryAwareRunner` normally. The `MemoryWritten` event carries `episodes: Int` — sufficient for the post-turn write report. This is the "duplicated recall is acceptable in a demo" path explicitly permitted by §5. The events stream is still subscribed for the count and write report (the better version).

## Concepts relevant to THIS change

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `AgentMemory[F[_]]` | service trait | `org.adk4s.memory` | reuse — implemented by `FileBackedAgentMemory` |
| `Episode` | case class | `org.adk4s.memory` | reuse — persisted as JSON lines |
| `EpisodeOutcome` | case class | `org.adk4s.memory` | reuse — returned by `remember` (empty-count instances) |
| `MemoryHit` | case class | `org.adk4s.memory` | reuse — returned by `recall`, printed in observability block |
| `SourceType` | enum | `org.adk4s.memory` | reuse — `Conversation` for user turns |
| `TemporalScope` | case class | `org.adk4s.memory` | reuse — ignored (law 4) |
| `InMemoryAgentMemory.naiveScore` | companion method | `org.adk4s.memory` | reuse — scoring logic for `FileBackedAgentMemory.recall` |
| `MemoryRetriever` | adapter class | `org.adk4s.memory` | reuse — `MemoryRetrieverExample` seam |
| `Retriever` / `Document` / `RetrieverConfig` | trait / case class | `org.adk4s.core.component` | reuse — `MemoryRetriever` adapts into `Retriever` |
| `AgentMemoryLaws` | laws trait | `org.adk4s.memory.testkit` | reuse — `FileBackedAgentMemorySpec` runs `AgentMemoryLaws(indexesContent = true).all` |
| `ReactAgent` | class | `org.adk4s.orchestration.agent` | reuse — `ReactAgent.create(...)` |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | reuse — `AgentRunner.create(agent, InMemoryCheckpointStore())` |
| `RunResult` | sealed trait | `org.adk4s.orchestration.agent` | reuse — pattern-matched by `printReport` |
| `InMemoryCheckpointStore` | class | `org.adk4s.orchestration.interrupt` | reuse — passed to `AgentRunner.create` |
| `MemoryAwareRunner` | decorator class | `org.adk4s.orchestration.memory` | reuse — wraps `AgentRunner` |
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` | reuse — `MemoryPolicy.default`, `policy.render` |
| `MemoryHook` | class | `org.adk4s.orchestration.memory` | reuse (indirect) — used internally by `MemoryAwareRunner`; `preTurnWithHits` / `postTurnWithCount` available if the example needs them |
| `MemoryRecalled` | AgentEvent variant | `org.adk4s.core.interrupt` | reuse — subscribed via `runWithEvents` for hit count |
| `MemoryWritten` | AgentEvent variant | `org.adk4s.core.interrupt` | reuse — subscribed via `runWithEvents` for write count |
| `AgentEvent` | sealed trait | `org.adk4s.core.interrupt` | reuse — event stream type |
| `ChatModel[F[_]]` | service trait | `org.adk4s.core.component` | reuse — mock implementation for the example |
| `UserMessage` / `Message` | case class | `llm4s` | reuse — `mRunner.run(List(UserMessage(...)))` |
| `FileBackedAgentMemory[F[_]]` | final class | `org.adk4s.examples.memory` | **introduce** — `AgentMemory[F]` double with JSON-lines persistence |
| `CrossRunMemoryExample` | `IOApp.Simple` object | `org.adk4s.examples.memory` | **introduce** — CLI-arg-driven teach/recall/reset entry point |
| `MemoryRetrieverExample` | `IOApp.Simple` object | `org.adk4s.examples.memory` | **introduce** — single-run retriever seam demo |
| JSON-lines `Episode` wire format | wire format | `org.adk4s.examples.memory` | **introduce** — one `Episode` per line via upickle `ReadWriter[Episode]` |

## Consequences for downstream artifacts

- **specs**: the spec can now write confirmed signatures (no `⚠ VERIFY` placeholders). The observability requirement (§5) is specified as: subscribe to `runWithEvents` for `MemoryRecalled.hitCount` and `MemoryWritten.episodes`, PLUS one direct `memory.recall(...)` call to print full hit details (text/score/provenance) that the event does not carry.
- **design**: `FileBackedAgentMemory.recall` delegates scoring to `InMemoryAgentMemory.naiveScore` (no helper extraction, no code duplication). The JSON-lines format uses upickle `ReadWriter[Episode]` — design records the derivation.
- **implementation-order**: step 1 (resolve `⚠ VERIFY` items) is now DONE at the inventory-check stage; the implementation order can start directly from `FileBackedAgentMemory`.
