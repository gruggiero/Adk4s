# Proposal: Correctness Substratum (schema v12)

## Why

The workflow's stated goal is that the LLM writes **correct** code. But the
schema never defines "correct". It defines a *procedure* — pass rings 0–9 —
and leaves the agent to infer the goal from the procedure. That inference is
the gap this change closes.

### The changelog is a fault dataset, and every entry has one shape

`schema.yaml`'s changelog records eleven versions of hardening. Read as data
rather than as history, the defects are not eleven accidents:

| ver | defect |
|---|---|
| 5 | Ring 5 mutated the wrong files (unanchored diff) |
| 8 | obligation `Source` cells named nothing — requirement→obligation binding unverifiable |
| 9 | an obligation named a test suite **that was never written** |
| 9 | an impossibility claim enforced only by tests (W5) |
| 10 | Ring 6 skipped by reflex on a rationale about *types*, not the algorithm |
| 11 | ALTITUDE marked `N/A` on a repo holding 31 concept files — never looked |
| 11 | `generatedBy` sat at 7.0.0 while the schema reached 11 — nothing ever read it |
| 11 | two parsers read a legitimate notation as *nothing* and reported OK |

Every one is the same defect:

> **A check reported PASS on evidence it never obtained.**

Not "the code was wrong and the check missed it" — the check's own input was
fabricated, stale, empty, or unread, and the reporting mechanism could not
distinguish that from a genuine pass.

This matters for the design. **An agent that wants correctness more fixes none
of the eight.** Every one was committed by an agent fully intending to be
correct; v11's reviewer was following the instructions faithfully, from a stale
copy. Motivation was never the missing ingredient. What is missing is a
mechanically enforced distinction between *knowing* and *believing*.

Consequently this change does **not** add exhortation. Step 13 already runs
seven lines of `███`, "MANDATORY", "No exceptions", and "is NOT acceptable"
three times. The violations happened *around* that prose, not in defiance of
it. Escalating it further has negative returns.

### Measured evidence from the live tree

Taken on `add-harness-api-phase0` at the time of writing:

```
spec-lint.sh              openspec/changes/add-harness-api-phase0  →   0 FAIL, 66 WARN
spec-lint.sh --artifacts  openspec/changes/add-harness-api-phase0  →  78 FAIL, 66 WARN
```

All 78 are F9 — obligations naming `MiddlewareStackSpec.scala`,
`SemilatticeLawsSpec`, `StackKernelBridgeSpec`, `AgentMiddlewareLawsSpec` and
others that do not resolve to a tracked file.

**This is not a defect report.** That change is pre-implementation, and F9 is
opt-in precisely because specs are written before their tests exist. 78
outstanding commitments is the *correct* state there. What the measurement
establishes is three facts about the machinery:

1. **Two of the three legs of a chain metric already work**, with zero new
   tooling: F7 gives `bound`, F9 gives `resolved`.
2. **The number that governs whether a claim is permitted is invisible by
   default.** `hooks/gate.sh` calls `spec-lint.sh --context-only`, never
   `--artifacts`. Its injected payload (verified by running it) reports
   *"next artifact — none, all planning artifacts exist"*: true, and the most
   reassuring possible framing of a change with 78 outstanding commitments,
   none of which it mentions.
3. **The default invocation renders two different states identically.**
   "78 commitments outstanding" and "everything discharged" both print
   `0 FAIL`. Pre-implementation that is right. Post-implementation it is the
   v9 failure shape — *an obligation naming a suite that was never written* —
   and the only thing forcing the `--artifacts` run is prose in Step 12.

A fourth fact is the actual gap: **`discharged` does not exist anywhere.** F9
proves a file *exists*. Nothing in the workflow records that it *ran green,
when, at what SHA, with what exit code*. The Step 13 checkpoint's
`**Rings:** <✅/⏭️ per ring>` is prose the agent writes about what it did —
recorded-but-never-checked, at the workflow's most load-bearing moment.

### Two live findings surfaced by the same run

- **W6** fires on this change: a spec declares Ring 6 Formal Contracts with no
  obligation naming a bridge/mirror artifact. Per v10's own rule, that is a
  proof about a model nobody runs.
- **W1 flags the word "correct"** inside `Then` clauses, e.g. *"the result is
  `Right(state)` with `state.get(c)` **correct**"*. A legitimate catch, and a
  pointed one: the term is undefined at the top of the workflow and undefined
  in the assertion. This change fixes the first; spec edits fix the second.

## What Changes

### 1. A definition of "correct", stated normatively

The workflow already owns the pieces — requirement → obligation → mechanism →
artifact — but never assembles them into a definition. Add one:

