# Spec: Schema Policy

<!-- DELTA spec for the `schema-policy` capability of the port-scanner-to-probatio
     change. Covers R-V1…R-V3: the prerequisite table amendment (jq/python3/
     shellcheck/shfmt retired post-port; curl-equivalent and optionally GraalVM
     added; Java removed as runtime in the native-binary happy path but retained
     where JAR fallback is active), the v14 schema rename verified-scala3 →
     probatio (generatedBy stamp rename, env-var migration with one-major
     deprecated alias, cache-dir auto-migration, CHANGELOG citation), and the
     hooks/README.md "Still excluded" policy rewrite.

     ALTITUDE: requirements and scenarios use behavioral vocabulary. Code
     identifiers — schema.yaml, CHANGELOG, env var names, cache paths, stamp
     strings — live in Implementation Anchors and the Concepts Introduced
     table. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Schema-policy amendment only; no behavioral concept changes | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | R-ARCH1: the probatio tooling subprojects depend on zero adk4s code; this spec amends schema metadata and policy text, introducing no adk4s type reuse |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| schema rename (verified-scala3 → probatio) | schema metadata change | The schema's declared name changes from the legacy identifier to the probatio identifier at the v14 version bump; the former name is recorded in the changelog as the pre-rename identity |
| generatedBy stamp rename | skill-stamp format change | The provenance stamp embedded in installed skill copies changes from the legacy stamp format to the probatio stamp format; the drift detector reads the new stamp and treats the old stamp as a pre-rename artifact with a one-time migration message |
| env var migration (VERIFIED_SCALA3_HOOKS → PROBATIO_HOOKS) | environment variable rename | The hook control variable is renamed; the old name is read as a deprecated alias for one major version, emitting a warning on stderr when used |
| cache dir migration (~/.cache/verified-scala3/ → ~/.cache/probatio/) | state directory relocation | The cache and state directory moves to the probatio path; contents are auto-migrated from the old location to the new on first run |

## ADDED Requirements

### Requirement: Prerequisite table amended in one version increment

The schema's declared prerequisite table SHALL be amended in exactly one
schema version increment. Prerequisites retired post-port: the JSON
processor, the Python runtime, the shell linter, and the shell formatter —
their last consumer migrates to the ported tooling and they are no longer
required at runtime. Prerequisites added: a curl-equivalent HTTP capability
(binary download is performed by the coursier/Java HTTP stack) and,
optionally, the native-image toolkit, declared as "required only to build
from source; prebuilt binaries otherwise." Prerequisites retained: the shell
interpreter (for hook shims), the version-control tool, the shell test
framework (the oracle — migration is incremental), and the openspec CLI.
The Java runtime SHALL be removed as a stated runtime prerequisite in the
native-binary happy path and SHALL be retained where the JAR fallback is
active — the table SHALL state both rows explicitly rather than hiding the
fallback.

**Given** the schema's prerequisite table at the current version
**When** the version is bumped to v14
**Then** the table reflects exactly the amended set: retired prerequisites
absent, added prerequisites present, retained prerequisites unchanged, and
both Java rows (native-binary happy path: not required; JAR fallback:
required) stated explicitly

**Rationale**: The prerequisite table is declared and versioned, not sacred;
v12 already changed it once. The port removes the polyglot runtime
dependency (four runtimes → one native binary) and the table must reflect
that in the same version bump that renames the schema, so a consumer reading
the table at v14 sees a self-consistent picture: new name, new
prerequisites, no stale entries.

> **MUST-CONFIRM**: The exact prerequisite table contents after amendment
> are authoritative in `openspec/schemas/verified-scala3/hooks/README.md`
> (to be relocated to the probatio schema path at the rename). The
> behavioral rule above defines the amendment policy; the README's table is
> the single source of truth for the exact row set, column format, and
> wording. The implementation MUST reconcile the table against this policy
> and record any deviation as a finding.

#### Scenario: retired prerequisites absent from the amended table

**Given** the amended prerequisite table at v14
**When** the table is inspected
**Then** the JSON processor, the Python runtime, the shell linter, and the
shell formatter are not listed as runtime prerequisites

#### Scenario: added prerequisites present in the amended table

**Given** the amended prerequisite table at v14
**When** the table is inspected
**Then** a curl-equivalent HTTP capability is listed (binary download via
coursier/Java HTTP), and the native-image toolkit is listed as optional
("required only to build from source; prebuilt binaries otherwise")

#### Scenario: retained prerequisites unchanged in the amended table

