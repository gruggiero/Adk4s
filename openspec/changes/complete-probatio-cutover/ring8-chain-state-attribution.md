Ring 8: Adversarial Spec-Compliance Review — chain-state-attribution

Fresh context: yes
Baseline: 271a7560f494fbafd4555bc1e3c335c2890e9e92   Diff reviewed: workflow/core/src/main/scala/org/sinemenda/probatio/core/{RequirementExtractor,ChainState,ChainStateReport}.scala; workflow/cli/src/main/scala/org/sinemenda/probatio/cli/{SubcommandEntrypoints,RepositoryFactsReader,StdoutRenderer,SubcommandWiring}.scala (chain-state hunks only); verified/probatio/src/main/scala/org/sinemenda/probatio/core/ChainStateKernel.scala; migrated/new test oracles (ChainStateAttributionSpec, ChainStateAttributionTypeContract, ChainStateSpec, VerifiedKernelBridgeSpec, ChainStateCmdSpec, ChainStateParitySpec, ChainStateCmdConformanceSpec, RepositoryFactsSpec)
Dangerous patterns found: 6 unmitigated (0 fixed / ~14 justified `danger-scan:allow` sites reviewed and accepted)
Oracle tampering: none confirmed; four coverage-honesty findings (the parity corpus cannot see the defect classes below)
Requirements: 2 PASS, 0 PARTIAL, 3 FAIL

Properties and contracts graded separately below: verdict-parity FAIL, obligation-rows-are-conserved FAIL, empty-is-not-unreadable PARTIAL, counts-are-consistent PASS, unattributable-never-discharged PASS, all three Compile-Negative Obligations PASS, Formal Contract `chainStateFold` FAIL (absent).

Reviewed against the full English spec (`openspec/changes/complete-probatio-cutover/specs/chain-state-attribution/spec.md`) and the complete predecessor (`openspec/schemas/verified-scala3/scanner/chain-state.sh.predecessor.bak`, 751 lines). Danger scan run via `openspec/schemas/verified-scala3/bin/probatio danger-scan 51bd0bc`: exit 0, no printed findings; every in-scope `danger-scan:allow` site was re-judged manually.

---

## Requirement-by-requirement verdicts

### Requirement: The correctness verdict is computed over the change's actual requirements — FAIL

The headline mechanics are right: `ChainState.compute` takes a `RequirementSet`, not a `List[Requirement]` (`ChainState.scala:83-91`); `RequirementExtractor.extract` derives requirements from parsed `SpecDocument`s on both paths (`RequirementExtractor.scala:124-144`); missing `specs/` and unreadable `spec.md` files go to could-not-determine (`SubcommandEntrypoints.scala:929-930, 944-957`); a read-but-empty set reports a clean `total: 0` distinguishable from undetermined.

The violation: `SpecLintCmd.findSpecs` swallows any traversal failure into `Nil` (`SubcommandEntrypoints.scala:585`, `catch case NonFatal(_) => Nil` with `// danger-scan:allow degraded-discovery — a failed find is "no spec files"`). `Files.walk` throws `UncheckedIOException` when a directory it descends into is unreadable. Reached via `prepareInputs` at `SubcommandEntrypoints.scala:932`: an existing `specs/` tree that cannot be enumerated produces `discovered = Nil` → `namedSpecs = Nil` → `RequirementSet(Nil, Nil, Nil, _)` → `compute` sees `neededSpecs = Nil` → a measured `total: 0` report, `Outcome.Ran(0)`, exit 0 — the exact "verdict over an empty requirement set reported as though the change had no requirements" the requirement forbids. The requirement set is *unreadable*, not empty; the two are collapsed inside the swallow.

The predecessor diverges the other way, correctly: `find` fails identically, but the ported-or-original `spec-lint` subprocess's own `find` also trips and it exits 1 having printed no recognised completion shape, so the F6 completion-shape check at `chain-state.sh.predecessor.bak:283-285` fires `die_undetermined`. Port: clean-0. Predecessor: exit 2.

Why tests missed it: no fixture anywhere makes `specs/` unlistable. `ChainStateCmdSpec` tests missing `specs/` (line 167) and corrupt ledgers (line 456); `ChainStateParitySpec` materialises only happy-writable trees. The `danger-scan:allow` justification is itself the bug class — "a failed find is 'no spec files'" maps a failure onto a valid domain value silently.

