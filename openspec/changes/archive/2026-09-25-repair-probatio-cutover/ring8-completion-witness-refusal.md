# Ring 8: Adversarial Spec-Compliance Review — completion-witness-refusal

**Fresh context:** yes — isolated read-only subagent (agent_id `408db1c1`), inputs: spec + changed production files only; no implementation conversation.
**Baseline:** `f3fe75be44876852c1fbdfbc819cd46720980923`
**Diff reviewed:** spec + `WitnessVerdict.scala` (new), `GateDecisions.scala` (`corroborationVerdict`, `decideCompletion`), `ReconcileEngine.scala` (`uncorroborated`), `SubcommandEntrypoints.scala` (completion-tier rewiring), `GateKernel.scala` (kernel mirror)

**Dangerous patterns found:** 0 unjustified — the review independently confirmed `mergeVerdict`'s `case _ => b` can only see `(Witnessed, _)` pairs, and every `danger-scan:allow` site is a fail-open or unreachable-branch justification.
**Findings at review:** 3 MAJOR + 2 MINOR → **all 3 MAJOR remediated; both MINOR resolved by spec amendment / parity justification**
**Requirements:** 3 PASS / 7 PARTIAL / 0 FAIL at review → **all requirements PASS or declared-divergence-documented after remediation**

---

## Requirement-by-requirement verdicts (as reviewed → after remediation)

### Requirement: A turn is refused when a green result has no corroboration — PARTIAL → PASS

Reviewed PARTIAL: the pure `corroborationVerdict`/`decideCompletion` composition was correct, but the adapter silently converted two spec-reachable states (unresolvable baseline → `"unknown"` sentinel → `Witnessed`; absent record → `Witnessed`) into a clean allow with no stated reason, and the presentation-marker precondition was undocumented.

- Every-corroborated proceeds: PASS — `Witnessed` → `Allow`.
- Single uncorroborated green refuses and names it: PASS — first in-scope claim carried in `Unwitnessed(row)` and rendered via `scan.uncorroborated`.
- Non-green needs no corroboration: PASS — `exit != 0` → `Corroboration.Exempt`, excluded from `uncorroborated`.
- Unreadable record allows with a stated reason naming the record: PARTIAL → **PASS after remediation** — absent record now produces `Undeterminable("$ledger: no evidence record")` instead of a silent `Witnessed`; unparseable/invalid rows already produced `Undeterminable` naming the file.
- Presentation-marker precondition: **resolved by spec amendment** — the refusal is scoped to a turn that presents a completion claim (the `presentation-*` marker); mid-work stops pass silently. Recorded in the spec's new *Applicability* paragraph — predecessor behaviour, not a relaxation.

### Requirement: The corroboration check fails open and says so — PARTIAL → PASS

Reviewed PARTIAL: never refused on unread state (correct), but "record absent" and "baseline unresolvable" — two of `genUnreadableState`'s four enumerated conditions — silently allowed without naming the input.

- Unavailable state area allows with named reason: PASS (unchanged) — `ctx.stateDir == None` → allow + trace names STATE_DIR.
- Record absent: **remediated** — `Undeterminable` naming the ledger path (exit 0, parity preserved — the predecessor skips reconcile on an absent ledger and also exits 0 under a clean chain-state).
- Baseline unresolvable: **remediated** — `ledgerBaseline: Option[String]`; `None` inside `completionScanChange` now yields `Undeterminable` naming `git rev-parse --short HEAD` instead of the `"unknown"` sentinel that made every row stale-by-comparison and silently `Witnessed` — a discarded warrant.
- Unreadable/unparseable record: PASS (unchanged) — `Undeterminable` → `AllowUndetermined` carrying the same `UndeterminedReason`.

### Requirement: At most one refusal is issued per turn — PARTIAL → PASS

Reviewed PARTIAL: all three refuse paths discarded `writeRefusal`'s `Boolean` — a failed marker write left the turn unbounded, so the next attempt refused again, violating both the bound and the fail-open discipline `writeRefusal` documents ("the caller fails open rather than blocking without a bound").

- **Remediated**: all three paths now mirror the tool-call/grant tier idiom — `if !writeRefusal(...) then fail open (trace + Ran(0)) else refuse`. Marker write still happens before the refusal is emitted, so a recorded refusal and an unwritable bound can never coexist.
- First refusal issued / second attempt not refused / new turn refuses again: PASS — marker keyed on `session.encoded`.

### Property: refusal-iff-an-uncorroborated-green-result-exists — PARTIAL → PASS

