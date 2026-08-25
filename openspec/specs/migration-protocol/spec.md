# Spec: Migration Protocol

<!-- Delta spec for the port-scanner-to-probatio change. Defines HOW the
     port is proven safe — the strangler migration protocol. The bats oracle
     is the acceptance suite; the existing *_OVERRIDE env seams are the
     swap-points; conformance between Scala validators and the executable
     .jq contracts is a property test; hook shims swap in dependency order;
     exactly one implementation is active per tool during migration; skill
     documents update atomically with each tool swap. Covers R-M1…R-M5. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Bats oracle | The porting acceptance suite that SHALL pass unmodified at every step; the regression oracle whose green run at a seam proves a step complete | `openspec/schemas/verified-scala3/tests/*.bats` |
| `*_OVERRIDE` env seams | The strangler swap-points: environment variables that redirect a tool invocation to an alternate implementation, enabling incremental substitution without touching the oracle | `openspec/schemas/verified-scala3/hooks/gate.sh` (seam declarations) |
| Executable jq contracts | The single statement of record/report/payload formats; conformance oracles for the ported validators, retained as fixtures until conformance is green for a full release cycle | `openspec/schemas/verified-scala3/scanner/{ledger-record-contract,chain-state-report-contract,gate-hookjson-contract}.jq` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required. The bats oracle, `*_OVERRIDE` seams,
and jq contracts are behavioral contracts reused as-is — the migration
protocol consumes them, it does not modify them.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| — | — | — |

R-ARCH1 isolates the probatio tooling subprojects from all adk4s code — no
library module, test utility, or shared source is reused. The migration
protocol operates entirely on behavioral contracts (the oracle, seams, and
jq contracts listed above), not on adk4s type-inventory entries. The
"Concepts Used (from inventory)" table is intentionally empty.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Strangler migration protocol | Behavioral concept | The oracle-driven incremental porting protocol: a step is complete when the acceptance oracle is green with the ported tool substituted at its seam, with all other tools still on the predecessor implementation. Creates the strangler pattern as a first-class behavioral concept for this schema. |
| Conformance property-test contract | Behavioral concept | The bidirectional equivalence between a ported validator and an executable contract over a generated corpus: the validator accepts a record if and only if the contract accepts it. Not a spot check — a property over satisfying and violating records for every clause. |
| Exactly-one-implementation invariant | Behavioral concept | During migration, precisely one implementation per tool is active at any commit — no dual bash+scala installations. The install step asserts this via the shim's resolved target. |
| Atomic skill-doc update | Behavioral concept | Skill documents are updated in the same commit as the tool swap they reference; no commit on main carries a skill that points at a non-existent path. |

## ADDED Requirements

### Requirement: Bats oracle is the porting acceptance suite

The system SHALL designate the existing bats oracle as the porting
acceptance suite, and it SHALL pass unmodified at every step of the
incremental port. The existing environment-variable override seams are the
swap-points: a migration step is complete when the oracle is green with
the ported tool substituted at its seam and every other tool still running
on the predecessor implementation. The oracle SHALL NOT be modified to
accommodate a ported tool — a ported tool that requires an oracle change
is a behavior delta, not a port, and is rejected by the feature freeze.

**Given** the acceptance oracle covering the workflow's correctness
invariants, and a set of override seams that redirect individual tool
invocations to alternate implementations
**When** a ported tool is substituted at its seam and the oracle is
executed
**Then** the oracle passes without modification — every test case produces
the same pass/fail outcome as before the substitution

**Rationale**: The oracle is the only artifact that can detect a silent
behavior delta in a ported tool. If the oracle is modified to make a port
pass, the oracle ceases to be an independent witness and the port's
correctness claim becomes circular. The override seams already exist in
the predecessor implementation and are used by the oracle itself for test
isolation — reusing them as swap-points means the porting protocol adds
zero new test infrastructure.

#### Scenario: Ported tool passes oracle at its seam

