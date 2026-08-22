# Design: Port the verified-scala3 scanner to probatio

## Package Structure

### Layers

The probatio tooling is a **leaf-by-construction** (R-ARCH1): it depends on
NOTHING adk4s-side. The layer rules below are PROJECT-SPECIFIC, derived from
the detected stack in `openspec/capability-profile.md` and the R-X3
allowed-dependency set.

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|-------------|
| Domain (pure) | `org.sinemenda.probatio.core` | stdlib, ujson/uPickle, scalameta (concept-scanner only) | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s, mainargs, os-lib | No outbound imports beyond the allowed set; no effect types |
| CLI (effect boundary) | `org.sinemenda.probatio.cli` | Domain, mainargs, os-lib, ujson/uPickle | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s | `allowed: { from: cli, to: [core] }`; effect = blocking IO via os-lib + System.exit |
| Plugin (sbt) | `org.sinemenda.probatio.plugin` | sbt APIs, stdlib (Scala 2.12) | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s, probatio-core | `allowed: { from: plugin, to: [] }` — links NO probatio-core code; communicates via Process + exit codes |
| Mirror (Ring 6) | `org.sinemenda.probatio.verified` | stdlib, Stainless library | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s, ujson, mainargs, os-lib, scalameta | Leaf module, pinned to Scala 3.7.2; PureScala stdlib only |
| Generated code | N/A | — | — | N/A — no IDL codegen in this change |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.sinemenda.probatio.core` | Domain (pure) | ADTs (LedgerRecord, ChainStateReport, GatePayload, LintReport, Outcome), validators (ex-jq contracts), verdict logic, banner/drift engine, contract violations |
| `org.sinemenda.probatio.core.scanner` | Domain (pure) | spec-lint F1–F10 checks, CONTEXT facts, conditional-check applicability (3, 6, 17, 18) |
| `org.sinemenda.probatio.core.metals` | Domain (pure, blocking) | LSP JSON-RPC client: Content-Length framing, init handshake, lifecycle; logs to stderr |
| `org.sinemenda.probatio.core.graph` | Domain (pure) | Transitive traceability/fact extraction (ported from openspec-graph.py) |
| `org.sinemenda.probatio.cli` | CLI (effect boundary) | mainargs @main entrypoints, one per predecessor script; multicall dispatch; Outcome[Int] → exit 0/1/2 |
| `org.sinemenda.probatio.plugin` | Plugin (sbt, Scala 2.12) | AutoPlugin, 7 tasks, install resolution, gate shim generation |
| `org.sinemenda.probatio.verified` | Mirror (Ring 6) | PureScala models of chain-state verdict logic, 15-clause validator (12 required-field + 3 provenance), banner engine |

### Build wiring

```
workflow/core     probatio-core   (Scala 3.8.4, org.sinemenda.probatio:probatio-core_3)
                  depends on: verified % Test (Ring 6 bridge)
                  scalacOptions: scala3Options ++ probatioScalacOptions (R-CS1..R-CS5)
                  libraryDependencies: os-lib, uPickle, mainargs (cli only), scalameta (scanner only)
                                      + munit, hedgehog-munit (Test)
                  NO cats, NO cats-effect, NO fs2, NO adk4s-*, NO llm4s, NO workflows4s

workflow/cli      probatio-cli    (Scala 3.8.4, org.sinemenda.probatio:probatio-cli_3)
                  depends on: probatio-core
                  scalacOptions: scala3Options ++ probatioScalacOptions
                  libraryDependencies: mainargs, os-lib, uPickle
                  native-image: yes (gate mandatory, others optional-with-warning)

workflow/plugin   sbt-probatio    (Scala 2.12, org.sinemenda.probatio:sbt-probatio_2.12)
                  depends on: NOTHING probatio-side
                  scalacOptions: sbt defaults (Scala 2.12)
                  libraryDependencies: sbt APIs only

verified/probatio probatio-verified (Scala 3.7.2, Stainless, NOT aggregated)
                  depends on: NOTHING project-local
                  scalacOptions: -deprecation, -feature, -Wconf:src=.*stainless-library.*:silent
                  stainlessEnabled := false (default); ring6 alias turns it on
                  publish / skip := true
