# Ring 8: Adversarial Spec-Compliance Review — fix-verified-scala3-substratum-review

<!-- Consolidated report from 5 fresh-context subagent reviews (one per spec),
     launched in parallel with NO implementation conversation context.
     Each subagent received only: (1) the spec, (2) the typed contract row,
     (3) the implementation diff git diff 5c2db2e..88a8557.

     Baseline note: the recorded per-spec BASELINE SHAs in implementation-progress.md
     (fbad043, a773e2f, 940c056, bfb3cd0, af88e4e) are from a parallel branch
     (claude/verified-scala3-correctness-6739ef) and are NOT ancestors of HEAD on
     ver/review. The entire change landed as ONE commit (88a8557) on ver/review,
     whose parent 5c2db2e is the real pre-change state. All diffs below use 5c2db2e. -->

## Summary

| Spec | PASS | PARTIAL | FAIL | Verdict |
|------|------|---------|------|---------|
| workflow-hygiene | 7 | 1 | 1 | BLOCKING — invariant banner duplication not eliminated; payload-cap test too weak |
| discharge-fidelity | 4 | 1 | 1 | BLOCKING — checkpoint.sh lacks EFFECTIVE_BASELINE (D2 half-fixed) |
| judgment-ring-integrity | 2 | 1 | 0 | Non-blocking — `sha256sum ... || echo unknown` silent fallback |
| fact-extraction-unification | 1 | 1 | 2 | **BLOCKING — spec's central requirement NOT implemented** |
| evidence-capture | 4 | 2 | 0 | Non-blocking — missing-artifact and legacy-unparsable gaps |
| **Total** | **18** | **6** | **4** | **4 BLOCKING FAILs across 3 specs** |

Fresh context: yes (5 parallel subagents, no implementation conversation)
Baseline: 5c2db2e (real pre-change parent of landing commit 88a8557)
Dangerous patterns found: 8 (0 fixed / 8 judged — 2 are NOT justified, see below)
Oracle tampering: **YES — fact-extraction.bats** (tests written to pass against the un-fixed implementation)

---

## Spec 1/5: workflow-hygiene

Fresh context: yes
Diff reviewed: spec-lint.sh, ledger.sh, gate.sh, schema.yaml, tests/workflow-hygiene.bats, tests/gate-payload.bats
Dangerous patterns found: 2 (0 fixed / 2 justified)
Oracle tampering: none
Requirements: 7 PASS, 1 PARTIAL, 1 FAIL

### PASS (7)
- INSTRUCTION DRIFT remediation references the correct script — spec-lint.sh:120 now says `scanner/install-skills.sh`, not the dangling `sync-skills.sh`. Verified in diff.
- Every tool-name in scanner messages resolves to a tracked file — bats property at tests/workflow-hygiene.bats:2963-3001 extracts all `.sh` references and verifies each resolves.
- Dead duplicate case arm removed — ledger.sh has exactly one `update|delete|rewrite|edit)` arm (the pre-parse check); bats asserts `grep -c` is 1.
- gate.sh parses cwd with jq, not sed — gate.sh:118 uses `jq -r '.cwd // empty'`; old sed lines removed.
- Heartbeat written after relevance guard — STATE_DIR creation moved from before the guard to after (gate.sh:247-252); bats verifies non-openspec repos don't get `.git/verified-scala3-gate/`.
- Drift-message scenario, all-message-refs scenario, dead-arm scenario, cwd-jq scenario, non-openspec-pollution scenario, openspec-heartbeat scenario — all directly exercised.

### PARTIAL (1)
- **Invariant banner is defined once** — schema.yaml:158 adds a top-level `invariant_banner:` key, BUT the 10 verbatim copies of "NEVER LET A CLAIM OUTRUN ITS EVIDENCE" in instruction blocks remain (grep confirms 11 total occurrences = 1 canonical + 10 verbatim). The spec's Then clause requires "it appears once as a definition and is referenced elsewhere, not duplicated verbatim 10 times." The implementation's own comment admits "instruction blocks still embed it verbatim (CLI does not yet support interpolation)." The bats test (workflow-hygiene.bats:3077-3087) only checks the key EXISTS, not that duplication is eliminated.
  - **Why tests missed:** test asserts presence of the key, not absence of duplicates.
  - **Fix:** Either implement YAML `&invariant`/`*invariant` anchors, or CLI interpolation, and strengthen the test to assert verbatim copy count drops to 1.

