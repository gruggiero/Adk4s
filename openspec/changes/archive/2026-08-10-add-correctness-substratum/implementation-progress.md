# Implementation Progress

**SINGLE SOURCE OF TRUTH for progress.** `tasks.md` is derived output,
regenerated from this file at every checkpoint (apply Step 13) — never
hand-maintained in parallel.

Change: `add-correctness-substratum` · 6 specs · risk **High** · all gate tiers
`separate` (12 human gates + 6 checkpoints).

Seeded unchecked 2026-08-08.

## Sequence

- [x] 1. `specs/correctness-invariant/spec.md`
- [x] 2. `specs/evidence-ledger/spec.md`
- [x] 3. `specs/chain-state/spec.md`
- [ ] 4. `specs/gate-payload/spec.md`
- [ ] 5. `specs/checkpoint-from-ledger/spec.md`
- [ ] 6. `specs/hook-tiers/spec.md`

## Per-spec state

<!-- Baseline SHA is recorded at each spec's Step 0 on a clean tree; every diff
     that spec's rings compute is against it. Ring cells: ✅ green, ❌ failing,
     ⏭️ skipped-with-impact, — not applicable, blank not yet run. -->

### 1. correctness-invariant

**Step 0 complete 2026-08-08** — tree clean; baseline recorded; registry gate
passed (`registry-check: OK`, 740 tokens / 15 spec refs); inventory snapshot
taken at `inventory-snapshots/correctness-invariant-before.md`.
Concept check: this spec's "Concepts Used" tables declare **none** — no Scala
type is read or written, so there is nothing to import and nothing to avoid
recreating. Proof Obligations verified complete at spec-lint (F4/F6/F7/F8
clean). Public-type-change impact scan: **not applicable** — no public type is
aliased, widened, or has its variant set changed. No MUST-CONFIRM items.

| Field | Value |
|-------|-------|
| Baseline SHA | `291eb22a7434f550b028f13b3be067e24ae7af49` |
| Gate 1 — typed contract (waiver) | ☑ **approved** 2026-08-08 |
| Gate 2 — test oracle + polarity | ☑ **approved** 2026-08-08 (17 RED / 3 green-by-design) |
| R0 parse / `bash -n` | ✅ YAML parses; `openspec validate --strict` valid; all changed scripts `bash -n` clean |
| R1 shellcheck + shfmt | ✅ shellcheck 0 findings across 11 `.sh` + 1 `.bash`; shfmt `-i 2 -ci` clean on the added file |
| R3 bats | ✅ **24/24**; polarity confirmed (17 red→green, 3 green-by-design held) |
| R8 adversarial | ✅ **`fresh-context: YES`** — subagent pass 2026-08-08. Found 3 PARTIAL + 1 failed obligation after the author-context pass reported clean; 6 of 9 fixes applied, 3 referred to the human gate below |
| Concept delta | ✅ **EMPTY** — before/after scanner snapshots byte-identical |
| Build-dependency delta | ✅ `build.sbt`/`project/` empty; **host prerequisites reported**: `jq`, `shellcheck`, `bats`, `shfmt` |
| Commit | `723f22f` |

**Ring 8 finding (FAIL → fixed).** R1 requires the definition be stated *"so
that it is carried into every artifact instruction"*. It was written into the
schema `description`, and all nine presence tests passed — but the CLI does
**not** emit `description`:
`openspec instructions apply | grep -c "CLAIM OUTRUN"` returned **0**.
The definition was present and unreachable: documentation, not a substratum,
and the tests could not see the difference because they grepped the file
directly. Fixed by adding the invariant to the **apply instruction**, which is
emitted (verified: 1 match, and 1 match for an unrelated change, so it is not
hard-coded to this one). Four REACHABILITY tests added — they assert the text
reaches an agent rather than that it exists on disk.

**Ring 8 finding (open, for human decision).** The property
"no workflow document asserts the superseded rule" matches only jq-phrasings.
A document asserting the old rule *without naming jq* would pass — e.g.
`templates/concept-registry/README.md:68` says registry-check is
"dependency-free — bash + git grep". That statement is **accurate about that
one script**, so it is not a violation, but the property's blind spot is real.
Widening it changes an approved oracle, so it is referred to the human rather
than done silently.

