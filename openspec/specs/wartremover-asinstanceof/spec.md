# Spec: WartRemover AsInstanceOf Re-enablement

<!-- Delta spec. Re-enables Wart.AsInstanceOf by replacing `asInstanceOf`
     runtime type dispatch with type-safe pattern matching or a sealed-algebra
     redesign. This is the highest-risk spec: it touches tool schema dispatch
     (Tool/ToolInfer/ToolSchema) and workflow node compilation
     (WIOGraph/WIONode/GraphExecutor) on erased generic types. The exact
     mechanism (pattern match vs sealed redesign vs compiletime `summonFrom`)
     is finalized in design.md; this spec fixes the behavioral requirements. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Tool` / `InvokableTool` / `StreamableTool` | service trait tier | `org.adk4s.core.component` |
| `ToolWrapper` | case class (dual storage) | `org.adk4s.core.tools` |
| `SafeToolExecutable` | trait | `org.adk4s.core.tools` |
| `ToolSchema[A]` | opaque type | `org.adk4s.core.tools` |
| `WIONode[Ctx, I, Err, O]` | sealed trait (10 variants) | `org.adk4s.orchestration.wiograph` |
| `WIONodeModifier[Ctx, I, Err, O]` | sealed trait | `org.adk4s.orchestration.wiograph` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | Per design.md, the `Tool.buildObjectSchema` dispatch is resolved via a plain pattern match over the `"type"` string constructing the matching `SchemaDefinition[String]` variant directly. The tentative `ToolDispatchResult` sealed trait is **withdrawn** — no new concept is introduced. The concept delta check at apply Step 12 verifies this spec introduces nothing. |

## ADDED Requirements

### Requirement: No `asInstanceOf` in compiled main sources

The system SHALL contain zero calls to `asInstanceOf` in any main source compiled by a module whose `wartremoverErrors` includes `Wart.AsInstanceOf`.

**Given** a main source file in `adk4s-core`, `adk4s-orchestration`, `structured-llm`, or `structured-llm-test-models`
**When** WartRemover runs with `Wart.AsInstanceOf` enabled
**Then** no `AsInstanceOf` error is reported and the build compiles

**Rationale**: `asInstanceOf` defeats the type system and is explicitly forbidden by `AGENTS.md` ("NEVER use isInstanceOf and asInstanceOf ... use pattern matching instead"). The current sites dispatch on erased generic types (`m.MirroredElemTypes`, `SchemaDefinition[String]`) where the cast is a latent `ClassCastException`.

#### Scenario: `Tool.buildObjectSchema` schema dispatch via pattern match

**Given** `Tool.buildObjectSchema` does `IntegerSchema(desc).asInstanceOf[SchemaDefinition[String]]`, `NumberSchema(...)`, `BooleanSchema(...)` to unify schema variants into `SchemaDefinition[String]`
**When** the refactor replaces the `match` on the type string with a pattern match that constructs the `SchemaDefinition[String]` whose variant matches the `"type"` string (`"integer"` → `IntegerSchema`, `"number"` → `NumberSchema`, `"boolean"` → `BooleanSchema`, default → `StringSchema`) directly (or via a sealed `ToolDispatchResult` wrapper)
**Then** the produced `ObjectSchema[ujson.Value]` is identical to the pre-refactor output for every `parameters` JSON, and no `asInstanceOf` remains

#### Scenario: `ToolInfer` compiletime `constValue` cast replaced by `summonFrom` / `constValueOpt`

**Given** `ToolInfer.getFieldNames` does `scala.compiletime.constValue[head].asInstanceOf[String]`
**When** the refactor uses `scala.compiletime.constValueOpt[head]` returning `Option[String]` with a `match` (or `summonFrom`), handling the `None` case explicitly
**Then** the generated field-name list is identical for all product types, and no `asInstanceOf` remains in `ToolInfer`

#### Scenario: `ToolInfer.decodeProduct` tuple cast replaced by type-safe reconstruction

**Given** `decodeProduct` does `decodeFields[...].asInstanceOf[m.MirroredElemTypes]` then `m.fromTuple(values)`
**When** the refactor reconstructs the product via a type-safe `Mirror`-driven path (e.g. building the tuple case-by-case and letting `fromTuple` accept it without a cast, or using a `Monad`-style decode over the tuple)
**Then** decoding succeeds for the same inputs and fails with the same `Left(message)` for malformed JSON, and no `asInstanceOf` remains

#### Scenario: `WIOGraph` / `WIONode` / `GraphExecutor` erased-type casts replaced by pattern match over sealed `WIONode`

**Given** `WIOGraph.toWIO` and `GraphExecutor` cast node outputs (`node.asInstanceOf[...]`, `output.asInstanceOf[O]`) when compiling the DAG
**When** the refactor pattern-matches over the sealed `WIONode` variants (each variant carries its concrete types) and threads the output type without a cast
**Then** the compiled `WIO` / executed `IO[Out]` behaves identically to the pre-refactor path for every graph shape, and no `asInstanceOf` remains

#### Scenario: Edge case — unknown schema type falls back to `StringSchema`

**Given** a `parameters` JSON with a field whose `"type"` is not `integer`/`number`/`boolean`
**When** the refactored dispatch runs
**Then** it produces `StringSchema` (the existing default branch), matching pre-refactor behavior — no `ClassCastException`

### Requirement: Re-enable `Wart.AsInstanceOf` in build.sbt

The build SHALL remove `Wart.AsInstanceOf` from the `filterNot` exclusion list in both wartremover blocks.

**Given** the `build.sbt` comment documents `AsInstanceOf` as temporarily excluded (`Tool.scala`, `ToolInfer.scala`, `ToolSchema.scala`)
**When** all `asInstanceOf` sites are refactored
**Then** `Wart.AsInstanceOf` is removed from both `filterNot` blocks and the comment line is deleted

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.AsInstanceOf` is removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: Tool schema generation is byte-identical pre- and post-refactor

