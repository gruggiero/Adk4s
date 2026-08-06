# Spec: Unicode Quote Normalization (Delta)

<!-- DELTA spec for the migrate-json-codec change. RE-VERIFIES the existing
     Unicode smart-quote normalization scenarios hold under the new
     tolerant-parse pipeline (JsonishParser → TypeCoercer). This spec's own
     concern — raw-text normalization before parsing — is orthogonal to and
     unaffected by the regex-cleanup removal, but its test oracle is re-run
     against the new pipeline as a compatibility gate. No new types are
     introduced. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| schema-aligned-parser | Unicode quote normalization runs before `JsonishParser.parse` in the new pipeline; the normalization step itself is unchanged | `openspec/concepts/schema-aligned-parser.md` |

No change to the `schema-aligned-parser.md` concept file — Unicode normalization is a pre-parse step whose position in the pipeline is preserved (it runs before `JsonishParser`, as it ran before the old `fixQuotes`).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `JsonishParser` | object (`parse: String => JsonishValue`) | `org.adk4s.structured.sap` |
| `JsonishValue` | enum | `org.adk4s.structured.sap` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | No new types; this spec re-verifies existing scenarios against the new pipeline |

## MODIFIED Requirements

### Requirement: Unicode smart quote normalization

The system SHALL normalize Unicode smart quotes (U+2018–U+201F) to standard ASCII quotes before JSON parsing, ensuring that LLM responses containing typographic quotes are parsed correctly. The normalization SHALL run BEFORE `JsonishParser.parse` in the new tolerant-parse pipeline (the same position it held before the old `fixQuotes` step). The normalization logic itself is UNCHANGED — only its position relative to the new parser is verified.

**Given** an LLM response containing Unicode smart quotes (`"` `"` `'` `'` `„` `‟`)
**When** the Schema-Aligned Parser processes the response (now via normalize → `JsonishParser.parse` → `TypeCoercer.coerce`)
**Then** all Unicode smart double quotes are replaced with `"` and all Unicode smart single quotes are replaced with `'` before `JsonishParser.parse` processes the string, and the final parsed result is `Success` with the expected field values

**Rationale**: (Base spec rationale unchanged — LLMs sometimes produce smart quotes which break JSON parsing.) This delta re-verifies that the normalization step continues to work when the downstream parser changes from `fixQuotes`-regex to `JsonishParser`. The normalization is orthogonal to the regex-cleanup removal: it operates on raw text before any parsing attempt, and `JsonishParser`'s quote-state-tracking scanner handles the normalized ASCII quotes correctly.

#### Scenario: Smart double quotes in JSON values

**Given** a JSON response `{"name": "John "Doc" Smith"}`
**When** `SchemaAlignedParser.parse` processes the response (normalize → `JsonishParser.parse` → `TypeCoercer.coerce`)
**Then** the result is `Success` with `name` = `John "Doc" Smith`

#### Scenario: Smart single quotes in JSON values

**Given** a JSON response `{"label": "it's a test"}`
**When** `SchemaAlignedParser.parse` processes the response (normalize → `JsonishParser.parse` → `TypeCoercer.coerce`)
**Then** the result is `Success` with `label` = `it's a test`

#### Scenario: No smart quotes — no change

**Given** a JSON response with only ASCII quotes
**When** `SchemaAlignedParser.parse` processes the response (normalize → `JsonishParser.parse` → `TypeCoercer.coerce`)
**Then** the result is identical to parsing without normalization (the normalization step is a no-op on ASCII-only input)

#### Scenario: Smart quotes AND apostrophes in the same value

**Given** a JSON response `{"note": "it's "fine", isn't it"}`
**When** `SchemaAlignedParser.parse` processes the response (normalize → `JsonishParser.parse` → `TypeCoercer.coerce`)
**Then** the smart quotes are normalized to ASCII, the apostrophes are preserved by `JsonishParser`'s quote-state-tracking scanner, and the result is `Success` with `note` = `it's "fine", isn't it`

## Properties (Ring 3)

### Property: Unicode quote normalization is idempotent

**Invariant**: Normalizing an already-normalized string produces the same string.

**Generator strategy**: `Gen.string(Gen.char, Range.linear(0, 100))` — arbitrary strings, classify by contains-unicode-quotes vs not. (Unchanged from base spec.)

```
forAll { (s: String) =>
  val once = normalizeUnicodeQuotes(s)
  normalizeUnicodeQuotes(once) == once
}
```

### Property: No ASCII quotes are modified

**Invariant**: Characters `"` (U+0022) and `'` (U+0027) are never added, removed, or modified by normalization beyond replacing Unicode quotes.

**Generator strategy**: `Gen.string(Gen.char, Range.linear(0, 100))` — classify by ASCII-quote-count. (Unchanged from base spec.)

```
forAll { (s: String) =>
  val normalized = normalizeUnicodeQuotes(s)
  normalized.count(_ == '"') == s.count(_ == '"') + s.count(c => c == '\u201C' || c == '\u201D' || c == '\u201E' || c == '\u201F')
}
```

### Property: Normalization + JsonishParser preserves apostrophe-containing values

**Invariant**: For any JSON string value containing ASCII apostrophes (after Unicode normalization), `normalize → JsonishParser.parse` preserves the apostrophes unchanged.

**Generator strategy**: `genStringWithApostrophes` (from the type-aware-sap-coercion spec) embedded in a JSON object; classify by apostrophe-count bucket. This is the compatibility gate: it verifies the normalization step does not interfere with `JsonishParser`'s apostrophe-preservation.

```
forAll { (s: String) =>
  val json = s"""{"note": "$s"}"""
  val normalized = normalizeUnicodeQuotes(json)
  val parsed = JsonishParser.parse(normalized)
  parsed == JsonishValue.Obj(Map("note" -> JsonishValue.Str(s, Complete)), Complete)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Unicode quotes normalized before JsonishParser | Requirement: Unicode smart quote normalization + Scenario: Smart double quotes in JSON values | scenario test (re-run against new pipeline) | UnicodeQuoteSpec |
| Normalization is idempotent | Property: Unicode quote normalization is idempotent | Hedgehog property (unchanged from base spec) | UnicodeQuoteSpec |
| ASCII quotes preserved | Property: No ASCII quotes are modified | Hedgehog property (unchanged from base spec) | UnicodeQuoteSpec |
| Normalization + JsonishParser preserves apostrophes | Requirement: Unicode smart quote normalization + Scenario: Smart quotes AND apostrophes + Property: Normalization + JsonishParser preserves apostrophe-containing values | Hedgehog property (compatibility gate) | UnicodeQuoteSpec |
| Normalization runs before JsonishParser | Requirement: Unicode smart quote normalization | adversarial review (audit pipeline order in SchemaAlignedParser.parse) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `normalizeUnicodeQuotes` | function | `structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala` | unchanged logic; verified to run before `JsonishParser.parse` in the new pipeline |
