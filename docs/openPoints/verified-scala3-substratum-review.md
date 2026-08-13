# Review: `add-correctness-substratum` (schema v12) — findings, defects, and roadmap

**Subject of review:** branch `claude/verified-scala3-correctness-6739ef` (verified-scala3
schema v12, "correctness substratum"). Reviewed on the adk4s repo as the workflow's
reference usage. This document is intended to be transformed later into an OpenSpec
change against the workflow; every finding below is written to be directly liftable
into a spec (requirement-shaped, with mechanism and test notes).

**Review method:** read-only analysis of the branch via `git show` (working tree not
disturbed), full read of `schema.yaml` v12, `hooks/{gate.sh,README.md}`,
`scanner/{ledger,chain-state,checkpoint,spec-lint,registry-check}.sh`, the `.jq`
contracts, the 6 specs + design + proposal + archived `evidence-ledger.jsonl`, the
bats suites, `GRAPH-PROTOTYPE.md` + `openspec-graph.py`, CI templates, and the
tutorial docs. Additionally, the v12 gate/scanners were **staged to a temp dir and
run against this live repo** for the payload/token-cost measurements in §7.

**Verdict: ADOPT.** The change materially improves correctness and is self-dogfooded
with receipts. Its residual weaknesses are all in one family the change itself names:
the evidence chain's last mile is still agent-composed. Each has a concrete fix below.

---

## 1. What the change is

Six layers, one goal — mechanically separate *knowing* from *believing*:

1. **A definition** (`correctness-invariant`): correct ⟺ every requirement is **bound**
   (spec-lint F7) → **resolved** (spec-lint F9) → **discharged** (a recorded green run
   at the current baseline). Operative rule: *never let a claim outrun its evidence.*
2. **Evidence ledger** (`scanner/ledger.sh`): JSONL, jq-encoded, append-only by
   absence-of-update-capability, tri-state exit codes (0/1/2 = clean/finding/undetermined),
   versioned records (`v`), unknown version ⇒ undetermined.
3. **Derived metric** (`scanner/chain-state.sh`): bound/resolved/discharged per
   requirement, delegating verdicts to spec-lint + ledger (never reimplementing),
   self-validating report contract (`chain-state-report-contract.jq`).
4. **Checkpoint generation** (`scanner/checkpoint.sh`): Step 13 ring results generated
   from ledger rows (last row wins, non-zero exit ⇒ "failed"), plus `regenerate-tasks`.
5. **Hook promotion** (`hooks/gate.sh`): per-turn `UserPromptSubmit` injection
   (fingerprint-suppressed), `PostToolUse` non-blocking correction (spec-lint /
   danger-scan at edit time), and a **bounded** `Stop`-tier completion refusal
   (once per turn, fail-open when bounding state unavailable).
6. **Self-instrumentation**: heartbeat + `--check-installed`, INSTRUCTION DRIFT
   detection in spec-lint CONTEXT, bats suites (~3,300 lines) for the workflow's own
   scripts, declared prerequisite set (bash, git, jq, shellcheck, bats, shfmt) with CI.

## 2. Why it improves correctness

The changelog-as-fault-dataset analysis is the load-bearing insight: 8 historical
defects are **one** defect — *PASS reported on evidence never obtained* — committed
by agents fully intending to be correct. The change acts on the diagnosis:

- **(a) The `undetermined` exit code (design Decision 2) is the highest-value
  mechanism.** "Absence of evidence ≠ evidence of absence," enforced structurally in
  every new tool; chain-state treats spec-lint's non-finding failures as undetermined.
- **(b) `discharged` closes the most dangerous hole.** Before v12 nothing distinguished
  "the suite exists" from "the suite ran green this session." Step 13's human gate is
  now grounded in recorded runs.
- **(c) Hooks attack opt-in-ness.** Per-turn facts recomputed from disk survive
  compaction; prose escalation does not.
- **(d) Self-dogfooding with receipts.** Ring 8 fresh-context passes found and fixed
  real defects in the tooling itself before shipping (unattributable-title defaulting
  to discharged; unbounded refusal without STATE_DIR; harness contract mismatch for
  refusals; `grep -qF` substring bugs ×3; no-trailing-newline ledger corruption;
  `has("total")` accepting an undetermined report…). Each fix is documented at the
  defect site.
