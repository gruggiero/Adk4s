# Design: Cross-Run Memory Example

## 1. Package structure

All new code lives in a single new package under the examples module:

```
adk4s-examples/src/main/scala/org/adk4s/examples/memory/
├── FileBackedAgentMemory.scala
├── CrossRunMemoryExample.scala
└── MemoryRetrieverExample.scala
adk4s-examples/src/test/scala/org/adk4s/examples/memory/
├── FileBackedAgentMemorySpec.scala
└── CrossRunMemorySmokeSpec.scala
```

### Layer dependency rules (Ring 2 — advisory, enforced by code review + import audit)

Per `openspec/capability-profile.md` line 141: `org.adk4s.examples.*` may import everything (examples are edge code). The new `org.adk4s.examples.memory` package inherits this. No new purity boundary is introduced.

| Package | May import | Must NOT import |
|---------|------------|-----------------|
| `org.adk4s.examples.memory` (main) | `org.adk4s.memory.*`, `org.adk4s.memory.testkit.*` (NO — testkit is test-scope), `org.adk4s.orchestration.memory.*`, `org.adk4s.orchestration.agent.*`, `org.adk4s.orchestration.interrupt.*`, `org.adk4s.core.component.*`, `org.adk4s.core.interrupt.*`, `llm4s` (`Message`, `UserMessage`), `cats-effect`, `fs2`, `upickle`/`ujson` | `org.adk4s.memory.testkit.*` (test-scope only — compile-negative obligation), `workflows4s`, `logback` (examples use it but the memory examples do not need it) |
| `org.adk4s.examples.memory` (test) | everything above PLUS `org.adk4s.memory.testkit.*` (`AgentMemoryLaws`), `munit`, `munit-cats-effect`, `hedgehog.munit` | — |

The one build change (`adk4s-examples % Test → adk4s-memory-testkit`) is the only dependency edit. No main-scope coupling to the testkit.

## 2. Effect boundaries

| Component | Pure? | Effectful? | Notes |
|-----------|-------|-----------|-------|
| `FileBackedAgentMemory.recall` scoring | ✅ pure | — | delegates to `InMemoryAgentMemory.naiveScore(contentLower, queryLower): Double` — a pure function on two strings |
| `FileBackedAgentMemory.recall` file read | — | ✅ `F[_]: Sync` | reads the JSON-lines file, parses each line via upickle `read[Episode]`, scores, sorts, takes top-k |
| `FileBackedAgentMemory.remember` | — | ✅ `F[_]: Sync` | appends one JSON line via `write[Episode].write` to the file |
| `FileBackedAgentMemory.rememberAll` | — | ✅ `F[_]: Sync` | default implementation from `AgentMemory` trait (traverses `episodes`, calls `remember` each) — or overridden to batch-append for efficiency |
| `CrossRunMemoryExample.run` | — | ✅ `IO` | builds the agent/runner/memory stack, runs `MemoryAwareRunner.runWithEvents`, prints the observability report |
| `MemoryRetrieverExample.run` | — | ✅ `IO` | seeds episodes, builds `MemoryRetriever`, calls `retrieve`, prints documents |
| Mock `ChatModel.generate` | ✅ pure (deterministic) | — | scans the conversation for the `"Relevant memory:"` marker, echoes the hit text found there; no I/O |

