# Status: what remains to fully replace `verified-scala3` with `probatio`

**Subject:** branch `probatio/porting` at `069e428` ("Archive repair-probatio-cutover — sync
11 specs to main"), after `repair-probatio-cutover` implemented all 11 specs and was
archived on 2026-09-25. Follows `probatio-cutover-gap-analysis.md` (2026-09-20). Written
to seed the next OpenSpec change; findings are requirement-shaped with the evidence that
established each one.

**Method.** Everything below was measured on 2026-09-25, not inherited from the archived
change's records. Five runs:

1. The 17-file / 287-test bats oracle against the live tree.
2. The same oracle against an **independent predecessor control** — a detached worktree
   at `069e428` with all seven `*.predecessor.bak` files restored over their shims.
3. `sbt probatioOracleDiff` (as part of `probatio-core/test`).
4. `probatio-core/test`, `probatio-cli/test`, `sbt-probatio/test`.
5. Three oracle files in a **JAR-only worktree** — the assembly JAR present, no native
   image — which is what `verify.yml` and any fresh clone provision.

Worktrees were removed afterwards; the working tree was left as found.

**Verdict: the repair succeeded at its central goal, but probatio does not yet replace
verified-scala3.** The differential comparison is now genuine — its arms diverge, and its
PROCEED verdict is corroborated by an independent control. All twelve regressions from
the previous report are closed. But the workflow only functions on a machine where a
GraalVM native image was built locally; the change archived with three red tests; two
tools the register calls "ported" still run as their predecessors; and one ported
subcommand is a silent-success stub.

---

## 1. The measurement

| Arm | Pass | Fail |
|---|---|---|
| Independent predecessor control (7 `.bak` restored) | 269 | **18** |
| Live probatio | 270 | **17** |
| `probatioOracleDiff` predecessor arm | — | 20 |
| `probatioOracleDiff` ported arm | — | 17 |
| **JAR-only environment** (3 files sampled: `gate-payload`, `evidence-ledger`, `hook-tiers`) | 11 of 74 | **63** |

| Test suite | Result |
|---|---|
| `probatio-core` | 808 / 810 — **2 failing** |
| `probatio-cli` | 783 / 784 — **1 failing** |
| `sbt-probatio` | 90 / 90 |

**What is now true that was not on 2026-09-20:**

- The harness materialises two different trees (`arms diverged`), and its ported-arm
  count equals the live measurement exactly. Its verdict — no file worse — is confirmed
  by the independent control: the port is at or below the predecessor in every file, and
  strictly better on `fact-extraction`.
- All twelve previously-measured regressions pass: the UNDETERMINED collapse, the
  unwitnessed-row refusal, the event-name fallback, the graph seam, and both retargeted
  structural tests.
- Seven tools run through probatio: `gate`, `spec-lint`, `chain-state`, `danger-scan`,
  `reconcile`, `ledger`, `checkpoint`. Every shim resolves relative to its own location.
- `chain-state` builds the traceability graph in-process; python is off its path.
- All 30 installed workflow skill documents carry `probatio-schema/14.0.0`.
- `NonGoalsGuardSpec` locates its corpus in the archive, and the cache migration has a
  caller.

**One small harness discrepancy.** The harness reports `fact-extraction` pred=3; a clean
predecessor tree gives 1. The harness's predecessor arm is two failures pessimistic on
that file, so it would mask a port regression of up to two tests there.

---

## 2. Finding A — the workflow only runs where a native image was built locally

**Severity: blocking.**

### A1. The JAR fallback cannot dispatch any subcommand

Every invocation through `java -jar` fails before reaching a tool:

```
$ java -jar probatio-cli-assembly-0.1.0-SNAPSHOT.jar gate --event prompt-submit …
unknown subcommand: probatio-cli-assembly-0.1.0-SNAPSHOT.jar      (exit 1)
```

Multicall dispatch resolves the program name from `sun.java.command`, which under
`java -jar` is the JAR's filename. `bin/probatio`'s own comment says so and falls back to
`java -jar` anyway. The plugin's installed launcher (`ProbatioPlugin.scala:334`) emits the
same `exec java -jar`.

**Who is affected:** every fresh clone and adopter (both the native image and the JAR are
gitignored build outputs); `verify.yml`, which builds only the assembly; Windows, which
the release matrix designates JAR-only; and anyone installing via the plugin without a
native binary. In the JAR-only worktree, `gate-payload` passed 3 of 24, `evidence-ledger`
4 of 23, `hook-tiers` 4 of 27, and the CI's own `bin/probatio spec-lint .` step exited 1.

A hook that exits 1 is a *non-blocking hook error* to Claude Code, so in production this
means the gate silently does not run — the same failure mode as the original argv defect
the first cutover change was created to fix.

**The fix already exists, in test code.** `ChainStateParitySpec.scala:595–597` symlinks
the JAR as `probatio` before calling `java -jar`. Verified here: `java -jar <link named
probatio> --help` and `gate --event prompt-submit` both exit 0. The workaround never
reached `bin/probatio` or the plugin's launcher. `SubprocessConformanceSpec` runs only
against the native artifact, so no test exercises the path that fails.

**Requirement shape.** *Every shipped launcher SHALL dispatch correctly when only the
assembly JAR is present.* Artifact: a subprocess conformance run with the native image
absent.

### A2. `verify.yml` has never executed

The branch is 55 commits ahead of `origin/probatio/porting` and 66 ahead of `github/main`,
and has never been pushed to GitHub, where `verify.yml` would run on a pull request.
Because it provisions only the JAR, **its first run will fail** (A1).

The obligation *"a regressing change fails the job"* was discharged by planting a failing
bats file locally, observing `bats` exit 1, and inferring the job's behaviour — recorded
in the evidence ledger as `exit: 0` with the inference in the command text. No job run
was ever observed.

### A3. No release has ever been cut

There are no `v*` tags, so `release-probatio.yml` has never run and there are no
published native binaries. Today the only working configuration is a local GraalVM
build.

---

## 3. Finding B — three tests are red at HEAD

The change archived with all three.

### B1. The completion-tier parity property depends on who runs it

> **Corrected on 2026-09-25, after root-causing.** This section first reported a
> behavioural divergence — "the port refuses where the predecessor allows". **That was
> wrong.** The two gates agree. The failure is a test-isolation defect.

`GateBannerCompatSpec` — *parity-with-predecessor-on-the-completion-tier* — fails 3 of 3
runs inside a Claude Code session, always with the same counterexample (one green,
current-baseline, uncorroborated row, no prior refusal: port 1, predecessor 0), and
**passes** when `CLAUDE_CODE_SESSION_ID` is unset.

**Root cause.** The test gives the port its session with `--session parity-session` but
gives the predecessor subprocess `VERIFIED_SCALA3_SESSION_ID`. The predecessor ranks
`CLAUDE_CODE_SESSION_ID` above that variable (`gate.sh.predecessor.bak:196–197`), and the
subprocess inherits it from whatever shell runs `sbt test`. Inside a Claude Code session
the predecessor looks up the wrong session, finds no checkpoint-presentation marker, and
allows. Reproduced by hand on the same fixture:

| Invocation | Exit |
|---|---|
| predecessor, `CLAUDE_CODE_SESSION_ID` unset | **1** — "completion refused: ledger rows are uncorroborated" |
| predecessor, `CLAUDE_CODE_SESSION_ID` inherited | **0** — allow |
| port, `--session parity-session` | **1** — same message |

That is also why it was green at its checkpoint: the result depends on the invoking
environment, not on the code.

**It is a class, not one test.** 19 Scala test files spawn processes; only one of them
(`LiveFactBannerSpec`) handles `CLAUDE_CODE_SESSION_ID`. 11 of the 17 bats files do not
unset it. Every measurement in this report was taken inside a Claude Code session; both
arms of every comparison saw the same environment, so the comparisons are fair, but
absolute counts could differ on a CI runner.

**Needs:** every test that spawns a workflow tool runs it in a hermetic environment —
harness session variables cleared unless the test sets them — enforced by one shared
process helper and a lint against raw process construction in tests. Separately, the
failing run reported the *current-baseline warrant* case at 3% against a declared 20%
cover minimum; whether cover minimums are enforced on passing runs should be confirmed.

### B2. `DifferentialHarnessSpec` hardcodes the active change path

*"the recorded predecessor control fixture is well-formed"* fails with `control fixture
missing: …/openspec/changes/repair-probatio-cutover/fixtures/predecessor-control.json`.
Archiving moved it to `archive/2026-09-25-repair-probatio-cutover/fixtures/`.

This is the **same defect** `feature-freeze-guard-integrity` fixed for
`NonGoalsGuardSpec`, recurring in a different suite: the fix was applied to one test
rather than to the pattern. **Requirement shape:** *no test SHALL reference a change
directory by a literal active-area path* — enforced by a shared archive-aware resolver and
a lint that rejects the literal.

### B3. The oracle-immutability guard is red and accepted

`NonGoalsGuardSpec` — *oracle immutability at every migration step* — fails at commit
`55837a0`. It enforces the migration protocol's rule that the acceptance oracle passes
**unmodified**. It was recorded as "pre-existing" at spec 6's checkpoint and left red.

The oracle was modified in five of the eleven specs — four files, +248 / −88 lines.
`workflow-hygiene.bats` was edited in four commits, but only spec 1 sanctioned editing it.
Spec 8's edit, for example, retargets a test that grepped `ledger.sh`'s source once
`ledger.sh` became a shim — reasonable in substance, recorded nowhere as a sanctioned
change. A guard that is permanently red and accepted guards nothing.

**Requirement shape:** *each oracle modification SHALL cite the spec requirement that
sanctions it, and the immutability guard SHALL pass exactly when every modification since
the baseline is sanctioned.*

---

## 4. Finding C — tools the register calls "ported" that are not

The register classifies a tool as `Ported` when its name is on the subcommand surface —
not when its live invocation path runs the port. Three tools fall through that gap.

| Tool | Register says | What actually runs |
|---|---|---|
| `scanner/install-skills.sh` (64 lines) | `Ported("install-skills")` | **the bash script** — never swapped |
| `hooks/install-hooks.sh` (145 lines) | `Ported("install-hooks")` | **the bash script** — never swapped; still needs python3 |
| `scanner/metals-start.sh` (109 lines) | `Ported("metals")` | **the bash script** — the port is a stub (C2) |

### C1. The two installers were ported but not swapped

`install-tool-surface-parity` reached surface parity against the predecessor, but its task
list never contained a swap step, and the swap did not happen; implementation-order's exit
criterion for that spec said "the two installer shims swapped". The differential harness
confirms it: it lists both installers under *not-compared files — exercise tools with no
seam*.

### C2. `metals start` reports success having done nothing

```
$ probatio metals start
metals: server started                                            (exit 0)
metals-mcp processes before: 3, after: 3.   .metals/mcp.url written: no
```

`MetalsClient.initialize` is a pure model: any timeout of 10 ms or more returns
`initialized = true` without launching a process. This violates
`cli-entrypoint-contract` — *"a tool whose work is not implemented SHALL NOT be selectable
— it must be unrecognised rather than recognised-and-silent"* — and `cli-wiring`'s
scenario *"metals start launches the LSP server … the endpoint URL is emitted on stdout"*,
whose obligation was discharged by "adversarial review" with its test "to be written in
apply phase". The register's own notes acknowledge the stub and classify it `Ported`
anyway.

### C3. The python graph tool is still live in the documentation

`chain-state` no longer calls it, but `openspec-graph.py` remains an executable in
`scanner/` rather than becoming a `.predecessor.bak`, and the tutorial
(`docs/09-tooling.html`, `docs/10-practice.html`) still instructs users to run it.
`probatio graph` is documented nowhere.

**Requirement shape for C1–C3:** *a tool SHALL classify `Ported` only when its live
invocation path reaches the port* — a shim is present, or the tool is reached through
`bin/probatio`.

---

## 5. Finding D — tools explicitly not ported

| Tool | Lines | Registered blocker | Verified status |
|---|---|---|---|
| `registry-check.sh` | 346 | scalameta spike | **Misassigned.** It uses only grep/awk/bash — no scalameta, no scala-cli. Portable today. It is also the one unported tool on the enforcement path: `verify.yml` runs it and the session banner names it every session. |
| `scan.sh` + `concept-scanner.scala` | 591 | scalameta spike | Genuine. The spike has never run — `workflow/spike` exercises uPickle, os-lib and mainargs only, and its source notes scalameta "is spike-gated separately". |
| `impact-scan.sh` | 117 | not on enforcement path | Accurate. |
| `removal-audit.sh` | 106 | not on enforcement path | Accurate. |
| `metals-call.sh` | 158 | not on enforcement path | Accurate — and it has no reachable endpoint through the port until C2 is fixed. |

---

## 6. Finding E — the rename is not finished

| Item | State |
|---|---|
| Skill stamps | **Done** — 30 × `probatio-schema/14.0.0` |
| Tutorial | **Largely done** — 43 mentions of probatio; see C3 |
| Directory rename | **Deferred, recorded** — `RenameDeferral.recorded` names the coupling with `recordedChangesPinning = 19`, which matches the tree. Blast radius: **105 tracked non-archive files** reference `schemas/verified-scala3` (schema 33, `workflow/cli` 22, specs 20, `workflow/core` 18, …). Every remaining old-name occurrence in the CI templates and adapters is one of these paths, so they move with the directory, not before it. |
| Environment variables | **Incomplete.** Only `HOOKS` and `HOOKS_TRACE` have `PROBATIO_` names. Four are read **only** under the legacy prefix: `VERIFIED_SCALA3_ACTIVE_SPEC`, `_ALLOW_PATHS`, `_SESSION_ID`, `_SKIP_PREDECESSOR_CHECK`. The gate's own refusal message tells users to *"Set VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1"*. |
| Legacy alias window | Closes at v16 (`aliasMajorWindow = 1`). The local `.claude/settings.json` documents only `VERIFIED_SCALA3_HOOKS=off`, so its escape hatch will stop working at v16 unless updated. |
| Gate state directory | Still `<git-dir>/verified-scala3-gate`; the oracle pins the name. |
| pi adapter | Still named `verified-scala3-gate.ts`, both in the schema and in `.pi/extensions/`. |

---

## 7. Finding F — the prerequisite retirement is still untrue

The v14 changelog declares jq, python3, shellcheck and shfmt retired. Measured use:

| Prerequisite | Still required by |
|---|---|
| jq | the bats oracle (172 references), the CI templates, the three `.jq` contracts |
| python3 | `install-hooks.sh` (live — C1), `metals-call.sh`, `openspec-graph.py`, the oracle, the CI templates |
| shellcheck, shfmt | the CI templates; the installers' prerequisite probe |

Both installers — predecessor and port — probe for all four, the port correctly so,
because it was ported at parity. Retiring them therefore depends on C1, on the remaining
ports, and on a decision about the oracle itself (G2). Amending the probed set changes a
verdict, so it needs its own change outside the feature freeze.

---

## 8. Finding G — the oracle

### G1. Seventeen failures have been "pre-existing" for three changes

They fail identically in both implementations and were never re-established — the
invariant requires a recorded limitation to be re-tested before it is relied upon.

**Eleven are stale fixtures — the locks work.** Every failing test in
`oracle-ordering-lock` (7) and `human-grant-lock` (4) calls the gate without a tool name.
The gate treats an empty tool name as read-only and allows — its trace says
`read-only tool '' on production path, allow`. With a name supplied, both locks block
(exit 2) in both implementations; verified for `Edit`, `Write`, `MultiEdit`, and pi's
lowercase `edit`, `write`, `bash`, by flag and by payload. Fixing them is an oracle edit,
which needs the sanction mechanism from B3.

The underlying design point is real: **an absent tool name fails open**. Claude Code and
pi supply one. The Devin adapter passes no `--tool` and relies on the payload —
MUST-CONFIRM against Devin's hook payload documentation that the tool name arrives in the
field the gate reads.

**Six `chain-state` tests are not root-caused.** Each reports `discharged: 0` and
`"degraded": true` on a fixture the test describes as fully satisfied. Hypothesis, not
verified: the fixture writes its rows with `ledger append` — testimony — while discharge
now requires corroboration.

### G2. Every swap forces an oracle edit

Structural tests grep a script's source. When a script becomes a shim they cannot pass, so
the oracle is edited to follow the implementation — specs 1, 8, 9 and 10 all did this.
Every remaining port will force more, eroding the independence that makes the oracle an
oracle. **Needs a decision:** separate the behavioural tests from the structural ones, and
either retire or re-home the structural ones.

---

## 9. Finding H — mutation thresholds missed, recorded but not enforced

| Spec | Target | Recorded |
|---|---|---|
| `gate-event-compatibility` (CLI) | 80% | **77.26%** |
| `install-tool-surface-parity` (CLI) | 80% | **46.58%** |
| `schema-rename-completion` (core) | 80% | **52.63%** |

Each was dispositioned as "all in-diff survivors justified". `stryker4s.conf` has
`break = 0`, so none failed anything. The CLI scores measure all of
`SubcommandEntrypoints.scala` — **4,701 lines holding all 11 entrypoints** — so a single
spec's diff is a small fraction of the file it is scored against. Splitting the file per
entrypoint would make the threshold meaningful.

---

## 10. Retiring the predecessor

Seven `.predecessor.bak` files remain. The retirement criterion — held green across a full
change cycle — is **not met**: three tests are red at HEAD (B1–B3), and continuous
integration has never run (A2).

---

## 11. Proposed ordering

1. **Make it run anywhere (A).** Fix both JAR launchers with the verified symlink approach;
   add a JAR-only subprocess conformance run; push the branch and let `verify.yml` execute
   for real.
2. **Clear the red (B).** Make every subprocess test hermetic (the B1 session leak);
   add the archive-aware fixture resolver and the literal-path lint; introduce the
   sanctioned-edit list so the immutability guard can go green.
3. **Finish the swaps (C).** Swap both installers; implement `metals start` or remove it
   from the surface; retire `openspec-graph.py` to `.predecessor.bak` and document
   `probatio graph`; classify register entries by live path.
4. **Fix the oracle (G).** Supply tool names in the lock fixtures; root-cause the six
   `chain-state` failures; confirm Devin's payload; decide the structural-test question.
5. **Port what is portable (D).** `registry-check` now; schedule the scalameta spike for
   the concept scanner.
6. **Finish the rename (E).** The four environment variables, the state directory, the pi
   adapter; then the directory, with a resolution alias.
7. **Retire the prerequisites (F)** — a deliberate change outside the feature freeze.
8. **Retire the `.bak` files** once everything above has held green across a full cycle,
   continuous integration included.

---

*Measured 2026-09-25 at `069e428`. Both worktrees were removed after their runs; the
working tree was left as found.*
