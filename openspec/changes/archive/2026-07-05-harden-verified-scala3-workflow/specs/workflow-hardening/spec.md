# Spec: verified-scala3 Workflow Hardening

<!-- Delta spec. Each requirement maps to one escape-analysis lever and to the
     concrete schema.yaml section it requires. The schema has ALREADY been
     amended; this spec is the verifiable record of what those amendments
     require. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `schema.yaml` (verified-scala3) | workflow definition | `openspec/schemas/verified-scala3/` |
| `specs` artifact | artifact | `schema.yaml` `artifacts[]` |
| `spec-lint` artifact | artifact | `schema.yaml` `artifacts[]` |
| `apply` phase Steps 0/2/4/5/12 | apply steps | `schema.yaml` `apply.instruction` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Type-Widening Impact subsection | spec obligation | specs rule 11 — downstream catch-all match audit when a public type is aliased/widened |
| PUBLIC-TYPE-CHANGE IMPACT SCAN | apply Step 0 sub-step | whole-module-graph grep for catch-all matches on a widened public type |
| ORACLE FAITHFULNESS rule | apply Step 2 rule | forbids loosening a spec-named error-variant assertion |
| ExhaustiveDomainConversion | Ring 2 architecture rule | forbids catch-all mapping an unrecognized variant to a valid domain value |
| REMOVAL AUDIT | apply Step 12 sub-step | grep for orphaned dependents of removed fields/types |

## ADDED Requirements

### Requirement: Behavior-preservation requirements cover every enum variant

The verified-scala3 workflow SHALL require (spec-lint check 1c) that any spec
requirement asserting "identical/same/preserved behavior" over an enum or
dispatch parameter be backed by at least one scenario per enum variant, each
asserting the discriminating observable, and SHALL require (Step 2 oracle) one
generated test per such variant.

#### Scenario: Per-variant scenario and test coverage

**Given** a spec requirement that claims behavior preservation over a
`RetryTrigger`/enum parameter with N variants
**When** the spec is linted and the test oracle is generated
**Then** there are at least N scenarios (one per variant) and N tests, each
asserting which inputs trigger which behavior (not just an aggregate call count)

**Lever:** 6 (oracle coverage) + 1 (spec-lint). **Escapes prevented:** Finding 1
(`RetryTrigger.LLMError` mis-mapping).

### Requirement: Type-alias widening is audited for downstream matches

The verified-scala3 workflow SHALL treat aliasing a public type to a type with
more variants as an enum extension, and SHALL require (a) a "Type-Widening
Impact" subsection in the spec listing every downstream catch-all match on that
type (specs rule 11, spec-lint check 11), and (b) a Step 0
PUBLIC-TYPE-CHANGE IMPACT SCAN over the whole module graph whose results are
recorded in the checkpoint.

#### Scenario: Alias widening fails lint and triggers the impact scan

**Given** a spec that changes `type Role` to alias a richer enum that adds a
`Tool` variant
**When** the spec is linted and Step 0 runs
**Then** the spec-lint FAILS unless a Type-Widening Impact subsection lists
every catch-all match on `Role`/`Message`, and Step 0 greps the whole module
graph (not just the diff) for those matches and requires each to be exhaustive,
explicitly rejecting, or justified

**Lever:** 8 (new scan — closes the structural blind spot) + 1 + 2.
**Escapes prevented:** Findings 2 & 3 (`MessageStream`/`PromptSyntax`/`ChatTemplate`
`case other =>` after `Role` aliasing).

### Requirement: Test oracles must be faithful to spec-named error variants

The verified-scala3 workflow SHALL require (Step 2) that a test asserting a
spec-named error variant assert that variant exactly, and SHALL forbid loosening
such an assertion with `.toString.contains(...)` / `message.contains(...)` / `||`
substring fallbacks to accommodate the implementation. If the implementation
cannot produce the named variant, the spec or producing signature must change
(re-approved at the Step 2 gate), never the oracle.

#### Scenario: Faithful error-variant assertion

**Given** a spec scenario requiring `Left(ToolCallError.InvalidArguments(...))`
**When** the test oracle is written
**Then** the test asserts the `InvalidArguments` variant exactly, and Ring 3
would FAIL rather than pass on a flattened `Left(String)`

**Lever:** 6 (oracle faithfulness) + 1 (error-variant feasibility, specs rule
13 / spec-lint check 14). **Escapes prevented:** Finding 6 (`toToolFunction`
flattens errors to `String`).

### Requirement: Consumer-facing surfaces must specify what the consumer observes

The verified-scala3 workflow SHALL require (specs rule 12, spec-lint check 13)
that any spec introducing or changing an LLM-/caller-facing surface state what
the consumer observes (parameter names/types/schema, not just name +
description) and carry a scenario asserting that surface.

#### Scenario: LLM-facing schema asserted

**Given** a spec synthesizing an LLM-visible `ToolFunction` from a
`StructuredToolFunction`
**When** the spec is linted
**Then** it FAILS unless a scenario asserts the LLM-facing schema exposes the
tool's input parameters (non-empty, consistent with `inputSchema`)

**Lever:** 1 (spec coverage) + 6. **Escapes prevented:** Finding 5
(`toToolFunction` empty parameter schema).

### Requirement: Refactors must remove orphaned dependents

The verified-scala3 workflow SHALL require (Step 12 REMOVAL AUDIT) that for
every field/type removed from a refactored concept, main sources are grepped for
now-orphaned dependents (traits, opaque adapters, extension methods, helper
factories) and each is deleted or explicitly retained with rationale, because
`RemoveUnused` lint does not flag public/opaque members.

#### Scenario: Orphaned adapter flagged by the removal audit

**Given** a refactor that deletes `ToolWrapper.executable` and
`originalToolFunction`
**When** Step 12 runs
**Then** `SafeToolExecutable`, `ToolFunctionAdapter`, and `toSafeExecutable`
are flagged as orphaned and must be deleted or retained with rationale

**Lever:** 1 (Step 12) + 3 (Ring 1). **Escapes prevented:** Finding 4 (dead code).

### Requirement: Domain-conversion matches must be exhaustive

The verified-scala3 workflow SHALL enforce (Ring 2 `ExhaustiveDomainConversion`,
paired with the Ring 1 grep) that pattern matches converting or constructing
domain values be exhaustive or explicitly reject, and SHALL flag a catch-all
that maps an unrecognized variant to a valid domain value (e.g.
`case other => UserMessage(...)`).

#### Scenario: Catch-all mapping flagged as a domain-conversion violation

**Given** a `messageForRole` match over `MessageRole` with a catch-all that
constructs a `UserMessage`
**When** Ring 1/Ring 2 run
**Then** the catch-all is flagged and must be made exhaustive or explicitly
reject the unrecognized variant

**Lever:** 2 (Ring 2 rule) + 3 (Ring 1 grep, broadened to importers).
**Escapes prevented:** Findings 2 & 3.

## Properties (Ring 3)

This is a workflow-definition change; the executable properties are the
self-tests in proposal.md §Verification Strategy (YAML validity, cross-reference
resolution, and the dry-run confirmation that the new checks would have FAILED
the archived `llm4s-middleware-and-dedup` specs).

## Proof Obligations

| Obligation | Enforcement | Artifact |
|------------|-------------|----------|
| schema.yaml is valid YAML and loads in openspec | manual check + `openspec` load | proposal self-test 1 |
| specs↔spec-lint cross-references resolve (11↔11, 12↔13, 13↔14) | manual cross-check | proposal self-test 2 |
| new checks are load-bearing (would fail the archived change) | dry-run lint of archived specs | proposal self-test 3 |
