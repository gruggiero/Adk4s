# Spec: Schema Rename Completion

The schema declares its new name and its new version, and the changelog describes a rename
that was carried out. On disk most of it was not: the installed instruction documents still
carry the previous name's stamp at the previous version, the tutorial and the shipped
continuous-integration templates still use the old name throughout, and the one piece of
the rename that has verified logic — the cache migration — has no caller.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol | The protocol's **Skill-doc update** action requires instruction documents to land with the swap they describe. This spec discharges that action for the rename. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SchemaPolicy` | object (`resolveHookEnv`, `migrateCache`, `renameVersion`) | `org.sinemenda.probatio.core` |
| `CacheState` | final case class | `org.sinemenda.probatio.core` |
| `DriftScan` | object | `org.sinemenda.probatio.core` |
| `DriftWarning` | sealed trait (including the pre-rename-stamp variant) | `org.sinemenda.probatio.core` |
| `InstallRootScan` | final case class | `org.sinemenda.probatio.core` |
| `InstallRoots` | final case class (six roots) | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RenameDeferral` | final case class (item, reason, blockedBy) | One rename item deliberately not carried out, with the coupling that blocks it. Recorded so the next change inherits a decision rather than rediscovering a problem. |

### Type-Widening Impact

No public type gains a variant. `CacheState` and `DriftWarning` are used as they stand.

## ADDED Requirements

### Requirement: Installed instruction documents carry the current stamp

Every instruction document installed into a searched root SHALL carry the schema's current
name and version, and a document MUST NOT carry a pre-rename stamp after this change.

**Given** the searched install roots
**When** each installed instruction document's stamp is read
**Then** each names the current schema and the current version

**Rationale**: the drift detector reports these stamps correctly at every session start —
the detector works and the documents were never regenerated. The stamp is what the detector
compares against, so a stale stamp means every session opens with a warning that nothing
acts on.

#### Scenario: Happy path — every installed document carries the current stamp

**Given** the searched install roots after the installer has run
**When** each stamp is read
**Then** each names the current schema and version

#### Scenario: Adversarial — a pre-rename stamp is still reported as migration, not drift

**Given** a root carrying a document with a pre-rename stamp
**When** the drift scan runs
**Then** it reports a migration message naming that root, and does not report it as a
version mismatch

#### Scenario: Error path — an unreadable root is could-not-determine, not absent

**Given** a searched root that exists but cannot be read
**When** the drift scan runs
**Then** it reports that root as unreadable, and does not report it as carrying no
document

### Requirement: The cache and state directory migration runs on first use

The cache and state directory migration SHALL be invoked by the shipped tool, and its
verified logic MUST NOT remain without a caller.

**Given** a first invocation in an environment carrying the previous directory
**When** the tool runs
**Then** the previous directory's contents are moved to the current one

**Given** a subsequent invocation
**When** the tool runs
**Then** no migration is performed and the current directory is unchanged

**Rationale**: the migration function is implemented, unit-tested and formally reachable,
and nothing calls it. A verified function with no caller discharges nothing — it is the
same shape of gap as a specification with no implementation, in the opposite direction.

#### Scenario: Happy path — a previous directory is migrated once

**Given** an environment carrying the previous directory and no current one
**When** the tool runs
**Then** the contents appear under the current directory

#### Scenario: Happy path — a second run performs no migration

**Given** the environment after that migration
**When** the tool runs again
**Then** nothing is moved

#### Scenario: Edge case — a fresh environment with neither directory migrates nothing

**Given** an environment carrying neither directory
**When** the tool runs
**Then** no migration is performed and no error is reported

#### Scenario: Adversarial — a previous directory is not migrated over an existing current one

**Given** an environment carrying both the previous and the current directory
**When** the tool runs
**Then** the current directory's contents are unchanged and nothing is overwritten

### Requirement: The shipped documents and templates use the current name

The tutorial documents and the shipped continuous-integration templates SHALL use the
schema's current name, and they MUST NOT present the previous name as current.

**Given** the shipped tutorial documents and continuous-integration templates
**When** their content is read
**Then** the current name is used where the schema is named as itself

**Given** a passage describing the rename or the pre-rename identity
**When** its content is read
**Then** the previous name may appear, marked as the former identity

#### Scenario: Happy path — the tutorial names the current schema

**Given** the tutorial's entry document
**When** its title and body are read
**Then** they name the current schema

#### Scenario: Adversarial — no template presents the previous name as current

**Given** each shipped continuous-integration template
**When** its content is read
**Then** no occurrence of the previous name is presented as the schema's current name

#### Scenario: Edge case — the changelog retains the previous name as history

**Given** the changelog's entry describing the rename
**When** its content is read
**Then** the previous name appears, marked as the pre-rename identity

### Requirement: The acceptance suite asserts the schema's actual version

The acceptance suite SHALL assert the version the schema declares, and it MUST NOT assert a
version the schema no longer carries.

**Given** the acceptance suite's schema-version assertion
**When** it runs against the schema
**Then** it asserts the declared version and passes

#### Scenario: Happy path — the assertion matches the declared version

**Given** the schema at its current version
**When** the assertion runs
**Then** it passes

#### Scenario: Adversarial — the assertion fails against a different version

**Given** a schema declaring a version other than the asserted one
**When** the assertion runs
**Then** it fails naming both versions

### Requirement: The directory rename is deferred with its coupling recorded

The schema's directory SHALL NOT be renamed by this change, and the deferral MUST be
recorded with the coupling that blocks it.

**Given** this change
**When** the schema's directory is inspected
**Then** it retains its current name

**Given** the recorded deferral
**When** it is read
**Then** it names the coupling: the workflow tool resolves a schema by its directory name,
the project configuration pins that name, and every archived change records it — so
renaming the directory without a resolution alias breaks schema resolution for all of them

**Rationale**: this is not an oversight being deferred but a dependency being respected.
Recording the coupling is what turns the deferral into an inheritable decision rather than a
problem the next change rediscovers.

#### Scenario: Happy path — the deferral names the coupling and the blocked items

**Given** the recorded deferral
**When** it is read
**Then** it names the directory rename, the resolution coupling, and the count of recorded
changes that pin the current name

#### Scenario: Adversarial — a deferral without a recorded reason is not accepted

**Given** a deferral entry carrying no reason
**When** the deferral record is checked
**Then** the check reports that entry as incomplete

## Properties (Ring 3)

### Property: every-searched-root-is-classified

**Invariant**: for every install root, the drift scan classifies it into exactly one of
absent, present-without-stamp, unreadable, or stamped — the classification is total, and
an unreadable root is never reported as absent.

**Generator strategy**: `genInstallRootState` — constructive over the four states crossed
with the six searched roots, built directly rather than filtered. Edge cases: all roots
absent, all roots stamped at the current version, one root unreadable, a root stamped at
each of the recorded historical versions.

```
property("every searched root is classified exactly once") {
  for {
    states <- genInstallRootStates.forAll
    result  = scan(states)
  } yield Result.assert(
    result.classifications.length == states.length &&
    states.zip(result.classifications).forall((s, c) => c.matches(s) && !(s.isUnreadable && c.isAbsent))
  )
}
```

### Property: migration-is-idempotent

**Invariant**: for every directory state, applying the migration twice yields the same
result as applying it once, and no state loses content.

**Generator strategy**: `genCacheState` — constructive over the four presence combinations
(neither, previous only, current only, both) crossed with content lists of size 0–4. No
filtering. Edge cases: both present with differing content, previous present but empty.

```
property("migration is idempotent and lossless") {
  for {
    state <- genCacheState.forAll
    once   = migrate(state)
    twice  = migrate(migrate(state))
  } yield Result.assert(once == twice && once.contents.containsAll(state.currentContents))
}
```

### Property: no-shipped-document-presents-the-previous-name-as-current

**Invariant**: for every shipped tutorial document and template, no occurrence of the
previous name is presented as the schema's current name.

**Generator strategy**: enumerated, not sampled — the domain is the finite set of shipped
documents and templates, discovered at test time rather than listed, so a newly added
document is covered automatically. Occurrences inside passages marked as historical are
excluded by the marking, not by a name-based exception. This finite-domain limit is stated
rather than presented as sampled coverage.

```
property("no shipped document presents the previous name as current") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    shippedDocuments.forall(d => currentNameOccurrences(d).forall(!_.isPreviousName))
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A rename deferral without a reason | A deferral with no recorded reason is indistinguishable from an omission | `assertDoesNotCompile("RenameDeferral(item)")` — the type requires the reason and the blocking coupling |
| A drift scan result treating an unreadable root as absent | Reporting an unreadable root as carrying no document turns a read failure into a verdict | `assertDoesNotCompile("InstallRootState.Unreadable == InstallRootState.Absent")` — the states are distinct variants |

