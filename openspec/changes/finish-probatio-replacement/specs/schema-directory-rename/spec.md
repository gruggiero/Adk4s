# Spec: Schema Directory Rename

The schema declares its name as probatio, and its directory still carries the previous name.
The rename was deferred by the previous change with a recorded coupling: the workflow tool
resolves a schema by directory name, and 19 recorded changes pin the previous name. This spec
performs the rename. It also corrects that coupling on the evidence of a test run while
drafting it.

### What was measured on 2026-09-25 (openspec CLI 1.3.1, scratch worktree)

| Experiment | Result |
|---|---|
| Rename the directory, no alias | An **active** change pinning the previous name fails: *"Schema 'verified-scala3' not found"* |
| Rename, plus a symbolic link from the previous name to the new directory | The active change resolves again, and its artifact progress reads correctly |
| Set the project configuration to the new name | New changes are created pinned to the new name |
| Resolve an **archived** change | The CLI does not operate on archived changes at all — it rejects their names |
| Other readers of recorded schema pins | One test, which counts them; nothing resolves them |

So the recorded coupling was overstated. Renaming does not break the 19 archived changes,
because nothing resolves their pins. It breaks every **active** change, and it would silently
break any tool that later chooses to resolve archived pins. The alias is still needed; the
reason is narrower than recorded.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The protocol's **Skill-doc update** action requires instructions to land with the change they describe. Every instruction naming the schema directory moves in the same commit as the directory. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `RenameDeferral` | final case class (`recorded`) | `org.sinemenda.probatio.core` |
| `SchemaPolicy` | object | `org.sinemenda.probatio.core` |
| `DriftScan` | object | `org.sinemenda.probatio.core` |
| `RepositoryFacts` | final case class | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `SchemaAlias` | final case class (previous, current) | The recorded fact that the previous schema directory name resolves to the current one, and the mechanism that makes it resolve. |

`RenameDeferral.recorded` is **discharged**: its one entry — the directory rename — is removed
when the rename lands.

### Type-Widening Impact

None. No enumeration changes.

## ADDED Requirements

### MUST-CONFIRM — how the workflow tool resolves an aliased schema

How the openspec CLI resolves a schema is **its behaviour, not this repository's**. The
2026-09-25 result above was measured on version 1.3.1 on Linux. It MUST be re-established on
the CLI version in use at apply time, before the directory moves, and it is not inherited
from this spec. **Authoritative source**: the CLI's own behaviour, observed by test.
**Known limit**: a symbolic link is not created on a checkout whose version control has
symbolic links disabled — the default for Windows checkouts.

### Requirement: The schema directory takes its probatio name

The schema directory SHALL be named probatio, and the project configuration MUST name the
probatio schema, so that new changes are created under it.

**Given** the repository after this change
**When** the schema directories are listed and a new change is created
**Then** the schema directory is named probatio, and the new change pins probatio

#### Scenario: Happy path — a new change pins the probatio schema

**Given** the project configuration after the rename
**When** a new change is created
**Then** its recorded schema is probatio

#### Scenario: Adversarial — the configuration does not name the previous schema

**Given** the project configuration after the rename
**When** it is read
**Then** its schema is not the previous name

### Requirement: The previous name stays resolvable

The previous schema name SHALL continue to resolve to the renamed directory, and an active
change that pins the previous name MUST NOT fail schema resolution.

**Given** an active change whose recorded schema is the previous name
**When** the workflow tool resolves its schema
**Then** it resolves to the renamed directory

#### Scenario: Happy path — an active change pinning the previous name resolves

**Given** an active change pinning the previous name, after the rename
**When** its status is requested
**Then** it reports its artifacts against the renamed schema

#### Scenario: Adversarial — removing the alias is detected

**Given** the rename without the alias
**When** the alias check runs
**Then** it reports that an active change pinning the previous name does not resolve

#### Scenario: Edge case — a checkout without symbolic links is reported, not silently broken

**Given** a checkout whose version control has symbolic links disabled
**When** the alias check runs
**Then** it reports that the alias is not a link, rather than passing

### Requirement: No live reference names the previous directory

Every tracked reference to the previous schema directory path SHALL be rewritten to the new
path, and no reference outside the historical record MAY name the previous path.

**Given** the tracked files outside the archive
**When** they are searched for the previous directory path
**Then** none is found, except the alias itself and records of the rename's history

**Rationale**: on 2026-09-25, 105 tracked files outside the archive named the previous path —
the schema directory itself, the command-line and core modules' sources and tests, the live
specifications, the CI workflow, the harness adapters, and the docs.

#### Scenario: Happy path — the search finds no live reference

**Given** the tree after the rename
**When** it is searched for the previous path
**Then** only the alias and historical records match

#### Scenario: Adversarial — a live reference left behind is reported

**Given** one live file still naming the previous path
**When** the search check runs
**Then** it names that file and line

### Requirement: The recorded deferral is discharged and its record corrected

The directory-rename deferral SHALL be removed from the recorded deferrals when the rename
lands, and the correction to its recorded coupling MUST be preserved in the change's
history.

**Given** the recorded deferrals after this change
**When** they are read
**Then** the directory rename is not among them, and this change's record states why its
recorded coupling was narrower than first stated

