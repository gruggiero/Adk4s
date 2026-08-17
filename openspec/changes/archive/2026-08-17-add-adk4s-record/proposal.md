# Proposal: `adk4s-record` — deterministic call recording and content-hash caching

## Why

Three independent roadmaps converge on one component. `dspy-port-analysis.md`
§4.7 states it directly: the rollout-id cache "is the same recording middleware
the ActiveGraph analysis wants for replay. One component, two roadmaps served."
It is three:

| Consumer | Needs recording for | Blocking? |
|---|---|---|
| `adk4s-optimize` Phase 1+ | Candidate evaluation without re-spending tokens; deliberate resampling for `BestOfN`/bootstrap rounds | **Yes** — §7 risk 2: "Do not ship BootstrapRS/GEPA before the content-hash cache exists" |
| DACE working memory | Replayable consolidation; fork-and-diff to tune retention weights empirically | **Yes** — without it, compaction policy is untunable folklore |
| `adk4s-journal` / replay | Deterministic replay over nondeterministic model calls | Yes, when scheduled |

The component is small, has no unresolved design risk, and is a strict subset of
the event-log substrate — nothing built here is wasted if the reactive kernel is
later built on top.

### The tension this change resolves

The two source analyses assume **different storage shapes** for the same
component:

- **ActiveGraph's** version is *log-backed*: the cache **is** the event log, a
  hit is a lookup of a recorded `llm.responded` event within a run, the record
  is the audit artifact, and it **never invalidates**.
- **DSPy's** version is a *side cache*: keyed by content hash plus `rolloutId`,
  scoped to an optimizer sweep, bounded and evictable, and it **must**
  invalidate.

These are compatible but not the same object. The resolution adopted here is a
single recording middleware over a **pluggable `Recorder[F]` sink** (§4.2 of the
requirements doc), with two conforming backends. Deciding this at design time is
cheaper than building one and retrofitting the other, because invalidation
semantics differ in opposite directions.

This proposal is grounded in `docs/adk4s-record-REQUIREMENTS.md` (REC-1 through
REC-25, RL0 through RL12, ring acceptance §7, exit criteria §8).

