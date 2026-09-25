# Spec: Legacy Name Retirement

The rename to probatio left the workflow's run-time names half-changed. Two environment
variables have probatio names; four do not, and the gate's own refusal message tells users
to set one of the four. The gate's per-repository state directory and the pi harness adapter
still carry the previous name. The previous rename's alias window closes at schema v16, and a
user who has only the legacy names documented will lose them without warning.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The state-directory rename edits oracle files that pin the directory name. Those edits go through the protocol's sanctioned-modification rule from `oracle-independence`. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SchemaPolicy` | object (`resolveHookEnv`, `aliasMajorWindow`, `renameVersion`) | `org.sinemenda.probatio.core` |
| `CacheMigration` | object | `org.sinemenda.probatio.cli` |
| `GateStateDir` | final case class | `org.sinemenda.probatio.cli` |
| `SessionId` | opaque type | `org.sinemenda.probatio.core` |
| `InstallTarget` | enum | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `LegacyAlias` | final case class (legacy, current, closesAt) | One legacy environment-variable name, its probatio name, and the schema version at which the alias stops being read. The alias table is the only place the gate looks up a variable. |

`GateStateDir` is **modified**: it resolves to the probatio-named directory, migrating a
legacy directory on first use.

### Type-Widening Impact

No enumeration gains a variant. The gate's direct environment reads — four legacy-only names
today — are replaced by lookups through the alias table. Each is enumerated in
Implementation Anchors.

## ADDED Requirements

### Requirement: Every environment variable the workflow reads has a probatio name

Each environment variable the workflow reads SHALL have a probatio name, and the legacy name
MUST be honoured as an alias until the recorded window closes — with the probatio name
winning when both are set.

**Given** any environment variable the gate or a scanner reads
**When** it is looked up
**Then** the probatio name is consulted first, the legacy name second, and the legacy name
only while the schema version is inside the alias window

#### Scenario: Happy path — the probatio name is read

**Given** the probatio session variable set
**When** the gate resolves its session
**Then** it uses that value

#### Scenario: Happy path — the legacy name still works inside the window

**Given** only the legacy session variable set, at schema v14
**When** the gate resolves its session
**Then** it uses that value and emits one deprecation notice naming the probatio name

#### Scenario: Adversarial — the probatio name wins over the legacy name

**Given** both names set to different values
**When** the gate resolves the variable
**Then** it uses the probatio value

#### Scenario: Adversarial — the legacy name is not read after the window closes

**Given** only the legacy name set, at a schema version past the window
**When** the gate resolves the variable
**Then** it treats the variable as unset

### Requirement: User-facing messages name only probatio variables

Every message the workflow shows a user SHALL name the probatio variable, and no message MAY
instruct a user to set a legacy name.

**Given** the gate's refusal, deprecation and help messages
**When** they are produced
**Then** each names the probatio variable

**Rationale**: on 2026-09-25 the pre-execution tier's predecessor-check refusal told users to
set the legacy variable. A user following it lands on the alias that is scheduled to stop
working.

#### Scenario: Happy path — the predecessor-check refusal names the probatio variable

**Given** a refusal from the predecessor check
**When** it is shown
**Then** its override instruction names the probatio variable

#### Scenario: Adversarial — no message names a legacy variable as something to set

**Given** every user-facing message the tools produce
**When** they are searched for an instruction to set a legacy name
**Then** none is found

### Requirement: The gate state directory takes its probatio name and carries existing state

The gate's per-repository state directory SHALL take its probatio name, and existing state in
the legacy directory MUST be migrated on first use — idempotently, losslessly, and never over
an existing probatio directory.

**Given** a repository whose state is in the legacy directory
**When** the gate first runs
**Then** the state is moved to the probatio directory and the gate reads it from there

#### Scenario: Happy path — legacy state is migrated once

**Given** a repository with state only in the legacy directory
**When** the gate runs
**Then** the state is in the probatio directory and the gate's decisions use it

#### Scenario: Happy path — a second run migrates nothing

**Given** the repository after that migration
**When** the gate runs again
**Then** nothing is moved

#### Scenario: Adversarial — legacy state does not overwrite existing probatio state

**Given** a repository with state in both directories
**When** the gate runs
**Then** the probatio directory is unchanged, and the gate reports that the legacy directory
was left in place

#### Scenario: Edge case — a repository without the workflow gets no state directory

**Given** a repository that does not carry the workflow
**When** the gate runs
**Then** neither directory is created

