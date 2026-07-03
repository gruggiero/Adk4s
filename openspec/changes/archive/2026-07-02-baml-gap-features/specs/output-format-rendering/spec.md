# Spec: Configurable Output Format Rendering

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `OutputFormatOptions` | case class | Configurable rendering: prefix, unionSeparator, hoistClasses, quoteClassFields, enumValuePrefix, alwaysHoistEnums, hoistedClassPrefix |
| `HoistStrategy` | enum | Auto / All / None / Subset(classes: List[String]) |
| `MapStyle` | enum | Inline / Verbose |
| `renderOutputFormat` | method | Renders Smithy IDL with configurable options |

## ADDED Requirements

### Requirement: Configurable schema-to-prompt rendering

The system SHALL render Smithy IDL schemas into prompt text with configurable options for class hoisting, field quoting, enum prefixing, and map style.

**Given** a `Schema[A]` and `OutputFormatOptions`
**When** `renderOutputFormat(schema, options)` is called
**Then** the output text reflects all configured options

**Rationale**: Different LLMs respond better to different prompt formats. BAML's configurable renderer improves LLM accuracy.

#### Scenario: Default rendering matches current format

**Given** `OutputFormatOptions()` with defaults
**When** rendering a schema
**Then** the output matches the current `outputFormatBlock` format

#### Scenario: Hoist all classes

**Given** `OutputFormatOptions(hoistClasses = HoistStrategy.All)` and a schema with nested structures
**When** rendering
**Then** all class definitions are hoisted to the top, with forward references in the main structure

#### Scenario: Quote class fields

**Given** `OutputFormatOptions(quoteClassFields = true)`
**When** rendering a schema with fields `name: String, age: Int`
**Then** the output shows `"name": String, "age": Int` with quoted field names

### Requirement: Recursive type handling

The system SHALL detect recursive type references in schemas and use forward references instead of infinite recursion.

**Given** a schema with a recursive type (e.g., `Tree` with `children: List[Tree]`)
**When** `renderOutputFormat` is called
**Then** the recursive reference is rendered as a forward reference, not inlined

#### Scenario: Self-referential type

**Given** a `Tree` schema with `children: List[Tree]`
**When** rendered
**Then** the `children` field references `Tree` by name, not by inlining the definition

## Properties (Ring 3)

### Property: Default rendering is backward compatible

**Invariant**: `renderOutputFormat(schema, OutputFormatOptions())` produces output identical to the current `outputFormatBlock`.

**Generator strategy**: Fixed set of existing schemas (from Smithy test models), no random generation.

```
forAll { (schema: Schema[A]) =>
  renderOutputFormat(schema, OutputFormatOptions()) == currentOutputFormatBlock(schema)
}
```

### Property: Hoist All produces no inline class definitions

**Invariant**: When `hoistClasses = HoistStrategy.All`, no class definition appears inline within another class.

**Generator strategy**: `Gen` of schemas with 1-5 nested structures, classify by nesting-depth.

```
forAll { (schema: Schema[A]) =>
  val rendered = renderOutputFormat(schema, OutputFormatOptions(hoistClasses = HoistStrategy.All))
  !containsInlineClassDef(rendered)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Default rendering backward compatible | Requirement 1 | Hedgehog property | OutputFormatSpec |
| Hoist All hoists all classes | Requirement 1 | Hedgehog property | OutputFormatSpec |
| Quote fields option works | Requirement 1 | Scenario test | OutputFormatSpec |
| Recursive types use forward refs | Requirement 2 | Scenario test | OutputFormatSpec |