```

## Effect Boundaries

### Pure Code (Ring 6 candidates)

probatio-core is pure by construction (R-C3). IO lives in probatio-cli only.
The metals client does blocking LSP request/response (not parallel streams).

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `core.LedgerRecord.validate` | 12-clause total validator → Either[ContractViolation, LedgerRecord] (extended to 15 clauses by provenance-validation spec: `Validator.validateFull` → Either[ContractViolation, ValidatedRecord]) | **Yes** — `LedgerValidatorKernel` mirror. Decision/fold at the centre (12 Boolean clauses → one violation or valid; extended to 15 clauses for provenance). Inputs reducible to BigInt identities + Booleans. Law: totality (every input maps to exactly one outcome). |
| `core.ChainState.compute` | (SpecLintReport, Ledger, Requirements, Baseline) → Either[Undetermined, ChainStateReport] | **Yes** — `ChainStateKernel` mirror. Decision kernel: bound/resolved/discharged verdict over a finite state machine. Inputs reducible to BigInt state identities + Booleans. Law: undetermined-never-collapses (the defect class the whole schema averts). |
| `core.BannerEngine.render` | (schemaVersion, skillInstallScan, registry/inventory/profile presence, detectedTestKit, activeChanges) → banner text | **Yes** — `BannerEngineKernel` mirror. Pure rendering function; byte-identical output for identical inputs. Inputs reducible to BigInt identities + Booleans. Law: idempotence (same inputs → same output). |
| `core.DriftScan.scan` | (schemaVersion, installRoots) → drift warnings | **Yes** — covered by `BannerEngineKernel` mirror (drift scan is a sub-function of the banner assembly). Law: every install root is checked (no silent omission). |
| `core.scanner.SpecLint.run` | F1–F10 checks + CONTEXT facts → LintReport | **No** — the F1–F10 checks are mechanical greps over file contents (effectful: reads files); the *decision* (which check applies, what verdict) is pure but the input collection is not. TP1–TP5 enforced by Ring 3 properties (F1–F10 verdict stability). The verdict *attribution* (R-C4) is pure and covered by the `ChainStateKernel` mirror. |
| `core.metals.MetalsClient` | LSP JSON-RPC over stdin/stdout | **No** — effectful (blocking I/O, process management). R-C6 framing correctness enforced by Ring 3 property (partial-read framing). |
| `core.graph.Traceability` | transitive traceability/fact extraction | **No** — graph traversal over file-system facts (effectful input collection). The traversal algorithm is pure but its inputs are not reducible without modeling the entire file system. Ring 3 property test over fixture corpus. |
| `cli.*` (all entrypoints) | mainargs dispatch, os-lib file I/O, System.exit | **No** — effectful by definition (the effect boundary). Outcome[Int] → exit code mapping is trivial and covered by Ring 3 property (exit-code-mapping-is-total-and-disjoint). |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `cli.ProbatioMain` | blocking IO (os-lib, System.exit) | Multicall dispatch: argv(1) or argv(0) → subcommand |
| `cli.SpecLintCmd` | blocking IO | Reads spec files, runs core.scanner.SpecLint, prints LintReport JSON, exits 0/1/2 |
| `cli.ChainStateCmd` | blocking IO | Reads ledger + spec-lint output, runs core.ChainState.compute, prints report, exits 0/1/2 |
| `cli.GateCmd` | blocking IO | Reads hook event JSON, runs gate logic, prints GatePayload JSON, exits 0/1/2 |
| `cli.LedgerCmd` | blocking IO | append-only ledger operations (read, append, validate — no update/delete/rewrite) |
| `cli.DangerScanCmd` | blocking IO | Runs danger-scan checks, prints report, exits 0/1/2 |
| `cli.CheckpointCmd` | blocking IO | Checkpoint from ledger, prints report, exits 0/1/2 |
| `cli.ConceptScanCmd` | blocking IO | Runs scalameta-based concept scanner, prints report |
| `cli.GraphCmd` | blocking IO | Runs traceability/fact extraction, prints report |
| `core.metals.MetalsClient` | blocking IO (stdin/stdout pipes) | LSP JSON-RPC: Content-Length framing, init handshake, lifecycle |
| `plugin.ProbatioPlugin` | sbt task effects | Process execution, file writes (shims), install resolution |

**No cats-effect IO, no fs2 Streams.** probatio uses blocking I/O via os-lib
and System.exit. This is a deliberate consequence of R-X3 (cats/cats-effect
excluded) and the tool's nature (a CLI, not a service). The AGENTS.md FP
mandate applies to adk4s product code under validation, not the validating
tool — a recorded one-line deviation cited in R-X3.

## Type Strategy — Invalid-State Prevention

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| Three-way exit protocol (0/1/2) | **Best** | `Outcome[A]` sealed enum with exactly 3 cases (`Ran[A]`, `Finding`, `Undetermined`); no fourth case is expressible; exhaustive match enforced by `-Wconf:name=PatternMatchExhaustivity:e` + compile-negative (no `case _`) | The defect class the whole schema averts ("undetermined collapsed into finding") is impossible to express |
| Append-only ledger (no update/delete/rewrite) | **Best** | `Ledger` module exposes only `read`, `append`, `validate` — no `update`/`delete`/`rewrite` constructor exists; compile-negative test proves absence | The forbidden operations are unconstructible, not denylisted |
| Ring outside R0–R9 | **Best** | `Ring` sealed enum with exactly 10 cases (R0–R9, manual); a ring outside this set is unrepresentable at the type level | Invalid ring is impossible to express |
| 13th ContractViolation (pre-provenance-validation) | **Best** | `ContractViolation` sealed trait with exactly 12 cases (one per required-field clause); compile-negative test proves no 13th case. **Extended by provenance-validation spec to 15 cases** (12 required-field + 3 provenance: OptionalFieldTypeInvalid, ObserverProvenanceInvalid, SessionProvenanceInvalid); compile-negative test proves no 16th case. | Invalid violation is impossible to express |
| Subcommand outside predecessor set | **Best** | `Subcommand` sealed enum with exactly one case per predecessor script; no extra case is expressible; CLI surface snapshot test verifies 1:1 | Extra subcommand is impossible to express |
| LedgerRecord with invalid clause combination | **Good** | Smart constructor `LedgerRecord.from(...): Either[ContractViolation, LedgerRecord]` — validates all 12 clauses; raw constructor is private | Invalid record is rejected at construction, not at use |
| ChainStateReport with collapsed undetermined | **Best** | `ChainStateReport` is produced only by `ChainState.compute`, which returns `Either[Undetermined, ChainStateReport]` — the type enforces the split | Undetermined is a separate type from the report, not a field |
| LintReport with missing verdict attribution | **Good** | `LintReport` is a typed AST with per-requirement verdict slots; missing slot = missing case in the sealed structure = compile error | Missing attribution is a type error, not a runtime gap |
| GatePayload with wrong JSON shape | **Good** | `GatePayload` case class + uPickle ReadWriter; byte-compatibility enforced by Ring 4 property test against `gate-hookjson-contract.jq` | Wrong shape is rejected by the validator, not silently accepted |
| Banner with stale state | **Best** | `BannerEngine.render` is a pure function with no mutable state; same inputs → same output (idempotence law proven in mirror) | Stale state is impossible — there is no state |
| Drift root silently omitted | **Best** | `DriftScan.scan` returns a `List[DriftWarning]` — the list is total over the install roots; an omitted root is a missing list element, detectable by the Ring 3 property | Omission is a missing element, not a silent skip |
| Discarded validator result | **Best** | `-Wvalue:discard` (R-CS3) makes discarding a non-Unit value a compile error | The silent-fallback defect class is a compile error |
| Deprecated API usage | **Best** | `-Wconf:cat=deprecation:e` (R-CS2) makes deprecated API usage a compile error | Deprecated-alias paths cannot hide silent fallbacks |
| Unsafe initialization order | **Best** | `-Ysafe-init` (R-CS4) makes unsafe init order a compile error | The chain-state.sh "die_undetermined before declaration" defect class is impossible |
| Forbidden dependency on classpath | **Best** | dependency-lint rule (R-ARCH1) fails the build if cats/cats-effect/fs2/adk4s-*/llm4s/workflows4s/scalacheck appears on any workflow/* classpath | Forbidden deps are a build failure, not a runtime risk |

No invariant is at the "Risky" or "Bad" level. The port's entire value
proposition is that the defect class it averts ("a corrupt ledger reads as
clean") is made impossible to express, not merely detected after the fact.

## Refined Type Strategy

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| `Ring` | `Int` (sealed enum) | R0–R9 only | Persisted in ledger; API boundary value; invalid ring is unrepresentable |
| `SchemaVersion` | `Int` | ≥ 1 | Persisted in schema.yaml; API boundary value |
| `ExitCode` | `Int` | 0, 1, or 2 | API boundary value (the three-way exit protocol) |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `LedgerRecord` fields (path, sha, ring, clauses) | Validated by the 12-clause smart constructor; internal to the validator |
| `ChainStateReport` fields (verdict, requirements, baseline) | Produced by a pure function; internal to the verdict logic |
| `GatePayload` fields (decision, additionalContext, payload) | Byte-compatibility with the .jq contract is the constraint; a refined type would duplicate the contract |
| `LintReport` fields (requirement, verdict, warnings) | Typed AST; the structure IS the constraint |
| `BannerEngine` inputs (schemaVersion, installRoots, etc.) | Pure function inputs; no persistence boundary |
| `DriftWarning` fields (root, expected, actual) | Internal to the drift scan output |

**Iron usage**: The project already uses Iron 3.3.2 (per
capability-profile.md). The `Ring` enum is a sealed enum, not an Iron
refined type, because the constraint is a closed set (R0–R9), not a
predicate — a sealed enum is strictly stronger. `SchemaVersion` and
`ExitCode` could use Iron refined types, but sealed enums are preferred
here too (closed sets, not open predicates).

## IDL Model Layout

**N/A — this change does not involve IDL operations.** probatio is a CLI
tool, not a service. There are no Smithy/protobuf service definitions. The
"API" is the CLI surface (argv, exit codes, stdout JSON), which is
specified in `specs/cli-protocol/spec.md` and tested by the bats oracle +
Hedgehog properties.

The three `.jq` contracts (`ledger-record-contract.jq`,
`chain-state-report-contract.jq`, `gate-hookjson-contract.jq`) are the
wire-format contracts. They are not IDL — they are executable jq programs
that validate JSON shapes. They are ported to Scala validators
(`LedgerRecord.validate`, `ChainStateReport.validate`, `GatePayload.validate`)
and retained as conformance fixtures (R-M2 property test).

## Error Strategy

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `Outcome[A]` | `Ran[A](value)`, `Finding(report)`, `Undetermined(reason)` | All CLI entrypoints; maps to exit 0/1/2 |
| `ContractViolation` | 15 cases (12 required-field clauses + 3 provenance: OptionalFieldTypeInvalid, ObserverProvenanceInvalid, SessionProvenanceInvalid) | `LedgerRecord.validate` (clauses 0–11) / `Validator.validateFull` (clauses 0–14, provenance-validation spec) |
| `ParseError` | `MissingFlag(name)`, `InvalidValue(flag, value, expected)`, `UnknownSubcommand(name)` | mainargs arg parsing (R-P4) |
| `DriftWarning` | `VersionMismatch(root, expected, actual)`, `NoSkillInstalled(root)` | `DriftScan.scan` |
| `MetalsError` | `FramingError(detail)`, `HandshakeFailed(detail)`, `Timeout` | `MetalsClient` |

All error enums are sealed traits with data-carrying cases (not empty
markers). No `case _` catch-all in any match over these types (compile-
negative obligation). No swallowed errors — every error path produces an
observable outcome (exit code, stderr message, or stdout JSON).

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure | `Either[E, A]` | `LedgerRecord.from(...): Either[ContractViolation, LedgerRecord]` |
| Pure → CLI | `Outcome[A]` at the boundary; CLI maps to exit code | `SpecLint.run(...): Outcome[LintReport]` → exit 0/1/2 |
| Arg parse → CLI | `ParseError` → stderr message + exit 2 (undetermined) | `MissingFlag("--baseline")` → "probatio: missing required flag --baseline" + exit 2 |
| Metals → Pure | `Either[MetalsError, A]` | `MetalsClient.initialize(...): Either[MetalsError, MetalsSession]` |
| Plugin → Build | `sys.error(message)` (sbt task failure) | `probatioSpecLint` task: exit 1 → `sys.error("probatio spec-lint reported N finding(s): …")` |

**No default branches returning valid domain values.** The
`-Wconf:name=MatchCaseUnreachable:e` flag (already in `scala3Options`)
plus `-Werror` (R-CS1) makes a catch-all that returns a default a compile
error. The compile-negative obligation (no `case _` in matches over sealed
types) is enforced by source lint + WartRemover.

## Compatibility Story (Ring 4)

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| Ledger records | JSON (uPickle) | Old-fixture decoding + round-trip; 15-clause validator ⊨ `ledger-record-contract.jq` (12 required-field + 3 provenance; provenance clauses added by provenance-validation spec) | `LedgerCompatSpec` (Hedgehog: old fixture → decode → expected domain value; new value → encode → decode → same value) + `ProvenanceConformanceSpec` (Hedgehog: 15-clause conformance, both directions) |
| Chain-state reports | JSON (uPickle) | Round-trip + `chain-state-report-contract.jq` conformance | `ChainStateCompatSpec` (Hedgehog: round-trip + contract conformance) |
| Gate hook payloads | JSON (uPickle) | Byte-stability vs `gate-hookjson-contract.jq`; `hookSpecificOutput.additionalContext` shape unchanged | `GatePayloadCompatSpec` (Hedgehog: byte-stability + contract conformance) |
| spec-lint output | JSON (uPickle, new LintReport AST) | Per-requirement verdict attribution (R-C4); consumed by chain-state as uPickle JSON | `LintReportCompatSpec` (Hedgehog: round-trip + F1–F10 verdict stability) |
| Schema rename | schema.yaml | v14 bump: `verified-scala3` → `probatio`; `generatedBy` stamp rename; env-var + cache-dir migration with one-major deprecated aliases | `SchemaPolicySpec` (Hedgehog: deprecated alias accepted for one major, then warning, then ignored) |
| Hook shims | bash (3-line exec wrappers) | Byte-stability: `#!/usr/bin/env bash` + `exec "<path>" gate "$@"` + trailing newline; idempotent | `ShimIdempotencySpec` (Hedgehog: run twice → byte-identical output) |