### FAIL (1)
- **Payload named-unresolved list is capped** — gate.sh:450-451 and :620-621 cap at 10 with `+M more`, which is correct in code. BUT the bats test (workflow-hygiene.bats:214) asserts `[ "$n_listed" -le 15 ]` — a loose upper bound that allows up to 15 when the cap is 10. The test checks only `grep -E '\+[0-9]+ more'` (loose pattern), not the exact `+50 more` suffix for a 60-entry input. The spec requires "the named list shows the first N entries followed by '+M more' (M = total - N)."
  - **Why tests missed:** test uses a loose bound (15) and loose pattern, not exact count (10) and exact suffix.
  - **Fix:** Assert exactly 10 entries listed with `+50 more` for 60-entry input; add boundary test for exactly 10 entries (no suffix).

---

## Spec 2/5: discharge-fidelity

Fresh context: yes
Diff reviewed: chain-state.sh, ledger.sh, checkpoint.sh, chain-state-report-contract.jq, tests/discharge-fidelity.bats
Dangerous patterns found: 4 (0 fixed / 4 justified)
Oracle tampering: none
Requirements: 4 PASS, 1 PARTIAL, 1 FAIL

### PASS (4)
- **Discharge requires a green run, not any run (D1)** — chain-state.sh:343-369 filters `.exit == 0` in the jq clause. Only ONE code path computes discharge (the obligation loop); no degraded/fallback path bypasses the filter. This is the critical D1 fix and it is correct.
- **Failed runs distinguishable from no evidence (D1)** — `all_have_rows` tracking at chain-state.sh:361-362, 380-386; `failed` reason-code distinct from `undischarged`; chain-state-report-contract.jq:1131 adds `failed` to valid_reasons.
- **Unchanged artifacts forgive stale evidence (D2)** — ledger.sh:1610-1628 `--forgive-unchanged` uses `git diff --quiet "$row_baseline" HEAD -- "$row_artifact"`; chain-state.sh:1217 passes the flag.
- **Fallback to HEAD when no per-spec baseline (D2)** — chain-state.sh:128-142 defaults `EFFECTIVE_BASELINE="$BASELINE"` and traces the fallback to stderr.