**Given** a ported implementation of one tool and the predecessor
implementation of every other tool
**When** the ported tool is substituted at its override seam and the full
oracle is executed
**Then** every test passes and the oracle's source is byte-identical to
its pre-port state

#### Scenario: Oracle modification rejected as a behavior delta

**Given** a ported tool whose output differs from the predecessor on a
path the oracle exercises
**When** the ported tool is substituted at its seam and the oracle is
executed
**Then** the oracle fails on the differing path, and the failure is
classified as a behavior delta (rejected by the feature freeze) rather
than resolved by editing the oracle

#### Scenario: Partial migration — mixed predecessor and ported tools

**Given** two tools ported and substituted at their seams, with the
remaining tools still on the predecessor implementation
**When** the full oracle is executed
**Then** the oracle passes, proving the ported and predecessor tools
interoperate correctly at the protocol boundary

### Requirement: Conformance between validators and executable contracts is a property test

The system SHALL prove conformance between each ported Scala validator and
its corresponding executable contract as a property test over a generated
corpus, not as a spot check. For every clause of every contract, the
system SHALL generate records that satisfy the clause and records that
violate it, and SHALL assert that the validator accepts a record if and
only if the executable contract accepts the same record. The corpus SHALL
include the existing fixture set plus derived generators covering each
clause's satisfying and violating boundaries. The executable contract
files SHALL be retained as conformance fixtures until conformance is green
in CI for one full release cycle, and SHALL be deleted only after that
cycle completes.

**Given** a ported validator for a record format, the corresponding
executable contract, and a corpus of records satisfying and violating
each clause of that contract
**When** each record in the corpus is evaluated by both the validator and
the executable contract
**Then** the validator's acceptance of a record is equivalent to the
contract's acceptance of the same record, for every record in the corpus
and every clause in the contract

**Rationale**: A spot check of a few hand-picked records cannot prove
equivalence — it can only fail to disprove it. A property test over
generated satisfying and violating records for every clause is the
minimum evidence that the validator and the contract agree on the
boundary of every clause. Retaining the executable contracts as fixtures
for a full release cycle ensures that any regression in the validator is
caught by the contract before the contract is removed.

#### Scenario: Validator accepts exactly what the contract accepts

**Given** a generated record that satisfies all clauses of the contract
**When** the record is evaluated by the validator and by the executable
contract
**Then** the validator returns a successful result and the executable
contract exits zero

#### Scenario: Validator rejects exactly what the contract rejects

**Given** a generated record that violates one clause of the contract
**When** the record is evaluated by the validator and by the executable
contract
**Then** the validator returns a failure naming the violated clause and
the executable contract exits non-zero

#### Scenario: Contract files retained for a full release cycle

**Given** conformance property tests green in CI across one full release
cycle
**When** the release cycle completes
**Then** the executable contract files are deleted and the validators
become the sole statement of the record formats

#### Scenario: Contract files not deleted prematurely

**Given** conformance property tests green in CI for less than one full
release cycle
**When** a request to delete the executable contract files is evaluated
**Then** the deletion is refused — the contracts must remain as
conformance fixtures until the full cycle completes

### Requirement: Hook shims are swapped in dependency order after oracle clearance

The system SHALL swap hook shims to the ported implementation only after
the last subcommand behind each shim has passed the acceptance oracle at
its seam. The swap order SHALL proceed from the purest, best-covered
tools to the highest-risk, highest-blast-radius tool: the ledger and
chain-state tools first; then the checkpoint, registry-check, and
reconcile tools; then the spec-lint tool (ported together with the typed
lint-report seam it produces); then the metals client; and the gate tool
last. A shim SHALL NOT be swapped before every subcommand it dispatches
has a green oracle run against the ported binary.

**Given** a hook shim that dispatches to one or more subcommands, and an
ordered migration sequence
**When** the last subcommand behind the shim passes the acceptance oracle
at its seam
**Then** the shim is swapped to dispatch to the ported binary, and not
before

