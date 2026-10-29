# Implementation Order

Twelve specs plus one MODIFIED delta, topologically sorted. The proposal's correctness risk is
**high**, so **every spec takes two separate human gates**: the combined gate needs
`complexity=simple` AND `risk=low`, and no spec qualifies.

This order differs from the proposal's first draft in two places. Both were caused by forward
dependencies found while building this graph. The proposal's Approach section has been
updated to match.

## Dependency graph

```
 1 hermetic-test-processes        (no dependencies — every later property spawns processes)
 │
 ├─► 2 jar-launcher-dispatch       its archive-conformance property runs in HermeticEnv
 │    │
 │    └─► 3 entrypoint-split        behaviour-preservation runs through both artifacts, hermetically
 │         │
 │         ├─► 4 archive-safe-fixtures
 │         │    └─► 5 oracle-independence   generalises 4's resolver into the guard
 │         │         └─► 6 oracle-fixture-repair   edits the oracle through 5's sanction rule;
 │         │              │                          edits the gate's pre-execution tier (after 3)
 │         │              └─► 7 delivery-verified   needs 2 (archive path) and 4–6 (green suites)
 │         │
 │         ├─► 8 installer-swap            edits installer entrypoints (after 3); arm provisioning (2)
 │         ├─► 9 surface-honesty            removes the code-intelligence entrypoint (after 3)
 │         │    └─► 10 registry-check-port  + cli-wiring delta (needs 9's removal and 10's addition)
 │         └─► 11 legacy-name-retirement    edits gate env reads (after 3); oracle edits (after 5)
 │
 └──────────────────────────────────────► 12 schema-directory-rename   touches everything; last
```

| Spec | Depends on | Reason |
|------|-----------|--------|
| 2 | 1 | `archive-conformance-matches-native-conformance` runs each process in `HermeticEnv` |
| 3 | 1, 2 | The behaviour-preservation property compares both artifacts, hermetically |
| 4 | 1 | Resolver scenarios build temporary repositories through the hermetic helper |
| 5 | 4 | The guard's corpus resolution becomes the shared `ChangeLocation` |
| 6 | 3, 5 | Edits the gate after the split; every oracle edit is sanctioned under 5 |
| 7 | 2, 4, 5, 6 | CI provisions only the archive (2); the suites must be able to pass (4–6) |
| 8 | 2, 3 | Installer entrypoints are edited after the split; arms carry the built tool (2) |
| 9 | 3 | The code-intelligence entrypoint has its own file to delete |
| 10 | 3, 9 | The verifier gains an entrypoint; the `cli-wiring` delta states the surface after both 9 and 10 |
| 11 | 1, 3, 5 | `ControlledVariable` gains the probatio names (1); env reads edited after the split (3); oracle edits sanctioned (5) |
| 12 | all | 105 references across every area the other specs edit |

## The order

### 1. `hermetic-test-processes`

| | |
|---|---|
| **Complexity** | medium — new test-infrastructure types, a lint, a coverage-enforcement question |
| **Typed contract** | **full** — `HermeticEnv` (private constructor), `ControlledVariable` |
| **Rings** | 0, 1, 2, 3, 5, 8 (Ring 6: stated skip) |
| **Human gate tier** | **separate** |
| **Introduces** | `HermeticEnv`, `ControlledVariable` |
| **Expected files (Ring 5)** | new shared test-support source; the 19 process-spawning suites migrated to the helper; `helpers.bash`; `.scalafix.conf` |
| **Ring 5 caveat** | test sources — move-to-main-and-back |
| **Exit criterion** | The completion-tier parity property passes with and without the harness session variable; every suite file's count is identical in both environments |
| **Expect** | tests that pass today only because of an inherited variable turn red once isolated — each is triaged and recorded |

### 2. `jar-launcher-dispatch`

| | |
|---|---|
| **Complexity** | high — dispatch algebra change, conformance on a second artifact, harness provisioning, Ring 6 |
| **Typed contract** | **full** — `InvocationSource`; `InvocationName` constructed from it |
| **Rings** | 0, 1, 2, 3, 5, 6, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `InvocationSource` |
| **Expected files** | `MulticallDispatch.scala`, `InvocationName.scala`, `ProbatioMain.scala`, `SubprocessConformanceSpec.scala`, `ArmTypes.scala`, `DispatchKernel.scala`; remove the symlink workaround in `ChainStateParitySpec.scala` |
| **Exit criterion** | The acceptance suite passes in an archive-only tree; the predecessor arm matches an independent control per file |

### 3. `entrypoint-split`

| | |
|---|---|
| **Complexity** | medium — large but mechanical |
| **Typed contract** | **minimal** — signatures of the moved objects only |
| **Rings** | 0, 1, 2, 3, 8 (Ring 5: no new logic; Ring 6: stated skip) |
| **Human gate tier** | **separate** |
| **Introduces** | none |
| **Expected files** | `SubcommandEntrypoints.scala` → 11 entrypoint files plus one shared-helpers file |
| **Exit criterion** | Every observable identical before and after; every moved body identical byte for byte |

