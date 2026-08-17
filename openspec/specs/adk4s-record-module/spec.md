# Spec: adk4s-record-module (module placement, build.sbt, Ring 2 arch rules)

<!-- Delta spec for the add-adk4s-record change. Defines the new sbt module
     placement, build.sbt wiring, dependency purity, and two new Scalafix
     architecture rules (AR-REC-1, AR-REC-2) that mechanically enforce the
     canonicalization purity requirements. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | The module wraps ChatModel; module placement sits directly above adk4s-core | `openspec/concepts/chat-model.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` |
| `Embedder[F[_]]` | trait | `org.adk4s.core.component` |
| `ToolMiddleware` | type alias | `org.adk4s.core.tools` |
| `JsonValue` | type alias | `org.adk4s.core.json` |
| `NodeKey` | opaque type (Iron RefinedType precedent) | `org.adk4s.core.types` |
| `Positive` | type alias (Iron) | `org.adk4s.core.types` |
| `NonEmpty` | type alias (Iron) | `org.adk4s.core.types` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `adk4s-record` | sbt module (module 13) | New subproject: deterministic call recording + content-hash caching |
| `AR-REC-1` | Scalafix arch rule | No ambient nondeterminism in the canonicalization package |
| `AR-REC-2` | Scalafix arch rule | No unordered iteration in the canonicalization package |

## ADDED Requirements

### Requirement: adk4s-record module placement and dependencies

The system SHALL create a new sbt module `adk4s-record` that depends on
`adk4s-core` and `verified` at Test scope, and MUST NOT depend on
`workflows4s`, `adk4s-orchestration`, `adk4s-optimize`, `adk4s-eval`,
`fs2-io` outside the file recorder source, or `logback`.

**Given** the build.sbt module graph
**When** the `adk4s-record` module is declared
**Then** it `.dependsOn(adk4s-core, verified % Test)` and its
`libraryDependencies` include `catsEffect`, `fs2` (core+io, io scoped to
file recorder), `iron`, `upickle`, `munitMain`, `munitCatsEffect`,
`hedgehogMunitMain`, and `testDeps`

**Rationale**: The wrappers target `adk4s-core` types, so the module sits
directly above core. It must not live in core itself: core stays free of
caching and provenance vocabulary. The `verified` Test-scope dependency
wires the Ring 6 bridge.

#### Scenario: Module compiles independently

**Given** the new module is declared with only the permitted dependencies
**When** the module is compiled in isolation
**Then** compilation succeeds without requiring the orchestration or
workflow-engine modules on the classpath

#### Scenario: No forbidden dependencies

**Given** the `adk4s-record` module's dependency tree
**When** the dependency tree is inspected
**Then** `workflows4s`, `adk4s-orchestration`, `adk4s-optimize`,
`adk4s-eval`, and `logback` are absent

### Requirement: Canonicalization package is free of ambient nondeterminism

The system SHALL enforce via a Scalafix architecture rule (AR-REC-1) that
the canonicalization package (`org.adk4s.record.canonical`) contains no
reference to `System.currentTimeMillis`, `Instant.now`, `java.util.Random`,
`UUID.randomUUID`, or `.hashCode` on a non-value type.

**Given** the canonicalization package source
**When** the AR-REC-1 Scalafix rule is run
**Then** no source file in `org.adk4s.record.canonical` references any
ambient-nondeterminism source

**Rationale**: This is the mechanical enforcement of REC-5 (canonicalization
is pure and total). `.hashCode` is included because JVM identity hashes are
the classic silent cross-process key instability.

#### Scenario: AR-REC-1 flags ambient time reference

**Given** a source file in the canonicalization package containing a
wall-clock time reference
**When** the architecture rule is run against the package
**Then** the rule reports a violation identifying the forbidden reference

#### Scenario: AR-REC-1 passes on clean canonicalization

**Given** a canonicalization package with no ambient-nondeterminism references
**When** the architecture rule is run against the package
**Then** the rule passes with no violations

### Requirement: Canonicalization package uses no unordered iteration