### Requirement: The acceptance suite follows the directory rename under sanction

The acceptance suite SHALL pass at predecessor parity after the rename. The files that pin the
legacy state-directory name — workflow-hygiene.bats, oracle-ordering-lock.bats,
gate-payload.bats, human-grant-lock.bats, hook-tiers.bats, ambient-capture-wiring.bats and
harness-install-verification.bats — MUST be edited only where a test asserts the directory's
name, or writes legacy state after the probatio directory exists.

**Given** the seven suite files that pin the legacy directory name
**When** the rename lands
**Then** tests that only write fixture state before the gate runs pass unmodified, through the
first-use migration, and the tests that assert the name are updated under this requirement's
sanction

**Rationale**: measured 2026-09-25, only two tests in workflow-hygiene.bats assert the name
(three assertions). The other six files write fixture state into the directory, which the
first-use migration carries across.

#### Scenario: Happy path — a fixture-writing test passes unmodified

**Given** a lock test that writes its phase into the legacy directory before invoking the gate
**When** it runs after the rename
**Then** it passes, and the phase was read from the migrated directory

#### Scenario: Adversarial — an edit to a file this requirement does not name is unsanctioned

**Given** an edit to an acceptance file outside the seven named here
**When** the immutability guard runs
**Then** it reports the edit as unsanctioned

### Requirement: The pi adapter takes a probatio name

The pi adapter SHALL be shipped and installed under a probatio name, and the installer MUST NOT
delete a legacy-named adapter it finds — it reports it.

**Given** an install for the pi harness
**When** the installer runs with the write instruction
**Then** the probatio-named adapter is installed, and a legacy-named adapter already present
is reported rather than removed

#### Scenario: Happy path — the adapter installs under its probatio name

**Given** a project with no pi adapter
**When** the installer writes it
**Then** the adapter is installed under its probatio name

#### Scenario: Adversarial — a legacy adapter is reported, not deleted

**Given** a project carrying the legacy-named adapter
**When** the installer runs with the write instruction
**Then** the legacy file is untouched and the installer names it as superseded

## Properties (Ring 3)

### Property: alias-resolution-is-ordered-and-bounded

**Invariant**: for every variable in the alias table, every combination of the two names being
set, and every schema version, the resolved value is the probatio value if set; otherwise the
legacy value if set and the version is inside the window; otherwise unset.

**Generator strategy**: `genAliasCase` — constructive over the alias table's entries × {neither,
legacy only, probatio only, both with different values} × schema versions {inside the window,
the last version inside, the first version outside}. All cases by construction.

```
property("alias resolution is ordered and bounded") {
  for {
    c <- genAliasCase.forAll
  } yield Result.assert(resolve(c) == expected(c))
}
```

### Property: state-migration-is-idempotent-and-lossless

**Invariant**: for every pair of directory states, migrating twice equals migrating once, and no
file present before is absent after.

**Generator strategy**: `genStateDirPair` — constructive over {neither, legacy only, probatio
only, both} × file lists of size 0–4 drawn from the gate's state-file kinds (heartbeat, phase,
presentation marker, refusal marker, grant token). No filtering.

