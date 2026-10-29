# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept.

     This file is generated from the specs, spec-lint (all PASS required),
     and design artifacts. The checkbox list at the bottom is the progress
     tracker used by the apply phase (tracks: implementation-progress.md). -->

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | specs/migration-protocol/spec.md | `OracleGreenGate` (object) | `Outcome[+A]`, `SeamConfiguration`, `MigrationState`, `ToolId`, `OracleGreenCheck` (all from inventory) | medium |
| 2 | specs/cli-wiring/spec.md | `CliContext`, `StdoutRenderer[A]`, `SubcommandWiring` | `Outcome[+A]`, `Subcommand`, `ProbatioMain`, `MulticallDispatch`, `ExitCode`, `HelpRegistry`, `Validator`, `Ledger`, `ContractViolation`, `LedgerRecord`, `ChainState`, `ChainStateReport`, `LintReport`, `BannerEngine`, `DriftScan`, `GatePayload`, `PredecessorCheck`, `GrantWaiver`, `SchemaPolicy`, `MetalsClient`, `ConformanceModel` (all from inventory) | high |
| 3 | specs/hook-cutover/spec.md | `ShimSwap`, `SwapOrder`, `OracleGreenGate` (shared with #1) | `ShimGenerator`, `SeamConfiguration`, `MigrationState`, `ShimTarget`, `ShimResolution`, `SkillDocLintResult`, `ToolId`, `OracleGreenCheck` (all from inventory) | medium |

**Topological sort rationale**:

- `migration-protocol` comes first because it introduces `OracleGreenGate`, which `hook-cutover` also introduces (shared concept). The migration-protocol spec is the authoritative source for the oracle-green gate's stage-transition semantics; `hook-cutover` reuses it for per-swap gating. Implementing `migration-protocol` first establishes the gate's contract before `hook-cutover` depends on it.
- `cli-wiring` comes second because `hook-cutover` (Stage 3) depends on the wired subcommands being in place (Stage 2). The shims point to the probatio binary, which must have its 16 subcommands wired before the shims can be swapped. The oracle-green gate at the Stage 2 → Stage 3 transition (from `migration-protocol`) verifies the wiring is complete before any shim swap begins.
- `hook-cutover` comes last because it is the final stage — replacing bash hooks with exec shims. It depends on both `cli-wiring` (the binary must be wired) and `migration-protocol` (the oracle-green gate must be in place to gate each swap).

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | migration-protocol | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | minimal |
| 2 | cli-wiring | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | full |
| 3 | hook-cutover | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |

**Ring rationale**:

- **R0** (compile): all 3 specs — `probatioScalacOptions` with `-Werror` and exhaustiveness escalation.
- **R1** (lint): all 3 specs — Scalafix DisableSyntax + WartRemover (no `isInstanceOf`/`asInstanceOf`/`Any`/mutable vars).
- **R2** (architecture): all 3 specs — `dependencyLint` task enforces R-ARCH1 (no cats/cats-effect/fs2/llm4s/workflows4s/adk4s in `workflow/*`).
- **R3** (property tests): all 3 specs — Hedgehog properties + bats oracle at `*_OVERRIDE` seams.
- **R4** (compatibility): `cli-wiring` only — the wired subcommands must produce byte-compatible stdout/exit codes with the bash originals. `migration-protocol` and `hook-cutover` don't touch persisted/wire data formats (the protocol is a restatement, the shims are a mechanism).
- **R5** (mutation): `cli-wiring` and `hook-cutover` — both change production code (`SubcommandEntrypoints.scala` and shim files respectively). `migration-protocol` is a delta restatement with no new production code (the `OracleGreenGate` is test-only).
- **R6** (formal): none — the decision logic is already verified in `probatio-verified`. This change is wiring, not new algorithms.
- **R7** (model checking): none — no TLA+/Apalache in this project.
- **R8** (adversarial review): all 3 specs — mandatory for every code-changing spec.
- **R9** (telemetry): none — no otel4s/Daut in `workflow/*`.

**Typed contract rationale** (from proposal):
- `migration-protocol`: **minimal** — restates R-M1–R-M5 as active protocol; no new production types (the `OracleGreenGate` is test-only). The contract is the protocol semantics, not a type signature.
- `cli-wiring`: **full** — 16 subcommand entrypoints change from stubs to real implementations. New I/O adapter types (`CliContext`, `StdoutRenderer[A]`, `SubcommandWiring`). The contract is the per-subcommand behavior contract: input shape → core call → output shape + exit code.
- `hook-cutover`: **full** — bash scripts replaced by exec shims. New types (`ShimSwap`, `SwapOrder`). The contract is the shim content and the atomic skill-doc update.

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | migration-protocol | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/OracleGreenGate.scala` (new, test-only) |
| 2 | cli-wiring | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` (populate 16 stubs), `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/CliContext.scala` (new), `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/StdoutRenderer.scala` (new), `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandWiring.scala` (new) |
| 3 | hook-cutover | `workflow/core/src/main/scala/org/sinemenda/probatio/plugin/ShimGenerator.scala` (existing, may need path updates), `hooks/spec-lint` (shim), `hooks/chain-state` (shim), `hooks/danger-scan` (shim), `hooks/reconcile` (shim), `hooks/gate` (shim), skill docs referencing predecessor scripts |

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | migration-protocol | separate | complexity=medium (new test-only type `OracleGreenGate` + protocol restatement); proposal correctness risk is medium (the protocol gates the entire port — a wrong gate lets bad code through) |
| 2 | cli-wiring | separate | complexity=high (16 subcommand wirings + 3 new types + Ring 4 compatibility); proposal correctness risk is high (byte-compatibility with bash originals) |
| 3 | hook-cutover | separate | complexity=medium (3 new types + 5 shim swaps); proposal correctness risk is high (shim swaps are irreversible — a bad swap breaks the gate) |

**No combined-tier specs.** All 3 specs have either complexity > simple or correctness risk > low. The human reviews the typed contract and the test oracle at separate gates for each spec.

## Complexity Guide

- **SIMPLE**: No new types, ≤1 new method on existing trait, no new error variants. Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.
- **MEDIUM**: New types OR complex business logic OR new error handling paths. Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.
- **HIGH**: New types AND complex logic AND involves Ring 6/7 or Ring 9. Typed contract: full. All applicable rings.

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md — import existing concepts; verify the spec's
        Proof Obligations table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE (combined-tier specs: merged into gate 3)
     3. Test oracle from spec + contract only (before implementation),
        run once for ORACLE POLARITY (red / green-by-design)
        → human review GATE
     4. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6/7
     5. Concept delta check (scanner diff) + build-dependency delta +
        update openspec/concept-inventory.md
     6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     7. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [x] 1. `specs/migration-protocol/spec.md` — Restate R-M1–R-M5 as the active porting protocol; introduce `OracleGreenGate` (test-only) that gates stage transitions on bats oracle green. Establishes the gate contract that `hook-cutover` depends on.
- [x] 2. `specs/cli-wiring/spec.md` — Wire all 16 subcommand entrypoints in `SubcommandEntrypoints.scala` from stubs to real implementations; introduce `CliContext`, `StdoutRenderer[A]`, `SubcommandWiring`; verify byte-compatibility with bash originals via bats oracle at `*_OVERRIDE` seams.
- [x] 3. `specs/hook-cutover/spec.md` — Replace 5 bash hook shims with 3-line `exec` shims pointing to the probatio binary, in dependency order (gate last), with oracle-green gating each swap; introduce `ShimSwap`, `SwapOrder`; update skill docs atomically with each swap.
