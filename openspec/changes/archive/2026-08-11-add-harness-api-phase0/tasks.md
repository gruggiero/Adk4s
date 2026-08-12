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

## 1. harness-state

- [ ] Prerequisite: add `adk4s-harness-api` module to `build.sbt` (`.dependsOn(adk4s-core)`, `.dependsOn(verified % Test)`, root aggregation) + `project/Dependencies.scala` if new deps; verify empty module compiles
- [ ] Step 1 — typed contract: `HarnessState` (final class, total get, immutable set/update, snapshot/restore), `StateCell[A]` (mandatory ReadWriter, visibility, merge, CellId opaque), `CellVisibility` (Private/Inherited/Shared), `StateDecodeError`, `MiddlewareName` (opaque), `PromptSection`/`SystemPrompt` — compiles in test sources (human gate)
- [ ] Step 2 — test oracle: get/set coherence, snapshot/restore round-trip, restore leniency (unknown fields ignored, new cells default, corrupted cell hard error), project/mergeBack visibility scenarios, mergeBack order-independence (semilattice) — Hedgehog properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation: `HarnessState.scala`, `StateCell.scala`, `CellVisibility.scala`, `MiddlewareName.scala`, `PromptSection.scala`, `SystemPrompt.scala`, `StateDecodeError.scala` in `org.adk4s.harness`; `HarnessStateKernel.scala` Ring 6 mirror in `verified/`
- [ ] R0 — `sbt adk4s-harness-api/compile` + `sbt verified/compile`
- [ ] R1 — Scalafix (`NoUjsonIn*` compliance — `JsonValue` only) + WartRemover (`@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))` scoped to `HarnessState.get`)
- [ ] R2 — `org.adk4s.harness` package boundary (no workflows4s/llm4s LLM client/adk4s-orchestration/fs2-io/logback imports)
- [ ] R3 — Hedgehog properties: get-set-coherence, set-preserves-other-cells, snapshot-restore-round-trip, restore-unknown-fields-ignored, restore-new-cells-default-initial, restore-corrupted-cell-hard-error, project-private-initial, project-inherited-shared-parent, mergeBack-shared-folds, mergeBack-private-inherited-unchanged, mergeBack-order-independence-semilattice
- [ ] R5 — `stryker4s.conf` retarget to `**/harness/HarnessState.scala`; threshold 85%
- [ ] R6 — `HarnessStateKernel` mirror (get/set coherence `ensuring`); `HarnessStateKernelBridgeSpec` bridge test; `sbt -J-Xmx6g ring6`
- [ ] R8 — adversarial review (fresh context): `asInstanceOf` scope, id-uniqueness invariant, snapshot/restore leniency, Private cell privacy in project/mergeBack
- [ ] Concept-delta check + create `openspec/concepts/harness-state.md` + update `openspec/concept-inventory.md` + checkpoint

## 2. agent-middleware

- [ ] Step 1 — typed contract: `AgentMiddleware[F[_]]` trait (name, stateCells, tools, promptSections(state), beforeAgent/afterAgent/wrapModelCall/wrapToolCall with defaults), `ModelRequest[F]`/`ModelResponse`, `ToolCallCtx`/`ToolCallOut`, `ModelStep[F]`/`ToolStep[F]` (Kleisli aliases), `ToolStep.passthrough`, `AgentMiddleware.id` — compiles in test sources (human gate)
- [ ] Step 2 — test oracle: hook default neutrality, tool/section contribution scenarios, `AgentMiddleware.id` observational equivalence, state-aware `promptSections(state)` scenarios — Hedgehog properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation: `AgentMiddleware.scala` (trait + companion + `id`), `ToolStep.scala` (passthrough lift) in `org.adk4s.harness`
- [ ] R0 — `sbt adk4s-harness-api/compile`
- [ ] R1 — Scalafix + WartRemover (no `asInstanceOf`, no `Any`, no wildcard imports)
- [ ] R2 — `org.adk4s.harness` package boundary (may import `adk4s-core.component`, `adk4s-core.tools`, `adk4s-core.json`, `adk4s-core.error`, llm4s `Message`/`Completion`/`CompletionOptions`; no workflows4s/adk4s-orchestration)
- [ ] R3 — Hedgehog properties + scenario tests: hook defaults, state-aware promptSections, id neutrality, tool/section contributions
- [ ] R5 — `stryker4s.conf` retarget to `**/harness/AgentMiddleware.scala`; threshold 85%
- [ ] R8 — adversarial review: `AgentMiddleware.id` truly neutral, `promptSections(state)` state-aware (not construction-time), wrap defaults pass through
- [ ] Concept-delta check + create `openspec/concepts/agent-middleware.md` + update `openspec/concept-inventory.md` + checkpoint

