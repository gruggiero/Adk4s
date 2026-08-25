# Tasks

Stock OpenSpec task checklist, derived from implementation-order.md.
This file lets `openspec list` and task tooling report progress; the
apply phase also tracks detailed state in implementation-progress.md.

## 0. Phase 0 — Setup & Spikes (gated before any spec implementation)

- [x] 0a. R-ARCH1 dependency-lint rule in build.sbt — fails if any workflow/* project's classpath reaches an adk4s module OR a forbidden dependency (cats, cats-effect, fs2, llm4s, workflows4s, scalacheck)
- [x] 0b. R-CS1–R-CS5 probatioScalacOptions setting on probatio-core/probatio-cli subprojects only (-Werror, -Wconf:cat=deprecation:e, -Wconf:cat=Feature:e, -Wvalue:discard, -Ysafe-init); NOT applied repo-wide
- [x] 0c. V1 spike: GraalVM native-image builds probatio-core + probatio-cli without hand-maintained reflection config (uPickle/ujson/os-lib/mainargs believed safe; scalameta is the known risk) — HARD BLOCKER
- [x] 0d. V2 spike: cold/warm wall-clock latency of gate binary meets R-N1 budget (200ms cold, 50ms warm) on linux-x86_64 — HARD BLOCKER (R-N1: change does not ship if unmet)
- [x] 0e. V3 spike: pi TS adapter invokes gate.sh by path; shim replacement verified end-to-end under `pi -e`
- [x] 0f. V4 spike: prebuilt-binary download host choice (GitHub Releases vs. Maven zip artifacts) and checksum story
- [x] Gate: all six Phase 0 tasks verified or change is re-scoped before any porting begins

## 1. probatio-core

- [x] Prerequisite: scaffold workflow/core and verified/probatio sbt subprojects in build.sbt (Scala 3.8.4 + 3.7.2 pin, StainlessPlugin, probatioScalacOptions, allowed deps only)
- [ ] Step 1 — typed contract: Outcome[A] sealed enum, LedgerRecord case class, Ring sealed enum (R0–R9), Ledger module (read/append/validate only), ContractViolation sealed trait (12 cases), ChainStateReport, LintReport/SpecLintReport, GatePayload, BannerEngine, DriftScan, MetalsClient signatures (compiles, human gate 1/2)
- [ ] Step 2 — test oracle: 73 scenarios + 8 Hedgehog properties (validator conformance both directions, F1–F10 verdict equality, banner golden-fixture, append-only roundtrip, Outcome totality, chain-state referential transparency, ContractViolation totality, LintReport roundtrip) + compile-negative stubs (no update/delete/rewrite, no fourth Outcome case, no case _, no 13th ContractViolation, no asInstanceOf, no cats imports) (human gate 2/2)
- [ ] Step 3 — implementation: port 12-clause validator from ledger-record-contract.jq, port chain-state verdict logic, port banner/drift engine, port metals LSP client, port F1–F10 checks, port graph traceability
- [ ] R0: compile under probatioScalacOptions (R-CS1–R-CS5) — zero warnings
- [ ] R1: Scalafix DisableSyntax + WartRemover (relaxed set) + scalafmt
- [ ] R2: dependency-lint rule passes (no adk4s/cats/cats-effect/fs2 on classpath)
- [ ] R3: 8 Hedgehog properties green (Hedgehog 0.13.1, NOT ScalaCheck)
- [ ] R4: ledger round-trip + old-fixture parse + .jq contract conformance (3 contracts)
- [ ] R5: Stryker4s on workflow/core production logic (threshold 90–95% pure domain)
- [ ] R6: 3 verified-mirror kernels (LedgerValidatorKernel, ChainStateKernel, BannerEngineKernel) in verified/probatio + bridge property tests in workflow/core test sources; `sbt ring6` green
- [ ] R8: adversarial review (fresh context) — look for silent fallback mappings, case _ defaults, partial functions collapsing undetermined into finding
- [ ] Concept-delta check (scanner diff) + update openspec/concept-inventory.md + checkpoint

## 2. cli-protocol

- [x] Prerequisite: scaffold workflow/cli sbt subproject (Scala 3.8.4, mainargs + os-lib + uPickle, probatioScalacOptions, depends on probatio-core)
- [x] Step 1 — typed contract: Subcommand sealed enum (one case per predecessor script), ProbatioMain multicall dispatch, 9 @main entrypoint signatures (SpecLintCmd, ChainStateCmd, GateCmd, LedgerCmd, DangerScanCmd, CheckpointCmd, ConceptScanCmd, GraphCmd, HelpCmd), ParseError sealed trait (compiles, human gate 1/2)
- [x] Step 2 — test oracle: 30 scenarios + 6 Hedgehog properties (exit-code-mapping-is-total-and-disjoint, undetermined-never-collapses, stdout-conformance-with-jq-contracts, arg-parse-error-attribution, help-lists-every-flag, multicall-dispatch-equivalence) (human gate 2/2)
- [x] Step 3 — implementation: multicall dispatch (argv(1) then argv(0) basename), mainargs arg parsing with named errors, Outcome[Int] → System.exit mapping, uPickle stdout encoding byte-compatible with .jq contracts
- [x] R0: compile under probatioScalacOptions — zero warnings
- [x] R1: Scalafix + WartRemover + scalafmt
- [x] R2: dependency-lint rule passes
- [x] R3: 6 Hedgehog properties green
- [x] R4: byte-compatible stdout vs 3 .jq contracts (ledger-record, chain-state-report, gate-hookjson)
- [x] R5: Stryker4s on workflow/cli production logic (threshold 80–90% adapters)
- [x] R8: adversarial review — look for exit-2 collapsed into exit-1, payload deviation, silent arg-parse failures
- [x] Concept-delta check + inventory update + checkpoint

## 3. sbt-plugin

- [ ] Prerequisite: scaffold workflow/plugin sbt subproject (Scala 2.12, sbt 1.x AutoPlugin, NO probatio-core dependency)
- [ ] Step 1 — typed contract: ProbatioPlugin AutoPlugin, 7 task signatures (probatioInstall, probatioSpecLint, probatioChainState, probatioCheckpoint, probatioLedgerAppend, probatioGateShim, probatioUninstall), probatioVersion setting, GraalVMHome setting (compiles, human gate 1/2)
- [ ] Step 2 — test oracle: 22 scenarios + 3 Hedgehog properties (shim-idempotency, exit-code-mapping-distinct, install-resolution-order) (human gate 2/2)
- [ ] Step 3 — implementation: AutoPlugin with projectSettings, install resolution (prebuilt binary → assembly JAR → opt-in local native-image), exit-code mapping (0→success, 1→sys.error finding, 2→sys.error undetermined), 3-line shim generation, uninstall cleanup
- [ ] R0: compile under Scala 2.12 for sbt 1.x
- [ ] R1: Scalafix + WartRemover + scalafmt (Scala 2.12 compatible)
- [ ] R2: dependency-lint rule passes (no probatio-core on plugin classpath — R-S1)
- [ ] R3: 3 Hedgehog properties green + scenario tests (sbt test framework)
- [ ] R8: adversarial review — look for load-time side effects, GlobalScope abuse, deprecated sbt operators, network resolution at build load
- [ ] Concept-delta check + inventory update + checkpoint

## 4. native-packaging

- [x] Prerequisite: GraalVM native-image plugin/config in build.sbt; native-image-config directory for workflow/cli
- [x] Step 1 — typed contract: release artifact manifest (binary + JAR + SHA-256 + SBOM + sources per platform), CI workflow structure, native-image build config (compiles, human gate 1/2)
- [x] Step 2 — test oracle: 18 scenarios + 4 Hedgehog properties (checksum-roundtrip, SBOM-parseability, release-artifact-completeness, CI-reproducibility) (human gate 2/2)
- [x] Step 3 — implementation: native-image build for gate (mandatory) + other subcommands (optional-with-warning), GitHub Releases CI pipeline (linux-x86_64, macos-aarch64, macos-x86_64, windows-x86_64 JAR-only), SHA-256 checksums, SBOM generation, sources JAR
- [x] R0: native-image build succeeds for gate binary
- [ ] R1: shellcheck + shfmt on any retained scripts
- [x] R2: dependency-lint rule passes
- [x] R3: 4 Hedgehog properties green
- [ ] R4: release artifacts byte-stable (checksum verification, SBOM parseability)
- [x] R8: adversarial review — look for silent JAR fallback on supported platform, tampered download accepted, locally-built artifacts accepted
- [x] V1/V2 spike gates verified (from Phase 0) — R-N1 latency budget met
- [x] Concept-delta check + inventory update + checkpoint

## 5. migration-protocol

- [ ] Prerequisite: identify all *_OVERRIDE seams (SPEC_LINT_OVERRIDE, CHAIN_STATE_OVERRIDE, DANGER_SCAN_OVERRIDE, GATE_command) in existing scanner scripts
- [ ] Step 1 — typed contract: conformance property test contract (validator ⊨ .jq contract both directions), seam swap protocol, exactly-one-implementation invariant (compiles, human gate 1/2)
- [ ] Step 2 — test oracle: 16 scenarios + 3 Hedgehog properties (validator-conformance-both-directions, oracle-immutability-at-every-step, exactly-one-implementation) (human gate 2/2)
- [ ] Step 3 — implementation: port subcommands one at a time (earliest-dependency-first), swap *_OVERRIDE seams to point at probatio binary, run bats oracle after each swap, swap hook shims last after all subcommands green
- [ ] R0: each ported subcommand compiles and runs
- [ ] R1: shellcheck + shfmt on swapped shims
- [ ] R2: dependency-lint rule passes
- [ ] R3: 3 Hedgehog properties green + bats oracle (17 files) passes unmodified at every step
- [ ] R4: .jq contract conformance (validator ⊨ contract over fixture corpus, both directions)
- [ ] R8: adversarial review — look for premature contract deletion, swap before oracle clearance, dual/missing installation, broken/forward references in skill docs
- [ ] Concept-delta check + inventory update + checkpoint

## 6. non-goals-guard

- [x] Prerequisite: dependency-lint rule from Phase 0a is in place
- [x] Step 1 — typed contract: feature-freeze contract, allowed-dependency set, dependency-lint rule structure (compiles, human gate 1/2)
- [x] Step 2 — test oracle: 12 scenarios + 3 Hedgehog properties (F1–F10 verdict stability across port, dependency boundary is closed, oracle immutability at every migration step) (human gate 2/2)
- [x] Step 3 — implementation: NonGoalsGuardSpec with verdict stability property (bash spec-lint vs probatio spec-lint over fixture corpus), dependency boundary property (all workflow/* subprojects × forbidden deps), oracle immutability property (git history check)
- [x] R0: NonGoalsGuardSpec compiles
- [x] R1: Scalafix + WartRemover + scalafmt
- [x] R2: dependency-lint rule verifies all forbidden deps rejected (cats, cats-effect, fs2, llm4s, workflows4s, scalacheck, adk4s-*)
- [x] R3: 3 Hedgehog properties green
- [x] R8: adversarial review — look for F11 check accepted, verdict alteration accepted, new workflow feature accepted, cats dep accepted, ScalaCheck dep accepted
- [x] Concept-delta check + inventory update + checkpoint

## 7. schema-policy

- [ ] Prerequisite: identify all schema.yaml, hooks/README.md, env-var, and cache-dir references in the existing schema
- [ ] Step 1 — typed contract: v14 schema.yaml structure (rename verified-scala3 → probatio), generatedBy stamp rename, env-var migration (VERIFIED_SCALA3_HOOKS → PROBATIO_HOOKS with deprecated alias), cache-dir migration (human gate 1/2)
- [ ] Step 2 — test oracle: 20 scenarios + 3 Hedgehog properties (deprecated-alias-accepted-for-one-major, cache-migration-roundtrip, generatedBy-stamp-rename) (human gate 2/2)
- [ ] Step 3 — implementation: schema.yaml v14 bump with rename, generatedBy stamp rename, env-var deprecated alias (one major), cache-dir migration with fallback, hooks/README.md policy rewrite (retire jq/python3/shellcheck/shfmt prerequisites; java removed as runtime prerequisite)
- [ ] R0: schema.yaml valid YAML; hooks/README.md renders
- [ ] R3: 3 Hedgehog properties green (deprecated alias, cache migration, stamp rename)
- [ ] R4: schema.yaml backward compatibility (old fixture → new schema → expected behavior); env-var alias accepted for one major
- [ ] R8: adversarial review — look for retired prerequisites still in table, legacy env var read after one major, schema template changes, sbt 2.x migration
- [ ] Concept-delta check + inventory update + checkpoint

## 8. provenance-validation

- [x] Prerequisite: specs 1 (probatio-core) and 2 (cli-protocol) are COMPLETE — this spec extends their types
- [x] Step 1 — typed contract: ProvenanceFields case class, ValidatedRecord case class, extended ContractViolation sealed trait (15 variants: 12 existing + OptionalFieldTypeInvalid, ObserverProvenanceInvalid, SessionProvenanceInvalid), Validator.validateFull signature (15 clauses → Either[ContractViolation, ValidatedRecord]) (compiles, human gate 1/2)
- [x] Step 2 — test oracle: 21 scenarios + 3 Hedgehog properties (validator-conforms-to-jq-contract-15-clauses, ContractViolation-totality-15-clauses, adversarial-review-ring-session-presence) + compile-negative stubs (no 16th ContractViolation variant, no session field on LedgerRecord core type, no force flag on ledger append, no read path that skips validation) (human gate 2/2)
- [x] Step 3 — implementation: extend Validator from 12 to 15 clauses (add optional-field type checks, observer provenance, session provenance), lift ContractViolation cap from 12 to 15, add ProvenanceFields + ValidatedRecord, enforce write-time validation in LedgerCmd.append (reject with Finding on validation failure), enforce read-time validation in Ledger.read (reject as undetermined on any malformed row)
- [x] R0: compile under probatioScalacOptions — zero warnings
- [x] R1: Scalafix + WartRemover + scalafmt
- [x] R2: dependency-lint rule passes
- [x] R3: 3 Hedgehog properties green (Hedgehog 0.13.1)
- [x] R4: ledger round-trip with provenance fields + 15-clause contract conformance
- [x] R5: Stryker4s on changed Validator + LedgerCmd + Ledger.read logic (threshold 90–95% pure domain)
- [x] R6: extend LedgerValidatorKernel from 12 to 15 clauses in verified/probatio + bridge property tests; `sbt ring6` green
- [x] R8: adversarial review (fresh context) — look for silent acceptance of R8 rows missing session, case _ defaults in provenance clause matching, force/bypass flags, read paths that skip malformed rows
- [x] Concept-delta check + inventory update + checkpoint

## 9. gate-checkpoint-lock

- [ ] Prerequisite: specs 1 (probatio-core) and 2 (cli-protocol) are COMPLETE — this spec reuses `Outcome[A]` and `LedgerRecord` and the `GateCmd` CLI entrypoint
- [ ] Step 1 — typed contract: `GateEvent` sealed enum, `GateDecision` sealed enum (Allow/Block), `SpecPhase` sealed enum (Oracle/Implementation/Verified), `BlockReason` sealed trait (PredecessorNotVerified, PredecessorNotCheckpointed, OracleOrderingViolation, GrantRequired), `PredecessorCheck` pure function signature, `GrantWaiver` pure function signature, `PresentationMarker` value type (compiles, human gate 1/2)
- [ ] Step 2 — test oracle: 12 scenarios + 3 Hedgehog properties (predecessor-check-requires-presentation, grant-waiver-requires-presentation, block-reason-distinguishes-not-checkpointed) + compile-negative stubs (no fifth GateEvent, no fifth BlockReason variant, no file I/O in PredecessorCheck/GrantWaiver, no case _ in GateDecision match) (human gate 2/2)
- [ ] Step 3 — implementation: port gate decision logic from gate.sh to pure Scala functions in probatio-core, wire GateCmd CLI entrypoint to read state files and call the pure functions, map GateDecision to Outcome[Int] at the CLI boundary
- [ ] R0: compile under probatioScalacOptions — zero warnings
- [ ] R1: Scalafix + WartRemover + scalafmt
- [ ] R2: dependency-lint rule passes (no adk4s/cats/cats-effect/fs2 on classpath)
- [ ] R3: 3 Hedgehog properties green (Hedgehog 0.13.1, NOT ScalaCheck)
- [ ] R5: Stryker4s on PredecessorCheck + GrantWaiver + BlockReason.render (threshold 90–95% pure domain)
- [ ] R6: GateDecisionKernel in verified/probatio (models the predecessor check and grant waiver decisions) + bridge property tests; `sbt ring6` green
- [ ] R8: adversarial review (fresh context) — look for presentation check bypassed when state dir is empty, grant waived on Verified without presentation, block reason conflating not-checkpointed with not-verified, escape hatch not bypassing both checks, case _ defaults in GateDecision match
- [ ] Concept-delta check + inventory update + checkpoint