**Rationale**: A shim is the last integration point — swapping it before
the subcommands behind it are proven creates a window where the harness
calls an unproven implementation in production. The dependency order
minimizes blast radius: the tools with the purest logic and deepest
oracle coverage migrate first, so by the time the gate (per-turn,
highest-stranding-risk) migrates, every tool it calls has already been
proven at its seam.

#### Scenario: Ledger and chain-state shims swap first

**Given** the ledger and chain-state subcommands have passed the
acceptance oracle at their seams
**When** the shim swap is evaluated
**Then** the ledger and chain-state shims are swapped to the ported
binary, and no other shim is swapped before them

#### Scenario: Gate shim swaps last

**Given** every other subcommand has passed the acceptance oracle at its
seam and its shim has been swapped
**When** the gate shim swap is evaluated
**Then** the gate shim is swapped to the ported binary — it is the final
shim in the ordered sequence

#### Scenario: Shim swap refused before subcommand clearance

**Given** a shim whose last subcommand has not yet passed the acceptance
oracle at its seam
**When** a request to swap the shim is evaluated
**Then** the swap is refused — the shim remains on the predecessor
implementation until the subcommand is proven

### Requirement: Exactly one implementation per tool during migration

During migration, the installed schema SHALL have exactly one
implementation per tool active at any commit. No bash and scala duplicate
installations SHALL coexist for the same tool. The installation step
SHALL assert precisely-one by resolving each shim's target and verifying
that exactly one implementation is reachable — zero implementations is a
broken install, and two implementations is an ambiguous install. The
assertion SHALL fail the install in both cases.

**Given** an installation step that installs tool shims, and a migration
state where some tools are on the predecessor implementation and some are
on the ported implementation
**When** the installation step resolves each shim's target
**Then** exactly one implementation is reachable per tool — no tool has
zero and no tool has two

**Rationale**: A dual installation creates an ambiguity about which
implementation the harness will invoke — the answer depends on PATH
ordering, symlink resolution, or environment variables, none of which
are visible to the operator. A missing installation is a silent failure
where the harness invokes a non-existent path. Both are the "corrupt
ledger reads as clean" defect class in a different guise: the install
appears successful but the runtime is not what the operator expects.

#### Scenario: Single implementation per tool after partial migration

**Given** a partial migration where three tools are on the ported
implementation and the rest are on the predecessor implementation
**When** the installation step resolves each shim's target
**Then** each tool resolves to exactly one implementation — three to the
ported binary, the rest to the predecessor scripts

#### Scenario: Dual installation detected and rejected

**Given** an installation state where one tool has both a predecessor
script and a ported shim installed
**When** the installation step resolves the shim's target
**Then** the assertion fails with a message naming the tool and both
reachable implementations, and the install exits non-zero

#### Scenario: Missing installation detected and rejected

**Given** an installation state where one tool's shim resolves to a
non-existent path
**When** the installation step resolves the shim's target
**Then** the assertion fails with a message naming the tool and the
missing target, and the install exits non-zero

### Requirement: Skill documents updated atomically with tool swap

The agent-facing skill documents SHALL be updated atomically with the
tool swap they reference. Hard-coded script paths in skill documents
SHALL be re-expressed as invocations of the ported tool at the same
commit that swaps the corresponding shim. No commit on main SHALL carry
a skill document that references a non-existent path — the skill document
and the tool swap are one atomic unit. A skill document that references a
predecessor script path after that script has been removed is a broken
reference, and a skill document that references a ported tool path before
that tool is installed is a forward reference; both are prohibited.

**Given** a skill document containing hard-coded references to tool
invocations, and a tool swap that changes the invocation target
**When** the tool swap is committed
**Then** the skill document is updated in the same commit to reference
the new invocation target, and no commit on main has a skill document
referencing a path that does not exist at that commit

