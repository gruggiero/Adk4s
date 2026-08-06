# Spec: Memory Retriever Bridge (Delta)

<!-- DELTA spec for the migrate-json-codec change. MODIFIES the
     `Document.metadata` field type from `Map[String, ujson.Value]` to
     `Map[String, JsonValue]`, and the `MemoryRetriever` mapping logic that
     populates it. The `Retriever` trait, `RetrieverConfig` handling, and
     all other requirements in the base spec are UNCHANGED. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| retriever | `Document.metadata` field type migrates from `Map[String, ujson.Value]` to `Map[String, JsonValue]`; `MemoryRetriever` mapping logic adapts | `openspec/concepts/retriever.md` |

No change to the `retriever.md` concept file's actions, state, or synchronizations — only the `Document.metadata` field's value type changes. The concept's Implementation map is updated at apply Step 12.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Document` | case class (`Retriever.Document`) | `org.adk4s.core.component` |
| `RetrieverConfig` | case class | `org.adk4s.core.component` |
| `MemoryHit` | case class | `org.adk4s.memory` |
| `AgentMemory[F[_]]` | service trait | `org.adk4s.memory` |
| `smithy4s.Document` | sealed trait (ADT) | `smithy4s` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `JsonValue` | type alias (`= smithy4s.Document`) | Introduced by the json-value-model spec; referenced here as the new `metadata` value type |

## MODIFIED Requirements

### Requirement: MemoryRetriever maps MemoryHit to Document with synthesized id and metadata

The system SHALL map each `MemoryHit` to a `Document(id: String, content: String, metadata: Map[String, JsonValue])` where `id` is a synthesized stable identifier, `content` is `hit.text`, and `metadata` carries `score` (as `JsonValue` `DNumber`), `provenance` (as `JsonValue` `DString` when present), and the entries of `hit.payload` (as `JsonValue` `DString`). The `metadata` value type SHALL be `JsonValue` (immutable), NOT `ujson.Value` (mutable).

**Given** a `MemoryHit(text = "Alice works at Meta", score = 0.9, provenance = Some("g1"), payload = Map("role" -> "user"))`
**When** `MemoryRetriever` maps it to a `Document`
**Then** `content == "Alice works at Meta"`, `metadata("score") == JsonValue.DNumber(0.9)`, `metadata("provenance") == JsonValue.DString("g1")`, `metadata("role") == JsonValue.DString("user")`, and `id` is non-empty

**Rationale**: `Document` (verified shape: `id: String, content: String, metadata: Map[String, ujson.Value]`) has no `score` field, so score must ride inside `metadata`. The value type migrates to `JsonValue` so `Document` — a public API type — no longer carries the mutable `ujson` AST or the undeclared transitive dependency. A synthesized `id` is required because `MemoryHit` has no identifier field.

#### Scenario: score carried as JsonValue DNumber

**Given** a hit with `score = 0.5`
**When** mapped to `Document`
**Then** `metadata("score")` is `JsonValue.DNumber(0.5)` (a `smithy4s.Document.DNumber`, not `ujson.Num`)

#### Scenario: provenance omitted when None

**Given** a hit with `provenance = None`
**When** mapped to `Document`
**Then** `metadata` does not contain the key "provenance"

#### Scenario: payload entries become JsonValue DString values

**Given** a hit with `payload = Map("k1" -> "v1")`
**When** mapped to `Document`
**Then** `metadata("k1") == JsonValue.DString("v1")`

#### Scenario: synthesized id is stable for the same hit