#### Scenario: Happy path — the deferral list no longer holds the rename

**Given** the recorded deferrals after the rename
**When** they are read
**Then** the directory rename is absent

#### Scenario: Adversarial — a deferral cannot be removed while its item is undone

**Given** the directory not yet renamed
**When** the deferral check runs
**Then** it reports the deferral as still owed

## Properties (Ring 3)

### Property: every-active-change-resolves

**Invariant**: after the rename, every active change resolves its schema, whichever of the two
names it pins.

**Generator strategy**: enumerated over the real active changes at apply time, plus two
synthetic active changes created in a scratch worktree — one pinning each name. The domain is
finite; the limit is stated.

```
property("every active change resolves") {
  for {
    c <- Gen.element(activeChanges.head, activeChanges.tail).forAll
  } yield Result.assert(resolveSchema(c).isRight)
}
```

### Property: no-live-reference-to-the-previous-path

**Invariant**: no tracked file outside the archive, the alias and the rename's historical
records contains the previous directory path.

**Generator strategy**: enumerated — the tracked-file list is discovered at test time, so a new
file is covered automatically. The finite limit is stated.

```
property("no live reference to the previous path") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(liveTrackedFiles.forall(f => !mentionsPreviousPath(f)))
}
```

## Compile-Negative Obligations

None — stated. The guarantees here are filesystem and resolution facts, enforced by the
properties and the scenario checks.

## Formal Contracts (Ring 6)

No formal contracts — stated skip: a directory move and a reference rewrite, with no decision
or law at their centre.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Alias resolution is re-established on the CLI in use | MUST-CONFIRM: how the workflow tool resolves an aliased schema | scratch-worktree experiment repeated at apply time, recorded with the CLI version | `MigrationProtocolSpec` |
| A new change pins the probatio schema | Requirement: The schema directory takes its probatio name + Scenario: Happy path — a new change pins the probatio schema | scenario test in a scratch worktree | `MigrationProtocolSpec` |
| The configuration does not name the previous schema | Requirement: The schema directory takes its probatio name + Scenario: Adversarial — the configuration does not name the previous schema | scenario test | `MigrationProtocolSpec` |
| An active change pinning the previous name resolves | Requirement: The previous name stays resolvable + Scenario: Happy path — an active change pinning the previous name resolves + Property: every-active-change-resolves | property over the enumerated active changes | `MigrationProtocolSpec` |
| Removing the alias is detected | Requirement: The previous name stays resolvable + Scenario: Adversarial — removing the alias is detected | scenario test | `MigrationProtocolSpec` |
| A checkout without links is reported | Requirement: The previous name stays resolvable + Scenario: Edge case — a checkout without symbolic links is reported, not silently broken | scenario test with symbolic links disabled | `MigrationProtocolSpec` |
| The search finds no live reference | Requirement: No live reference names the previous directory + Scenario: Happy path — the search finds no live reference + Property: no-live-reference-to-the-previous-path | property over tracked files (enumerated) | `workflow-hygiene.bats` |
| A left-behind reference is reported | Requirement: No live reference names the previous directory + Scenario: Adversarial — a live reference left behind is reported | scenario test with a planted reference | `workflow-hygiene.bats` |
| The deferral list no longer holds the rename | Requirement: The recorded deferral is discharged and its record corrected + Scenario: Happy path — the deferral list no longer holds the rename | scenario test | `SchemaRenameCompletionTypeContract` |
| An undone deferral cannot be removed | Requirement: The recorded deferral is discharged and its record corrected + Scenario: Adversarial — a deferral cannot be removed while its item is undone | scenario test | `SchemaRenameCompletionTypeContract` |
| The acceptance suite passes after the move | Criterion: this spec's exit criterion | bats oracle at the new path against the predecessor control | `hook-tiers.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The directory | schema | `openspec/schemas/verified-scala3/` → `openspec/schemas/probatio/` | Moved with history preserved |
| The alias | symbolic link | `openspec/schemas/verified-scala3` → `probatio` | Resolves the previous name; confirmed on openspec 1.3.1, Linux |
| Configuration | project config | `openspec/config.yaml:8` (`schema: verified-scala3`) | Set to `probatio` |
| Active changes | change metadata | `openspec/changes/<active>/.openspec.yaml` | This change itself pins `verified-scala3` and resolves through the alias |
| Archived pins | change metadata | 19 archived `.openspec.yaml` files | Not resolved by the CLI; counted by one test. Left unedited — they are history |
| The deferral | core | `workflow/core/src/main/scala/org/sinemenda/probatio/core/RenameDeferral.scala:84–95` | `recordedChangesPinning = 19`; its entry is removed |
| The pin-counting test | munit suite | `workflow/cli/src/test/scala/org/sinemenda/probatio/migration/MigrationProtocolSpec.scala` | The only reader of `.openspec.yaml` outside the CLI |
| References to rewrite | tracked files | 105 outside the archive on 2026-09-25 | Including the forwarding scripts' and launcher's path assumptions, the CI workflow, the adapters, the oracle's schema-directory helper, and the banner's schema line |
| Ordering | — | last spec of the change | It touches files every other spec edits |
| Ring 5 note | — | `stryker4s.conf` | No production logic changes beyond path constants; stated skip for new mutants |
