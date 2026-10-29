# Ring 8: Adversarial Spec-Compliance Review — oracle-independence

Fresh context: yes (isolated subagent; inputs: spec + typed contract + baseline diff only — no implementation conversation)
Baseline: `8e9ccf6`   Diff reviewed: `spec.md` (self-amendment), `strangler-migration-protocol.md`, `tests/` (chain-state, correctness-invariant, gate-payload, harness-install-verification, human-grant-lock, oracle-ordering-lock, workflow-hygiene .bats; +`oracle-baseline`; +`oracle-sanctions.jsonl` (25 rows); +`shape/*-shape.bats` ×5), `guard/` (`SanctionTypes`, `OracleSanctionGuard`, `OracleSanctionGens`, `OracleSanctionTypeContract` new; `FeatureFreezeGuardIntegrityTypeContract`, `NonGoalsGuardSpec` edited), `SpecLintBridgeSpec.scala`, `SpecLintKernel.scala`
Reviewer session: `devin-subagent-ring8-oracle-independence` (agent_id f07cfffa)

## Verdicts

| Requirement | Verdict |
|---|---|
| The acceptance oracle holds only behavioural tests | PARTIAL → remediated (see below) |
| Every oracle modification cites the requirement that made it necessary | PASS |
| The guard passes exactly when every modification is sanctioned | PASS |
| The baseline is recorded, not discovered | PASS |
| The existing modifications are resolved, not waved through | PASS |
| Property: guard-passes-iff-all-sanctioned / sanction-record-round-trips / oracle-contains-no-structural-test | PASS |
| Compile-Negative: sanction with no cited requirement / verdict without baseline | PASS |
| Formal Contract: sanctionVerdict (kernel + bridge) | PASS |

**Requirements: 8 PASS, 1 PARTIAL → remediated, 0 FAIL** — oracle tampering: none (all 8 moved test bodies byte-verbatim vs baseline; generators match declared strategies — Disposition set splits "names it" into NamesFile/NamesTest, readability 3-way; `coverConfig` testLimit 100→300 strengthens).

## PARTIAL → remediated: "The acceptance oracle holds only behavioural tests"

The spec's requirement is absolute — a test that asserts over a tool's source
text MUST reside in the shape suite — but enforcement is
`OracleSanctionGuard.classifyTest`/`structuralIn`
(`OracleSanctionGuard.scala:186–285`), a heuristic over shell tokens. The
current corpus is clean (reviewer re-implemented the classifier and ran it over
all 17 oracle files: zero findings; the enumerated property is genuinely
green), and all 8 known structural tests moved byte-verbatim. However the
detector has concrete under-match holes — each verified against the
re-implementation — through which a future structural test resides in the
oracle undetected:

1. **Unquoted literal bindings are never captured.** `binding` regex
   (`OracleSanctionGuard.scala:194–195`) binds only `X="..."` or `X=$...`
   values. `SRC=workflow/hooks/gate.sh` then `cat "$SRC"` → Behavioural.
   Ordinary bats style, not exotic.
2. **Extensionless tools.** `codeExt` covers only `.scala|.sh|.py|.ts|.js`
   (line 188). `cat "$root/bin/probatio"` — `bin/probatio` is a real
   extensionless tool — → Behavioural. `.jq` (e.g.
   `scanner/chain-state-report-contract.jq`) and `.bash` (`tests/helpers.bash`)
   likewise evade.
3. **`sourceTree` whitelist incomplete** (line 200):
   `workflow|scanner|hooks|bin|src|adapters` — `grep -rl needle
   "$root/verified"` over the `verified/` Scala tree → Behavioural.
4. **Helper-function indirection.** `structuralIn` classifies only `@test`
   block bodies; a source read inside a file-scope helper invoked from a test
   → Behavioural.
5. **`eval` and indirection.** `eval "$READV $SRC"`, `xargs cat`,
   `find -exec grep`, and double-variable indirection `$A$B` (only the first
   var is resolved, `varRef.findFirstMatchIn` at line 245) → all Behavioural.
6. **Unbound fixture-prefixed vars.** `fixtureRef` excludes
   `$FAKE*`/`$TEST_*`/`$BATS*`/`$FX`/`TEST_TMPDIR` from the resolved token; an
   env-provided `$TEST_SRC` pointing at real source → Behavioural.
