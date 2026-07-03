# Spec: Unicode Quote Normalization

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SchemaAlignedParser` | object | `org.adk4s.structured.sap` |
| `ParserConfig` | case class | `org.adk4s.structured.sap` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| None | — | No new types; extends existing `fixQuotes` method |

## ADDED Requirements

### Requirement: Unicode smart quote normalization

The system SHALL normalize Unicode smart quotes (U+2018–U+201F) to standard ASCII quotes before JSON parsing, ensuring that LLM responses containing typographic quotes are parsed correctly.

**Given** an LLM response containing Unicode smart quotes (`"` `"` `'` `'` `„` `‟`)
**When** the Schema-Aligned Parser processes the response
**Then** all Unicode smart double quotes are replaced with `"` and all Unicode smart single quotes are replaced with `'` before further JSON parsing

**Rationale**: LLMs (especially Claude) sometimes produce smart quotes in JSON output, which breaks standard JSON parsing.

#### Scenario: Smart double quotes in JSON values

**Given** a JSON response `{"name": "John "Doc" Smith"}`
**When** `SchemaAlignedParser.parse` processes the response
**Then** the result is `Success` with `name` = `John "Doc" Smith`

#### Scenario: Smart single quotes in JSON values

**Given** a JSON response `{"label": "it's a test"}`
**When** `SchemaAlignedParser.parse` processes the response
**Then** the result is `Success` with `label` = `it's a test`

#### Scenario: No smart quotes — no change

**Given** a JSON response with only ASCII quotes
**When** `SchemaAlignedParser.parse` processes the response
**Then** the result is identical to parsing without normalization

## Properties (Ring 3)

### Property: Unicode quote normalization is idempotent

**Invariant**: Normalizing an already-normalized string produces the same string.

**Generator strategy**: `Gen.string(Gen.char, Range.linear(0, 100))` — arbitrary strings, classify by contains-unicode-quotes vs not.

```
forAll { (s: String) =>
  val once = normalizeUnicodeQuotes(s)
  normalizeUnicodeQuotes(once) == once
}
```

### Property: No ASCII quotes are modified

**Invariant**: Characters `"` (U+0022) and `'` (U+0027) are never added, removed, or modified by normalization beyond replacing Unicode quotes.

**Generator strategy**: `Gen.string(Gen.char, Range.linear(0, 100))` — classify by ASCII-quote-count.

```
forAll { (s: String) =>
  val normalized = normalizeUnicodeQuotes(s)
  normalized.count(_ == '"') == s.count(_ == '"') + s.count(c => c == '\u201C' || c == '\u201D' || c == '\u201E' || c == '\u201F')
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Unicode quotes normalized before parsing | Requirement 1 | Scenario test | UnicodeQuoteSpec |
| Normalization is idempotent | Property 1 | Hedgehog property | UnicodeQuoteSpec |
| ASCII quotes preserved | Property 2 | Hedgehog property | UnicodeQuoteSpec |