> Code is **correct** iff every requirement is **bound** to ≥1 enforcement
> mechanism, each mechanism is **resolved** to an artifact that exists, and
> that artifact was **discharged** — observed to run green in this session.
> Obtained, not recalled.

Three clauses, each naming a distinct historical failure: *bound* → F7 (v8);
*resolved* → F9 (v9); *discharged* → the v11 defect.

Compressed to the operative rule, decidable on every single action:

> **Never let a claim outrun its evidence.**
> A verdict you did not obtain in this session is not a verdict.
> `N/A`, `passes`, `not applicable`, `already handled` are **claims**.

The property that makes this substratum-shaped, and that "the code must be
correct" lacks: it is a **state, not a phase** — answerable at any instant, in
any context, at any ring, by any agent, without knowing where in the workflow
it is.

### 2. Evidence ledger (new artefact, script-written)

`openspec/changes/<change>/evidence-ledger.jsonl` — append-only, **written by
`scanner/*.sh` and the ring commands, never composed by the model**. One JSON
object per line (design Decision 1 — the `command` field routinely contains
quotes and can contain newlines, so delegating framing to the encoder is what
makes row integrity structural rather than a matter of caller discipline):

```
{"v":1,"ts":…,"change":…,"spec":…,"ring":…,"obligation":…,"artifact":…,"command":…,"exit":…,"baseline":…}
```

Consequences:
- `discharged` becomes computable — the third leg lands.
- **Step 13's checkpoint is generated from the ledger**, not written by the
  agent. A claim with no ledger row is then mechanically absent rather than
  merely unsupported.
- `gate.sh` reads it to compute the live unresolved count.

Honest limit, stated up front: a ledger the agent *can* write, it *can*
fabricate. Recording `command` + `exit` + `sha` makes a row re-checkable, and
script-owned appends make tampering an explicit act rather than the default
path. Full tamper-proofing is not reachable in-process; moving tampering from
*default* to *deliberate* is the achievable and sufficient goal.

### 3. `hooks/gate.sh` promotion — from positional to normative

Current payload is *where am I* (schema version, registry present/absent,
active change, next artifact). It says nothing about what must be true of
anything the agent says. Changes:

- **Fire on `UserPromptSubmit`, not only `SessionStart`.** `gate.sh` already
  handles `--event prompt-submit` → `UserPromptSubmit`; it is built and
  unwired. SessionStart fires once and is lost to compaction.
- **Move fingerprint-and-reinject-on-change from the pi adapter into
  `gate.sh`**, so every harness gets it instead of only pi.
- **Run `--artifacts`** so the unresolved count is in the payload.
- **New payload**: the invariant plus a live counter and the offending rows —
  a standing indictment, not a status line:

```
verified-scala3 — invariant (schema v12)
  Never let a claim outrun its evidence.
  "N/A" / "passes" / "already handled" are CLAIMS, not verdicts.

  chain     12 requirements · 12 bound · 9 resolved · 3 UNRESOLVED
  unresolved  Requirement: operator mapping is total   (no artifact)
              Property: recall-ordering                (dangling, F8)
              Requirement: replay equals live          (names ReplaySpec — absent)
```

The design intent: an agent cannot write "spec complete" three lines under
`3 UNRESOLVED` — not because it is motivated, but because the contradiction is
in its context. Slogans decay under compaction; a number recomputed from disk
every turn does not.

### 4. Tier B+ — timely correction without stranding

`hooks/README.md` declines blocking hooks because *"a blocking hook that
misfires strands the agent with no way forward"*, and asks for promotion
"later, on evidence". That reasoning stands and is not overridden. Use the
unused middle tier instead:

- **`PostToolUse`** — the action already happened, so nothing can be stranded,
  but correction arrives at maximum relevance:
  - after `Write`/`Edit` on `openspec/changes/*/specs/**/spec.md` → run
    `spec-lint.sh`, inject FAILs now, not three artifacts later
  - after `Write`/`Edit` on `**/src/main/**/*.scala` → run `danger-scan.sh`,
    inject unjustified hits at the keystroke instead of at Ring 8
- **`Stop`** — the one place a genuine block is justified: refuse to end a turn
  that claims a checkpoint while the chain has unresolved rows. Stop-blocking
  cannot strand an agent mid-edit; worst case is one extra turn. *"You may not
  finish on an unsupported claim"* is the invariant at its cheapest
  enforcement point.

### 5. Hook self-check

`gate.sh` always exits 0, its relevance guard exits silently when `openspec/`
is absent, and `VERIFIED_SCALA3_HOOKS_TRACE` is off unless exported. The
README ships a **three-level manual** verification procedure precisely because
nothing automated does it. Add a heartbeat `gate.sh` writes and a scanner check
reads, so "not installed" is a detectable state rather than a silent one.

