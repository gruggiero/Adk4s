# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes.

     BOOTSTRAPPING NOTE (from design.md): bats-only verification for this
     change. The live gate's verdicts are NOT evidence during this change.
     The bats suite is the evidence. Re-install hooks and re-run the full
     gate only after all 5 specs are complete. -->

## 1. workflow-hygiene

- [x] Step 1 — typed contract (minimal): signatures of touched code (spec-lint.sh drift message, ledger.sh case arm, gate.sh cwd/heartbeat/payload, schema.yaml anchors)
- [x] Step 2 — test oracle: 9 bats scenarios (drift ref, message refs resolve, dead arm, cwd jq, non-openspec repo, heartbeat after guard, YAML anchors, payload cap, short list) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: fix drift ref to scanner/install-skills.sh; remove dead `update|delete|rewrite|edit)` case arm; replace sed with jq for cwd; move heartbeat after relevance guard; add YAML `&invariant`/`*invariant` anchors; cap named-unresolved list at N with "+M more"
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (9 bats scenarios) R8 (adversarial: D7/D8 vs review)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 2. discharge-fidelity

- [x] Step 1 — typed contract (full): chain-state.sh discharge jq clause (`.exit == 0` filter, `failed` reason-code); ledger.sh `--forgive-unchanged` flag; gate.sh per-spec baseline from implementation-progress.md; checkpoint.sh shared forgiveness; chain-state-report-contract.jq discharge clause
- [x] Step 2 — test oracle: 11 bats scenarios (failed run, green run, mixed, only failed, undischarged vs failed, per-spec baselines, fallback to HEAD, forgive unchanged, forgive changed, no flag, gate+checkpoint agree) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `.exit == 0` filter to chain-state discharge jq; add `failed` reason-code; read per-spec baselines from implementation-progress.md; add `--forgive-unchanged` to ledger.sh read; wire gate.sh and checkpoint.sh to use per-spec baselines + --forgive-unchanged
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (11 bats scenarios) R4 (ledger JSONL compat: archived fixtures decode unchanged) R8 (adversarial: D1/D2 vs review)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 3. judgment-ring-integrity

- [x] Step 1 — typed contract (full): ledger.sh verify disagreement lint; ledger-record-contract.jq artifact requirement for manual/R8 rows
- [x] Step 2 — test oracle: 7 bats scenarios (resolvable artifact, missing artifact, no artifact field, grep disagreement, matching not flagged, non-runnable not flagged, verify flags disagreement) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add command/exit disagreement check to ledger.sh verify; require `artifact` field for manual/R8 rows in ledger-record-contract.jq; validate artifact existence at write time
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (7 bats scenarios) R4 (ledger contract compat: manual rows with artifact) R8 (adversarial: D4 vs review)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 4. fact-extraction-unification

- [x] Step 1 — typed contract (full): openspec-graph.py export subcommand (JSON topology); spec-lint.sh --format json; chain-state.sh consume graph JSON + degraded mode; install-skills.sh python3 prerequisite; schema.yaml python3 declared
- [x] Step 2 — test oracle: 10 bats scenarios (export JSON, chain-state consumes graph, unattributable deleted, spec-lint json F7, spec-lint json clean, chain-state consumes json, python3 check-installed, missing python3, degraded documented, degraded produces report) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `export` subcommand to openspec-graph.py; add `--format json` to spec-lint.sh; modify chain-state.sh to consume graph JSON via jq (degraded mode: awk fallback with trace line); add python3 to install-skills.sh --check-installed; add python3 to schema.yaml prerequisites
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (10 bats scenarios) R8 (adversarial: D5 vs review)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 5. evidence-capture

- [x] Step 1 — typed contract (full): ledger.sh run subcommand (capture mode, observes exit, rejects --exit); ledger.sh verify subcommand (replay mode); sha256/digest/wallTime fields; ledger-record-contract.jq optional new fields; schema.yaml apply Steps 3-11 instruction update (shipped but NOT consumed by this change)
- [x] Step 2 — test oracle: 13 bats scenarios (run observes exit 0, run records exit 1, run rejects --exit, sha256 recorded, sha256 absent in append, digest+wallTime, verify matches, verify diverges, verify names manual, verify non-zero on divergence, verify zero when all match, legacy rows readable, mixed legacy+capture) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `run` subcommand to ledger.sh (execute command, observe $?, compute sha256/digest/wallTime); add `verify` subcommand (re-execute, compare exits, report match/diverge/manual); extend ledger-record-contract.jq with optional new fields; update schema.yaml apply Steps 3-11 to mention `ledger.sh run` (NOT consumed by this change — bootstrapping)
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (13 bats scenarios) R4 (ledger JSONL compat: mixed legacy+capture rows) R8 (adversarial: D3/D6 vs review)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## Post-implementation (bootstrapping)

- [x] Re-install hooks from modified schema: `bash openspec/schemas/verified-scala3/hooks/install-hooks.sh`
- [x] Run full gate against the finished change: verify gate verdicts agree with bats suite
- [x] If gate disagrees with bats: investigate as a finding (not a revert)
- [x] Record final ledger row: `ring: "manual"`, `artifact: "spec-lint.md"` (the workflow's own verification report for this change)