**Ring 8 fresh-context pass (2026-08-08) — the mandate earned its keep.**
The author-context pass found 1 FAIL and reported the spec complete. A
fresh-context subagent, given only the spec, the approved contract and
`git diff 291eb22 HEAD`, returned **1 PASS / 3 PARTIAL / 0 FAIL plus a failed
Proof Obligation**. Findings and disposition:

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | **R1 PARTIAL** — the requirement says "carried into EVERY artifact instruction"; the author fix covered `apply` only, which is not an artifact. 0 of 8 artifact instructions carried it, and the REACHABILITY tests probed only the one instruction that had been fixed | **FIXED** — prelude added to all 8; property now enumerates artifact ids from `schema.yaml` at test time. Negative-tested: removing the prelude from one artifact fails the property and names it |
| 2 | **R1** — 5 tests hard-coded `--change add-correctness-substratum`, so the suite would go permanently red once the change is archived, in a suite CI runs every pipeline | **FIXED** — `first_active_change()` discovers it at test time; skips honestly when none exists |
| 3 | **R3 PARTIAL** — Property 1 **would not have caught the text it was written for**: the baseline `hooks/README.md` read ``no `jq` `` (backticked) and the matcher missed it. An implementation that left the old paragraph verbatim passed all 24 tests | **REFERRED** — widening the matcher changes an approved oracle (gate item 7) |
| 4 | **R3** — Property 2's domain hard-codes the six tool names in its `awk` selector, contradicting its Generator strategy ("read from the table at test time… so adding a prerequisite without a justification fails"). Verified: a 7th row selects 0 rows | **REFERRED** — gate item 8 |
| 5 | **R3** — `docs/09-tooling.html` still stated the superseded rule **as a Rule**; `docs/04-concepts.html`, `schema.yaml:212` and `concept-registry/README.md` repeated it; two docs stamped v11 | **FIXED** — all amended; tree sweep now shows only the two deliberate "superseded" notes |
| 6 | **R3** — `openspec` is required by 5 tests, declared in no prerequisite table and installed by no CI template: **the shipped gate failed on its own introducing commit** | **FIXED** — added to the prerequisite table with its consumer, to all 3 templates, and to each verify loop |
| 7 | **R4, Obligation #7 FAIL** — the retest rule reached no artifact instruction, including the two that read living documents | **FIXED** — the prelude carries it to all 8 (same fix as #1) |
| 8 | **R4** — `capability-profile.md` carried two records this very commit invalidated (the shellcheck baseline; "CI runs only registry-check.sh"), relied upon and not replaced, in a file the commit edited | **FIXED** — both re-established with date and mechanism |
| 9 | **danger-scan reported as clean was VACUOUS** — it filters `*.scala` + `/src/main/`, so it is structurally blind to a shell-only change | **FIXED** — recorded as N/A with the reason, in the profile |
| 10 | Three **misattributed citations** shipped into permanent files, plus the exit-code convention stated as present fact when only 2 of 11 scripts follow it | **FIXED** — citations corrected; convention restated prescriptively |
| 11 | Obligation #6 discharged by a weaker proxy (one file, two substrings, no entry enumeration) | **REFERRED** — gate item 9 |

Every measurement recorded by the author pass was independently **verified
true** by the reviewer (shfmt 1987/690/515/837; "~276 lines" exactly 276; the
`description`-not-emitted claim; both shellcheck justifications; the empty
concept delta; cross-change reachability).

**Oracle-tampering check limitation, recorded not glossed:** the reviewer noted
`correctness-invariant.bats` is introduced whole in one commit, so the
Step-2-approved version has no committed form to diff against. Absence of
detectable tampering here is not evidence of none.

### 2. evidence-ledger

**Step 0 complete 2026-08-08** — tree clean; baseline recorded; registry gate
passed (740 tokens / 15 spec refs); inventory snapshot at
`inventory-snapshots/evidence-ledger-before.md`. Concept check: "Concepts Used
(from inventory)" declares **none** — no Scala type read or written, nothing to
import or avoid recreating. Zero MUST-CONFIRM items. Public-type-change impact
scan: **not applicable** — no public type aliased, widened, or given new
variants. Proof Obligations verified complete at spec-lint (F4/F6/F7/F8 clean),
including the three added when design Decision 1 changed the record format.

**Ring 8 will run in a FRESH-CONTEXT SUBAGENT** — standing authorization given
2026-08-08 after the spec 1 result (author context found 1 finding, fresh
context found 11).

| Field | Value |
|-------|-------|
| Baseline SHA | `723f22fe327fa1600e32d3960580fd807236ee83` |
| Gate 1 — typed contract (full: ledger record contract) | ☑ **approved** 2026-08-08 |
| Gate 2 — test oracle + polarity | ☑ **approved** (21 RED / 2 green-by-design) |
| R0 | ✅ `bash -n`; contract executes; 15 rejection paths each named |
| R1 | ✅ shellcheck 0 findings; `shfmt -i 2 -ci` clean on added files |
| R2 | ✅ prerequisite rule (bash/git/jq only, no JVM, no network); runs identically from any cwd |
| R3 | ✅ **52/52** across both suites; polarity confirmed (21 red→green, 2 green-by-design held) |
| **R4** | ✅ committed `evidence-ledger-v1.jsonl` (5 rows) re-parses; unknown `v` → undetermined; nothing regenerates it |
| R8 | ✅ **`fresh-context: YES`** after fixing **3 FAIL + 1 PARTIAL** — see below |
| Concept delta | ✅ **empty** — before/after snapshots differ only in the scan-date comment |
| Build-dependency delta | ✅ `build.sbt`/`project/` none; no new host prerequisite beyond spec 1's set |
| Commit | `00d3de1` |

**Typed contract note.** For a shell spec the analogue of "genuinely compiled"
is genuinely executable: `scanner/ledger-record-contract.jq` IS the contract
and its checker, shared by the implementation and the oracle so the two cannot
drift by disagreeing about what a row is. Running it at Gate 1 found a real
bug — `rings | index(.ring)` rebinds `.` to the array, so the conformant record
was being rejected. Prose would have shipped that.

**Ring 8 fresh-context pass — 3 FAIL, 1 PARTIAL, all fixed.** The code passed
52/52 tests, shellcheck, shfmt and every gate, and was broken in six ways:

| Finding | Fix |
|---|---|
| **FAIL** append onto a file with no trailing newline **concatenated onto the prior row**, corrupting it and making the ledger permanently unreadable. The reader explicitly supports such files, so this is a supported input shape | writer now owns the record separator (`tail -c1` check) |
| **FAIL** an unreadable ledger (`chmod 000`) read as **exit 0, zero rows** — the unchecked `done <"$FILE"` redirect. A directory gave exit 1 (a finding, not undetermined). A newline-only file read clean | explicit `-f`/`-r` guards → undetermined; a blank line is now an unparseable record, not skipped |
| **FAIL** `*) shift ;;` silently swallowed unknown flags, so a typo'd `--baselin` **disabled the staleness filter** and returned superseded evidence with exit 0 | unrecognised arguments are refused as findings |
| **PARTIAL** a dangling flag **hung forever** — `shift 2` does not shift with one argument left, so no exit code was ever returned. In a hook that strands the session | each flag requires a value; a missing one is a finding |
| **PARTIAL** `--exit 007` stored as `7`; `--exit 99999999999999999999` stored as `1e+20` | strict integer form; the stored value is the supplied one |
| the `update` refusal never fired — `--row` was rejected as unknown first, so the caller never learned the ledger is append-only | subcommand refusal moved ahead of argument parsing |

It also found **4 enumerated domains narrower than their declared Generator
strategies**, two of them exactly where the code broke: P1 varied ledger *size*
but never *shape*, so the no-trailing-newline case was outside the domain; P4
held only content corruptions of a readable file; the empty-string value
declared by both P1 and P2 was absent; P4's "file of only whitespace" was
implemented as a whitespace *line* appended to a good ledger. All widened to
what the spec declares — a correction of my Step 2 error, not a change to the
approved spec.

Two of my own bugs surfaced while fixing: `${OBL:-default}` in the test helper
substituted for **empty as well as unset**, so the empty-value case never
reached the writer; and a `'"'"'`-style quoting sequence leaked literally from
a heredoc into the argument-error message.

**Corrected false claim.** `design.md` states *"there is no read path that
ignores baseline"*. That was false — `--baseline` is optional on read, so the
default path ignores it. The typo hole is fixed; whether to make `--baseline`
mandatory is a CLI-contract change and is a gate item, not applied silently.

**Deferred to the gate (would change the approved oracle):** the spec's Then is
"the row is **reported as stale** and the obligation is reported as not
discharged". Only the second half is implemented — a stale row is excluded but
not named. Reporting it on stderr violates an approved assertion, because bats
merges stderr into `$output` and the approved test asserts the row is absent.

### 3. chain-state

**Step 0 complete 2026-08-09** — tree clean; baseline `00d3de1aa49141277dc4855a353f009fc7cc941a`;
registry gate passed (740 tokens / 15 spec refs); inventory snapshot at
`inventory-snapshots/chain-state-before.md`. Concepts Used declares none.
Zero MUST-CONFIRM items. Proof Obligations verified complete at spec-lint.

**Ring 8 fresh-context pass — 4 FAIL + 5 PARTIAL, all fixed or explicitly
scoped.** The code passed 17/17 tests, shellcheck, shfmt, and every gate; the
reviewer found it broken in nine ways, all reproduced with real commands
against the real script, not merely inferred:

| Finding | Fix |
|---|---|
| **FAIL (critical)** a title spec-lint's own F7 calls **bound**, for which this tool's narrow "Requirement: \<title\>" matcher found ZERO rows (an ordinal Source, a case-different Source, a prefix-title collision), defaulted to `is_resolved=1`/`is_discharged=1` **never lowered by an empty loop** — reported fully discharged on literally no evidence, exit 0. The exact defect this project targets, found inside the tool built to prevent it | new reason `unattributable`; such a title can never be counted resolved or discharged; added to the contract's reason enum |
| **FAIL** `grep -qF` (no `-x`) on the unbound lookup is a **substring** match — a title that is a textual prefix of another title falsely inherited its verdict, and chain-state reported it unbound when spec-lint did not | `-x` anchors the whole line |
| **FAIL** the same substring bug on the F9-line lookup — line 29 matched line 290, misattributing an unrelated row's unresolved verdict to a fully-resolving requirement 260 lines away | `-x` anchors the line number exactly |
| **FAIL** the header-row exclusion was a *content* test (`!~ /^\| *Obligation/`); a genuine data row whose Obligation-cell text happens to start with "Obligation" was silently dropped from this script's own table parsing | header excluded **structurally** — the row immediately preceding the `\|---\|` separator, not by matching its text |
| **PARTIAL** `die_undetermined` was called before `CHANGE`/`BASELINE` were declared; under `set -u` this crashed with "unbound variable" — an undetermined condition surfacing as a raw shell error, no report at all | variable declarations moved above every call site |
| **PARTIAL** a `specs/` subdirectory chain-state could read but spec-lint's own `find` could not (or the reverse) silently under-counted `total`, since spec-lint's `set -e` makes such a crash exit 1 — indistinguishable from ordinary FAIL findings | require spec-lint's completion summary line (or its two literal "nothing to lint" messages, each independently re-verified real); cross-check its reported file count against this script's own enumeration |
| **PARTIAL** the undetermined report never validated against its own contract, so "the single statement of the report shape" was false for that branch | contract gained an explicit `undetermined` branch |
| **PARTIAL** the contract accepted duplicate `(spec, requirement)` entries and could not detect a real **misclassification** (only shape violations) — bound/resolved/discharged and `unresolved` were built by the same pass, so they agreed by construction regardless of correctness | added a duplicate-pair rejection and a **counts↔reasons cross-check** recounting `unresolved` by reason and requiring exact agreement — this is what makes the self-check able to catch a future regression of the F1 class, not just a shape defect |
| swallowed jq failures and an unreachable-but-wrong-direction fallthrough (`[ "" -gt 0 ]` silently taking the "clean" branch) | explicit exit-code capture on report assembly; numeric-format guards before the final branch |