**Fixture obligation**: `old fixture bytes/json → decode → expected domain
value` and `new value → encode → decode → same value`. The existing fixture
corpus under `openspec/schemas/verified-scala3/tests/fixtures/` provides
the old-fixture inputs; the bats oracle provides the wire-compatibility
suite (R-M1).

## Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `core.LedgerRecord.validate` | 12-clause total validator (extended to 15 clauses by provenance-validation spec) | **Yes** — `LedgerValidatorKernel` mirror. Decision/fold: 12 Boolean clauses → one violation or valid (extended to 15 for provenance). Inputs: BigInt clause identities + Booleans. Law: totality (every input → exactly one outcome). |
| `core.ChainState.compute` | verdict logic (bound/resolved/discharged/undetermined) | **Yes** — `ChainStateKernel` mirror. Decision kernel: finite state machine over (lintReport, ledger, requirements, baseline). Inputs: BigInt state identities + Booleans. Law: undetermined-never-collapses. |
| `core.BannerEngine.render` | banner/drift assembly | **Yes** — `BannerEngineKernel` mirror. Pure rendering: (version, installs, presence, testkit, changes) → text. Inputs: BigInt identities + Booleans. Law: idempotence (same inputs → same output). |
| `core.scanner.SpecLint.run` | F1–F10 checks | **No** — mechanical greps over file contents (effectful input collection); the *decision* is pure but inputs are not reducible without modeling the file system. F1–F10 verdict stability enforced by Ring 3 property test over fixture corpus. |
| `core.metals.MetalsClient` | LSP JSON-RPC | **No** — effectful (blocking I/O, process management). R-C6 framing enforced by Ring 3 property (partial-read framing). |
| `core.graph.Traceability` | transitive traceability | **No** — graph traversal over file-system facts. Ring 3 property over fixture corpus. |
| `cli.*` | CLI entrypoints | **No** — effect boundary. Exit-code mapping enforced by Ring 3 property. |
| `plugin.*` | sbt tasks | **No** — effectful (Process, file writes). Enforced by Ring 3 scenario tests + bats oracle. |