**Invariant**: For all `parameters: ujson.Value` representing a tool parameter schema, `refactoredBuildObjectSchema(parameters) == originalBuildObjectSchema(parameters)` (reference oracle captured before refactor).

**Generator strategy**: Constructive `Gen` over `ujson.Obj` with `"type"` ∈ {`"integer"`, `"number"`, `"boolean"`, `"string"`, `"array"`, missing} and `"description"` / `"required"` fields. Label by `type` value.

```
forAll { (params: ujson.Value) =>
  refactoredBuildObjectSchema(params) === referenceBuildObjectSchema(params)
}
```

### Property: Product decode agrees with reference for well-formed and malformed JSON

**Invariant**: For all product types `I` with a `Mirror.ProductOf[I]` and all `json: ujson.Value`, `refactoredDecodeProduct[I](json) == referenceDecodeProduct[I](json)` (both `Right` with equal values, or both `Left` with the same message).

**Generator strategy**: Use the `structured-llm-test-models` generated types (e.g. `Resume`) as the product; generate JSON via a constructive `Gen` that produces both well-formed objects (all required fields present with matching types) and malformed objects (missing required fields, wrong types, trailing commas). Label `wellFormed` / `missingField` / `wrongType` / `malformed`.

```
forAll { (json: ujson.Value) =>
  refactoredDecodeProduct[Resume](json) === referenceDecodeProduct[Resume](json)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `x.asInstanceOf[T]` in a wart-enabled module main source | `AsInstanceOf` violation | `sbt compile` fails if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No `asInstanceOf` in main sources | Requirement 1 | static rule (WartRemover `Wart.AsInstanceOf`) | build.sbt |
| Tool schema generation byte-identical | Property 1 / Scenario 1 | Hedgehog property (reference oracle) | AsInstanceOfSpec |
| Product decode agrees with reference | Property 2 / Scenario 3 | Hedgehog property (reference oracle) | AsInstanceOfSpec |
| WIOGraph compile/execute behavior preserved | Scenario 4 | scenario tests (existing `WIOGraphTest`, `WIORunnableNodeTest`, `WIONodeModifierTest` unchanged) | existing tests |
| Unknown type falls back to `StringSchema` | Scenario 5 | scenario test | AsInstanceOfSpec |
| No new concept introduced (ToolDispatchResult withdrawn) | Concepts Introduced | concept delta check at apply Step 12 | concept-inventory.md |
| `Wart.AsInstanceOf` removed from exclusion list | Requirement 2 | build metadata review + `sbt compile` | build.sbt |
| Adversarial review of dispatch redesign | Verification Strategy Ring 8 | adversarial spec-compliance review | review record |