Every finding was independently **re-verified fixed** with the reviewer's own
adversarial commands, run directly against the patched script — not merely
via the (also-updated) bats suite. One test I initially wrote against F1's
finding was itself wrong (asserting a case that can never occur, since
spec-lint's F7 *and* F9 share the identical content-based header exclusion,
so a single-row "Obligation"-prefixed fixture is invisible to spec-lint too
— chain-state correctly agrees with it). Rewritten to test the real,
observable case: a *second* mapped row silently dropped from discharge-
checking even though the *first* row's presence already made the title
bound.

Two of my own bugs surfaced while fixing: a jq expression
(`.reasons | index(X) or (.reasons | index(Y))`) relies on `|` binding
*looser* than `or` in a way that re-pipes the right-hand side through the
already-piped `.` — caught immediately by testing the contract directly
rather than trusting it read correctly. And an earlier manual verification of
mine (`spec-lint.sh ... | tail -5; echo $?`) reported `tail`'s exit code, not
spec-lint's — leading me to initially believe "no specs/ directory at all"
exits 0, when it is actually spec-lint's own **exit 2** (undetermined)
signal, correctly distinct from "specs/ exists but is empty" (exit 0). One
test's expectation was corrected, not the code.

Manual sanity check against the real change (`add-correctness-substratum`,
fresh empty ledger): `total:28, bound:28, resolved:28, discharged:0,
unattributable:0, unmapped:0, exit:1` — unchanged by every fix above,
confirming none of the nine defects were live against this project's own
specs (all 28 requirements map cleanly via the preferred form).

| Field | Value |
|-------|-------|
| Baseline SHA | `00d3de1aa49141277dc4855a353f009fc7cc941a` |
| Gate 1 — typed contract (full) | ☑ **approved** 2026-08-09 |
| Gate 2 — test oracle + polarity | ☑ **approved** (16 RED / 1 skip→pass) |
| R0 | ✅ `bash -n`; contract executes cleanly |
| R1 | ✅ shellcheck 0/13 shell files; `shfmt -i 2 -ci` clean on added files |
| R2 | ✅ prerequisite rule; no `git ls-files` in chain-state.sh (verified by its own test) |
| R3 | ✅ **24/24** (chain-state.bats); **76/76** across the full suite; polarity confirmed |
| R4 | — not applicable (no persisted format of its own; consumes ledger + spec-lint formats) |
| R8 | ✅ **`fresh-context: YES`** — 4 FAIL + 5 PARTIAL found and fixed, all independently re-verified |
| Concept delta | ✅ **empty** — before/after snapshots byte-identical |
| Build-dependency delta | ✅ none |
| Commit | `792dcb8` |

### 4. gate-payload

