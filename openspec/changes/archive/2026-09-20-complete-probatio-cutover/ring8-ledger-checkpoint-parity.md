# Ring 8 — Adversarial Spec-Compliance Review: ledger-checkpoint-parity (Spec 7)

**Review mode:** fresh context (read-only reviewer, spec + contract + diff only; no
implementation conversation carried over)
**Scope:** `git diff 2c379e2` — all Spec 7 production and oracle sources
**Verdict:** 3 PASS / 2 PARTIAL / 1 FAIL (vacuous-marker finding folded under the
`--rings` and marker-decision findings)
**Disposition:** all findings verified against predecessor source; remediated;
re-run evidence below.

## Requirement-by-requirement findings

### FAIL — checkpoint marker must only be written when all rings evidenced and discharged

`CheckpointEngine.report` re-filtered the supplied records by
`r.baseline == baseline` after `LedgerCmd.readRowsFiltered` (the read path) had
already applied the change/spec/baseline filter and `--forgive-unchanged`
disposition. The predecessor (`checkpoint.sh:229` → `ledger.sh read
--forgive-unchanged`, then `:245` select by ring only) treats the ledger read as
the filtering authority: forgiven stale rows are emitted with their *stored*
baseline and count as evidence. The engine's re-check dropped every forgiven
row, reporting `unevidenced` where the predecessor reports green.

**Verified:** `ledger.sh` read emits the stored baseline verbatim on forgiven
rows; `checkpoint.sh` selects by ring only.

**Remediation:** `CheckpointEngine.report` now selects
`records.filter(_.ring == ring).lastOption` — no baseline conjunct. The oracle
(`CheckpointParitySpec`) was retargeted to pin the delegation contract: supplied
records are already filtered; a stale-baseline record supplied by the engine's
caller is valid evidence. New CLI end-to-end tests in `LedgerParitySpec` drive
`readRowsFiltered` → `CheckpointEngine.report`: a stale row without forgiveness
is excluded (ring unevidenced); with forgiveness it is retained and the ring is
green.

### FAIL/PARTIAL — ring-evidence selection must not accept invalid or vacuous `--rings`

`--rings ","` (and `",,"`, `"R0,"`, `",R0"`) split into empty elements; the
parse branch validated `Ring.fromString(n).isEmpty` but the success branch then
used `Ring.fromString(n).getOrElse(Ring.R0)` — a silent fallback to a *real
ring* on unparseable input, and an empty split produced a vacuous report/marker.
The predecessor (`checkpoint.sh`) names every unparsable ring token and exits 2;
empty tokens are unrecognised rings, not defaults.

**Verified:** predecessor `split(",")` + per-token `R[0-9]|manual` match, die on
first unknown.

**Remediation:** parse via `split(",", -1)` (trailing empties preserved) and
`flatMap(Ring.fromString)`; empty elements fail `Ring.fromString` and are
rejected by name with the predecessor's `unrecognised ring in --rings: <tok>`
message. The `getOrElse(Ring.R0)` fallback is gone from the shipped path.
Regression tests: `CheckpointCmdSpec` covers `","`, `",,"`, `"R0,"`, `",R0"`;
`LedgerCheckpointParitySpec` adds a corpus entry asserting both sides reject
`--rings ","`.

### PARTIAL — replay verdict classification duplicated in the shipped path

`runVerify` re-implemented the judgment-ring set and exit comparison inline
rather than classifying through `ReplayVerdict`, so the typed model and the
shipped path could drift apart.

**Remediation:** added `ReplayVerdict.classifyName(ring: String, ...)` — `R8`
and `manual` are `Unreplayable`; other names delegate to `ReplayVerdict.classify`.
`runVerify` now reads `ReplayVerdict.unreplayableRings` for the judgment set and
classifies every row through `classifyName`; the `Matches`/`Diverges`/`Unreplayable`
arms map to the predecessor's counted/recorded-disagreement/replay-failure
emissions.

### PARTIAL — marker eligibility computed by a self-contained `allGreen`

`CheckpointReport.of` computed `markerPermitted` as
`all rings Green && unresolved == 0`, which a hand-constructed
`RingEvidence(Green, record = None)` would satisfy — green without evidence.

**Remediation:** `of` now delegates to `CheckpointEngine.markerDecision`, which
requires `status == Green && record.isDefined` per requested ring plus
`unresolved == 0` — the same decision the bridge spec proves equivalent to the
verified kernel.

