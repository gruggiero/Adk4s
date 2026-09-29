# Ring 8: Adversarial Spec-Compliance Review — gate-event-compatibility

Fresh context: yes (isolated read-only subagent; inputs limited to the spec,
the approved typed contract, and the baseline diff — no implementation
conversation)
Baseline: `2950ad10a4a0b63bf1540efb07282781ba1315ac`
Diff reviewed: `workflow/core/.../EventDispatch.scala` (new),
`workflow/cli/.../SubcommandEntrypoints.scala` (parseEvent / runEvent /
runInjection / emitBanner / dispatchToken / prologue),
`verified/.../DispatchKernel.scala` (classifyEvent mirror), plus
GateEvent.scala, HarnessPayloadReader.scala, GateStateDir.scala,
Outcome.scala, ExitCode.scala, ProbatioMain.scala, HelpRegistry.scala,
hooks/gate.sh, bin/probatio, hooks/gate.sh.predecessor.bak, adapters,
gate-hookjson-contract.jq, workflow-hygiene.bats, gate-payload.bats, and all
five named test artifacts.

Dangerous patterns found: 0 unjustified (danger-scan clean; every
wildcard/fallback arm is predecessor-parity and `danger-scan:allow`
annotated — Injection→"SessionStart" at SubcommandEntrypoints.scala:1663,
lenient `case _ :: rest` at :110, NonFatal fail-open sites).

Oracle tampering: none — the superseded "still rejected" scenario was
rewritten per the spec's mandated semantic change and *strengthened*
(diagnostic name, output presence, grant absence, heartbeat fidelity,
envelope name); generators match the spec's declared strategies exactly.

Requirements: **11 PASS, 0 PARTIAL, 0 FAIL** (4 requirements + 4 properties +
2 compile-negatives + 1 Ring-6 contract).

## Verdict per requirement

- Req (recognised name → own tier): PASS — single injective
  `recognisedTable`; enumerated property compares the shipped set against
  the fixture's *independent* `recognisedPairs`; end-to-end test asserts no
  unrecognised-name diagnostic for any recognised name.
- Req (unrecognised → injection, clean status): PASS — Injection →
  `runInjection` → `runBanner` → `Outcome.Ran(0)` on every path incl.
  fail-open catch; no path reaches Finding/Undetermined; empty name
  covered.
- Req (supplied name in diagnostics): PASS — stderr names it verbatim,
  trace + heartbeat carry `dispatchToken` = supplied; recognised names
  produce no such line.
- Req (envelope carries harness event name): PASS — Tier →
  `GateEvent.harnessName`; Injection → `"SessionStart"` = predecessor's
  `*)` arm (verified vs gate.sh.predecessor.bak:1758-1762); jq contract
  admits only SessionStart/UserPromptSubmit.
- All 4 properties PASS; both compile-negatives PASS; Ring-6 contract PASS
  (postcondition verbatim + injectivity/distinctness lemmas; bridge
  re-checks the tier arm's own token).

## Specific adversarial checks

- Verdict change for recognised events: none — Tier arms preserve prior
  semantics (prompt-submit still clears refusals + writes grants under
  `stateDir` guard, matching predecessor :1573).
- Prompt-submit side effects on unrecognised names: none — Injection never
  touches `clearRefusals`/`writeSessionGrants`; grant absence asserted.
- classify totality/injectivity: total by construction, six distinct keys.

## Observations (not spec violations)

1. `--event` flag entirely absent → `Outcome.Finding` (exit 1), while the
   predecessor defaults `EVENT="session-start"` → banner + exit 0
   (predecessor :59). Outside the spec's quantified domain ("a supplied
   event name"); pre-existing at baseline — recorded in
   implementation-progress.md as a known residual parity gap.
2. `eventToken` duplicates the token table in reverse for heartbeat/trace
   — a permutation would escape tests; correct by inspection.
3. `portedEventExit` maps `Outcome.Ran(n) → n` but the binary maps
   `Ran(_) → Clean → 0` — conservative direction only; all gate paths
   return `Ran(0)`.
4. ~~Weak assertion `err.contains(name)` — vacuous on names that are
   substrings of the fixed diagnostic~~ **REMEDIATED**: the three
   diagnostic assertions now assert the quoted name
   (`err.contains("'xyzzy-plugh'")`,
   `"unrecognised event 'user-prompt-submit'"`,
   `"unrecognised event 'inject'"`, and `s"'$name'"` in the property).
   Safe: the generator alphabet contains no `'` characters.
5. Prologue is not NonFatal-guarded — a malformed `--repo` throws for any
   event while the predecessor exits 0 via the relevance guard. Latent,
   pre-existing, outside the generator domain.