**Step 0 complete** — baseline `792dcb8` (HEAD after spec 3's commit); tree
clean; inventory snapshot at `inventory-snapshots/gate-payload-before.md`.
Concepts Used declares none — no Scala type read or written.

| Field | Value |
|-------|-------|
| Baseline SHA | `792dcb8` |
| Gate 1 — typed contract (full: CLI + response-shape contract) | ☑ **approved** |
| Gate 2 — test oracle + polarity | ☑ **approved** (4 properties + 13 scenarios, 17 tests as designed; 19 by the time implementation reached this session). Grew to **21** in this session: +1 fixing test 17's cross-format comparison, which was colliding two calls into one session via PPID fallback (gave each side of the comparison its own `--session`) and adding a direct test for `CLAUDE_CODE_SESSION_ID`'s priority over the generic override; +1 for the worktree heartbeat bug below. Both additions independently RED-confirmed (against a reverted copy for the worktree fix; by direct reproduction for the session-collision fix) before being folded in |
| R0 | ✅ `bash -n`; `gate-hookjson-contract.jq` executes and passes against real `gate.sh` output |
| R1 | ✅ shellcheck 0 findings; `shfmt -i 2 -ci` clean on `gate.sh` after reformat |
| R2 | ✅ prerequisite rule holds (no JVM/network added); adapter no-logic rule holds — grepped adapters for business-logic terms (`fingerprint`/`unresolved`/`chain-state`/`discharged`), zero hits, all logic stays in `gate.sh` |
| R3 | ✅ **24/24** (post-Ring-8); polarity confirmed |
| **R4** | ✅ hook-json envelope shape unchanged for existing consumers; cross-format property asserts identical facts in every `--format` |
| R8 | ✅ **`fresh-context: YES`** — 2 FAIL + 1 PARTIAL, all fixed, plus 3 NOTEs acted on — see below |
| Harness-seam manual verification (2 obligations) | ✅ **pi**: re-ran `pi -e` end-to-end after the rewrite — `customType:"verified-scala3-context"` lands before the model call (an unrelated LLM connection error followed, irrelevant to the adapter). ✅ **Claude Code**: `UserPromptSubmit` block installed into this repo's own local `.claude/settings.json` (git-ignored via `.git/info/exclude`, not a tracked change) and invoked for real against this worktree — `--check-installed` flipped from `false` to `true` with `event:"prompt-submit"` after a live call. **Devin**: not first-hand verified (no runtime available) — documented as such in `hooks/README.md`, same honesty pattern already used there |
| Concept delta | ✅ **empty** — before/after snapshots byte-identical |
| Build-dependency delta | ✅ none — `build.sbt`/`project/` untouched |
| Commit | `a5d6113` |

**Real bug found during manual harness-seam verification, not by the oracle.**
Checking `--check-installed` against this repository's own live worktree
returned `installed:false` immediately after a call that should have
recorded a heartbeat. Cause: `gate.sh` gated its state directory on
`[ -d "$REPO/.git" ]`, but in a git **worktree** — this project's own dev
environment — `.git` is a plain pointer *file*, not a directory, so the test
was always false and heartbeat/suppression state was silently never
persisted in any worktree. None of the 20 originally-passing bats tests
caught this because every fixture uses `git init`, which always produces a
directory; the worktree shape was simply never in the test domain. Fixed by
resolving the state dir with `git rev-parse --absolute-git-dir` (handles
ordinary repo, worktree, and submodule alike) instead of assuming the path.
Confirmed RED against a reverted copy of the fix (worktree-secondary-checkout
assertion failed as expected), then GREEN against the real fix. A dedicated
scenario test (`tests/gate-payload.bats`, "the heartbeat and installation
check work from inside a git worktree, not only a plain checkout") now covers
both shapes so this cannot regress silently again.

**Ring 8 fresh-context pass — 2 FAIL + 1 PARTIAL, all fixed; 3 NOTEs acted
on.** The reviewer read only spec.md, the approved jq contract, and the
staged diff — no access to this progress file — then independently executed
`gate.sh` against constructed fixtures to confirm each hypothesis with a real
command, per this project's own standard.

| Finding | Fix |
|---|---|
| **FAIL** session-id sanitization (`tr -c 'A-Za-z0-9._-' '_'`) is **lossy** — every disallowed character collapses to the same `_`, so `--session 'abc!def'` and `--session 'abc?def'` both sanitize to `abc_def` and collide onto the same suppression-state file. Reproduced live: session `abc?def`'s first-ever call came back empty, wrongly suppressed as a repeat of an unrelated session. `--session` is adapter-supplied, not restricted to a safe character set | switched to a lossless encoding: `jq -Rr '@base64' \| tr '+/=' '-_.'` — base64 (RFC 4648) is total and reversible, and substituting its three non-filename-safe characters for three that never otherwise appear in base64 output keeps it lossless while filename-safe (the "base64url" transform). A `tr`-only fallback remains if `jq` is unexpectedly absent at runtime, intentionally still lossy — degrading is preferable to failing a hook that must never fail |
| **FAIL** the jq contract's own comment claimed a suppressed/no-op call "must still produce a VALID hook-json envelope (an empty one)" — false; `gate.sh` emits **zero bytes**, not an envelope, on every no-op path in every format. Separately, `jq -e -f contract.jq` is **vacuously true on empty input** (jq never evaluates the logic when stdin is empty), so the bats helper built to enforce this contract would have silently "passed" a malformed envelope too, had one ever been emitted. Nothing in the suite exercised {hook-json format} × {suppressed} × {contract validation} at all | corrected the contract's comment to state the true behaviour and the vacuous-truth gotcha explicitly; `conforms_hookjson` now refuses empty input outright (forcing callers to `[ -z "$output" ]` instead); added a dedicated scenario test asserting a suppressed hook-json call is exactly empty |
| **PARTIAL** the "is this a genuine chain-state measurement" gate checked only `has("total")`, true even when `total` is present-but-`null`. A report combining `undetermined:true` with `total:null` (a shape the real, approved chain-state.sh never emits — its two report shapes are disjoint — but which a test stub or a misbehaving override is not otherwise prevented from producing) rendered as a clean `unresolved 0`, indistinguishable from a genuinely resolved change | tightened to `(.total \| type) == "number"` — the direct check for what this code actually needs, independent of any assumption about chain-state.sh's exit-code/undetermined-field relationship |
| NOTE: the Devin adapter passes no `--session` (no verified per-conversation env var documented for Devin), falling to the unverified PPID inference — a real, self-acknowledged gap, not reproducible without a Devin runtime | documented explicitly in `hooks/README.md`'s adapter table rather than left implicit |
| NOTE: the no-jq escaping fallback (`awk '{printf "%s\\n", $0}'`) appended an extra trailing `\n` inside `additionalContext` that the primary jq path does not produce | replaced with a sed-slurp form (`sed ':a;N;$!ba;s/\n/\\n/g'`) that matches jq's output exactly; verified directly against both paths side by side |
| NOTE: `readiness is never reported without also reporting the unresolved count` asserted only that `"5"` appeared *somewhere* in the payload — would pass even if a stray `"5"` came from an unrelated place and the count were never actually attached to the "unresolved" label | strengthened to assert `"unresolved 5"` as a paired string |

One NOTE was assessed and deliberately left as a manual-review finding, not a
code change: the tension between "facts survive compaction" (Requirement 2's
rationale) and suppression being keyed purely on whether facts changed (so a
long, unchanging session may never see the payload reappear once the
original injection is compacted out of context). The spec's own Proof
Obligations table already scopes this to manual/Ring 8 review — "compaction
is harness behaviour and cannot be induced from a shell test" — so this is
recorded as the discharge of that obligation, not deferred further: the
per-turn invocation is real and tested; the harness's decision about what
survives its own compaction is outside what a hook can control or shell-test.

Every FAIL/PARTIAL fix was independently **RED-confirmed** by reverting just
that fix in a scratch copy and re-running the new regression test against it
(session-sanitizer collision reproduced; chain-state undetermined+null-total
rendered as clean, exactly as the reviewer found), then GREEN-confirmed
against the real fix — both via the scratch reproduction and the full suite.

### 5. checkpoint-from-ledger

**Step 0 complete** — baseline `319090b` (HEAD after spec 4's commit); tree
clean; inventory snapshot at `inventory-snapshots/checkpoint-from-ledger-before.md`.
Concepts Used declares none — no Scala type read or written.

| Field | Value |
|-------|-------|
| Baseline SHA | `319090b` |
| Gate 1 — typed contract (`checkpoint.sh report`/`regenerate-tasks` CLI, exit-code convention) | ☑ **approved** |
| Gate 2 — test oracle + polarity | ☑ **approved** — 15/15 RED before implementation existed (no green-by-design; nothing pre-existing to test) |
| R0 | ✅ `bash -n` clean |
| R1 | ✅ shellcheck 0 findings; `shfmt -i 2 -ci` clean |
| R2 | ✅ prerequisite rule holds (no JVM/network); `checkpoint.sh` never re-implements row filtering (delegates to `ledger.sh read`) or chain-state classification (passthrough `--chain-state-json`) |
| R3 | ✅ **19/19** (post-Ring-8); polarity confirmed. Cross-module regression: full suite (all 5 spec `.bats` files) **119/119** |
| R8 | ✅ **`fresh-context: YES`** — 3 FAIL + 1 PARTIAL, all fixed — see below |
| Concept delta | ✅ **empty** — before/after snapshots byte-identical |
| Build-dependency delta | ✅ none — `build.sbt`/`project/` untouched |
| Commit | `c20d071` |

**Revised Gate 1 decision, found while implementing.** The Gate 1 proposal
said `ledger.sh read`'s `--baseline` would become *required*, to fix
design.md's pre-existing false claim ("there is no read path that ignores
baseline") and match the earlier "make `--baseline` required" instruction
given for `chain-state.sh`'s own CLI. Checking before applying it: nine
already-approved, already-committed `evidence-ledger.bats` scenarios call
`read` WITHOUT `--baseline` on purpose (reading across specs, malformed-row
detection, absent-ledger handling — none of them discharge anything from the
result). Making the flag mandatory at `ledger.sh`'s own layer would have
forced cosmetic changes onto spec 2's already-approved oracle for no safety
benefit to those call sites. Reversed: `--baseline` stays optional in
`ledger.sh` itself; `checkpoint.sh` always supplies it, the same layered
discipline `chain-state.sh` already uses (its own comment: *"`--baseline` is
REQUIRED (not optional, unlike ledger.sh's read)"*). design.md's false claim
is corrected instead of made true by force — see its Type Strategy table.

**Ring 8 fresh-context pass — 3 FAIL + 1 PARTIAL, all fixed.** The code
passed 15/15 tests and every gate; the reviewer, working from spec.md and the
diff only, found it broken in four ways, all reproduced with real commands
against the real script:

| Finding | Fix |
|---|---|
| **FAIL (critical)** a ledger row honestly recording a **failed** command (`--exit 1`) was reported `status:"green"`, citing that same failing command — the assembly logic never inspected `.exit`, even though it copied that field into its own output object two lines later. A ring that regressed after an earlier green run could make the whole checkpoint report readiness | gated on `.exit == 0`; a non-zero-exit row now reports `"failed"` (distinct from `"unevidenced"` — a human should see the difference between "never ran" and "ran and broke") |
| **FAIL (critical)** chain-state.sh's own genuine **undetermined** report (`total:null, undetermined:true, unresolved:[]`) passed `has("total")` — the key exists, its value is `null` — and was read as "unresolved 0", letting the checkpoint exit 0 with the correctness definition completely unknown. Exactly the defect class this project exists to remove, reproduced by chaining the real `chain-state.sh` into `checkpoint.sh` end-to-end | validation tightened to `(.total \| type) == "number" and (.undetermined // false) == false` |
| **FAIL** `regenerate-tasks`'s tracker lookup (`grep -F "N<TAB>"`) is a **substring** match — spec `"1"` matched the trailing `...1` of spec `"21"`'s row, so which spec's completeness applied depended on tracker file ORDER, not on the requested number, in both directions (a pending spec wrongly checked, a complete spec wrongly left unchecked). The identical defect class chain-state.sh already fixed with `-x` anchoring (F2/F3), reintroduced here uncaught — the 15-test suite only ever used non-colliding numbers "1" and "2" | replaced with an exact field match: `awk -F'\t' -v s="$cur_spec" '$1==s{print $2; exit}'` |
| **PARTIAL** a duplicate ring name (`--rings R0,R0,R1`) passed validation and produced two entries for R0 in the output, both citing the same row — invisible to the self-check, which only compared COUNTS (3 requested, 3 assembled — still equal) | `--rings` de-duplicated once, up front, so every later stage can assume it names a SET |
| the self-check itself was **structurally vacuous** — the assembly loop always appends exactly one row per requested ring, so the count it verified could never actually diverge; it protected against an unreachable bug while the reachable one (same ring twice) sailed past with matching counts | rewritten to compare the SET of reported ring names against the SET of requested ones, which the duplicate-rings case now genuinely exercises |
| (lower priority, schema.yaml) Step 13's instructions ran `regenerate-tasks` — which reads the spec's own Commit field to decide completeness — *before* the commit recording that hash existed | reordered: `report` (needs only ledger evidence + baseline) runs before commit; `regenerate-tasks` runs after, once the real hash is recorded, as a small separate follow-up commit — matching the two-commit pattern already used for spec 4's tracker update |

Every finding was independently **RED-confirmed**: each fix was reverted in
a scratch copy (kept alongside the real `ledger.sh` so `SELF_DIR`-relative
sibling lookup still resolved) and the corresponding new regression test
re-run against it, reproducing the exact reviewer-reported behaviour, before
being folded back in and GREEN-confirmed via the full suite.

**Dogfooded on this spec's own checkpoint.** `evidence-ledger.jsonl` for this
change did not exist before this spec — specs 1-4's Ring 0-4/8 verification
was recorded as prose in this tracker, never as ledger rows, since the
ledger only started existing at spec 2 and nothing before this spec actually
called `ledger.sh append` for its own bookkeeping. Five rows were appended
now, for this spec's own R0/R1/R2/R3/R8 checks, and `checkpoint.sh report`
was run for real against them (output below) — genuine end-to-end use of the
tool for its own purpose, not a synthetic fixture. `chain_state` correctly
reports 28 total requirements, 0 discharged: this is an HONEST reflection of
the ledger being newly adopted, not a defect — the other 27 requirements'
underlying work was done and verified across specs 1-4, just never logged in
this format. Backfilling that history is out of scope for this spec (it
built the tool; it did not retroactively re-instrument four already-shipped,
already-approved specs) and is not attempted here.

```
checkpoint: add-correctness-substratum/checkpoint-from-ledger @ 319090b
  R0: green (bash -n openspec/schemas/verified-scala3/scanner/checkpoint.sh)
  R1: green (shellcheck scanner/checkpoint.sh && shfmt -i 2 -ci -d scanner/checkpoint.sh)
  R2: green (grep -nE 'curl |wget |scala |sbt |java |http://|https://' scanner/checkpoint.sh)
  R3: green (bats openspec/schemas/verified-scala3/tests/checkpoint-from-ledger.bats)
  R8: green (fresh-context Agent subagent review (spec+diff only) + reproduction of each finding against reverted scratch copies)
  chain state: total 28  bound 28  resolved 28  discharged 0  unresolved 28
    [... 28 undischarged requirements, spanning all 6 specs of this change — see full output ...]
```

**Real pre-existing data gap found by running `regenerate-tasks` for real,
not by the oracle.** Running it against this project's own tracker/tasks.md
initially left specs 1-3 UNCHECKED after regeneration — even though all
three are genuinely complete and committed. Cause: spec 1's tracker entry
recorded its Commit field as `✅ (see checkpoint)` (a placeholder, not a
hash — written before this spec's tool existed to depend on it), and specs
2-3's tracker entries had no Commit row at all. `regenerate-tasks` correctly
and honestly reported them incomplete by its own stated rule; the gap was in
the tracker's historical data, not in the tool. Fixed by recording each
spec's actual final commit (cross-checked against `git log` and against the
NEXT spec's own recorded Baseline SHA, which must equal it): spec 1 →
`723f22f` (its last of three commits — implementation, Ring 8 fixes,
approved oracle strengthening), spec 2 → `00d3de1`, spec 3 → `792dcb8`.
Re-running `regenerate-tasks` after the fix correctly checks specs 1-5 and
leaves spec 6 unchecked.

### 6. hook-tiers

**Step 0 complete** — baseline `e20f805` (HEAD after spec 5's commit); tree
clean. Concepts Used declares none — no Scala type read or written.

| Field | Value |
|-------|-------|
| Baseline SHA | `e20f805` |
| Gate 1 — typed contract (full: `--event post-edit`/`completion` CLI + refusal contract) | ☑ **approved** |
| Gate 2 — test oracle + polarity | ☑ **approved** — 22 tests; 12/22 directly RED before implementation, remaining 10 individually weak (negative-only / proceed-path) but each covered rigorously by one of two cross-product property tests, which were RED |
| R0 | ✅ `bash -n` clean |
| R1 | ✅ shellcheck 0 findings; `shfmt -i 2 -ci` clean |
| R2 | ✅ prerequisite rule holds; post-edit tier delegates to `spec-lint.sh`/`danger-scan.sh` unchanged, never reimplements dangerous-pattern or lint logic |
| R3 | ✅ **27/27** (post-Ring-8); polarity confirmed. Cross-module regression: full suite (all 6 spec `.bats` files) **146/146** |
| R8 | ✅ **`fresh-context: YES`** — 4 FAIL + 1 PARTIAL, all fixed — see below |
| Harness-seam manual verification (2 obligations) | ✅ **pi**: post-edit (`tool_result`) verified against the installed `@earendil-works/pi-coding-agent` package's own `docs/extensions.md` and `protected-paths.ts` example (event name, tool-name filter, `event.input.path` field all confirmed, not assumed); extension loads and runs under `pi -e` without error. Completion gate **verified absent** from pi's extension API — not wired, documented why. ✅ **Claude Code `PostToolUse`**: end-to-end — installed live, then confirmed firing for real on this spec's own implementation: `--check-installed` read `event:"post-edit"` immediately after a genuine `Edit` tool call, with no manual gate.sh invocation in between. **Claude Code `Stop`**: installed live with explicit human approval (the one genuinely blocking mechanism in this schema); see the finding below for what happened when it actually fired |
| Concept delta | ✅ **empty** |
| Build-dependency delta | ✅ none |
| Commit | `7631e4a` |

**Real-repo finding while verifying the completion gate live, before relying
on the installed hook to demonstrate it.** Ran `gate.sh --event completion
--turn-text "Spec 6/6 complete: hook-tiers"` directly against this actual
repository (not a fixture) and it refused — correctly, but for a reason
worth stating plainly: chain state for the **unrelated** active change
`add-harness-api-phase0` is itself undetermined (`chain-state.sh exit 2`
there), and the completion gate checks **every** active change, not only
the one the current work concerns. This means the live `Stop` hook just
installed will very likely refuse the checkpoint message below, citing a
change this session never touched. That is the design as specified — the
gate checks "chain state," and this repository's chain state genuinely
includes that change — but it is a real scope characteristic, not a
hypothetical: spec.md's own language ("chain state reports unresolved
requirements for **that spec**") arguably intends narrower, per-change
scoping than "every active change in the repo," and free-text
change-attribution would be an even more approximate heuristic than
completion-claim detection already is. Recorded as a disclosed scope
choice for Ring 8 to weigh, not silently narrowed or silently left broad.

**Ring 8 fresh-context pass — 4 FAIL + 1 PARTIAL, all fixed.** The code
passed 22/22 tests and every gate; the reviewer, working from spec.md and
the diff only, found the schema's one blocking mechanism broken in ways
that would have made it either silently inert or, worse, unboundedly
stuck — the two failure directions this spec exists specifically to
prevent, both found in the tool built to prevent them:

| Finding | Fix |
|---|---|
| **FAIL (critical)** the primary refusal path (`exit 1`, unresolved requirements — the ordinary, common case) is a **no-op on the real harness**. Verified against two independent primary sources: a live fetch of Claude Code's own hooks documentation ("Claude Code treats exit code 1 as a non-blocking error and proceeds with the action") and this project's OWN already-approved `docs/11-enforcement.html`, which lists exit 2 or `decision:block` JSON — never exit 1 — as the blocking signal for every harness in its table. The completion gate's most common trigger silently let every turn proceed unchallenged | for `--format hook-json`, blocking is now signalled ONLY via `{"decision":"block",...}` JSON on an exit-0 response — the one mechanism actually verified to work — uniformly for both refusal reasons; `--format text` keeps its own 0/1/2 scriptable contract unchanged |
| **FAIL (critical)** even the path that DOES block (`exit 2`, undetermined) delivered **no reason to the agent** — Claude Code reads the exit-2 reason from stderr, and every branch wrote exclusively to stdout | resolved as a side effect of the fix above: the undetermined branch also now signals via stdout JSON on exit 0 in hook-json format, sidestepping the stderr/stdout ambiguity entirely |
| **FAIL (critical)** the bounded-refusal guarantee is implemented **entirely** via a state-directory marker file; when `STATE_DIR` cannot be established (no `.git` yet, unwritable `.git` — both already-anticipated conditions for the injection tier, where the consequence was cosmetic), the "already refused" check can never become true and a refusal-worthy claim refuses **every** attempt with **no bound at all** — reproduced directly: 3 consecutive refusals with no `.git` present, never a single allow. Exactly the "blocking hook that is genuinely difficult to escape" failure this schema's own pre-existing documentation warns against | the event now fails OPEN (allows) when the bounding mechanism itself is unavailable — a blocking decision may only be made when the thing that bounds it is actually available |
| **FAIL** the transcript-extraction path (`tail -c 20000` applied AFTER extracting every string from the transcript tail) could **drop a genuine, exact, unparaphrased completion claim** whenever enough other text (e.g. a large tool-call argument) followed it in the same window — reproduced with a constructed transcript: the literal claim text present, `--turn-text` bypassed (real stdin path), and it was silently dropped by the byte-tail | detection now runs over the full extracted text; the byte cap removed rather than merely enlarged, since the correctness of the ONE genuinely blocking check in this schema is worth more than the bytes saved |
| **PARTIAL** both post-edit path classifications require a literal leading `/` before the matched segment, so a **relative** `FILE_PATH` (plausible from an adapter/harness) matched neither pattern and silently triggered nothing | `FILE_PATH` is normalized to absolute (prefixed with `$REPO`) immediately after extraction, before classification |

Two NOTEs recorded, not code-changed: Devin's PPID-based session identity
(already disclosed in `hooks/README.md` for cross-conversation collision)
may also defeat the completion gate's OWN bound specifically if Devin
spawns each hook retry as a fresh subprocess — unverifiable without a live
Devin runtime, folded into the existing disclosure rather than guessed at.
And the stdin-JSON extraction paths (the ones adapters actually use) had
zero direct test coverage before this pass, which is precisely where both
FAIL 3 and the relative-path PARTIAL lived — closed with the new tests
below, all exercising the real stdin/transcript path, not `--turn-text`.

Every fix independently **RED-confirmed**: reverted in a scratch copy kept
alongside the real `scanner/` siblings (for `SELF_DIR`-relative lookups to
resolve), the corresponding new regression test re-run against it —
reproducing exit 1 instead of 0 for the hook-json fix, an unbounded refusal
for the fail-open fix, and a dropped claim for the truncation fix — then
GREEN-confirmed via the full suite once folded back in.

## Rings not run, with impact

Recorded once for the whole change; restated at each checkpoint.

| Ring | Status | Impact |
|------|--------|--------|
| R5 mutation | ⏭️ unavailable | No bash mutation tooling. Surviving-mutant evidence is unavailable for every script; R8 and the enumerated properties carry that weight |
| R6 formal | — not applicable | Stainless is Scala-only. All six triage candidates carry a stated verdict in `design.md`; `chain-state` is the closest call and the reason is recorded |
| R7 model checking | ⏭️ unavailable | No TLA+/Apalache |
| R9 telemetry | — not applicable | No telemetry stack |

## Standing limitations

Not defects. Restated here so a checkpoint cannot silently present them as
coverage.

1. Ring 3 properties are **enumerated over finite listed domains**, not
   sampled — no generator or shrinker exists for bash.
2. The **harness seam** is manually verified once per supported harness
   (4 obligations, specs 4 and 6).
3. The **ledger is forgeable in-process** — recording command and exit status
   makes a row re-checkable, not unforgeable (specs 2 and 5).
4. **Completion-claim detection is approximate** — reads turn text; bounded
   refusal is the mitigation (spec 6).
