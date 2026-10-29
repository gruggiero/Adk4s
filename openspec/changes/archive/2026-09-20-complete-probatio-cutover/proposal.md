# Proposal: Complete the probatio cutover

## Why

The `port-scanner-to-probatio` and `complete-probatio-porting` changes moved
the verified-scala3 workflow's *type layer* into Scala 3 and then swapped five
live hooks onto the ported binary. The type layer is real and well-tested. The
**behaviour** behind those hooks is not: the cutover shipped a binary whose
subcommands are entrypoint shells around core logic that, for most tools, does
not exist yet.

The result is not a partially-ported workflow. It is a workflow that reports
success while enforcing nothing — the exact defect class the invariant exists
to remove.

### Measured state (2026-08-29, this session)

Two runs of the 17-file, 282-test bats oracle, in the same repository, on the
same commit (`5cbb176`), differing only in whether the five swapped seams point
at the predecessor bash scripts or at the ported binary:

| Implementation | Pass | Fail |
|---|---|---|
| Predecessor bash (control) | 262 | 20 |
| Ported probatio | 160 | 122 |

**The port regresses 102 oracle tests.** Per file:

| Bats file | Tests | Bash fail | Probatio fail | Delta |
|---|---|---|---|---|
| `gate-payload` | 24 | 0 | 21 | **+21** |
| `ambient-capture-wiring` | 32 | 0 | 21 | **+21** |
| `hook-tiers` | 27 | 0 | 20 | **+20** |
| `chain-state` | 24 | 6 | 17 | **+11** |
| `ambient-evidence-capture` | 9 | 0 | 9 | **+9** |
| `workflow-hygiene` | 10 | 1 | 7 | **+6** |
| `discharge-fidelity` | 11 | 0 | 6 | **+6** |
| `human-grant-lock` | 10 | 4 | 7 | **+3** |
| `fact-extraction` | 10 | 0 | 3 | **+3** |
| `oracle-ordering-lock` | 13 | 7 | 9 | **+2** |
| all others (7 files) | 125 | 2 | 2 | 0 |

The seven files at delta 0 are exactly the files whose tools were **not**
swapped (`ledger.sh`, `checkpoint.sh` are still bash). Every swapped tool
regressed.

R-M1 of `migration-protocol` states the bats oracle "passes unmodified at every
step" and R-M3 makes the oracle-green check a mandatory gate between stages.
Both were violated: the Stage 3 cutover proceeded with the oracle 102 tests
redder than the stage it left.

### The four defects that make this urgent

1. **Every subcommand is unreachable through the real entry point.**
   `ProbatioMain.dispatch` reads `args(0)` as the program name and `args(1)` as
   the subcommand. The JVM (and GraalVM native-image) exclude argv[0] from
   `main`'s array, so the shim invocation `probatio gate --event session-start`
   is parsed as argv0=`gate`, argv1=`--event`, and `GateCmd` receives
   `["session-start", …]` — a bare value where a flag is expected. Observed on
   the freshly built assembly:

   | Invocation | Observed |
   |---|---|
   | `probatio gate --event session-start` | `gate: session-start` exit 1 |
   | `probatio chain-state --change-dir /tmp …` | `chain-state: /tmp` exit 1 |
   | `probatio spec-lint --change c --spec s` | `spec-lint: foo` exit 1 |
   | `probatio ledger read --file f --change c` | `ledger: unknown subcommand: '--file'` exit 1 |
   | `probatio reconcile --file f --change c` | `reconcile: /tmp/x` exit 1 |

   The unit tests pass because `CliWiringContractSpec` and `MulticallDispatchSpec`
   call `dispatch` with a hand-built array that *includes* argv0 — the tests
   encode the implementation's assumption rather than the runtime's contract.
   Since commit `5cbb176`, the gate has therefore exited 1 (a non-blocking hook
   error) on every tool call in every session: **no gate check has run at all.**