**Ring 6 (formal verification)**: NOT applicable. The pure functions (`naiveScore`, the mock's context-scan) are trivial string operations on edge code, not algorithmic logic worth formal verification. The `verified` module is reserved for algorithmic mirrors.

## 3. Type strategy — invalid-state prevention

| Invariant | Placement | Justification |
|-----------|-----------|---------------|
| `FileBackedAgentMemory` storage path is a directory that exists or can be created | Good — smart constructor | `FileBackedAgentMemory.apply[F[_]: Sync](dataDir: Path): F[FileBackedAgentMemory[F]]` creates the directory if it does not exist (`Files.createDirectories`). The constructor returns `F[...]`, not a pure value, because directory creation is an effect. |
| Episodes file is treated as absent on first recall (no error) | Good — runtime check in `recall` | `if Files.exists(episodesFile) then readAndScore else IO.pure(Nil)`. The missing file is NOT an error — it is zero episodes. |
| `recall` returns at most `k` hits | Good — `take(k)` in `recall` | after sorting by score descending, `.take(k)` enforces the limit |
| Scoring matches `InMemoryAgentMemory.naiveScore` exactly | Good — property test (Property 3) | the implementation calls `naiveScore` directly (no reimplementation), so the invariant holds by construction; the property test guards against drift |
| Mock model does not answer from a canned script | Okay — scenario test + adversarial review | the mock's response function scans for the `"Relevant memory:"` marker; the adversarial scenario (Req 4 sc 2) asserts no "blue" when no context is injected. Cannot be made a type-level invariant because "answers from context" is behavioral. |
| No main-scope import of `adk4s-memory-testkit` | Best — build-scope separation | the `% Test` dependency makes the testkit unavailable in main sources at compile time. Compile-negative obligation (Req 7 sc 2). |

No invalid state is allowed to reach the evaluator and return a fallback. The one "Risky" path (mock answering from context) is pinned by an adversarial scenario test, not by a fallback default.

## 4. Refined type strategy

No refined types are introduced. Per `openspec/capability-profile.md`: no Iron/refined library is present. The existing `AgentMemory` API uses plain typed values (`Int` for `k`, `String` for content/query, `Option[Instant]` for temporal scope). `FileBackedAgentMemory` follows the same convention:

- `dataDir: Path` — plain `java.nio.file.Path` (no newtype; it is an example, not a library type)
- `episodesFile: Path` — derived as `dataDir.resolve("episodes.jsonl")` (no newtype)
- `k: Int` — passed through to `recall` unchanged (the `AgentMemory` trait already accepts `Int`)

The JSON-lines wire format (see §7) uses upickle `ReadWriter[Episode]` derived from the `Episode` case class — no hand-written schema, no refined constraints on persisted fields.

## 5. IDL model layout

Not applicable. This change introduces no Smithy/protobuf operations. The `Episode` type is a Scala case class from `adk4s-memory-api`, not an IDL model. The JSON-lines format is an example-scoped persistence twist using upickle, not a wire protocol.

## 6. Error strategy

`FileBackedAgentMemory` introduces NO new error variants. It implements `AgentMemory[F]`, whose methods return `F[EpisodeOutcome]` and `F[List[MemoryHit]]` — no `Either` / sealed error hierarchy. Errors surface as `F` failures (exceptions raised by `Sync`):

| Error condition | How it surfaces | Handling |
|----------------|----------------|----------|
| Episodes file is missing on `recall` | NOT an error — returns `Nil` | `if Files.exists(...) then ... else IO.pure(Nil)` |
| Episodes file contains a malformed JSON line | `F` failure (upickle `ParseException`) | the line is skipped with a warning printed to stdout (demo code — examples may print). The recall continues with the parseable lines. This is the one contained deviation from "no swallowed errors": a corrupted line in a demo file is not fatal. Documented in the file-level comment. |
| Directory cannot be created | `F` failure (`IOException`) | propagates to the caller — the example prints the error and exits |
| `remember` fails to append (disk full, permissions) | `F` failure (`IOException`) | propagates |

The mock `ChatModel` is deterministic and total — it always returns a `Completion` for any input conversation. No error path.

`CrossRunMemoryExample` pattern-matches `RunResult` (`Completed` / `Interrupted` / `Failed`) exhaustively. The exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) is active, so a missing branch fails Ring 0. The `Interrupted` and `Failed` branches print the result and exit — they do not attempt recall/remember (consistent with `MemoryAwareRunner`'s own behavior, which skips `postTurn` on non-`Completed` results).

## 7. Compatibility story (Ring 4)

The JSON-lines `Episode` wire format is the one new persistence format. It is example-scoped (lives in `adk4s-examples`, not a library module), so there are no old fixtures to decode and no schema evolution concerns across releases. The compatibility story is:

| Concern | Strategy | Test |
|---------|----------|------|
| Round-trip: `remember` then `recall` returns the same episode | upickle `ReadWriter[Episode]` derived from the case class — `write[Episode]` / `read[Episode]` are inverses by construction | Property 1 (round-trip) |
| Reload across instances: fresh `FileBackedAgentMemory` at the same path recalls episodes written by a prior instance | the file is the sole state; no in-memory cache — every `recall` reads the file fresh | Property 2 (reload equivalence) |
| Cross-process: episodes survive JVM exit | the file is on disk; a new JVM constructs a new `FileBackedAgentMemory` pointing at the same path | Scenario test (two rebuilt stacks sharing only the temp dir) + documented shell invocations |
| Unknown/missing fields in a JSON line | upickle's default behavior for case-class `ReadWriter`: missing fields use the case class default (if any) or fail. `Episode` has no defaults — a missing field fails the line parse, and the line is skipped (see §6). | not separately tested (demo code) |
| Schema evolution | N/A — example-scoped format, no versioning | — |

The `ReadWriter[Episode]` is derived via upickle's `macroRW[Episode]` (or `readWriter[Episode]`) in the `FileBackedAgentMemory` companion. No hand-written serializer. The `Episode` case class fields (`content: String`, `sourceType: SourceType`, `timestamp: Instant`, `groupId: Option[String]`, `metadata: Map[String, String]`) are all upickle-supported out of the box (`SourceType` is a Scala 3 enum with `String`-based serialization via upickle's `ReadWriter.derived`).

**`Instant` serialization**: upickle does not serialize `java.time.Instant` by default. The design adds a `given ReadWriter[Instant]` in the `FileBackedAgentMemory` companion (e.g. `bimap(_.toString, Instant.parse)`). This is the one custom serializer — recorded here so the implementation does not forget it.

## 8. Verification map