**Given** the same `MemoryHit` mapped twice
**When** both `Document.id` values are compared
**Then** they are equal (the id is a pure function of the hit's fields, not random)

#### Scenario: metadata is immutable

**Given** a `Document` produced by `MemoryRetriever`
**When** code attempts to mutate `metadata` in place
**Then** compilation fails — `Map[String, JsonValue]` is an immutable `Map` of immutable values; no in-place `update` exists

## Properties (Ring 3)

### Property: MemoryHit-to-Document mapping preserves all fields

**Invariant**: For every `MemoryHit`, the mapped `Document` has `content == hit.text`, `metadata("score") == DNumber(hit.score)`, `metadata("provenance") == DString(hit.provenance)` when `provenance` is present (absent key otherwise), every `payload` entry mapped to `DString`, and a non-empty stable `id`.

**Generator strategy**: `genMemoryHit` (constructive — `genString` for text, `genDouble` Range.linearF 0.0 1.0 for score, `genOption(genString)` for provenance, `genMap(genString, genString)` for payload; classify by provenance-present vs absent, payload-empty vs non-empty). Reuses the existing `genHit` from `adk4s-memory-api/src/test/.../Generators.scala`.

```
forAll { (hit: MemoryHit) =>
  val doc = MemoryRetriever.mapHit(hit)
  doc.content == hit.text &&
  doc.metadata("score") == DNumber(hit.score) &&
  hit.provenance.forall(p => doc.metadata("provenance") == DString(p)) &&
  hit.provenance.isEmpty || !doc.metadata.contains("provenance") &&
  hit.payload.forall { (k, v) => doc.metadata(k) == DString(v) } &&
  doc.id.nonEmpty
}
```

### Property: Document metadata is JsonValue not ujson

**Invariant**: Every value in `Document.metadata` is a `smithy4s.Document` variant, never a `ujson.Value`.

**Generator strategy**: `genMemoryHit` (as above). This is primarily a type-system obligation (the field type is `Map[String, JsonValue]`), but the property verifies the mapping logic produces the right variants.

```
forAll { (hit: MemoryHit) =>
  val doc = MemoryRetriever.mapHit(hit)
  doc.metadata.values.forall(_.isInstanceOf[smithy4s.Document])
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `Document.metadata: Map[String, ujson.Value]` | The field type migrates to `Map[String, JsonValue]` | `assertDoesNotCompile("val d = Document(..., Map[String, ujson.Value]())")` (type mismatch) |
| In-place mutation of `metadata` | `Map[String, JsonValue]` is immutable | `assertDoesNotCompile("doc.metadata.update(...)")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Document.metadata is Map[String, JsonValue] | Requirement: MemoryRetriever maps MemoryHit to Document + Scenario: score carried as JsonValue DNumber | type system (field type) + adversarial review | DocumentTypeContract |
| Score mapped to DNumber | Requirement: MemoryRetriever maps MemoryHit to Document + Scenario: score carried as JsonValue DNumber | Hedgehog property (MemoryHit-to-Document mapping preserves all fields) | MemoryRetrieverSpec |
| Provenance omitted when None | Requirement: MemoryRetriever maps MemoryHit to Document + Scenario: provenance omitted when None | Hedgehog property + scenario test | MemoryRetrieverSpec |
| Payload entries become DString | Requirement: MemoryRetriever maps MemoryHit to Document + Scenario: payload entries become JsonValue DString values | Hedgehog property | MemoryRetrieverSpec |
| Synthesized id is stable | Requirement: MemoryRetriever maps MemoryHit to Document + Scenario: synthesized id is stable for the same hit | Hedgehog property (same hit → same id) | MemoryRetrieverSpec |
| Metadata is immutable | Requirement: MemoryRetriever maps MemoryHit to Document + Scenario: metadata is immutable | compile-negative test | DocumentTypeContract |
| Metadata values are Document not ujson | Property: Document metadata is JsonValue not ujson | Hedgehog property + type system | MemoryRetrieverSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Document` | case class | `adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala` | `metadata: Map[String, ujson.Value]` → `Map[String, JsonValue]` |
| `MemoryRetriever` mapping | function | `adk4s-memory-api/src/main/scala/org/adk4s/memory/MemoryRetriever.scala` | `ujson.Num(score)` → `DNumber(score)`, `ujson.Str(s)` → `DString(s)` |
