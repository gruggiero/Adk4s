# Ring 8: Adversarial Spec-Compliance Review — chain-state-undetermined-fidelity

**Fresh context:** yes — isolated read-only subagent (agent_id `3d744b78`), inputs: spec + contract + baseline diff only; no implementation conversation.
**Baseline:** `523ceb8b362200438484123da753602817367104`
**Diff reviewed:** 21 tracked files + 6 new spec-2 files (`git diff 523ceb8b…`; confirmed by parent — core `PrePassOutcome`/`ChainState`/`ChainStateReport`/`RepositoryFacts`, cli `ChainStatePrePass`/`SubcommandEntrypoints`/`StdoutRenderer`/`RepositoryFactsReader`, verified `ChainStateKernel`, and the spec-2 test/contract files)

**Dangerous patterns found:** ~10 reviewed (0 unjustified — every flagged site is a fail-toward-undetermined rejection path or carries a same-line `danger-scan:allow`)
**Oracle tampering:** one generator narrowing found (parity corpus) — **remediated**, see below
**Requirements:** 34 PASS / 1 PARTIAL / 0 FAIL at review → **35 PASS / 0 PARTIAL / 0 FAIL after remediation**

---

## Requirement-by-requirement verdicts (as reviewed)

### Requirement: A verdict is produced only from a completed pre-pass — PASS

`ChainState.compute` (`ChainState.scala:100–118`) matches `PrePassOutcome` first: `DidNotRun` → `Left(ChainStateUndetermined(change, baseline, reason))` before ledger/reqs/baselines are consulted. `Completed` is the only path into `computeCompleted`, the only caller of `ChainStateReport.from` — which takes `PrePassOutcome.Completed` as its first parameter. `ChainStateUndetermined` has no count fields: counts are unrepresentable, not merely nulled.

- Happy path, cannot-execute, exit-outside-range, and non-completed-cannot-produce-a-report scenarios all PASS (cli suite lines 280–342; compile-negative at `ChainStateCompileNegative.scala:20–30`).
- `private[probatio] fromCounts` bypasses the `Completed` gate inside `org.sinemenda.probatio.*` but is required there for wire reconstruction (`RepositoryFactsReader.scala:576`) and still enforces every count/monotonicity clause. Documented in the type contract. Justified.
- Noted honestly: `PrePassOutcome.Completed` is a public enum case — a caller can fabricate `Completed(Map.empty)`; the type proves "not DidNotRun", not "a real run happened". Inherent to a closed enum; the spec's forcing function is exactly the compile error on `DidNotRun`. Not scored against.

### Requirement: Every distinct could-not-determine reason is named — PASS

`UndeterminedReason` opaque type: `of` rejects empty; `stated` maps empty → `unclassifiable = "the input under inspection"` — the spec-mandated fallback (the adversarial scenario requires an unclassified cause to still name the input). Every `probe` `Left` names the unavailable input and its path. `emitUndetermined` can only emit an `UndeterminedReason`.

- Unreadable evidence record names the file (ledger-read `Left` carries the path; test asserts `reason.contains("missing-ledger.jsonl")`).
- Empty readable record is a measurement (Finding, `discharged == 0`) — matches bats test 12.
- Unclassified cause still names the input (`stated` fallback; four-termination-shape test asserts non-empty named reasons).
- Tension noted (not a violation): with `specBaselines.nonEmpty`, a corrupt ledger is *tolerated* (measured undischarged report, exit 1) — the predecessor's own per-spec read behaviour, mandated by the parity property and itself tested (`baseline-map-corrupt-ledger` fixture). The scenario is honoured in the applicable (empty-map) case.

### Requirement: The exit status distinguishes could-not-determine from a finding — PASS

`Outcome.toExitCode`: Ran→0, Finding→1, Undetermined→2 — disjoint variants, collapse impossible. `emitReport` returns `Ran(0)` only when `unresolved` and `unmappedObligations` are both empty. Satisfied-change-exits-clean and uncomputable-not-finding scenarios tested.

### Property: no-counts-without-a-completed-pre-pass — PASS

Constructive generator (70/30 Completed/DidNotRun), coverage thresholds enforced, rendered JSON checked for non-null count fields, monotonicity + `unresolved.length == total - discharged` asserted on every `Right`.

