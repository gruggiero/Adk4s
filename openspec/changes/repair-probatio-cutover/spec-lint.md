# Spec Lint Report

Eleven spec files under `specs/`. **Verdict: all PASS.** No spec is blocked from design or
implementation-order.

## Mechanical pre-pass

**1. `openspec validate --changes --strict`** — **PASS**, `1 passed, 0 failed`, zero
issues reported.

One issue was found and fixed during this pass: `gate-event-compatibility`'s requirement
*"The supplied name survives into the diagnostic output"* was rejected with *"must contain
SHALL or MUST"* although its normative statement contained SHALL. The statement began with
the word "When" at line start, which the validator reads as a scenario clause. Rephrased to
lead with the subject; the requirement's meaning is unchanged.

**2. `scanner/spec-lint.sh openspec/changes/repair-probatio-cutover`** —
`spec-lint: 11 spec file(s), 0 FAIL, 42 WARN`.

All 42 warnings are **W3** (adversarial-confirmation candidates). Each is confirmed below
under check 15. Zero F-checks failed: no F6 (unresolvable obligation source), no F7
(unenforced requirement), no F8 (dangling typed reference), no F10 (missing behavioural
concepts section), no W1 (vague words), no W4 (positional references), no W5
(impossibility enforced only by tests), no W6 (Ring 6 without a bridge), no W7 (code
identifiers in clauses).

Three issues were found and fixed during this pass, before the run above:

- Two obligation `Source` cells in `differential-harness-integrity` used the kind
  `Formal Contract:` and the bare text `Type-Widening Impact`, neither of which is a
  resolvable typed reference (F6). Re-sourced as `Invariant:` and `Type-Constraint:`.
- Four obligation `Artifact` cells named suites that do not exist as tracked files (F9,
  run here with `--artifacts`). Re-pointed at the tracked suites the work extends.
- `unported-tool-register` declared a Formal Contracts section whose stated-skip prose was
  long enough to read as a contract declaration without a bridge artifact (W6). Shortened
  to a two-line skip; the section still states why no contract applies.