## 3. middleware-stack

- [ ] Step 1 — typed contract: `MiddlewareStack[F]` (private constructor, middlewares: List, empty/validated/++, allCells/allTools/allSections(state), beforeAgent/afterAgent, wrapModelCall/wrapToolCall), `StackError` (enum: DuplicateCellId/DuplicateToolName) — compiles in test sources (human gate)
- [ ] Step 2 — test oracle: monoid identity (any position), monoid associativity, hook distribution (wrapModelCall/wrapToolCall trace order), disjoint commutativity (conditional), validated duplicate detection (cell ids + tool names, error accumulation), project/mergeBack boundary scenarios, mergeBack order-independence — Hedgehog properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation: `MiddlewareStack.scala` (private constructor, validated, fold-based hooks, foldRight wraps), `StackError.scala` in `org.adk4s.harness`; `StackKernel.scala` Ring 6 mirror (project/mergeBack + order-independence) in `verified/`
- [ ] R0 — `sbt adk4s-harness-api/compile` + `sbt verified/compile`
- [ ] R1 — Scalafix + WartRemover (private constructor, no `Var`, no `asInstanceOf`)
- [ ] R2 — `org.adk4s.harness` package boundary
- [ ] R3 — Hedgehog properties: monoid-identity-any-position, monoid-associativity, hook-distribution-wrapModelCall, disjoint-commutativity, validated-duplicate-detection; scenario tests: beforeAgent/afterAgent order, wrapModelCall nesting, allSections fold, empty stack aggregates to empty, validated Right/Left, StackError exhaustiveness
- [ ] R5 — `stryker4s.conf` retarget to `**/harness/MiddlewareStack.scala`; threshold 85%
- [ ] R6 — `StackKernel` mirror (project/mergeBack `ensuring` + order-independence for semilattice); `StackKernelBridgeSpec` bridge test; `sbt -J-Xmx6g ring6`
- [ ] R8 — adversarial review: private constructor enforcement, validated accumulates ALL errors (not fail-fast), StackError exhaustiveness, mergeBack purity (no in-place mutation), stack-order semantics (beforeAgent forward, afterAgent reverse, wraps m1-outermost)
- [ ] Concept-delta check + create `openspec/concepts/middleware-stack.md` + update `openspec/concept-inventory.md` + checkpoint

## 4. checkpoint-store-fpoly

