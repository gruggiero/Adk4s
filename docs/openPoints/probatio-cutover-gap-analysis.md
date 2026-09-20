# Gap analysis: completing the `verified-scala3` → `probatio` replacement

**Subject:** branch `probatio/porting` at `817d185` ("Implement native-gate-delivery
spec and archive complete-probatio-cutover"), after the `port-scanner-to-probatio`
(2026-08-25), `complete-probatio-porting` (2026-08-28) and `complete-probatio-cutover`
(2026-09-20) changes were all archived. Written to be transformed into the next
OpenSpec change against the workflow; every finding below is requirement-shaped, with
its mechanism and the artifact that would discharge it.

**Method.** Read-only analysis, plus three measured runs on 2026-09-20:

1. The 17-file / 282-test bats oracle against the live tree (the shims).
2. The same oracle against a **real predecessor control** — a detached `git worktree`
   at `817d185` with `hooks/gate.sh` and `scanner/{chain-state,danger-scan,reconcile,
   spec-lint}.sh` restored from their `*.predecessor.bak` files. The worktree was
   removed afterwards; the working tree was not disturbed.
3. `sbt probatioOracleDiff`, `probatio-core/test`, `probatio-cli/test`.

**Verdict: the port is functionally near-complete but NOT certified, and the mechanism
that certified it is inert.** probatio regresses 12 oracle tests against the real
predecessor across 4 files. The cutover gate's own criterion — "no bats file is worse
than the predecessor control" — is violated, and `probatioOracleDiff` cannot detect it
because neither of its two arms ever executes the predecessor. The correct verdict
under `cutover-gate` is REVERT, not the recorded PROCEED.

---

## 1. The measurement

| Arm | Pass | Fail | Total |
|---|---|---|---|
| Predecessor control (real, measured here) | 264 | **18** | 282 |
| Live probatio (shims, measured here) | 252 | **30** | 282 |
| `sbt probatioOracleDiff`, reported "predecessor arm" | — | **30** | 282 |
| `sbt probatioOracleDiff`, reported "ported arm" | — | **30** | 282 |

Per file — `Δ` is probatio's failures minus the real predecessor's:

| Bats file | Tests | Predecessor fail | probatio fail | Δ |
|---|---|---|---|---|
| `workflow-hygiene` | 10 | 0 | 6 | **+6** |
| `fact-extraction` | 10 | 0 | 3 | **+3** |
| `chain-state` | 24 | 6 | 8 | **+2** |
| `ambient-capture-wiring` | 32 | 0 | 1 | **+1** |
| `correctness-invariant` | 30 | 1 | 1 | 0 |
| `human-grant-lock` | 10 | 4 | 4 | 0 |
| `oracle-ordering-lock` | 13 | 7 | 7 | 0 |
| `ambient-evidence-capture` | 9 | 0 | 0 | 0 |
| `checkpoint-from-ledger` | 19 | 0 | 0 | 0 |
| `discharge-fidelity` | 11 | 0 | 0 | 0 |
| `evidence-capture` | 13 | 0 | 0 | 0 |
| `evidence-ledger` | 23 | 0 | 0 | 0 |
| `gate-payload` | 24 | 0 | 0 | 0 |
| `harness-install-verification` | 10 | 0 | 0 | 0 |
| `hook-tiers` | 27 | 0 | 0 | 0 |
| `judgment-ring-integrity` | 7 | 0 | 0 | 0 |
| `judgment-ring-provenance` | 10 | 0 | 0 | 0 |
| **Total** | **282** | **18** | **30** | **+12** |

The regression is purely additive: no test the predecessor fails now passes. The 18
shared failures are genuinely pre-existing (they fail identically under bash).

---

## 2. Finding D1 — the differential harness never runs the predecessor

**Severity: blocking.** Everything downstream depends on this measurement being real.

`DifferentialHarness.runSuite`
(`workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarness.scala:54`)
builds an environment map and runs `bats <file>` **in place**. It never copies a tree,
never writes a shim, and never reads a `*.predecessor.bak` file — despite its own
doc comment at line 7 claiming it "materialises two seam-configured copies of the
scanner tree".

Both arms therefore execute the same on-disk `.sh` files, which since the cutover are
the probatio shims. The only difference between the arms is five `*_OVERRIDE`
environment variables, which:

- the bats tests overwhelmingly set themselves inside each test (`hook-tiers.bats`
  alone contains 36 `*_OVERRIDE=` assignments), overriding the harness's value; and
- are **sub-tool selection seams** in both `gate.sh.predecessor.bak` and probatio's
  `SubcommandEntrypoints` — a way to stub *what the gate calls*, not a way to swap
  *the implementation under test*. `GATE_OVERRIDE` is read by nothing at all; the
  tests invoke `$GATE` as a file path.

Consequences:

- `hasRegression=false` is structurally guaranteed. `CutoverVerdict.Proceed` is
  unfalsifiable by construction.
- The recorded change exit criterion ("`probatioOracleDiff` reports no bats file worse
  than the predecessor control — VERDICT PROCEED 2026-09-20") was discharged by a
  comparison of a run against itself.

**Proof:** the harness prints `workflow-hygiene.bats: pred=6`. The real predecessor
fails **0** of that file's 10 tests. It prints `chain-state.bats: pred=8`; the real
predecessor fails **6**. Every `pred=` figure it reports equals the live probatio
figure, and equals the single-arm run measured directly.

**Second defect in the same mechanism.** `SeamTypes.ToolId`
(`.../migration/SeamTypes.scala:26`) enumerates only five tools — `SpecLint`,
`ChainState`, `DangerScan`, `Reconcile`, `Gate`. Even a *working* harness would leave
`evidence-ledger`, `evidence-capture`, `checkpoint-from-ledger`, `discharge-fidelity`,
`judgment-ring-integrity` and `judgment-ring-provenance` (82 tests) comparing bash
against bash, because `ledger` and `checkpoint` are not seams. probatio's ledger and
checkpoint implementations **have never been executed by the acceptance oracle**, in
either arm, at any point in the migration.

**Requirement shape.** *The differential comparison SHALL execute the predecessor
implementation in one arm and the ported implementation in the other, at every seam
the cutover swapped.* Mechanism: materialise two trees, restore `*.predecessor.bak`
in the predecessor arm, assert the two arms' script contents differ before running.
Artifact: a new bats or munit test that fails when both arms resolve to the same
bytes — the check that would have caught this.

---

## 3. Finding D2 — the 12 behavioural regressions

Attributed by diffing the failing-test name lists between the two arms.

### D2a — `chain-state` collapses UNDETERMINED into a finding (2 tests)

`chain-state.bats` #11 "a spec-lint that cannot run at all yields undetermined" and
#23 "PROPERTY every induced failure yields undetermined, never a satisfied report".

Observed: exit **1** plus a report asserting `total:1, bound:1, resolved:1,
discharged:0`. Expected: exit **2**, no report. The predecessor returns UNDETERMINED.

This is the design's Decision 2 three-way protocol being collapsed at exactly the
point it exists to protect: probatio emits a *measurement* of bound/resolved
requirements derived from a spec-lint invocation that did not run. A claim outruns its
evidence, inside the tool whose job is to detect that.

### D2b — the completion tier does not refuse an unwitnessed green row (1 test)

`ambient-capture-wiring.bats` #29 "completion is refused when a green row has no
witness". Observed exit 0 with empty output; predecessor exits 1. A blocking Stop-hook
enforcement tier is silently passing.

### D2c — the gate rejects the predecessor's event-name permissiveness (4 tests)

`workflow-hygiene.bats` #5, #6, #9, #10 all invoke
`gate.sh --event user-prompt-submit`. Measured directly:

| Invocation | Predecessor | probatio |
|---|---|---|
| `--event user-prompt-submit` | exit 0, 1470 bytes (injection tier) | `gate: unknown event 'user-prompt-submit'`, **exit 1** |
| `--event prompt-submit` | exit 0 | exit 0 |
| `--event totally-bogus-event` | exit 0 (injection tier) | exit 1 |

The predecessor dispatches five tier names (`tool-call`, `post-edit`, `post-bash`,
`completion`, and `prompt-submit` for fingerprint suppression) and routes **everything
else** to the injection tier. probatio's `eventFromString`
(`workflow/cli/.../SubcommandEntrypoints.scala:327`) is total over six names and
rejects the rest.

Strictness is defensible as a typed contract, but `migration-protocol` R-M1 is
explicit: *"A test that fails after substitution is a regression, not a test bug."*
Either the alias is restored, or the divergence is specified and the oracle amended
under a recorded decision — it cannot simply be left failing.

### D2d — `chain-state` lost the `openspec-graph.py` fact-extraction seam (3 tests)

`fact-extraction.bats` D5 #2, #6, #9. probatio's `chain-state` neither consumes
`openspec-graph.py export` JSON nor emits the required degraded-mode trace
`python3 unavailable; using degraded bash-only mode`. Note the coupling: `openspec-graph.py`
is on the declared out-of-scope list (§5), yet `chain-state` — which *is* in scope —
depends on it. The non-port was scoped as if the two were separable.

### D2e — two tests are structurally unpassable against a shim (2 tests)

`workflow-hygiene.bats` D7 (`grep -c 'install-skills\.sh' "$SPEC_LINT"`) and D8
("gate.sh extracts cwd from hook JSON using jq, not sed") assert over the *source text*
of the bash implementation. A 2-line exec shim contains neither string.

These are the only two of the twelve that are oracle-design artifacts rather than
behavioural gaps. They still need an explicit disposition — retarget them at the
probatio source, or retire them with a recorded rationale — because leaving them red
means the suite can no longer distinguish "known-stale test" from "new regression".

---

## 4. Finding D3 — tools ported but not swapped

Four subcommands exist in `Subcommand` and are wired, but their shims were never
swapped; the bash predecessors are what actually runs.

| Tool | Predecessor | probatio surface | Blocker |
|---|---|---|---|
| `ledger` | `scanner/ledger.sh` (616 lines; `append`/`run`/`read`/`verify`, 11 flags) | `ledger` (4 modes, flags present) | never exercised by the oracle (§2) |
| `checkpoint` | `scanner/checkpoint.sh` (514 lines) | `checkpoint report` / `regenerate-tasks` | same |
| `install-skills` | `scanner/install-skills.sh` (64 lines) | `install-skills --dir <one dir>` | **narrower surface** |
| `install-hooks` | `hooks/install-hooks.sh` (144 lines) | `install-hooks --dir <one dir>` | **narrower surface** |
| `metals start` | `scanner/metals-start.sh` (109 lines) | `metals start` | not swapped |

The two install tools are not shim-swappable as they stand:

- `install-skills.sh` has `--check-installed`, which probes the seven declared
  prerequisites (`bash git jq python3 shellcheck bats shfmt`) and is a distinct
  feature from `gate --check-installed` (the heartbeat probe, which probatio *does*
  have). It also installs into three agent roots (`.claude/skills`, `.pi/skills`,
  `.devin/skills`) from `../skills`. probatio takes a single `--dir`.
- `install-hooks.sh` has `--agent <pi|devin|claude>`, `--project <root>`, `-h/--help`,
  **dry-run by default with `--apply` to write**, and a python3 JSON merge into
  `.claude/settings.json` that refuses to clobber. probatio takes a single `--dir`.

**Requirement shape.** *A shim SHALL NOT be swapped onto a subcommand whose argument
surface is narrower than the predecessor's.* Mechanism: a surface-parity test per
tool, enumerating the predecessor's accepted flags and asserting each is accepted by
the port.

---

## 5. Finding D4 — tools explicitly not ported

Recorded as out of scope by the `complete-probatio-cutover` proposal, and correctly
removed from the `Subcommand` enum so they are unparseable rather than silently green.
Listed here so the remaining surface is stated rather than assumed:

| Tool | Lines | Notes |
|---|---|---|
| `scanner/registry-check.sh` | 346 | named in the gate banner's "gate checks" line |
| `scanner/impact-scan.sh` | 117 | apply Step 0 recipe; Metals-driven |
| `scanner/removal-audit.sh` | 106 | apply Step 12 recipe; Metals-driven |
| `scanner/scan.sh` + `concept-scanner.scala` | 53 + 538 | gated on the `native-packaging` R-N5 scalameta spike, **not run** |
| `scanner/openspec-graph.py` | — | but `chain-state` still needs it — see D2d |
| `scanner/metals-call.sh` | 158 | `metals` has `--method`/`--params` flags with no `call` token to reach them |

---

## 6. Finding D5 — red right now

- **`probatio-core/test`: 583/584.** `org.sinemenda.probatio.guard.NonGoalsGuardSpec`
  ("F1–F10 verdict stability across the port") fails with *"no spec fixtures under
  complete-probatio-cutover/specs"*. `NonGoalsGuardSpec.scala:350` hardcodes
  `openspec/changes/complete-probatio-cutover/specs`, which archiving moved to
  `openspec/changes/archive/2026-09-20-complete-probatio-cutover/specs`. The guard
  went red in the same commit that declared the change complete, and nothing re-ran
  the suite afterwards. `non-goals-guard` R-X1 — the feature freeze — is currently
  unenforced. This is the only test in `workflow/core/.../guard/`.
- **`correctness-invariant.bats` #23 asserts `the schema version is 13`.**
  `schema.yaml` declares `version: 14`. Red in both arms; one of the 18 pre-existing
  failures.
- `probatio-cli/test`: **651/651 green**. `sbt-probatio/test` did not run (the
  `probatio-core` failure aborted the batch).

---

## 7. Finding D6 — the v14 rename is declared, not performed

`schema.yaml` says `name: probatio`, `version: 14`, and the CHANGELOG's v14 entry
describes the full rename. On disk:

| Item | State |
|---|---|
| Schema directory | still `openspec/schemas/verified-scala3/` |
| Installed skill stamps | `generatedBy: verified-scala3-schema/13.0.0` in `.claude/skills`, `.pi/skills`, `.devin/skills`; `…/11.0.0` and `…/7.0.0` in `~/.zcode/skills`. The SessionStart banner reports this correctly every session — the *detector* works, the *skills* were never regenerated. |
| Env var | `PROBATIO_HOOKS` wired via `CliContext` with `VERIFIED_SCALA3_HOOKS` as a deprecated alias — **done**. `.claude/settings.json` documents only the legacy name. |
| Cache/state dir migration | `SchemaPolicy.migrateCache` / `CacheState` have **no production caller** — only `SchemaPolicy.scala` itself and `SchemaPolicySpec`. A verified kernel with no adapter; the requirement "cache and state directories auto-migrated on first run" is undischarged in the shipped artifact. |
| Tutorial docs | 14 HTML files under `docs/`, zero occurrences of "probatio"; `<title>verified-scala3 — the workflow tutorial</title>` |
| CI templates | all three (`github-actions.yml`, `gitlab-ci.yml`, `azure-pipelines.yml`) name `verified-scala3` paths |
| Harness adapters | `hooks/adapters/pi/verified-scala3-gate.ts`, `.pi/extensions/verified-scala3-gate.ts`, `hooks/README.md` |
| Gate state dir | `<git-dir>/verified-scala3-gate` — intentional (the oracle asserts it), but it is rename debt |
| Prerequisite retirement | CHANGELOG v14 declares jq, python3, shellcheck and shfmt retired. Still required by `install-skills.sh --check-installed`, `install-hooks.sh` (python3), `openspec-graph.py`, `ledger.sh`, `checkpoint.sh`, `registry-check.sh` and the CI templates. |

---

## 8. Finding D7 — infrastructure

- **Shims are not portable.** All five live shims hardcode
  `/home/gruggiero/git/rs/adk4s/openspec/schemas/verified-scala3/bin/probatio`. They
  are tracked in git. `bin/probatio` itself resolves relatively
  (`SCRIPT_DIR/../../../..`), so the fix is to give the shims the same treatment.
  Any clone, worktree, or CI runner currently gets an exec to a nonexistent path —
  and since `ShimGenerator` emits whatever path the resolution returned, this will
  recur unless the resolution is made relative for the in-repo install scenario.
- **Nothing runs the oracle in CI.** `.github/workflows/` contains only
  `release-probatio.yml`. The schema's `ci/github-actions.yml` is an uninstalled
  template. No job runs the bats suite, `probatioOracleDiff`, or the probatio test
  suites — which is how a red `NonGoalsGuardSpec` and a 12-test regression both
  reached an archived, checkpoint-approved change.
- **`.devin/skills` is written but never scanned.** `install-skills.sh` installs into
  it; `DriftScan.installRoots` searches six roots (`.agents`, `.claude`, `.pi` under
  the repo; `.agents`, `.claude`, `.zcode` under `$HOME`) and `.devin` is not among
  them. Pre-existing, not a port regression.

---

## 9. What is genuinely done

Stated so the next change does not re-litigate it:

- The argv contract is fixed; the built artifact works as a subprocess
  (`SubprocessConformanceSpec` runs against the native image).
- The live-fact banner reads from disk — this session's SessionStart block reported
  `PRESENT (37 concepts)`, `363 typed rows`, `TestControl testkit`, and the v13 stamp
  drift, all correct.
- `spec-lint`'s F1–F10 engine is at verdict parity with the predecessor under a
  model-based Hedgehog property that executes `spec-lint.sh` as the model over the
  repo's own spec corpus (`SpecLintParitySpec`, 452 lines). Same for `danger-scan`
  (`DangerScanParitySpec`).
- `gate` has all six events including `post-bash`, plus `--check-installed`.
- `reconcile` and `danger-scan` are at oracle parity.
- The native image builds and the R-N1 latency budget is met (median 129.4 ms
  against 150 ms p50, measured over 100 warm runs).
- `probatio-cli`: 651/651 green.

---

## 10. Proposed ordering for the next change

1. **Fix `DifferentialHarness`** — materialise two trees, restore `*.predecessor.bak`
   in the predecessor arm, and add the guard test that fails when both arms resolve
   to identical bytes. Extend `ToolId` to every swapped seam plus `Ledger` and
   `Checkpoint`. Re-measure. Nothing below is trustworthy until this measures
   something. (D1)
2. **Close the behavioural regressions** — D2a (UNDETERMINED collapse) and D2b
   (unwitnessed-row refusal) first; both are live enforcement holes. Then D2c, D2d.
   Disposition D2e explicitly. (D2)
3. **Restore the guards** — fix `NonGoalsGuardSpec`'s path, make the shims relative,
   install a CI job that runs the bats oracle + `probatioOracleDiff` + all probatio
   suites. (D5, D7)
4. **Swap the four ported-but-unswapped tools** under the now-real harness, after
   widening `install-skills` and `install-hooks` to predecessor surface parity. (D3)
5. **Finish the v14 rename** — directory, skill stamps, docs, CI templates, adapters,
   `migrateCache` wiring; update `correctness-invariant.bats` to 14; reconcile the
   declared prerequisite retirement with what the remaining bash actually needs. (D6)
6. **Only then retire the `.bak` files.** The archived change's own exit criterion
   keeps them as the revert target "until this criterion has held green across a full
   change cycle" — by the measurement in §1, it has not held green at all.

A note on scope discipline: `non-goals-guard` R-X1 (no new F-check, no verdict change,
no new workflow feature) still applies to all of the above, and per D5 its enforcing
test is currently red. Fixing that test belongs in step 3, before any step that could
be tempted to widen a verdict.

---

*Measurements in §1, §2 and §3 were taken on 2026-09-20 at `817d185`. The predecessor
control was a throwaway `git worktree`, removed after the run; the working tree was
left as found.*
