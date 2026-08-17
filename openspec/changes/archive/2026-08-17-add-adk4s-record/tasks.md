# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes.

     RULES:
     - One `## <n>. <spec-name>` section per spec, in implementation-order.md order
     - Per-spec checkboxes follow the schema cycle: typed contract (human gate) →
       test oracle (human gate) → implementation → applicable rings → concept-delta
       + inventory update + checkpoint
     - List only the rings that apply to that spec (skip those marked `—` in the
       Ring Applicability table)
     - Prerequisite work (build restructure, deps, static-analysis config) goes
       first in the owning spec's section
     - Every task is observable and stack-specific — never "implement the spec" -->

## 1. adk4s-record-module

- [x] Create `adk4s-record` sbt module in `build.sbt`: `.dependsOn(adk4s-core, verified % Test)`, libraryDependencies (catsEffect, fs2 core+io, iron, upickle, munitMain, munitCatsEffect, hedgehogMunitMain, testDeps)
- [x] Add AR-REC-1 Scalafix rule to `.scalafix.conf`: forbid `System.currentTimeMillis`/`Instant.now`/`java.util.Random`/`UUID.randomUUID`/`.hashCode` in `org.adk4s.record.canonical`
- [x] Add AR-REC-2 Scalafix rule to `.scalafix.conf`: forbid unsorted `Map`/`Set` iteration in `org.adk4s.record.canonical`
- [x] Step 1 — typed contract: module compiles with empty source tree (human gate, combined tier)
- [x] Step 2 — test oracle: module-purity property + no-forbidden-deps scenario (human gate, combined tier)
- [x] Step 3 — implementation: verify `sbt adk4s-record/compile` succeeds, `sbt scalafixAll --check` passes
- [x] Rings: R0 (compile) R1 (lint) R2 (arch rules) R3 (property) R8 (adversarial)
- [x] Concept-delta check + update openspec/capability-profile.md (module count 12→13) + checkpoint — commit `568873e`

## 2. call-key

- [x] Step 1 — typed contract: `CallKey` (opaque type), `RolloutId` (Iron opaque type `String :| NonEmpty`), `keyVersion` (val), `CanonicalForm`/`CallKind`/`CanonicalBody` generated from Smithy IDL (`canonical_form.smithy`) via smithy4s codegen, `Canonicalization` object signatures, `normalizeToolCallIds` signature, `CanonicalFormOps` convenience constructors (compiles, human gate)
- [x] Step 2 — test oracle: 8 properties (key-determinism, key-sensitivity, key-insensitivity, rollout-separation, normalization-idempotence, normalization-order-preservation, version-isolation, canonical-form-inspectable) + 8 type contract tests (human gate)
- [x] Step 3 — implementation: `CallKey.scala`, `ModelCallRequest.scala` (with `RequestMutation`/`NonAffectingMutation`), `canonical/Canonicalization.scala`, `canonical_form.smithy` (Smithy IDL for CanonicalForm/CallKind/CanonicalBody/ModelBody/ToolBody/EmbeddingBody/CanonicalMessage union + message types)
- [x] Rings: R0 R1 R2 (AR-REC-1, AR-REC-2) R3 (8 properties + 8 type contracts, 500 runs each) R8 (adversarial)
- [x] Refactor 1: replaced `ujson.Obj` with `smithy4s.Document` in `CanonicalForm.body` — commit `5b1da20`
- [x] Refactor 2: replaced dynamic smithy4s with compile-time codegen from Smithy IDL — commit `c55b681`
- [x] Doc update: updated all change docs to reflect Smithy IDL codegen — commit `3b06b21`
- [x] Concept-delta check + update openspec/concept-inventory.md (add CallKey, RolloutId, CallKind, CanonicalForm, CanonicalBody, CanonicalMessage, ModelBody, ToolBody, EmbeddingBody, RequestMutation, NonAffectingMutation, ToolDef, ModelCallRequest, Canonicalization, CanonicalFormOps, 13 Smithy models, 21 generators) + checkpoint

## 3. recorder-sink