Fix class (strongest first):
1. Type-level prevention — make discovery return a sum type (`Found(List[Path]) | Unlistable(reason)`) so `Nil` is unrepresentable as a failure result.
2. Explicit runtime rejection — `findSpecs` returns `Either[String, List[Path]]`; `prepareInputs` maps `Left` to the could-not-determine `Left`.
3. Targeted test — a fixture with `specs/<name>` chmod 000 asserting exit 2.

### Requirement: A requirement whose obligations cannot be attributed is never counted as discharged — PASS

Verdict order in `compute` is the predecessor's (`ChainState.scala:152-195`): unbound → mapped-is-empty → `Unattributable` (Degraded) / `Unresolved` (Graph) — evaluated before any ledger read, so the unattributable case can never reach the discharge branch. `unresolvedN` folds `Unattributable` into `bound - resolved` (`ChainState.scala:232-233`), and `fromCounts` cross-checks the counts against the reason multiset (`ChainStateReport.scala:218-233`) — a mislabelled count is an internal-error undetermined, not a silent pass. Direct scenario tests exist and are honest (`ChainStateAttributionSpec.scala:319-346`).

Residual attribution-fidelity defects are charged to the next requirement and to parity, not here: the *reason text* can still be wrong when the extracted row set diverges from the predecessor's (e.g. an empty-obligation-cell row maps in the port and reports `undischarged`/`unresolved` where the predecessor reports `unattributable`), but nothing unattributable is ever discharged.

### Requirement: An obligation that maps to no known requirement is reported separately, never dropped and never misattributed — FAIL

`degradedObligations` enumerates `s.document.obligationRows` (`RequirementExtractor.scala:172`). `obligationRows` is spec-lint's check_source row set, gated at `SpecDocumentParser.scala:242` (`nf >= 4 && src.nonEmpty && !src.startsWith("<!--")`) and by `inPo`, which is cleared by `### Requirement:`/`### Property:`/`### Temporal:` headings (`SpecDocumentParser.scala:293, 298, 305`).

The predecessor's own row set is a different, wider set (`chain-state.sh.predecessor.bak:536-546`): every `|`-row in the `## Proof Obligations` section after the 4-cell separator, with `ob != ""`, where the section flag `m` is cleared only by `^## ` headings. The unmapped-detection loop (`:667-698`) looks every F9 line up in *that* set. Concrete divergences, each with a verdict flip:

- **Empty-Source rows dropped.** `| obl |  | manual | `bad.scala` |`: predecessor's `rows` includes it (`ob` non-empty, no source gate); the F9 artifact scan (`artifactRows`, nf ≥ 5, no source gate — `SpecDocumentParser.scala:255-271` and the doc comment at `SpecDocument.scala:107-113`) flags that line; predecessor reports it in `unmapped_obligations` → exit 1. Port: absent from `obligationRows` → no `ExtractedObligation` → silently absent → exit 0 when otherwise clean.
- **Comment-Source rows dropped.** `| obl | <!-- note --> | manual | `bad.scala` |`: identical flip — the parser's `src.startsWith("<!--")` gate has no counterpart in the predecessor's awk.
- **Empty-Obligation-cell rows wrongly admitted.** `|  | Requirement: X | manual | `a` |`: predecessor excludes (`ob != ""`); port includes (no `ob` gate) → X gets a "mapped" row with `obligation = ""` → `undischarged` (or `unresolved` if the artifact fails) where the predecessor reports `unattributable`. Reason-text and report divergence.
- **Rows after `### ` headings dropped.** A `### Requirement:` heading inside the section clears the port's `inPo` but not the predecessor's `m` — subsequent rows vanish from the port's attribution set while spec-lint's `inPoArtifacts` (cleared only by `## `) still flags them.
- **F4 reintroduced.** The port's `dataRow` excludes rows matching `headerRe` (`^\| *Obligation`, `SpecDocumentParser.scala:36`) *anywhere* — a post-separator data row whose first cell starts with "Obligation" is dropped. The predecessor's own F4 fix (`:519-535`) excludes the header structurally (pre-separator skip) precisely because a content test ate a real row; the port inherits spec-lint's deliberate content-test limitation into a context where the predecessor explicitly removed it.
- **Pre-separator rows wrongly admitted.** The port has no `sep` state; a `|`-row placed between `## Proof Obligations` and the `|---|` separator is collected (nf ≥ 4, non-empty source) where the predecessor skips everything before the separator.

