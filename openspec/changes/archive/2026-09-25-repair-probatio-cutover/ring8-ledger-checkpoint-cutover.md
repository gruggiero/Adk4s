# Ring 8 — Adversarial Review: ledger-checkpoint-cutover (pass 2)

**Reviewer**: isolated read-only subagent `027ab241` — fresh context
(inputs: spec + typed contract + post-remediation 40-file diff
`/tmp/spec8-diff-r8pass2.patch` only; no implementation conversation,
no first-pass report).
**Date**: 2026-09-24

## Verdict: APPROVE

All five first-pass remediations confirmed genuinely landed.
Requirements 4/4 PASS, scenarios 12/12 enforced, properties 3 PASS /
2 PARTIAL, compile-negatives 3/3 enforced (one via documented
equivalent mechanism), formal contract PASS (671/671 VCs), exit
criterion PASS (machinery + on-disk swap state). 0 new dangerous
patterns. No oracle tampering.

## First-pass remediation verification

| # | Remediation | Verdict |
|---|-------------|---------|
| 1 | Fractional-second timestamp rejected | FIXED — `Validator.timestampPattern` is the contract's exact fixed-second shape; deterministic pins both sides + mutations + edge rows |
| 2 | Whole integral-JSON-number domain | FIXED — `isWhole` domain, `BigInt` fields (`v`/`exit`/`wallTime`, `ClaimVerdict.observed`, replay/verify paths); 0 residual `.toInt` on record fields |
| 3 | `authoriseSwap` covers files absent from `comparison.files` | FIXED — expected scope = exercising domain; absent rows synthesised absent-absent, refusal names them |
| 4 | `RingEvidence` outcome-without-record unconstructible | FIXED — final class + private ctor; `unevidenced`/`evidenced` smart constructors; compile-negatives pin ctor and `.copy` |
| 5 | Conformance generator matches declared strategy | MOSTLY FIXED — constructive `genLedgerRecord` judged by real `jq -e -f`; residual gaps → findings 1–2 |

## Findings (remediated below)

1. **[PARTIAL → FIXED]** Conformance generator did not produce the
   spec's declared edge values — `spec.md:175-179` declares
   "records with empty and non-ASCII field values" and "augmented
   with the committed fixture corpus". No domain emitted `""` for
   optional strings or non-ASCII text; fixture rows were jq-checked
   only in a separate non-property test; minimal/all-optional
   records arose ~1% per draw rather than constructively.
   **Remediation**: optional-string generators now draw from pools
   containing `""` and non-ASCII values; the committed fixture
   corpus is a generated-domain class (every fixture row is judged
   against `jq -e -f` inside the property); minimal (no optional
   fields) and all-optional records are constructive edge classes,
   not sampling accidents.

2. **[PARTIAL → FIXED]** Round-trip generator sampled the optional
   power set rather than covering it — `spec.md:196-198` requires
   the subset "drawn constructively over the full power set".
   **Remediation**: the property now enumerates all 32 subsets of
   the five optional fields constructively (mask-driven), so every
   combination of present/absent optional fields is covered on
   every run.

## Observations (recorded, non-blocking)

3. `authoriseSwap` empty-scope refusal diverges from the literal
   vacuous-truth property — documented `hasEvidence` strengthening
   approved at the Step-1 gate; kernel authorises vacuously per the
   spec's own contract text; bridge domain excludes the empty case.
4. Dead post-validation fallbacks in `runVerify` (`getOrElse` on
   `ring`/`exit`) — unreachable behind `validateFull`; recorded.
5. `LedgerRecord`'s `private[core]` ctor admits wire-inexpressible
   `BigInt` states inside the package — unreachable from the wire;
   byte-compatibility over the contract domain holds.
6. `RingEvidence.evidenced` admits `Unevidenced` status — the
   reverse inconsistency; never produced by `classify`.
7. Public case-class constructors on `FileComparison`/
   `DifferentialResult`/`GateRecord`/`ShimSwap` — forgeable outside
   the measured flow, but all in-flow inconsistencies are
   conservative (incomplete-or-worse ⇒ refuse).
8. `performSwap` overwrites the live seam file unconditionally —
   correct for the shipped state (live == canonical shim,
   `.bak` == predecessor); the `predecessorRel == seamRel` backup
   branch is dead code.
9. spec.md inventory staleness — `RingEvidence` "final case class",
   `LedgerRecord` "private constructor" (`private[core]`), and the
   literal `Subcommand.fromString("update")` compile-negative
   (amended enforcement documented; spec text records the literal
   form).
10. Spec-7 `contractAccepts` conformance property uses a Scala
    restatement of the contract (vacuous `true == true` on
    conforming-only domain) — superseded by the spec-8 property's
    real `jq -e -f` judgment; recorded as drift risk, not oracle
    tampering.
11. `SeamSwapExec` `ToolId.valueOf` throws on an invalid
    `PROBATIO_SEAM` — test-runner entry point only.
12. 12-clause decode surfaces (`LedgerRecord.from`,
    `Validator.validate`, `LedgerData` ReadWriter) accept
    R8-without-session / non-ambient `source` — every shipped read
    path goes through `validateFull`; dormant elsewhere.

## Dangerous-pattern audit

- `case _` arms (~15): all map wrong-typed/invalid input to
  `Left`/`None`/throw — never to a valid value. 0 silent
  enum→valid mappings.
- `getOrElse`: `Map.getOrElse` (correct domain semantics), CLI
  flag defaults, dead post-validation fallbacks (observation 4).
  0 live silent-fallback-to-zero.
- No `.get`, `isInstanceOf`, `asInstanceOf`, `Any`, or mutable
  `var` in the reviewed paths.
- 0 residual `.toInt` on record fields.
- `sys.error`/`require` — rejection on violated invariants,
  codebase convention.

## Oracle-tampering verdict

NONE. The conformance property executes the real `jq -e -f`
against the committed contract on every generated row; the two
stale beyond-Int32 tests were inverted to the contract's actual
domain (correct direction); generators are constructive, not
filtered.