2. **The banner asserts fabricated facts as filesystem reads.**
   `GateCmd.runEvent` constructs `BannerInputs` from hardcoded constants
   (`registryPresent = false`, `skillInstallScan = Nil`, `activeChanges = Nil`)
   and then prints the banner's own closing sentence: *"The lines above were
   READ FROM DISK just now; they are facts, not recollection."* Side by side on
   an identical repository containing one concept file and a v13 skill install:

   ```
   BASE: behavioural registry  openspec/concepts/  PRESENT (1 concepts)
         !! INSTRUCTION DRIFT: skill at ~/.zcode/skills is schema v13, this schema is v14.
   PORT: behavioural registry  openspec/concepts/  ABSENT
         no skill installed across any of the searched roots
   ```

   `BannerEngine` is a correct pure function; nothing populates it. This is a
   direct violation of `probatio-core` R-C5b ("the banner is assembled from live
   reads, not remembered state") and of the APPLICABILITY corollary of the
   invariant itself.

3. **Whole tools are stubs that return `Ran(0)`.**
   `spec-lint` returns an empty clean `LintReport` regardless of input — there is
   no F1–F10 engine, no W1–W7 warnings, no Proof-Obligations parser, no CONTEXT
   block in `probatio-core` at all. `danger-scan` returns clean without scanning.
   `reconcile`, `registry-check`, `scan`, `removal-audit`, `impact-scan`,
   `concept-scanner`, `graph`, and `metals stop|call` return `Ran(0)` having done
   nothing. Because `chain-state` and `checkpoint` consume `LintReport` and pass
   `Nil` for requirements, **chain-state reports zero unresolved for every change**
   — the correctness verdict is structurally incapable of finding anything.

4. **The gate is missing an entire event and every state-dependent tier.**
   `GateCmd.Event` has five cases; `gate.sh` dispatches six — `post-bash`, the
   ambient evidence writer, has no counterpart, which is why
   `ambient-evidence-capture` fails 9/9 and `ambient-capture-wiring` 21/32.
   `tool-call` returns `Undetermined` unconditionally (no state-directory
   reader), `post-edit` returns `Ran(0)` without delegating to
   spec-lint/danger-scan, and `completion` computes chain-state over `Nil`
   requirements. `--check-installed` is not a recognised flag, so the heartbeat
   installation probe is gone.

### Why this cannot be left as "incremental porting"

The predecessor is still on disk (`*.predecessor.bak`), so the workflow is
recoverable — but it is not currently *running*. Every session since the cutover
has been operating with the gate silently disabled. A workflow whose enforcement
mechanism reports success while doing nothing is worse than no workflow: it
manufactures the false evidence that the invariant exists to forbid.

## What Changes

This change completes the port so `probatio` genuinely replaces
`verified-scala3`, and makes the substitution *provable* rather than asserted.

It is still a **port, not a redesign**: the feature freeze (`non-goals-guard`
R-X1) holds. No F-check is added, no verdict is altered, no workflow feature is
introduced. Success is defined as **the bats oracle at parity with the
predecessor control** (≤ 20 failures, the recorded pre-existing set), not as
"tests we wrote pass".

### Affected Capabilities

- `specs/cli-entrypoint-contract/spec.md` — the argv contract: `main` receives
  program arguments only; multicall dispatch resolves from a program name the
  process supplies, never from the first user argument. Includes the differential
  test that would have caught the defect (invoke the built artifact as a
  subprocess, not `dispatch` in-JVM).
- `specs/spec-lint-engine/spec.md` — the F1–F10 checks, W1–W7 warnings, the
  Proof-Obligations table parser, and the CONTEXT block, as pure functions over
  parsed spec documents in `probatio-core`, verdict-identical to the predecessor
  on every fixture.
- `specs/chain-state-attribution/spec.md` — requirement extraction, the
  bound/resolved/discharged computation over real requirements, the
  `unmapped_obligations` and `unattributable` reason codes, and the
  `openspec-graph` fact-extraction seam with its degraded-mode trace.
- `specs/gate-event-completeness/spec.md` — the sixth event (`post-bash`) and
  the ambient evidence writer; the harness-payload reader; the tool-call
  predecessor/grant tiers wired to a real state-directory reader; post-edit
  delegation; `--check-installed`; bounded one-refusal-per-turn discipline;
  session identity, fingerprint suppression and heartbeat.
- `specs/live-fact-banner/spec.md` — the filesystem reader that populates
  `BannerInputs` from real reads across all six install roots, so the banner's
  closing claim is true.
- `specs/danger-reconcile-engines/spec.md` — the danger-scan pattern engine with
  `danger-scan:allow` justification handling, and the reconcile
  witness/testimony/contradicted classifier.
- `specs/ledger-checkpoint-parity/spec.md` — the `verify` subcommand, run-mode
  `digest`/`wallTime`/`sha256` capture, `--forgive-unchanged`, `--quiet`,
  `--row`, and `checkpoint report` / `checkpoint regenerate-tasks` with per-ring
  evidence attribution and the R8 fresh-context session comparison.
- `specs/cutover-gate/spec.md` — an executable Stage-4 gate: the shims may point
  at the ported binary only while a differential oracle run shows probatio at or
  below the predecessor's failure set, with automatic revert-to-predecessor
  otherwise.
- `specs/native-gate-delivery/spec.md` — the native-image `gate` binary and the
  R-N1 latency budget, measured rather than assumed.

### Out of Scope

- **`concept-scanner`, `graph`, `impact-scan`, `removal-audit`, `scan`,
  `registry-check`, `metals call|stop`.** These seven remain on their
  predecessor implementations (scala-cli / python3 / bash). They are *not*
  hook-invoked, they are not exercised by the bats oracle, and porting them is
  gated on the `native-packaging` R-N5 scalameta spike, which has not been run.
  This change makes that non-port explicit: their probatio subcommands are
  removed from the dispatch surface rather than left as `Ran(0)` stubs, so an
  unimplemented tool is *unreachable*, not *silently green*. Their ports are a
  later change.
- Any new F-check, verdict change, or workflow feature (`non-goals-guard` R-X1).
- Any schema change beyond what v14 already declares (`non-goals-guard` R-X2).
- Retiring the predecessor `.sh` files. They remain on disk as the revert target
  until the Stage-4 gate has held green across a full change cycle.

## Approach

**Fix the entry point first, then measure, then port behaviour under the
measurement.**

1. **Establish the differential harness.** Two checkouts of the scanner tree —
   one on predecessor scripts, one on probatio shims — run against the same bats
   suite in the same repository, producing a per-file pass/fail delta. This
   already exists as a throwaway in this session; the change makes it an
   `sbt` task so that "oracle-green" becomes a computed fact rather than a
   claim. Without it, R-M3 is unenforceable, which is how the current state
   shipped.

2. **Fix the argv contract** and re-measure. This alone is expected to move a
   large fraction of the 122 failures; the point of measuring immediately after
   is to learn how large, rather than to assume.

3. **Port behaviour in oracle-failure order**, largest delta first: gate events
   (`post-bash`, tiers, banner reads) → spec-lint engine → chain-state
   attribution → danger-scan/reconcile → ledger/checkpoint parity. Each spec's
   exit criterion is its own bats file at parity with the control, verified by
   the harness from step 1.

4. **Make the cutover reversible and gated.** The shim target becomes a
   function of the differential result, and the Stage-4 gate can revert.

5. **Deliver the native binary and measure the latency budget.** The current JAR
   launcher measures 200–300 ms warm against a 150 ms p50 budget
   (`native-packaging` R-N1), which the spec calls a hard blocker.

The ordering is deliberate: (1) and (2) are prerequisites for *any* honest claim
about the rest, because until the entry point works, every oracle number
conflates "unimplemented" with "unreachable".

## Correctness Risk Level

**Risk**: **high** — this change restores an enforcement mechanism that is
currently inert. Its failure modes are silent-success: a stub that returns clean,
a banner that fabricates a read, a gate that exits non-blocking. Every one of
those has already shipped once in this port. The change also touches a
verdict-producing algorithm (spec-lint F1–F10, chain-state attribution) where a
mis-port changes what the workflow *concludes*, not merely what it prints.

## Verification Strategy

- [x] Ring 0: Compilation — `probatioScalacOptions` (`-Werror`, exhaustiveness
      escalation, discard/init checks) on `probatio-core` and `probatio-cli`
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, danger-scan on the diff
- [x] Ring 2: Architecture — `probatioDependencyLint`; R-ARCH1 (no cats /
      cats-effect / fs2 / llm4s / workflows4s / adk4s on the `workflow/*`
      classpath); `probatio-core` stays free of file I/O and `System.getenv`
- [x] Ring 3: Property-based tests — MANDATORY. munit + Hedgehog. Additionally
      the **bats differential oracle** is the acceptance suite (`migration-protocol`
      R-M1): each spec's exit criterion is its bats file at parity with the
      predecessor control. **Concurrency note**: `ledger run` and `gate post-bash`
      execute subprocesses and observe exit codes; their scenarios use recorded
      process outcomes and a deterministic clock seam, never wall-clock sleeps.
- [x] Ring 4: Wire/persistence compatibility — REQUIRED. The three `.jq`
      contracts (`ledger-record-contract.jq`, `chain-state-report-contract.jq`,
      `gate-hookjson-contract.jq`) are the wire format. Ledger rows gain
      `digest`/`wallTime`/`sha256` round-tripping; the hook-json envelope's
      `hookEventName` must emit harness names (`UserPromptSubmit`), not internal
      enum names (`PromptSubmit`). Mixed legacy/capture ledgers must read cleanly.
- [x] Ring 5: Mutation testing — Stryker4s on changed production files.
      Threshold **80%** on `probatio-cli` adapters, **90%** on `probatio-core`
      decision logic. The `cli-wiring` spec recorded 16.39% with 365 NoCoverage
      mutants in the stubs; removing the stubs and covering the engines is the
      direct remedy.
- [x] Ring 6: Formal verification — APPLIES. The chain-state fold
      (bound → resolved → discharged, with `unattributable` and
      `unmapped_obligations` as non-collapsing outcomes) is a decision at the
      centre of this change and already has a Stainless mirror leaf
      (`verified/probatio`: `ChainStateKernel`, `LedgerValidatorKernel`,
      `BannerEngineKernel`). The mirror is extended, not skipped, per
      `templates/verified-mirror.md`.
- [ ] Ring 7: Model checking — not applicable; no distributed or event-ordering
      invariant beyond the ledger's append-only discipline, which Ring 6 covers.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY, fresh context, per
      spec, before Rings 5/6. **Reviewer standing instruction for this change**:
      for every requirement, check whether the *shipped artifact* satisfies it
      when invoked as a subprocess — not whether an in-JVM unit test passes. The
      argv defect and the fabricated banner both survived a prior R8 because the
      tests and the reviewer inspected functions, not the product.
- [ ] Ring 9: Telemetry — no telemetry stack in `workflow/*`.

## Typed Contract Decision

**Per-spec classification**:

| Spec | Typed contract | Justification |
|------|----------------|---------------|
| `specs/cli-entrypoint-contract/spec.md` | full | changes the public entry-point signature and the dispatch algebra |
| `specs/spec-lint-engine/spec.md` | full | introduces the spec-document AST, `CheckOutcome`, and the F1–F10/W1–W7 verdict algebra |
| `specs/chain-state-attribution/spec.md` | full | new `ObligationRow`/`ObligationSource` types; makes the existing-but-unreachable `UnresolvedReason.Unattributable` reachable |
| `specs/gate-event-completeness/spec.md` | full | new `Event.PostBash` variant, `HarnessPayload`/`ToolOutcome` ADTs, `GateStateDir` reader algebra |
| `specs/live-fact-banner/spec.md` | full | new `RepositoryFacts` reader; changes how `BannerInputs` is constructed |
| `specs/danger-reconcile-engines/spec.md` | full | new `DangerPattern`, `DangerHit`, `Corroboration` ADTs |
| `specs/ledger-checkpoint-parity/spec.md` | full | ledger record gains optional persisted fields; new `checkpoint` subcommand algebra |
| `specs/cutover-gate/spec.md` | full | new `DifferentialResult`/`ShimTarget` decision types |
| `specs/native-gate-delivery/spec.md` | minimal | build/packaging wiring; `BinaryResolution` already exists and is unchanged |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `Outcome[+A]` | enum (`Ran`/`Finding`/`Undetermined`) | `probatio.core` | reuse as-is; the three-way protocol is unchanged |
| `LedgerRecord` | final case class (private ctor) | `probatio.core` | extend with the optional persisted fields; keep smart constructor |
| `LedgerRecordOptional` | case class | `probatio.core` | currently unattached — must be joined to the record's `ReadWriter` |
| `Validator` | object (15 clauses) | `probatio.core` | reuse as-is; already at contract parity |
| `ContractViolation` | sealed hierarchy (15) | `probatio.core` | reuse as-is |
| `Ledger` / `Ledger.LedgerData` | object / type | `probatio.core` | reuse; add staleness + forgive-unchanged filtering |
| `ChainState` / `ChainStateReport` / `ChainStateUndetermined` | object / case classes | `probatio.core` | extend `compute` with real requirements and attribution |
| `UnresolvedReason` | enum | `probatio.core` | reuse as-is — all five variants including `Unattributable` already exist; `ChainState.compute` never produces `Unattributable`, so the variant is currently **unreachable**. This change makes it reachable, it does not add it. |
| `LintReport` / `RequirementVerdict` / `Verdict` / `CheckId` / `LintWarning` | case classes + enums | `probatio.core` | reuse as the engine's *output*; the engine itself is new |
| `BannerEngine` / `BannerInputs` / `BannerOutput` | object / case classes | `probatio.core` | reuse the pure renderer; supply real inputs |
| `DriftScan` / `InstallRootScan` / `DriftWarning` | object / types | `probatio.core` | reuse; correct `installRoots` from 3 to the documented 6 |
| `GateEvent` / `GateDecision` / `BlockReason` / `SpecPhase` | enums | `probatio.core` | reuse as-is |
| `PredecessorCheck` / `GrantWaiver` / `PresentationMarker` | objects | `probatio.core` | reuse as-is; wire them to a real state reader |
| `GatePayload` | case class | `probatio.core` | reuse |
| `SchemaPolicy` | object | `probatio.core` | reuse for the v14 rename and env-var aliasing |
| `MetalsClient` | object | `probatio.core` | reuse for `metals start`; `call`/`stop` are out of scope |
| `Subcommand` / `MulticallDispatch` / `ExitCode` / `CliError` / `HelpRegistry` | enum / objects | `probatio.cli` | reuse; `Subcommand` loses the seven out-of-scope cases |
| `CliContext` / `StdoutRenderer[A]` / `SubcommandWiring` | case class / typeclass / object | `probatio.cli` | reuse and extend |
| `BinaryResolution` / `Platform` / `ReleaseManifest` / `ReleaseValidator` / `ChecksumVerifier` | objects / types | `probatio.packaging` | reuse as-is for native delivery |
| `OracleGreenGate` / `OracleGreenCheck` / `Stage` / `ToolId` / `SeamConfiguration` | test-only objects/enums | `probatio.migration` | reuse; the differential harness replaces the current predecessor-path logic |
| `SwapOrder` / `ShimSwap` / `ShimGenerator` | enum / case class / object | `probatio.migration`, `probatio.plugin` | reuse; extend with the revert direction |
| `ChainStateKernel` / `LedgerValidatorKernel` / `BannerEngineKernel` / `ConformanceModel` | Stainless objects | `probatio.verified` | reuse and extend for Ring 6 |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `ProgramArgs` | opaque type over `List[String]` | makes "these are program arguments, argv0 excluded" unrepresentable-otherwise at the entry point |
| `SpecDocument` | case class | the parsed spec.md: requirements, properties, temporals, scenarios, obligation rows, concept sections |
| `RequirementBlock` / `PropertyBlock` / `TemporalBlock` | case classes | headed blocks with source line numbers, for line-attributed findings |
| `ObligationRow` | case class | one Proof-Obligations data row: source, mechanism, artifact, enforcement tier |
| `ObligationSource` | enum (`ByTitle`/`ByOrdinal`/`Typed`/`Unresolvable`) | the Source cell's resolved form — drives F6/F8 and W4 |
| `CheckOutcome` | enum (`Pass`/`Fail(CheckId, line, message)`/`Warn(code, line, message)`) | one F/W check result; total, never silently absent |
| `LintContext` | case class | the CONTEXT block's applicability facts, as data |
| `RepositoryFacts` | case class | the live filesystem reads that populate `BannerInputs` and `LintContext` |
| `DangerPattern` | enum (8 cases) | the predecessor's eight named pattern classes |
| `DangerHit` | case class | file, line, pattern, justified-or-not |
| `Corroboration` | enum (`SelfObserved`/`Witnessed`/`Testimony`/`Contradicted`/`Exempt`) | reconcile's per-row classification |
| `HarnessPayload` | case class | the gate's stdin payload, read at most once |
| `ToolOutcome` | enum (`Exit(n)`/`Skip(reason)`) | the post-bash outcome predicate — a harness refusal is `Skip`, never a red row |
| `GateStateDir` | case class | resolved `.git/`-scoped state: heartbeat, fingerprint, grants, presentations, phase |
| `SessionId` | opaque type | losslessly filename-safe (base64url) session identity |
| `RefusalBudget` | case class | the bounded one-refusal-per-turn discipline as data |
| `RingEvidence` | case class | checkpoint's per-ring row attribution (ring, rows, verdict, session) |
| `DifferentialResult` | case class | per-file predecessor-vs-port pass/fail delta |
| `CutoverVerdict` | enum (`Proceed`/`Revert(DifferentialResult)`) | the Stage-4 gate decision |

## Risks and Mitigations

| Risk | Detection | Mitigation |
|---|---|---|
| The port re-lands with the same "green stub" failure mode | Each spec's exit criterion is its bats file at parity with the *control*, computed by the differential harness — not a self-authored test count | The harness is spec 1's deliverable, built before any behaviour is ported |
| A ported check changes a verdict (feature-freeze breach, R-X1) | `FeatureFreezeVerdict` property tests over the fixture corpus; verdict-identity is a property, not a spot check | Fixture corpus is extracted from the predecessor's own bats fixtures before porting begins |
| Unit tests again encode the implementation's assumptions rather than the runtime's | Ring 8 standing instruction: verify against the built artifact invoked as a subprocess | `cli-entrypoint-contract` mandates a subprocess-level conformance suite |
| The gate blocks the very session doing the work (observed today: a working `tool-call` tier denied every tool) | The escape hatch and the bounded-refusal discipline are part of spec 4's acceptance, and the Stage-4 gate can revert the shims | Predecessor `.sh` files stay on disk; `PROBATIO_HOOKS=1` and `VERIFIED_SCALA3_HOOKS` alias both honoured |
| Latency budget unmet, blocking release per R-N1 | Measured, not assumed: warm p50 recorded in the ledger | If native-image cannot meet 150 ms, the gate seam reverts to bash and the finding is recorded rather than the budget being quietly widened |
| Scope creep into the seven out-of-scope tools | `Subcommand` loses those cases; a stub cannot be reintroduced without a compile-negative failing | Removal is a spec requirement, not a convention |
