# Spec Lint Report

Change: `add-correctness-substratum` · 6 specs · linted 2026-08-08

## Mechanical pre-pass

**openspec validate --strict**: `Change 'add-correctness-substratum' is valid` — PASS

**spec-lint.sh**: `spec-lint: 6 spec file(s), 0 FAIL, 23 WARN`

Warning breakdown: **23 × W3** (adversarial confirmation candidates — see
check 15), **0 × W1** (two vague-word candidates found and fixed during
authoring, see check 9), **0 × W7** (see check 17), **0 × W2/W4/W5/W6**.

W6 fired 5× on the first pass and was **correct**: the specs retained
`## Formal Contracts (Ring 6)`, `## Temporal Properties (Ring 9)` and
`## Compile-Negative Obligations` sections carrying an explicit "not
applicable" verdict. The spec template says to delete a section that does not
apply; the "silence is not a verdict" rule that motivated retaining them
governs the **design** artifact's Ring 6 triage table, not the spec. Sections
removed; the Ring 6 verdicts are carried into `design.md` instead. W6 clear.

### Applicability (CONTEXT block, verbatim)

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v11
  behavioural registry  openspec/concepts/             PRESENT (31 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
       F10 checks the structural half; W7 lists code-identifier candidates;
       reading the clause prose for behavioural altitude is still your job.
  type inventory        openspec/concept-inventory.md  PRESENT (132 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

| Conditional check | CONTEXT says | Verdict allowed |
|---|---|---|
| 3 · testable with detected stack | capability profile PRESENT | **APPLIES** — judged per spec below |
| 6 · reused concepts resolved | type inventory PRESENT (132 rows) | **APPLIES** — every spec declares zero reused concepts, which resolves trivially and is verified, not assumed |
| 17 · ALTITUDE | registry PRESENT (31 concepts) | **APPLIES** — N/A not available; prose judged below |
| 18 · CONCURRENCY | TestControl detected | **APPLIES** — judged below; the detected kit is Scala-only and no requirement here concerns concurrent execution |

### Two stack notes bearing on checks 3 and 18

**Check 3 — the detected property framework does not cover this change.**
Hedgehog is Scala-only; these specs ship bash. Every Properties section
therefore declares its enumeration explicitly and states that a "property"
here is enumerated over a listed finite domain in bats rather than sampled
from a generator, with unbounded domains reduced to a stated finite subset.
This is weaker than Ring 3 on the Scala side. It is declared in each spec
rather than left implied, so the oracle is not misstated.

**Check 3 — a testability boundary exists at the harness seam.** Several
scenarios concern what the *harness* does (fires an event, delivers findings,
honours a refusal, compacts context), which no shell test can observe. Found
during this lint and **fixed in the specs**: `gate-payload` and `hook-tiers`
each gained explicit manual-verification obligations naming the boundary and
pointing at the per-harness procedure already documented for this gate. Before
that fix these scenarios had no declared enforcement, which would have been a
check 12 failure discovered at implementation.

**Check 18 — no requirement in any spec concerns concurrent execution,
timeouts, cancellation, or interruption.** This is a finding, not an N/A: each
spec's requirements were read against the concurrency trigger and none match.
The nearest candidates were examined and rejected — per-turn fingerprint
suppression (`gate-payload`) is sequential within a session, and multiple
active changes (`evidence-ledger`) is a scoping concern, not a concurrency
one. Both are specified by deterministic observables regardless.

## Results

### Spec: specs/correctness-invariant/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 4 requirements, all with Given/When/Then |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | No behavior-preservation-over-enum requirement present |
| 2 | Then observable | ✅ | Outcomes are file content and text presence — readable from disk |
| 3 | Scenarios testable | ✅ | All 9 scenarios assert file content; bats-testable in full |
| 4 | Error paths specified | ✅ | Each requirement carries a failing-condition scenario |
| 5 | New concepts declared | ✅ | Correctness Invariant, Prerequisite Set |
| 6 | Reused concepts resolved | ✅ | Declares none; no Scala type read or written |
| 7 | Generator strategies | ✅ | F3 clean; 2 properties, both enumerations stated |
| 8 | Temporal trigger/response | ✅ | No temporal properties (section removed per template) |
| 9 | No vague words | ✅ | W1 clear |
| 10 | Unreachable claims proven | ✅ | No unreachability claim made |
| 11 | Enum extension / type-widening | ✅ | No type changes; no public Scala type touched |
| 12 | Proof obligations complete | ✅ | F4/F6/F7/F8 clean; 8 obligations, every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | No consumer-facing surface in this spec |
| 14 | Error variants type-feasible | ✅ | No error variants asserted |
| 15 | Adversarial scenarios for negatives | ✅ | 4 W3 candidates confirmed — each has a scenario whose input the requirement forbids (absent artifact; carried-over verdict; superseded-rule assertion; undated limitation) |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced table; the prerequisite set is a repository decision recorded in capability-check |
| 17 | Altitude respected | ✅ | F10 present; W7 zero. Prose read: clauses use "the workflow definition", "the hook documentation", "the session-gate script header" — identifiers confined to Implementation Anchors |
| 18 | Concurrency deterministic | ✅ | No concurrent-behaviour requirement |

**Verdict: PASS**

### Spec: specs/evidence-ledger/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 5 requirements, all complete |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | None applicable |
| 2 | Then observable | ✅ | File content, field values, exit status |
| 3 | Scenarios testable | ✅ | All 13 scenarios bats-testable; no harness seam in this spec |
| 4 | Error paths specified | ✅ | Missing field, modification attempt, truncation, absent file, stale baseline all specified |
| 5 | New concepts declared | ✅ | Evidence Ledger, Ledger writer |
| 6 | Reused concepts resolved | ✅ | Declares none |
| 7 | Generator strategies | ✅ | F3 clean; 4 properties, each with an enumerated domain and named edge cases |
| 8 | Temporal trigger/response | ✅ | None |
| 9 | No vague words | ✅ | One W1 found ("correct field count") and rewritten to "field count equals the number of fields supplied" |
| 10 | Unreachable claims proven | ✅ | None claimed |
| 11 | Enum extension / type-widening | ✅ | No type changes |
| 12 | Proof obligations complete | ✅ | 12 obligations; every requirement named; two limits recorded explicitly as manual review rather than overstated |
| 13 | Consumer-facing surface asserted | ✅ | The ledger format is consumed by chain-state and the checkpoint generator; the round-trip property asserts field-level observation, not merely presence |
| 14 | Error variants type-feasible | ✅ | Failures are non-zero exit plus a named field — feasible for a shell entry point |
| 15 | Adversarial scenarios for negatives | ✅ | 4 W3 candidates confirmed — rejection of incomplete rows, refusal of modification, exclusion of another change's rows, non-discharge of stale rows |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced values |
| 17 | Altitude respected | ✅ | F10 present; W7 zero. Clauses say "a verification step", "the ledger", "evidence is read"; filenames confined to Implementation Anchors |
| 18 | Concurrency deterministic | ✅ | Multi-change scoping examined and found to be a scoping concern, not concurrency |

**Verdict: PASS**

### Spec: specs/chain-state/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 4 requirements, all complete |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | None applicable |
| 2 | Then observable | ✅ | Report counts, list membership, exit status |
| 3 | Scenarios testable | ✅ | All 10 scenarios bats-testable against constructed changes |
| 4 | Error paths specified | ✅ | Corrupt ledger, failed lint, and the empty-vs-undetermined distinction all specified |
| 5 | New concepts declared | ✅ | Chain State |
| 6 | Reused concepts resolved | ✅ | Declares none |
| 7 | Generator strategies | ✅ | F3 clean; 3 properties with enumerated construction sets |
| 8 | Temporal trigger/response | ✅ | None |
| 9 | No vague words | ✅ | W1 clear |
| 10 | Unreachable claims proven | ✅ | None claimed |
| 11 | Enum extension / type-widening | ✅ | No type changes |
| 12 | Proof obligations complete | ✅ | 10 obligations; the no-duplicate-implementation obligation is correctly declared manual, since it is a property of source rather than of output |
| 13 | Consumer-facing surface asserted | ✅ | The report is consumed by the gate payload and the checkpoint; its shape is asserted by scenario and property |
| 14 | Error variants type-feasible | ✅ | Non-zero exit plus an undetermined report — feasible |
| 15 | Adversarial scenarios for negatives | ✅ | 3 W3 candidates confirmed — lone-verdict emission forbidden, disagreement with the lint forbidden, undetermined-as-zero forbidden |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced values |
| 17 | Altitude respected | ✅ | F10 present; W7 zero. Clauses say "the specification lint", "evidence", "chain state" |
| 18 | Concurrency deterministic | ✅ | No concurrent-behaviour requirement |

**Verdict: PASS**

### Spec: specs/gate-payload/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 5 requirements, all complete |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | None applicable |
| 2 | Then observable | ✅ | Payload text, injection occurrence, exit status, heartbeat record |
| 3 | Scenarios testable | ✅ **with a declared boundary** | 11 of 13 scenarios are bats-testable from the script. Two — "facts are available after compaction" and harness invocation — are harness properties. **Fixed during this lint**: both now carry explicit manual-verification obligations naming the boundary and the per-harness procedure. Previously unenforced |
| 4 | Error paths specified | ✅ | Failing chain-state tool, non-workflow repository, unwritable heartbeat all specified |
| 5 | New concepts declared | ✅ | Gate Heartbeat |
| 6 | Reused concepts resolved | ✅ | Declares none |
| 7 | Generator strategies | ✅ | F3 clean; 4 properties, each enumerated with escaping edge cases named |
| 8 | Temporal trigger/response | ✅ | None |
| 9 | No vague words | ✅ | W1 clear |
| 10 | Unreachable claims proven | ✅ | None claimed |
| 11 | Enum extension / type-widening | ✅ | No type changes |
| 12 | Proof obligations complete | ✅ | 15 obligations after this lint's additions; every requirement named; payload-size judgement correctly declared manual rather than given an arbitrary threshold |
| 13 | Consumer-facing surface asserted | ✅ | **This is the change's principal consumer-facing surface** — the payload the model reads. Asserted at field level by the contents property and the cross-format property, not merely by presence |
| 14 | Error variants type-feasible | ✅ | The gate always exits zero by requirement; undetermined evidence is reported in payload text, which is feasible |
| 15 | Adversarial scenarios for negatives | ✅ | 5 W3 candidates confirmed — position-only payload forbidden, repeated identical injection forbidden, session failure forbidden, silent non-invocation forbidden |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced values |
| 17 | Altitude respected | ✅ | F10 present; W7 zero. Clauses say "the session gate", "the payload", "a prompt is submitted"; adapter filenames confined to Implementation Anchors |
| 18 | Concurrency deterministic | ✅ | Suppression is sequential within a session; the scenarios assert injection occurrence, never timing |

**Verdict: PASS**

### Spec: specs/checkpoint-from-ledger/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 4 requirements, all complete |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | None applicable |
| 2 | Then observable | ✅ | Rendered summary content, checklist file content |
| 3 | Scenarios testable | ✅ | All 9 scenarios bats-testable against constructed ledgers and trackers |
| 4 | Error paths specified | ✅ | Missing rows, superseded baseline, divergent checklist all specified |
| 5 | New concepts declared | ✅ | Generated Checkpoint |
| 6 | Reused concepts resolved | ✅ | Declares none |
| 7 | Generator strategies | ✅ | F3 clean; 3 properties, each enumerated |
| 8 | Temporal trigger/response | ✅ | None |
| 9 | No vague words | ✅ | W1 clear |
| 10 | Unreachable claims proven | ✅ | None claimed |
| 11 | Enum extension / type-widening | ✅ | No type changes |
| 12 | Proof obligations complete | ✅ | 11 obligations; the forgery limit is recorded as an accepted limit rather than claimed solved |
| 13 | Consumer-facing surface asserted | ✅ | The checkpoint summary is read by a human at the approval gate; its content is asserted by scenario and by the trace-to-row property |
| 14 | Error variants type-feasible | ✅ | Unevidenced is a render state, not a typed error |
| 15 | Adversarial scenarios for negatives | ✅ | 3 W3 candidates confirmed — free-text outcomes forbidden, superseded rows forbidden, divergent checklist not merged |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced values |
| 17 | Altitude respected | ✅ | F10 present; W7 zero. Clauses say "the checkpoint", "ledger rows", "the progress tracker" |
| 18 | Concurrency deterministic | ✅ | No concurrent-behaviour requirement |

**Verdict: PASS**

### Spec: specs/hook-tiers/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 5 requirements, all complete |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | None applicable |
| 2 | Then observable | ✅ | Returned findings, file content, refusal occurrence |
| 3 | Scenarios testable | ✅ **with a declared boundary** | 14 of 16 scenarios bats-testable from the script. Whether the harness delivers findings and honours a refusal is outside the script's observation. **Fixed during this lint**: two manual-verification obligations added naming that boundary |
| 4 | Error paths specified | ✅ | Unavailable check, errored check, undetermined evidence, repeated attempt all specified |
| 5 | New concepts declared | ✅ | Post-edit correction tier, Completion gate |
| 6 | Reused concepts resolved | ✅ | Declares none; the two invoked checks are existing scripts, cited in Implementation Anchors as unchanged |
| 7 | Generator strategies | ✅ | F3 clean; 4 properties, each enumerated, including a path set with a space-containing path |
| 8 | Temporal trigger/response | ✅ | None |
| 9 | No vague words | ✅ | One W1 found in a rationale ("cheapest to correct") and rewritten |
| 10 | Unreachable claims proven | ✅ | None claimed |
| 11 | Enum extension / type-widening | ✅ | No type changes |
| 12 | Proof obligations complete | ✅ | 16 obligations after this lint's additions; the claim-detection limit is recorded explicitly, with bounded refusal as its stated mitigation |
| 13 | Consumer-facing surface asserted | ✅ | Returned findings and refusal text are what the agent observes; both asserted by scenario |
| 14 | Error variants type-feasible | ✅ | Refusal carries a reason distinguishing unresolved from undetermined — feasible as script output |
| 15 | Adversarial scenarios for negatives | ✅ | 4 W3 candidates confirmed — edit rejection forbidden, non-triggering path asserted, completion-without-claim permitted, undetermined-permits-completion forbidden |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced values |
| 17 | Altitude respected | ✅ | F10 present; W7 zero. Clauses say "a specification file of an active change", "a production source file", "turn completion" |
| 18 | Concurrency deterministic | ✅ | Refusal bounding is per-turn sequential; no timing assertion anywhere |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/correctness-invariant/spec.md | **PASS** | none |
| specs/evidence-ledger/spec.md | **PASS** | none |
| specs/chain-state/spec.md | **PASS** | none |
| specs/gate-payload/spec.md | **PASS** | none — testability boundary declared and enforced by added obligations |
| specs/checkpoint-from-ledger/spec.md | **PASS** | none |
| specs/hook-tiers/spec.md | **PASS** | none — as above |

**All 6 PASS. `design.md` and `implementation-order.md` may be generated.**

## Corrections made during this lint

Recorded because a lint that silently repairs what it checks is not a lint.

| # | Found | Fix |
|---|-------|-----|
| 1 | W6 ×5 — Ring 6 / Ring 9 / compile-negative sections retained with an "N/A" verdict against the template's instruction to delete | Sections removed from all 6 specs; Ring 6 verdicts carried to `design.md`'s triage table, which is where the "silence is not a verdict" rule actually applies |
| 2 | W1 — `evidence-ledger` asserted a row parses "with the **correct** field count" | Rewritten to "field count equals the number of fields supplied … original field values recoverable on read". A spec defining correctness cannot use the word vaguely |
| 3 | W1 — `hook-tiers` rationale used "correct" as a verb twice next to "a valid domain value" | Rewritten to "repair" and "a value the domain accepts, so the failure is silent" |
| 4 | Check 3 — 4 scenarios across `gate-payload` and `hook-tiers` assert **harness** behaviour that no shell test can observe, and had no declared enforcement | 4 manual-verification obligations added, each naming the boundary and pointing at the per-harness procedure already documented for this gate |

## Re-lint after design (2026-08-08)

`design.md` Decision 1 changed the ledger record format from tab-separated to
JSON lines, which amends `evidence-ledger` — a spec already passed at this
gate. Re-linted rather than applied silently, since the spec is a commitment.

Amendment: one scenario renamed and generalised, two generator strategies
restated over framing characters, one **new requirement added** (every row
carries a format version; an unrecognised version halts the read as
undetermined rather than skipping rows), three obligations added, anchors
updated. No case removed, no generator narrowed — the awkward-value
enumeration survives intact as JSON string content, so this is not oracle
tampering.

**F8 caught a dangling reference introduced by the amendment**: the scenario
was renamed but an obligation `Source` still cited the old heading. This is
exactly the failure F8 was added for in schema v9, and it was invisible to
reading — the row looked well-formed. Fixed.

```
spec-lint: 6 spec file(s), 1 FAIL, 23 WARN   ← F8 dangling Source
spec-lint: 6 spec file(s), 0 FAIL, 23 WARN   ← after fix
openspec validate --strict: valid
```

Stale-reference sweep across the whole change directory: zero remaining
references to the superseded format outside `design.md`'s decision record,
where they are the subject.

**Verdict unchanged: all 6 PASS.**

## Standing limitations carried into design

Not defects; declared so they are not later mistaken for coverage.

1. **Property testing is enumerated, not sampled.** No generator or shrinker
   exists for bash. Each property states its finite domain; unbounded domains
   are stated finite subsets. Weaker than Ring 3 on the Scala side.
2. **The harness seam is manually verified**, once per supported harness,
   using the heartbeat plus the documented procedure. Four obligations.
3. **The ledger is forgeable in-process.** Recording command and exit status
   makes a row re-checkable; it does not make it unforgeable. Recorded as an
   accepted limit in two specs rather than claimed solved.
4. **Completion-claim detection reads turn text and is approximate.** It will
   miss paraphrases and may fire on discussion of completion. Bounded refusal
   (at most once per turn) is the stated mitigation.