- Minor deviation (cosmetic): `genDidNotRun` draws `"spec-lint absent"` shorthand plus `namedInputReasons.drop(1)` — the first canonical reason is never drawn. Invariant is reason-agnostic.
- Literal-text note: undetermined JSON carries `"total": null` — the predecessor's `die_undetermined` shape verbatim; the spec's "absent, not zero" intent is no count *figure*. Consistent.

### Property: outcome-status-is-total-and-disjoint — PASS

Constructive `genVerdictInput` over 5 stub kinds × 4 ledger kinds × 2 baseline kinds with coverage thresholds; expected status recomputed from ground truth; finding ⇒ non-empty unresolved/unmapped; clean ⇒ both empty.

### Property: parity-with-predecessor-on-the-undetermined-boundary — PARTIAL → **PASS after remediation**

Mechanism correct and strong (40-case Cartesian corpus, predecessor `.bak` run as the model per case, normalised JSON + exit code compared). PARTIAL because the corpus narrowed the declared generator:

1. requirement count 0–3 declared → only {0,1} shapes drawn;
2. no "completes with findings" pre-pass behaviour — the `Real` stub only exercises "completes clean" (fixture specs are lint-clean); an implementation misclassifying exit-1-with-marker would pass.

**Remediation applied (fix class 3 — test-level, no production change):**
- `BoundaryStub.FindingsRun` added: exits 1 with the recognised `spec-lint: <n> spec file(s), 1 FAIL, 0 WARN` marker, where the stub enumerates `specs/**/spec.md` itself so N agrees with each arm's enumeration. No F7/F9 lines → both sides see an empty finding set and stay in parity.
- `three-req` shape added (3 requirement blocks + 3 obligation rows).
- Corpus now 3 shapes × 6 stub kinds × 4 ledger states = **72 cases**; `ChainStateParitySpec` re-run green (2/2).

### Compile-Negative Obligations — all PASS

`ChainStateReport.from(DidNotRun)` (param type), count-bearing `ChainStateUndetermined` (no field), `UndeterminedReason("")` (no public `apply`) — all three asserted non-compiling in `ChainStateCompileNegative`.

### Formal Contract: computeVerdict — PASS

`ChainStateKernel` models `PrePassOutcome`{Completed/DidNotRun}, `computeOutcome` short-circuits DidNotRun, four `.ensuring`-proved lemmas (populated-evidence ignorance, reason preservation, completed delegation, derived-counts monotonicity). Bridge spec runs shipped `compute` vs kernel on deliberately populated evidence — 15/15.

### Proof Obligations (16 rows) — all PASS

Every obligation maps to a real artifact: scenario tests, Hedgehog properties, compile-negatives, boundary parity property, bats tests 11 + 23, kernel lemmas + bridge, and the acceptance file at control parity.

## Dangerous-pattern hunt — all justified

`stated("")` → `unclassifiable` (spec-mandated named fallback); probe `case _ =>`/`NonFatal` marker classification (rejection direction); `runScanner` `NonFatal => None` (unlaunchable → undetermined); crash → `Left` (exit 2); progress-file `NonFatal => Nil` (stricter, never fakes a pass); `gitOut.getOrElse(Set.empty)` (finding direction); `bySpec(req.spec)` Map.apply — safe via `neededSpecs` invariant (soft observation); `Outcome.Finding` lint arm — dead via sanctioned pipeline, fails toward undetermined; absent-key → `Some(Nil)` (jq parity); `sys.error` on empty wire reason (rejection).

## Oracle-tampering check

No assertion loosened (structured JSON checks, exit-code equality; `reason` matched by substring only — free text by design). Generators constructive with coverage thresholds. The one narrowing (parity corpus) was remediated in-session.

## Additional observations (non-scoring)

- Stale spec anchor: `ChainStateUndetermined` is anchored to `BlockReason.scala` in the spec but lives at `ChainStateReport.scala:371–375`.
- Lint data for `Completed` is recomputed in-process (`SpecLintEngine.lint`) with the external run used as the completion witness — deliberate, parity-verified, arguably stronger than parsing subprocess output.