- [ ] Step 1 — typed contract: `CheckpointStore[F[_]]` (get/set/delete/keys return F[…], inMemory[F[_]: Sync], type CheckpointId = String), `CheckpointStateV2` (version=2, messages: List[CheckpointMessage], harnessState: JsonValue, interruptSignalJson, agentName; derives ReadWriter), `CheckpointMessage` (role, content, toolCalls, toolCallId; derives ReadWriter), `CheckpointToolCall` (id, name, arguments: String; derives ReadWriter) — compiles in test sources (human gate)
- [ ] Step 2 — test oracle: V1 read compatibility (v1 payload decodes to CheckpointStateV2 with defaults), V2 round-trip (write→read identity), full-fidelity preservation (toolCalls/toolCallId survive), harnessState snapshot round-trip (restore yields original), F-polymorphism (inMemory get/set/delete/keys semantics) — Hedgehog properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation: `CheckpointStore.scala` (generalized trait + companion + inMemory + CheckpointId), `CheckpointStateV2.scala` (+ CheckpointMessage + CheckpointToolCall), `AgentRunner.scala` (run builds CheckpointStateV2; resume reads as CheckpointStateV2 + calls HarnessState.restore)
- [ ] R0 — `sbt adk4s-orchestration/compile` (existing IO call sites compile at F=IO)
- [ ] R1 — Scalafix (`NoUjsonInOrchestration` — `JsonValue` for harnessState, `String` for arguments) + WartRemover (no `asInstanceOf` in checkpoint code)
- [ ] R2 — `org.adk4s.orchestration.interrupt` + `org.adk4s.orchestration.agent` package boundaries (may import `adk4s-harness-api` for HarnessState.restore)
- [ ] R3 — Hedgehog properties: V1-read-compatibility, V2-round-trip, full-fidelity-preservation, harnessState-snapshot-round-trip, F-polymorphism; munit scenarios: inMemory get/set/delete/keys, version=2, harnessState carries snapshot, empty-stack harnessState, v1 payload with tool messages, v2 payload full fidelity, corrupted payload fails, resume restores/fails/deletes
- [ ] R4 — v1→v2 checkpoint read compat (optional `version` field, absent ⇒ v1); v2 round-trip; full-fidelity messages; `ReactAgent.create` source compat (`sbt adk4s-examples/compile`)
- [ ] R5 — `stryker4s.conf` retarget to `**/orchestration/interrupt/CheckpointStore.scala`, `**/orchestration/agent/CheckpointStateV2.scala`; threshold 85%
- [ ] R8 — adversarial review: v1-read compat (absent version ⇒ v1, missing fields default), v2 round-trip fidelity, resume returns Failed on corrupted cell (not silent data loss), CheckpointId transparent alias (not opaque), no asInstanceOf, no SerializableCheckpointMessage/CheckpointState outside v1 compat
- [ ] Concept-delta check + update `openspec/concepts/checkpoint-store.md` + `openspec/concepts/agent-runner.md` + update `openspec/concept-inventory.md` + checkpoint

## 5. harness-agent

- [ ] Prerequisite: add `adk4s-orchestration .dependsOn(adk4s-harness-api)` to `build.sbt`; verify orchestration compiles against harness-api
- [ ] Step 1 — typed contract: `HarnessAgent[F[_]: Async]` (generate/stream, Config, IOHarnessAgent alias), `HarnessResult` (sealed: Completed/Interrupted/Failed), `ReactAgent.create` sugar signature (no MiddlewareStack param) — compiles in test sources (human gate)
- [ ] Step 2 — test oracle: L0 empty-stack-equivalence (gatekeeper — must be RED before implementation), per-request-prompt-folding, per-request-tool-list, afterAgent-skipped-on-interrupt, parallel-tool-merge-order-independence (TestControl) — Hedgehog properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation: `HarnessAgent.scala` (F[_]: Async, generate/stream loop per design §6.1), `HarnessResult.scala` (sealed trait), `ReactAgent.scala` (create delegates to HarnessAgent[IO] with MiddlewareStack.empty), `ReactAgentImpl.scala` (loop body moves to HarnessAgent), `AgentRunner.scala` (resume re-enters loop with restored state)
- [ ] R0 — `sbt adk4s-orchestration/compile` + `sbt adk4s-examples/compile` (55+ examples source-compatible)
- [ ] R1 — Scalafix + WartRemover (no `asInstanceOf`, no `Var`, no `Any`)
- [ ] R2 — `org.adk4s.orchestration.agent` package boundary (may import `adk4s-harness-api`)
- [ ] R3 — Hedgehog properties: empty-stack-equivalence (L0 gatekeeper), per-request-prompt-folding, per-request-tool-list, afterAgent-skipped-on-interrupt, parallel-tool-merge-order-independence (CONCURRENCY — TestControl); scenario tests: no-tool termination, multi-tool, interrupt mid-iteration, step budget exhausted, hook trace order, event emission order, ReactAgent.create compiles unchanged, AgentTool.fromReactAgent accepts refactored agent
- [ ] R4 — `ReactAgent.create` source compatibility (`sbt adk4s-examples/compile` — 55+ examples unchanged)
- [ ] R5 — `stryker4s.conf` retarget to `**/agent/HarnessAgent.scala`, `**/agent/HarnessResult.scala`; threshold 85%
- [ ] R8 — adversarial review: L0 gatekeeper green before merge, per-request tool/prompt derivation (not construction-time baking), afterAgent skipped on interrupt, ReactAgent.create signature unchanged (no stack param), Async[F] context bound, event emission stays in loop (middlewares wrap not replace), no Thread.sleep (TestControl for concurrency)
- [ ] Concept-delta check + update `openspec/concepts/react-agent.md` + `openspec/concepts/agent-runner.md` + update `openspec/concept-inventory.md` + checkpoint