> **Note on requirements-doc drift.** The requirements doc was written against
> `build.sbt` at `main` and carries two verification notes (V1, V2). Both are
> re-checked below against the landed source. Of the three further items the
> doc did not flag, D1 *confirms* the doc's Iron assumption was correct (a
> prior draft of this proposal wrongly claimed Iron absent), while D2 and D3
> are genuine drifts the doc's assumptions must adjust for:
>
> - **V1 (resolved).** `AgentMiddleware.promptSections` **does** take a
>   `HarnessState` parameter
>   (`promptSections(state: HarnessState): List[PromptSection]`,
>   `org.adk4s.harness`). The harness-api Phase 0 change landed it. Not blocking
>   for this change, but the DACE change that follows can rely on state-derived
>   prompt sections.
> - **V2 (resolved).** Ring numbering: Ring 0 (scalac exhaustiveness), Ring 1
>   (WartRemover), Ring 2 (module purity / arch rules), Ring 3 (property tests),
>   Ring 4 (wire compat), Ring 5 (mutation), Ring 6 (Stainless), Ring 8
>   (adversarial review). Confirmed against `openspec/config.yaml` and
>   `build.sbt`.
> - **D1 (Iron IS in stack — requirements doc confirmed).** The requirements
>   doc §5 and §7.1 propose Iron-refined types (`RolloutId` non-empty,
>   `maxEntries` positive). Iron **IS PRESENT**: `project/Dependencies.scala`
>   defines `iron` (`iron` + `iron-cats` 3.3.2) and `ironUpickle`;
>   `openspec/capability-profile.md` records Iron as the refined-types library
>   (row: "Refined types | Iron | ... `MiddlewareName` (`NonEmpty`),
>   `StateCell.CellId`"); `openspec/concept-inventory.md` lists the established
>   Iron-refined types (`NodeKey` `NonEmpty & Not[Reserved]`, `Positive`,
>   `NonNegative`, `MiddlewareName`, `CheckpointId`). The `add-iron-refined-types`
>   change migrated the project's newtypes *to* Iron. This change therefore uses
>   Iron-refined types for `RolloutId` (`NonEmpty`) and `maxEntries`
>   (`numeric.Positive`), following the `NodeKey` `RefinedType` precedent and the
>   `refineEither` runtime-construction precedent in `ToolsNodeConfig` — exactly
>   as the requirements doc specified, with no new dependency.
> - **D2 (JSON currency).** The requirements doc §5 originally modeled `CanonicalForm.body`
>   as `ujson.Obj` and `CallRecord.payload` as `ujson.Value`. The
>   `migrate-json-codec` change (archived 2026-08-06) introduced
>   `JsonValue = smithy4s.Document` as ADK4S's internal JSON currency and
>   confined `ujson.Value` to the llm4s boundary via Scalafix rules. **RESOLVED:**
>   `CanonicalForm.body` is now a typed union (`CanonicalBody`) generated from
>   Smithy IDL via smithy4s codegen — `ModelBody`/`ToolBody`/`EmbeddingBody`
>   structures with `smithy4s.Document` for flexible fields (tool arguments, tool
>   schemas). ujson is confined to the llm4s boundary (`ToolCall.arguments:
>   ujson.Value` → `JsonValueCodec.fromUjson`). Recorded payloads (`Completion`,
>   tool results, embeddings) use a typed `RecordPayload` union (also generated
>   from Smithy IDL) — `ModelPayload`/`ToolPayload`/`EmbeddingPayload` — for
>   type-safe redaction and stable serialization. `Redaction` operates on
>   `RecordPayload => RecordPayload`, not untyped JSON.
> - **D3 (fs2-io vs fs2-core / main-scope law deps).** The requirements doc §3
>   build.sbt snippet lists `fs2Core` and `hedgehogMunit` (Test scope), but (a)
>   the file recorder signature uses `fs2.io.file.Path`, which is in **fs2-io**
>   not fs2-core, and (b) `RecorderLaws` ships in **main scope** (the
>   `adk4s-optimize` `OptimizerLaws` precedent and the `adk4s-harness-testkit`
>   precedent), which requires `hedgehogMunitMain` and `catsEffectTestkitMain`
>   in main scope. The build.sbt for this module will use `fs2` (core+io, io
>   scoped to the file recorder source per Ring 2) and the main-scope law
>   dependency variants.

## What Changes

A new `adk4s-record` module providing deterministic call recording and
content-hash caching for LLM, tool, and embedding calls. The module sits
directly above `adk4s-core` and below every consumer. Recording is opt-in at
wiring time by wrapping the `ChatModel` / `Embedder` / tool middleware a caller
passes in — no orchestration change required.

### Affected Capabilities

- `specs/call-key/spec.md` — canonical content-hash key (`CallKey`,
  `CanonicalForm`, `CallKind`), canonicalization rules (REC-1 through REC-8,
  REC-24, REC-25), tool-call id normalization (REC-4), `keyVersion` isolation.
- `specs/recorder-sink/spec.md` — effect-polymorphic `Recorder[F]` sink algebra
  with three reference implementations (`noop`, `inMemory`, `file`), append-only
  invariant, bounded eviction, sequence numbering, failure recording
  (REC-9 through REC-12, REC-19 through REC-21).
- `specs/recorded-wrappers/spec.md` — `RecordedChatModel[F]`, recording
  `ToolMiddleware`, `RecordedEmbedder[F]`; wrapper transparency, hit/miss
  semantics, write-failure containment, rollout-id sampling, redaction and
  classification (REC-13 through REC-18, REC-22 through REC-23).
- `specs/recorder-laws/spec.md` — `RecorderLaws` property testkit in main scope
  (RL0 through RL12), parameterized over `Recorder[F]`, Hedgehog properties
  including mutation-generator-driven RL3/RL4.
- `specs/recorder-verified-model/spec.md` — Ring 6 Stainless model in `verified`
  for recorder coherence (RL1) and tool-call id normalization idempotence /
  order preservation (REC-4).
- `specs/adk4s-record-module/spec.md` — module placement, build.sbt wiring,
  Ring 2 architecture rules (AR-REC-1, AR-REC-2), dependency purity.
- `specs/record-replay-example/spec.md` — example in `adk4s-examples`
  demonstrating zero-provider-call replay (exit criteria §8.5, §8.6).

### Out of Scope

- **Streaming recording.** `ChatModel.stream`/`streamContent` pass through
  unrecorded. Replaying a chunk sequence without original timing is a different
  contract, deferred until a consumer needs it (requirements doc §9.1).
- **Persistent event log / journal.** No SQLite or Postgres backend, no
  `runId`-scoped store, no fork. `Recorder[F]` is shaped so those slot in behind
  it unchanged.
- **Strict replay execution.** This change records the data strict replay needs
  and specifies the divergence contract as a law, but the replay driver belongs
  to the journal change.
- **Any optimizer.** `adk4s-optimize` Phase 1 is a separate change that depends
  on this one.
- **Cache eviction policy beyond a size bound.** No LRU tuning, no TTL.
- **Single-flight.** Two identical concurrent calls both miss and both spend
  tokens. Proposed as an optional recorder decorator in a future change
  (requirements doc §9.2), not a requirement here.

## Approach

A single recording middleware over a pluggable `Recorder[F]` sink, with two
conforming backends (bounded cache + append-only file). The canonicalization
function is pure and total, producing a stable `CallKey` from a `CanonicalForm`
that includes every field affecting the model's output distribution and excludes
every field that does not. Tool-call ids are normalized to positional
identifiers before hashing (REC-4) — the failure mode that silently destroys hit
rates. Wrappers (`RecordedChatModel`, recording `ToolMiddleware`,
`RecordedEmbedder`) sit below the middleware stack so the key reflects what was
actually sent to the provider. `RecorderLaws` (RL0–RL12) ships in main scope so
downstream backend authors can run the same properties. A Ring 6 Stainless model
in `verified` discharges recorder coherence and normalization idempotence.

## Correctness Risk Level

**Risk**: high — content-hash canonicalization is a silent-failure surface: a
single un-normalized tool-call id (REC-4), a single unordered collection
iteration (AR-REC-2), or a single ambient-nondeterminism leak (AR-REC-1)
silently empties every cache beyond turn one with no error and no test failure
unless the property suite specifically probes it. The requirements doc
explicitly identifies this as "the failure mode that silently destroys hit
rates." Combined with per-spec complexity (canonicalization is complex; wrappers
and recorder sink are moderate; module wiring is simple), this keeps the
typed-contract and test-oracle gates separate for every spec except the module
wiring spec.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags (`scala3Options` with
  `PatternMatchExhaustivity` and `MatchCaseUnreachable` escalated to errors),
  Iron-refined types (`RolloutId` `NonEmpty`, `maxEntries` `numeric.Positive`)
  following the `NodeKey` `RefinedType` / `ToolsNodeConfig` `refineEither`
  precedent (D1: Iron IS in stack)
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover (`Warts.unsafe` minus
  project exclusions, no new exclusions), dangerous-pattern scan