7. **Column-0 `}` truncation.** `batsTestBlocks` ends a block at the first
   column-0 `}` (line 320); a mid-test column-0 `}` (e.g. inside a
   `{ ...; }` group) truncates the scanned body. Not present in the current
   corpus; latent.

Why tests missed it: the surgical-kill tests (`NonGoalsGuardSpec.scala:1284–1420`)
pin detection paths that exist, but nothing exercises these non-paths — the
enumerated property only confirms the current corpus happens to contain none
of these shapes.

Fix class: (3) targeted tests — add evasion-shape cases (unquoted binding,
extensionless tool, helper indirection, `verified/` tree) to the
surgical-kill block and extend `binding`/`codeExt`/`sourceTree` accordingly;
failing that, the spec should state the heuristic's under-match boundary as an
explicit limitation rather than imply detection.

## Remediation applied

**Detector changes** (`OracleSanctionGuard.scala`):

- `binding` now captures every assignment spelling — `name="$(…)"` (keeps
  inner quotes so fixture markers survive), `name="v"`, `name='v'`,
  `name=$v`, `name=literal`.
- `codeExt` widened: `.bash`, `.jq`, `.bats` are code, not documents.
- `sourceTree` widened (`verified`, `openspec`, `tests`, `project`) and now
  requires a WHOLE segment (`/verified/` matches, `verified-scala3-gate` does
  not); checked on RESOLVED tokens, not the raw line.
- Extensionless files under a source-tree segment are structural
  (`bin/probatio`); a basename that is an unresolved `$VAR` or carries a
  non-code dot is not.
- Variable resolution is a bounded fixpoint (10 passes) over `$X`/`${X}` —
  `$A$B` two-step indirection resolves; cycles settle instead of hanging.
- Lines are split into command SEGMENTS on unquoted `|` `&` `;` (quote state
  + backslash escapes tracked) — `tail f | jq -f contract.jq` executes the
  contract, it does not read it; only segments containing a read verb scan.
  `source` is a verb only when not a `--source`/`.source`/`$source` argument;
  `eval`/`find`/`xargs` added.
- `#`-led lines are prose, not commands.
- `batsTestBlocks`/`bindingsOutsideTestBlocks` share `testLineMarks` — brace
  depth, so a nested `{ }` group's column-0 `}` no longer truncates the body;
  block end is also delimited by the next `@test` header for adjacent blocks.
- `structuralIn` gains call-graph reachability: file-scope `name() {…}`
  helpers invoked (transitively) from a `@test` are classified and reported
  as `(file scope: name)`; bats lifecycle fns (`setup`, `teardown`,
  `setup_file`, `teardown_file`) are bats-invoked machinery — their reads are
  covered by file sanctions, matching this spec's existing disposition.
  Bare top-level executable lines report as `(file scope)`.

**Corpus false-positives found and fixed during remediation** (the widened
detector initially flagged 8 real tests): `jq -f` contract args (segment
splitting), `git_dir="$(cd "$FX" && …)"` truncated quoted binding losing the
`$FX` marker (`$(…)` binding alternative), `verified-scala3-gate` matching
`/verified` (segment boundary), `$ROOT/openspec/$doc` with unresolved loop
var basename (`extensionless` requires a concrete basename), `setup()`'s
launcher-follower read (lifecycle exemption + reachability).

**Tests added** (`NonGoalsGuardSpec`, Ring 8 remediation + Ring 5 surgical
sections): 13 evasion-shape tests pinning each closed hole plus
documented-boundary cases (setup machinery, cyclic bindings, depth cap);
13 surgical-kill tests for the remediation code's own mutant surface.
**114/114 green; enumerated property over the real corpus stays clean.**

**Ring 5 re-run on final code**: `stryker4s.conf` retargeted to the two
guard files moved to core main (same convention as the original run);
test-filter = NonGoalsGuardSpec + both contract specs. First post-remediation
run 80.63%/81.0% (43 survivors, all in the widened classifier) → 13
surgical-kill tests → second run **83.41% total / 83.78% covered** (286
mutants, low=80 PASS). Remaining ~24 live survivors judged
benign-equivalents: `segmentsOf` seed-flag flips scan the same segment for
verbs regardless; lifecycle-name literal removals leave those helpers
unreachable-from-tests anyway; `<=`→`==` and `next==s`→false converge
identically; `getOrElse` defaults are unreachable.

## Residual under-match boundary (recorded, accepted)

