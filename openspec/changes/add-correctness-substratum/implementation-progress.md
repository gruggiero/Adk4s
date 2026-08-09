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
- [ ] 3. `specs/chain-state/spec.md`
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
| Commit | ✅ (see checkpoint) |

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

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: exit-code contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · R8 | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 4. gate-payload

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: CLI + response-shape contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · **R4** · R8 | |
| Harness-seam manual verification (2 obligations) | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 5. checkpoint-from-ledger

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (minimal) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · R8 | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 6. hook-tiers

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: event + refusal contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · R8 | |
| Harness-seam manual verification (2 obligations) | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

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
