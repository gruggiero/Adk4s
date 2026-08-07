# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Mechanical pre-pass

<!-- Run BEFORE the judgment checks:
     1. `openspec validate --strict` — must pass.
     2. `openspec/schemas/verified-scala3/scanner/spec-lint.sh <change-dir>` —
        enforces the greppable subset (F1–F7) and reports vague-word,
        adversarial-confirmation and positional-reference candidates (W1–W4).
        F6 = obligation Source names nothing resolvable (mandated format).
        F7 = requirement named by NO obligation (unenforced — check 12 as
             reachability, stricter than the W2 row count).
        F8 = typed source names a Property/Scenario heading that does not
             exist in the spec (dangling reference).
        W4 = ordinal requirement references (prefer exact titles).
        W5 = impossibility claim enforced only by tests (ladder tier 1-2
             expected, or "tier-justified: <why not>" in Enforcement).
        F10 = behavioural registry exists but a spec has no "Concepts Used
             (behavioral)" section (ALTITUDE, structural half).
        W7 = code-identifier candidates inside a Given/When/Then clause
             (ALTITUDE — candidates for your judgment, not verdicts).
     F9 (artifact existence) is NOT run here — it is post-implementation;
     apply Step 12 runs `spec-lint.sh --artifacts`.
     Paste both outputs here. Script FAILs are lint FAILs. -->

**openspec validate --strict**: <!-- PASS / output -->
**spec-lint.sh**: <!-- paste summary line + findings -->

### Applicability (paste the script's CONTEXT block VERBATIM)

<!-- MANDATORY. Conditional checks (3, 6, 17, 18) have two halves: does the
     rule APPLY here, and does the spec OBEY it. The first is a repository
     fact the script reads off the filesystem; the second is yours. Never
     infer the first — a review once recorded ALTITUDE as N/A on a repo with
     30 concept files, having never looked for openspec/concepts/.

     A check the block reports as APPLIES may NOT be recorded N/A below.
     A check recorded N/A must quote the CONTEXT line that says so. -->

```
<!-- paste the CONTEXT block here -->
```

| Conditional check | CONTEXT says | Verdict allowed |
|---|---|---|
| 3 · testable with detected stack | <!-- PRESENT/ABSENT --> | <!-- APPLIES / N/A --> |
| 6 · reused concepts resolved | <!-- PRESENT/ABSENT --> | |
| 17 · ALTITUDE | <!-- PRESENT/ABSENT --> | |
| 18 · CONCURRENCY | <!-- kit detected? --> | |

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative SHALL/MUST statement before its first `**Given**` (mechanical: F1)
1c. Every "identical/same/preserved behavior" requirement over an enum/dispatch parameter has one scenario PER variant, each asserting the discriminating observable
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (openspec/capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in openspec/concept-inventory.md
7. Every property has a declared generator strategy (mechanical: F3)
8. Every temporal property has a trigger event and a response event (mechanical: F5)
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition (candidates: W1)
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave (aliasing to a richer type counts — "Type-Widening Impact" subsection required)
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism, in the mandated Source format (mechanical: F4 section presence, **F6 Source resolvable**, **F7 every requirement named — reachability**, **F8 typed source exists**, F9 artifact resolves at Step 12, W2 row count, W4 positional refs, W5 mechanism strength)
13. Every consumer-facing surface (tool/operation/IDL) has a scenario asserting what the consumer observes (parameter schema, not just presence)
14. Every asserted error variant is type-feasible vs the producing API's return type
15. ADVERSARIAL — every "only"/"never"/"must not" requirement has a scenario whose input the requirement forbids (mechanical half: F2/W3)
16. MUST-CONFIRM — externally-sourced classification tables / code mappings / value domains are marked MUST-CONFIRM with a pointer to the real source; invented plausible values FAIL
17. ALTITUDE — no code identifiers in Given/When/Then; concepts cited in "Concepts Used (behavioral)" link to registry files. Applicability comes from the CONTEXT block above, never from assumption (mechanical: **F10** section presence, **W7** identifier candidates). W7 silence is not a pass — it matches shapes, not prose
18. CONCURRENCY — concurrent-behavior requirements name deterministic observables testable with the detected deterministic test kit; wall-clock timing assertions FAIL

## Results

### Spec: [specs/<capability-1>/spec.md]

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | <!-- ✅/❌ --> | <!-- offending heading + what's missing --> |
| 1b | SHALL/MUST normative opener | | |
| 1c | Per-variant behavior-preservation scenarios | | |
| 2 | Then observable | | |
| 3 | Scenarios testable | | |
| 4 | Error paths specified | | |
| 5 | New concepts declared | | |
| 6 | Reused concepts resolved | | |
| 7 | Generator strategies | | |
| 8 | Temporal trigger/response | | |
| 9 | No vague words | | |
| 10 | Unreachable claims proven | | |
| 11 | Enum extension / type-widening behavior | | |
| 12 | Proof obligations complete (F6 Source format / F7 reachability) | | |
| 13 | Consumer-facing surface asserted | | |
| 14 | Error variants type-feasible | | |
| 15 | Adversarial scenarios for negatives | | |
| 16 | MUST-CONFIRM marks present | | |
| 17 | Altitude respected (F10 + W7 candidates judged) | | |
| 18 | Concurrency deterministic | | |

**Verdict: PASS / FAIL**

<!-- Repeat the table per spec. -->

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| <!-- specs/x/spec.md --> | <!-- PASS/FAIL --> | <!-- count + one-line summary --> |

<!-- Overall: implementation-order may only be generated when every spec is PASS. -->