## Prerequisites

The workflow's old constraint — *bash + git only, no JVM, no network, no `jq`*
(`hooks/README.md`) — is **replaced** by an explicit, installable prerequisite
set (human decision, 2026-08-08). The retained reasoning, *"a check that only
runs on one machine is a check that stops running"*, is now served by declaring
and installing the set rather than by having no dependencies:

| Prerequisite | Version here | Purpose |
|---|---|---|
| `bash`, `git` | — | baseline |
| `jq` | 1.6 | JSON parse/emit in hooks and scanners (**ban lifted**) |
| `shellcheck` | 0.11.0 | Ring 1 — shell lint |
| `bats` | 1.14.0 | Ring 3 — shell tests |
| `shfmt` | 3.13.1 | shell format check |

JVM and network remain excluded for gate checks.

Two obligations follow, both in scope for this change:

- **`hooks/README.md` and `gate.sh`'s header comment still assert "no jq" and
  are now factually wrong.** They must be amended here. Shipping a change that
  leaves a documented rule contradicted by practice would reproduce the exact
  drift class this change exists to remove.
- **CI installs none of these.** `ci/*.yml` runs only `registry-check.sh` and
  has no install step, so Rings 1 and 3 for shell would be developer-local —
  the same opt-in weakness the hooks address. CI gains the install and the
  invocations.

## Approach

Ordered by dependency; each lands independently.

0. **Prerequisites**: amend `hooks/README.md` + `gate.sh` header for the new
   rule; add tool install + `shellcheck`/`shfmt`/`bats` steps to `ci/*.yml`;
   establish the `.bats` layout for the schema's scripts.
1. Invariant text + definition into `schema.yaml` (v12) and the spec template.
2. Ledger format + writer helper in `scanner/`; ring commands append.
3. `gate.sh` payload + `--artifacts` + fingerprinting + `UserPromptSubmit`.
4. Step 13 checkpoint generated from the ledger.
5. `PostToolUse` adapters; `Stop` gate.
6. Heartbeat + self-check.

## Verification Strategy

**This is not a docs-only change** — unlike the `harden-verified-scala3-workflow`
precedent, which amended YAML only. This ships **executable bash** (`gate.sh`
changes, a ledger writer, new hook adapters) that becomes load-bearing on every
correctness claim the workflow makes. Ring 3 is therefore **not** waivable for
the script components.

- [ ] Ring 0 (compile) — N/A (no Scala). Substitute: `bash -n` syntax check on
      every changed script; `schema.yaml` parses and `openspec` loads it.
- [x] **Ring 1 (lint) — APPLIES.** `shellcheck` **0.11.0** on every changed
      script — targets the quoting/word-splitting class that `bash -n` misses,
      where `gate.sh`'s string surgery lives. Baseline measured: 8/11 scripts
      clean, 5 findings in 3 files (4 intentional, needing annotated
      suppressions; 1 possible dead variable); `gate.sh` itself is clean, so
      shellcheck can gate this change from day one.
      `shfmt` **3.13.1** is installed but the tree is **unformatted (1955 diff
      lines)**, so `shfmt -d` applies to **changed/new files only** — a
      blocking tree-wide gate needs a separate one-time reformat commit.
      (Design artifact settles this; see capability-check.)
- [ ] Ring 2 (architecture) — N/A (no Scala layers). Substitute: the
      **PREREQUISITE RULE** (capability-profile) — declared tool set only
      (`bash`, `git`, `jq`, `shellcheck`, `bats`, `shfmt`); no JVM, no network
      — asserted per changed script.
- [x] **Ring 3 (behavioural tests) — MANDATORY, no waiver.** Framework
      **detected: bats 1.14.0** — generate `.bats`, not a hand-rolled runner.
      Cases: ledger append/parse round-trip; `gate.sh` payload shape for each
      `--format`; fingerprint suppression and re-emission on change; relevance
      guard silence outside an `openspec/` repo; `Stop` gate fires on
      unresolved and stays silent on clean. No `.bats` conventions exist yet
      for the schema's 11 scripts — establishing them is task 1.
- [ ] Ring 4 (wire compat) — **APPLIES**. The ledger is a persisted format read
      by `gate.sh` and the checkpoint generator: round-trip law, and a fixture
      ledger from this version must still parse after future edits.
- [ ] Ring 5 (mutation) — N/A (no mutation tooling for bash).
- [ ] Ring 6 (formal) — N/A. No decision/fold/law at the centre; the logic is
      I/O and string assembly. *(Stated as a verdict, per v10: silence is not a
      verdict.)*
