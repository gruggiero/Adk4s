# Concept: Schema

## Concept specification

```
concept Schema[A]
purpose
    Typeclass bridging Smithy IDL (for prompt injection) and smithy4s
    Schema (for JSON decoding), with attached constraints.
state
    smithyDefinition: Schema[A] -> String
    description: Schema[A] -> Option[String]
    smithySchema: Schema[A] -> SmithySchema[A]
    constraints: Schema[A] -> Vector[Constraint[A]]
actions
    instance [ smithy: String ; desc: Option[String] ] (using smithySchema: SmithySchema[A])
        => [ schema: Schema[A] ]
    derived [ smithy: String ] (using smithySchema)
        => [ schema: Schema[A] ]
    withCheck [ label: String ] (predicate: A => Boolean)
        => [ schema: Schema[A] ]
    withAssert [ label: String ] (predicate: A => Boolean)
        => [ schema: Schema[A] ]
    outputFormatBlock
        => [ block: String ]   # sanitized IDL + instructions
operational principle
    A caller defines a Smithy IDL string, obtains the smithy4s Schema via
    codegen, and calls Schema.instance to produce Schema[A]. The schema's
    outputFormatBlock is appended to a prompt; after the LLM responds, the
    smithySchema decodes the JSON. withCheck/withAssert attach predicates
    evaluated by StructuredLLM.completeValidated.
```

## Implementation map

| Element | Code |
|---|---|
| opaque type `Schema` | `opaque type Schema[A] = SchemaData[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| state `SchemaData` | `private final case class SchemaData(smithyDefinition, description, smithySchema, constraints)` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| action `instance` | `Schema.instance[A](smithy, desc)(using smithySchema): Schema[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| action `derived` | `Schema.derived[A](smithy)(using smithySchema): Schema[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| action `withCheck` | `Schema.withCheck(label)(predicate): Schema[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| action `withAssert` | `Schema.withAssert(label)(predicate): Schema[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| action `outputFormatBlock` | `Schema.outputFormatBlock: String` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| helper `sanitizeSmithyForPrompt` | `Schema.sanitizeSmithyForPrompt(idl): String` rewrites `list Foo { member: Bar }` to `Bar[]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| type `ParseResult` | `enum ParseResult[+A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| type `ParseError` | `sealed trait ParseError` (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`) |
| constraint type | `Constraint[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Constraint.scala`) |
| runtime host | `org.adk4s.structured.core` |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Smithy IDL syntax accepted by `sanitizeSmithyForPrompt` | Only the `list Foo { member: Bar }` pattern is rewritten; other Smithy forms pass through unchanged. Authoritative source: the regex in `Schema.sanitizeSmithyForPrompt`. | 2026-07-18 (code) |
| `ParseError` variants | `JsonSyntaxError`, `SchemaViolation`, `MissingField`, `TypeMismatch`, `UnexpectedField` — defined in `Schema.scala`. | 2026-07-18 (code) |

## Deviations from the pattern

- `sanitizeSmithyForPrompt` only handles the `list Foo { member: Bar }` pattern; other Smithy syntax that may confuse LLMs (maps, unions with `member` keys) is not sanitized (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`).
- `outputFormatBlock` duplicates sanitization logic that also exists in `OutputFormat.sanitizeForRendering` (`structured-llm/src/main/scala/org/adk4s/structured/core/OutputFormat.scala`) — two implementations of the same rewrite.
- `withCheck` and `withAssert` differ only in strictness (`Assert` fails `completeValidated`, `Check` records a result) but both store `Constraint[A]` in the same vector — the distinction is enforced by `Constraint.evaluateStrictAll`, not by the type (`structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala`).
