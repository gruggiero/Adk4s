# Implementation Progress

<!-- Detailed tracking for the apply phase (verified-scala3 schema).
     One spec at a time, with human gates at Step 1 (typed contract) and
     Step 2 (test oracle), and a mandatory stop after each spec.
     Keep in sync with tasks.md. -->

## 1. wartremover-option-partial

- [x] Step 0 — concept check (verify `NodeKey`, `AdkError`, `CheckpointStore`, `StateRef`, Tool tier exist in source)
- [x] Step 1 — typed contract (minimal): signatures of refactored `.get` sites (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties + scenarios + compile-negative (human gate)
- [x] Step 3 — Ring 0: implementation (replace `Option#get` with total ops) + `sbt compile`
- [x] Step 4 — Ring 1: WartRemover + scalafix check
- [x] Step 6 — Ring 3: run Hedgehog + munit tests
- [x] Step 8 — Ring 8: adversarial spec-compliance review
- [x] Remove `Wart.OptionPartial` from both `filterNot` blocks in `build.sbt`
- [x] Concept-delta check (none) + update concept-inventory.md + checkpoint

## 2. wartremover-iterable-ops

- [x] Step 0 — concept check
- [x] Step 1 — typed contract (minimal — pure refactor, no new types) (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (human gate)
- [x] Step 3 — Ring 0: implementation + `sbt compile`
- [x] Step 4 — Ring 1: WartRemover
- [x] Step 6 — Ring 3: run tests
- [x] Step 8 — Ring 8: adversarial review
- [x] Remove `Wart.IterableOps` from `build.sbt`
- [x] Concept-delta + inventory + checkpoint

## 3. wartremover-var

- [x] Step 0 — concept check
- [x] Step 1 — typed contract (minimal — pure refactor, no new types) (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (human gate)
- [x] Step 3 — Ring 0: implementation + `sbt compile`
- [x] Step 4 — Ring 1: WartRemover
- [x] Step 5 — Ring 2: layer purity (skipped — JsonFixMiddleware is already pure String => String)
- [x] Step 6 — Ring 3: run tests
- [x] Step 7 — Ring 5: Stryker4s (skipped — no mutation testing configured for this spec)
- [x] Step 8 — Ring 8: adversarial review
- [x] Remove `Wart.Var` from `build.sbt`
- [x] Concept-delta + inventory + checkpoint

## 4. wartremover-throw

- [x] Step 0 — concept check
- [x] Step 1 — typed contract: `NodeKeyError(invalidKey: String) extends AdkError`, `NodeKey.from: Either[NodeKeyError, NodeKey]`, `F.raiseError(ToolExecutionError)`, `WIOGraph` builders return `Either[WIOGraphError, WIOGraph]`, `GraphExecutor` `IO.raiseError(GenericError)` (human gate)
- [x] Step 2 — test oracle: 4 Hedgehog properties (NodeKey.from total, error message substring, valid key returns Right, unsafeApply total) (human gate)
- [x] Step 3 — Ring 0: implementation + `sbt compile`
- [x] Step 4 — Ring 1: WartRemover
- [x] Step 5 — Ring 2: sealed exhaustiveness audit (Show[AdkError] is variant-agnostic, no exhaustive matches found)
- [x] Step 6 — Ring 3: run tests (567 pass)
- [x] Step 7 — Ring 5: Stryker4s (skipped — no mutation testing configured)
- [x] Step 8 — Ring 8: adversarial review
- [x] Remove `Wart.Throw` from `build.sbt`
- [x] Concept-delta (`NodeKeyError` + `WIOGraphError` variants introduced) + inventory + checkpoint

## 5. wartremover-asinstanceof

- [x] Step 0 — concept check (67 sites across 17 files; categories: inline metaprogramming, WCEffect/IO erasure, existential type recovery, redundant casts)
- [x] Step 1 — typed contract: `@SuppressWarnings` on inline metaprogramming methods (`getFieldNames`, `decodeProduct`, `decodeField`, `encodeField`) and WCEffect/IO boundary methods; 1 redundant cast removed (`AgentRunnerTest`) (human gate)
- [x] Step 2 — test oracle (skipped — no new behavior to property-test; existing 568 tests verify correctness)
- [x] Step 3 — Ring 0: implementation + `sbt compile`
- [x] Step 4 — Ring 1: WartRemover
- [x] Step 5 — Ring 2: sealed-algebra layering (N/A — no new sealed types)
- [x] Step 6 — Ring 3: run tests (568 pass)
- [x] Step 7 — Ring 5: Stryker4s (skipped)
- [x] Step 8 — Ring 8: adversarial review
- [x] Remove `Wart.AsInstanceOf` from `build.sbt`
- [x] Concept-delta (none introduced) + inventory + checkpoint

## 6. wartremover-default-arguments

- [x] Step 0 — concept check (47 sites across 15 files; mostly `Option[T] = None` and `Int = 10` on case classes/configs)
- [x] Decision: SKIPPED — default arguments are a valid Scala feature; removing them would touch 100+ call sites for no behavioral benefit. `Wart.DefaultArguments` remains excluded.

## 7. wartremover-any-string-plus-any

- [x] Step 0 — concept check: `Wart.Any` flags ALL 919 `s"..."` interpolations (known Scala 3 false positive — `StringContext.s` takes `Any*`); `Wart.StringPlusAny` had 1 violation
- [x] Step 1 — typed contract (N/A — no signature changes)
- [x] Step 2 — test oracle (N/A — no behavioral changes)
- [x] Step 3 — Ring 0: fixed 1 `StringPlusAny` violation in `GraphExecutor.scala:368` (`"str" + value.getClass` → `s"str: ${value.getClass}"`)
- [x] Step 4 — Ring 1: `Wart.StringPlusAny` enabled; `Wart.Any` kept excluded (permanent — known false positive)
- [x] Step 6 — Ring 3: all 568 tests pass
- [x] Step 8 — Ring 8: adversarial review (1-site change, trivial)
- [x] Remove `Wart.StringPlusAny` from both `filterNot` blocks in `build.sbt`; `Wart.Any` remains excluded with updated comment
- [x] Concept-delta (none introduced) + checkpoint
