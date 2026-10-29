# Ring 8: Adversarial Spec-Compliance Review — gate-event-compatibility (re-run)

**Supersedes**: `ring8-gate-event-compatibility.md` (subagent `c39873b4`) — the human
declined to attest that session's freshness, so the review was re-run in a verifiably
fresh context.

- **Fresh context**: yes — isolated read-only subagent `devin-subagent-437b30fb`,
  launched 2026-09-23. Inputs limited to: the spec
  (`specs/gate-event-compatibility/spec.md`), the approved typed contract, the
  implementation diff against the Step-0 baseline, and the mechanical danger-scan
  result. No implementation conversation, no progress tracker, no prior review.
- **Baseline**: `2950ad10a4a0b63bf1540efb07282781ba1315ac`
- **Diff reviewed**: `stryker4s.conf`; `workflow/core/.../EventDispatch.scala` (new);
  `workflow/cli/.../SubcommandEntrypoints.scala`; `verified/.../DispatchKernel.scala`;
  `workflow/cli/test`: `EventDispatchFixtures.scala` (new), `GateBridgeSpec.scala`,
  `GateEventSpec.scala`, `SubprocessConformanceSpec.scala`;
  `workflow/core/test`: `GateEventCompletenessTypeContract.scala`
- **Dangerous patterns**: 2 production arms + test-side wildcards — all justified
  (the `Injection → "SessionStart"` envelope arm verified against predecessor `*)`
  at `gate.sh.predecessor.bak:1761`; `Injection → consumesPayload=false` matches
  predecessor `NEEDS_PAYLOAD=0`); mechanical scan: OK, no unjustified patterns
- **Oracle tampering**: none — the one rewritten test (reject→injection) is
  spec-mandated supersession and its assertions are STRICTER
- **Requirements**: **4 PASS / 0 PARTIAL / 0 FAIL** — properties 4 PASS,
  compile-negatives 2 PASS, Ring-6 `classify` contract PASS

## Verdicts

- A recognised event name dispatches to its tier: **PASS** — `recognisedTable` is
  the single (token→event) source, identical pairs/order to the old `parseEvent`;
  exhaustive match on the sealed enum makes misrouting unconstructible
- An unrecognised event name routes to the injection tier: **PASS** —
  `runInjection` has no `Finding`/`Undetermined` path; the old
  `case None → Finding` arm is deleted; empty name tested directly
- The supplied name survives into the diagnostic output: **PASS** — unconditional
  stderr line before `runBanner`; heartbeat/trace carry the name verbatim via
  `dispatchToken`, matching predecessor `$EVENT`
- The payload envelope reports the harness event name: **PASS** — `Tier →
  GateEvent.harnessName`, `Injection → "SessionStart"` verified against the
  predecessor `*)` arm directly
- Properties `event-dispatch-is-total`, `recognised-names-never-fall-back`,
  `unrecognised-names-exit-clean`, `parity-with-predecessor-on-event-dispatch`:
  **PASS** (incl. shipped-set vs independent oracle-set comparison)
- Compile-negatives (failable classification; injection without supplied name):
  **PASS**
- Ring-6 kernel mirror: **PASS** — `classifyEvent` postcondition clauses 2/3 are
  non-vacuous, `recognisedEventNamesDistinct` self-locks the closed set, the
  bridge genuinely discriminates shape/name/tier-payload drift; the name-code
  abstraction's blind spots are covered by the enumerated oracle-set comparison

## Review mechanics note

The initial diff supplied to the reviewer was path-scoped and omitted
`verified/.../DispatchKernel.scala`; the reviewer flagged the omission rather than
assuming coverage, the hunk was supplied as a supplement, and the updated verdict
above covers the complete implementation diff. The reviewer's overall statement:
"the implementation is faithful, total, predecessor-exact, and genuinely pinned —
I could not construct a spec violation that survives the current tests."

## Non-blocking observations (no verdict change)

1. `GateEventSpec.scala:4395` `distinct.length == length` on a constant oracle
   table is vacuous; real injectivity covered by per-name asserts + the
   enumerated property. Cosmetic.
2. `genUnrecognisedName` consults the shipped `recognisedNames`; mitigated by the
   independent oracle-set comparison. Adequate defense-in-depth.
3. `SubprocessConformanceSpec.portedEventExit` maps `Ran(n) => n` while the real
   boundary collapses `Ran(_) → 0`; coincides today (gate emits only `Ran(0)`).
   Latent mis-model, not a defect for this spec.
4. The six-event envelope coverage lives in `GateBannerCompatSpec`, not the
   bats artifact the PO table names. Obligation enforced at a different artifact.
5. The `workflow-hygiene.bats` control-parity exit criterion requires a rebuilt
   native image at suite-run time; not verifiable from the diff.
6. (`--event` absent → Finding vs predecessor's session-start default) — known
   residual parity gap carried over from the prior review; outside the spec's
   supplied-name domain, pre-existing at baseline.
