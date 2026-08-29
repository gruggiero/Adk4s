# Design: Complete Probatio Porting

## Package Structure

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|-------------|
| Domain (pure) | `org.sinemenda.probatio.core` | stdlib, os-lib, ujson (upickle) | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck | R-ARCH1: enforced by `dependencyLint` task at compile time |
| CLI (I/O adapter) | `org.sinemenda.probatio.cli` | Domain, mainargs, os-lib, ujson | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck | R-ARCH1: enforced by `dependencyLint` task |
| Plugin (sbt adapter) | `org.sinemenda.probatio.plugin` | sbt APIs (Scala 2.12) | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck, `probatio-core` (R-S1) | R-ARCH1 + R-S1: enforced by `dependencyLint` |
| Verified (Ring 6 mirror) | `org.sinemenda.probatio.verified` | stdlib, Stainless library | everything project-local (leaf) | Leaf module, not aggregated |
| Migration (test-only) | `org.sinemenda.probatio.migration` | Domain, ujson, Hedgehog | cats, cats-effect, fs2, adk4s | Test-scope only; not shipped |

### New Packages

No new packages are introduced. This change populates existing stubs in `org.sinemenda.probatio.cli` and adds 3 new types to the existing CLI package. The migration test-only types already exist in `org.sinemenda.probatio.migration`.

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.sinemenda.probatio.cli` (existing) | CLI | `CliContext`, `StdoutRenderer[A]`, `SubcommandWiring` added here — same package as `SubcommandEntrypoints.scala` |

## Effect Boundaries

### Pure Code (Ring 6 candidates)

The decision logic is already Ring-6-verified in `probatio-core` and `probatio-verified`. This change adds NO new pure algorithms — it wires existing pure functions to I/O boundaries. The wiring layer itself is I/O adaptation, not a kernel.

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `Validator.validateFull` (existing) | 15-clause ledger record validation | Yes — already verified in `probatio-verified/ConformanceModel` |
| `ChainState.compute` (existing) | Bound/resolved/discharged verdict | Yes — already verified in `probatio-verified/ConformanceModel` |
| `PredecessorCheck.apply` (existing) | Predecessor verification gate | Yes — already verified in `probatio-verified` |
| `GrantWaiver.apply` (existing) | Grant waiver gate | Yes — already verified in `probatio-verified` |
| `BannerEngine.assembleBanner` (existing) | Invariant + context banner assembly | Yes — already verified in `probatio-verified` |
| `ShimGenerator.generateShim` (existing) | 3-line shim content generation | No — trivially correct by inspection (string interpolation of shebang + exec + newline); idempotency enforced by Ring 3 property test |
| `SubcommandWiring` (new) | I/O adapter: arg parse → core call → render → exit code | No — effectful (os-lib file reads, stdout writes); the pure decisions are delegated to core |
| `CliContext` (new) | Resolved paths + env vars | No — data carrier, no algorithm |
| `StdoutRenderer[A]` (new) | Render core result to stdout string | No — string formatting, no decision logic; byte-compatibility enforced by Ring 3 property test (gate-banner-byte-compatibility) and the bats oracle |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `SubcommandEntrypoints.*Cmd.run` | Direct I/O (os-lib read/write, stdout/stderr print) | Read files, call core, render output, return `Outcome[Int]` |
| `MetalsCmd.run` | Blocking LSP I/O via `MetalsClient` | Start/stop Metals LSP server, send/receive JSON-RPC |
| `InstallSkillsCmd.run` / `InstallHooksCmd.run` | Filesystem I/O (os-lib copy) | Copy skill/hook files to agent directories |
| `GateCmd.run` | Subprocess delegation + stdout | Dispatch on event, call core, emit banner, return exit code |

**No cats-effect `IO` is used in the CLI layer.** The CLI is a synchronous command-line tool — each subcommand reads its inputs, calls core, prints output, and exits. The effect boundary is the process boundary: `Outcome[Int]` maps to exit 0/1/2. This is a deliberate design choice: the CLI has no concurrency, no streaming, no resource management beyond process exit. Using `IO` would add a dependency forbidden by R-ARCH1 and complicate the native-image build.

## Type Strategy — Invalid-State Prevention

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| No `--force` / `--skip-validation` flag on `ledger append` | Best | The `append` method signature has no `force` parameter — the type does not express the bypass. Compile-negative test proves it. | The bypass is not denylisted — it is unconstructible. The method takes a record JSON string and a ledger path; there is no parameter through which validation could be skipped. |
| No `update` / `delete` action on `ledger` | Best | `LedgerCmd.Action` is an enum with exactly 3 cases: `Append`, `Read`, `Validate`. No `Update` or `Delete` case exists. Compile-negative test proves it. | The mutation action is unconstructible — the enum does not express it. |
| No `case _` catch-all in gate event dispatch | Best | `GateEvent` is a sealed enum with 5 cases. The `-Wconf:name=PatternMatchExhaustivity:e` scalac option makes inexhaustive matches a compile error. Compile-negative test proves it. | The catch-all is unconstructible — the compiler rejects it at Ring 0. |
| Three-way exit protocol (0/1/2) never collapses undetermined into clean | Best | `ExitCode` is an enum with exactly 3 cases: `Clean`, `Finding`, `Undetermined`. `ExitCode.from(outcome)` is a total function mapping `Outcome.Ran` → `Clean`, `Outcome.Finding` → `Finding`, `Outcome.Undetermined` → `Undetermined`. No `case _` default. | The collapse is unconstructible — the mapping is total and exhaustive. |
| Exactly one implementation per seam | Good | `MigrationState` is constructed from `SeamConfiguration` which is a `Set[ToolId]` — a tool is either in the set (ported) or not (predecessor). There is no "both" field. Compile-negative test proves the `both` field does not exist. | The dual-implementation state is unconstructible at the type level. A runtime check in `OracleGreenCheck` verifies the configuration before each swap. |
| Swap order has Gate last | Best | `ToolId.swapOrder` is a `List[ToolId]` with Gate as the last element. `SwapOrder` enum has no `GateFirst` case. Compile-negative test proves it. | The wrong order is unconstructible — the list is a val, not a var, and the enum has no variant for it. |
| Shim content is exactly 3 lines (shebang + exec + newline) | Best | `ShimGenerator.generateShim` takes only a `String` (the binary path) and returns a fixed template. There is no parameter for "extra logic". Compile-negative test proves the function signature has no logic parameter. | The extra-logic shim is unconstructible — the function signature does not express it. |

## Refined Type Strategy

This change introduces no new refined types. The CLI layer uses plain `String` for file paths, `Array[String]` for argv, and `Option[String]` for optional flags. The domain types in `probatio-core` (e.g. `LedgerRecord`, `ChainStateReport`) already have their own type-level constraints — the CLI layer does not re-constrain them.

### New Refined Types

None. The CLI layer is an I/O adapter — it passes values through, it does not own them.

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `CliContext` fields (repoRoot, changeDir, ledgerFile, gitDir) | File paths read from env/args — validated by existence check at I/O boundary, not by a constrained opaque type. A path that doesn't exist produces `Outcome.Undetermined`, not a type error. |
| `StdoutRenderer[A]` | A typeclass with a `render(value: A): String` method — no constrained value. |
| `SubcommandWiring` | An object with I/O methods — no constrained value. |

## IDL Model Layout

N/A. This change does not involve API operations, Smithy schemas, or protobuf. The CLI communicates via argv + stdout + exit codes — the "IDL" is the `Subcommand` enum (16 cases) and the `--help` output format.

## Error Strategy

### Error Modeling

The CLI layer uses the existing `Outcome[+A]` enum from `probatio-core`:

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `Outcome[+A]` | `Ran(value: A)`, `Finding(message: String)`, `Undetermined(message: String)` | All 16 subcommand entrypoints |
| `CliError` (existing) | `UnknownSubcommand(token: String)`, `MissingFlag(flag: String)`, `InvalidValue(flag: String, value: String)` | `MulticallDispatch.resolve`, arg parsing |

No new error types are introduced. The three-way exit protocol maps `Outcome` to exit codes via `ExitCode.from` (existing, total, exhaustive).

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Core → CLI | `Either[ContractViolation, Unit]` from `Validator.validateFull` → mapped to `Outcome.Finding` or `Outcome.Ran(0)` | `Validator.validateFull(json) match { case Right(_) => Outcome.Ran(0); case Left(v) => Outcome.Finding(s"clause ${v.clauseIndex}") }` |
| CLI → Process | `Outcome[Int]` → `ExitCode.from` → `System.exit` | `ExitCode.toInt(ExitCode.from(outcome))` |
| Arg parse → CLI | `Either[CliError, Subcommand]` from `MulticallDispatch.resolve` → stderr + exit 1 | `Left(err) => System.err.println(CliErrorRender.render(err)); 1` |
| File I/O → CLI | `os.read` failure → `Outcome.Undetermined` | `Outcome.Undetermined(s"could not read $path")` |

**No swallowed errors.** Every `Left` from a core function becomes a `Finding` or `Undetermined`. Every I/O failure becomes an `Undetermined`. There is no `case _ => Outcome.Ran(0)` default — the exhaustiveness escalation makes this a compile error.

## Compatibility Story (Ring 4)

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| Ledger records | JSON Lines (one JSON object per line) | `ledger-record-contract.jq` executable contract + Hedgehog bidirectional conformance property | `evidence-ledger.bats` at `LEDGER_OVERRIDE` seam + `LedgerCmdConformanceSpec` (to be written) |
| Chain-state reports | JSON (single object) | `chain-state-report-contract.jq` executable contract + Hedgehog conformance property | `chain-state.bats` at `CHAIN_STATE_OVERRIDE` seam + `ChainStateCmdConformanceSpec` (to be written) |
| Gate hook payloads | JSON (hook-json format) | `gate-hookjson-contract.jq` executable contract + bats oracle | `gate-payload.bats` at `GATE_command` seam |
| Gate banner (text format) | Plain text (invariant + context block) | Byte-compatibility property test (predecessor vs ported) | `hook-tiers.bats` + `GateBannerCompatSpec` (to be written) |
| Old fixtures | JSON Lines | Round-trip: old fixture bytes → ported `ledger read` → same records | `tests/fixtures/evidence-ledger-v1.jsonl` |

**Fixture obligation**: `old fixture bytes → ported subcommand → same output as predecessor`. The bats oracle exercises this by running the same test against both the predecessor script and the ported binary via the `*_OVERRIDE` seam.

## Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `Validator.validateFull` (existing) | 15-clause ledger record validation | Yes — already verified in `probatio-verified/ConformanceModel`. Bridge test: `ConformanceModelBridgeSpec` in `probatio-core/test`. |
| `ChainState.compute` (existing) | Bound/resolved/discharged verdict | Yes — already verified in `probatio-verified/ConformanceModel`. |
| `PredecessorCheck.apply` (existing) | Predecessor verification gate | Yes — already verified in `probatio-verified`. |
| `GrantWaiver.apply` (existing) | Grant waiver gate | Yes — already verified in `probatio-verified`. |
| `BannerEngine.assembleBanner` (existing) | Banner assembly | Yes — already verified in `probatio-verified`. |
| `SubcommandWiring` (new) | I/O adapter | No — effectful (os-lib file reads, stdout writes). The pure decisions are delegated to core. Ring 3 property tests + bats oracle enforce byte-compatibility. |
| `StdoutRenderer[A]` (new) | Render to stdout string | No — string formatting, no decision logic. Byte-compatibility enforced by Ring 3 property test and bats oracle. |
| `CliContext` (new) | Data carrier | No — no algorithm. |
| `ShimGenerator.generateShim` (existing) | 3-line shim generation | No — trivially correct by inspection (string interpolation). Idempotency enforced by Ring 3 property test. |
| `OracleGreenCheck.runOracle` (existing, test-only) | Run bats oracle under seam config | No — subprocess invocation, not a pure algorithm. |

No new Ring 6 mirrors are needed. The decision logic was verified by the archived `port-scanner-to-probatio` change. This change's bridge property test is the oracle-green check at the `*_OVERRIDE` seam — the bats suite proves the wired subcommand produces the same outcomes as the bash original.

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `SubcommandEntrypoints.scala` (wiring) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `CliContext` (new) | ✅ | ✅ | ✅ | — | — | — | — | — | ✅ | — |
| `StdoutRenderer[A]` (new) | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |
| `SubcommandWiring` (new) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `ShimGenerator` (existing, cutover) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `SeamTypes` / `OracleGreenCheck` (existing, cutover) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| Skill docs (cutover) | — | — | — | — | — | — | — | — | ✅ | — |

**R0** (compile): `probatioScalacOptions` with `-Werror` — exhaustiveness escalation catches missing event/variant handling.
**R1** (lint): Scalafix DisableSyntax + WartRemover — no `isInstanceOf`/`asInstanceOf`/`Any`/mutable vars.
**R2** (architecture): `dependencyLint` task — R-ARCH1: no cats/cats-effect/fs2/llm4s/workflows4s/adk4s in `workflow/*`.
**R3** (property tests): Hedgehog conformance properties + bats oracle at `*_OVERRIDE` seams.
**R4** (compatibility): `.jq` contracts + old fixture round-trip + byte-compatibility property.
**R5** (mutation): `stryker4s.conf` already targets `SubcommandEntrypoints.scala`; retarget to add new wiring helper files.
**R6** (formal): no new mirrors — decision logic already verified in `probatio-verified`.
**R7** (model checking): N/A — no TLA+/Apalache.
**R8** (adversarial review): mandatory for every code-changing spec — fresh-context reviewer checks for silent fallbacks, `case _` defaults, partial functions, stdout-shape divergences.
**R9** (telemetry): N/A — no otel4s/Daut in `workflow/*`.

## Technical Decisions

### Decision: Synchronous CLI — no cats-effect IO

**Context**: The CLI layer needs to read files, call core logic, print output, and exit. The project uses cats-effect 3 for the adk4s modules, but the probatio modules are R-ARCH1-isolated (no cats/cats-effect/fs2).

**Options considered**:
1. Use `IO` from cats-effect — rejected: R-ARCH1 forbids cats-effect in `workflow/*`; would complicate native-image build.
2. Use a custom effect type — rejected: adds complexity for no benefit; the CLI has no concurrency, streaming, or resource management.
3. Direct I/O (synchronous, os-lib + stdout) — chosen.

**Decision**: The CLI uses direct synchronous I/O. Each subcommand reads its inputs via os-lib, calls core, prints output via `System.out`/`System.err`, and returns `Outcome[Int]`. The `ProbatioMain.dispatch` method maps the outcome to an exit code and calls `System.exit`. No `IO`, no `Future`, no async.

**Consequences**: The CLI is simpler, has fewer dependencies, and builds cleanly with native-image. The tradeoff is no composability — but a CLI tool does not need to compose effects. The effect boundary is the process boundary.

### Decision: StdoutRenderer as a typeclass, not per-subcommand methods

**Context**: Each subcommand emits a different stdout format (JSON for chain-state, text for gate banner, empty for ledger append). The rendering logic must be byte-compatible with the predecessor.

**Options considered**:
1. A `render` method on each `*Cmd` object — rejected: couples rendering to the entrypoint, making it hard to test independently.
2. A `StdoutRenderer[A]` typeclass with given instances per result type — chosen.
3. A single `render(subcommand: Subcommand, result: Any): String` — rejected: uses `Any`, violates AGENTS.md.

**Decision**: `StdoutRenderer[A]` is a typeclass with a `render(value: A): String` method. Each core result type (`ChainStateReport`, `LintReport`, `GatePayload`, `BannerOutput`) gets a given instance. The entrypoint calls `StdoutRenderer[A].render(result)` and prints the string.

**Consequences**: Rendering is testable independently of I/O — the property test calls `render` directly and compares to the predecessor's output. The typeclass is extensible — new subcommands add a given instance without modifying existing code.

### Decision: CliContext as an immutable case class, not a mutable environment

**Context**: Subcommands need resolved paths (repo root, change dir, ledger file, git-dir) and env-var overrides. These are read once at entrypoint start and do not change during execution.

**Options considered**:
1. Read env vars on each call — rejected: re-reading env per subcommand is wasteful and inconsistent if env changes mid-execution (unlikely but untestable).
2. A mutable `CliContext` with setters — rejected: violates AGENTS.md (no mutable variables).
3. An immutable `CliContext` case class constructed once at dispatch — chosen.

**Decision**: `CliContext` is an immutable case class constructed at the start of `ProbatioMain.dispatch` (or at the start of each `*Cmd.run` if the subcommand needs different paths). It carries resolved paths and env-var overrides. It is passed to core calls that need paths.

**Consequences**: The context is testable (construct a `CliContext` with test paths, pass to the entrypoint, assert the output). No mutation, no race conditions.

### Decision: Gate wired last, oracle-green gate between stages

**Context**: The gate is the only blocking hook — swapping it before its subcommand dependencies are verified would leave the gate running on unverified subcommands.

**Options considered**:
1. Wire all subcommands in parallel, then swap all shims at once — rejected: a single regression in any subcommand would block the entire cutover; no incremental verification.
2. Wire in dependency order, swap in dependency order, gate last — chosen.
3. Wire the gate first (it's the most complex) — rejected: the gate depends on spec-lint, chain-state, danger-scan, and reconcile; wiring it first means testing it against unverified subcommands.

**Decision**: The wiring order follows the spec's dependency graph: ledger + chain-state first (they have the strongest oracles), then spec-lint, danger-scan, reconcile, then the remaining 10 subcommands, then the gate last. The `OracleGreenGate` blocks the Stage 2 → Stage 3 transition until the bats oracle is green with every ported subcommand substituted. Within Stage 3, each shim swap is gated by `OracleGreenCheck` for that tool.

**Consequences**: The cutover is incremental — a regression in one subcommand blocks only that subcommand's swap, not the entire cutover. The gate is always the last swap, ensuring it never runs on unverified subcommands. The tradeoff is a longer cutover timeline, but the correctness guarantee is worth it.

### Decision: Behavioral concept files created during apply Step 12

**Context**: The specs reference two behavioral concepts ("Strangler migration protocol", "Conformance property-test contract") that don't exist as files in `openspec/concepts/`. The registry-check skips them because they're marked `(NEW — created by this spec)`.

**Options considered**:
1. Create the concept files now, during the design phase — rejected: the concept files describe behavior that doesn't exist until the implementation lands; creating them now would be speculative.
2. Create them during apply Step 12 (concept delta check) — chosen.
3. Don't create them at all — rejected: the specs commit to creating them; the `(NEW — created by this spec)` annotation is a commitment, not a suggestion.

**Decision**: The concept files `openspec/concepts/strangler-migration-protocol.md` and `openspec/concepts/conformance-property-test-contract.md` are created during apply Step 12, after the implementation lands. They describe the behavior as implemented, not as speculated.

**Consequences**: The registry-check reports 0 spec concept references during the planning phase (it skips NEW concepts). After apply Step 12, the concept files exist and registry-check verifies them. This is the schema's intended workflow — concepts are created with the implementation, not before it.