Why tests missed it: the `obligation-rows-are-conserved` property (`ChainStateAttributionSpec.scala:776-866`) generates `ExtractedObligation`s directly — it measures conservation *given* an obligation list, never the document→obligation seam where the drop lives. `ChainStateParitySpec`'s corpus contains only `Requirement: T`, `Requirement N`, `Property: x`, `n/a`, and combined `Requirement: T + Scenario: s` sources — never an empty or comment source, never an empty obligation cell, never a `### ` heading inside the table, never an "Obligation"-prefixed data row.

Fix class:
1. Type-level prevention — n/a; the row sets are genuinely different shapes and both are needed.
2. Explicit runtime rejection — `degradedObligations` must enumerate its own row set over `document.lines` replicating the predecessor awk (post-4-cell-separator, `## `-only section toggle, `ob != ""`), not borrow `obligationRows`. The `dataRowCount`/`artifactRows` precedent shows the parser already tracks three distinct row sets; the chain-state set is a fourth, not a fifth use of the second.
3. Targeted tests — parity corpus fixtures for empty-Source, comment-Source, and empty-obligation-cell rows (each currently flips a verdict).

### Requirement: The extraction path used for the requirement set is reported — FAIL

`graphExport` emits its diagnostic at `SubcommandEntrypoints.scala:1123-1133`: any parseable export yields `Some(json)` plus `"chain-state: fact extraction via openspec-graph.py export (graph)"`. The `usableExport` gate (`RequirementExtractor.scala:152-160`, the port of `jq -e '.obligations'`) is applied *later*, inside `extract` (`:131`). An export that parses but lacks a usable `.obligations` member — e.g. `{"specs":[...]}` with no `obligations` key, or `"obligations": null` — produces `FactSource.Degraded` obligations while stderr has already claimed the transitive extractor ran. That is the spec's adversarial scenario verbatim: "the diagnostic channel does not name the transitive extractor as the source" for a fallback result. The predecessor gates before choosing the path (`chain-state.sh.predecessor.bak:227-230`), so its "produced invalid JSON; using degraded" line is honest.

Why tests missed it: `ChainStateCmdSpec:554-568` (attribution spec) exercises the gate at the `extract` level but asserts only the `source` tag, not the emitted diagnostic; `ChainStateCmdSpec.scala:207-233` and `:262-278` test a runnable extractor and a missing `PROBATIO_SCANNER_DIR` — never the parses-but-unusable middle case.

Fix class:
2. Explicit runtime rejection — move `usableExport` into `graphExport` (or re-emit the diagnostic after `extract` returns) so the emitted line reflects the gated outcome: `"openspec-graph.py export produced unusable output; using degraded mode"`.
3. Targeted test — export JSON without `.obligations` asserting the degraded announcement and the absence of the graph claim.

### Requirement: A could-not-determine result states its reason exactly once — PASS

`emitUndetermined` (`SubcommandEntrypoints.scala:885-893`) writes the marker once on stderr and emits the report on stdout; `readLedgerFile` carries the reason unmarked (`SubcommandWiring.scala:69-73`); the `ChainStateUndetermined` renderer writes `reason` verbatim with null counts (`StdoutRenderer.scala:71-85`). The undetermined report shape matches `die_undetermined` (`chain-state.sh.predecessor.bak:66-75`). Scenario tests assert single-occurrence directly (`ChainStateCmdSpec.scala:285-330`).

---

## Properties (Ring 3)

### verdict-parity-with-predecessor — FAIL

Confirmed divergences beyond the requirement-level findings above:

1. **`resolveSha` `.distinct` collapse** (`SubcommandEntrypoints.scala:1163`). Verified empirically: `git rev-parse deadbee` echoes `deadbee` on stdout then exits 128; the predecessor's `|| echo` produces `"deadbee\ndeadbee"`, which matches no ledger row and routes the rows through the forgiveness oracle. The port's `List(out, sha).filter(_.nonEmpty).distinct` produces `"deadbee"` — a value that matches a ledger row whose `baseline` is literally `deadbee`, discharging without forgiveness. The comment at `:1148-1151` claims `"<sha>\n<sha>"` is "reproduced exactly"; the code contradicts it. (`cd ""` *is* a bash no-op — verified, bash 5.1 — so the port's cwd-fallback is correct; only the dedup is wrong.)
2. **`resolveBaselines` missing two awk arms** (`SubcommandEntrypoints.scala:1033-1051` vs `chain-state.sh.predecessor.bak:151-168`): (a) `/^## / && !/^## Spec/` clears `in_baseline` — a non-Spec `## ` heading inside a `### Baseline` paragraph does not clear the port's `inBaseline`, so a later backticked SHA is wrongly attributed to the last spec; (b) the predecessor's `in_baseline && /SHA `/` gate requires the literal `SHA \`` on the line — the port captures *any* backticked hex in the paragraph. Both fabricate an admissible baseline (evidence-inflation direction).
3. **Baseline-map tolerance asymmetry** (`chain-state.sh.predecessor.bak:300-337`): with a non-empty spec-baseline map, per-spec ledger read failures are skipped with a trace and the unfiltered read's failure is ignored — a corrupt ledger then yields `undischarged`, exit 1. The port fails closed (any invalid row → undetermined, exit 2). The port's behavior is arguably the spec-correct one ("cannot trust a ledger that contains a record the contract rejects"), but it is a parity divergence whenever `implementation-progress.md` exists.
4. **Discovery-failure collapse** — itemised under Requirement 1.
5. **Extraction-path mislabel** — itemised under Requirement 4.
6. **Argument surface**: `--spec`, `--format`, `--artifacts`, `--forgive-unchanged` accepted (`SubcommandEntrypoints.scala:851-876`) where the predecessor dies `unrecognised argument` (`:106`). Spec-anchored extension (`spec.md:393`) — noted, not counted against.

Why tests missed it: the parity corpus exercises the *degraded* path only (`ChainStateParitySpec.scala:26-31`), uses only well-formed obligation rows, has no `implementation-progress.md` fixture, no unresolvable baseline, and — because `"00d3de1"` resolves in this repo and the fixture artifacts changed since it — every corpus ledger row is stale on *both* arms, so the baseline-matched discharge and forgiveness paths are never parity-exercised at all. `normalise` (`:463-470`) additionally collapses undetermined reports to `{change, baseline, undetermined}`, hiding reason-text divergences.

### counts-are-consistent — PASS

`fromCounts` enforces monotonicity, the complement law, pair uniqueness, and reason↔count cross-consistency (`ChainStateReport.scala:204-250`); a violation becomes internal-error undetermined (`ChainState.scala:248-256`), mirroring the predecessor's contract self-check (`:733-735`). The property generator is independent-verdict constructive per the spec.

### unattributable-is-reachable-and-never-discharged — PASS

See Requirement 2. Constructive generator at `ChainStateAttributionSpec.scala:707-748` builds the reachable-but-unattributable shape directly.

### obligation-rows-are-conserved — FAIL

The invariant fails at the extraction seam for the dropped row classes itemised under Requirement 3: an empty-Source or comment-Source row carrying an F9 finding is attributed to nothing *and* absent from `unmapped_obligations` — neither counted nor listed. The property test measures `compute` over synthesised `ExtractedObligation`s, so the drop upstream of its inputs is invisible.

### empty-is-not-unreadable — PARTIAL

Ledger arm holds (readable-empty → `Ran`, corrupt → `Undetermined`; `SubcommandWiring.scala:73-118`; `ChainStateCmdSpec.scala:444-473`). Spec-document arm holds (unreadable `spec.md` → `Left`, `SubcommandEntrypoints.scala:946-957`). Unlistable-directory arm collapses to clean-0 (Requirement 1 finding) — empty and *undiscoverable* produce the same result.

---

## Compile-Negative Obligations

All three PASS:

- `ChainState.compute(lint, ledger, Nil, baseline, change)` — the parameter type is `RequirementSet` (`ChainState.scala:86`); compile-negative tests present (`ChainStateAttributionSpec.scala:168-196`).
- `ChainStateReport` with `discharged > total` — private primary constructor, `copy` sealed (`ChainStateReport.scala:150-183`), sole route `fromCounts`; wire reader re-validates (`:312-325`). Attempted direct construction is a compile error, verified by tests (`:223-242`).
- `UnresolvedEntry` with empty reasons — private constructor, sealed `copy`, `of` rejects empty names/reasons and duplicates (`ChainStateReport.scala:55-119`). A `compute`-side `of` failure is not silent: the dropped entry breaks the complement law and yields internal-error undetermined (`ChainState.scala:225-256`).

Public `RequirementSet` construction remains possible (it is a plain case class), so a caller *can* forge an inconsistent set — but `compute`'s `neededSpecs` fold turns the observable consequences (missing lint) into undetermined, and the only producer in the shipped path is `RequirementExtractor`. Acceptable per the spec's seam design.

---

## Formal Contracts (Ring 6) — FAIL

`spec.md:331-362` prescribes `chainStateFold(total, verdicts, discharged, unattributable)` with a documented postcondition and states `ChainStateKernel` "is extended here". The kernel contains no `chainStateFold`, no `unattributable`, no per-spec baselines, and no unmapped-obligation logic (`ChainStateKernel.scala` — diff vs baseline is scalafmt-only, 74+/57-). Worse, the kernel is now a *stale model*: `matchesBaselineChange` still requires `isNonManual(rec.ring)` (`ChainStateKernel.scala` ~line 125) while production correctly admits Manual-ring rows per `ledger.sh` parity — production and kernel now disagree on discharge semantics, and `VerifiedKernelBridgeSpec` bridges only the undetermined-never-collapses law on empty inputs (`VerifiedKernelBridgeSpec.scala` diff: signature adapters only), so the divergence is unobservable. Proof-obligation row `spec.md:379` ("formal contract (Ring 6) + bridge test") is unmet.

Fix class: implement `chainStateFold` in the kernel per the spec's stated signature/postcondition, reconcile the Manual-ring filter with shipped semantics, and extend the bridge to generated non-empty inputs.

---

## Dangerous-pattern findings (manual classification; danger-scan printed none)

Unmitigated (6):

| # | Site | Pattern | Effect |
|---|------|---------|--------|
| 1 | `SubcommandEntrypoints.scala:585` | `catch NonFatal → Nil` on `Files.walk` | "discovery failed" silently maps to the valid value "no files" → clean-0 (Req-1 FAIL) |
| 2 | `SubcommandEntrypoints.scala:1163` | `.distinct` on the fallback echo | unresolvable baseline becomes row-matching literal (parity FAIL) |
| 3 | `SubcommandEntrypoints.scala:1123-1133` | diagnostic emitted before the usability gate | fallback labelled as extractor result (Req-4 FAIL) |
| 4 | `SubcommandEntrypoints.scala:1033-1051` | missing `/^## /` clearing arm in the fold | spurious spec-baseline attribution |
| 5 | `SubcommandEntrypoints.scala:1041` | missing `SHA \`` gate on the capture | any backticked hex in the paragraph becomes a spec baseline |
| 6 | `RequirementExtractor.scala:172` | wrong row set borrowed from spec-lint | five divergence axes (Req-3 FAIL), incl. F4 reintroduction |

Reviewed and accepted as justified (same-line `danger-scan:allow` with honest reasons, or provably safe): `usableExport`'s `case _ => false` (`RequirementExtractor.scala:160` — non-Obj cannot satisfy the `jq -e '.obligations'` gate); `arrField`/`strField`/`case _ => Nil` defaults (`:237, 240, 262-267` — jq's null-filter parity); `obligationLine` fallback `1` (`:257-259` — predecessor's `[ -n "$uobl_line" ] || uobl_line=1`, byte-exact); `gitExit → 128` (`:1176` — "changed", never "unchanged"); `readAllBytes.last` (`SubcommandWiring.scala:136` — guarded by `size > 0`); `bySpec(req.spec)` `Map.apply` (`ChainState.scala:154` — provably total: `neededSpecs ⊆ bySpec` by the fold above it); `UnresolvedEntry.of(...).toList` (`:227` — a dropped entry breaks the complement law and yields undetermined, never silent); `f9ArtifactToken` first-match vs the sed's greedy last-match (`:259-263` — F9 messages have exactly one artifact token); `parseReport`'s absent-key `Nil`/`"unknown"` defaults (`RepositoryFactsReader.scala:541, 558, 568-571` — jq `null|length` parity, and `fromCounts` re-validates the assembled report); `emitUndetermined`'s dead `Outcome.Finding` arm (`:790`); `gitOut → None` (`:525`); `repoRoot → cwd` (`:538`); `userHome → user.home` (`:548`); unreadable progress file → `Nil` (`:1020` — predecessor's awk on an unreadable file also yields no matches); `resolveSha` `catch → sha` (`:1165` — predecessor's `|| echo` parity); `ChainStateReport`/`UnresolvedEntry` `sys.error` wire rejections (type-rejection direction throughout).

Non-findings checked and cleared: `Outcome.Finding` lint outcome → undetermined is consistent (a `Finding` outcome means no report was produced); duplicate `specNames` collapsing via `.toMap` (`:981-983`) is unreachable — spec names derive from sibling directory names; `unmapped`'s `bySpec.get(o.spec)` (`ChainState.scala:208`) is safe because `reqs.obligations` can only name specs in `specNames ⊆ neededSpecs ⊆ bySpec`; `Validator.validate` re-checking already-`validateFull`-validated rows (`:799`) is redundant but harmless.

---

## Oracle tampering — none confirmed

Four items inspected closely; all cleared or reclassified as coverage weaknesses:

- `RepositoryFactsSpec.scala:568` `reasons:["unbound"]→["undischarged"]` — the old fixture was contract-*inconsistent* (total=2, bound=2, discharged=1 with an unbound reason); `fromCounts` now rejects it. The change is a forced fixture correction, evidence the validator works.
- `ChainStateSpec.scala` "Manual ring records do NOT count" → "count as discharged" — a genuine oracle flip, but in the *correct* direction: `ledger.sh read` does not filter by ring, and the parity corpus's `manual-ring` fixture confirms the predecessor discharges manual rows. Side effect noted above: production now diverges from the still-stale `ChainStateKernel`, which the bridge is too weak to observe.
- `ChainStateCmdConformanceSpec` and `ChainStateAttributionSpec` — assertions tightened or preserved, generators not narrowed: `genComputeInputs` keeps independent per-requirement verdicts; `genObligationRows` keeps all four source kinds with ≥12% cover each.
- `ChainStateParitySpec.normalise` collapsing undetermined reports to `{change, baseline, undetermined}` — a documented projection, not tampering; it does hide reason-text/reason-order divergences, listed under coverage below.

Coverage-honesty findings (the oracle cannot see whole mechanism classes):

1. Graph-path parity is untested — the corpus forces the degraded arm on both sides (`ChainStateParitySpec.scala:26-31`); the comment "covered by the same mechanism in environments where the export can run" is aspirational.
2. No corpus fixture has an empty-Source, comment-Source, empty-obligation-cell, `### `-heading-interrupted, `Obligation`-prefixed, or pre-separator obligation row — the entire Req-3 defect class is invisible.
3. Discharge-via-matched-baseline and forgiveness are never exercised — the corpus baseline resolves and the fixture artifacts changed since it, so every corpus row is stale on both arms.
4. No baseline-map (`implementation-progress.md`), unresolvable-baseline, unreadable-file, or unlistable-tree fixture — the `resolveSha`, `resolveBaselines`, `findSpecs`, and ledger-tolerance divergences are invisible.

---

## Proposed fixes (strongest fix class first)

1. **Type-level prevention**
   - `findSpecs` returns `Found(paths) | Unlistable(reason)` (or `Either[String, List[Path]]` minimum): "walk threw" must be unrepresentable as "walk returned empty".
   - Extend `ChainStateKernel` with `chainStateFold` per `spec.md:336-358` including the `unattributable` parameter, and reconcile `isNonManual` with the shipped (predecessor-correct) admission of Manual-ring rows; extend `VerifiedKernelBridgeSpec` to non-empty generated inputs.

2. **Smart constructor / explicit runtime rejection**
   - `degradedObligations` enumerates the predecessor's own row set over `document.lines` (post-4-cell-separator rows, `## `-only section toggle, `ob != ""`, no source/field-count gate) instead of `obligationRows`. Add the set to `SpecDocument` rather than re-deriving it ad hoc.
   - `graphExport` applies the `.obligations` usability gate before emitting the path diagnostic.
   - `resolveSha` drops `.distinct` — reproduce `"<sha>\n<sha>"` byte-exactly on rev-parse failure (or make an unresolvable baseline a value that structurally cannot equal a row baseline — never collapse it to the literal).
   - `resolveBaselines` adds the missing `/^## / && !/^## Spec/` `inBaseline`-clearing arm and the `/SHA `/` gate on the capture line.
   - `ChainStateReport`-equivalent self-check exists; keep it — no change needed.

3. **Targeted tests**
   - Parity corpus fixtures: empty-Source row + unresolvable artifact (exit 1 vs 0 flip); comment-Source row same; empty-obligation-cell row (unattributable vs undischarged); `implementation-progress.md` with a non-Spec `## ` heading inside `### Baseline`; unresolvable `--baseline`; unlistable `specs/` subtree; parseable export lacking `.obligations` (diagnostic claim).
   - A `counts`-level assertion that `unmapped_obligations` is computed over the predecessor row set, not merely over synthesised `ExtractedObligation`s.

No defensive fallbacks are proposed anywhere: each fix either makes the failure unrepresentable, rejects it explicitly as undetermined, or restores the predecessor's exact observable semantics.