**Given** the amended prerequisite table at v14
**When** the table is inspected
**Then** the shell interpreter, the version-control tool, the shell test
framework, and the openspec CLI are listed with their prior roles unchanged

#### Scenario: Java runtime rows state both paths explicitly

**Given** the amended prerequisite table at v14
**When** the Java runtime rows are inspected
**Then** one row states Java is not required at runtime under the default
binary install, and a second row states Java is required where the JAR
fallback is active — both rows present, neither hidden

### Requirement: Schema renamed at the v14 bump

The schema version SHALL be bumped to v14, and the schema's declared name
SHALL change from the legacy identifier to the probatio identifier at this
bump. The former name SHALL be recorded in the changelog as the pre-rename
identity. The changelog SHALL cite this change by name.

**Given** the schema at the current version (v13) with the legacy name
**When** the version is bumped to v14
**Then** the schema's declared name is the probatio identifier, the version
is 14, and the changelog records the former name alongside a citation of
this change by name

**Rationale**: The rename is the single visible inflection for consumers.
Recording the former name in the changelog gives the next agent (and any
consumer diffing versions) the provenance to trace the rename, not a
mystery. Citing this change by name binds the rename to the change that
motivated it, matching the fault-dataset changelog style established at v12.

#### Scenario: the schema name changes at v14

**Given** the schema metadata file at v13 with the legacy name
**When** the v14 bump lands
**Then** the declared name is the probatio identifier and the version
number is 14

#### Scenario: the changelog records the former name

**Given** the changelog at the v14 entry
**When** the entry is read
**Then** it records the former (legacy) name as the pre-rename identity,
states the new name, and cites this change by name

### Requirement: Skill stamp renamed and old stamp treated as pre-rename

The provenance stamp embedded in installed skill copies SHALL change from
the legacy stamp format to the probatio stamp format. The drift detector
SHALL read the new stamp when comparing installed skill copies against the
schema version. The drift detector SHALL treat the old stamp as a
pre-rename artifact: when an installed skill carries the old stamp, the
detector SHALL emit a one-time migration message identifying the rename,
rather than reporting it as ordinary version drift.

**Given** installed skill copies carrying the legacy stamp format
**When** the drift detector scans the six install roots at v14
**Then** skills carrying the new stamp are compared against the schema
version as before, and skills carrying the old stamp produce a one-time
migration message naming the rename and the re-install instruction, not a
generic drift warning

**Rationale**: The stamp is the provenance link between installed skills and
the schema version. Renaming the stamp without a migration message would
make every pre-rename install look like drift — a false alarm the agent
cannot distinguish from real drift. Treating the old stamp as pre-rename
(by design, one-time) preserves the drift detector's signal: real drift
after the rename is still caught, while the rename itself is explained.

#### Scenario: new stamp compared against schema version

**Given** an installed skill carrying the new (probatio) stamp at version N
**When** the drift detector scans and the schema version is N
**Then** no drift is reported (stamp matches schema version)

#### Scenario: new stamp with version mismatch reports drift

**Given** an installed skill carrying the new (probatio) stamp at version N
**When** the drift detector scans and the schema version is N+1
**Then** a drift warning is reported with the re-install instruction (real
drift, not a rename artifact)

#### Scenario: old stamp produces migration message, not drift

**Given** an installed skill carrying the legacy stamp format at any version
**When** the drift detector scans at v14
**Then** a one-time migration message is emitted naming the rename and the
re-install instruction, distinct from a generic drift warning

#### Scenario: no skill installed produces explicit no-skill line

**Given** no installed skill copies match across the six install roots
**When** the drift detector scans
**Then** an explicit "no skill installed" line is emitted (preserved from
the pre-rename behavior — silence about checking is the same defect class
as silent drift)

### Requirement: Environment variable migrated with deprecated alias for one major

The hook control environment variable SHALL be renamed from the legacy name
to the probatio name. The legacy name SHALL be read as a deprecated alias
for one major version: when the legacy name is set and the new name is not,
the value of the legacy name SHALL be honored as if it were the new name.
When the legacy name is read as an alias, a deprecation warning SHALL be
emitted on stderr naming the rename and the one-major window. After one
major version, the legacy name SHALL no longer be read.

**Given** the hook control variable is set under the legacy name and not
under the new name
**When** the gate process starts
**Then** the legacy value is honored as if set under the new name, and a
deprecation warning is written to stderr naming the rename and the one-major
alias window

