# Spec: Unported Tool Register

Six tools remain on their predecessor implementations. That fact currently lives in one
paragraph of an archived proposal. A tool that is in neither the ported surface nor any
checked record is a tool nobody is tracking — which is how the traceability tool became a
dependency of the correctness verdict while being scoped out of the port.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol | The protocol's **State** tracks which tools have been ported. This spec makes the complement — which have not, and why — an equally explicit and machine-checked part of that state. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter the concept's purpose, actions, or synchronizations. It records
the migration state's unported half in a checked form; whether the concept's **State**
section should name the register is flagged for the human gate at the concept-delta check.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum | `org.sinemenda.probatio.cli` |
| `ToolId` (migration) | enum | `org.sinemenda.probatio.migration` |
| `MigrationState` | final case class | `org.sinemenda.probatio.migration` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `UnportedTool` | final case class (name, path, blocker, citedBy) | One tool remaining on its predecessor implementation, with what blocks its port and which workflow instructions still cite it. |
| `PortBlocker` | enum (`GatedOnSpike(name)`, `NotOnEnforcementPath`, `SupersededByPortedTool(subcommand)`) | Why a tool is not ported. A closed set, so "no stated reason" is not a representable blocker. |
| `ToolSurfaceClassification` | enum (`Ported(subcommand)`, `Registered(unported)`) | The total classification of an executable tool in the tree. The absence of a third variant is the mechanism: a tool cannot be in neither. |

### Type-Widening Impact

No existing public type gains a variant. All three new types are closed and every match
over them is written in this change.

## ADDED Requirements

### Requirement: Every tool in the tree is either on the ported surface or in the register

Each executable tool in the workflow's tool directories SHALL be classified as either
named on the ported tool surface or recorded in the register, and a tool MUST NOT be
absent from both.

**Given** the set of executable tools in the workflow's tool directories
**When** each is classified
**Then** each is either named on the ported surface or present in the register

**Rationale**: the traceability tool was scoped out of the port, was not on the ported
surface, and appeared in no checked record — so nothing noticed that the correctness
verdict depended on it. The register's value is not the list; it is that the classification
is total and checked, so the next tool cannot fall into the same gap.

#### Scenario: Happy path — every present tool classifies

**Given** the current tool directories
**When** each executable is classified
**Then** each classifies as ported or registered

#### Scenario: Adversarial — a tool in neither is reported

**Given** a tool directory containing an executable named on no ported surface and in no
register entry
**When** the classification runs
**Then** the check reports that tool as unclassified and terminates with the finding
status

#### Scenario: Adversarial — a third classification is unconstructible

**Given** a caller attempting to classify a tool as neither ported nor registered
**When** the code is compiled
**Then** compilation fails: the classification type has exactly two variants

#### Scenario: Error path — an unreadable tool directory is could-not-determine

**Given** a tool directory that cannot be read
**When** the classification runs
**Then** the outcome is could-not-determine naming that directory, and no tool is reported
as unclassified on the strength of an unread directory

### Requirement: Every register entry states what blocks its port

Each register entry SHALL name what blocks the tool's port, drawn from the closed set of
blockers, and an entry MUST NOT record a tool without a blocker.

**Given** a register entry
**When** it is read
**Then** it names the tool, its location, its blocker, and the workflow instructions that
still cite it

#### Scenario: Happy path — an entry names its blocker and citations

**Given** a register entry for a tool blocked on an unrun investigation
**When** the entry is read
**Then** it names that investigation as the blocker and lists the instructions citing the
tool

#### Scenario: Adversarial — an entry without a blocker is unconstructible

**Given** a caller building a register entry with no blocker
**When** the code is compiled
**Then** compilation fails: the entry type requires the blocker

#### Scenario: Adversarial — a blocker outside the closed set is unconstructible

**Given** a caller building a register entry with a free-text blocker
**When** the code is compiled
**Then** compilation fails: the blocker is a closed enumeration, not text

### Requirement: A registered tool's citations resolve

Each workflow instruction a register entry cites SHALL resolve to a document present in
the tree, and a citation MUST NOT name a document that does not exist.