- [ ] Ring 7 (model checking) — N/A.
- [x] **Ring 8 (adversarial review) — MANDATORY, fresh context.** Specific
      hunt for this change: can the ledger record a row for a run that did not
      happen? Can `gate.sh` emit a clean chain while unresolved rows exist? Can
      the `Stop` gate be satisfied without discharging anything?
- [ ] Ring 9 (telemetry) — N/A (no telemetry stack per capability profile).

**Self-tests specific to this change (the dogfood check):**

1. Re-run `spec-lint.sh --artifacts` on `add-harness-api-phase0` **after** its
   implementation lands: the 78 F9 FAILs must go to 0. If any remain, the
   obligation was discharged some other way and the row must say so.
2. The new `gate.sh` payload, run against this repo today, must surface a
   nonzero unresolved count — if it reports clean while `--artifacts` reports
   78, the payload is lying and the change has failed its own premise.
3. This proposal's own change directory must survive `spec-lint.sh` once its
   specs exist.

## Correctness Risk Level

**High.**

Blast radius is total — it shapes every future change, like the v11 precedent.
But unlike that precedent (YAML only, "low per-edit risk, trivially
reversible"), this change ships **executable enforcement that becomes the
source of truth for correctness claims**:

- A **ledger that is wrong manufactures false confidence** — it would generate
  checkpoints asserting runs that never happened. That is precisely the defect
  class this workflow exists to remove, rebuilt with more authority.
- A **`Stop` gate that misfires** blocks turn completion. Bounded (one extra
  turn), but it is the first blocking hook in the workflow and `hooks/README.md`
  deliberately avoided that risk.
- A **per-turn payload that is wrong** is worse than none: it would be trusted
  precisely because it claims to be read from disk.

Mitigation: land in the order above; the ledger is read-only-advisory (feeding
the payload) before it becomes checkpoint-generating; `Stop` lands last, after
the non-blocking path has run in daily use — the promotion-on-evidence
discipline `hooks/README.md` asks for.

## Existing Concepts

Workflow-level, not Scala. All exist and are reused, not recreated:

| Concept | Where | Role here |
|---|---|---|
| `spec-lint.sh` F7 (reachability) | `scanner/spec-lint.sh` | supplies `bound` |
| `spec-lint.sh` F9 (`--artifacts`) | `scanner/spec-lint.sh` | supplies `resolved` |
| `spec-lint.sh --context-only` | `scanner/spec-lint.sh` | single source of applicability facts; payload extends it, never recomputes |
| `hooks/gate.sh` | `hooks/gate.sh` | the injection point; extended, not replaced |
| pi adapter fingerprinting | `hooks/adapters/pi/verified-scala3-gate.ts` | logic promoted into `gate.sh` |
| `danger-scan.sh` | `scanner/danger-scan.sh` | invoked by the new `PostToolUse` tier |
| Proof Obligations table | `templates/spec.md` | the chain's declaration site |
| Step 0 baseline SHA | `schema.yaml` apply | the `sha` column of every ledger row |

No Scala concepts are used or introduced; `openspec/concept-inventory.md` is
untouched by this change.

## New Concepts

| Concept | Kind | Description |
|---|---|---|
| Correctness Invariant | normative text | the three-clause definition + the one-line operative rule |
| Evidence Ledger | persisted format (TSV) | append-only script-written record of discharged obligations |
| Chain State | derived metric | `bound / resolved / discharged / unresolved` for a change |
| Hook Heartbeat | persisted marker | makes "hook not installed" detectable |

## Typed Contract Decision

**Waiver for Scala** (no Scala types or signatures are introduced) — but **not
a waiver overall**. The precedent's blanket "docs/workflow-config only" waiver
does not apply, because this change ships executable scripts. Two contracts are
declared and reviewed at the Step 1 gate instead:

1. **Ledger record contract** — column names, types, ordering, append-only
   semantics, and the parse/round-trip law Ring 4 enforces.
2. **`gate.sh` CLI contract** — the existing `--event` / `--format` / `--repo`
   surface plus the new payload schema, held to the `hooks/README.md` rule that
   *adapters decide when to ask and where to put the answer; all logic stays in
   `gate.sh`*.

## Out of Scope

- Rewriting existing specs to remove the vague word "correct" flagged by W1
  (separate, mechanical, and per-change).
- Fixing the live W6 on `add-harness-api-phase0` (belongs to that change).
- Any change to rings 0–9 themselves. This change makes their results
  *recorded and load-bearing*; it does not alter what they check.
