# Implementation Order

This artifact determines the EXACT sequence for depth-first implementation.
Each spec is processed one at a time through all applicable verification
rings. The order is based on concept dependency analysis: a spec that
introduces a concept must come before any spec that uses that concept.

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/probatio-core/spec.md` | `Outcome[A]`, `LedgerRecord`, `Ring`, `Ledger`, `ContractViolation`, `ChainStateReport`, `LintReport`/`SpecLintReport`, `GatePayload`, `BannerEngine`, `DriftScan`, `MetalsClient`, `probatioScalacOptions` | (none — foundational; R-ARCH1 isolates probatio from adk4s) | high |
| 2 | `specs/cli-protocol/spec.md` | multicall binary, `Subcommand` sealed enum, CLI surface (one subcommand per predecessor script) | `Outcome[A]`, `LintReport`, `ChainStateReport`, `GatePayload`, `Ledger` (all from probatio-core) | high |
| 3 | `specs/sbt-plugin/spec.md` | `ProbatioPlugin` AutoPlugin, 7 sbt tasks, `probatioInstall` resolution, `probatioGateShim`, `probatioUninstall`, `GraalVMHome` | CLI surface (subcommands, exit codes — the plugin invokes the binary via Process); NO probatio-core types (R-S1) | medium |
| 4 | `specs/native-packaging/spec.md` | multicall binary native-image, release artifacts (binary + JAR + SHA-256 + SBOM + sources), CI release pipeline, scalameta spike | CLI multicall binary (from cli-protocol); probatio-core (native-image input) | medium |
| 5 | `specs/migration-protocol/spec.md` | migration protocol concept, conformance property test contract, `*_OVERRIDE` seam swap order, exactly-one-implementation invariant | All ported subcommands (from cli-protocol + probatio-core); bats oracle; `.jq` contracts | medium |
| 6 | `specs/schema-policy/spec.md` | schema rename (v14), `generatedBy` stamp rename, env-var migration, cache-dir migration, hooks/README rewrite | (none — schema.yaml policy changes; no probatio code dependency) | simple |
| 7 | `specs/non-goals-guard/spec.md` | feature-freeze contract, allowed-dependency set, `dependency-lint rule` (R-ARCH1) | All probatio subprojects (the dependency-lint rule checks all workflow/* classpaths); probatio-core (F1–F10 verdict stability cross-reference) | medium |

### Dependency graph

```
probatio-core (1) ──┬──> cli-protocol (2) ──┬──> sbt-plugin (3)
                    │                        ├──> native-packaging (4)
                    │                        └──> migration-protocol (5)
                    │
                    └──> non-goals-guard (7) [dependency-lint rule checks all workflow/*]

schema-policy (6) [independent — schema.yaml policy, no probatio code dependency]
```

**Topological sort**: schema-policy (6) is independent of the probatio code
and can be done first or last. However, the v14 rename is the single
visible inflection for consumers (proposal §3), so it is scheduled LAST
to avoid mid-port schema drift. The dependency-lint rule (non-goals-guard
#7) must exist before any probatio code compiles (it is a setup task per
capability-check.md consequence #2), but the full non-goals-guard spec
(oracle immutability, verdict stability) is verified AFTER the port is
complete. So non-goals-guard is split: the dependency-lint rule is a
Phase 0 setup task (in tasks.md), and the spec's verification is last.

**Resolved order**: 1 → 2 → 3 → 4 → 5 → 7 → 6

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | probatio-core | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | full |
| 2 | cli-protocol | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | full |
| 3 | sbt-plugin | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | full |
| 4 | native-packaging | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — | full |
| 5 | migration-protocol | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — | full |
| 6 | schema-policy | ✅ | — | — | ✅ | ✅ | — | — | — | ✅ | — | full |
| 7 | non-goals-guard | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | full |

**Notes**:
- R0 (compile): all specs involve new code or config; `probatioScalacOptions` (R-CS1–R-CS5) on core/cli; Scala 2.12 on plugin; `bash -n` on shims.
- R1 (lint): Scalafix + WartRemover + scalafmt on Scala subprojects; shellcheck + shfmt on shims. Schema-policy (#6) is yaml/markdown — no Scala lint.
- R2 (architecture): dependency-lint rule (R-ARCH1) applies to all specs with workflow/* code (1, 2, 3, 7). Specs 4, 5, 6 touch CI/schema, not classpaths.
- R3 (property): MANDATORY for all code-changing specs. Hedgehog 0.13.1 (NOT ScalaCheck).
- R4 (wire/persistence): applies to specs touching persisted/wire data — core (ledger, reports, payloads), cli (stdout payloads), migration (conformance), schema-policy (schema.yaml, env vars), native-packaging (release artifacts).
- R5 (mutation): Stryker4s on changed production logic. Not applicable to sbt-plugin (Scala 2.12, sbt test framework — Stryker4s targets Scala 3), native-packaging (CI pipeline, not production code), migration-protocol (test protocol, not production code), schema-policy (config, not code), non-goals-guard (build rule + test, not production logic).
- R6 (formal): only probatio-core (3 verified-mirror kernels: LedgerValidatorKernel, ChainStateKernel, BannerEngineKernel).
- R7 (model checking): ❌ no TLA+/Apalache. Skip for all specs.
- R8 (adversarial review): MANDATORY for all specs. Fresh-context reviewer.
- R9 (telemetry): ❌ no otel4s/Daut. Skip for all specs.

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | probatio-core | `workflow/core/src/main/scala/org/sinemenda/probatio/core/*.scala` (LedgerRecord, Ring, Ledger, ContractViolation, ChainStateReport, LintReport, GatePayload, BannerEngine, DriftScan, MetalsClient, scanner/SpecLint, graph/Traceability); `verified/probatio/src/main/scala/org/sinemenda/probatio/verified/*.scala` (LedgerValidatorKernel, ChainStateKernel, BannerEngineKernel); `build.sbt` (new subprojects, probatioScalacOptions) |
| 2 | cli-protocol | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/*.scala` (ProbatioMain, SpecLintCmd, ChainStateCmd, GateCmd, LedgerCmd, DangerScanCmd, CheckpointCmd, ConceptScanCmd, GraphCmd, Subcommand enum); `workflow/cli/src/test/scala/...` (Hedgehog properties) |
| 3 | sbt-plugin | `workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin/*.scala` (ProbatioPlugin, tasks); `workflow/plugin/src/test/scala/...` (Hedgehog properties); `project/build.properties` (sbt version) |
| 4 | native-packaging | `.github/workflows/release.yml` (CI release pipeline); `workflow/cli/native-image-config/` (GraalVM config); `project/plugins.sbt` (native-image plugin if needed) |
| 5 | migration-protocol | `openspec/schemas/verified-scala3/tests/*.bats` (oracle — NOT modified, but run); `openspec/schemas/verified-scala3/scanner/*_OVERRIDE` seams; hook shim files |
| 6 | schema-policy | `openspec/schemas/verified-scala3/schema.yaml` (v14 rename); `openspec/schemas/verified-scala3/hooks/README.md` (policy rewrite); `openspec/schemas/verified-scala3/hooks/gate.sh` (env-var migration); CI cache config |
| 7 | non-goals-guard | `build.sbt` (dependency-lint rule); `workflow/core/src/test/scala/.../NonGoalsGuardSpec.scala` (Hedgehog properties); CI workflow (dependency-lint step) |

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | probatio-core | separate | complexity=high (new types AND complex logic AND Ring 6); proposal risk=high |
| 2 | cli-protocol | separate | complexity=high (new types AND complex logic); proposal risk=high |
| 3 | sbt-plugin | separate | complexity=medium (new types); proposal risk=high |
| 4 | native-packaging | separate | complexity=medium (CI pipeline + native-image); proposal risk=high |
| 5 | migration-protocol | separate | complexity=medium (migration protocol); proposal risk=high |
| 6 | schema-policy | separate | complexity=simple BUT proposal risk=high (consumer-visible inflection) |
| 7 | non-goals-guard | separate | complexity=medium (build rule + properties); proposal risk=high |

**All specs are `separate` tier.** The proposal's correctness risk is
**high** (ports correctness-critical tooling; defect class = "a corrupt
ledger reads as clean"). Per the rule, `combined` is allowed ONLY when
complexity is `simple` AND risk is `low`. Since risk is high for all
specs, every spec gets two separate human gates: (1) typed contract
review, (2) test oracle review. This is the schema's two-gate discipline
for high-risk changes.

## Complexity Guide

- **SIMPLE**: No new types, ≤1 new method, no new error variants. Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.
- **MEDIUM**: New types OR complex logic OR new error handling paths. Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.
- **HIGH**: New types AND complex logic AND involves Ring 6/7/9. Typed contract: full. All applicable rings.

## Implementation Sequence

Process each spec in this exact order. For each spec:
1. Record baseline SHA (clean tree) + inventory snapshot; read
   openspec/concept-inventory.md — import existing concepts; verify the
   spec's Proof Obligations table is complete
2. Typed contract (mandatory) — genuinely compiled in test sources
   → human review GATE (separate tier: gate 1 of 2)
3. Test oracle from spec + contract only (before implementation), run
   once for ORACLE POLARITY (red / green-by-design)
   → human review GATE (separate tier: gate 2 of 2)
4. Implement through all applicable rings (see table above) — Ring 8
   adversarial review (fresh context) runs BEFORE Rings 5/6/7
5. Concept delta check (scanner diff) + build-dependency delta +
   update openspec/concept-inventory.md
6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
7. STOP for human validation before next spec

DO NOT skip ahead. DO NOT batch-implement. One spec at a time.

- [ ] 1. `specs/probatio-core/spec.md` — ADTs (LedgerRecord, ChainStateReport, GatePayload, LintReport, Outcome), validators (ex-jq contracts), verdict logic, banner/drift engine, metals client, strict compiler flags (R-CS1–R-CS5), Ring 6 mirrors (3 kernels)
- [ ] 2. `specs/cli-protocol/spec.md` — multicall binary, one subcommand per predecessor script, three-way exit protocol, byte-compatible stdout payloads, arg-parse error attribution, --help, multicall dispatch
- [ ] 3. `specs/sbt-plugin/spec.md` — sbt 1.x AutoPlugin (Scala 2.12), 7 tasks, install resolution order, exit-code mapping, idempotent gate shims, no load-time side effects
- [ ] 4. `specs/native-packaging/spec.md` — native-image (mandatory for gate, optional-with-warning for others), per-platform release artifacts, CI-reproducible pipeline, scalameta spike
- [ ] 5. `specs/migration-protocol/spec.md` — bats oracle as acceptance suite, conformance property test, shim swap order, exactly-one implementation, atomic skill updates
- [ ] 6. `specs/non-goals-guard/spec.md` — feature-freeze contract verification, F1–F10 verdict stability, dependency boundary closed, oracle immutability (dependency-lint rule is a Phase 0 setup task, verified here)
- [ ] 7. `specs/schema-policy/spec.md` — v14 rename (verified-scala3 → probatio), generatedBy stamp rename, env-var + cache-dir migration with deprecated aliases, hooks/README rewrite

### Phase 0 setup tasks (BEFORE spec 1)

Per capability-check.md consequence #2, two setup tasks MUST complete
before any spec implementation begins. These are preconditions for Ring
0/Ring 2 discharging on this change:

- [ ] 0a. R-ARCH1 dependency-lint rule — fails if any workflow/* project's classpath reaches an adk4s module OR a forbidden dependency (cats, cats-effect, fs2, llm4s, workflows4s, scalacheck)
- [ ] 0b. R-CS1–R-CS5 `probatioScalacOptions` setting on probatio-core/probatio-cli subprojects only (not repo-wide)
- [ ] 0c. V1 spike: GraalVM native-image builds probatio-core + probatio-cli without hand-maintained reflection config (gated — hard-blocker if fails)
- [ ] 0d. V2 spike: cold/warm wall-clock latency of gate binary meets R-N1 budget on linux-x86_64 (gated — hard-blocker if fails)
- [ ] 0e. V3 spike: pi TS adapter invokes gate.sh by path; shim replacement verified end-to-end under `pi -e`
- [ ] 0f. V4 spike: prebuilt-binary download host choice (GitHub Releases vs. Maven zip) and checksum story

**Gate**: all six Phase 0 tasks verified or the change is re-scoped before
any porting begins. V1 and V2 are hard-blockers — R-N1 states the change
does not ship if the latency budget is unmet.