**Rationale**: The env var is the escape hatch (`off`) and the trace control.
A hard rename with no alias would break every consumer's existing
`export …=off` on the first v14 run — a stranding defect. A permanent alias
would make the rename meaningless. One major version is the window: long
enough for consumers to migrate their shell configs, short enough that the
old name eventually disappears. The stderr warning ensures the alias is
visible, not silent — a silent alias is the same defect class as silent
drift (the consumer never learns the name changed).

#### Scenario: legacy name honored as alias

**Given** the legacy hook control variable is set to `off` and the new name
is not set
**When** the gate process starts
**Then** the gate is disabled as if the new name were set to `off` (the
escape hatch works through the alias)

#### Scenario: legacy name emits deprecation warning on stderr

**Given** the legacy hook control variable is set and the new name is not
set
**When** the gate process starts
**Then** a deprecation warning is written to stderr naming the old name, the
new name, and the one-major alias window — the warning is on stderr, not
stdout, so it does not pollute the hook payload

#### Scenario: new name takes precedence over legacy alias

**Given** both the legacy and the new hook control variables are set to
different values
**When** the gate process starts
**Then** the new name's value is used and no deprecation warning is emitted
(the new name is authoritative when present)

#### Scenario: legacy name not read after one major

**Given** the schema version is beyond the one-major alias window
**When** the gate process starts and only the legacy name is set
**Then** the legacy name is not read (no alias behavior); the gate proceeds
as if the variable were unset

### Requirement: Cache and state directories auto-migrated on first run

The cache and state directory SHALL move from the legacy path to the
probatio path. On the first run after the rename, if the new directory does
not exist and the legacy directory does, the contents of the legacy
directory SHALL be moved to the new directory. The migration SHALL be
idempotent: a second run after a successful migration SHALL find the new
directory present and perform no migration. If neither directory exists, the
new directory SHALL be created empty (fresh install).

**Given** the legacy cache directory exists with contents and the new
directory does not exist
**When** the tool runs for the first time after the rename
**Then** the legacy directory's contents are moved to the new directory, and
the tool proceeds using the new directory

**Rationale**: The cache directory holds the heartbeat, suppression state,
and trace files. A hard path change with no migration would make every
v14-first-run look like a fresh install — lost suppression state means
re-injection of unchanged payloads, and a lost heartbeat means
`--check-installed` reports `false` after a session that definitely ran.
Auto-migration on first run preserves continuity; idempotence prevents
re-migration from a partially-moved state.

#### Scenario: legacy directory migrated to new path on first run

**Given** the legacy cache directory exists with heartbeat and suppression
state, and the new directory does not exist
**When** the tool runs for the first time after the rename
**Then** the contents are moved to the new directory and the heartbeat is
readable from the new path

#### Scenario: second run after migration performs no migration

**Given** the new cache directory exists (migration already completed) and
the legacy directory may or may not still exist
**When** the tool runs
**Then** no migration is performed; the tool reads state from the new
directory directly

#### Scenario: fresh install with no legacy directory

**Given** neither the legacy nor the new cache directory exists
**When** the tool runs for the first time
**Then** the new directory is created empty and the tool proceeds (no
migration needed, no error)

#### Scenario: legacy directory absent but new directory present

**Given** the new cache directory exists and the legacy directory does not
exist
**When** the tool runs
**Then** the tool uses the new directory (no migration, no error)

### Requirement: Excluded-resources policy rewritten

The hooks documentation's statement of excluded resources SHALL be rewritten
to reflect the new policy. The prior statement — which excluded the JVM and
network categorically from any gate check — SHALL be replaced with a
statement that distinguishes runtime from install-time requirements: the JVM
is not required at hook runtime under the default binary install, but is
required for build-from-source and JAR-fallback; network is not required at
hook runtime, but is required once per version per project for binary
install.

**Given** the hooks documentation's prior "Still excluded: JVM and network"
statement
**When** the v14 policy rewrite lands
**Then** the statement is replaced with the two-part policy: JVM not
required at hook runtime under default binary install; required for
build-from-source and JAR-fallback. Network not required at hook runtime;
required once per version per project for binary install.

**Rationale**: The prior exclusion was absolute because the prior tooling
was bash/jq/python3 with no JVM and no network at runtime. The port moves
the tooling to a native binary (no JVM at runtime in the happy path) but
introduces a JAR fallback (JVM required there) and a binary download
(network required once per version per project at install time, not at hook
runtime). An unchanged statement would be a false claim; the rewrite keeps
the documentation honest about what is and isn't required when.

#### Scenario: JVM policy distinguishes runtime from build/fallback