**Rationale**: A skill document is an agent's instruction for invoking a
tool. If the document references a path that no longer exists, the agent
follows the instruction and fails — silently, because the skill document
is read in a fresh context that has no memory of the path ever existing.
If the document is updated in a separate commit from the swap, the
interval between commits is a window where every commit on main has a
broken reference. Atomic updates close that window.

#### Scenario: Skill document and tool swap in one commit

**Given** a skill document referencing a predecessor script path and a
ported tool ready to swap at its shim
**When** the swap is committed
**Then** the skill document in the same commit references the ported
tool invocation, and the predecessor script path is not referenced by
any skill document at that commit

#### Scenario: Broken reference detected at commit time

**Given** a commit that swaps a shim to the ported binary but does not
update a skill document that referenced the predecessor script
**When** the skill-document lint check runs in CI
**Then** the check fails, naming the skill document and the non-existent
path it references, and the commit is blocked from main

#### Scenario: Forward reference detected at commit time

**Given** a commit that updates a skill document to reference a ported
tool invocation before the ported tool is installed
**When** the skill-document lint check runs in CI
**Then** the check fails, naming the skill document and the not-yet-
installed tool it references, and the commit is blocked from main

## Properties (Ring 3)

### Property: conformance-validator-contract-equivalence

**Invariant**: For every record in the generated corpus, the ported
validator accepts the record if and only if the executable contract
accepts the record. Equivalence is bidirectional: no record is accepted
by the validator but rejected by the contract, and no record is rejected
by the validator but accepted by the contract.

**Generator strategy**: `genContractRecord` — constructive over records
satisfying and violating each clause of each contract. For each clause,
generates a satisfying record and a violating record that exercises that
clause's boundary. Edge cases: empty record, all-clauses-satisfied
record, single-clause-violated records for every clause, multi-clause-
violated records. Corpus augmented with the existing fixture set under
`tests/fixtures/`.

```
property("validator-contract equivalence over corpus") {
  for {
    record <- genContractRecord.forAll
    contractResult = runContract(contractFile, record)
    validatorResult = validate(record)
  } yield {
    val contractAccepts = contractResult == 0
    val validatorAccepts = validatorResult.isRight
    Result.diff(contractAccepts, validatorAccepts)(_ == _)
  }
}
```

### Property: oracle-green-at-every-step

**Invariant**: The acceptance oracle produces the same set of pass/fail
outcomes before and after a tool substitution at a seam. This is a
regression property: the oracle is the fixed reference, and the ported
tool's behavior at the seam must not change any test's outcome.

**Generator strategy**: not a generated-input property — a regression
property over the oracle's own test suite. The "generator" is the set of
override-seam configurations: for each tool, one configuration with the
predecessor implementation and one with the ported implementation, with
all other seams on the predecessor.

```
property("oracle green at every step") {
  for {
    seamConfig <- genSeamConfiguration.forAll
    predecessorResult = runOracle(seamConfig.withPredecessor)
    portedResult = runOracle(seamConfig.withPorted)
  } yield {
    Result.diff(predecessorResult, portedResult)(_ == _)
  }
}
```

### Property: exactly-one-implementation-invariant

**Invariant**: For every tool in the installation, exactly one
implementation is reachable via the shim's resolved target. The invariant
holds at every commit on main during migration, regardless of which tools
have been ported.

**Generator strategy**: `genMigrationState` — constructive over subsets
of tools that have been ported (the ported set ranges from empty to all
tools). For each subset, the installation is performed and each shim's
target is resolved.

