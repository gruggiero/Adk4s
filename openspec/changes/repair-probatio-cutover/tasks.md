# Tasks: repair-probatio-cutover

Derived from `implementation-order.md`. `implementation-progress.md` is the single source
of truth for progress; this file is regenerated from it at each checkpoint and is never
hand-maintained in parallel.

Every spec takes **two separate human gates** — the change's correctness risk is high, so
no spec qualifies for a combined gate.

## 1. differential-harness-integrity

- [x] Prerequisite — confirm the five predecessor implementations are present on disk and readable; record their content digests as the arm-materialisation source
- [x] Prerequisite — record the hand-measured predecessor control (18 failures of 282, per file, at the change baseline) as the fixture this spec must reproduce
- [x] Step 1 — typed contract: `ArmTree` with a private constructor reachable only from materialisation, `ArmDivergence`, `SeamResolution` carrying a content digest, `ToolId` gaining the ledger and checkpoint seams (compiles; **human gate**)
- [x] Step 2 — test oracle: 14 scenarios + 4 properties (`identical-arms-never-proceed`, `divergence-is-detected-by-content-not-path`, `seam-set-covers-the-swap-order`, `comparison-is-monotone-in-failures`) + 3 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: replace the environment-variable arm construction with tree materialisation; digest each seam; refuse a comparison whose arms are identical; retarget the two source-inspecting acceptance tests at the ported implementation
- [x] Ring 0 — `sbt "probatio-core/compile" "probatio-cli/compile"` clean; the two new seam variants force every match over the seam enum
- [x] Ring 1 — scalafix + WartRemover clean; dangerous-pattern scan on the diff
- [x] Ring 2 — `probatioDependencyLint` clean across all four modules
- [x] Ring 3 — property + scenario suites green; **the predecessor arm reproduces the recorded control per file**
- [x] Ring 5 — move the migration sources to main, retarget the mutate list to the harness and seam files, run, move back; read the score (90% core / 80% adapter)
- [x] Ring 6 — extend the cutover kernel with the divergence and worse-file contracts; bridge property green; invoke the verification module directly
- [x] Ring 8 — fresh-context adversarial review; standing instruction: check whether the mechanism can return a passing verdict when the thing it measures is absent
- [x] Concept-delta check + inventory update + **checkpoint**

## 2. chain-state-undetermined-fidelity

- [x] Step 1 — typed contract: `PrePassOutcome` with `Completed`/`DidNotRun`, `ChainStateReport`'s factory narrowed to the completed arm, the could-not-determine type carrying no count field (compiles; **human gate**)
- [x] Step 2 — test oracle: 9 scenarios + 3 properties (`no-counts-without-a-completed-pre-pass`, `outcome-status-is-total-and-disjoint`, `parity-with-predecessor-on-the-undetermined-boundary`) + 3 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: map every pre-pass termination to an outcome in the adapter; make a report unconstructible without a completed pre-pass; name the unreadable input in every could-not-determine
- [x] Ring 0 — compile clean; the new outcome type is exhaustiveness-escalated
- [x] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [x] Ring 2 — dependency lint clean; the boundary decision stays free of file I/O
- [x] Ring 3 — property + scenario suites green; **its acceptance file at parity with the genuine control**
- [x] Ring 4 — the correctness-report contract still conforms for every emitted report
- [x] Ring 5 — retarget the mutate list to the chain-state and report files; read the score (90%)
- [x] Ring 6 — extend the chain-state kernel with the boundary and count-monotonicity contracts; bridge property green
- [x] Ring 8 — fresh-context adversarial review
- [x] Concept-delta check + inventory update + **checkpoint**

## 3. completion-witness-refusal