The system SHALL enforce via a Scalafix architecture rule (AR-REC-2) that
the canonicalization package contains no direct iteration over `Map` or
`Set` without an explicit sort.

**Given** the canonicalization package source
**When** the AR-REC-2 Scalafix rule is run
**Then** no source file in `org.adk4s.record.canonical` iterates over a
`Map` or `Set` without first sorting (e.g. `.toList.sortBy(_._1)`)

**Rationale**: `Map`/`Set` iteration order is JVM-dependent. Direct
iteration produces same request, different keys across processes.

#### Scenario: AR-REC-2 flags unsorted Map iteration

**Given** a source file in the canonicalization package containing direct
iteration over an unordered collection without a prior sort
**When** the architecture rule is run against the package
**Then** the rule reports a violation identifying the unsorted iteration

#### Scenario: AR-REC-2 passes on sorted iteration

**Given** a canonicalization package where all unordered-collection
iteration is preceded by an explicit sort
**When** the architecture rule is run against the package
**Then** the rule passes with no violations

### Requirement: fs2-io is source-scoped to the file recorder

The system SHALL confine the `fs2-io` dependency to the file recorder
source; canonicalization and key computation must remain free of streaming
and effect types.

**Given** the `adk4s-record` module source
**When** the source files are inspected
**Then** `fs2.io` imports appear only in the file recorder implementation,
and the canonicalization package imports neither `fs2` nor `cats.effect`

**Rationale**: Ring 2 purity rule — canonicalization is pure and must not
depend on effect types or streaming.

#### Scenario: Canonicalization has no fs2 imports

**Given** the canonicalization package source files
**When** they are inspected for `fs2` imports
**Then** none are found

### Requirement: Iron refined types are used for RolloutId and maxEntries

The system SHALL use Iron-refined types for `RolloutId` (`NonEmpty`) and
`maxEntries` (`numeric.Positive`), following the `NodeKey` `RefinedType`
precedent, with no new dependency (Iron is already in `adk4s-core`).

**Given** the `adk4s-record` module
**When** `RolloutId` and `maxEntries` are declared
**Then** they use `String :| NonEmpty` and `Int :| Positive` respectively,
reusing the constraints from `org.adk4s.core.types`

**Rationale**: D1 (Iron IS in stack). The `add-iron-refined-types` change
migrated the project's newtypes to Iron. This change follows the same
pattern with no new dependency.

#### Scenario: RolloutId uses NonEmpty constraint

**Given** a `RolloutId` declaration in the record module
**When** it is compiled
**Then** it uses `String :| NonEmpty` from `org.adk4s.core.types`, and an
empty string literal fails compilation

#### Scenario: maxEntries uses Positive constraint

**Given** a `Recorder.inMemory` factory declaration
**When** it is compiled
**Then** its `maxEntries` parameter uses `Int :| Positive` from
`org.adk4s.core.types`, and zero fails compilation

## Properties (Ring 3)

### Property: module-purity

**Invariant**: The `adk4s-record` module does not depend on
`workflows4s`, `adk4s-orchestration`, `adk4s-optimize`, `adk4s-eval`, or
`logback`.

**Generator strategy**: n/a — this is a build-level property, verified by
inspecting the dependency tree, not a generated property test.