### Mirror placement decision

**Decision: new `verified/probatio` leaf module, NOT reuse of existing
`verified/` leaf.**

**Context**: The existing `verified/` module (build.sbt line 329) is pinned
to Scala 3.7.2 for Stainless and contains adk4s-side Ring 6 models
(`OracleKernel`, `SoupKernel`). The probatio mirrors model a different
algorithm (ledger validation, chain-state verdicts, banner rendering) and
must not import any adk4s code (R-ARCH1).

**Options considered**:
1. Reuse `verified/` — add probatio kernels alongside adk4s kernels.
2. New `verified/probatio` leaf — separate module, same Scala 3.7.2 pin,
   same Stainless setup, no adk4s dependency.

**Decision**: Option 2. R-ARCH1 requires probatio to depend on NOTHING
adk4s-side. Reusing `verified/` would put probatio models in a module that
also contains adk4s models — not a classpath violation (the models don't
import each other), but it couples the two at the build level and makes
extraction to `sinemenda/probatio` harder (the `git filter-repo` would need
to split the module). A separate leaf is cleaner and extraction-ready.

**Consequences**: One additional sbt subproject declaration. The
`ring6` alias is extended: `; set verified/probatio / stainlessEnabled := true ; verified/probatio / compile`.
The bridge tests live in `workflow/core` test sources and depend on
`verified/probatio % Test`.