- [x] Ring 2: Architecture — module purity rule (`adk4s-record` must not depend
  on `workflows4s`, `adk4s-orchestration`, `adk4s-optimize`, `adk4s-eval`,
  `logback`; `fs2-io` confined to file recorder source), two new
  `scalafix-arch-rules` (AR-REC-1: no ambient nondeterminism in canonicalization;
  AR-REC-2: no unordered iteration in canonicalization)
- [x] Ring 3: Property-based tests — MANDATORY. Hedgehog properties RL0–RL12
  against all three reference recorders (`noop`, `inMemory`, `file`). RL3/RL4
  must be mutation-generator-driven over a `RequestMutation` ADT. **No
  concurrent behavior in this change** — the wrappers are sequential
  (single-flight is explicitly out of scope, §9.2), so no deterministic
  concurrency test kit is required. The `file` recorder uses `Async[F]` for
  resource-safe file I/O but introduces no parallelism, cancellation, or
  timeouts beyond `Resource` scope management.
- [x] Ring 4: Wire/persistence compatibility — the `file` recorder writes JSONL
  records to disk (persisted data). Round-trip codec tests (RL7), golden-file
  canonical-form pinning (requirements doc §9.5), `keyVersion` forward-compat
  (REC-25: old-version records treated as absent, not errors)