- [x] Step 1 — typed contract: `Recorder[F[_]]` trait (lookup/record/nextSeq), `CallRecord` generated union (Succeeded/Failed), `RecordPayload` generated union (ModelPayload/ToolPayload/EmbeddingPayload), `RecordedError` generated structure, `Classification` generated enum, `RecorderError` (AdkError variant), `Recorder.noop`/`inMemory`/`file` factory signatures, `Schema[CallRecord]` for JSONL via smithy4s.json.Json (compiles, human gate)
- [x] Step 2 — test oracle: 5 properties (record-lookup-coherence, append-only-monotonicity, codec-round-trip, failure-fidelity, bounded-eviction-preserves-recency) + 9 requirement scenarios + 2 compile-negative stubs (Recorder.inMemory(0), Recorder.inMemory(-1)) (human gate)
- [x] Step 3 — implementation: `Recorder.scala`, `record_form.smithy` (Smithy IDL for CallRecord/RecordPayload/ModelPayload/ToolPayload/EmbeddingPayload/RecordedError/Classification), `RecorderInstances.scala` (noop, inMemory), `file/FileRecorder.scala`
- [x] Add `RecorderError` variant to `AdkError` sealed trait; fix all non-exhaustive pattern matches surfaced by the compiler
- [x] Rings: R0 R1 R2 (module purity) R3 (5 properties + scenarios) R4 (JSONL round-trip + old-fixture decoding) R5 (mutation) R6-partial (RecorderCoherenceModel — deferred to spec 4) R8 (adversarial)
- [x] Concept-delta check + update openspec/concept-inventory.md (add Recorder, CallRecord, RecordPayload, ModelPayload, ToolPayload, EmbeddingPayload, RecordedError, Classification, RecorderError, Recorder.noop/inMemory/file) + checkpoint

## 4. recorder-verified-model

- [x] Step 1 — typed contract: `NormalizationModel` object (normalize function with ensuring), `RecorderCoherenceModel` object (record/lookup with ensuring), hash injective assumption (compiles in verified module, human gate)
- [x] Step 2 — test oracle: 2 bridge properties (bridge-normalization-idempotence, bridge-recorder-coherence) (human gate)
- [x] Step 3 — implementation: `verified/src/main/scala/org/adk4s/verified/NormalizationModel.scala`, `verified/src/main/scala/org/adk4s/verified/RecorderCoherenceModel.scala`, bridge tests in `adk4s-record/src/test`
- [x] Rings: R0 R1 R2 R3 (bridge properties) R6 (Stainless: `sbt -J-Xmx6g ring6` discharges ensuring clauses) R8 (adversarial)
- [x] Concept-delta check + update openspec/concept-inventory.md (add NormalizationModel, RecorderCoherenceModel) + checkpoint

## 5. recorded-wrappers

- [x] Step 1 — typed contract: `RecordedChatModel` factory signature, `ToolMiddleware.recording` factory signature, `RecordedEmbedder` factory signature, `Redaction` type alias (`RecordPayload => RecordPayload`), `OnWriteFailure` enum (compiles, human gate)
- [x] Step 2 — test oracle: 4 properties (transparency-noop, zero-call-hit, write-failure-containment, redaction-neutrality) + 9 requirement scenarios (human gate)
- [x] Step 3 — implementation: `RecordedChatModel.scala`, `RecordedEmbedder.scala`, `RecordingToolMiddleware.scala`, `Redaction.scala`, `OnWriteFailure.scala`
- [x] Rings: R0 R1 R2 (module purity, fs2-io scoping) R3 (4 properties + scenarios) R5 (mutation) R8 (adversarial)
- [x] Concept-delta check + update openspec/concept-inventory.md (add RecordedChatModel, ToolMiddleware.recording, RecordedEmbedder, Redaction, OnWriteFailure) + checkpoint

## 6. recorder-laws

- [x] Step 1 — typed contract: `RecorderLaws` class signature with 13 `Property` vals (rl0–rl12), `RequestMutation` ADT, `NonAffectingMutation` ADT (compiles in main scope, human gate)
- [x] Step 2 — test oracle: verify all 13 properties are Hedgehog `Property` values parameterized over `Recorder[F]` (human gate)
- [x] Step 3 — implementation: `RecorderLaws.scala` (main scope), `RequestMutation.scala` (main scope, shared with spec 2), `NonAffectingMutation.scala` (main scope, shared with spec 2), `RecorderLawsSpec.scala` (test scope, runs all 13 laws against noop/inMemory/file)
- [x] Rings: R0 R1 R2 R3 (13 properties) R8 (adversarial)
- [x] Concept-delta check + update openspec/concept-inventory.md (add RecorderLaws) + checkpoint

## 7. record-replay-example

- [x] Add `adk4s-examples → adk4s-record` dependency in `build.sbt`
- [x] Step 1 — typed contract: `RecordReplayExample` IOApp.Simple skeleton (compiles, human gate, combined tier)
- [x] Step 2 — test oracle: 3 scenario assertions (zero-call second run, multi-turn full cache hit, runs without API key) (human gate, combined tier)
- [x] Step 3 — implementation: `RecordReplayExample.scala` (deterministic model double + RecordedChatModel + file recorder + multi-turn tool-calling conversation), `run-example.sh` entry
- [x] Rings: R0 R3 (scenario assertions) R8 (adversarial)
- [x] Run example: verify exit criteria §8.5 (zero-provider-call second run) and §8.6 (multi-turn full cache hit)
- [x] Concept-delta check + update openspec/concept-inventory.md (add RecordReplayExample as application-edge) + checkpoint