**Given** the rewritten excluded-resources statement
**When** it is read
**Then** it states the JVM is not required at hook runtime under the default
binary install, and is required for build-from-source and JAR-fallback

#### Scenario: network policy distinguishes runtime from install

**Given** the rewritten excluded-resources statement
**When** it is read
**Then** it states network is not required at hook runtime, and is required
once per version per project for binary install

## Properties (Ring 3)

Properties use Hedgehog (the detected property framework per
`openspec/capability-profile.md` — NOT ScalaCheck). These properties test
the migration mechanics (env var alias, cache dir auto-migration, stamp
rename) as pure functions over generated inputs, binding the shipped
migration logic to its behavioral contract.

### Property: env-var-alias-honored-with-warning

**Invariant**: When the legacy env var name is set and the new name is not,
the resolved value equals the legacy value, and a deprecation warning is
emitted on stderr. When the new name is set, the resolved value equals the
new name's value and no warning is emitted, regardless of the legacy name.

**Generator strategy**: `genEnvVarSetting` — constructive over the four
states: (neither set, legacy only, new only, both set) × value space
(`off`, `on`, `trace`). Edge cases: legacy set to `off` with new unset
(the critical escape-hatch-through-alias case); both set to conflicting
values.

```
forAll { (setting: EnvVarSetting) =>
  val (resolved, warnings) = resolveHookEnv(setting)
  setting match
    case EnvVarSetting.Neither(_) =>
      resolved == EnvDefault && warnings.isEmpty
    case EnvVarSetting.LegacyOnly(value) =>
      resolved == EnvValue(value) && warnings.contains(DeprecationWarning)
    case EnvVarSetting.NewOnly(value) =>
      resolved == EnvValue(value) && warnings.isEmpty
    case EnvVarSetting.Both(newVal, _) =>
      resolved == EnvValue(newVal) && warnings.isEmpty
}
```

### Property: cache-dir-migration-idempotent

**Invariant**: Migration is a pure function of (legacy-dir-exists,
new-dir-exists, legacy-contents). After migration, the new directory
contains exactly the legacy contents (if migration ran) or its prior
contents (if it did not). A second migration call is a no-op: the new
directory's contents are unchanged.

**Generator strategy**: `genCacheState` — constructive over (legacy-exists,
new-exists) × `genCacheContents` (heartbeat file, suppression markers,
trace log — 0–5 files with distinct names). Edge cases: legacy exists /
new absent (migration runs); both absent (fresh install); new exists /
legacy absent (no-op); both exist (no migration, new wins).

```
forAll { (state: CacheState) =>
  val afterFirst  = migrateCache(state)
  val afterSecond = migrateCache(afterFirst)
  afterFirst.newDirContents == expectedAfterMigration(state) &&
  afterSecond.newDirContents == afterFirst.newDirContents
}
```

### Property: stamp-rename-migration-message

**Invariant**: The drift detector's classification of a scanned stamp is a
pure function of the stamp format and version. A legacy-format stamp at any
version produces a migration message (not a drift warning). A new-format
stamp at a matching version produces no drift. A new-format stamp at a
mismatched version produces a drift warning. No installed skills produces
the explicit no-skill line.

**Generator strategy**: `genStampScan` — constructive over the six install
roots, each carrying (stamp-format: Legacy | New, version: Int) or absent.
Edge cases: all-legacy (all migration messages); all-new-matching (no
drift); all-new-mismatched (all drift); mixed (migration + drift); empty
(no-skill line).