### 4. `archive-safe-fixtures`

| | |
|---|---|
| **Complexity** | simple — a resolver and a lint |
| **Typed contract** | **minimal** |
| **Rings** | 0, 1, 2, 3, 5, 8 |
| **Human gate tier** | **separate** — risk is high |
| **Introduces** | `ChangeLocation` |
| **Expected files** | `GuardCorpus.scala` (generalised), shared test support, `DifferentialHarnessSpec.scala`, `.scalafix.conf` |
| **Ring 5 caveat** | test sources — move-to-main-and-back |
| **Exit criterion** | `DifferentialHarnessSpec` green after archival; the lint rejects a planted literal |

### 5. `oracle-independence`

| | |
|---|---|
| **Complexity** | high — new persisted format, guard decision change, oracle restructuring, Ring 6 |
| **Typed contract** | **full** |
| **Rings** | 0, 1, 2, 3, 4, 5, 6, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `OracleTestKind`, `OracleSanction`, `SanctionVerdict`, `OracleBaseline` |
| **Expected files** | `NonGoalsGuardSpec.scala` and guard types, the sanction record, `tests/shape/` (new), the four edited oracle files, `SpecLintKernel.scala`, the migration concept file |
| **Ring 5 caveat** | test sources — move-to-main-and-back |
| **Exit criterion** | The immutability guard is green, and red on a planted unsanctioned edit |

### 6. `oracle-fixture-repair`

| | |
|---|---|
| **Complexity** | medium — fixture edits, one diagnostic, one root-cause investigation |
| **Typed contract** | **minimal** |
| **Rings** | 0, 1, 2, 3, 5, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `ToolNameSource` |
| **Expected files** | `oracle-ordering-lock.bats`, `human-grant-lock.bats`, `chain-state.bats` (sanctioned edits); the gate's entrypoint file |
| **Prerequisite** | root-cause the six `chain-state.bats` failures **before** Step 1; confirm Devin's tool-name field against its documentation |
| **Exit criterion** | The three files pass under both implementations, or a shared implementation defect is recorded and left for a scoped exception |

### 7. `delivery-verified`

| | |
|---|---|
| **Complexity** | medium — configuration and observation |
| **Typed contract** | **full** — `ToolchainIdentity`; the release check's signature changes (the waiver first drafted in the proposal is withdrawn: this spec now changes production code) |
| **Rings** | 0, 1, 2, 3, 5, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `ToolchainIdentity` |
| **Expected files** | `.github/workflows/verify.yml`, `build.sbt` (native toolchain pin), `ReleaseCheck.scala` |
| **Outward action** | **Publishing the branch** to the hosting service so the job runs — only on the maintainer's explicit authorisation at apply time |
| **Exit criterion** | A recorded hosted run passing at a green commit; a recorded hosted run failing on a regressing branch; a locally validated release candidate on the tested toolchain; latency re-measured if the toolchain changed |

### 8. `installer-swap`

| | |
|---|---|
| **Complexity** | medium — two seams and two swaps |
| **Typed contract** | **minimal** |
| **Rings** | 0, 1, 2, 3, 5, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | none (`ToolId`, `SwapOrder` gain entries) |
| **Expected files** | `SeamTypes.scala`, `SwapOrder.scala`, the two installer scripts → forwarding scripts (originals kept as revert targets), the migration concept file |
| **Exit criterion** | Both installers compared and swapped at parity; the hook installer merges without python3 |

### 9. `surface-honesty`

| | |
|---|---|
| **Complexity** | medium — surface narrowing, register reclassification, tutorial rewrite |
| **Typed contract** | **full** |
| **Rings** | 0, 1, 2, 3, 5, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `LiveRoute` |
| **Expected files** | `Subcommand.scala`, `HelpRegistry.scala`, `ProbatioMain.scala`, the code-intelligence entrypoint file (deleted), `UnportedTool.scala`, `UnportedToolRegister.scala`, `unported-tools.md`, `openspec-graph.py` → `.predecessor.bak`, `docs/09-tooling.html`, `docs/10-practice.html`, the migration concept file |
| **Exit criterion** | The register classifies every tool by live route with no finding; no instruction runs the predecessor graph script |

### 10. `registry-check-port` (with the `cli-wiring` delta)

