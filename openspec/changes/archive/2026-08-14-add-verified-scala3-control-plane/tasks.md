# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes.

     BOOTSTRAPPING NOTE (from design.md): bats-only verification for this
     change. The live gate's verdicts are NOT evidence during this change.
     The bats suite is the evidence. Re-install hooks and re-run the full
     gate only after all 5 specs are complete. -->

## 1. oracle-ordering-lock

- [x] Step 1 — typed contract (full): `gate.sh` tool-call event shape (block decision JSON / exit 2); phase-state machine (`oracle`/`implementation`/`verified`); polarity predicate over `ledger.sh run` rows ⊕ `git merge-base --is-ancestor`; fail-open when STATE_DIR unavailable; `VERIFIED_SCALA3_HOOKS=off` escape
- [x] Step 2 — test oracle: 12 bats scenarios (impl edit blocked, test edit allowed, artifact edit allowed, tooling edit allowed, red run advances, green run does not advance, non-ancestor baseline rejected, no row rejected, green after red verifies, green without red rejected, fail open no state dir, escape hatch allows) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `--event tool-call` to `gate.sh`; phase-state read/transition in git-dir state; polarity predicate (ledger rows ⊕ merge-base --is-ancestor); block only `*/src/main/**` while phase is oracle; fail-open discipline; escape hatch
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (12 bats scenarios) R8 (adversarial: oracle inversion vs failure-report drift + harness-agnostic blocking surface)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 2. human-grant-lock

- [x] Step 1 — typed contract (full): grant-token state (`grant-<change>-<spec-N>`); checkpoint-presentation record (hash of `checkpoint.sh report` output); prompt-submit writes grant; tool-call refuses spec N+1 Step-0 without grant; session-scoped; new-turn clears refusal
- [x] Step 2 — test oracle: 10 bats scenarios (blocked without grant, allowed with grant, grant scoped to next spec, prompt writes grant, no checkpoint no grant, non-prompt no grant, report writes presentation, no report no grant, grant session-scoped, new turn clears refusal) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: write grant on prompt-submit when presentation record exists; record checkpoint-presentation on `checkpoint.sh report` output; tool-call clause refusing spec N+1 Step-0 signature (baseline recording, implementation-progress.md section, spec-dir files) without grant; session-scoped state; clear refusal on new turn
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (10 bats scenarios) R8 (adversarial: tacit-approval vs failure-report drift)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 3. ambient-evidence-capture

- [x] Step 1 — typed contract (full): `gate.sh --event post-bash`; enumerated ring-shape match table; ledger row appended with harness-observed exit in existing JSONL format; never blocks
- [x] Step 2 — test oracle: 9 bats scenarios (green row recorded, red row recorded, non-matching no row, no double record, compile not recorded, danger-scan recorded, existing readers decode, legacy fixture compat, failed command no block) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `--event post-bash` to `gate.sh`; enumerated match table (`sbt .*test`, `danger-scan.sh`, `registry-check.sh`, `spec-lint.sh`, `checkpoint.sh report`, `ledger.sh run`); append ledger row with observed exit; de-duplicate explicit `ledger.sh run`; never emit a block decision
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (9 bats scenarios) R4 (ledger JSONL compat: ambient rows decode with existing readers; legacy fixtures unchanged) R8 (adversarial: missing-ledger vs failure-report drift)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 4. judgment-ring-provenance

- [x] Step 1 — typed contract (full): `session` field on `ring: "R8"` ledger rows; `ledger-record-contract.jq` requires `session` for adversarial-review rows; `checkpoint.sh` provenance check (report's session ≠ implementing session, or explicit limitation); D4 artifact+hash unchanged
- [x] Step 2 — test oracle: 8 bats scenarios (adversarial row has session, no session rejected, same session flagged, different session accepted, unverified session limitation, verified session clean, missing artifact rejected, artifact+different session pass) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `session` field to adversarial-review rows in `ledger.sh`/`ledger-record-contract.jq`; add provenance check to `checkpoint.sh` (session ≠ implementing session, or limitation note for unverified session source); keep D4 artifact+hash check
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (8 bats scenarios) R4 (ledger contract compat: adversarial rows with session; legacy rows without session decode unchanged) R8 (adversarial: self-assessed Ring 8 vs failure-report drift)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 5. harness-install-verification

- [x] Step 1 — typed contract (full): apply-Step-0 `--check-installed` requirement; `PreToolUse`/`tool_call` adapter wiring for Claude/Devin/pi; Devin three-level verification result; schema v13 changelog entry
- [x] Step 2 — test oracle: 10 bats scenarios (installed proceeds, uninstalled stops, devin stop verified, devin text fallback, devin stop not a block, claude pretooluse wired, devin pretooluse wired, pi tool_call wired, v13 entry present, v13 names asymmetry) — ORACLE POLARITY run (red)
- [x] Step 3 — implementation: add `PreToolUse` (matcher Bash|Edit|Write|MultiEdit) to claude.settings.json and devin.hooks.v1.json; add `tool_call` handler to pi adapter (map `{"decision":"block"}` → `{block:true,reason}`); make `--check-installed` an apply-Step-0 requirement in schema.yaml; execute Devin three-level verification, update hooks/README.md adapter table; add schema v13 changelog entry
- [x] Rings: R0 (bats run) R1 (shellcheck + shfmt) R3 (10 bats scenarios) R8 (adversarial: harness-agnostic blocking surface; Devin verified-or-corrected)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## Post-implementation (bootstrapping)

- [x] Re-install hooks from modified schema: `bash openspec/schemas/verified-scala3/hooks/install-hooks.sh --apply`
- [x] Run full gate against the finished change: verify gate verdicts agree with bats suite
- [x] If gate disagrees with bats: investigate as a finding (not a revert)
- [x] Record final ledger row: `ring: "manual"`, `artifact: "spec-lint.md"` (the workflow's own verification report for this change)
