# Spec: Entrypoint Split

Every subcommand's entrypoint lives in one 4,701-line source file. Mutation testing scores a
spec's diff against the whole file, so a change to one subcommand is scored against ten it
did not touch: two of the previous change's three below-threshold scores were measured
this way (77.26% and 46.58% against an 80% target). The
file is also where most remaining specs of this change make their edits. This spec splits it,
one file per subcommand, without changing what any subcommand does.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The protocol's **Gate** action is the evidence that a behaviour-preserving refactor preserved behaviour: the acceptance suite and the differential comparison, before and after. The protocol's actions and state are unchanged. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum (11 cases) | `org.sinemenda.probatio.cli` |
| `SubcommandWiring` | object | `org.sinemenda.probatio.cli` |
| `CliContext` | final case class | `org.sinemenda.probatio.cli` |
| `StdoutRenderer[A]` | typeclass | `org.sinemenda.probatio.cli` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

None. The eleven entrypoint objects keep their names, members and signatures. Only the file
each lives in changes.

### Type-Widening Impact

None. No type gains, loses or changes a member.

## ADDED Requirements

### Requirement: Each subcommand's entrypoint resides in its own source file

Each subcommand's entrypoint SHALL reside in a source file of its own, and no source file MAY
hold the entrypoints of two subcommands.

**Given** the command-line module's main sources
**When** the entrypoint files are listed
**Then** there is one file per subcommand, and each holds exactly one entrypoint

#### Scenario: Happy path — every subcommand has its own file

**Given** the eleven subcommands
**When** the entrypoint files are listed
**Then** each subcommand's entrypoint is found in a distinct file

#### Scenario: Adversarial — a file holding two entrypoints is reported

**Given** a source file that declares two subcommand entrypoints
**When** the layout check runs
**Then** it reports that file, naming both entrypoints

### Requirement: Every subcommand behaves exactly as before

Every subcommand SHALL produce the same standard output, standard error and termination
status after the split as before it, for every invocation, and the split MUST NOT alter any
subcommand's behaviour.

**Given** the full invocation corpus of the acceptance suite, the subprocess conformance run
and the parity properties
**When** each runs before and after the split
**Then** every observable result is identical

#### Scenario: Happy path — the acceptance suite is unchanged

**Given** the acceptance suite's per-file failure counts before the split
**When** it runs after the split
**Then** every file's count is identical

#### Scenario: Adversarial — a rejected invocation is still rejected identically

**Given** an invocation every subcommand rejects before the split — an unknown flag, a
missing value
**When** it runs after the split
**Then** it is rejected with the same message and termination status

### Requirement: Moved code is moved, not edited

The body of every entrypoint SHALL be identical after the split to its body before, apart
from the declarations a separate file requires, and no line inside a moved body MAY change.

**Given** each entrypoint's body before and after the split
**When** they are compared with the file-level declarations removed
**Then** they are identical

**Rationale**: a mechanical move is reviewable in minutes and cannot change behaviour. A move
combined with an edit is neither. The remaining specs make their edits after this one,
each in its own diff.

#### Scenario: Happy path — a moved body compares identical

**Given** an entrypoint's body in the original file and in its new file
**When** they are compared, ignoring package, import and file-header lines
**Then** they are identical

#### Scenario: Adversarial — an edit hidden in the move is detected

**Given** a moved body with one changed line
**When** the comparison runs
**Then** it reports that line

## Properties (Ring 3)

### Property: split-preserves-every-observable

**Invariant**: for every invocation in the combined corpus — every acceptance-suite call,
every conformance invocation, every parity-property fixture — the output and termination
status after the split equal those before.

**Generator strategy**: the corpus is the union of the existing suites' invocations, taken
as a fixed, enumerated domain. It is run through the parity properties' own generators with
their existing seeds, so the before and after runs see identical inputs. The domain is
finite; the limit is stated. Each process runs in the hermetic environment of
`hermetic-test-processes`, so the comparison cannot pass because of an inherited variable.

```
property("the split preserves every observable") {
  for {
    inv <- Gen.element(corpus.head, corpus.tail).forAll
  } yield Result.assert(runBefore(inv) == runAfter(inv))
}
```

## Compile-Negative Obligations

None — stated. The split adds no type and forbids no new construction; its guarantees are
behavioural (the property) and structural (the layout and move checks).

## Formal Contracts (Ring 6)

No formal contracts — stated skip: a mechanical relocation with no decision, fold or law of
its own; every kernel binding its moved code is unchanged.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Every subcommand has its own file | Requirement: Each subcommand's entrypoint resides in its own source file + Scenario: Happy path — every subcommand has its own file | scenario test over the source tree | `CliSurfaceSpec` |
| A file holding two entrypoints is reported | Requirement: Each subcommand's entrypoint resides in its own source file + Scenario: Adversarial — a file holding two entrypoints is reported | scenario test with a negative fixture | `CliSurfaceSpec` |
| The acceptance suite is unchanged | Requirement: Every subcommand behaves exactly as before + Scenario: Happy path — the acceptance suite is unchanged | bats oracle before and after, recorded in the evidence ledger | `hook-tiers.bats` via `probatioOracleDiff` |
| A rejected invocation is rejected identically | Requirement: Every subcommand behaves exactly as before + Scenario: Adversarial — a rejected invocation is still rejected identically | scenario test | `CliParseErrorSpec` |
| Every observable is preserved | Property: split-preserves-every-observable | subprocess property over the enumerated corpus | `SubprocessConformanceSpec` |
| A moved body compares identical | Requirement: Moved code is moved, not edited + Scenario: Happy path — a moved body compares identical | mechanical comparison at Ring 8, recorded | `EntrypointContractSpec` |
| An edit hidden in the move is detected | Requirement: Moved code is moved, not edited + Scenario: Adversarial — an edit hidden in the move is detected | mechanical comparison with a planted edit | `EntrypointContractSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The file | Scala source | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | 4,704 lines, 11 objects: `GateCmd` (34), `SpecLintCmd` (1676), `ChainStateCmd` (1948), `GraphCmd` (2494), `LedgerCmd` (2937), `CheckpointCmd` (3507), `ReconcileCmd` (3831), `DangerScanCmd` (3928), `MetalsCmd` (4047), `InstallSkillsCmd` (4147), `InstallHooksCmd` (4344) — line cites corrected at spec-3 R8; the +3 shift came from spec-2 defect fix `192961d`, landed after this spec was written |
| The gate remains large | observation | `GateCmd`, about 1,640 lines | One file per subcommand still leaves the gate at about 35% of the original. Splitting it per tier would make gate-related mutation scores sharper, but that is an edit, not a move; recorded here, not done |
| `MetalsCmd` | object | as above | Moved by this spec, then removed by `surface-honesty` |
| Shared helpers | private members | within `GateCmd` and others | Helpers used by more than one entrypoint move to a shared file; helpers used by one stay with it. **Resolution recorded at Step 1 (gate-approved):** the 8 cross-entrypoint members are all `private[cli]` and are called from inside other entrypoints' bodies — relocating them would force qualifier rewrites inside moved bodies, which "no line inside a moved body MAY change" forbids. They remain in their owning objects; no shared-helpers file is produced (nothing exists at file scope to share) |
| Ring 5 | — | `stryker4s.conf` | From this spec on, each spec retargets `mutate` to its own subcommand's file |