| | |
|---|---|
| **Complexity** | high — a ported engine, a new seam, a spike run, Ring 6 |
| **Typed contract** | **full** (the delta: **minimal**, text only) |
| **Rings** | 0, 1, 2, 3, 4, 5, 6, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `RegistryRow`, `BindingVerdict`, `RegistryReport` |
| **Expected files** | new verifier engine in core, new entrypoint, `Subcommand.scala`, seam types, `registry-check.sh` → forwarding script, `verify.yml`, CI templates, `SpecLintKernel.scala`, the spike module, `unported-tools.md` |
| **The delta** | `specs/cli-wiring/spec.md` lands here: its restated requirement describes the surface after both 9 and 10, and is verified by the surface tests |
| **Exit criterion** | Report lines equal to the predecessor's on the repository and generated registries; swapped at parity; spike outcome recorded |

### 11. `legacy-name-retirement`

| | |
|---|---|
| **Complexity** | medium — alias table, directory migration, adapter rename |
| **Typed contract** | **full** |
| **Rings** | 0, 1, 2, 3, 4, 5, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `LegacyAlias` |
| **Expected files** | `SchemaPolicy.scala`, the gate's entrypoint file, `GateStateDir.scala`, the pi adapter (schema and `.pi/extensions`), `.scalafix.conf`, `workflow-hygiene.bats` (sanctioned) |
| **Exit criterion** | No legacy-only variable; the state directory migrates; the seven named oracle files at parity |

### 12. `schema-directory-rename`

| | |
|---|---|
| **Complexity** | medium — wide but mechanical, with one external-behaviour dependency |
| **Typed contract** | **full** |
| **Rings** | 0, 1, 2, 3, 4, 8 |
| **Human gate tier** | **separate** |
| **Introduces** | `SchemaAlias` |
| **Prerequisite** | re-establish the CLI's alias resolution on the version in use (MUST-CONFIRM) before the move |
| **Expected files** | the schema directory (moved), the alias link, `openspec/config.yaml`, `RenameDeferral.scala`, 105 referencing files |
| **Exit criterion** | Every active change resolves under both names; no live reference to the previous path; the deferral discharged |

## Human gate tiers — summary

| Spec | Complexity | Risk | Tier |
|------|-----------|------|------|
| 1–12 | simple to high | high | **separate** — the change's risk is high, so none qualifies for the combined gate |

## Progress tracking

- [ ] **1. hermetic-test-processes** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R5 R8 → concept delta → checkpoint
- [ ] **2. jar-launcher-dispatch** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R5 R6 R8 → concept delta → checkpoint
- [ ] **3. entrypoint-split** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R8 → concept delta → checkpoint
- [ ] **4. archive-safe-fixtures** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R5 R8 → concept delta → checkpoint
- [ ] **5. oracle-independence** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R4 R5 R6 R8 → concept delta → checkpoint
- [ ] **6. oracle-fixture-repair** — root cause → Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R5 R8 → concept delta → checkpoint
- [ ] **7. delivery-verified** — Step 1 → gate → Step 2 → gate → Step 3 → authorised publish → R0 R1 R2 R3 R5 R8 → checkpoint
- [ ] **8. installer-swap** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R5 R8 → concept delta → checkpoint
- [ ] **9. surface-honesty** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R5 R8 → concept delta → checkpoint
- [ ] **10. registry-check-port + cli-wiring delta** — Step 1 → gate → Step 2 → gate → Step 3 → spike → R0 R1 R2 R3 R4 R5 R6 R8 → concept delta → checkpoint
- [ ] **11. legacy-name-retirement** — Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R4 R5 R8 → concept delta → checkpoint
- [ ] **12. schema-directory-rename** — MUST-CONFIRM → Step 1 → gate → Step 2 → gate → Step 3 → R0 R1 R2 R3 R4 R8 → concept delta → checkpoint
- [ ] **Change exit criterion** — the differential shows no file worse than the genuine predecessor control, measured **in an archive-only environment and with harness session variables set**; a recorded hosted CI run passes at the final commit; the immutability guard is green

## Standing notes for the apply phase

- **Code intelligence is unavailable** — the Metals endpoint was re-probed on 2026-09-25 and is
  not running. Apply Steps 0 and 12 use text search unless it is started.
- **Ring 5** — `stryker4s.conf` holds the previous spec's target; retarget per spec. `break = 0`,
  so read the score. A score below threshold is raised or recorded as a failed ring — never
  dispositioned as "in-diff survivors justified".
- **Ring 6** — invoke `probatio-verified` directly; the `ring6` alias is broken under sbt 1.12.
- **Ring 8** — review in an archive-only environment with harness session variables set.
- **Inventory** — at every concept-delta check, a new object under `verified/probatio` is an
  inventory obligation. Two consecutive changes shipped kernels or companion types without a row.
- **Behavioural citations** — cite concepts by the identifier the registry declares
  (`Strangler`, `TraceabilityGraph`, `Conformance`), and re-run the registry check once specs
  change. A one-row table that cites nothing passes the verifier silently.
- **Outward action** — spec 7 publishes the branch; it waits for explicit authorisation.