**3. CONTEXT block — copied verbatim from the run:**

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v14
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/git/rs/adk4s/.claude/skills carries a pre-rename stamp (verified-scala3-schema/13), this schema is v14.
     Re-install (scanner/install-skills.sh) before trusting this report.
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/git/rs/adk4s/.pi/skills carries a pre-rename stamp (verified-scala3-schema/13), this schema is v14.
     Re-install (scanner/install-skills.sh) before trusting this report.
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/.zcode/skills carries a pre-rename stamp (verified-scala3-schema/13), this schema is v14.
     Re-install (scanner/install-skills.sh) before trusting this report.
  behavioural registry  openspec/concepts/             PRESENT (37 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
       F10 checks the structural half; W7 lists code-identifier candidates;
       reading the clause prose for behavioural altitude is still your job.
  type inventory        openspec/concept-inventory.md  PRESENT (370 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

**Applicability, read from that block — not inferred**: checks **3**, **6**, **17** and
**18** all **APPLY**. None of them is recorded N/A anywhere in this report.

**Standing caveat the block itself raises**: the instruction documents installed at three
roots carry a pre-rename stamp, and the block says to re-install before trusting this
report. The stamps are stale **content**, not a stale lint engine — this report's verdicts
come from the engine and from reading the specs, neither of which the installed documents
affect. The staleness is itself a finding this change fixes
(`specs/schema-rename-completion/spec.md`), and the fix depends on the installer reaching
surface parity (`specs/install-tool-surface-parity/spec.md`). Recorded here rather than
dismissed.

## Per-check results

Columns are the eleven specs in implementation order: **1** differential-harness-integrity,
**2** chain-state-undetermined-fidelity, **3** completion-witness-refusal, **4**
gate-event-compatibility, **5** graph-tool-port, **6** feature-freeze-guard-integrity,
**7** install-tool-surface-parity, **8** ledger-checkpoint-cutover, **9**
workflow-delivery-hygiene, **10** schema-rename-completion, **11** unported-tool-register.

| # | Check | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |
|---|-------|---|---|---|---|---|---|---|---|---|----|----|
| 1 | Concrete Given/When/Then | P | P | P | P | P | P | P | P | P | P | P |
| 1b | SHALL/MUST before Given | P | P | P | P | P | P | P | P | P | P | P |
| 1c | Per-variant scenarios for "same behaviour" claims | P | P | P | P | P | P | P | P | P | P | P |
| 2 | Every Then observable | P | P | P | P | P | P | P | P | P | P | P |
| 3 | Testable with detected stack | P | P | P | P | P | P | P | P | P | P | P |
| 4 | Error paths specified | P | P | P | P | P | P | P | P | P | P | P |
| 5 | New concepts in Concepts Introduced | P | P | P | P | P | P | P | P | P | P | P |
| 6 | Reused concepts exist in inventory | P | P | P | P | P | P | P | P | P | P | P |
| 7 | Generator strategy per property | P | P | P | P | P | P | P | P | P | P | P |
| 8 | Temporal trigger/response | — | — | — | — | — | — | — | — | — | — | — |
| 9 | No vague words | P | P | P | P | P | P | P | P | P | P | P |
| 10 | Unreachability claims have type proof | P | P | P | P | P | P | P | P | P | P | P |
| 11 | Enum extension states match behaviour | P | P | P | P | P | P | P | P | P | P | P |
| 12 | Proof Obligations cover every requirement | P | P | P | P | P | P | P | P | P | P | P |
| 13 | Consumer surface asserted | — | — | — | — | P | — | P | — | — | — | — |
| 14 | Error-variant type feasibility | P | P | P | P | P | P | P | P | P | P | P |
| 15 | Adversarial scenario per negative requirement | P | P | P | P | P | P | P | P | P | P | P |
| 16 | MUST-CONFIRM on external value domains | P | P | P | P | P | P | P | P | P | P | P |
| 17 | ALTITUDE — behavioural vocabulary in clauses | P | P | P | P | P | P | P | P | P | P | P |
| 18 | Concurrency — deterministic observable | P | P | P | P | P | — | — | P | P | — | — |

`P` = pass. `—` = the check has no subject in that spec (no temporal property, no
consumer-facing surface, no concurrent behaviour). `—` is used only for checks the CONTEXT
block does not mark as applying, or for applying checks with nothing in that spec to
judge; checks 3, 6, 17 and 18 all apply and none is recorded N/A.

## Notes per check where the verdict needed judgment

**Check 1c — per-variant coverage.** Four specs make same-behaviour claims over a variant
set. Each covers every variant rather than one: `gate-event-compatibility` scenarios say
"each of the six recognised event names in turn" and its enumerated property loops the
closed set; `ledger-checkpoint-cutover` says "for each clause in turn"; `schema-rename-
completion`'s classification property enumerates all four root states; `differential-
harness-integrity`'s bijection property enumerates the full seam set. None relies on an
aggregate count.

**Check 3 — testability (APPLIES per CONTEXT).** Every property is Hedgehog-shaped with an
explicit generator; every scenario is munit- or bats-shaped. Three obligations are
**manual and say so**: the predecessor-control reproduction in spec 1, and the two
continuous-integration job behaviours in spec 9. Each names a real recorded run rather
than a test suite, which the instruction permits provided it is explicit. They are the
only three.

**Check 4 — error paths.** Nine specs carry a scenario labelled "Error path". Two do not,
and both specify failure behaviour without that label: `gate-event-compatibility` makes
dispatch total, so there is no unspecified failure mode left — the fallback *is* the
error path, and the empty-name case covers the degenerate input; `feature-freeze-guard-
integrity` specifies failure through its not-found resolution and its
could-not-determine requirement. Recorded as pass on the substance, not on the label.

**Check 6 — reused concepts (APPLIES per CONTEXT).** Every concept named in every spec's
"Concepts Used (from inventory)" table was verified present in
`openspec/concept-inventory.md` by direct grep during this pass, not assumed. Seven
concepts that specs 1 and 8 reuse were **missing** from the inventory and were added by
this change's inventory-check with their originating spec's provenance; two more had a
wrong kind recorded and were corrected. `PortBlocker` returns zero inventory rows, which
is correct — it is introduced by spec 11, not reused.

**Check 9 — vague words.** W1 reported nothing, and a direct grep of every Given/When/Then
clause across all eleven specs for the five banned words returned nothing. Where a spec
needed a fuzzy-sounding notion it defined it: "safely quoted" is defined by an enumerated
character set, "corroborated" by the presence of an independent observation.

**Check 11 — enum extensions.** Two specs widen a public enum: spec 1 adds two seam
variants, spec 5 adds one subcommand variant. Both carry a "Type-Widening Impact"
subsection naming the affected matches and stating that each must handle the new variants
exhaustively rather than absorb them into a catch-all — with the compiler's exhaustiveness
escalation (an error, not a warning, on the probatio modules) as the enforcing mechanism.
The other nine specs state explicitly that they widen nothing.

**Check 13 — consumer surface.** Two specs change a caller-facing tool surface. Spec 5
carries "Consumer surface — the help output names the five operations and their
arguments"; spec 7 carries two, one per installer. Both assert the argument surface, not
merely the tool's presence.

**Check 15 — adversarial rule (the 42 W3 warnings).** Every requirement the engine flagged
as negative was checked by hand for a scenario whose input it forbids. All 42 are
confirmed: each flagged requirement carries at least one scenario headed "Adversarial"
whose input the requirement forbids — for example spec 1's "identical arms yield a
refusal, not Proceed", spec 2's "a pre-pass exiting outside its outcome range emits no
counts", spec 3's "a second attempt in the same turn is not refused", spec 11's "a tool in
neither is reported". No requirement is pinned by positive examples alone.

**Check 16 — MUST-CONFIRM.** One external value domain was found during this pass and was
**not** marked: the names each harness uses for its own hook events, asserted by
`gate-event-compatibility`. Those names are set by each harness's hook API, not by this
repository. A `MUST-CONFIRM` block was added to that spec naming the authoritative source
(each harness's own hook documentation) and the in-repository record that the scenarios
assert against (the three shipped adapter configurations and the executable envelope
contract). No other spec asserts a value domain owned outside the repository — the
predecessor implementations, the record contract and the oracle corpus are all in-tree.

**Check 17 — ALTITUDE (APPLIES per CONTEXT; W7 silence is not a pass).** W7 reported no
candidates, and a direct grep of every Given/When/Then clause for backticks, file
extensions, paths and camelCase identifiers returned nothing. Beyond the shapes, the clause
prose was read for behavioural altitude: clauses speak of "the correctness verdict", "the
mechanical pre-pass", "the injection tier", "a forwarding script", "the evidence record" —
behavioural vocabulary, with every code identifier confined to each spec's "Implementation
Anchors" table. Each spec carries a "Concepts Used (behavioral)" section citing the
migration-protocol concept file, and two also cite the conformance-contract concept.

**Check 18 — concurrency (APPLIES per CONTEXT).** Six specs involve concurrent or
subprocess behaviour: spec 1 spawns two full acceptance suites, spec 2 observes a
sub-tool's termination, spec 3 observes a bounded refusal across a turn sequence, spec 4
and spec 5 run the predecessor as a subprocess model, spec 8 observes recorded run
outcomes, spec 9 invokes forwarding scripts. Every one names a **deterministic
observable** — an exit status, an emitted output set, a final file-tree digest, a refusal
count — and none asserts a wall-clock bound.

The CONTEXT block names `TestControl` as the detected deterministic kit. **It is not
usable here**, and this is a stated substitution rather than a waiver: `TestControl` ships
with cats-effect, which the workflow modules' architecture rule forbids on their
classpath. The substitute is the one the existing probatio specs already use — recorded
process outcomes and an injected clock seam, never wall-clock sleeps. This was verified
against the build during this change's capability-check, which corrected the profile's
previously unqualified claim that concurrency scenarios use `TestControl`.

## Verdict

| Spec | Verdict |
|------|---------|
| `specs/differential-harness-integrity/spec.md` | **PASS** |
| `specs/chain-state-undetermined-fidelity/spec.md` | **PASS** |
| `specs/completion-witness-refusal/spec.md` | **PASS** |
| `specs/gate-event-compatibility/spec.md` | **PASS** |
| `specs/graph-tool-port/spec.md` | **PASS** |
| `specs/feature-freeze-guard-integrity/spec.md` | **PASS** |
| `specs/install-tool-surface-parity/spec.md` | **PASS** |
| `specs/ledger-checkpoint-cutover/spec.md` | **PASS** |
| `specs/workflow-delivery-hygiene/spec.md` | **PASS** |
| `specs/schema-rename-completion/spec.md` | **PASS** |
| `specs/unported-tool-register/spec.md` | **PASS** |

No FAIL. Design and implementation-order may proceed.