```
property("exactly one implementation per tool") {
  for {
    portedSet <- genMigrationState.forAll
    targets = resolveAllShimTargets(portedSet)
  } yield {
    Result.assert(
      targets.forall(_.resolvedTarget.isDefined) &&
      targets.forall(t => t.candidateTargets.size == 1)
    )
  }
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Oracle source modified to accommodate a ported tool | A modified oracle is not an independent witness; the port's correctness claim becomes circular | `git diff` of oracle files is empty at every migration step; enforced by CI check |
| Two implementations installed for the same tool | Dual installation creates an ambiguous runtime — PATH ordering, not operator intent, decides which runs | Install step asserts `candidateTargets.size == 1` per tool and fails otherwise |
| Zero implementations reachable for a tool | A missing installation is a silent failure where the harness invokes a non-existent path | Install step asserts `resolvedTarget.isDefined` per tool and fails otherwise |
| Skill document referencing a predecessor script after removal | A broken reference causes the agent to follow a non-existent path in a fresh context | Skill-document lint check in CI greps for removed paths and fails if any skill references one |
| Skill document referencing a ported tool before installation | A forward reference causes the agent to invoke a tool that is not yet available | Skill-document lint check verifies referenced paths exist at the commit |
| Executable contract files deleted before one full release cycle of green conformance | Premature deletion removes the conformance oracle before the validator is proven over time | CI gate on the deletion step checks release-cycle duration |
| Shim swapped before its last subcommand passes the oracle | An unproven subcommand in production is the defect class the port exists to avert | Swap-order check in CI verifies oracle-green status before allowing a shim swap |

## Formal Contracts (Ring 6)

### Contract: Conformance relation — validator iff contract

The conformance relation between a ported validator and its executable
contract is a pure kernel expressible as a verified-mirror model. The
model states the bidirectional equivalence as a postcondition: for every
record in the model's domain, the validator's judgment equals the
contract's judgment.

**Precondition** (`require`): record is in the model's domain (a
finite representation of the record format's clause space)
**Postcondition** (`ensuring`): `validatorAccepts(record) ==
contractAccepts(record)`

```scala
def conformance(record: RecordModel): Boolean = {
  // model of the validator's clause checks over a finite record domain
  val validatorJudgment = modelValidate(record)
  // model of the contract's clause checks over the same domain
  val contractJudgment = modelContract(record)
  validatorJudgment == contractJudgment
}.ensuring(result => result == (modelValidate(record) == modelContract(record)))
```

### Contract: Conformance symmetry — no false positives and no false negatives

The conformance relation is symmetric: the validator produces no false
positives (accepts a record the contract rejects) and no false negatives
(rejects a record the contract accepts). Both directions are stated as
separate postconditions so a partial conformance cannot satisfy the
contract by passing only one direction.

**Precondition** (`require`): record is in the model's domain
**Postcondition** (`ensuring`): `!validatorAccepts(record) ||
contractAccepts(record)` (no false positive) AND
`!contractAccepts(record) || validatorAccepts(record)` (no false
negative)

```scala
def conformanceNoFalsePositive(record: RecordModel): Boolean = {
  !modelValidate(record) || modelContract(record)
}.ensuring(result => result == (!modelValidate(record) || modelContract(record)))

