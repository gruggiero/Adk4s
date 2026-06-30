# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     The apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes. -->

## 1. wartremover-option-partial

- [x] Step 1 — typed contract: signatures of refactored `.get` sites (`NodeKey`, `CheckpointStore.load`, `StateRef`, `AgentTool`, `GraphExecutor` accessors) returning `Either`/`Option`/fold (compiles in `adk4s-core`/`adk4s-orchestration` Test/compile, human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (total on None, `Some` preserves value) + scenarios (missing checkpoint → `Left(CheckpointNotFoundError)`, `Map#get` not flagged) + compile-negative stub (human gate)
- [x] Step 3 — implementation: replace every `Option#get` in main+test sources with `.getOrElse`/`.fold`/pattern match
- [x] Rings: R0 `sbt compile` R1 WartRemover R3 Hedgehog+munit R8 adversarial review
- [x] Remove `Wart.OptionPartial` from both `filterNot` blocks in `build.sbt`; delete comment line
- [x] Concept-delta check (none introduced) + update concept-inventory.md + checkpoint

## 2. wartremover-iterable-ops

- [x] Step 1 — typed contract: signatures of refactored `.init`/`.last` sites (minimal — no signature changes) (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (`dropRight(1)==init` on non-empty, total on empty) + edge-case scenario (human gate)
- [x] Step 3 — implementation: grep `.init`/`.last`/`.head`/`.tail`/`.reduce` in main+test sources; replace with `dropRight(1)`/`lastOption`/`headOption`/`drop(1)`/`foldLeft` + explicit fallback
- [x] Rings: R0 `sbt compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Remove `Wart.IterableOps` from both `filterNot` blocks in `build.sbt`; delete comment line
- [x] Concept-delta check (none introduced) + update concept-inventory.md + checkpoint

## 3. wartremover-var

- [x] Step 1 — typed contract: signatures of `JsonFixMiddleware.replaceSingleQuotes`/`applyHeuristicFixes` (pure `String => String`, recursive/fold) + `Ref[IO, Int]` mock signatures (compiles, human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (`replaceSingleQuotes` byte-identical to reference oracle, `applyHeuristicFixes` idempotent, empty-string scenario) (human gate)
- [x] Step 3 — implementation: rewrite `replaceSingleQuotes` as tail recursion; `applyHeuristicFixes` as fixpoint loop; replace test `var` with `Ref[IO, _]`/`AtomicReference`
- [x] Rings: R0 `sbt compile` R1 WartRemover R2 layer purity R3 Hedgehog R5 Stryker4s (skipped) R8 adversarial review
- [x] Remove `Wart.Var` from both `filterNot` blocks in `build.sbt`; delete comment line
- [x] Concept-delta check (none introduced) + update concept-inventory.md + checkpoint

## 4. wartremover-throw

- [x] Step 1 — typed contract: `NodeKeyError(invalidKey: String) extends AdkError`; `NodeKey.from: Either[NodeKeyError, NodeKey]`; `Tool.invokable.run` `F.raiseError(ToolExecutionError)`; `GraphExecutor` `IO.raiseError(GenericError)`; `WIOGraph` builder methods return `Either[WIOGraphError, WIOGraph]`; `Graph.identityStub` → `IO.raiseError(GenericError)`; `RealLlmExample.validateAndLoadConfig` returns `Either[String, AppConfig]` (human gate)
- [x] Step 2 — test oracle: 4 Hedgehog properties (`NodeKey.from` total + rejects empty/reserved, error message substring, valid key returns Right, `unsafeApply` total) (human gate)
- [x] Step 3 — implementation: add `NodeKeyError` to `AdkError.scala`; add `NodeKey.from`, make `unsafeApply` non-throwing; add `WIOGraphError` variants; convert `WIOGraph` builders to `Either`; convert `WIOGraph` compilation methods to `Either`; replace `throw` in `Tool` with `F.raiseError`; replace `throw` in `Graph` with `IO.raiseError`; fix `GraphExecutor.calculateLevels` to use pattern match; convert `RealLlmExample.validateAndLoadConfig` to `Either[String, AppConfig]`; convert all example + test call sites to for-comprehensions; replace test `throw` with `fail`/`assert(isLeft)`
- [x] Rings: R0 `sbt compile` R1 WartRemover R2 sealed exhaustiveness audit R3 Hedgehog R5 Stryker4s (skipped) R8 adversarial review
- [x] Remove `Wart.Throw` from both `filterNot` blocks in `build.sbt`
- [x] Concept-delta check (`NodeKeyError` + `WIOGraphError` variants introduced) + update concept-inventory.md + checkpoint

## 5. wartremover-asinstanceof

- [x] Step 1 — typed contract: `@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))` on inline metaprogramming methods (`ToolInfer.getFieldNames`, `decodeProduct`, `decodeField`; `ToolSchema.encodeField`) and WCEffect/IO boundary methods (`WIONode.toWIO` variants, `WIONodeModifier.apply`, `WIOGraph` class-level, `GraphExecutor.executeNodeParallel`, `Tool.buildObjectSchema`); 1 redundant cast removed (`AgentRunnerTest.fail().asInstanceOf[String]`) (human gate)
- [x] Step 2 — test oracle: skipped (no new behavior — existing 568 tests verify correctness)
- [x] Step 3 — implementation: added `@SuppressWarnings` to 15 methods/classes across 7 main source files + 9 test/example files; removed 1 redundant cast in `AgentRunnerTest`
- [x] Rings: R0 `sbt compile` R1 WartRemover R2 sealed-algebra (N/A) R3 Hedgehog (existing tests) R5 Stryker4s (skipped) R8 adversarial review
- [x] Remove `Wart.AsInstanceOf` from both `filterNot` blocks in `build.sbt`; delete comment line
- [x] Concept-delta check (none introduced) + update concept-inventory.md + checkpoint

## 6. wartremover-default-arguments

- [x] Decision: SKIPPED — 47 default argument sites across 15 files, mostly `Option[T] = None` on config case classes. Default arguments are a valid Scala API design feature. Removing them would require companion `apply` overloads + updating 100+ call sites for no behavioral benefit. `Wart.DefaultArguments` remains excluded in `build.sbt`.

## 7. wartremover-any-string-plus-any

- [x] Step 1 — typed contract (N/A — no signature changes)
- [x] Step 2 — test oracle (N/A — no behavioral changes)
- [x] Step 3 — fixed 1 `StringPlusAny` violation in `GraphExecutor.scala:368` (`"str" + value.getClass` → `s"str: ${value.getClass}"`); `Wart.Any` skipped (919 false positives from `s"..."` interpolation)
- [x] Rings: R0 `sbt compile` R1 WartRemover (`StringPlusAny` enabled, `Any` excluded) R3 Hedgehog (568 tests pass) R8 adversarial review
- [x] Remove `Wart.StringPlusAny` from both `filterNot` blocks in `build.sbt`; `Wart.Any` remains excluded with updated permanent-exclusion comment
- [x] Concept-delta check (none introduced) + checkpoint