```
property("state migration is idempotent and lossless") {
  for {
    s <- genStateDirPair.forAll
  } yield Result.assert(migrate(migrate(s)) == migrate(s) && s.allFiles.subsetOf(migrate(s).allFiles))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A direct environment read of a controlled variable outside the alias table | A direct read is how four variables were left with no probatio name | static rule: a scalafix `DisableSyntax` regex forbidding the legacy prefix in `env.get` calls outside the alias table |
| An alias with no closing version | An alias that never closes is not a deprecation | `assertDoesNotCompile("LegacyAlias(\"VERIFIED_SCALA3_X\", \"PROBATIO_X\")")` — `closesAt` is required |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: alias resolution is an ordered lookup, and the migration law
is already verified for the cache directory; a bridge property reuses it.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The probatio name is read | Requirement: Every environment variable the workflow reads has a probatio name + Scenario: Happy path — the probatio name is read | scenario test | `SchemaPolicySpec` |
| The legacy name works inside the window, with one notice | Requirement: Every environment variable the workflow reads has a probatio name + Scenario: Happy path — the legacy name still works inside the window | scenario test | `SchemaPolicySpec` |
| The probatio name wins | Requirement: Every environment variable the workflow reads has a probatio name + Scenario: Adversarial — the probatio name wins over the legacy name | scenario test | `SchemaPolicySpec` |
| The legacy name is not read after the window | Requirement: Every environment variable the workflow reads has a probatio name + Scenario: Adversarial — the legacy name is not read after the window closes | scenario test | `SchemaPolicySpec` |
| Alias resolution is ordered and bounded | Property: alias-resolution-is-ordered-and-bounded | Hedgehog property | `SchemaPolicySpec` |
| No direct read bypasses the alias table | Compile-Negative: A direct environment read of a controlled variable outside the alias table | static rule (scalafix) | `.scalafix.conf` |
| An alias must have a closing version | Compile-Negative: An alias with no closing version | compile-negative test | `SchemaRenameCompletionTypeContract` |
| The refusal names the probatio variable | Requirement: User-facing messages name only probatio variables + Scenario: Happy path — the predecessor-check refusal names the probatio variable | scenario test | `GateEventSpec` |
| No message names a legacy variable to set | Requirement: User-facing messages name only probatio variables + Scenario: Adversarial — no message names a legacy variable as something to set | scenario test over every message template | `CliOutputSpec` |
| Legacy state is migrated once | Requirement: The gate state directory takes its probatio name and carries existing state + Scenario: Happy path — legacy state is migrated once | scenario test | `GateStateDirSpec` |
| A second run migrates nothing | Requirement: The gate state directory takes its probatio name and carries existing state + Scenario: Happy path — a second run migrates nothing | scenario test | `GateStateDirSpec` |
| Legacy state does not overwrite probatio state | Requirement: The gate state directory takes its probatio name and carries existing state + Scenario: Adversarial — legacy state does not overwrite existing probatio state | scenario test | `GateStateDirSpec` |
| A repository without the workflow gets no directory | Requirement: The gate state directory takes its probatio name and carries existing state + Scenario: Edge case — a repository without the workflow gets no state directory | bats oracle | `workflow-hygiene.bats` |
| Migration is idempotent and lossless | Property: state-migration-is-idempotent-and-lossless | Hedgehog property, bridged to the verified cache-migration law | `CacheMigrationSpec` |
| A fixture-writing test passes unmodified | Requirement: The acceptance suite follows the directory rename under sanction + Scenario: Happy path — a fixture-writing test passes unmodified | bats oracle | `oracle-ordering-lock.bats` |
| An edit outside the named files is unsanctioned | Requirement: The acceptance suite follows the directory rename under sanction + Scenario: Adversarial — an edit to a file this requirement does not name is unsanctioned | the immutability guard | `NonGoalsGuardSpec` |
| The adapter installs under its probatio name | Requirement: The pi adapter takes a probatio name + Scenario: Happy path — the adapter installs under its probatio name | scenario test | `InstallToolSurfaceParitySpec` |
| A legacy adapter is reported, not deleted | Requirement: The pi adapter takes a probatio name + Scenario: Adversarial — a legacy adapter is reported, not deleted | scenario test | `InstallToolSurfaceParitySpec` |
| The seven files pass at parity after the rename | Criterion: this spec's exit criterion | bats oracle against the predecessor control | `gate-payload.bats`, `hook-tiers.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| Legacy-only reads | gate adapter | the gate's entrypoint file after `entrypoint-split` (today `SubcommandEntrypoints.scala:398` session, `:888` allow-paths, `:1071` active spec, `:1133` skip-predecessor-check) | Four direct `env.get` calls on the legacy prefix |
| The refusal text | gate adapter | today `SubcommandEntrypoints.scala:1164` | *"Set VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1 …"* |
| Existing aliases | core | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SchemaPolicy.scala:150–160` | `PROBATIO_HOOKS` / `PROBATIO_HOOKS_TRACE` already aliased; the window is v14–v15 |
| State directory | adapter | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/GateStateDir.scala` | `<git-dir>/verified-scala3-gate` today |
| Migration pattern | adapter | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/CacheMigration.scala` | Reused for the state directory |
| pi adapter | TypeScript | `openspec/schemas/verified-scala3/hooks/adapters/pi/verified-scala3-gate.ts`; installed copy `.pi/extensions/verified-scala3-gate.ts` | Renamed in the schema; installed under the new name |
| Oracle files pinning the name | bats | the seven named in the requirement; only `workflow-hygiene.bats:138,154,155` assert the name | Edited under this spec's sanction |
| Hermetic interaction | test infrastructure | `hermetic-test-processes` | Its controlled-variable set includes both the legacy and the probatio names |
| Ring 5 note | — | `stryker4s.conf` | Retarget to the alias table and the state-directory migration |
