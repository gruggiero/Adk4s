# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept. -->

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/adk4s-record-module/spec.md` | `adk4s-record` module, `AR-REC-1`, `AR-REC-2` | (none — foundational module setup) | simple |
| 2 | `specs/call-key/spec.md` | `CallKey`, `CanonicalForm`, `CallKind`, `RolloutId`, `keyVersion`, `RequestMutation`, `NonAffectingMutation` | `ChatModel`, `Embedder`, `InvokableTool`, `ToolInput`, `ToolOutput`, `NodeKey`, `Positive`, `NonEmpty`, `JsonValue`, `JsonValueCodec` (all from inventory) | high |
| 3 | `specs/recorder-sink/spec.md` | `Recorder[F]`, `CallRecord`, `RecordedError`, `Classification`, `Recorder.noop`, `Recorder.inMemory`, `Recorder.file` | `CallKey`, `CallKind` (from spec 2), `Positive`, `JsonValue`, `AdkError` (from inventory) | high |
| 4 | `specs/recorder-verified-model/spec.md` | `RecorderCoherenceModel`, `NormalizationModel` | `CallKey`, `CallRecord`, `Recorder` (from specs 2, 3), `PredictorKernel`, `SemilatticeKernel` (precedents from inventory) | high |
| 5 | `specs/recorded-wrappers/spec.md` | `RecordedChatModel`, `ToolMiddleware.recording`, `RecordedEmbedder`, `Redaction` | `CallKey`, `CallKind`, `RolloutId` (from spec 2), `Recorder`, `CallRecord`, `Classification` (from spec 3), `ChatModel`, `Embedder`, `ToolMiddleware`, `ModelStep`, `ModelRequest`, `DeterministicChatModel`, `RecordedRequest`, `Observation` (from inventory) | high |
| 6 | `specs/recorder-laws/spec.md` | `RecorderLaws`, `RequestMutation` (shared with spec 2), `NonAffectingMutation` (shared with spec 2) | `CallKey`, `CallRecord`, `Recorder`, `RolloutId` (from specs 2, 3), `ChatModel`, `Embedder`, `ToolMiddleware`, `DeterministicChatModel`, `RecordedRequest`, `Observation`, `AgentMiddlewareLaws`, `SemilatticeLaws` (from inventory) | medium |
| 7 | `specs/record-replay-example/spec.md` | `RecordReplayExample` | `RecordedChatModel` (from spec 5), `Recorder.file`, `Recorder.noop` (from spec 3), `ChatModel`, `ReactAgent`, `AgentRunner`, `RunResult`, `DeterministicChatModel` (from inventory) | simple |

### Topological Sort Rationale

1. **adk4s-record-module** (first): creates the sbt module and arch rules
   that all other specs depend on. No concept dependencies — foundational.
2. **call-key** (second): introduces `CallKey`, `CanonicalForm`, `CallKind`,
   `RolloutId` — the pure canonicalization kernel. Every other spec depends
   on these types. High complexity (new types + complex logic + Ring 6).
3. **recorder-sink** (third): introduces `Recorder[F]`, `CallRecord` — the
   sink algebra. Depends on `CallKey`/`CallKind` from call-key. High
   complexity (new types + effectful + Ring 6 model).
4. **recorder-verified-model** (fourth): introduces the Ring 6 PureScala
   models. Depends on `CallKey`, `CallRecord`, `Recorder` from specs 2, 3.
   High complexity (Stainless formal verification). Could be done in
   parallel with spec 5 conceptually, but placing it before wrappers
   ensures the verified model is available when the wrappers are tested.
5. **recorded-wrappers** (fifth): introduces `RecordedChatModel`,
   `ToolMiddleware.recording`, `RecordedEmbedder`. Depends on concepts from
   specs 2, 3. High complexity (new types + effectful + multiple Ring 3
   properties).
6. **recorder-laws** (sixth): introduces `RecorderLaws`. Depends on concepts
   from specs 2, 3, 5. Medium complexity (testkit, no new production types
   beyond the laws class).
7. **record-replay-example** (seventh): introduces `RecordReplayExample`.
   Depends on wrappers (spec 5) and recorder (spec 3). Simple complexity
   (application-edge example, no new library types).

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | adk4s-record-module | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | minimal |
| 2 | call-key | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | partial | — | ✅ | — | full |
| 3 | recorder-sink | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | partial | — | ✅ | — | full |
| 4 | recorder-verified-model | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | ✅ | — | full |
| 5 | recorded-wrappers | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 6 | recorder-laws | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | full |
| 7 | record-replay-example | ✅ | — | — | ✅ | — | — | — | — | ✅ | — | waiver |

> R6 is "partial" for call-key and recorder-sink: only the normalization
> algorithm (NormalizationModel) and recorder coherence
> (RecorderCoherenceModel) are modeled in PureScala. The rest of
> canonicalization and the effectful recorders are covered by Ring 3
> properties. Spec 4 (recorder-verified-model) is the dedicated Ring 6
> spec that creates and verifies these models.
>
> Typed contract for spec 7 is "waiver" — it is an example
> (application-edge code), not a library spec. The example's correctness
> is demonstrated by the RecorderLaws (RL0–RL12) and by the scenario
> assertions, which are runnable as integration tests.

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | adk4s-record-module | `build.sbt` (new module), `.scalafix.conf` (AR-REC-1, AR-REC-2 rules) |
| 2 | call-key | `adk4s-record/src/main/scala/org/adk4s/record/CallKey.scala`, `adk4s-record/src/main/scala/org/adk4s/record/CanonicalForm.scala`, `adk4s-record/src/main/scala/org/adk4s/record/CallKind.scala`, `adk4s-record/src/main/scala/org/adk4s/record/RolloutId.scala`, `adk4s-record/src/main/scala/org/adk4s/record/canonical/Canonicalization.scala`, `adk4s-record/src/main/scala/org/adk4s/record/canonical/NormalizeToolCallIds.scala` |
| 3 | recorder-sink | `adk4s-record/src/main/scala/org/adk4s/record/Recorder.scala`, `adk4s-record/src/main/scala/org/adk4s/record/CallRecord.scala`, `adk4s-record/src/main/scala/org/adk4s/record/RecordedError.scala`, `adk4s-record/src/main/scala/org/adk4s/record/Classification.scala`, `adk4s-record/src/main/scala/org/adk4s/record/RecorderInstances.scala` (noop, inMemory), `adk4s-record/src/main/scala/org/adk4s/record/file/FileRecorder.scala` |
| 4 | recorder-verified-model | `verified/src/main/scala/org/adk4s/verified/RecorderCoherenceModel.scala`, `verified/src/main/scala/org/adk4s/verified/NormalizationModel.scala` |
| 5 | recorded-wrappers | `adk4s-record/src/main/scala/org/adk4s/record/RecordedChatModel.scala`, `adk4s-record/src/main/scala/org/adk4s/record/RecordedEmbedder.scala`, `adk4s-record/src/main/scala/org/adk4s/record/RecordingToolMiddleware.scala`, `adk4s-record/src/main/scala/org/adk4s/record/Redaction.scala` |
| 6 | recorder-laws | `adk4s-record/src/main/scala/org/adk4s/record/RecorderLaws.scala`, `adk4s-record/src/main/scala/org/adk4s/record/RequestMutation.scala` |
| 7 | record-replay-example | `adk4s-examples/src/main/scala/org/adk4s/examples/record/RecordReplayExample.scala`, `adk4s-examples/run-example.sh` |

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | adk4s-record-module | combined | complexity=simple, correctness risk=low (module setup + arch rules) |
| 2 | call-key | separate | complexity=high (new types + complex logic + Ring 6 model) |
| 3 | recorder-sink | separate | complexity=high (new types + effectful + Ring 6 model) |
| 4 | recorder-verified-model | separate | complexity=high (Stainless formal verification) |
| 5 | recorded-wrappers | separate | complexity=high (new types + effectful + multiple properties) |
| 6 | recorder-laws | separate | complexity=medium (testkit with 13 properties) |
| 7 | record-replay-example | combined | complexity=simple, correctness risk=low (example, no new library types) |

## Complexity Guide

- **SIMPLE** (specs 1, 7): No new library types (module setup / example).
  Typed contract: minimal/waiver. Rings: 0, 1, 2, 3, 8 minimum.
- **MEDIUM** (spec 6): New testkit class with 13 properties, but no new
  production types beyond the laws class. Typed contract: full. Rings:
  0, 1, 2, 3, 8.
- **HIGH** (specs 2, 3, 4, 5): New types AND complex logic AND Ring 6.
  Typed contract: full. All applicable rings.

## Implementation Sequence

- [x] 1. `specs/adk4s-record-module/spec.md` — Create the `adk4s-record` sbt module, wire dependencies (adk4s-core, verified % Test, catsEffect, fs2, iron, upickle, hedgehogMunitMain), and add AR-REC-1/AR-REC-2 Scalafix arch rules.
- [ ] 2. `specs/call-key/spec.md` — Implement the canonical content-hash key kernel: `CallKey`, `CanonicalForm`, `CallKind`, `RolloutId` (Iron-refined), `keyVersion`, `normalizeToolCallIds`, and the pure canonicalization functions. Write the typed contract, test oracle (RL2/RL3/RL4/RL6/RL12), and implement through Rings 0–6 (NormalizationModel) and 8.
- [ ] 3. `specs/recorder-sink/spec.md` — Implement the `Recorder[F]` sink algebra: trait, `CallRecord` ADT, `RecordedError`, `Classification`, and three backends (noop, inMemory, file). Write the typed contract, test oracle (RL1/RL7/RL8/RL9), and implement through Rings 0–6 (RecorderCoherenceModel) and 8.
- [ ] 4. `specs/recorder-verified-model/spec.md` — Implement the Ring 6 PureScala models (`NormalizationModel`, `RecorderCoherenceModel`) in the `verified` module, with Stainless `ensuring` clauses and bridge property tests. Run `sbt -J-Xmx6g ring6` to discharge.
- [ ] 5. `specs/recorded-wrappers/spec.md` — Implement `RecordedChatModel`, `RecordedEmbedder`, `ToolMiddleware.recording`, and `Redaction`. Write the typed contract, test oracle (RL0/RL5/RL10/RL11), and implement through Rings 0–5 and 8.
- [ ] 6. `specs/recorder-laws/spec.md` — Implement `RecorderLaws` (main scope, RL0–RL12 Hedgehog properties) and the `RequestMutation`/`NonAffectingMutation` ADTs. Write the typed contract, test oracle, and implement through Rings 0–3 and 8.
- [ ] 7. `specs/record-replay-example/spec.md` — Implement `RecordReplayExample` in `adk4s-examples`, demonstrating zero-provider-call replay against a warm file recorder with a multi-turn tool-calling conversation. Run the example to verify exit criteria §8.5 and §8.6.
