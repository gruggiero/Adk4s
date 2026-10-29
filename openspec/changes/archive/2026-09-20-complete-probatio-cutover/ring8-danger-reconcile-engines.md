Ring 8: Adversarial Spec-Compliance Review — danger-reconcile-engines

Fresh context: yes (read-only subagent; inputs limited to spec + diff vs a7b49cf + predecessors + supporting types)
Baseline: a7b49cf   Diff reviewed: DangerScanEngine.scala, ReconcileEngine.scala, ChangedFilesReader.scala, ReconcileKernel.scala, StdoutRenderer/SubcommandEntrypoints/HelpRegistry edits + 9 test/contract files
Dangerous patterns found: 14 justified sites in new code — all audited honest; 2 strengthened (see fixes)
Oracle tampering: 1 finding (vacuous-pass escape hatch in the parity property) + 1 missing spec-declared cover label
Requirements: 10 PASS, 4 PARTIAL → all 4 PARTIALs fixed and verified green

## PARTIALs found and fixed

1. **Hit emission order was line-major; the predecessor is pattern-major.** The
   predecessor runs eight sequential `grep -nE` scans per file — hits group by
   pattern declaration order. `scan` flattened per-line hits, and its doc comment
   claimed pattern-major — a false claim worse than the bug. Fix:
   `DangerScanEngine.scan` now regroups per-file hits by `patternsInOrder`
   (DangerScanEngine.scala:200-211); new scenario test
   "scan emits hits pattern-major within a file" pins the order
   (DangerScanEngineSpec.scala:179).

2. **`observer-at-wrong-key` cover label missing** — spec declares three covers
   for `witness-requires-key-agreement`; the property had two. The generator
   already produced the data (perturbed-key ambient rows); nothing enforced it.
   Fix: added `.cover(25, "observer-at-wrong-key", …)` — a Testimony claim with
   an ambient row at a different key (ReconcileEngineSpec.scala:287-307).

3. **Vacuous-pass escape hatch in the parity property.** `materialise` returned
   `None` when bash/git couldn't launch and the property mapped `None` to
   `Result.success` — on a host without tooling, 80 green tests with zero
   comparisons. A second hatch: predecessor-launch failure returned `(∅, ∅)`,
   trivially equal. Fix: `materialise` returns `Either[String, …]`; every
   tooling failure is `Left` and the property fails with the reason
   (DangerScanParitySpec.scala:126-135, 201-271). Also mirrored the reader's
   `isReadable` guard in the port-side replica.

4. **`Witnessed(observer, observers)` coherence not enforced.** The public
   2-field ctor admitted `Witnessed(rec, Nil)` — a "witnessed" verdict with
   `observed: []`, testimony wearing the wrong label, constructible around
   `ReconcileReport.of`. Fix (type-level): `Witnessed(observer, preceding,
   following)` — the full ambient-at-key set is derived as
   `preceding ++ (observer :: following)`; the observer is a required field and
   ledger order is structural (ReconcileEngine.scala:39-44). Contract pin
   updated to the 3-arg apply; new scenario pins `observed == List(1, 0)` when a
   dissenting ambient row precedes the witness.

## Justification strengthening (below verdict threshold, applied)

- `ReconcileReport.claims`/`verdictOf` `case _` catch-alls on the sealed
  `Corroboration` enum replaced with named cases (`SelfObserved | Exempt`,
  `Testimony | SelfObserved | Exempt`) — a future sixth case now forces a
  compile error instead of being silently absorbed.
- `SubcommandEntrypoints.scala:1812` dead `Outcome.Finding` arm remapped from
  `undetermined` to passthrough `Finding` — if it ever fires it exits 1
  (finding), not 2.

## Reviewer observations noted, not defects (recorded for the checkpoint)

- `SubcommandWiring.readLedgerFile` lazy `lines.iterator` can throw
  mid-iteration (pre-existing, not in this diff).
- `ChangedFilesReader.git` drains stdout before stderr — theoretical pipe
  deadlock if git writes >64KB stderr (git diff name-only never does).
- Path-with-whitespace divergence: predecessor word-splits `git diff` output
  and would skip such paths; the port scans them. Arguably more correct;
  generator produces `[a-zA-Z0-9]` paths so untested either way.
- CLI specs exercise `run` in-process, not the built artifact.
- `ClaimVerdict.verdict` is a free String — softening, not a violation.

## Verdict list (post-fix)

- Req: scan examines production files changed since baseline — PASS (after fix 1)
- Req: baseline optional, defaults to working tree — PASS
- Req: uncorroborated written outcome is reported — PASS
- Req: corroboration does not decide discharge — PASS
- Prop: danger-parity-with-predecessor — PASS (after fix 3)
- Prop: justification-excludes-exactly-its-own-occurrence — PASS
- Prop: corroboration-is-total-and-exclusive — PASS
- Prop: witness-requires-key-agreement — PASS (after fix 2)
- Prop: no-discharge-verdict-in-output — PASS
- CN: DangerReport empty-hits + count>0 — PASS
- CN: Witnessed without the observing record — PASS (after fix 4, type-level)
- CN: discharge verdict referenced — PASS
- CN: DangerPattern ninth case — PASS
- Formal contract corroborationFold — PASS (Ring 6 complete: 345/345 VCs valid; the spec's `forall`/`zip`/`exists` ensuring was rewritten as structural `validRecords`/`postOk`/`foldGo` per docs/ring6-stainless-verification-experience.md §4 — the initial run stalled with no per-VC timeout)

Post-fix verification: probatio-core spec-6 suites 52/52, probatio-cli 25/25,
all green.
