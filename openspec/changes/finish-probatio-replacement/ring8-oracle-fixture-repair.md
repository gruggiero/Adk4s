# Ring 8: Adversarial Spec-Compliance Review — oracle-fixture-repair

Fresh context: yes (isolated read-only subagent; inputs: spec + typed contract + baseline diff only — no implementation conversation)
Baseline: `b414a80`   Diff reviewed: `chain-state.bats`, `human-grant-lock.bats`, `oracle-ordering-lock.bats`, `oracle-sanctions.jsonl`, `GateCmd.scala`, `GateEventSpec.scala`, `GateDecisions.scala`, `ToolNameSource.scala` (new), `GateDecisionSpec.scala`, `GateEventCompletenessTypeContract.scala`, `ToolNameSourceTypeContract.scala` (new)
Reviewer session: `devin-subagent-ring8-oracle-fixture-repair` (agent_id 867ad9f5)

## Verdicts

| Requirement | Verdict |
|---|---|
| The lock fixtures supply the tool name a harness supplies | PASS |
| An absent tool name keeps parity and is stated | PARTIAL → remediated (see below) |
| MUST-CONFIRM: the Devin payload's tool-name field | PASS (externally confirmed against docs.devin.ai) |
| The Devin adapter delivers the tool name | PASS |
| The correctness-verdict fixture failures are root-caused before they are fixed | PASS |
| Property: lock-decision-is-independent-of-name-case-and-channel | PASS |
| Property: absent-name-always-allows-and-says-so | PASS |
| Compile-Negative: possibly-empty String at the decision site | PASS |
| Scoped exception (human-grant-lock test 9 re-assertion) | VERIFIED — both legs genuinely exercise `findAnySessionGrant` |

**Requirements: 7 PASS, 1 PARTIAL, 0 FAIL** — oracle tampering: none.

## PARTIAL → remediated

**Req "An absent tool name keeps parity and is stated"** — the
`VERIFIED_SCALA3_ALLOW_PATHS` prefix short-circuit (`GateCmd.scala` allowPrefix
arm) returned `Ran(0)` before `preExecution` ran: an absent name on an
allow-listed production path allowed *silently* — the requirement admits no
silent allow.

**Fix applied** (trace only, verdict untouched): the prefix arm now matches
`toolName` and appends `— no tool name supplied` on `Absent`. The established
prefix phrase `file under allow-listed path $prefix, allow` is preserved as an
intact leading substring.

**Tests added** (`GateEventSpec`, spec-6 section):

- `an absent tool name on an allow-listed production path still states the
  absence` — `VERIFIED_SCALA3_ALLOW_PATHS` set + no `--tool` → `Ran(0)` +
  `allow-listed path` + `no tool name supplied`.
- `a --file flag suppresses the payload tool_name and the absence is stated` —
  pins the diagnostic leg of the `--file`-suppresses-payload arm (the verdict
  leg was already pinned at `"the --file flag suppresses the payload's
  tool_name"`).

Post-remediation: `GateEventSpec` 144/144 green.

## Observations (recorded, not violations)

1. `ToolNameSource.Supplied("")` remains publicly constructible despite the
   docstring's non-empty invariant — its verdict is predecessor-parity
   (`""` ∈ `readOnlyTools` → allow) and it is unconstructible through the CLI
   mapping (empty flag/payload → `Absent`). A smart constructor would close
   this; flagged for the checkpoint.
2. Proof-obligation table mapped "a read-only tool is not blocked" to
   `oracle-ordering-lock.bats`, which carries no read-only invocation — the
   scenario is verified in `GateEventSpec` (supplied `Read`) and
   `GateDecisionSpec` (read-only name set). **Spec table amended** to name the
   real artifacts.
3. `payload=None` + `--file`-absent fold arm is structurally identical to the
   tested arm — low risk, noted.

## Dangerous-pattern hunt

`danger-scan --also` over the six spec-6 files: 13 catch-all findings, ALL in
pre-existing regions (before the spec-6 sections). The single catch-all in new
code carries a same-line `danger-scan:allow`. Independent review of the diff:
the `fold(Absent)(Supplied)` mapping is clean (explicit empty rejection); the
`preExecution` match is exhaustive on the sealed enum; the bats `--tool Edit`
additions and resolved-SHA `BASE` *strengthen* the tests (rows previously
unmatchable; the absent-name bypass previously masked failures).