**Given** a register entry's citation list
**When** each citation is resolved against the tree
**Then** each resolves to a present document

**Rationale**: the register's citations are what make the blast radius of a future port
visible. A citation that does not resolve overstates or understates that radius, and a
register nobody can trust is worse than none.

#### Scenario: Happy path — every citation resolves

**Given** the register's entries
**When** each citation is resolved
**Then** each resolves

#### Scenario: Adversarial — a citation naming an absent document is reported

**Given** a register entry citing a document not present in the tree
**When** the register is checked
**Then** the check names the entry and the unresolvable citation

### Requirement: A tool that becomes ported leaves the register

When a tool gains an implementation on the ported surface, its register entry SHALL be
removed, and a tool MUST NOT be both named on the ported surface and present in the
register.

**Given** a tool named on the ported surface
**When** the register is checked
**Then** that tool has no register entry

**Rationale**: a tool recorded as unported while being ported is the dual-implementation
condition the migration protocol already forbids for seams, applied to the record rather
than the seam. This change ports the traceability tool, so its removal from the register is
this requirement's first exercise.

#### Scenario: Happy path — the newly ported tool has no register entry

**Given** the traceability tool, ported by this change
**When** the register is checked
**Then** it has no register entry

#### Scenario: Adversarial — a tool both ported and registered is reported

**Given** a tool named on the ported surface and also present in the register
**When** the register is checked
**Then** the check names that tool as doubly classified and terminates with the finding
status

## Properties (Ring 3)

### Property: classification-is-total-and-exclusive

**Invariant**: for every tool set, each tool classifies into exactly one of ported or
registered — never both, never neither.

**Generator strategy**: `genToolSet` — constructive over sets of 0–12 tools, each
independently assigned a state drawn from {on the ported surface only, in the register
only, in both, in neither}, so the two failure conditions arise by construction rather
than by filtering. Edge cases: the empty set, all ported, all registered, one in both, one
in neither.

```
property("classification is total and exclusive") {
  for {
    tools <- genToolSet.forAll
    result = classifyAll(tools)
  } yield Result.assert(
    result.unclassified.isEmpty == tools.forall(t => t.onSurface || t.registered) &&
    result.doublyClassified.isEmpty == tools.forall(t => !(t.onSurface && t.registered))
  )
}
```

### Property: every-entry-carries-a-blocker

**Invariant**: for every register, each entry names a blocker from the closed set — there
is no entry whose blocker is absent or outside the set.

**Generator strategy**: `genRegister` — constructive over entry lists of size 0–8, each
entry's blocker drawn from the closed blocker enumeration. The property is a totality
check over the generated domain; the type makes the negative case unconstructible, so the
property confirms the type's guarantee holds through serialisation as well.

```
property("every entry carries a blocker from the closed set") {
  for {
    register <- genRegister.forAll
    back      = readRegister(writeRegister(register))
  } yield Result.assert(back.entries.forall(e => knownBlockers.contains(e.blocker)))
}
```

### Property: citations-resolve-for-the-real-register

**Invariant**: for the register as committed, every citation resolves to a present
document.

**Generator strategy**: enumerated, not sampled — the domain is the committed register's
entries, discovered at test time rather than listed, so a newly added entry is covered
automatically. This finite-domain limit is stated rather than presented as sampled
coverage.