def conformanceNoFalseNegative(record: RecordModel): Boolean = {
  !modelContract(record) || modelValidate(record)
}.ensuring(result => result == (!modelContract(record) || modelValidate(record)))
```

> **Delegated to Ring 3**: the full corpus-based conformance property
> (validator ⟺ contract over the fixture corpus plus derived generators
> for all 12 ledger clauses and the two report/payload contracts) runs as
> a Hedgehog property test — the model's finite domain cannot represent
> the full generator space. The Ring 6 model covers the *decision*
> (clause satisfaction equivalence), not the jq execution layer or the
> ujson/uPickle wire layer. A bridge property test binds the shipped
> validators to the model on the same generated inputs.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Oracle is the acceptance suite and passes unmodified | Requirement: Bats oracle is the porting acceptance suite | regression property (oracle-green-at-every-step) + CI git-diff check on oracle sources | existing bats oracle (unmodified) |
| Oracle modification rejected as behavior delta | Requirement: Bats oracle is the porting acceptance suite | compile-negative obligation (oracle source diff is empty) + adversarial review (Ring 8) | CI git-diff gate on existing bats oracle |
| Conformance is a property test over generated corpus | Requirement: Conformance between validators and executable contracts is a property test | property test (conformance-validator-contract-equivalence) | Hedgehog conformance property test (to be created in probatio-core test sources) |
| Conformance bidirectional — no false positives or false negatives | Requirement: Conformance between validators and executable contracts is a property test | formal contract (Ring 6 conformance symmetry) + bridge property test | PureScala conformance model in verified leaf + bridge property test (to be created) |
| Contract files retained for one full release cycle | Requirement: Conformance between validators and executable contracts is a property test | CI gate on deletion step (release-cycle duration check) | tasks.md Phase 6 gate |
| Contract files not deleted prematurely | Requirement: Conformance between validators and executable contracts is a property test | compile-negative obligation (deletion refused before full cycle) | CI gate |
| Shims swapped in dependency order | Requirement: Hook shims are swapped in dependency order after oracle clearance | CI swap-order check (verifies oracle-green before shim swap) | tasks.md Phase 4/5 ordering |
| Gate shim swaps last | Requirement: Hook shims are swapped in dependency order after oracle clearance | CI swap-order check (gate is final in sequence) | tasks.md Phase 5 |
| Shim swap refused before subcommand clearance | Requirement: Hook shims are swapped in dependency order after oracle clearance | compile-negative obligation (swap refused before oracle green) | CI gate |
| Exactly one implementation per tool | Requirement: Exactly one implementation per tool during migration | property test (exactly-one-implementation-invariant) + install assertion | install assertion in install-skills step + Hedgehog property test (to be created) |
| Dual installation detected and rejected | Requirement: Exactly one implementation per tool during migration | compile-negative obligation (candidateTargets.size == 1) | install assertion in install-skills step |
| Missing installation detected and rejected | Requirement: Exactly one implementation per tool during migration | compile-negative obligation (resolvedTarget.isDefined) | install assertion in install-skills step |
| Skill documents updated atomically with tool swap | Requirement: Skill documents updated atomically with tool swap | CI skill-document lint check (referenced paths exist at commit) | CI skill-document lint check (to be created) |
| Broken reference detected at commit time | Requirement: Skill documents updated atomically with tool swap | compile-negative obligation (no skill references a non-existent path) + adversarial review (Ring 8) | CI skill-document lint check |
| Forward reference detected at commit time | Requirement: Skill documents updated atomically with tool swap | compile-negative obligation (no skill references a not-yet-installed tool) | CI skill-document lint check |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `tests/*.bats` (17 files) | bats oracle | `openspec/schemas/verified-scala3/tests/` | The acceptance suite; passes unmodified at every step. CI `git diff` gate asserts byte-identity. |
| `SPEC_LINT_OVERRIDE` | env seam | `openspec/schemas/verified-scala3/scanner/chain-state.sh`, `hooks/gate.sh` | Redirects spec-lint invocation to an alternate implementation; swap-point for the spec-lint port. |
| `CHAIN_STATE_OVERRIDE` | env seam | `openspec/schemas/verified-scala3/hooks/gate.sh` line 126 | Redirects chain-state invocation; swap-point for the chain-state port. |
| `DANGER_SCAN_OVERRIDE` | env seam | `openspec/schemas/verified-scala3/hooks/gate.sh` line 129 | Redirects danger-scan invocation in the post-edit tier; swap-point for the danger-scan port. |
| `RECONCILE_OVERRIDE` | env seam | `openspec/schemas/verified-scala3/hooks/gate.sh` line 127 | Redirects reconcile invocation; swap-point for the reconcile port. |
| `GATE_command` / gate shim-ability | seam | `openspec/schemas/verified-scala3/hooks/gate.sh` | The gate itself is shim-able: the hook adapters invoke `gate.sh` by path, so swapping `gate.sh` to a 3-line `exec` shim is the gate swap-point. Swapped last per R-M3. |
| `ledger-record-contract.jq` | jq contract (12 clauses) | `openspec/schemas/verified-scala3/scanner/` | Conformance fixture for the ledger validator; 12 clauses: object type, required fields, version integer, timestamp format, change/spec non-empty no-separator, ring closed domain, obligation non-empty, artifact non-empty, command non-empty, exit integer, baseline hex revision, optional-field shape. Retained until one full release cycle of green conformance. |
| `chain-state-report-contract.jq` | jq contract | `openspec/schemas/verified-scala3/scanner/` | Conformance fixture for the chain-state report validator. |
| `gate-hookjson-contract.jq` | jq contract | `openspec/schemas/verified-scala3/scanner/` | Conformance fixture for the gate payload validator. |
| `tests/fixtures/` | fixture corpus | `openspec/schemas/verified-scala3/tests/fixtures/` | Existing records augmenting the derived generators in the conformance property test. |
| `ConformanceSpec.scala` | Hedgehog property test | `probatio-core/src/test` | `property("validator-contract equivalence over corpus")` with `genContractRecord` over all 12 ledger clauses + 2 report/payload contracts. |
| `ConformanceModel.scala` | PureScala model (Ring 6) | `verified/` leaf (Scala 3.7.2) | Models the conformance *decision* (clause satisfaction equivalence), not the jq execution or ujson wire layer. |
| `ConformanceBridgeSpec.scala` | bridge property test | `probatio-core/src/test` | Binds shipped validators to `ConformanceModel` on the same generated inputs. |
| `OracleGreenCheck.scala` | regression property | `probatio-core/src/test` | `property("oracle green at every step")` — runs the oracle with predecessor vs ported at each seam configuration and asserts equal outcomes. |
| `InstallPreciselyOneSpec.scala` | property test + install assertion | `probatio-cli/src/test` | `property("exactly one implementation per tool")` with `genMigrationState`; install step asserts `candidateTargets.size == 1` and `resolvedTarget.isDefined`. |
| `SkillDocLintCheck.scala` | CI lint check | `openspec/schemas/verified-scala3/tests/` or CI workflow | Greps skill documents for references to `scanner/*.sh` paths and verifies referenced paths exist at the commit; blocks main on broken or forward references. |
| `install-skills.sh` / `install-hooks.sh` | install scripts | `openspec/schemas/verified-scala3/scanner/` | The install step that asserts precisely-one implementation per tool via shim target resolution. |
| `.pi/skills/openspec-scan-concepts/SKILL.md` | skill document | `.pi/skills/openspec-scan-concepts/` | References `scanner/scan.sh`, `scanner/concept-scanner.scala`, `scanner/registry-check.sh`; re-expressed as `probatio <tool>` invocations atomically with the shim swap. |
| `.pi/skills/openspec-code-intel/SKILL.md` | skill document | `.pi/skills/openspec-code-intel/` | References `scanner/metals-start.sh`, `scanner/metals-call.sh`, `scanner/impact-scan.sh`; re-expressed as `probatio metals`, `probatio impact-scan` atomically with the shim swap. |
| `.pi/skills/openspec-property-tests/SKILL.md` | skill document | `.pi/skills/openspec-property-tests/` | References scanner script paths for property-test recipes; re-expressed atomically with the shim swap. |
| `tasks.md` Phase 2–5 | migration ordering | `openspec/changes/port-scanner-to-probatio/tasks.md` | Encodes the R-M3 swap order: ledger + chain-state (Phase 2); checkpoint, registry-check, reconcile (Phase 4); spec-lint with `LintReport` seam (Phase 4); metals (Phase 4); gate (Phase 5). |
| `tasks.md` Phase 6 | contract retirement | `openspec/changes/port-scanner-to-probatio/tasks.md` | `.jq` files deleted after one full release cycle of green conformance in CI. |