- [x] Ring 5: Mutation testing — Stryker4s on the canonicalization package and
  `Recorder` implementations (the two places where a surviving mutant means a
  silently wrong key or a silently lost record). Threshold at or above project
  default (break=90/low=91/high=95), not relaxed
- [x] Ring 6: Formal verification — Stainless model in `verified` (leaf module,
  Scala 3.7.2) using the verified-mirror pattern: (1) recorder coherence (RL1
  pair, finite-map model, `HarnessState` get/set coherence precedent), (2)
  tool-call id normalization idempotence and order preservation (REC-4).
  Hash collision-freedom assumed (injective abstract function, stated explicitly)
- [ ] Ring 7: Model checking — not applicable (no distributed/event-driven
  invariants; sequence numbering is a monotonic counter, not a distributed
  ordering protocol)
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY (fresh-context
  reviewer, runs before Rings 5/6/7 in the apply sequence)
- [ ] Ring 9: Telemetry — not applicable (no telemetry stack detected:
  `openspec/config.yaml` confirms otel4s is NOT PRESENT; this change does not
  affect API operations or event sequences in the `AgentEvent` sense)

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

| Spec | Typed contract | Justification |
|------|----------------|---------------|
| `specs/call-key/spec.md` | Full | Introduces new ADT (`CallKey`, `CanonicalForm`, `CallKind`) and the canonicalization algorithm — the highest-risk pure kernel in the change |
| `specs/recorder-sink/spec.md` | Full | Introduces `Recorder[F]` trait, `CallRecord` ADT (`Succeeded`/`Failed`), three implementations, sequence numbering, persistence (JSONL) |
| `specs/recorded-wrappers/spec.md` | Full | Introduces `RecordedChatModel`, recording `ToolMiddleware`, `RecordedEmbedder` — public API wrappers around `ChatModel`/`Embedder`/tools |
| `specs/recorder-laws/spec.md` | Full | Introduces `RecorderLaws` (main-scope API), `RequestMutation` ADT for RL3/RL4, Hedgehog property definitions |
| `specs/recorder-verified-model/spec.md` | Full | Introduces PureScala model in `verified` + bridge property test (verified-mirror pattern) |
| `specs/adk4s-record-module/spec.md` | Full | New sbt module, build.sbt wiring, Ring 2 arch rules (AR-REC-1, AR-REC-2) |
| `specs/record-replay-example/spec.md` | Full | New example in `adk4s-examples`, demonstrates exit criteria §8.5/§8.6 |

## Existing Concepts to Reuse

