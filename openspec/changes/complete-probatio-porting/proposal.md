# Proposal: Complete the Probatio Porting (Stages 2 & 3)

## Why

The `port-scanner-to-probatio` change (archived 2026-08-25) ported the
**decision logic** of the verified-scala3 scanner from bash/jq/python3 to
pure Scala 3 in `workflow/core/` (`probatio-core`): the 15-clause
`Validator`, `ChainState.compute`, `BannerEngine`, `DriftScan`,
`PredecessorCheck`, `GrantWaiver`, the 16-subcommand `Subcommand` enum,
the multicall `ProbatioMain` dispatcher, the sbt plugin, and the native
packaging model. All 9 specs of that change are COMPLETE and
human-validated.

What that change deliberately left unfinished — and what this change
completes — is the **wiring** and the **cutover**:

- **Stage 2 (IN PROGRESS per the archived change's §18):** the 16
  subcommand entrypoints in
  `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala`
  are stubs returning `Outcome.Ran(0)`. The decision logic lives in
  `probatio-core` (tested and Ring-6-verified), but the CLI layer that
  reads files → calls core → emits stdout JSON is not populated. The
  active runtime tools are still the bash scripts under
  `openspec/schemas/verified-scala3/scanner/` and `hooks/gate.sh`.
- **Stage 3 (NOT STARTED):** the hook shims (`hooks/gate.sh` and the
  scanner scripts invoked via `*_OVERRIDE` seams) have not been replaced
  by 3-line shims that `exec` the `probatio` native binary. Skill
  documents still reference the predecessor script paths.

The port is a strangler migration governed by the `migration-protocol`
spec (R-M1–R-M5): the bats oracle (17 files) is the acceptance suite and
passes unmodified at every step; the `*_OVERRIDE` env seams are the
swap-points; conformance between the Scala validators and the `.jq`
contracts is a property test; hook shims swap in dependency order;
exactly one implementation is active per tool at any commit; skill
documents update atomically with each swap. This change executes the
remaining two stages of that protocol.

## What Changes

### Affected Capabilities

- `specs/cli-wiring/spec.md` — **NEW**: wire the 16 stub subcommand
  entrypoints to read inputs (args, files, git), delegate to
  `probatio-core` decision logic, and emit the exact stdout/exit-code
  contract the bash originals produce. One requirement per subcommand
  group, verified against the unchanged bats oracle at each seam.
- `specs/hook-cutover/spec.md` — **NEW**: replace the bash hook shims
  with 3-line `exec` shims pointing at the `probatio` binary, in the
  dependency order mandated by `migration-protocol` R-M3 (ledger +
  chain-state first; checkpoint, registry-check, reconcile next;
  spec-lint + danger-scan next; metals next; gate last). Each swap is
  atomic with the corresponding skill-document update (R-M5).
- `specs/migration-protocol/spec.md` — **MODIFIED (delta)**: restate
  R-M1–R-M5 as the active acceptance protocol for THIS change's cutover
  (the archived change defined the protocol; this change executes it).
  Adds the per-tool swap sequence as a binding order and the
  oracle-green-at-every-step gate as a per-swap checkpoint.

### Out of Scope

- **No new decision logic.** `probatio-core` is complete and
  Ring-6-verified. This change wires and swaps; it does not alter the
  validators, chain-state computation, banner engine, or gate decision
  functions. Any behavior delta discovered during wiring is a bug in
  core or a behavior delta in the bash original — both are escalated,
  not silently reconciled (R-M1: oracle modification is rejected as a
  behavior delta).
- **No new subcommands.** The 16-subcommand surface is frozen by the
  `non-goals-guard` spec (R-X1). This change populates existing stubs.
- **No native-image build pipeline changes.** The `native-packaging`
  spec is complete; the binary is built via `sbt probatio-cli/nativeImage`.
  Release artifacts (GitHub Releases, checksums, SBOM) are out of scope —
  this change uses a locally-built binary for the cutover.
- **No `.jq` contract deletion.** Per R-M2, the executable contracts are
  retained as conformance fixtures for one full release cycle after
  conformance is green. This change keeps them.
- **No sbt-plugin release.** `sbt-probatio` is complete; publishing it
  to Maven Central is a separate change.

## Approach

**Stage 2 — CLI wiring (one subcommand group at a time, earliest-dependency-first):**

Each subcommand entrypoint is wired in isolation, then verified against
the bats oracle by setting the corresponding `*_OVERRIDE` env var to
invoke the `probatio` binary instead of the bash script. The wiring
order follows the `migration-protocol` swap order (R-M3):

1. `ledger` (append/read/validate) — reads the JSONL file, validates
   each row via `Validator.validateFull`, appends via os-lib, emits the
   bash-compatible stdout. Highest coverage, purest logic.
2. `chain-state` — reads the ledger + spec files, calls
   `ChainState.compute`, emits the JSON report matching
   `chain-state-report-contract.jq`.
3. `checkpoint` — reads the ledger, writes the presentation marker under
   `.git/verified-scala3-gate/`, emits the report.
4. `registry-check` + `reconcile` — concept registry edge checks and
   obligation reconciliation.
5. `spec-lint` — runs F1–F10 via `LintReport` core, emits the CONTEXT
   block via `BannerEngine`.
6. `danger-scan` — git diff + pattern scan, emits findings.
7. `impact-scan` + `removal-audit` + `scan` + `concept-scanner` + `graph`
   — the code-intelligence subcommands (Metals-backed where applicable).
8. `metals` (start/stop/call) — LSP framing via `MetalsClient`.
9. `install-skills` + `install-hooks` — filesystem installers.
10. `gate` — the final and highest-blast-radius subcommand: assembles
    `GatePayload` via `BannerEngine` + `DriftScan` + `PredecessorCheck` +
    `GrantWaiver`, emits the hook banner, implements the 5-event tier
    blocking logic. Wired last per R-M3.

Each wiring step is a per-spec cycle: typed contract (the entrypoint
signature already exists — the contract is the bash-equivalent
stdout/exit-code behavior), test oracle (the bats suite at the
`*_OVERRIDE` seam), implementation (populate the stub), GREEN run
(oracle green at the seam), R8 adversarial review, checkpoint.

**Stage 3 — Hook cutover (after every subcommand behind a shim is oracle-green):**

For each tool group in dependency order, replace the bash script with a
3-line shim:

```bash
#!/usr/bin/env bash
exec "/path/to/probatio" <subcommand> "$@"
```

The swap is atomic with the skill-document update (R-M5): in the same
commit, every skill doc that referenced `scanner/<tool>.sh` is updated
to reference `probatio <subcommand>`. The `SkillDocLintCheck` test
enforces no broken or forward references (R-M5 scenarios 2 & 3).

The `exactly-one-implementation` invariant (R-M4) is asserted at each
swap: the install step resolves each shim's target and verifies exactly
one implementation is reachable — zero is a broken install, two is an
ambiguous install.

## Correctness Risk Level

**Risk**: high — the gate subcommand runs on every turn (per-turn hook
latency budget R-N1: 200ms cold, 50ms warm) and its blocking logic
(predecessor check, grant waiver) directly controls whether the agent
may proceed. A wiring bug in the gate that silently allows blocked
edits, or silently blocks allowed edits, is the exact "corrupt ledger
reads as clean" defect class the whole schema exists to avert. The
ledger and chain-state subcommands write the evidence record and
compute the correctness verdict — a wiring bug there corrupts the
evidence chain. Mitigation: the bats oracle is the independent witness
at every step; no subcommand is swapped before its oracle is green; the
gate is wired and swapped last.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags (`probatioScalacOptions`:
  `-Werror`, exhaustiveness escalation), refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover (no
  `isInstanceOf`/`asInstanceOf`/`Any`/mutable vars), dangerous-pattern
  scan via `danger-scan`
- [x] Ring 2: Architecture — `probatioDependencyLint` (R-ARCH1: no
  adk4s/cats/cats-effect/fs2 in `workflow/*`; no
  `org.sinemenda.probatio` cross-dep in sbt-probatio)
- [x] Ring 3: Property-based tests — MANDATORY. The bats oracle (17
  files) is the acceptance suite at each `*_OVERRIDE` seam
  (`SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`,
  `DANGER_SCAN_OVERRIDE`, `GATE_command`/`GATE_OVERRIDE`). Hedgehog
  properties for the Scala-side wiring (arg parsing → core call →
  stdout shape). This change does NOT introduce concurrent behavior —
  the gate is single-threaded per turn; the metals subcommand uses
  blocking LSP I/O, not async streaming.
- [x] Ring 4: Wire/persistence compatibility — the ledger JSONL format,
  the chain-state report JSON, and the gate hook-json payload are
  byte-compatible with the bash originals. Verified by the `.jq`
  conformance contracts (`ledger-record-contract.jq`,
  `chain-state-report-contract.jq`, `gate-hookjson-contract.jq`) as
  property tests (R-M2). Old fixtures under `tests/fixtures/` round-trip
  through the ported subcommands unchanged.
- [x] Ring 5: Mutation testing — Stryker4s on the wired entrypoint files
  in `workflow/cli/`. Threshold 90% (pure adapter logic: arg parse →
  core call → stdout render; high branch coverage achievable).
- [x] Ring 6: Formal verification — the decision logic is already
  Ring-6-verified in `probatio-core` and `probatio-verified`. The wiring
  layer is I/O adaptation (os-lib file reads, ujson parsing, stdout
  printing) — not a pure kernel. The verified-mirror pattern applies
  only if a wiring subcommand contains a decision/fold; the gate's
  blocking decision delegates to the already-verified
  `PredecessorCheck`/`GrantWaiver`. No new kernel is introduced; the
  bridge property test is the oracle-green check at the seam.
- [ ] Ring 7: Model checking — N/A. No distributed/event-driven
  invariants in the wiring layer.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY for every
  code-changing spec. Fresh-context reviewer checks each wired
  subcommand for silent fallback mappings, `case _` defaults that hide
  spec violations, partial functions that crash instead of returning
  the spec-mandated `Outcome.Undetermined`, and stdout-shape divergences
  from the bash original that the bats oracle does not exercise.
- [ ] Ring 9: Telemetry — N/A. No telemetry stack in `workflow/*`.

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| `specs/cli-wiring/spec.md` | Full | Each of the 16 subcommand entrypoints changes from a stub (`Outcome.Ran(0)`) to a real implementation that reads files, calls core, and emits stdout. The typed contract is the per-subcommand behavior contract: input shape → core call → output shape + exit code, matching the bash original. New I/O adapter types may be introduced (e.g. a `CliContext` carrying resolved paths, a `StdoutRenderer` for each subcommand's output format). |
| `specs/hook-cutover/spec.md` | Full | Each bash script is replaced by a 3-line `exec` shim. The typed contract is the shim content (shebang + exec + trailing newline, byte-identical to `ShimGenerator.generateShim` output) and the atomic skill-doc update. New types: `ShimSwap` (tool, shimContent, skillDocsUpdated), `SwapOrder` (the binding dependency order). |
| `specs/migration-protocol/spec.md` (delta) | Minimal | Restates R-M1–R-M5 as the active protocol; no new types — reuses `SeamConfiguration`, `MigrationState`, `ShimTarget`, `ShimResolution`, `SkillDocLintResult` from the archived change's test-only inventory. The delta adds the binding swap sequence and the per-swap oracle-green checkpoint. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` | The three-way exit protocol every entrypoint returns |
| `Subcommand` | enum (16 cases) | `org.sinemenda.probatio.cli` | The dispatch target — already wired in `ProbatioMain` |
| `ProbatioMain` | object (dispatch) | `org.sinemenda.probatio.cli` | The multicall entry point — unchanged |
| `MulticallDispatch` | object (resolve) | `org.sinemenda.probatio.cli` | argv(0)/argv(1) resolution — unchanged |
| `HelpRegistry` / `HelpOutput` | object / case class | `org.sinemenda.probatio.cli` | `--help` rendering — unchanged |
| `ExitCode` | enum (Clean, Finding, Undetermined) | `org.sinemenda.probatio.cli` | `Outcome` → exit code mapping — unchanged |
| `Validator` | object (validateFull) | `org.sinemenda.probatio.core` | 15-clause ledger validation — called by `LedgerCmd` |
| `Ledger` | object (read/append/validate) | `org.sinemenda.probatio.core` | Append-only ledger operations — called by `LedgerCmd` |
| `ChainState` | object (compute) | `org.sinemenda.probatio.core` | Bound/resolved/discharged verdict — called by `ChainStateCmd` |
| `ChainStateReport` | case class | `org.sinemenda.probatio.core` | The report JSON shape — serialized by `ChainStateCmd` |
| `LintReport` / `CheckId` | case class / enum (F1–F10) | `org.sinemenda.probatio.core` | Spec-lint result — called by `SpecLintCmd` |
| `BannerEngine` / `BannerInputs` / `BannerOutput` | object / case classes | `org.sinemenda.probatio.core` | Gate banner rendering — called by `GateCmd` |
| `DriftScan` / `DriftScanResult` | object / case class | `org.sinemenda.probatio.core` | Skill-stamp drift — called by `GateCmd` |
| `GatePayload` / `HookSpecificOutput` | case classes | `org.sinemenda.probatio.core` | Gate hook-json payload — emitted by `GateCmd` |
| `PredecessorCheck` / `GrantWaiver` | objects (pure functions) | `org.sinemenda.probatio.core` | Gate blocking logic — called by `GateCmd` tool-call event |
| `GateDecision` / `BlockReason` / `SpecPhase` / `GateEvent` / `PresentationMarker` | sealed traits / enums | `org.sinemenda.probatio.core` | Gate decision algebra — called by `GateCmd` |
| `MetalsClient` / `LspMessage` / `MetalsError` / `MetalsSession` | object / case classes / sealed trait | `org.sinemenda.probatio.core` | LSP framing — called by `MetalsCmd` |
| `SchemaPolicy` | object | `org.sinemenda.probatio.core` | Env-var/cache-dir migration — called by `GateCmd` |
| `ShimGenerator` | object (generateShim) | `org.sinemenda.probatio.plugin` | 3-line shim content — reused by the cutover |
| `ConformanceModel` / `RecordModel` | object / case class | `org.sinemenda.probatio.verified` | Ring 6 conformance model — reused for the conformance property test |
| `SeamConfiguration` / `MigrationState` / `ShimTarget` / `ShimResolution` / `SkillDocLintResult` / `ToolId` | case classes / enum (test-only) | `org.sinemenda.probatio.migration` | Migration protocol types — reused by the cutover spec's tests |
| `ChecksumVerifier` / `ChecksumResult` | object / enum | `org.sinemenda.probatio.packaging` | Binary checksum verification — used by the install step |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `CliContext` | final case class | Carries resolved paths (repo root, change dir, ledger file, git-dir) and env-var overrides read once at entrypoint start; passed to core calls. Avoids re-reading env per subcommand. |
| `StdoutRenderer[A]` | typeclass (given instances per subcommand) | Renders a core result type (`ChainStateReport`, `LintReport`, `GatePayload`, `BannerOutput`) to the exact stdout string the bash original emits. One instance per subcommand output format. |
| `SubcommandWiring` | object | The I/O adapter layer: reads files via os-lib, parses args via mainargs, calls core, renders via `StdoutRenderer`, maps to `Outcome[Int]`. Pure where possible (arg parse + render); side-effecting only at the os-lib boundary. |
| `ShimSwap` | final case class (tool: ToolId, shimContent: String, skillDocsUpdated: List[String]) | A single atomic cutover unit: the 3-line shim content plus the list of skill docs updated in the same commit. |
| `SwapOrder` | enum (LedgerAndChainState, CheckpointAndRegistryAndReconcile, SpecLintAndDangerScan, Metals, Gate) | The binding dependency order from R-M3. Each variant carries its predecessor-implementation list (the tools that must be oracle-green before this group swaps). |
| `OracleGreenGate` | object (check: SwapOrder × SeamConfiguration → Either[String, SwapOrder]) | The per-swap checkpoint: verifies the bats oracle is green at the seam for every tool in the swap group before allowing the swap. Returns the swap order or a block reason naming the failing tool. |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| A wired subcommand emits stdout that differs from the bash original on a path the bats oracle does not exercise | R8 adversarial review compares stdout byte-for-byte against the bash original on a fixture corpus; the `.jq` conformance contracts (R-M2) are property tests over the corpus, not spot checks |
| The gate subcommand's blocking logic is wired incorrectly (silently allows blocked edits or blocks allowed edits) | The gate is wired and swapped LAST (R-M3); `PredecessorCheck` and `GrantWaiver` are already Ring-6-verified in core; the `hook-tiers.bats`, `human-grant-lock.bats`, and `oracle-ordering-lock.bats` suites exercise the blocking logic at the `GATE_command` seam |
| A native binary build regression breaks the per-turn latency budget (R-N1) | The V2 spike measured 42ms cold / 5ms warm (4.7x/10x under budget); the wired binary adds only I/O calls (os-lib, ujson) already present in the spike; latency is re-measured after the gate wiring and before the gate swap |
| A skill-document update is missed in a swap commit, leaving a broken reference | The `SkillDocLintCheck` test (R-M5) runs in CI and blocks any commit with a broken or forward reference; the swap is atomic by construction (one commit per swap group) |
| The `exactly-one-implementation` invariant (R-M4) is violated during a partial migration | The install step resolves each shim's target and asserts exactly one; zero and two are both install failures |
| A behavior delta is discovered during wiring that tempts an oracle modification | R-M1 forbids oracle modification; a behavior delta is escalated to the human, not silently reconciled. The feature freeze (R-X1) rejects any new lint check, verdict alteration, or workflow feature |