- **(e) The workflow's own 11 scripts now have a test corpus** (bats) with sound
  conventions (enumerate-don't-claim-sampled, assert exit codes, fixtures never
  regenerated).
- **(f) Declared prerequisites replace the zero-dependency rule honestly** — the
  retained justification ("a check that only runs on one machine is a check that
  stops running") is now served by *declaring and asserting* the set.

## 3. Defects found in the change (requirement-shaped, for the future spec)

### D1 (critical): `discharged` ignores the run's exit status

**Where:** `scanner/chain-state.sh` — the per-obligation discharge check:

```bash
found="$(printf '%s' "$ledger_out" | jq -r --arg sp "$spec_name" --arg ob "$mobl" \
  'select(.spec == $sp and .obligation == $ob) | .obligation' | head -1)"
```

No `.exit == 0` filter. A row recording a **failed** command (`"exit": 1`)
**discharges the obligation** in chain-state — hence in the per-turn gate payload and
in the completion gate (both consume chain-state). Contradicts schema.yaml's own
definition ("DISCHARGED — observed to run **green**") and `checkpoint.sh`'s discipline
(which *does* inspect `.exit`, rendering non-zero as "failed").

**Proof of untestedness:** every discharged fixture in `tests/chain-state.bats` uses
`--exit 0`; the chain-state spec's scenarios cover "no runs recorded" but never "a run
recorded with a non-zero exit."

**Fix:** `select(.spec==$sp and .obligation==$ob and .exit == 0)`. Consider a
`failed` reason-code so a recorded red run is visible as *negative* evidence rather
than indistinguishable from no evidence. Add the bats case.

### D2 (critical): baseline = HEAD vs baseline = spec-start ⇒ permanent red indictment

**Where:** `hooks/gate.sh` computes chain state for every active change at
`git rev-parse HEAD` (`gate_baseline`). Comment justifies: "the natural,
already-defined value is the CURRENT tip of the branch."

**Problem:** Step 0 records a per-spec baseline at spec start; ledger rows are stamped
with it (archived change rows carry `319090b`, `e20f805`, …). After spec N commits,
HEAD advances and every prior row reads stale: the prompt-submit payload and the
completion gate report earlier specs **undischarged forever** unless every ring is
re-run after every commit. Two equilibria, both bad:

1. Agents learn the counter is always red and stop trusting it (alert fatigue —
   *teaches dismissal of the invariant*: worse than no counter).
2. Agents re-run the full ring suite after every commit (ruinously expensive).

**Fix options (increasing strength):**
1. Gate/checkpoint read per-spec baselines from `implementation-progress.md` (they
   are centrally recorded there).
2. Forgive staleness when the artifact is unchanged since the row's baseline
   (`git diff --quiet <baseline> HEAD -- <artifact>`): a run remains discharged until
   the artifact it ran changes. Semantically the right definition of stale evidence.
3. Expose `--forgive-unchanged` in `ledger.sh read` so gate and checkpoint share one
   discipline.

### D3 (high): the ledger's `exit` field is agent-supplied — the one field fabrication needs

`ledger.sh append --command '…' --exit 0` — the model composes the invocation,
*including the claimed exit code*. The design honestly limits tamper-proofing ("moving
tampering from default to deliberate"), but a **capture mode** converts "agent asserts
an exit" into "script observes an exit" at no philosophical cost:

```bash
ledger.sh run --file F --change C --spec S --ring R --obligation O \
              --artifact A --baseline SHA -- bats x.bats
```

The script executes, records its own `$?`, stdout/stderr digest, wall time.

**Adjacent gap:** schema.yaml apply Steps 3–11 **never instruct a `ledger.sh append`**;
only Step 13 consumes the ledger. In the archived run, rows share timestamps (appended
in one batch *after the fact*, not at ring time). Solution: wire `ledger.sh run` into
each ring step in the apply instruction so evidence is a side effect of running the
ring, forgotten-proof.

**Also add:** artifact content hash (`sha256` field) at record time → "the artifact
that ran green is the artifact present" becomes checkable without re-running, and
feeds D2's forgiveness rule.

### D4 (high): judgment rings recorded as prose degrade re-checkability where it matters

The ledger contract permits `ring: "manual"` and arbitrary `command` text. The archived
ledger already contains:

```
R8:  "fresh-context Agent subagent review (spec+diff only) + reproduction…"
R2:  "manual trace of dispatch code + adapter config review"
R2:  "grep -nE 'curl |wget …' checkpoint.sh", exit: 0
     # — but grep-with-no-matches exits 1: command and recorded exit DISAGREE.
     # The row is not re-checkable as recorded. Full v9 pattern inside the
     # evidence store itself.
```

Judgment rings (R2, R8) legitimately have no deterministic command. Rather than
pretending to one: require `manual`/R8 rows to name a resolvable report **artifact**
(the R8 report file), verify it F9-style, hash it (D3). Add a lint: if `command`
parses as a runnable shell command, replay must reproduce the recorded exit (D6).

### D5 (medium): three parallel parsers of the Proof-Obligations table, documented divergence

- `spec-lint.sh` (awk): F7/F9 verdicts; header excluded **by content** (`!~ /Obligation/`).
- `chain-state.sh` (awk): row mapping; header excluded **structurally** (lookahead);
  deliberately diverges from spec-lint (tested, commented).
- `openspec-graph.py`: a third parse; "inferred links" honestly tagged.

The `unattributable` reason-code is the cost visible in the product: chain-state's
exact-title matcher is narrower than spec-lint's `named_exists`, so a garbage-but-honest
category exists for real-world specs. **Root fix (pick one):**

1. **Machine-readable obligation blocks in specs.** The table is already machine-mandated
   (v8 Source format); make it *generated from* a fenced ```json block in the spec, with
   a writer script doing table↔JSON round-trip and spec-lint validating agreement. Kills
   the awk table-surgery bug class (header-exclusion, +-splitting, ordinal vs title)
   permanently. LLMs keep structured blocks consistent far better than prose-table
   conventions.
2. **Single fact extractor:** promote `openspec-graph.py` (§5); chain-state becomes a jq
   predicate over its export + the ledger.

**Sub-fix regardless of choice:** give `spec-lint.sh` a `--format json` output consumed
by chain-state — today chain-state regexes spec-lint's human prose (completion-message
forensics: summary lines, "no specs found…" strings). It guards well, but a machine
interface retires the fragility class.

### D6 (medium): re-checkability promised, never mechanized — no `ledger verify`

"A row records the COMMAND and its EXIT STATUS, so it is re-checkable by re-running it"
— nothing runs it. Add `ledger.sh verify [--change C] [--baseline SHA]`: re-execute each
row's `command`, compare exits, report `replay matches | diverges | manual/unreplayable`
per row, non-replayable explicitly named (never silently skipped). Wire into CI and into
the pre-archive gate. Combined with D1 this closes the last route by which a wrong row
can stand.

### D7 (low): dangling reference in the drift detector's own remediation

`spec-lint.sh` INSTRUCTION DRIFT prints:
`Re-install (verified-scala3/sync-skills.sh)` — **no such script exists** (the real one
is `scanner/install-skills.sh`). The detector for stale instructions carries a wrong
remediation pointer. Fix the reference; add a bats property: every tool-name mentioned
in scanner messages resolves to a tracked file.

### D8 (low): hygiene batch

- `ledger.sh`: dead duplicate `update|delete|rewrite|edit)` case arm at the bottom
  (unreachable — the pre-parse check dies first). Remove; dead code in an enforcement
  tool breeds confusion about where enforcement lives.
- `gate.sh` hand-rolled JSON-escape fallbacks survive "in case jq goes missing."
  Pragmatic for never-fail-a-session, but re-introduces the escaping class the jq lift
  removed. Annotate with `deps-rule:allow`-style markers + a property: when jq exists,
  fallback paths never execute.
- `gate.sh` extracts `cwd` from hook JSON with a `sed` regex over JSON though jq is a
  declared prerequisite loaded two lines later. Parse structured data with the
  structured parser.
- Heartbeat is written **before** the relevance guard → `.git/verified-scala3-gate/`
  is created in *every* git repo the hook fires in, including non-openspec ones
  (confirmed live during this review: a `verified-scala3-gate/` dir was created in this
  repo by a measurement run). Write after the guard, or document the pollution as
  deliberate.
- INVARIANT banner duplicated verbatim ×8 in schema.yaml (justified comment exists, but
  9 copies drift). YAML anchors (`&invariant`/`*invariant`) enforce identity, or emit
  the banner from `description` via the CLI.
- PPID session fallback: concurrent sessions sharing a PPID collapse fingerprint
  suppression and the bounded refusal. Documented debt for Devin; acceptable, keep
  flagged.
- Normalize remaining two-way-exit scripts to tri-state (design Decision 2 deferred it;
  every scanner called by chain-state/gate inherits the ambiguity).

## 4. The No-JVM constraint and the "Scala scripts" question

**Keep the hook-path tools bash.** `gate.sh → chain-state.sh → ledger.sh`, and
post-edit → `spec-lint`/`danger-scan`, run synchronously per turn, possibly cold, where
JVM startup would invite `VERIFIED_SCALA3_HOOKS=off` — the documented "a disabled hook
enforces nothing" cliff.

**But the change itself discovered the right no-JVM upgrade target: `jq`.** The `.jq`
contract files are the strongest, most testable code in the change; chain-state's
report assembly (already jq) survived Ring 8 most cleanly. **Recommendation: port the
remaining decision logic (table parsing, counts, filtering) into `jq` programs**, bash
reduced to arg-parsing + file walking + I/O — the formal version of design Decision 3.
jq programs are deterministic, total, compiled-per-invocation (syntax errors fail
loudly, not silently mid-pipeline), already a declared prerequisite, and testable in
bats. Buys most of what a Scala rewrite would, with zero new constraint exceptions.
Scala-cli pays for itself only where the domain is Scala ASTs (concept-scanner,
Metals/impact scans) — already JVM-laden by nature.

**Long-term option for the gate path:** a precompiled native binary (GraalVM
native-image of a small fact-extractor) installed as a declared prerequisite, versioned
and asserted in CI — the same mechanism v12 built for jq/bats. v13+ candidate.

**Determinism note:** the bats suite now hard-requires the `openspec` CLI
(correctness-invariant.bats renders artifact instructions through it). Correct to fail
loudly rather than skip; ensure the failure names the missing prerequisite.

## 5. GRAPH-PROTOTYPE: promote it; it and chain-state are two answers to one need

`chain-state`'s bound/resolved clauses ≈ the graph's `obligations` reachability audit,
re-implemented in awk against spec-lint's text output. The graph already: models
req→oblig→artifact→code as topology, honestly tags inferred links, lists its graduation
path.

1. **Promote `openspec-graph.py` to the single fact extractor.** `export` → JSON;
   chain-state becomes a jq predicate over (graph JSON ⊕ ledger read). Deletes
   chain-state's awk re-parsing, the `unattributable` escape hatch (graph resolves
   ordinal/title/property sources uniformly), and D5's three-parser divergence.
   python3 goes through the v12 prerequisite mechanism (declare, install in CI);
   keep bash chain-state as the degraded gate-path mode (mirroring concept-scanner's
   grep fallback).
2. **Add test→requirement edges** (Ring 3 cross-reference tables + the bats
   `# spec: Scenario:` citation convention just standardized) so `discharged` refines
   from "some row mentions the obligation" to "the tests cited by its obligations ran."
3. **Temporal commit nodes**: per-spec SHAs are recorded in
   `implementation-progress.md`. As graph nodes they make staleness (D2) a graph
   query: *is there a path from this artifact to a commit after the row's baseline?*
4. **Wire the reachability audit into spec-lint as a mechanical check** (stricter than
   check 12, per the prototype's own graduation notes). Caveat: the prototype's
   measured `add-optimizable-surface` FAIL (4 candidate unenforced requirements via
   inferred links) is from 2026-07-20 on an archived change — confirm stale or live
   before building on it.

## 6. Safety design of the new blocking tier — assessment

Sound and consistent with the change's philosophy: post-edit can't strand (post-hoc);
completion refusal bounded once-per-turn, cleared at prompt-submit, `stop_hook_active`
honored, unbounded-refusal-when-STATE_DIR-missing fixed by failing open *with a trace
line*. Residual point: the `grep -qE 'Spec N/M complete|…'` heuristic means phrasing
evades the gate — acknowledged by the spec ("approximate, by design"). Acceptable
*because* bounded refusal costs one turn; but never let the gate be cited as stronger
than it is in future payloads or docs ("prevents completion without evidence" — no: it
nags once per turn on matching phrasing). The payload carries the real weight.

## 7. Token cost (measured, not estimated)

Method: branch's gate.sh/scanners staged under /tmp, run against this live repo;
heuristic 4 chars/token (±20%).

### 7.1 Per-turn hook injection

| Case | v11 | v12 | Δ |
|---|---|---|---|
| Steady state (no active/facts-changed) | 1,228 B ≈ 310 tok | 1,717 B ≈ 430 tok | **+~120** |
| Repeat same session (fingerprint) | n/a | **0 (measured)** | 0 |
| Worst case: 28-requirement change, empty ledger, facts moved | — | 4,029 B ≈ ~1,000 tok | +~690 |

Unresolved requirements are listed **one named line each**; a 60-requirement spec
mid-flight prints 60+ lines ≈ +1,300–1,500 tok per facts-changing turn. Suppression
means ordinary turns pay ~0 after the first.

**Improvement:** cap the named list at N with "+M more" — the counts carry the
invariant; the full to-do list is `chain-state.sh`-available on demand. Removes the
worst case; steady-state delta stabilizes under 200 tok/turn.

### 7.2 Schema instruction growth (CLI-emitted)

- +871 chars (~217 tok) invariant banner ×7 artifacts (proposal, capability-check,
  inventory-check, specs, design, impl-order, tasks)
- spec-lint: +846 tok (CONTEXT-block rule + applicability table)
- apply: +801 tok (Step 13 rewrite)
- **Whole-change ≈ +3,200 tok** across the artifact DAG.

### 7.3 Output-side new work

- `ledger.sh append/run` per ring: ~75 tok × 5–8 rings × N specs (~3,000–3,600 per
  6-spec change) — but it *replaces* comparable free-text claims formerly written into
  implementation-progress.md. Net ≈ 0.
- Step 13 paste ≈ same size as the prose ring list it replaces. Net ≈ 0.
- Post-edit injections: 0 when clean; ~200 tok per finding (measured spec-lint FAIL
  ≈ 855 B). The only recurring cost with no suppressor — the price of correction at
  the keystroke.

### 7.4 Bottom line

Realistic 6-spec change, ~150 turns: **order +10k–15k tokens, i.e. low single-digit
percent** of what those turns already consume. Offsets that count honestly: prevented
wrong-direction work (78 invisible F9 commitments in the proposal's own measurement),
reduced post-compaction re-orientation scavenger hunts (plausible, unmeasured), and the
completion gate deleting the done-without-evidence failure mode (one human review
round-trip ≈ the year's overhead by itself).

## 8. Beyond the workflow: additional correctness mechanisms

Ordered by expected value:

1. **Evidence replay as standing CI** (extends D6): periodically re-execute archived
   ledgers of merged changes. Evidence decays as the repo evolves; diverging replay is
   the mechanical trigger for v12's own "recorded limitation re-established" corollary.
2. **Bind evidence into git history via `git notes`**: attach the checkpoint.sh JSON
   to the per-spec commit (`git notes add -F checkpoint.json <sha>`). Evidence becomes
   immutable-ish, diffable, bound to the durable identity Step 13 says the commit is;
   editing a note is visible in the notes ref's history. Pure bash+git — no new
   prerequisite.
3. **Escape ledger / defect-class registry, institutionalized.** The proposal's own
   method ("changelog as fault dataset") becomes a standing artifact:
   `openspec/escape-analysis.md` — every post-green correctness incident maps to a
   defect class; every class maps to a mechanical detector or an admitted blind spot.
   The `verified-scala3-escape-analysis` skill does this ad hoc; tracking *the escapes
   themselves* as a dataset turns it into a calibrated instrument. Workflow-level
   analogue of "every production bug becomes a regression test."
4. **Red-team corpus + escape-rate telemetry.** Detectors' coverage is currently
   argued, never measured. N synthetic changes with seeded subtle defects (silent
   `case _` mapping; obligation naming a nonexistent suite; N/A on an applicable check;
   ledger row with `command`/`exit` disagreement…) run through the full workflow by
   fresh agents, tracking which rings catch which seeds. Cheap on git worktrees;
   tells you where the next hardening token should go.
5. **Move "danger" detection from regex to compiler.** danger-scan.sh greps for
   `case _`/`isInstanceOf` shapes; the same rules as **custom Scalafix semantic rules**
   (SemanticDB already enabled in build.sbt) run at Ring 0/1 deterministically,
   formatting-immune, zero LLM involvement. AGENTS.md's "NEVER isInstanceOf/
   asInstanceOf" is a compile-time rule masquerading as prose+regex.
6. **Deterministic re-run priming**: record ScalaCheck/TestControl seeds in ledger
   rows; `verify` re-runs with the *recorded* seed. "Observed green once" →
   "reproducibly green."
7. **Cross-agent audit asymmetry**: Ring 8 is fresh-context but same-model. Where the
   harness allows, run the Ring 8 reviewer / Step-13 claim-auditor as a *different
   model family* — decorrelated hallucination patterns give a second, weaker but
   independent channel for the irreducibly-judgment-shaped checks.
8. **Session-provenance binding**: stamp the hook session id into ledger rows
   (`VERIFIED_SCALA3_SESSION_ID`); checkpoint.sh requires the presenting session to
   match the evidence-producing session (or explicitly attest). The invariant says
   "obtained in **this** session"; nothing measures it today.

## 9. Prioritized roadmap (input for the future OpenSpec change)

| # | Item | Class | Effort | Rationale |
|---|------|-------|--------|-----------|
| 1 | D1 — discharged requires `.exit == 0` (jq clause + bats case) | defect fix | XS | contradicts the shipped definition |
| 2 | D3+D6 — `ledger.sh run` (capture) + `ledger.sh verify` (replay); wire `run` into apply Steps 3–11 | mechanism | M | turns the ledger from better-written claim into observed evidence — the definition's actual promise |
| 3 | D2 — per-spec baselines and/or unchanged-artifact forgiveness | defect fix | S | without it the indictment desensitizes within one change |
| 4 | D4 — judgment-ring rows name a resolvable hashed report artifact; lint command/exit disagreement | integrity rule | S | the archived ledger already contains a disagreing row |
| 5 | D5 — single fact extraction: promote openspec-graph.py OR machine-readable obligation fenced blocks; `--format json` on spec-lint | structural | L | retires the bug *class* Ring 8 kept finding |
| 6 | D7+D8 hygiene: dangling sync-skills reference, dead case arm, YAML anchors, jq for cwd parse, heartbeat-after-guard, tri-state normalization | hygiene | XS each | cheap, all measured |
| 7 | §5 — graph graduation: test→req edges, temporal nodes, spec-lint mechanical audit | capability | L | transitive truth instead of pairwise lint |
| 8 | §7.1 — cap the payload's named-unresolved list at N | UX/cost | XS | removes the only unbounded per-turn cost |
| 9 | §8.1–8.3 — replay CI, git-notes binding, escape ledger | standing process | M | converts one-off insights into continuous calibration |
| 10 | §8.4 — red-team corpus / escape-rate telemetry | measurement | L | the only honest way to know the guards work |

## 10. One-line summary

The substratum is the right architecture — a definition plus three legs already built
(F7, F9, ledger) — and this review's entire agenda reduces to one sentence in the
workflow's own vocabulary: **make evidence production a side effect of ring execution
(capture), and make evidence consumption replayable (verify).**