### Mirror scope notes

**`LedgerValidatorKernel`** — proves: totality (every input → exactly one
outcome); clause independence (each clause's failure is distinguishable).
Extended by provenance-validation spec from 12 to 15 clauses (adds
optional-field type checks, observer provenance, session provenance).
Delegates to Ring 3: the 15-clause conformance property test
(`validator ⊨ ledger-record-contract.jq` over fixture corpus, both directions).

**`ChainStateKernel`** — proves: undetermined-never-collapses (the
defect class the whole schema averts); verdict totality (every input →
exactly one of bound/resolved/discharged/undetermined). Delegates to Ring
3: the F1–F10 verdict stability property test (before/after port equality).

**`BannerEngineKernel`** — proves: idempotence (same inputs → same output);
drift totality (every install root is checked). Delegates to Ring 3: the
golden-fixture banner conformance property test.

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `core` (ADTs, validators, verdict logic, banner/drift) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ | — |
| `core.scanner` (F1–F10 checks) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `core.metals` (LSP client) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `core.graph` (traceability) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `cli` (entrypoints, multicall) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `plugin` (sbt AutoPlugin) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `verified/probatio` (Ring 6 mirrors) | ✅ | — | — | ✅ | — | — | ✅ | — | ✅ | — |
| shims (bash 3-line exec wrappers) | ✅ | ✅ | — | — | ✅ | — | — | — | ✅ | — |
| dependency-lint rule (R-ARCH1) | ✅ | — | ✅ | ✅ | — | — | — | — | ✅ | — |
| schema.yaml + env-var migration | ✅ | — | — | ✅ | ✅ | — | — | — | ✅ | — |

**Ring notes**:
- **R0** (compile): `probatioScalacOptions` (R-CS1–R-CS5) on core/cli; Scala 2.12 on plugin; `bash -n` on shims.
- **R1** (lint): Scalafix + WartRemover + scalafmt on Scala; shellcheck + shfmt on shims.
- **R2** (architecture): dependency-lint rule (R-ARCH1) — fails if workflow/* reaches adk4s.
- **R3** (property): Hedgehog 0.13.1 (NOT ScalaCheck). Conformance, verdict stability, banner golden-fixture, exit-code mapping, shim idempotency.
- **R4** (wire/persistence): byte-compatibility with .jq contracts; ledger round-trip; hook payload byte-stability.
- **R5** (mutation): Stryker4s on core/cli production logic. Thresholds: 90–95% pure domain, 80–90% adapters.
- **R6** (formal): verified-mirror pattern. 3 mirrors in `verified/probatio` leaf (Scala 3.7.2, Stainless). Bridge tests in `workflow/core` test sources.
- **R7** (model checking): ❌ no TLA+/Apalache. Gate's two-lock asymmetry is single-threaded per session; covered by bats oracle + R8.
- **R8** (adversarial review): MANDATORY. Fresh-context reviewer looks for silent fallback mappings, `case _` defaults, partial functions that could satisfy the bats oracle while violating §4 preservations.
- **R9** (telemetry): ❌ no otel4s/Daut. probatio is a build/CLI tool with no runtime telemetry surface.

## Technical Decisions

### Decision: Hedgehog, not ScalaCheck

**Context**: The requirements doc R-X3 names "ScalaCheck" as an allowed
dependency. The detected capability profile (`openspec/capability-profile.md`)
establishes that the project's property framework is Hedgehog 0.13.1, NOT
ScalaCheck. The profile's "profile wins" rule applies.

**Options considered**:
1. Use ScalaCheck (follow the requirements doc literally).
2. Use Hedgehog (follow the detected capability profile).

**Decision**: Option 2. Adding ScalaCheck would contradict the detected
stack and introduce a dependency not present elsewhere in the build. The
profile's "NOT ScalaCheck" consequence is explicit. The requirements doc
R-X3 is corrected in `specs/non-goals-guard/spec.md` to use Hedgehog.

**Consequences**: All property tests use `hedgehog-munit` 0.13.1. Generator
strategies use Hedgehog's `Gen` API. The `dependency-lint` rule rejects
ScalaCheck if it appears on any workflow/* classpath.

### Decision: Blocking I/O, not cats-effect

**Context**: R-X3 excludes cats and cats-effect from probatio. The
AGENTS.md FP mandate (Cats for data structures, Cats Effect for effects)
applies to adk4s product code under validation, not the validating tool.

**Options considered**:
1. Use cats-effect IO for probatio-cli (follow AGENTS.md globally).
2. Use blocking I/O via os-lib + System.exit (follow R-X3 literally).

**Decision**: Option 2. R-X3 is explicit: cats/cats-effect are excluded.
probatio is a CLI tool, not a service — blocking I/O is the natural
effect model. The AGENTS.md mandate is a recorded one-line deviation,
cited in R-X3 and the proposal's Ring 1 note.

**Consequences**: No `IO` monad, no `Resource`, no `Stream`. File I/O via
os-lib (`os.read`, `os.write`). Process execution via `os.proc` or
`scala.sys.process`. Exit codes via `System.exit`. The metals client uses
blocking stdin/stdout pipes. No concurrency scenarios are required
(probatio-core is pure by construction; the metals client is
request/response, not parallel streams).

### Decision: New `verified/probatio` leaf for Ring 6 mirrors

**Context**: See "Mirror placement decision" above. The existing `verified/`
module contains adk4s-side Ring 6 models. R-ARCH1 requires probatio to
depend on NOTHING adk4s-side.

**Decision**: New `verified/probatio` leaf module, pinned to Scala 3.7.2,
same Stainless setup as `verified/`, no adk4s dependency. Bridge tests in
`workflow/core` test sources depend on `verified/probatio % Test`.

**Consequences**: One additional sbt subproject. The `ring6` alias is
extended. Extraction to `sinemenda/probatio` reduces to `git filter-repo`
on `workflow/` + `verified/probatio/` + a publish-config change.

### Decision: Sealed enums over Iron refined types for closed sets

**Context**: The project uses Iron 3.3.2 for refined types. Several
probatio domain values are closed sets (Ring: R0–R9; ExitCode: 0/1/2;
Subcommand: one per predecessor script).

**Options considered**:
1. Use Iron refined types (`Int :| Within(0, 9)` etc.).
2. Use sealed enums with exactly the valid cases.

**Decision**: Option 2 for closed sets. A sealed enum makes invalid values
unrepresentable at the type level — strictly stronger than a runtime
predicate. Iron is used for open predicates (e.g., `SchemaVersion >= 1`),
but closed sets use sealed enums.

**Consequences**: `Ring` is a sealed enum with 10 cases, not an Iron
refined `Int`. `ExitCode` is a sealed enum with 3 cases. `Subcommand` is a
sealed enum with one case per predecessor script. Compile-negative tests
prove no extra case can be added without updating all exhaustive matches.

### Decision: Multicall binary, not separate binaries

**Context**: The requirements doc R-P6 specifies multicall dispatch by
argv(1) and argv(0). One native-image binary handles all subcommands.

**Options considered**:
1. Separate native-image binaries per subcommand.
2. One multicall binary dispatching on argv(1) or argv(0).

**Decision**: Option 2. A multicall binary is ~20–40 MB (one image for all
tools) vs. ~20–40 MB × N (one per tool). The download/cache cost is lower.
The dispatch is a pure function (argv → Subcommand) tested by Ring 3
property.

**Consequences**: `probatio` binary dispatches: `probatio spec-lint …` or
`probatio-spec-lint …` (symlink/hardlink). The `cli.ProbatioMain` entrypoint
checks argv(1) first, then argv(0) basename. One native-image build
configuration covers all subcommands.
