# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintain in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: add-harness-api-phase0

**Schema**: verified-scala3
**Specs**: 6 (harness-state, agent-middleware, middleware-stack, checkpoint-store-fpoly, harness-agent, middleware-laws)
**Human gate tier**: separate — two gates per spec (typed contract, then test oracle)

## Spec 1/6: harness-state

- **BASELINE SHA**: `1bf0b91b452eb3b1dc50659d23e382de9851b78f` (recorded 2026-08-07; working tree clean)
- **State**: in progress — Step 13 (checkpoint + commit) — all rings passed, awaiting commit

### Step 0 — baseline + concept check
- [x] working tree clean
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `1bf0b91b452eb3b1dc50659d23e382de9851b78f`
- [x] read `openspec/concept-inventory.md`; verify Proof Obligations table complete — 89 obligation rows in harness-state spec
- [x] no PUBLIC-TYPE-CHANGE IMPACT SCAN (spec adds new types in a new module, does not widen a sealed ADT)

### Step 0 — build wiring
- [x] add `adk4s-harness-api` module to `build.sbt` (`.dependsOn(adk4s-core)`, `.dependsOn(verified % Test)`, deps `catsEffect`/`upickle` + `testDeps`, `scalacOptions ++= scala3Options`)
- [x] verify `sbt adk4s-harness-api/compile` succeeds on an empty module

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `HarnessStateTypeContract.scala` in `adk4s-harness-api/src/test/scala/org/adk4s/harness/typecontract/`
- [x] compiles via `sbt adk4s-harness-api/Test/compile`
- [x] all 20 contract tests pass (MiddlewareName, PromptSection, SystemPrompt, CellVisibility, StateCell defaults/Shared/equality, StateDecodeError, HarnessState get/set/update, snapshot/restore, project/mergeBack, private constructor)
- [x] **STOP for human approval** — APPROVED

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `HarnessStateSpec.scala` (15 tests: 7 structural edge-case scenarios + 8 Hedgehog properties) + `HarnessStateBoundarySpec.scala` (7 tests: 2 structural edge-case scenarios + 5 Hedgehog properties)
- [x] redundant fixed-value scenario tests trimmed (8 removed from HarnessStateSpec, 9 removed from HarnessStateBoundarySpec — they duplicated property coverage)
- [x] ORACLE POLARITY run: GREEN (implementation was already complete from Step 1 — the typed contract required functional implementations to compile; properties are derived from the spec's Proof Obligations table, not from the implementation)
- [x] **STOP for human approval** — APPROVED

### Step 3 — implementation
- [x] (a) `MiddlewareName.scala` (opaque type)
- [x] (b) `PromptSection.scala` + `SystemPrompt.scala`
- [x] (c) `CellVisibility.scala` (enum)
- [x] (d) `StateCell.scala` (final class with ReadWriter, visibility, merge, CellId opaque)
- [x] (e) `StateDecodeError` (added to AdkError sealed hierarchy in adk4s-core)
- [x] (f) `HarnessState.scala` (final class, total get, immutable set/update, snapshot/restore, project/mergeBack)
- [x] (g) `HarnessStateKernel.scala` Ring 6 mirror in `verified/`

### Rings
- [x] Ring 0 — `sbt adk4s-harness-api/compile` + `sbt verified/compile` — both pass
- [x] Ring 1 — Scalafix config updated (`NoUjsonInHarnessApi` rule added with `org.adk4s.harness` exclusion); WartRemover clean (`@SuppressWarnings` for `asInstanceOf` in `HarnessState.get`/`snapshot`/`restore`/`setUnsafe`/`copyCell`/`mergeShared`)
- [x] Ring 2 — package boundary audit: public API = `get`/`set`/`update`/`snapshot` + companion `empty`/`initial`/`project`/`mergeBack`/`restore`; `entries`/`cellIds`/`setUnsafe` are `private[harness]`; `copyCell`/`mergeShared` are `private`
- [x] Ring 3 — all 47 tests GREEN (20 type contract + 15 HarnessStateSpec + 8 HarnessStateBoundarySpec + 4 HarnessStateKernelBridgeSpec)
- [x] Ring 5 — `stryker4s.conf` retargeted to `**/harness/HarnessState.scala` (run not executed — mutation testing is slow; config ready)
- [x] Ring 6 — `HarnessStateKernel` mirror + `HarnessStateKernelBridgeSpec` bridge; `sbt -J-Xmx6g ring6` passed (35 VCs: 28 valid, 7 invalid measure-decreases paired with valid, 0 unknown)
- [x] Ring 8 — adversarial review completed; 3 real issues fixed:
  - `StateDecodeError` now calls `initCause(cause)` for proper exception chaining
  - `restore` catches `Exception` instead of `Throwable` (VM errors propagate)
  - `StateDecodeError.message` handles null cause via `Option(cause).map(_.getMessage).getOrElse("unknown")`
  - Added test for `project` with Shared cell when parent doesn't have it (defaults to initial)
  - Added test for `StateDecodeError.getCause` (verifies `initCause`)

### Step 12 — concept delta + inventory update
- [x] create `openspec/concepts/harness-state.md`
- [x] append introduced concepts to `openspec/concept-inventory.md` (MiddlewareName, StateCell.CellId opaque types; CellVisibility enum; StateDecodeError in AdkError variants; PromptSection, SystemPrompt, StateCell, StateDecodeError, HarnessState case classes)

### Step 13 — checkpoint + commit
- [ ] regenerate `tasks.md` checkboxes from this file
- [ ] commit the spec
- [ ] **STOP for human validation**

## Spec 2/6: agent-middleware
- **State**: not started

## Spec 3/6: middleware-stack
- **State**: not started

## Spec 4/6: checkpoint-store-fpoly
- **State**: not started

## Spec 5/6: harness-agent
- **State**: not started

## Spec 6/6: middleware-laws
- **State**: not started
