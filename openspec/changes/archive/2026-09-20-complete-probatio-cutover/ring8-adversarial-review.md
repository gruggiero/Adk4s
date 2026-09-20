# Ring 8: Adversarial Spec-Compliance Review — gate-event-completeness

```text
Fresh context: yes (read-only explore subagent, spec + typed contract + full diff only)
Baseline: 5cebe3e0fa79be5fb2145df9a12599779e75fc12
Requirements: 3 PASS, 4 PARTIAL, 0 FAIL → remediated (see Disposition)
Obligation rows: 15 PASS, 7 PARTIAL, 2 FAIL, 1 deferred → remediated
Round 2 (fresh re-review, post-remediation): 13 items VERIFIED-FIXED,
  1 MAJOR — ownedFileCheck on non-production paths (F1)
Round 3 (focused re-verify of F1 fix): see §10
```

## Findings and disposition

### 1. FAIL — `ToolOutcome.classify` `.toInt` overflow — FIXED

`"Error: Exit code <digits>"` with ≥11 digits threw `NumberFormatException`,
propagating out of the never-blocking post-bash tier. Fixed at
`ToolOutcome.scala:75-86`: `toIntOption` — an unrepresentable code classifies
as `Skip(NotACommandOutcome)`, matching the predecessor's observable result
(the digit string is captured, the ledger's own `-999..999` grammar check
rejects the row, exit 0). Boundary regression test added in `ToolOutcomeSpec`
(`Int.MaxValue` still classifies; `MaxValue+1` does not). Belt-and-suspenders
boundary guards added on both Tier A′ dispatches (`PostEdit`, `PostBash`) —
the predecessor's `_post_bash_exit0` discipline is now encoded at the tier
boundary, not just inside `classify`.

### 2. FAIL — blocking added/relaxed vs predecessor — PARTIALLY CONFIRMED

- `advancePhase` (GateDecisions.scala:121-131): **real relaxation** —
  `implementation → verified` now requires `redExists && greenExists`
  (predecessor: `red_exists=1 && green_exists=1` at gate.sh.bak:909-911),
  not `greenExists` alone. Fixed.
- `ownedFileCheck` on non-production expected-file paths: ~~spec-added
  surface~~ — **REVISED in round 2 to a real violation (F1), now FIXED**.
  Round 1 misdispositioned this: the spec obligation row "No blocking
  behaviour was added or relaxed relative to the predecessor" is the
  higher authority, and the Step-2 oracle had pinned the block on a
  *non-production* fixture (`src/A.scala` lacks `/src/main/`). The
  predecessor consults Expected-Files ownership only inside the
  production branch (gate.sh.bak:733+); non-production paths allow
  unconditionally after Step-0 grant handling (gate.sh.bak:~668
  `non-production path, allow`). Reachable in-repo: spec 9's Expected
  Files include `build.sbt` — port blocked, predecessor allows.
  Remediation: `ownedFileCheck` deleted; `nonProdLock` allows after the
  Step-0 branch; the scenario fixtures re-pointed at a production path
  (`src/main/scala/A.scala`, two-spec impl-order fixture) where the
  faithful priors-only predecessor check runs; new regression test pins
  the F1 input (declared `build.sbt` + uncheckpointed prior → allow).
  Second divergence uncovered by the fix: `ownedFileCheck` had emitted
  `Finding` (exit 1) for the block; the predecessor's tool-call block is
  `exit 2` (`Undetermined`) — the oracle assertion was corrected too.
- "Presentation-marker orphan check conditional on expectedFiles": reviewer
  misread — the predecessor check over priors runs unconditionally once the
  phase is past oracle in both implementations (SubcommandEntrypoints.scala:962-989
  mirrors gate.sh.bak:946+). No divergence found on re-check.

### 3. PARTIAL — adapter-config coverage hard-coded — FIXED

New scenario test `every adapter-configured event name is handled` in
`GateEventSpec` extracts `--event` tokens from the three real adapter
configs (`devin.hooks.v1.json`, `claude.settings.json`, `pi/verified-scala3-gate.ts`
— both inline and array forms) and asserts each parses through `GateCmd.run`
without rejection. A new adapter event now fails the test.

### 4. PARTIAL — compile-negatives are positive controls — STRENGTHENED

`java.nio.file` genuinely was compilable inside `GateDecisions` (classpath
member). Added a scoped DisableSyntax rule `NoIOInProbatioCore` to
`.scalafix.conf` banning `java.nio.file.*`, `java.io.*`, `scala.io.*`, and
`scala.sys.*` in `workflow/core/src/main/scala/**` — a real file-I/O
violation in the decision layer now fails `scalafixAll`. (`java.nio.charset`
remains permitted — `SessionId` encoding is not an I/O surface. `System.getenv`/
`sys.env` were already banned by the global rules.) The `GateEvent`
exhaustiveness pin stays a positive control: `compileErrors` cannot express
a warning-escalated exhaustivity failure (verified empirically at Step 2);
`-Wconf:name=PatternMatchExhaustivity:e` in build.sbt is the real enforcement.

### 5. PARTIAL — probe `.cwd` fallback unreachable — FIXED