Populated from `openspec/concept-inventory.md` (160 typed rows) and verified
against landed source in this session.

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `ChatModel[F[_]]` | trait (`F[_]`) | `org.adk4s.core.component` | Wrapped by `RecordedChatModel`; `generate`, `stream`, `withConfig` |
| `Embedder[F[_]]` | trait (`F[_]`) | `org.adk4s.core.component` | Wrapped by `RecordedEmbedder`; `embed`, `embedBatch`, `dimension` |
| `InvokableTool[F[_]]` | trait (`F[_]`) | `org.adk4s.core.component` | Tool execution surface; `run(ujson.Value): F[ujson.Value]` |
| `ToolMiddleware` | type alias (`ToolEndpoint => ToolEndpoint`) | `org.adk4s.core.tools` | Kleisli endomorphism; recording middleware composes with existing `logging`/`timing`/`validation` set |
| `ToolInput` | case class | `org.adk4s.core.tools` | `name`, `arguments`, `callId` — `callId` is the field REC-4 normalizes |
| `ToolOutput` | case class | `org.adk4s.core.tools` | `name`, `result`, `callId`, `isError` — `callId` paired with `ToolInput.callId` |
| `AdkError` | sealed trait | `org.adk4s.core.error` | Error hierarchy; recording failures are first-class records, not new `AdkError` variants (REC-21) |
| `JsonValue` | type alias (`= smithy4s.Document`) | `org.adk4s.core.json` | Internal JSON currency post-migration; design phase decides payload representation (D2) |
| `JsonValueCodec` | object | `org.adk4s.core.json` | Boundary adapter `toUjson`/`fromUjson` — bridges canonical-form body to llm4s boundary if needed |
| `NodeKey` (Iron `RefinedType` pattern) | opaque type (`String :| NonEmpty & Not[Reserved]`) | `org.adk4s.core.types` | The established Iron-refined-newtype precedent this change follows for `RolloutId`; `RefinedType` with compile-time literal checking + `refineEither` runtime construction (D1) |
| `Positive` / `NonNegative` (Iron constraints) | type (`Int :| numeric.Positive` / `numeric.Positive0`) | `org.adk4s.core.types` | Iron numeric constraints reused for `maxEntries` (`Positive`); `refineEither` runtime construction precedent in `ToolsNodeConfig` (D1) |
| `CompletionOptions` | (llm4s) | `llm4s` | `temperature`, `maxTokens`, `topP`, `stopSequences` — the output-affecting fields in REC-2 |
| `Completion` | (llm4s) | `llm4s` | Model call result; recorded as payload on hit |
| `Conversation` / `Message` / `AssistantMessage` | (llm4s) | `llm4s` | Message list in canonical form (REC-2); `AssistantMessage.toolCalls` carry provider-generated ids (REC-4) |
| `ToolFunction` / `ToolRegistry` | (llm4s) | `llm4s` | Tool-definition list in canonical form (REC-2: name, description, parameter schema) |
| `ModelStep[F[_]]` | type alias (`Kleisli[F, ModelRequest[F], ModelResponse]`) | `org.adk4s.harness` | Recording sits below the middleware stack, wrapping the `ModelStep` base (requirements doc §5.3) |
| `ModelRequest[F[_]]` | case class | `org.adk4s.harness` | `systemPrompt`, `messages`, `tools`, `options`, `state` — the request surface canonicalization reads |
| `DeterministicChatModel` | test double | `org.adk4s.harness.testkit` | Seed-based `ChatModel[IO]` double with `RecordedRequest` trace capture — reusable for RL0 transparency and RL5 zero-call hit |
| `RecordedRequest` | case class | `org.adk4s.harness.testkit` | `renderedSystemPrompt`, `messages`, `toolNames` — trace capture precedent |
| `Observation` | case class | `org.adk4s.harness.testkit` | Observational equivalence (`≍`) — RL0 transparency mirrors this pattern |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `CallKey` | opaque type (`String`) | Content hash of a canonical request form; stable across processes and JVMs |
| `CanonicalForm` | final case class | Inspectable canonical form (`keyVersion`, `kind`, `body`); diffable when keys mismatch (REC-8) |
| `CallKind` | enum | `Model` / `Tool` / `Embedding` — discriminates canonicalization strategy |
| `RolloutId` | opaque type (`String :| NonEmpty`, Iron `RefinedType`) | Deliberate-resampling discriminator; Iron-refined non-empty, following the `NodeKey` precedent (D1: Iron IS in stack) |
| `CallRecord` | enum (ADT) | `Succeeded(key, seq, kind, payload, classification)` / `Failed(key, seq, kind, error, classification)` — first-class failure recording (REC-21) |
| `RecordedError` | final case class | Captured failure detail for replay fidelity (RL9) |
| `Classification` | enum / opaque type | Data-classification marker traveling with every record (REC-23) |
| `Recorder[F[_]]` | trait | Effect-polymorphic sink algebra: `lookup`, `record`, `nextSeq` |
| `Recorder.noop` / `Recorder.inMemory` / `Recorder.file` | implementations | Three reference backends (REC-10) |
| `RecordedChatModel[F[_]]` | wrapper | `ChatModel[F]` decorator with recording + caching |
| `ToolMiddleware.recording` | factory | Recording `ToolMiddleware` (Kleisli) composing with existing set |
| `RecordedEmbedder[F[_]]` | wrapper | `Embedder[F]` decorator with recording + caching |
| `RecorderLaws` | class (main scope) | RL0–RL12 Hedgehog properties, parameterized over `Recorder[F]` |
| `RequestMutation` | ADT | Mutation generator for RL3 (key sensitivity) / RL4 (key insensitivity) |
| `Redaction` | function type | Payload redaction applied after key computation (REC-22) |
| `keyVersion` | constant | Canonicalization algorithm version; incremented on breaking canonicalization change (REC-24) |
| `RecorderCoherenceModel` | PureScala model (`verified`) | Ring 6 finite-map model for RL1 coherence |
| `NormalizationModel` | PureScala model (`verified`) | Ring 6 model for REC-4 normalization idempotence / order preservation |
| `AR-REC-1` / `AR-REC-2` | Scalafix arch rules | No ambient nondeterminism / no unordered iteration in canonicalization package |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| **Silent hit-rate collapse from un-normalized tool-call ids (REC-4).** Provider-minted ids enter the hash → every turn after the first tool call has an unrepeatable key. | RL4 mutation property (regenerate tool-call ids, key unchanged); Ring 6 normalization idempotence/order-preservation proof; exit criterion §8.6 (multi-turn tool-calling full cache hit on replay) |
| **Ambient nondeterminism in canonicalization (REC-5).** `System.currentTimeMillis`, `UUID.randomUUID`, `.hashCode` on non-value types — cross-process key instability. | AR-REC-1 Scalafix arch rule (mechanical enforcement); RL2 key determinism property (cross-process); Ring 2 dependency purity |
| **Unordered collection iteration (AR-REC-2).** `Map`/`Set` iteration order is JVM-dependent → same request, different keys across processes. | AR-REC-2 Scalafix arch rule (no direct iteration over `Map`/`Set` without explicit sort); RL2 key determinism |
| **Write-failure takes down an agent run (REC-16).** A cache write failure must not fail the call. | RL10 write-failure containment property; inverted policy for journal backends flagged as open question §9.3 (design phase decides `OnWriteFailure.Continue | Fail`) |
| **Key instability across llm4s upgrades (§9.5).** llm4s message representation changes shift canonical forms without an intentional `keyVersion` bump. | Golden-file test pinning canonical forms for a fixed corpus; `keyVersion` mechanism (REC-24/REC-25) for intentional bumps |
| **Invalid `RolloutId`/`maxEntries` constructed at runtime (REC-2, §7.1).** Empty rollout id or non-positive entry bound silently corrupts key separation / cache behaviour. | Iron-refined types (`RolloutId` `NonEmpty`, `maxEntries` `numeric.Positive`) following the `NodeKey` `RefinedType` precedent; `refineEither` runtime construction returns `Either[String, A]` at the boundary (D1: Iron IS in stack — no new dependency) |
| **JSON currency drift (D2).** Requirements doc originally used `ujson.Value`; post-migration currency is `JsonValue`. | **RESOLVED:** CanonicalForm.body is a typed union generated from Smithy IDL (CanonicalBody: ModelBody/ToolBody/EmbeddingBody) with `smithy4s.Document` for flexible fields. ujson confined to llm4s boundary via `JsonValueCodec`. Record payloads use typed `RecordPayload` union (ModelPayload/ToolPayload/EmbeddingPayload), also from Smithy IDL. `Redaction` is `RecordPayload => RecordPayload` (type-safe). |
| **fs2-io scope (D3).** File recorder needs `fs2.io.file.Path` (fs2-io), but module purity rule confines fs2-io to file recorder source. | Ring 2 arch rule: fs2-io dependency allowed but source-scoped to file recorder; canonicalization and key computation are pure (no fs2) |