## Formal Contracts (Ring 6)

The cache migration is already formally specified and verified; this spec adds no new
decision to the mirror. Its contribution is a caller, which is an adapter concern and not
mirrorable. The drift classification is a total function over a four-variant state and is
covered by the totality property above.

This is a stated scope note, not a Ring 6 skip for the change: the change's Ring 6
obligations are discharged by the other specs' kernels.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Every installed document carries the current stamp | Requirement: Installed instruction documents carry the current stamp + Scenario: Happy path — every installed document carries the current stamp | scenario test + bats oracle | `DriftScanSpec`, `harness-install-verification.bats` |
| A pre-rename stamp is reported as migration, not drift | Requirement: Installed instruction documents carry the current stamp + Scenario: Adversarial — a pre-rename stamp is still reported as migration, not drift | scenario test | `DriftScanSpec` |
| An unreadable root is not reported as absent | Requirement: Installed instruction documents carry the current stamp + Scenario: Error path — an unreadable root is could-not-determine, not absent + Property: every-searched-root-is-classified | Hedgehog property | `DriftScanSpec` |
| An unreadable root cannot be equated with an absent one | Compile-Negative: A drift scan result treating an unreadable root as absent | compile-negative test | `LiveFactBannerTypeContract` |
| A previous directory is migrated once | Requirement: The cache and state directory migration runs on first use + Scenario: Happy path — a previous directory is migrated once | scenario test | `SchemaPolicySpec` |
| A second run performs no migration | Requirement: The cache and state directory migration runs on first use + Scenario: Happy path — a second run performs no migration | scenario test | `SchemaPolicySpec` |
| A fresh environment migrates nothing | Requirement: The cache and state directory migration runs on first use + Scenario: Edge case — a fresh environment with neither directory migrates nothing | scenario test | `SchemaPolicySpec` |
| An existing current directory is not overwritten | Requirement: The cache and state directory migration runs on first use + Scenario: Adversarial — a previous directory is not migrated over an existing current one | scenario test | `SchemaPolicySpec` |
| Migration is idempotent and lossless | Property: migration-is-idempotent | Hedgehog property | `SchemaPolicySpec` |
| The migration has a caller in the shipped tool | Requirement: The cache and state directory migration runs on first use | scenario test invoking the built artifact as a subprocess | `SubprocessConformanceSpec` |
| The tutorial names the current schema | Requirement: The shipped documents and templates use the current name + Scenario: Happy path — the tutorial names the current schema | bats oracle | `workflow-hygiene.bats` |
| No template presents the previous name as current | Requirement: The shipped documents and templates use the current name + Scenario: Adversarial — no template presents the previous name as current + Property: no-shipped-document-presents-the-previous-name-as-current | Hedgehog property (enumerated, discovered at test time) | `PluginSourceLintSpec` |
| The changelog retains the previous name as history | Requirement: The shipped documents and templates use the current name + Scenario: Edge case — the changelog retains the previous name as history | bats oracle | `workflow-hygiene.bats` |
| The version assertion matches the declared version | Requirement: The acceptance suite asserts the schema's actual version + Scenario: Happy path — the assertion matches the declared version | bats oracle | `correctness-invariant.bats` |
| The version assertion fails against a different version | Requirement: The acceptance suite asserts the schema's actual version + Scenario: Adversarial — the assertion fails against a different version | bats oracle (negative fixture) | `correctness-invariant.bats` |
| The deferral names the coupling and blocked items | Requirement: The directory rename is deferred with its coupling recorded + Scenario: Happy path — the deferral names the coupling and the blocked items | scenario test | `MigrationProtocolSpec` |
| A deferral without a reason is reported incomplete | Requirement: The directory rename is deferred with its coupling recorded + Scenario: Adversarial — a deferral without a recorded reason is not accepted | scenario test | `MigrationProtocolSpec` |
| A deferral without a reason is unconstructible | Compile-Negative: A rename deferral without a reason | compile-negative test | `LiveFactBannerTypeContract` |
| The suite files reach control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `correctness-invariant.bats`, `harness-install-verification.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `SchemaPolicy.migrateCache`, `CacheState` | object method, case class | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SchemaPolicy.scala` | Shipped, unit-tested, **no production caller** — verified by this change's inventory-check. This spec supplies the caller. |
| `SchemaPolicy.resolveHookEnv` | object method | same file | Already wired through the context type; unchanged |
| `DriftScan`, `InstallRoots` | object, case class | `.../core/DriftScan.scala:138` | Six searched roots; unchanged by this spec |
| `RenameDeferral` | new case class | `.../core/` | New |
| The installed instruction documents | markdown | the six searched roots | Regenerated by the installer, which reaches surface parity in `specs/install-tool-surface-parity/spec.md` — this spec depends on that one |
| `schema.yaml`, `CHANGELOG.md` | schema metadata | `openspec/schemas/verified-scala3/` | Already carry the current name and version; the changelog's rename entry is the historical record the edge case preserves |
| The tutorial documents | HTML | `openspec/schemas/verified-scala3/docs/` | Fourteen documents; none currently uses the current name |
| The continuous-integration templates | YAML | `openspec/schemas/verified-scala3/ci/` | Three templates; all name the previous identity |
| `correctness-invariant.bats` | bats suite | `openspec/schemas/verified-scala3/tests/` | Its schema-version assertion is one of the failures present in both arms today |
| The deferred directory rename | — | `openspec/schemas/verified-scala3/` | **Not renamed by this change.** The coupling: the workflow tool resolves a schema by directory name, `openspec/config.yaml` pins it, and eighteen archived changes plus this one record it in their own metadata. |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