| Module / Package | Ring 0 | Ring 1 | Ring 2 | Ring 3 | Ring 4 | Ring 5 | Ring 6 | Ring 7 | Ring 8 | Ring 9 |
|-----------------|--------|--------|--------|--------|--------|--------|--------|--------|--------|--------|
| `org.adk4s.examples.memory` (main) | ✅ `sbt adk4s-examples/compile` | ✅ Scalafix + WartRemover (relaxed set) + scalafmt | ⚠️ advisory (examples may import everything) | ✅ via test oracle | ✅ JSON-lines round-trip + reload properties | ⏸ skipped (demo code) | ⏸ N/A (not `verified` module) | ❌ no TLA+ | ✅ mandatory adversarial | ❌ no telemetry |
| `org.adk4s.examples.memory` (test) | ✅ `sbt adk4s-examples/Test/compile` | ✅ | — | ✅ laws suite + smoke scenarios | ✅ | — | — | — | — | — |
| `build.sbt` (adk4s-examples dep edit) | ✅ `sbt adk4s-examples/compile` | — | — | ✅ compile-negative (test-scope only) | — | — | — | — | — | — |
| `README.md` | — | — | — | — | — | — | — | — | ✅ manual review | — |
| `run-example.sh` | — | — | — | ✅ manual run (documented) | — | — | — | — | — | — |

### Human gate tier

Per the proposal: **risk = low**, **complexity = simple** (examples + tests, no library code, one ~60-line double pinned by existing laws). Per the schema's gate-tier rule, simple+low specs MAY combine the typed-contract and test-oracle gates into one presentation. The implementation-order artifact will record this.

## 9. Mock `ChatModel` design (the adversarial core)

The mock is the most design-sensitive component because it is where the demo could lie. The design pins it:

```scala
// Inside CrossRunMemoryExample — private, not reusable
private final class MemoryDemoMockModel extends ChatModel[IO]:
  def generate(conversation: Conversation): IO[Completion] =
    val lastUserText: String = conversation.messages.collect {
      case m: UserMessage => m.content
    }.lastOption.getOrElse("")
    // Scan ALL messages (not just the last user message) for the injected block.
    // MemoryAwareRunner prepends the block to the latest user message OR
    // inserts it as a separate system/user message — the mock checks both.
    val allText: String = conversation.messages.map(_.content).mkString("\n")
    val memoryMarker: String = "Relevant memory:"
    val answer: String =
      if allText.contains(memoryMarker) then
        // Extract the hit text after the marker — echo the first hit line
        val afterMarker: String = allText.drop(allText.indexOf(memoryMarker) + memoryMarker.length)
        val hitLine: Option[String] = afterMarker.linesIterator.find(_.trim.startsWith("-"))
        hitLine.map(_.trim.stripPrefix("-").trim).getOrElse("I see memory context but could not parse it.")
      else
        // NO injected context — the mock has no source for any fact.
        // Return a generic acknowledgment that does NOT contain any fact value.
        "I don't have any memory context to draw on."
    IO.pure(Completion(AssistantMessage(answer, Nil)))
```

Key design decisions:
1. **The mock scans all messages**, not just the last user message, because `MemoryAwareRunner.injectContext` may prepend the block to the user message or insert it separately. The mock is robust to both injection shapes.
2. **The marker `"Relevant memory:"` is read from `MemoryPolicy.defaultRender`'s output**, not hardcoded as a magic string — but since `defaultRender` is the default `render` field of `MemoryPolicy.default`, and the mock runs with `MemoryPolicy.default`, the marker is stable. The implementation comment notes this coupling.
3. **When no marker is found, the mock returns a generic acknowledgment** that does NOT contain any fact value ("blue", "Acme Corp", etc.). This is what makes the adversarial scenario (Req 4 sc 2) work: without injected context, the mock cannot produce "blue".
4. **The mock is `IO.pure`** — deterministic, no `TestControl` needed.

## 10. Observability implementation (Req 5)

`MemoryAwareRunner.runWithEvents` returns `(IO[RunResult], Stream[IO, AgentEvent])`. The example:

1. Calls `memory.recall(query, k, scope)` once directly → prints the `Pre-turn recall` section with full hit details (text, score, provenance). This is the "duplicated recall is acceptable in a demo" path from the source doc §5, because `MemoryRecalled` carries only `hitCount`, not the hits.
2. Runs `mRunner.runWithEvents(messages)` → collects the `MemoryRecalled` and `MemoryWritten` events from the stream for the count and write report.
3. Prints the `Injected context` section using `MemoryPolicy.default.render(hits)` (the same render function the runner uses) — so the printed block matches what the model actually saw.
4. Prints the `Agent answer` section from `RunResult.Completed.output`.
5. Prints the `Post-turn remember` section using the `MemoryWritten.episodes` count from the event stream.

The four sections are printed in order via `IO.println`. The smoke test captures stdout (or the example returns the report as a `String` for testability — the design prefers returning a `String` report that the example prints, so the test can assert on it without capturing stdout).

**Design decision**: `CrossRunMemoryExample.run` returns `(RunResult, String)` where the `String` is the formatted report. The `main` method prints the report. The smoke test asserts on the report `String` directly. This avoids stdout capture in tests.
