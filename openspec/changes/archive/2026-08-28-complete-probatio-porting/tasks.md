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

## 1. migration-protocol

- [x] Step 1 — typed contract (minimal): `OracleGreenGate.apply(stage: Stage, seamConfig: SeamConfiguration): Boolean` signature compiled in test sources (human gate)
- [x] Step 2 — test oracle: 5 scenarios (R-M1–R-M5) + 2 Hedgehog properties (genSeamConfiguration prefix-subset, genSeamConfiguration all-valid-subsets) + 1 compile-negative (MigrationState with dual-implementation seam) (human gate)
- [x] Step 3 — implementation: `OracleGreenGate` object in `workflow/core/src/test/scala/org/sinemenda/probatio/migration/OracleGreenGate.scala` — delegates to `OracleGreenCheck.runOracle` for each tool in the seam config
- [x] R0: `probatioScalacOptions` with `-Werror` + exhaustiveness escalation
- [x] R1: Scalafix DisableSyntax + WartRemover (no `isInstanceOf`/`asInstanceOf`/`Any`/mutable vars)
- [x] R2: `dependencyLint` task — R-ARCH1 (no cats/cats-effect/fs2/llm4s/workflows4s/adk4s in `workflow/*`)
- [x] R3: Hedgehog properties pass + bats oracle green at `*_OVERRIDE` seams
- [x] R8: adversarial review (fresh context) — check for silent fallbacks, `case _` defaults, partial functions
- [x] Concept-delta check (scanner diff) + update `openspec/concept-inventory.md` + checkpoint

## 2. cli-wiring

- [x] Step 1 — typed contract (full): `CliContext` case class, `StdoutRenderer[A]` typeclass with given instances for `ChainStateReport` / `LintReport` / `GatePayload` / `BannerOutput`, `SubcommandWiring` object signatures — all compiled in test sources (human gate)
- [x] Step 2 — test oracle: 7 requirement scenarios + 4 Hedgehog properties (genLedgerRecord 15-clause coverage, genChangeState varying discharge, genRepoState varying repo state, genSubcommandInvocation 16 subcommands) + 3 compile-negatives (--force flag, update/delete action, case-catch-all) (human gate)
- [x] Step 3 — implementation: populate 16 stubs in `SubcommandEntrypoints.scala`; create `CliContext.scala`, `StdoutRenderer.scala`, `SubcommandWiring.scala` in `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/`
- [x] R0: `probatioScalacOptions` with `-Werror` + exhaustiveness escalation (16-case `Subcommand` enum match)
- [x] R1: Scalafix DisableSyntax + WartRemover
- [x] R2: `dependencyLint` task — R-ARCH1 (no cats/cats-effect/fs2/llm4s/workflows4s/adk4s in `workflow/*`)
- [x] R3: 4 Hedgehog properties pass + bats oracle green at all `*_OVERRIDE` seams (evidence-ledger.bats, chain-state.bats, hook-tiers.bats, etc.)
- [x] R4: `.jq` contract round-trip (ledger records, chain-state reports, gate payloads) + old fixture decoding + byte-compatibility property (gate banner)
- [x] R5: Stryker4s mutation testing retargeted to `SubcommandEntrypoints.scala` + new wiring files (git diff against Step 0 baseline SHA)
- [x] R8: adversarial review (fresh context) — check for stdout-shape divergences, silent fallbacks, `case _` defaults, exit-code collapses
- [x] Concept-delta check (scanner diff) + update `openspec/concept-inventory.md` + checkpoint

## 3. hook-cutover

- [x] Step 1 — typed contract (full): `ShimSwap` case class, `SwapOrder` enum (6 cases), `OracleGreenGate` per-swap gating signature — compiled in test sources (human gate)
- [x] Step 2 — test oracle: 3 requirement scenarios + 3 Hedgehog properties (genBinaryPath, genSeamConfiguration prefix subsets, genSwapSequence valid prefixes) + 2 compile-negatives (shim with logic, SwapOrder.GateFirst) (human gate)
- [x] Step 3 — implementation: replace 5 bash hooks (`hooks/spec-lint`, `hooks/chain-state`, `hooks/danger-scan`, `hooks/reconcile`, `hooks/gate`) with 3-line exec shims; update skill docs atomically; create `ShimSwap.scala`, `SwapOrder.scala` in `workflow/core/src/test/scala/org/sinemenda/probatio/migration/`
- [x] R0: `probatioScalacOptions` with `-Werror` + exhaustiveness escalation (6-case `SwapOrder` enum match)
- [x] R1: Scalafix DisableSyntax + WartRemover
- [x] R2: `dependencyLint` task — R-ARCH1
- [x] R3: 3 Hedgehog properties pass + bats oracle green after each shim swap (harness-install-verification.bats)
- [x] R5: Stryker4s mutation testing retargeted to changed shim-generation code (git diff against Step 0 baseline SHA)
- [x] R8: adversarial review (fresh context) — check for shim content divergences, non-atomic skill-doc updates, swap-order violations, oracle-gate bypasses
- [x] Concept-delta check (scanner diff) + update `openspec/concept-inventory.md` + create `openspec/concepts/strangler-migration-protocol.md` + `openspec/concepts/conformance-property-test-contract.md` (apply Step 12) + checkpoint
