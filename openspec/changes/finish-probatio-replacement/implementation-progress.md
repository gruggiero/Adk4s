# Implementation Progress — finish-probatio-replacement

<!-- Single source of truth for apply-phase progress. tasks.md is regenerated
     from this file at each checkpoint (checkpoint.sh regenerate-tasks) and is
     never hand-maintained in parallel. Section format is parser-contractual:
       - `## Spec N/12: <name>`  — checkpoint.sh report baseline extraction
       - `### N. <name>`         — checkpoint.sh regenerate-tasks pass 1
       - `- **BASELINE SHA**: `…`` — per-spec diff anchor, recorded at Step 0
       - `| Commit | <sha> |`    — completion witness (hex 7-40 = complete)
-->

## Spec 1/12: hermetic-test-processes

### 1. hermetic-test-processes

- **Status**: IN PROGRESS — Step 0 underway
- **BASELINE SHA**: `8a0c15f4a10046d041a09ef7fb3a839fb095ad29`

### Step Progress
- [ ] Prerequisite — record, per suite, the results with and without the harness session variable set, as the baseline this spec must make identical
- [ ] Step 0 — Baseline + concept check
- [ ] Step 1 — typed contract: `HermeticEnv` (private constructor, no inherit factory), `ControlledVariable` (closed set, legacy and probatio names) — **human gate**
- [ ] Step 2 — test oracle: 11 scenarios + 2 properties + 2 compile-negative stubs; Hedgehog cover-minimum behaviour established; ORACLE POLARITY run — **human gate**
- [ ] Step 3 — implementation: shared helpers (Scala + bats), migrate 19 process-spawning suites + `helpers.bash`, no-raw-process lint, triage red tests
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — scalafix (new lint + planted violation), WartRemover, danger-scan, shellcheck `helpers.bash`
- [ ] Ring 2 — `probatioDependencyLint`
- [ ] Ring 3 — suites green in both environments; completion-tier parity passes with and without the harness variable
- [ ] Ring 5 — move-to-main, retarget, run, move back; read the score (80%)
- [ ] Ring 8 — fresh-context review in an archive-only tree with harness session variables set
- [ ] Concept-delta check + inventory update + checkpoint

---

## Spec 2/12: jar-launcher-dispatch

### 2. jar-launcher-dispatch

- **Status**: PENDING

---

## Spec 3/12: entrypoint-split

### 3. entrypoint-split

- **Status**: PENDING

---

## Spec 4/12: archive-safe-fixtures

### 4. archive-safe-fixtures

- **Status**: PENDING

---

## Spec 5/12: oracle-independence

### 5. oracle-independence

- **Status**: PENDING

---

## Spec 6/12: oracle-fixture-repair

### 6. oracle-fixture-repair

- **Status**: PENDING

---

## Spec 7/12: delivery-verified

### 7. delivery-verified

- **Status**: PENDING

---

## Spec 8/12: installer-swap

### 8. installer-swap

- **Status**: PENDING

---

## Spec 9/12: surface-honesty

### 9. surface-honesty

- **Status**: PENDING

---

## Spec 10/12: registry-check-port

### 10. registry-check-port (with the cli-wiring delta)

- **Status**: PENDING

---

## Spec 11/12: legacy-name-retirement

### 11. legacy-name-retirement

- **Status**: PENDING

---

## Spec 12/12: schema-directory-rename

### 12. schema-directory-rename

- **Status**: PENDING

---

## Change exit criterion

- [ ] The differential shows **no file worse** than the genuine predecessor control, measured in an **archive-only environment** and **with harness session variables set**
- [ ] A recorded hosted CI run passes at the final commit
- [ ] The oracle-immutability guard is green
- [ ] The seven `.predecessor.bak` files remain as revert targets — their retirement needs a full green cycle after this change