- [x] Step 1 — typed contract: `WitnessVerdict` with three variants including the unreadable case, a refusal that requires the offending row (compiles; **human gate**)
- [x] Step 2 — test oracle: 9 scenarios + 4 properties (`refusal-iff-an-uncorroborated-green-result-exists`, `unreadable-state-never-refuses`, `refusal-budget-is-bounded-and-nonzero`, `parity-with-predecessor-on-the-completion-tier`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: the corroboration predicate in the decision core; the completion tier wired to it; fail open with a stated reason; bounded to one refusal per turn
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [x] Ring 2 — dependency lint clean; the predicate reads no file
- [x] Ring 3 — property + scenario suites green (injected turn identity and clock seam, no wall-clock); **its acceptance file at parity**
- [x] Ring 5 — retarget the mutate list to the gate decision files; read the score (90%)
- [x] Ring 6 — extend the gate kernel with the corroboration contract; bridge property green
- [x] Ring 8 — fresh-context adversarial review; check the tier as a subprocess, not as a function
- [x] Concept-delta check + inventory update + **checkpoint**

## 4. gate-event-compatibility

- [x] Step 1 — typed contract: `EventDispatch` with `Tier`/`Injection`, the classification total (no optional, no either), the fallback carrying the supplied name (compiles; **human gate**)
- [x] Step 2 — test oracle: 11 scenarios + 4 properties (`event-dispatch-is-total`, `recognised-names-never-fall-back`, `unrecognised-names-exit-clean`, `parity-with-predecessor-on-event-dispatch`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: restore the permissive fallback at predecessor parity; add the diagnostic line naming the supplied value; keep the envelope carrying the harness event name
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [x] Ring 2 — dependency lint clean
- [x] Ring 3 — property + scenario suites green; the alternate-name regressions green (tests 5,6); residual +3 recorded and deferred to owning specs per human decision (see progress: test 3 → ledger-checkpoint-cutover, tests 9/10 → chain-state report contract)
- [x] Ring 4 — the hook envelope contract conforms for all six events in both output formats, executed rather than shape-checked
- [x] Ring 5 — retarget the mutate list to the event-parse and gate-entry files; read the score (80% adapter) — core 100%; cli 77.26% covered-code with in-diff survivor analysis recorded
- [x] Ring 6 — extend the dispatch kernel with totality and fallback fidelity; bridge property green — 490/490 VCs
- [x] Ring 8 — fresh-context adversarial review; confirm the restored fallback changes no verdict
- [x] Concept-delta check + inventory update + **checkpoint**

## 5. graph-tool-port

- [x] Prerequisite — confirm the predecessor traceability tool runs on this host, so it is usable as the executable model for the agreement property
- [x] Step 1 — typed contract: the nine node kinds, the nine edge kinds, the graph requiring its unlinkable set, the five operations as a closed set, the reachability result carrying lists rather than counts, the subcommand enum regaining its case (compiles; **human gate**)
- [x] Step 2 — test oracle: 15 scenarios + 4 properties (`unlinkable-rows-are-conserved`, `reachability-is-transitive-and-grounded`, `export-round-trips`, `export-agrees-with-the-predecessor`) + 4 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: the registry and inventory parsers in the decision core, reusing the existing spec parser; the graph model and the five operations; the reading confined to the new entrypoint; the correctness verdict's graph seam and its stated degradation
- [x] Ring 0 — compile clean; the new subcommand case forces every match over the subcommand enum
- [x] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [x] Ring 2 — dependency lint clean; **the no-I/O rule proves the parsers read no file**
- [x] Ring 3 — property + scenario suites green; **its acceptance file at parity**
- [x] Ring 4 — the export round-trips and agrees with the predecessor on the repository corpus and on generated corpora; labels with quotes, newlines and non-ASCII characters covered
- [x] Ring 5 — retarget the mutate list to the graph model, parsers and entrypoint; read the score (90% core / 80% adapter)
- [x] Ring 6 — new reachability kernel with grounded/complete/conserving contracts, terminating by explicit fuel; bridge property green
- [x] Ring 8 — fresh-context adversarial review; check that an unparsed row is reported rather than dropped
- [x] Concept-delta check + inventory update + **checkpoint**

## 6. feature-freeze-guard-integrity

- [x] Step 1 — typed contract: `FixtureCorpus` unconstructible from an empty list, `CorpusResolution` carrying the searched locations, the accepted verdict taking a resolved corpus (compiles; **human gate** — approved)
- [x] Step 2 — test oracle: 10 scenarios + 3 properties (`corpus-resolution-is-location-independent`, `empty-corpus-never-passes`, `verdict-stability-across-the-port`) + 3 compile-negative stubs; ORACLE POLARITY run (**human gate** — approved)
- [x] Step 3 — implementation: resolve the corpus from the active and archived areas; report could-not-determine on an empty or unresolvable corpus; keep the check-identifier set closed; compare every fixture's verdict against the predecessor
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [x] Ring 2 — dependency lint clean
- [x] Ring 3 — property + scenario suites green; **the guard suite green, and red on an empty corpus**
- [x] Ring 5 — move the guard sources to main, retarget, run, move back; read the score (80%) — **87.1%**, re-run post-Ring-8 remediation
- [x] Ring 6 — extend the spec-lint kernel with the guard outcome contract; bridge property green — 661/661 VCs
- [x] Ring 8 — fresh-context adversarial review; check that the repaired guard cannot pass vacuously — 3 majors found + fixed (`ring8-feature-freeze-guard-integrity.md`)
- [x] Concept-delta check + inventory update + **checkpoint**

## 7. install-tool-surface-parity

- [x] Step 1 — typed contract: `InstallTarget`, `InstallMode` with no default, `PrerequisiteProbe`, `PrerequisiteReport` holding probes rather than names (compiles; **human gate**)
- [x] Step 2 — test oracle: 13 scenarios + 3 properties (`surface-parity-with-the-predecessor`, `dry-run-writes-nothing`, `install-covers-every-declared-directory`) + 3 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: the prerequisite probe; the multi-directory install; the hook installer's harness selector, project-root selector, report-before-write default and no-clobber refusal; both help entries widened
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean; dangerous-pattern scan on the diff; shellcheck on the two forwarding scripts once swapped
- [x] Ring 2 — dependency lint clean
- [x] Ring 3 — property + scenario suites green; **surface parity against the predecessor**
- [x] Ring 5 — retarget the mutate list to the two installer entrypoints and the new install types; read the score (80%) — `InstallSurface.scala` **100%**; `SubcommandEntrypoints.scala` **46.58% covered-code** after 3 surgical-kill rounds (25.67→39.73→46.12→46.58), all remaining in-diff survivors justified equivalents
- [x] Ring 8 — fresh-context adversarial review; confirm no invocation the predecessor honoured now errors — 3 majors found + fixed (`ring8-install-tool-surface-parity.md`)
- [ ] Concept-delta check + inventory update + **checkpoint**

## 8. ledger-checkpoint-cutover

- [ ] Prerequisite — confirm the two predecessor implementations are present and readable as the revert target
- [ ] Step 1 — typed contract: the swap record gaining a required comparison field; no new domain type (compiles; **human gate**)
- [ ] Step 2 — test oracle: 12 scenarios + 4 properties (`record-validator-agrees-with-the-contract`, `record-round-trips-all-present-fields`, `checkpoint-reports-every-requested-ring`, `swap-decision-requires-a-complete-comparison`) + 3 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: measure each seam under the repaired comparison; swap only a seam whose exercising files are at parity; leave each predecessor on disk
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; dangerous-pattern scan on the diff; shellcheck on the two forwarding scripts
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — property + scenario suites green; **the six exercising acceptance files at parity — their first real measurement; new regressions here are a result, not a surprise**
- [ ] Ring 4 — the record contract conforms; every optional-field combination round-trips; the committed fixture reads cleanly; an unrecognised version is could-not-determine
- [ ] Ring 5 — move the swap-decision sources to main, retarget, run, move back; read the score (80%)
- [ ] Ring 6 — extend the record-validator kernel with the swap-authorisation contract; bridge property green
- [ ] Ring 8 — fresh-context adversarial review
- [ ] Concept-delta check + inventory update + **checkpoint**

## 9. workflow-delivery-hygiene

- [ ] Step 1 — typed contract: `ShimTargetScope` as a required generation parameter, the relative variant unable to carry an absolute path (compiles; **human gate**)
- [ ] Step 2 — test oracle: 11 scenarios + 3 properties (`shim-resolves-from-any-location`, `no-committed-script-carries-an-absolute-path`, `generation-refuses-unquotable-targets`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: regenerate the five forwarding scripts with location-relative resolution; add a continuous-integration job running the acceptance suite, the differential comparison and every module suite; update the three shipped templates to name tools that exist
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; dangerous-pattern scan on the diff; **shellcheck on all five regenerated scripts and the new job**
- [ ] Ring 2 — dependency lint clean; the plugin still links no decision-core code
- [ ] Ring 3 — property + scenario suites green; **the acceptance suite passes from a fresh clone at a different filesystem location**
- [ ] Ring 5 — retarget the mutate list to the shim generator and install resolver; read the score (80%)
- [ ] Ring 8 — fresh-context adversarial review; check the job fails on a deliberately regressing branch rather than reporting success
- [ ] Concept-delta check + inventory update + **checkpoint**

## 10. schema-rename-completion

- [ ] Step 1 — typed contract: `RenameDeferral` requiring its reason and blocking coupling (compiles; **human gate**)
- [ ] Step 2 — test oracle: 13 scenarios + 3 properties (`every-searched-root-is-classified`, `migration-is-idempotent`, `no-shipped-document-presents-the-previous-name-as-current`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: wire the existing cache-migration kernel to a caller in the shipped tool; regenerate every installed instruction document with the current stamp; update the fourteen tutorial documents, the three continuous-integration templates and the harness adapters; correct the acceptance suite's schema-version assertion; record the directory-rename deferral with its coupling
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — property + scenario suites green; **no searched root carries a pre-rename stamp**; the two affected acceptance files at parity
- [ ] Ring 5 — retarget the mutate list to the migration caller and the drift classification; read the score (80%)
- [ ] Ring 8 — fresh-context adversarial review; confirm the deferral records a coupling rather than an excuse
- [ ] Concept-delta check + inventory update + **checkpoint**

## 11. unported-tool-register

- [ ] Step 1 — typed contract: `ToolSurfaceClassification` with exactly two variants, `UnportedTool` requiring a blocker, `PortBlocker` as a closed enum (compiles; **human gate**)
- [ ] Step 2 — test oracle: 11 scenarios + 3 properties (`classification-is-total-and-exclusive`, `every-entry-carries-a-blocker`, `citations-resolve-for-the-real-register`) + 3 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: the register document with one entry per unported tool, each naming its blocker and the instructions citing it; the total classification over the tool directories; the newly ported traceability tool removed from the register
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; dangerous-pattern scan on the diff
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — property + scenario suites green; **every executable in the tool directories classifies**
- [ ] Ring 5 — retarget the mutate list to the classification and register types; read the score (90%)
- [ ] Ring 8 — fresh-context adversarial review; check that no tool falls outside both halves
- [ ] Concept-delta check + inventory update + **checkpoint**

## Change exit criterion

- [ ] The repaired comparison reports **no acceptance file worse** than the genuine predecessor control — measured by a harness proven to materialise two different implementations, not by one that compares a run with itself
- [ ] The predecessor implementations remain on disk as the revert target; the green criterion has not yet held across a full change cycle
- [ ] The feature-freeze guard is green and fails on an empty corpus
- [ ] A continuous-integration job runs the acceptance suite, the differential comparison and every module suite on each change
