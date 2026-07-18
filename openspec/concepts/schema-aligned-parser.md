# Concept: SchemaAlignedParser

## Concept specification

```
concept SchemaAlignedParser
purpose
    Lenient JSON parser that recovers from common LLM output defects
    (markdown fences, trailing commas, single quotes, unquoted keys,
    comments, truncation, unicode smart quotes, enum mismatch, type
    coercion) and decodes the result into a typed A via smithy4s.
state
    # pure computation — no state
actions
    parse [ response: String ] (using Schema[A])
        => [ result: ParseResult.Success(value, warnings) ]
    parse [ response ]
        => [ result: ParseResult.Failure([JsonSyntaxError("Failed to parse JSON after recovery attempts", recoveryAttempted=true)]) ]
    parseWithConfig [ response ; config: ParserConfig ]
        => [ ParseResult[A] ]   # strictMode bypasses recovery
operational principle
    Given an LLM response, the parser builds a list of candidate strings
    (markdown-fenced blocks, JSON segments, aggregated segments, whole
    response, JSON-string-decoded response), applies cleaning and recovery
    strategies in order, deduplicates, and tries smithy4s decoding on each
    candidate until one succeeds. Warnings accumulate from each applied
    recovery.
```

## Recovery strategies (as actually implemented)

1. Markdown fence extraction — `extractMarkdownCodeBlocks` (regex ` ```...``` `)
2. Unicode smart quote normalization — `UnicodeQuoteNormalizer.normalize`
3. Comment removal — `removeComments` (single-line `//` and multi-line `/* */`)
4. Quote/key fixing — `fixQuotes` (single→double quotes, unquoted keys)
5. Trailing comma removal — `removeTrailingCommas`
6. Close unbalanced — `applyCloseUnbalanced` (adds missing `}`/`]`/`"`)
7. Insert missing commas — `applyInsertMissingCommas`
8. Fill missing values — `applyFillMissingValues` (nulls)
9. Coerce numeric strings — `applyCoerceNumericStrings`
10. Trim trailing garbage — `applyTrimTrailingGarbage`
11. Unwrap Smithy `member` list wrapper — `applyUnwrapSmithyMember`
12. Enum matching — `EnumMatching.matchEnum` (exact, case-insensitive, punctuation-stripped)
13. Type coercion — `TypeCoercer.coerceToJson` (String→Int/Bool/Double, single→array)

## Implementation map

| Element | Code |
|---|---|
| object `SchemaAlignedParser` | `object SchemaAlignedParser` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| action `parse` | `SchemaAlignedParser.parse[A: Schema](response): ParseResult[A]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| action `parseWithConfig` | `SchemaAlignedParser.parseWithConfig[A: Schema](response, config): ParseResult[A]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| action `buildCandidates` | `SchemaAlignedParser.buildCandidates(response): List[Candidate]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| action `attemptCandidates` | `SchemaAlignedParser.attemptCandidates[A](candidates, smithySchema): ParseResult[A]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `extractMarkdownCodeBlocks` | `SchemaAlignedParser.extractMarkdownCodeBlocks(text): List[String]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `removeComments` | `SchemaAlignedParser.removeComments(text): (String, Boolean)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `fixQuotes` | `SchemaAlignedParser.fixQuotes(text): (String, Boolean)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `removeTrailingCommas` | `SchemaAlignedParser.removeTrailingCommas(text): (String, Boolean)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `tryRecovery` | `SchemaAlignedParser.tryRecovery(json): Option[(String, List[String])]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `applyCloseUnbalanced` | `SchemaAlignedParser.applyCloseUnbalanced(json): (String, Boolean, String)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `applyInsertMissingCommas` | `SchemaAlignedParser.applyInsertMissingCommas(json)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `applyFillMissingValues` | `SchemaAlignedParser.applyFillMissingValues(json)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `applyCoerceNumericStrings` | `SchemaAlignedParser.applyCoerceNumericStrings(json)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `applyTrimTrailingGarbage` | `SchemaAlignedParser.applyTrimTrailingGarbage(json)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| recovery `applyUnwrapSmithyMember` | `SchemaAlignedParser.applyUnwrapSmithyMember(json)` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| helper `UnicodeQuoteNormalizer` | `UnicodeQuoteNormalizer.normalize(input): String` (`structured-llm/src/main/scala/org/adk4s/structured/sap/UnicodeQuoteNormalizer.scala`) |
| helper `EnumMatching` | `EnumMatching.matchEnum(input, enumValues): Option[(String, Vector[CoercionFlag])]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/EnumMatching.scala`) |
| helper `TypeCoercer` | `TypeCoercer.coerceToJson(value: JsonishValue): (String, Vector[CoercionFlag])` (`structured-llm/src/main/scala/org/adk4s/structured/sap/TypeCoercer.scala`) |
| helper `tryParseWithSmithy` | `SchemaAlignedParser.tryParseWithSmithy[A](jsonString, smithySchema, warnings): ParseResult[A]` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) |
| runtime host | `org.adk4s.structured.sap` |

## Deviations from the pattern

- `TypeCoercer.parseAndCoerce` is a stub: it returns the response as-is with a comment "the JsonishValue layer will be fully integrated in a future iteration" — `JsonishValue`, `EnumMatching`, and `CoercionScore` are defined but not actually wired into the main parse pipeline (`structured-llm/src/main/scala/org/adk4s/structured/sap/TypeCoercer.scala`).
- `tryRecovery` applies all recovery steps sequentially and aggregates changes; there is no rollback if an early step corrupts the JSON for later steps (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`).
- The final failure error is a single `JsonSyntaxError("Failed to parse JSON after recovery attempts", recoveryAttempted=true)` — the individual candidate errors are discarded, so callers cannot see which candidates were tried (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`).
- `removeComments` uses a regex `(?<!:)//[^\n]*` to avoid stripping `//` after colons, but this still strips `//` inside string values — comments inside JSON strings are corrupted (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`).