```
forAll { (scan: StampScan) =>
  val lines = classifyDrift(scan, schemaVersion = 14)
  scan.roots.flatMap(_.stamp) match
    case Nil =>
      lines == List(NoSkillLine)
    case stamps =>
      val migrationCount = stamps.count(_.format == Legacy)
      val driftCount = stamps.count(s =>
        s.format == New && s.version != 14)
      lines.count(_ == MigrationMessage) == migrationCount &&
      lines.count(_ == DriftWarning) == driftCount &&
      lines.contains(NoSkillLine) == stamps.isEmpty
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Reading the legacy env var without a deprecation-warning code path | The alias MUST warn on stderr; a silent alias is the same defect class as silent drift | `assertDoesNotCompile("resolveLegacyOnly(value) // no warning emitted")` — the migration resolver signature requires a `Warnings` return component |
| Treating the legacy stamp as ordinary drift (no migration message branch) | The old stamp is pre-rename by design; classifying it as drift produces a false alarm indistinguishable from real drift | `assertDoesNotCompile("classifyStamp(Legacy, v) == DriftWarning")` — the sealed `StampClassification` type has a `PreRename` variant that the legacy branch must return |
| Migrating the cache directory when the new directory already exists | Migration MUST be idempotent; re-migration from a partially-moved state could duplicate or lose files | `assertDoesNotCompile("migrateCache(state.copy(newExists = true))")` — the `migrateCache` function's precondition requires `!newExists || !legacyExists` for the migration branch |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Retired prerequisites absent | Requirement: Prerequisite table amended in one version increment + Scenario: retired prerequisites absent from the amended table | bats scenario: retired prereqs absent from table | schema-policy bats suite + MUST-CONFIRM against hooks README |
| Added prerequisites present | Requirement: Prerequisite table amended in one version increment + Scenario: added prerequisites present in the amended table | bats scenario: added prereqs present in table | schema-policy bats suite + MUST-CONFIRM against hooks README |
| Retained prerequisites unchanged | Requirement: Prerequisite table amended in one version increment + Scenario: retained prerequisites unchanged in the amended table | bats scenario: retained prereqs unchanged | schema-policy bats suite + MUST-CONFIRM against hooks README |
| Java runtime rows state both paths | Requirement: Prerequisite table amended in one version increment + Scenario: Java runtime rows state both paths explicitly | bats scenario: both java rows present | schema-policy bats suite + MUST-CONFIRM against hooks README |
| Schema name changes at v14 | Requirement: Schema renamed at the v14 bump + Scenario: the schema name changes at v14 | bats scenario: schema name is probatio at v14 | schema-policy bats suite |
| Changelog records former name and cites change | Requirement: Schema renamed at the v14 bump + Scenario: the changelog records the former name | bats scenario: changelog cites change by name | schema-policy bats suite |
| New stamp compared against schema version | Requirement: Skill stamp renamed and old stamp treated as pre-rename + Scenario: new stamp compared against schema version | bats scenario: new stamp no drift at matching version | schema-policy bats suite |
| New stamp version mismatch reports drift | Requirement: Skill stamp renamed and old stamp treated as pre-rename + Scenario: new stamp with version mismatch reports drift | bats scenario: new stamp drift at mismatched version | schema-policy bats suite |
| Old stamp produces migration message | Requirement: Skill stamp renamed and old stamp treated as pre-rename + Scenario: old stamp produces migration message, not drift | property test (stamp-rename-migration-message) + bats scenario: legacy stamp migration message | schema-policy Hedgehog spec + schema-policy bats suite |
| No skill installed produces explicit line | Requirement: Skill stamp renamed and old stamp treated as pre-rename + Scenario: no skill installed produces explicit no-skill line | bats scenario: no-skill line emitted | schema-policy bats suite |
| Legacy env var honored as alias | Requirement: Environment variable migrated with deprecated alias for one major + Scenario: legacy name honored as alias | property test (env-var-alias-honored-with-warning) + bats scenario: legacy alias disables gate | schema-policy Hedgehog spec + schema-policy bats suite |
| Legacy env var warns on stderr | Requirement: Environment variable migrated with deprecated alias for one major + Scenario: legacy name emits deprecation warning on stderr | property test (env-var-alias-honored-with-warning) + bats scenario: warning on stderr not stdout | schema-policy Hedgehog spec + schema-policy bats suite |
| New env var takes precedence | Requirement: Environment variable migrated with deprecated alias for one major + Scenario: new name takes precedence over legacy alias | property test (env-var-alias-honored-with-warning) + bats scenario: new name authoritative | schema-policy Hedgehog spec + schema-policy bats suite |
| Legacy env var not read after one major | Requirement: Environment variable migrated with deprecated alias for one major + Scenario: legacy name not read after one major | bats scenario: legacy name ignored post-major | schema-policy bats suite |
| Cache dir migrated on first run | Requirement: Cache and state directories auto-migrated on first run + Scenario: legacy directory migrated to new path on first run | property test (cache-dir-migration-idempotent) + bats scenario: contents moved to new path | schema-policy Hedgehog spec + schema-policy bats suite |
| Second run after migration is no-op | Requirement: Cache and state directories auto-migrated on first run + Scenario: second run after migration performs no migration | property test (cache-dir-migration-idempotent) + bats scenario: idempotent re-run | schema-policy Hedgehog spec + schema-policy bats suite |
| Fresh install creates new dir empty | Requirement: Cache and state directories auto-migrated on first run + Scenario: fresh install with no legacy directory | bats scenario: fresh install no error | schema-policy bats suite |
| Legacy absent new present uses new | Requirement: Cache and state directories auto-migrated on first run + Scenario: legacy directory absent but new directory present | bats scenario: new dir used when legacy absent | schema-policy bats suite |
| JVM policy distinguishes runtime from build/fallback | Requirement: Excluded-resources policy rewritten + Scenario: JVM policy distinguishes runtime from build/fallback | bats scenario: JVM policy rewritten | schema-policy bats suite |
| Network policy distinguishes runtime from install | Requirement: Excluded-resources policy rewritten + Scenario: network policy distinguishes runtime from install | bats scenario: network policy rewritten | schema-policy bats suite |
| Env var alias not silent (compile-negative) | Compile-Negative: reading legacy env var without warning | compile-negative test (assertDoesNotCompile) | schema-policy Hedgehog spec |
| Legacy stamp not classified as drift (compile-negative) | Compile-Negative: treating legacy stamp as ordinary drift | compile-negative test (assertDoesNotCompile) | schema-policy Hedgehog spec |
| Cache migration idempotence (compile-negative) | Compile-Negative: migrating when new dir exists | compile-negative test (assertDoesNotCompile) | schema-policy Hedgehog spec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `schema.yaml` name + version | schema metadata | `openspec/schemas/verified-scala3/schema.yaml` → relocated to probatio schema path at rename | `name: probatio`, `version: 14` (was `name: verified-scala3`, `version: 13`) |
| `CHANGELOG.md` v14 entry | changelog | `openspec/schemas/verified-scala3/CHANGELOG.md` → relocated | Records `formerly: verified-scala3`, cites `port-scanner-to-probatio` by name, fault-dataset style |
| `generatedBy` stamp | skill provenance stamp | `.pi/skills/openspec-*/`, `.claude/skills/`, `.devin/skills/`, `$HOME` equivalents | `generatedBy: probatio-schema/<N>` (was `generatedBy: verified-scala3-schema/<N>`); drift detector reads new stamp, classifies old stamp as `PreRename` (migration message) |
| `PROBATIO_HOOKS` env var | hook control variable | gate process environment | Renamed from `VERIFIED_SCALA3_HOOKS`; old name read as deprecated alias for one major; warning on stderr when alias is used; new name takes precedence when both set |
| `~/.cache/probatio/` | cache + state directory | user home cache | Renamed from `~/.cache/verified-scala3/`; auto-migrated on first run (contents moved); idempotent; fresh install creates empty |
| `hooks/README.md` prerequisite table | prerequisite declaration | `openspec/schemas/verified-scala3/hooks/README.md` → relocated | **MUST-CONFIRM authoritative source**: exact row set, column format, and wording reconciled against the amendment policy (R-V1) |
| `hooks/README.md` excluded-resources statement | policy statement | `openspec/schemas/verified-scala3/hooks/README.md` → relocated | "Still excluded: JVM and network" rewritten to two-part policy (R-V3): JVM not required at hook runtime under default binary install; required for build-from-source and JAR-fallback. Network not required at hook runtime; required once per version per project for binary install |
| `SchemaPolicySpec.scala` | Hedgehog property tests | `workflow/cli/src/test/scala/org/sinemenda/probatio/` | Properties: `env-var-alias-honored-with-warning`, `cache-dir-migration-idempotent`, `stamp-rename-migration-message`; compile-negative obligations |
| `tests/schema-policy.bats` | bats oracle scenarios | `openspec/schemas/verified-scala3/tests/` → relocated | Scenario coverage for R-V1…R-V3; MUST-CONFIRM table reconciliation against `hooks/README.md` |
| `StampClassification` | sealed type (probatio-core) | `workflow/core/src/main/scala/org/sinemenda/probatio/` | Disjoint sum: `Matching` | `DriftWarning` | `PreRename` | `NoSkill` — the legacy stamp branch must return `PreRename`, enforced by compile-negative |
| `resolveHookEnv` | pure function (probatio-core) | `workflow/core/src/main/scala/org/sinemenda/probatio/` | `(EnvVarSetting) => (ResolvedValue, Warnings)` — returns a `Warnings` component so the deprecation warning cannot be silently dropped (compile-negative enforced) |
| `migrateCache` | pure function (probatio-core) | `workflow/core/src/main/scala/org/sinemenda/probatio/` | `(CacheState) => CacheState` — migration branch precondition: `!newExists || !legacyExists`; idempotent by construction |