## 6. middleware-laws

- [ ] Prerequisite: add `adk4s-harness-testkit` module to `build.sbt` (`.dependsOn(adk4s-harness-api)`, `.dependsOn(verified % Test)`, main-scope `cats-effect`/`munit`/`hedgehog-munit` NOT % Test, root aggregation) + `project/Dependencies.scala`; verify empty module compiles
- [ ] Step 1 — typed contract: `AgentMiddlewareLaws` (L0–L10 Hedgehog properties, parameterized by stack + DeterministicChatModel), `SemilatticeLaws` (L11 commutativity/associativity/idempotence + mergeBack-order-independence), `DeterministicChatModel` (deterministic ChatModel[IO] double, scripted responses, request-trace recording) — compiles in test sources (human gate)
- [ ] Step 2 — test oracle: L0-conservative-refactor (empty-stack harness ≍ ReactAgentImpl), L1-monoid-identity, L2-monoid-associativity, L3-hook-distribution, L4-default-neutrality, L5-cell-frame-rule, L6-disjoint-commutativity, L7-codec-round-trip, L8-restore-leniency, L9-privacy, L10-merge-back-neutrality, L11-semilattice-commutativity/associativity/idempotence, L11-mergeBack-order-independence (TestControl) — Hedgehog properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation: `AgentMiddlewareLaws.scala`, `SemilatticeLaws.scala`, `DeterministicChatModel.scala`, `Generators.scala` (explicit Gen + Range, no Arbitrary) in `org.adk4s.harness.testkit` (main scope); `SemilatticeKernel.scala` Ring 6 mirror in `verified/`; `SemilatticeModelBridgeSpec.scala` bridge test in `adk4s-harness-api` test sources
- [ ] R0 — `sbt adk4s-harness-testkit/compile` + `sbt verified/compile`
- [ ] R1 — Scalafix + WartRemover (no `asInstanceOf`, no `Any`, no `Arbitrary`)
- [ ] R2 — `org.adk4s.harness.testkit` package boundary (may import `org.adk4s.harness`, cats-effect, munit, hedgehog; no workflows4s/llm4s LLM client/adk4s-orchestration/fs2-io/logback)
- [ ] R3 — Hedgehog properties: L0-conservative-refactor through L10-merge-back-neutrality, L11-semilattice-commutativity/associativity/idempotence, L11-mergeBack-order-independence (CONCURRENCY — TestControl); DeterministicChatModel determinism + trace recording scenarios; adversarial scenarios (L4 non-default NOT neutral, L5 cross-cell write detected, L6 overlapping cells do NOT commute, L6 request rewriters do NOT commute, L8 corrupted cell hard error, L9 child writes unobservable, L10 non-idempotent breaks neutrality, L11 non-commutative fails laws)
- [ ] R5 — `stryker4s.conf` retarget to `**/harness/testkit/*.scala`; threshold 85%
- [ ] R6 — `SemilatticeKernel` mirror (commutative/associative/idempotent `ensuring`); `SemilatticeModelBridgeSpec` bridge test in harness-api; `StackKernelBridgeSpec` bridge test in harness-testkit; `sbt -J-Xmx6g ring6`
- [ ] R8 — adversarial review: DeterministicChatModel no wall-clock/I/O, no Arbitrary in generators (explicit Gen + Range), AgentMiddlewareLaws in main scope (not Test), testkit no heavy deps (no neo4j/lucene/http4s), TestControl used for concurrency scenarios (no Thread.sleep), L0 gatekeeper is real (not trivially passing)
- [ ] Concept-delta check + update `openspec/concepts/agent-middleware.md` (laws section) + update `openspec/concept-inventory.md` + checkpoint