```
property("every committed citation resolves") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    committedRegister.entries.forall(_.citedBy.forall(documentExists))
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A third tool classification | A tool in neither the ported surface nor the register is the gap this spec closes; a third variant would reopen it | `assertDoesNotCompile("ToolSurfaceClassification.Unknown")` — the enum has exactly two cases |
| A register entry without a blocker | An entry with no stated reason is indistinguishable from an oversight | `assertDoesNotCompile("UnportedTool(name, path)")` — the blocker and citation list are required |
| A free-text blocker | A free-text reason cannot be checked, and an unchecked reason drifts | `assertDoesNotCompile("UnportedTool(name, path, \"because\", Nil)")` — the blocker is a closed enumeration |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: a two-variant total classification with no fold,
recursion or arithmetic invariant to mirror.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Every present tool classifies | Requirement: Every tool in the tree is either on the ported surface or in the register + Scenario: Happy path — every present tool classifies | scenario test | `MigrationProtocolSpec` |
| A tool in neither is reported | Requirement: Every tool in the tree is either on the ported surface or in the register + Scenario: Adversarial — a tool in neither is reported | scenario test | `MigrationProtocolSpec` |
| Classification is total and exclusive | Property: classification-is-total-and-exclusive | Hedgehog property | `MigrationProtocolSpec` |
| A third classification is unconstructible | Requirement: Every tool in the tree is either on the ported surface or in the register + Scenario: Adversarial — a third classification is unconstructible + Compile-Negative: A third tool classification | compile-negative test | `SubcommandTypeContract` |
| An unreadable tool directory is could-not-determine | Requirement: Every tool in the tree is either on the ported surface or in the register + Scenario: Error path — an unreadable tool directory is could-not-determine | scenario test | `MigrationProtocolSpec` |
| An entry names its blocker and citations | Requirement: Every register entry states what blocks its port + Scenario: Happy path — an entry names its blocker and citations | scenario test | `MigrationProtocolSpec` |
| An entry without a blocker is unconstructible | Requirement: Every register entry states what blocks its port + Scenario: Adversarial — an entry without a blocker is unconstructible + Compile-Negative: A register entry without a blocker | compile-negative test | `SubcommandTypeContract` |
| A free-text blocker is unconstructible | Requirement: Every register entry states what blocks its port + Scenario: Adversarial — a blocker outside the closed set is unconstructible + Compile-Negative: A free-text blocker | compile-negative test | `SubcommandTypeContract` |
| Every entry carries a blocker through serialisation | Property: every-entry-carries-a-blocker | Hedgehog property | `MigrationProtocolSpec` |
| Every citation resolves | Requirement: A registered tool's citations resolve + Scenario: Happy path — every citation resolves + Property: citations-resolve-for-the-real-register | Hedgehog property (enumerated, discovered at test time) | `MigrationProtocolSpec` |
| An unresolvable citation is reported | Requirement: A registered tool's citations resolve + Scenario: Adversarial — a citation naming an absent document is reported | scenario test | `MigrationProtocolSpec` |
| The newly ported tool has no register entry | Requirement: A tool that becomes ported leaves the register + Scenario: Happy path — the newly ported tool has no register entry | scenario test | `MigrationProtocolSpec` |
| A doubly classified tool is reported | Requirement: A tool that becomes ported leaves the register + Scenario: Adversarial — a tool both ported and registered is reported | scenario test | `MigrationProtocolSpec` |
| The register is complete for the current tree | Criterion: this spec's exit criterion | scenario test run against the real tool directories, recorded in the evidence ledger | `MigrationProtocolSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `UnportedTool`, `PortBlocker`, `ToolSurfaceClassification` | new types | `workflow/core/src/main/scala/org/sinemenda/probatio/core/` | New; the classification is pure, the directory reading is in the adapter |
| `Subcommand` | enum | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/Subcommand.scala:25` | The ported half of the classification; its scaladoc currently lists the unported tools in prose and is replaced by a reference to the register |
| The register document | markdown | `openspec/schemas/verified-scala3/` | The committed register the check reads |
| The six registered tools | shell / scala-cli scripts | `openspec/schemas/verified-scala3/scanner/` | The concept-registry verifier, the concept scanner and its wrapper, the two code-intelligence recipes, and the two unported operations of the code-intelligence client. Each gated on a stated blocker; two are gated on an investigation that has not been run. |
| The traceability tool | python script | `openspec/schemas/verified-scala3/scanner/openspec-graph.py` | **Leaves** the unported set in this change — ported by `specs/graph-tool-port/spec.md`, which is this spec's first exercise of the leaves-the-register requirement |
| `MigrationState` | final case class | `workflow/cli/src/test/scala/org/sinemenda/probatio/migration/MigrationTypes.scala` | Records which tools are ported; the register is its complement |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