`GateCmd.run` now reads the channel for `--check-installed` when `--repo`
is absent, threading `payload.cwd` into `resolveRepo` — matching the
predecessor's `NEEDS_PAYLOAD || -z REPO` stdin read
(gate.sh.predecessor.bak:157-162).

### 6. PARTIAL — `stdinHasInput` trade-off — ACCEPTED, documented

`System.in.available() > 0` vs `[ ! -t 0 ]`: a harness that opens stdin
before writing can race past the read. Deliberate — the alternative blocked
forever on open silent pipes under bats/sbt (observed: 25-min hang in
`ambient-capture-wiring`). Every real harness writes the payload before
exec completes; the trade-off stands and is documented in `run`'s docstring.

### 7. PARTIAL — unreadable-state generator — EXTENDED

`unreadable-state-allows` now covers three shapes: file-instead-of-dir,
corrupt-JSON heartbeat, and marker files whose names fit no gate glob.
(Malformed *phase* content is deliberately excluded — the predecessor reads
it as `oracle`, which *blocks* a production tool-call; it is fail-closed by
design, not a fail-open shape.)

### 8. PARTIAL — envelope property reimplemented jq — FIXED

`envelope-conforms-to-contract` now pipes every non-empty hook-json output
through the real `jq -e -f gate-hookjson-contract.jq` process instead of a
Scala predicate. Generator scope (session-start/prompt-submit only) is
correct and now documented: the contract admits only those two
`hookEventName` values; post-edit's `PostToolUse` envelope is a different,
spec-prose contract.

### 9. Missing `GateBridgeSpec` — FIXED

`workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateBridgeSpec.scala`
created: `bridge-refusalBudget` and `bridge-classifyOutcome` properties run
shipped `RefusalBudget.apply` / `ToolOutcome.classify` against the Stainless
`GateKernel` mirrors on generated inputs. `carriedCode` is bounded to `Int`
(the shipped classifier's representable range; the kernel models the digit
string as unbounded `BigInt`).

### 10. Round 3 — focused re-verify of the F1 fix — VERIFIED-FIXED

Fresh-context review traced every `runToolCall`/`nonProdLock`/`grantLock`/
`oracleLock` branch against gate.sh.bak:388–1010. No code path lets a
non-production file block: `nonProdLock` handles Step-0 targets via
`grantLock` (predecessor-exact: spec-dir exemptions, required-spec
selection, two-stage grant resolution, bounded `grant-refused` marker,
`toolCallRefusal` = stderr+exit-2 / `decision:block`+exit-0), then allows.
`owningSpec` is consulted only inside `oracleLock` — the production
branch, matching pred:779–803. The new regression test pins the exact F1
input and would have failed pre-remediation. Two informational
residuals, neither reachable in practice: a `FILE_PATH` ending exactly at
`…/specs/` matches the predecessor's trailing `*` glob (`target_spec=""`
→ grant scan) where `step0Target` returns `None` — only reachable via a
trailing-slash non-file path; and `orderedSpecs` is computed after the
spec-dir exemption rather than before — no observable difference. The
stale `nonProdLock` scaladoc was corrected.

## Deferred

- The deferred bats differential runbook (predecessor on disk) — unchanged
  obligation, still pending. Ring 5 executed: PASS A 95.65% covered-code
  (1 equivalent survivor); PASS B 91.91% in-diff covered after 4 remediation
  rounds (≈94% counting verified phantoms + post-run kills). Ring 6
  executed: `GateKernel` 401/401 VCs valid via inductive-lemma
  restructure; `GateBridgeSpec` 2/2 green.
- `GateEventSpec` "other major version" literal (`"14"`-shaped constant) —
  minor staleness risk at schema v16; noted, not a spec violation.
- Missing `--event`: the predecessor defaults to `session-start`; the port
  rejects an absent event. Deliberate — silently running the banner on a
  malformed invocation is the bug class the spec's "reject unknown events"
  requirement exists to prevent; noted as a documented divergence.
- Ambient ledger append runs in-process (`LedgerCmd.runAppend`) so the
  predecessor's two `ledger.sh` subprocess trace lines have no counterpart
  — the append's own trace sites exist. Informational.
- Round-3 residuals (unreachable): a `FILE_PATH` ending exactly at
  `…/specs/` matches the predecessor's trailing-`*` glob where
  `step0Target` returns `None`; `orderedSpecs` computes after the spec-dir
  exemption rather than before — no observable difference.

## Reviewer misreadings (recorded for the record)

- `check_expected_files` / `UnexpectedArtifact` do not exist in the
  predecessor or the port — the reviewer's FAIL-2 line cites were off, though
  the `advancePhase` relaxation underneath was real.
- The `human-grant-lock` (4) and `oracle-ordering-lock` (7) bats failures are
  pre-existing suite staleness, verified by running `gate.sh.predecessor.bak`
  directly: an empty `--tool` hits the `""` read-only case and exits 0 on the
  predecessor too. Both diff arms run the ported gate, so gate-level
  divergences were never visible to `probatioOracleDiff` — the Scala oracle
  suite is the real gate-parity evidence.