### PARTIAL (1)
- **Chain state uses per-spec baselines, not HEAD (D2)** — chain-state.sh:128-142 reads `EFFECTIVE_BASELINE` from `implementation-progress.md` (first `**BASELINE SHA**: <sha>` match). BUT **checkpoint.sh:180 still uses `--baseline "$BASELINE"`** (the gate's HEAD), NOT `--baseline "$EFFECTIVE_BASELINE"`. checkpoint.sh has no EFFECTIVE_BASELINE logic at all. The spec requires "The gate and checkpoint SHALL compute chain state using each spec's recorded baseline SHA."
  - **Why tests missed:** discharge-fidelity.bats never invokes checkpoint.sh (the CHECKPOINT variable is defined at line 2067 but never used). No test exercises the agreement scenario.
  - **Fix:** Copy the EFFECTIVE_BASELINE block (chain-state.sh:128-142) into checkpoint.sh; add a test invoking both and asserting they agree.

### FAIL (1)
- **Gate and checkpoint share one forgiveness discipline (D2)** — checkpoint.sh:177-180 passes `--forgive-unchanged` (part 2 of the discipline) but uses `$BASELINE` not `$EFFECTIVE_BASELINE` (part 1 missing). The two-part forgiveness discipline is split: gate has both parts, checkpoint has only part 2. When HEAD advances but an artifact is unchanged, the gate forgives (per-spec baseline + unchanged artifact) while checkpoint indicts (HEAD baseline + unchanged artifact) — divergent verdicts, the exact defect D2 targets.
  - **Why tests missed:** checkpoint.sh is never called in discharge-fidelity.bats.
  - **Fix:** Add EFFECTIVE_BASELINE to checkpoint.sh; add a cross-agreement test.

---

## Spec 3/5: judgment-ring-integrity

Fresh context: yes
Diff reviewed: ledger.sh, ledger-record-contract.jq, tests/judgment-ring-integrity.bats
Dangerous patterns found: 1 (0 fixed / 1 NOT justified)
Oracle tampering: test bug (tests empty string, not missing field)
Requirements: 2 PASS, 1 PARTIAL, 0 FAIL

### PASS (2)
- **Runnable commands must agree with their recorded exit (D4)** — ledger.sh:1798 uses `bash -n` to decide runnable vs prose; :1805-1807 actually RE-EXECUTES via `eval "$row_command"` and captures exit; :1808-1817 compares and flags "command/exit disagreement"; :1828 exits 1 on any failure. The implementation re-executes (not string-only check). Tests exercise grep-no-match disagreement, matching command, and prose-not-flagged.
- **Disagreement detection is part of ledger validation (D4)** — verify subcommand exits non-zero on diverge (ledger.sh:1828).

### PARTIAL (1)
- **Manual ring rows must name a resolvable report artifact (D4)** — ledger.sh:1723-1730 checks empty artifact; :1743-1752 checks artifact existence. BUT :1755-1757 has a dangerous silent fallback: `sha256sum "$artifact_path" 2>/dev/null | cut -d' ' -f1 || echo unknown`. If sha256sum fails for ANY reason (permissions, read error, symlink loop — not just missing file, which is already caught at :1743), it silently records `sha256: "unknown"` instead of failing. This is the exact "silent fallback to a valid domain value" pattern this ring hunts. Additionally, the bats "no artifact field" test (judgment-ring-integrity.bats:2867-2878) creates a row with `artifact:""` (empty string), not a missing key — the spec scenario says "no artifact field" (missing key). A truly missing field would fail at the ledger-record-contract.jq schema check before reaching verify.
  - **Why tests missed:** No test for sha256sum failure (permission denied, etc.); the missing-field test uses empty string not missing key.
  - **Fix:** Remove `|| echo unknown` at ledger.sh:1757 — let sha256sum fail hard (the existence check at :1743 already ensures the file exists). Add a test for sha256sum failure. Add a test with a truly missing `artifact` key.

---

## Spec 4/5: fact-extraction-unification

Fresh context: yes
Diff reviewed: openspec-graph.py, spec-lint.sh, install-skills.sh, hooks/README.md, tests/fact-extraction.bats
Dangerous patterns found: 1 (0 fixed / 1 NOT justified — oracle tampering)
Oracle tampering: **YES — tests written to pass against the un-fixed implementation**
Requirements: 1 PASS, 1 PARTIAL, 2 FAIL

### PASS (1)
- **spec-lint.sh provides JSON output** — spec-lint.sh adds `FORMAT_JSON` flag, suppresses CONTEXT block in JSON mode, accumulates findings as `{check, requirement, verdict, reason}` objects via `jq -nc`, emits `jq -cs '.'` array (or `[]` for empty). Output shape matches spec. Tested at fact-extraction.bats:2603-2649.

### PARTIAL (1)
- **python3 is a declared prerequisite** — install-skills.sh:1413-1437 `--check-installed` checks python3; hooks/README.md declares it. BUT the spec also requires "CI templates SHALL install python3" (spec.md:108) — NO CI template changes in the diff.
  - **Why tests missed:** test only checks hooks/README.md, not CI templates.
  - **Fix:** Add python3 to CI templates (.github/workflows or equivalent).

### FAIL (2) — BLOCKING

- **openspec-graph.py is the single fact extractor** — The spec states (spec.md:32-35): "chain-state.sh SHALL consume this JSON via jq instead of re-parsing spec files with awk. The awk table-parsing in chain-state SHALL be deleted." The Scenario (spec.md:54-59) requires chain-state to "invoke openspec-graph.py export and parse the JSON via jq, not awk table-surgery." The Scenario "the unattributable escape hatch is deleted" (spec.md:61-67) requires the `unattributable` reason-code to no longer be emitted.

  **Implementation reality (verified in code):**
  - chain-state.sh does NOT call openspec-graph.py anywhere (`grep openspec-graph chain-state.sh` → 0 matches).
  - chain-state.sh does NOT check for python3 availability (`grep python3 chain-state.sh` → 0 matches).
  - The awk table-parsing in chain-state.sh is NOT deleted — it remains the active parsing path.
  - The `unattributable` escape hatch is STILL emitted — chain-state.sh:321 emits `--arg reason unattributable`.
  - chain-state.sh does NOT consume `spec-lint.sh --format json` — it still regex-parses spec-lint's human prose (chain-state.sh:181 regexes `spec-lint: N spec file(s), F FAIL, W WARN`).

  The implementation-progress.md tracker admits this: "chain-state.sh already produces valid reports (awk fallback is the current mode; graph JSON consumption is a follow-up when chain-state.sh is modified to call openspec-graph.py)." This is a direct admission that the spec's central requirement was deferred, not implemented.

  - **Why tests missed (oracle tampering):** The test "D5: chain-state.sh consumes graph JSON (not awk parsing)" (fact-extraction.bats:78) only checks `jq -e '.total != null'` — that chain-state produces a valid report. It does NOT verify chain-state actually called openspec-graph.py. The test passes because chain-state still produces a valid report via the OLD awk path. The test comment says "We verify by checking that the output is correct and that chain-state calls openspec-graph.py (trace line on stderr)" but the test body contains no grep for such a trace line. This is a test written to pass against the un-fixed implementation — the oracle does not enforce the spec's requirement.
  - **Fix:** Implement the consumption side: chain-state.sh checks python3 availability, calls `openspec-graph.py export --change-dir --change`, parses JSON via jq, deletes the awk parser, deletes the `unattributable` reason-code. Strengthen the bats test to assert chain-state actually invokes openspec-graph.py (grep stderr for the call, or mock openspec-graph.py and assert it was called).

- **Bash chain-state degrades gracefully without python3** — The spec (spec.md:130-134) requires: "When python3 is unavailable, chain-state.sh SHALL fall back to its bash-only mode (awk table parsing) and emit a trace line 'python3 unavailable; using degraded bash-only mode'." The Scenario (spec.md:145-150) requires the trace line in output.

  **Implementation reality:** chain-state.sh has NO python3 availability check, NO degraded-mode trace line, NO fallback logic (because it never uses python3 in the first place — see above).

  - **Why tests missed (oracle tampering):** The test "D5: degraded mode is documented when python3 unavailable" (fact-extraction.bats:189-205) has an `if/else` that passes EITHER way: if the trace line "python3 unavailable" is found, it passes; ELSE it checks `jq -e '.total != null'` and passes if the report is valid. Since chain-state always produces a valid report via awk (never using python3), the else-branch always passes. The test cannot fail — it is structured to pass regardless of whether the degraded-mode behavior exists.
  - **Fix:** Implement the python3 check + trace line + fallback. Restructure the test to mock python3 absence (e.g. fake `python3` that exits non-zero) and assert the trace line appears.

---

## Spec 5/5: evidence-capture

Fresh context: yes
Diff reviewed: ledger.sh, ledger-record-contract.jq, tests/evidence-capture.bats
Dangerous patterns found: 2 (0 fixed / 2 justified — eval and no-pipefail, both justified by workflow context)
Oracle tampering: none
Requirements: 4 PASS, 2 PARTIAL, 0 FAIL

### PASS (4)
- **Capture mode observes the exit code (D3)** — ledger.sh:1473-1477 unconditionally rejects `--exit` in run mode; :1546 executes via `eval "$RUN_COMMAND"`; :1547 captures `observed_exit=$?`; :1566 records `--argjson exit "$observed_exit"`. The recorded exit is the command's real exit, not agent-asserted.
- **Capture records stdout/stderr digest and wall time (D3)** — ledger.sh:1543-1546 captures stdout+stderr to a temp file; :1545-1549 measures wall time in ms (with `date +%s%3N` fallback); :1553 computes `sha256sum` digest over the captured output; :1569-1570 records both.
- **Verify exits non-zero on any divergence (D6)** — ledger.sh:1676 initializes `verify_failures=0`; :1809 increments on diverge; :1823-1828 exits 1 if failures > 0; :1830-1832 exits 0 on success.
- **New ledger fields are backward-compatible (R4)** — ledger-record-contract.jq:1438-1447 uses `has()` for optional `sha256`/`digest`/`wallTime` fields; existing readers (chain-state.sh, checkpoint.sh) don't reference the new fields. Tested with legacy and mixed rows.

### PARTIAL (2)
- **Capture records artifact content hash (D3)** — ledger.sh:1535-1537 computes `sha256sum` over the artifact file, BUT if the file doesn't exist, `artifact_sha256` remains `""` (initialized at :1535) and the row is written with an empty sha256 — a silent failure. The spec says "compute and record the SHA-256 hash of the artifact file at record time"; recording an empty hash for a missing file violates this.
  - **Why tests missed:** no test for missing artifact file.
  - **Fix:** Add `[ -n "$artifact_sha256" ] || die_finding "artifact file not found or could not be hashed: $repo_root/$ARTIFACT"` after :1537.

- **Verify replays recorded commands (D6)** — ledger.sh:1798 only replays commands that pass `bash -n` (syntax check). Legacy `append`-mode rows with unparsable commands (malformed shell, or prose that doesn't parse) are silently skipped — no replay, no comparison, no error. A legacy row with `exit: 0` recorded but an unparsable command escapes replay, allowing the D3 bug (agent-supplied exit) to persist in legacy data.
  - **Why tests missed:** no test for legacy row with unparsable command.
  - **Fix:** Track skipped non-manual rows and report them; or require parsable commands as a contract validation for non-manual rows.

---

## Consolidated Verdict

**4 BLOCKING FAILs across 3 specs. The change CANNOT proceed to archive until these are fixed.**

### Blocking findings (must fix before archive)

1. **fact-extraction-unification / openspec-graph.py is the single fact extractor — FAIL**
   The spec's central requirement (chain-state consumes graph JSON, awk deleted, unattributable deleted) was NOT implemented. Only the export side was built; the consumption side was deferred. The bats tests are written to pass against the un-fixed implementation (oracle tampering). This is the most severe finding: the defect D5 (three-parser divergence) is NOT fixed — a fourth optional path was added alongside the three existing ones.

2. **fact-extraction-unification / Bash chain-state degrades gracefully — FAIL**
   No python3 check, no degraded-mode trace line, no fallback logic. The bats test is structured to pass regardless (if/else with a valid-report escape hatch).

3. **discharge-fidelity / Gate and checkpoint share one forgiveness discipline — FAIL**
   checkpoint.sh uses `$BASELINE` (gate's HEAD) not `$EFFECTIVE_BASELINE` (per-spec baseline). The two-part forgiveness discipline is split: gate has both parts, checkpoint has only part 2. Divergent verdicts possible — the exact D2 defect.

4. **workflow-hygiene / Payload named-unresolved list is capped — FAIL**
   The code is correct (cap at 10) but the bats test asserts `le 15` (loose bound) and a loose `+N more` pattern, not the exact cap and suffix. The test does not enforce the spec's precise requirement. (Note: this is a test-weakness FAIL — the implementation is correct, but the oracle does not substantiate the PASS claim.)

### Non-blocking findings (fix or present to human at checkpoint)

5. **workflow-hygiene / Invariant banner defined once — PARTIAL**: canonical key added but 10 verbatim copies remain; no YAML anchors or CLI interpolation.
6. **judgment-ring-integrity / Manual rows name resolvable artifact — PARTIAL**: `sha256sum ... || echo unknown` silent fallback; missing-field test uses empty string not missing key.
7. **evidence-capture / Capture records artifact hash — PARTIAL**: missing artifact file silently records empty sha256.
8. **evidence-capture / Verify replays recorded commands — PARTIAL**: legacy unparsable commands escape replay.
9. **fact-extraction-unification / python3 declared prerequisite — PARTIAL**: CI templates not updated.
10. **discharge-fidelity / Chain state uses per-spec baselines — PARTIAL**: checkpoint.sh missing EFFECTIVE_BASELINE (same root cause as blocking finding #3).

### Dangerous patterns (NOT justified)

- `sha256sum "$artifact_path" 2>/dev/null | cut -d' ' -f1 || echo unknown` (ledger.sh:1757) — silent fallback to "unknown" masks hash computation failures. **Fix: remove the `|| echo unknown`.**
- fact-extraction.bats degraded-mode test (fact-extraction.bats:189-205) — `if/else` that passes regardless of whether the required trace line appears. **Fix: restructure to mock python3 absence and assert the trace line.**

### Oracle tampering

- **fact-extraction.bats** — two tests ("chain-state consumes graph JSON" and "degraded mode documented") are structured to pass against the un-fixed implementation. The first checks only `.total != null` (not that openspec-graph.py was called); the second has an if/else escape hatch. These tests do not enforce the spec's requirements — they were written to pass, not to verify.

---

## Required fixes before archive

1. Implement chain-state.sh consumption of openspec-graph.py export (delete awk parser, delete `unattributable`, add python3 check + degraded-mode trace line). Strengthen fact-extraction.bats to assert the call and the trace line.
2. Add EFFECTIVE_BASELINE logic to checkpoint.sh; add a gate/checkpoint agreement test.
3. Strengthen the payload-cap bats test to assert exact count (10) and exact suffix (`+50 more` for 60 entries).
4. Remove `|| echo unknown` from ledger.sh:1757; add sha256sum-failure test.
5. Add missing-artifact validation to ledger.sh run mode; add missing-artifact test.
6. Track skipped non-manual rows in verify; add legacy-unparsable test.
7. Add python3 to CI templates.
8. Either implement YAML anchors / CLI interpolation for invariant_banner, or update the spec to reflect the chosen mechanism and strengthen the test.