Pure-layer iff held at review; the adapter could void it via the `"unknown"` baseline and the absent-record `Witnessed`. Both sentinel paths are now `Undeterminable`, so a warrant can no longer be laundered into a clean allow — the property holds end-to-end for every readable baseline.

### Property: unreadable-state-never-refuses — PARTIAL → PASS

`!isRefusal` held at review; `verdict.reason.namesInput` failed on record-absent and baseline-unresolvable. Both now carry stated `UndeterminedReason`s — all four `genUnreadableState` conditions name their input.

### Property: refusal-budget-is-bounded-and-nonzero — PARTIAL → PASS

The pure fold was correct; the shipped bound was voided by the discarded `writeRefusal`. Remediated (see requirement 3).

### Property: parity-with-predecessor — PASS (unchanged)

The declared stale-baseline divergence is implemented exactly as amended: `corroborationVerdict` filters `c.baseline == baseline`; `classify` is called unfiltered so stale rows exist but are out of scope. Post-remediation parity re-verified: `GateBannerCompatSpec` 9/9 — the absent-ledger `Undeterminable` and the writeRefusal fail-open both keep exit status 0 where the model is 0.

### Compile-Negative: two-variant witness verdict — PASS

`WitnessVerdict` is a closed 3-variant enum; `WitnessVerdict.Unknown` does not compile.

### Compile-Negative: refusal without offending row — PASS

`Unwitnessed(row: ClaimVerdict)` requires the row; `Unwitnessed()` does not compile.

### Contract: decideCompletion (Ring 6) — PASS

`GateKernel.decideCompletion` postcondition mirrors the spec verbatim: `isRefusal == (priorRefusals == 0 && warranted)`; a refusal carries an in-range index whose row satisfies the warrant (`fuSound`); `priorRefusals > 0 ==> !isRefusal`. `AllowUndetermined` correctly unmodelled — the kernel quantifies over already-read rows only. 480/480 VCs valid.

### Baseline representation — PASS

`gateBaseline` = long `rev-parse HEAD` → chain-state; `ledgerBaseline` = `rev-parse --short HEAD` → corroboration scope, matching the ambient writer's `--short` record.

### Decision ordering — PASS

`scan.undetermined` → `decision.isRefusal` → `scan.unresolved` → allow — the predecessor's order.

---

## Findings and dispositions

1. **MAJOR — remediated.** `ledgerBaseline` fell back to `"unknown"` when `rev-parse --short HEAD` failed; `corroborationVerdict` then found no row at baseline `"unknown"` and returned `Witnessed` → silent clean allow, discarding a genuine warrant. Now `Option[String]`; `None` inside `completionScanChange` produces `Undeterminable` naming the baseline resolution.
2. **MAJOR — remediated.** Absent ledger short-circuited to `Witnessed` — a silent allow with no stated reason, against `genUnreadableState`'s "record absent" condition. Now `Undeterminable("$ledger: no evidence record")`. Exit status unchanged (0); parity preserved — the predecessor skips reconcile on an absent ledger.
3. **MAJOR — remediated.** All three refuse paths discarded `writeRefusal`'s Boolean; a failed marker write left the turn unbounded (second attempt refuses again). Now the tool-call-tier idiom: failed write → trace + `Ran(0)` (fail open); the marker is written before the refusal is emitted.
4. **MINOR — resolved by spec amendment.** `hasSessionPresentation` gates the whole refusal: a turn with a warrant but no presentation marker allows. Deliberate predecessor parity (mid-work stops pass silently); the spec text never carved it out. Recorded in the spec's *Applicability* paragraph.
5. **MINOR — recorded, predecessor-exact.** `activeChangeDirs` swallows `NonFatal` to `List.empty` → `CompletionScan.Empty` → clean allow. The shared helper's contract serves every tier; the predecessor's `changes/*/` glob on an unenumerable directory iterates nothing — identical silent allow. Changing the helper's signature would blast across all tiers for zero parity gain. Recorded in the *Applicability* paragraph.

## Non-findings verified by the reviewer

- `mergeVerdict`: `Unwitnessed` > `Undeterminable` > `Witnessed`, first `Unwitnessed` wins; the `case _ => b` arm only sees `(Witnessed, _)`.
- `uncorroborated` = testimony ∪ contradicted in record order; red rows are `Exempt`, never warrants.
- `decideCompletion(verdict, RefusalBudget.full)` with `full` is sound because `hasRefusal` short-circuits prior refusals first.
- `Undeterminable` → `AllowUndetermined` never writes the refusal marker and allows under a spent budget — the budget is not consumed.
- `scan.uncorroborated` is non-empty whenever `decision.isRefusal` (the refusal always renders the report detail).

**Verdict: FINDINGS-0-BLOCKERS after remediation.**