### PARTIAL — non-UTF-8 / TOCTOU read failures escape as JVM crashes

`SubcommandWiring.readLedgerFile` had no catch around `Files.lines`/iteration:
`MalformedInputException` (non-UTF-8 bytes) and TOCTOU `IOException`s propagated
as crashes instead of `UNDETERMINED` outcomes. `appendLedgerLine` used
`.last` on `readAllBytes` (crashes on truncation) and embedded
`UNDETERMINED —` inside its reason (double-marker when the caller re-prefixed).

**Verified:** predecessor maps every unreadable condition to
`ledger: UNDETERMINED — …` (stderr, exit 2); missing file is a distinct
`no ledger at F` (stdout, exit 2).

**Remediation:** `readLedgerFile` wraps the read in `try`/`catch NonFatal` →
`Outcome.Undetermined("could not read …")`. `appendLedgerLine` uses
`lastOption` and skips the separator when the file read yields none; its reason
is the bare `could not append to …`. `runRead` now distinguishes the missing
file (stdout `no ledger at …`, exit 2) from other failures (stderr
`UNDETERMINED`, exit 2), matching `runVerify`'s triage.

### PARTIAL — argument grammar divergences

- `--exit "007"`: the port normalised via `toIntOption`; the predecessor
  requires the canonical integer grammar `^-?[0-9]+$` **as written** and
  rejects `007`. Fixed: canonical-grammar check (`canonicalInt`) runs before
  the session check, in the predecessor's order (missing fields → exit
  grammar → session), with predecessor messages (`exit must be an integer in
  -999..999 as written`, bare `missing required field(s): change …`).
- Forbidden flags in `run`: `--source`/`--exit` were rejected *after* parse;
  the predecessor dies at parse position, leftmost-first, and takes `--` as a
  flag's *value* verbatim. Fixed: `parseArgs` gained a `forbiddenFlags`
  overload checked in flag position before `MissingValue`;
  `splitAtDoubleDash` is value-aware (a `--` consumed as a flag's value is
  data, not a separator).
- `regenerateTasks` pass 1 used the *last* matching `### N.` section's
  completion state; the predecessor's `awk '{print; exit}'` takes the first.
  Fixed: `find` (first match) instead of last.
- `runRunMode` artifact resolution used `repoRoot.resolve(artifact)`;
  predecessor is string concat `$repo_root/$ARTIFACT`. Fixed.
- `ledger read` missing-ledger message/stream corrected (see above).

`LedgerCheckpointParitySpec` adds corpus entries asserting both sides reject
`--exit "007"` and `--rings ","`.

## Oracle weaknesses found (fixed in the oracle, not the implementation)

1. `marker-when-all-evidenced` derived its expectation from `report.rings` —
   self-referential: a broken report could satisfy it. Retargeted: the expected
   marker state is computed independently from the generated inputs via a
   test-local replica of the R8 session ladder.
2. The superseded-baseline scenario only exercised the engine in isolation and
   silently encoded the re-filter bug. Retargeted to the delegation contract +
   new end-to-end `readRowsFiltered` → engine tests (stale-excluded /
   forgiven-evidenced).

## Post-remediation evidence

| Ring | Result |
|------|--------|
| Ring 0 compile | verified + core main/test + cli main/test clean |
| Ring 1 scalafmt | stable after comment moves |
| Ring 1 scalafix | clean on spec-7 sources; 4 pre-existing `NoSystemGetenv` baseline errors in untouched `GrantWaiver.scala`/`PredecessorCheck.scala` (unchanged, recorded) |
| Ring 1 danger-scan | `danger-scan.sh 2c379e2 --also <4 new mains>` → OK, no unjustified patterns |
| Ring 3 core suite | 538/538 green |
| Ring 3 cli suite | 390/390 green |
| Ring 3 OracleDiff | PROCEED — no file worse than predecessor across all 17 bats files |
| Ring 4 wire compat | `jq -e -f ledger-record-contract.jq` over persisted rows — 8/8 green |

## Out-of-scope confirmations

- `ProbatioMain.scala` / `MulticallDispatch.scala` flagged as review scope
  candidates: **not** in the spec-7 diff (`git diff 2c379e2`), confirmed
  unrelated — not danger-scan targets.
- Ring 5/6 evidence is recorded separately in `implementation-progress.md`
  after execution; this report covers the Ring 8 gate only.
