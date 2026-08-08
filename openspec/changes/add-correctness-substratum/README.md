# add-correctness-substratum — schema v12

Gives the verified-scala3 workflow a **definition of "correct"** and a
mechanism that keeps it present in every context, at every step, in every ring.

## The problem in one line

The schema defines correctness *extensionally* — "passes rings 0–9" — which is
a procedure, not a definition, and gives the agent nothing decidable in the
moment.

## The finding

Read as a fault dataset rather than as history, all eleven versions of hardening
in `schema.yaml`'s changelog record **one** defect:

> A check reported PASS on evidence it never obtained.

Every instance was committed by an agent that fully intended to be correct.
Motivation was never the missing ingredient — a mechanically enforced
distinction between *knowing* and *believing* is. This change therefore adds no
exhortation; Step 13 is already at maximum prose intensity and the violations
happened around it.

## The definition

> Code is **correct** iff every requirement is **bound** to ≥1 enforcement
> mechanism, each mechanism is **resolved** to an artifact that exists, and that
> artifact was **discharged** — observed to run green in this session.

Operative rule, decidable on every action:

> **Never let a claim outrun its evidence.**

`bound` → F7 (added v8). `resolved` → F9 (added v9). `discharged` → **does not
exist**; this change adds it.

## Measured on the live tree

```
spec-lint.sh              add-harness-api-phase0  →   0 FAIL, 66 WARN
spec-lint.sh --artifacts  add-harness-api-phase0  →  78 FAIL, 66 WARN
```

Correct-by-design (that change is pre-implementation, F9 is opt-in for exactly
that reason). What it demonstrates: the metric is computable today, and the
number that governs whether a claim is permitted is **invisible by default** —
`gate.sh` never passes `--artifacts`, and its payload reports *"all planning
artifacts exist"* without mentioning the 78.

## What ships

| # | Lever | Kind |
|---|---|---|
| 1 | Correctness invariant + definition | `schema.yaml`, `templates/spec.md` |
| 2 | Evidence ledger (`evidence-ledger.jsonl`) | new script-written format |
| 3 | `gate.sh`: `UserPromptSubmit`, `--artifacts`, fingerprinting, new payload | bash |
| 4 | Step 13 checkpoint **generated from** the ledger | `schema.yaml` |
| 5 | `PostToolUse` spec-lint / danger-scan; `Stop` gate | hook adapters |
| 6 | Hook heartbeat + self-check | bash |

## Why this is riskier than the v11 precedent

`2026-07-05-harden-verified-scala3-workflow` amended YAML only and was rated
low risk. This ships **executable enforcement that becomes the source of truth
for correctness claims**. A wrong ledger manufactures false confidence — the
defect class this workflow exists to remove, rebuilt with more authority.
Risk level: **High**. Mitigation is ordering: the ledger is advisory before it
is checkpoint-generating, and the `Stop` gate lands last, honouring
`hooks/README.md`'s promotion-on-evidence discipline.

## Status

- [x] proposal
- [x] capability-check — shell toolchain **installed** (shellcheck 0.11.0,
      bats 1.14.0, shfmt 3.13.1) and the `jq` ban **lifted** in favour of a
      declared prerequisite set. Rings 1 and 3 for shell are now available;
      `.bats` is the detected framework. Follow-on scope: amend
      `hooks/README.md` + `gate.sh` (they still assert "no jq"), and add the
      tools to CI (currently developer-local only).
- [x] inventory-check — **no Scala concepts used or introduced** (shell/YAML/
      markdown only), so the Step 12 concept delta must be **empty**. Project
      inventory tables verified clean against the semantic scanner (opaque
      types 7/7); **5 stale prose/metadata items corrected**, including an
      obsolete note that had been steering every consistency check away from a
      working scanner. `registry-check.sh` OK (740 tokens).
- [x] specs — 6 capabilities, dependency-ordered:
      `correctness-invariant` → `evidence-ledger` → `chain-state` →
      `gate-payload` → `checkpoint-from-ledger` → `hook-tiers` (blocking tier
      last). **0 FAIL, 23 WARN**; `openspec validate --strict` passes.
- [x] spec-lint — **all 6 PASS**, 0 FAIL / 23 WARN (all W3, confirmed).
      4 corrections applied during the lint, incl. 4 missing obligations at the
      harness testability boundary. 4 standing limitations declared.
- [x] design — 4 decisions. **Ledger is JSON lines, not TSV** (amends the
      `evidence-ledger` spec; re-linted, F8 caught a dangling Source).
      Three-way exit codes with `2` = undetermined. Ring 6 triage: all
      candidates **No**, with `chain-state` recorded as the closest call and
      the reason stated (bash, not Scala — a distinct objection from the two
      v10 forbids).
- [x] implementation-order — 6 specs, dependency-sorted; **all 12 gates
      `separate`** (mechanical consequence of risk=High); 2 typed contracts
      declared despite the Scala waiver; `hook-tiers` last by design decision,
      not by dependency.
- [x] tasks — 71 tasks, derived from `implementation-progress.md` (the single
      source of truth, seeded unchecked).

**All 8 artifacts complete.** Ready for `/opsx:apply`.