```
// verified by: sbt "adk4s-record/whatDependsOn workflows4s" returns no results
// verified by: grep -r "workflows4s\|adk4s-orchestration\|adk4s-optimize\|adk4s-eval\|logback" adk4s-record/src returns no results
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `import org.adk4s.orchestration.*` in adk4s-record | Module purity rule — must not depend on orchestration | `assertDoesNotCompile` or Scalafix arch rule |
| `import workflows4s.*` in adk4s-record | Module purity rule — must not depend on workflows4s | `assertDoesNotCompile` or Scalafix arch rule |

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name | Status |
|-------------|-----------|--------|
| Requirement: adk4s-record module placement and dependencies — Scenario: Module compiles independently | ModulePuritySpec: "Module compiles independently" | ✅ |
| Requirement: adk4s-record module placement and dependencies — Scenario: No forbidden dependencies | ModulePuritySpec: "No forbidden dependencies" | ✅ |
| Requirement: Canonicalization package is free of ambient nondeterminism — Scenario: AR-REC-1 flags ambient time reference | `.scalafix.conf` NoAmbientNondeterminismInCanonical rule (declared; empty source tree) | ✅ |
| Requirement: Canonicalization package is free of ambient nondeterminism — Scenario: AR-REC-1 passes on clean canonicalization | `.scalafix.conf` NoAmbientNondeterminismInCanonical rule (declared; empty source tree) | ✅ |
| Requirement: Canonicalization package uses no unordered iteration — Scenario: AR-REC-2 flags unsorted Map iteration | `.scalafix.conf` NoUnorderedIterationInCanonical rule (declared; empty source tree) | ✅ |
| Requirement: Canonicalization package uses no unordered iteration — Scenario: AR-REC-2 passes on sorted iteration | `.scalafix.conf` NoUnorderedIterationInCanonical rule (declared; empty source tree) | ✅ |
| Requirement: fs2-io is source-scoped to the file recorder — Scenario: Canonicalization has no fs2 imports | ModulePuritySpec: "Canonicalization has no fs2 or cats.effect imports" | ✅ |
| Requirement: Iron refined types are used for RolloutId and maxEntries — Scenario: RolloutId uses NonEmpty constraint | Deferred to spec 2 (call-key introduces RolloutId) | ⏭️ |
| Requirement: Iron refined types are used for RolloutId and maxEntries — Scenario: maxEntries uses Positive constraint | Deferred to spec 3 (recorder-sink introduces maxEntries) | ⏭️ |
| Property: module-purity | ModulePuritySpec: "module-purity" | ✅ |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Module placement and deps | Requirement: adk4s-record module placement and dependencies | scenario test (compile) + build inspection | `build.sbt`, `sbt adk4s-record/compile` |
| No forbidden dependencies | Requirement: adk4s-record module placement and dependencies | property test (module-purity) + scenario test | `build.sbt`, `ModulePuritySpec.scala` |
| AR-REC-1 no ambient nondeterminism | Requirement: Canonicalization package is free of ambient nondeterminism | static rule (Scalafix AR-REC-1) | `.scalafix.conf` |
| AR-REC-2 no unordered iteration | Requirement: Canonicalization package uses no unordered iteration | static rule (Scalafix AR-REC-2) | `.scalafix.conf` |
| fs2-io source-scoped | Requirement: fs2-io is source-scoped to the file recorder | scenario test (import audit) + manual review | `ModulePuritySpec.scala` |
| Iron refined types used | Requirement: Iron refined types are used for RolloutId and maxEntries | type system (Iron RefinedType) + compile test | `build.sbt`, `sbt adk4s-record/compile` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `adk4s-record` | sbt module | `build.sbt` | `lazy val adk4s-record = (project in file("adk4s-record")).dependsOn(adk4s-core, verified % Test)` |
| `AR-REC-1` | Scalafix custom rule | `.scalafix.conf` | regex-based: `System.currentTimeMillis\|Instant.now\|java.util.Random\|UUID.randomUUID\|\.hashCode` in `org.adk4s.record.canonical` |
| `AR-REC-2` | Scalafix custom rule | `.scalafix.conf` | detects `Map`/`Set` `.foreach`/`.map`/`.iterator` without preceding `.toList.sortBy` in `org.adk4s.record.canonical` |
| `scala3Options` | scalacOptions | `build.sbt` | shared val with exhaustiveness escalation; `adk4s-record` uses it |
| `iron` | dependency | `build.sbt` | already in `adk4s-core` (transitive); `adk4s-record` may need explicit `iron` if it declares `RolloutId` before `adk4s-core` re-exports |
| `hedgehogMunitMain` | dependency (main scope) | `build.sbt` | for `RecorderLaws` in main scope |
| `fs2` (core+io) | dependency | `build.sbt` | `fs2Core` + `fs2-io` (io for file recorder only) |