The detector remains a token heuristic: a source path smuggled through a
variable whose VALUE lives in another file (e.g. `helpers.bash` bindings, or
a value computed at run time), a `.`-sourcing form, or a chain deeper than
the 10-pass cap stays behavioural. These are stated in the object's docstring
and pinned by tests where deterministic.

## Observations (recorded, not violations)

- **Self-amendment is mechanically and semantically compliant.** The amended
  requirement "The existing modifications are resolved, not waved through"
  (spec.md:172–210) literally names all 11 sanctioned basenames and all 8 moved
  `@test` titles — and describes *why* each disposition applies. This is the
  intended use of the naming rule. CAVEAT: because the rule is verbatim-naming
  only, this requirement now names those 11 files permanently — a later commit
  can cite it for an unrelated edit to them. Inherent to the mechanical rule
  the spec defines, not an implementation defect.
- **All 25 real modifications are covered.** Reviewer computed
  `git log --name-only` since the recorded baseline `6b83766` (verified
  ancestor of HEAD): exactly 25 oracle modifications, all present in
  `oracle-sanctions.jsonl`, and every cited requirement's text verifiably names
  the file — including the 5 rows appended by `oracle-fixture-repair`.
- **`human-grant-lock.bats` semantic inversion** (diff hunk :246): "a grant
  does not leak across sessions" was rewritten into a test asserting durable
  cross-session grants — a behavioural spec change, not just a fixture repair.
  Sanctioned by an `oracle-fixture-repair` requirement that names the file
  (mechanical rule satisfied; comment cites an approved scoped exception), but
  the citation is loose relative to the change's semantics.
- **`isOracleFile` (`NonGoalsGuardSpec.scala:926–929`)** accepts any-depth
  `tests/**/*.bats` excluding `/shape/`, while the classification enumeration
  scans only `os.list(testsDir)` top-level. A future nested
  `tests/sub/x.bats` would need a sanction but evade structural classification.
- **`requirementTextOf` (line 1702)** takes `documents.headOption` of a sorted
  walk — two change dirs carrying `specs/<same-name>/spec.md` would consult
  only the alphabetically-first. No collision today.
- **`requirementNames` substring matching** (`OracleSanctionGuard.scala:51`):
  a basename that is a substring of another file's name would cross-cover (no
  real collisions); naming in a scenario/rationale counts, not just the
  requirement body. Per the mechanical rule, correct.
- **Old machinery removed including a real fail-open**: deleted
  `checkOracleImmutability` returned `Immutable(commit)` when `git show`
  failed; `migrationCommitsOnMain` fell back to "last 5 commits" when the
  `--grep` search failed. New design — recorded baseline +
  `None`→`Undeterminable` — eliminates both. `modificationsSince` returns
  `None` when the baseline is absent from history (fail-closed);
  `HistoryEntry.message` carried but never inspected (verified).
- **Bridge ghost arm is dead code** (`SpecLintBridgeSpec.scala:275–314`):
  ghost sanctions never match a history `(file,commit)` so `checkSanction`'s
  unresolvable/unrelated arms are unreached there — but exercised by
  `genSanctionFixture`'s `Nonexistent`/`Unrelated` dispositions in the main
  property; coverage exists elsewhere.
- `readSanctions` catches `Exception` broadly but maps every failure to `Left`
  — fail-closed; injection safe because `ujson.write` escapes within the line.
- `OracleSanction("","")` empty-string fields constructible but resolve to
  `RejectedUnresolvable`; compile-negative covers arity; semantics fail-closed.

## Dangerous-pattern hunt

`danger-scan.sh 8e9ccf6` clean; 9 candidates hand-judged — all justified. Every
`case _`/`catch` in the diff maps to a failing value (`Left`/`Rejected`/`false`),
never a pass. The one silent drop (`parseGitHistory` dropping a non-40-hex
chunk, `NonGoalsGuardSpec.scala:1666`) is unreachable given
`git log --format=%x1e%H%x1f%s` always emits 40-hex `%H`, and a dropped
baseline entry degrades to `Undeterminable`, not pass.

## Could not verify

- Whether the suite runs green (`sbt test` not run by the reviewer); verified
  by construction that live guard inputs (real history, real record, real spec
  tree, real titles) produce `AllSanctioned(6b83766)`.
- Step-2-before-Step-3 ordering claim (would require commit narration);
  generator strategies match the spec's Properties section literally.
- `stryker4s.conf` retargeting (outside the diff's surface).
