# Inventory Check

<!-- Per-change verification report against the PROJECT concept inventory
     (openspec/concept-inventory.md — the living document; see
     templates/concept-inventory.md for its format). This report is what
     gets archived with the change; the inventory itself never is.

     Cases:
     - Project inventory missing → it was CREATED by this change via the
       multi-module semantic scanner (say so, note scanner vs manual scan).
     - Project inventory exists → it was VERIFIED (consistency check below);
       stale rows were fixed PRESERVING their provenance column — never
       re-created from scratch (a fresh scan loses which spec introduced
       each concept). -->

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-02
**Consistency check**: 1 stale row fixed (listed below), 1 registry STALE row fixed

The project inventory was verified against the current source by spot-checking
every concept this change touches (`InterruptSignal`, `Document`, `Demo`,
`TraceEntry`, `EvaluationResult`, `JsonishValue`, `CoercionFlag`,
`ToolSchemaError`, ` smithy4s.Document`, `smithy4s.json.Json`, `Schema[A]`,
`EnumMatching`, `PredictorKernel`). Package paths, field types, and variant
lists were cross-checked against the actual source files. One stale row was
found and fixed; the rest matched.

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `ToolSchemaError` | variants: `MissingRequiredField`, `TypeMismatch` (2) | variants: `MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed` (4) | pre-existing (provenance unchanged) |

**Evidence**: `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala`
lines 208-246 define four case classes extending `ToolSchemaError`:
`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`.
The inventory listed only the first two — a stale row from an earlier scan.
This is directly relevant to this change: the proposal's item 5 commits to
populating `ToolSchemaError`'s "existing rich cases" instead of discarding
them, and the `type-aware-sap-coercion` / `structured-toolcall` specs will
exercise all four variants. The corrected variant list is the contract those
specs will test against.

No other stale rows were found in the concepts this change touches. Verified
field types for the migration targets:

- `InterruptSignal.Stateful.state: ujson.Value` — confirmed at
  `InterruptSignal.scala:23` (migration target → `JsonValue`)
- `InterruptSignal.Composite.state: ujson.Value` — confirmed at
  `InterruptSignal.scala:33` (migration target → `JsonValue`)
- `Document.metadata: Map[String, ujson.Value]` — confirmed at
  `Retriever.scala:8-12` (migration target → `Map[String, JsonValue]`)
- `Demo.input: ujson.Value, output: ujson.Value` — confirmed at
  `Demo.scala:11` (migration target → `JsonValue`)
- `TraceEntry.input: ujson.Value, output: ujson.Value` — confirmed at
  `TraceEntry.scala:17` (imports `ujson.Value`; migration target → `JsonValue`)
- `InterruptResult.data: ujson.Value` — confirmed in inventory line 134
  (OUT OF SCOPE per proposal — `InterruptResult` is human-provided data at the
  llm4s boundary, stays `ujson.Value`)
- `AdkToolInfo.parameters: ujson.Value` — confirmed in inventory line 114
  (OUT OF SCOPE per proposal — JSON Schema metadata handed to
  `org.llm4s.toolapi.ToolFunction`, stays `ujson.Value`)

## Behavioral Concepts (registry pass)

**registry-check.sh**: `OK (697 implementation-map tokens verified, 0 spec concept references checked, 2 weak binding(s) to tighten)` — run on 2026-08-02 after fixing the eval-harness STALE row (see below). The 2 WEAK rows are pre-existing in `react-agent.md` (`isDefined`, `foreach` not in the cited `ReactAgent.scala` but exist elsewhere); they are NOT caused by this change and are not blocking.

**Stale implementation-map rows**: 1 fixed (pre-existing, not caused by this change but relevant to it):

- `eval-harness.md` row "mean aggregate" — the backtick-quoted code snippet
  `rows.map(_.score.value).sum / rows.size` contained `/` and was
  misinterpreted by `registry-check.sh` as a file path (false-positive STALE).
  Rephrased to `rows.map(_.score.value).sum` divided by `rows.size` with a
  file citation `(`Evaluate.scala`)`. The code at `Evaluate.scala:126` was
  unchanged; only the registry's phrasing was fixed. This was a pre-existing
  issue from the `8b0316f` commit ("adk4s-eval: fix resource leaks..."), fixed
  here because this change touches `eval-core` (`TraceEntry`/`EvaluationResult`
  field-type migration).

**Unregistered actions / syncs / state components flagged for human review**:

- **CITED existing concepts (no new behavioral concepts introduced by this
  change)**: This change is a type-migration + parser-completion change. It
  introduces no new behavioral unit — no new agent command/event, no new
  message consumer/producer, no new persisted state component. The behavioral
  concepts it touches (`interrupt-signal`, `retriever`, `eval-harness`,
  `optimizable-surface`, `schema-aligned-parser`, `tool`, `tools-node`) all
  exist in the registry and their action/state tables are unchanged by this
  change (only field *types* change, not behavior). The
  `type-aware-sap-coercion` spec completes an already-specified but
  unimplemented capability — the `schema-aligned-parser` concept file already
  documents the `TypeCoercer`/`JsonishValue` design; this change implements it,
  it does not add a new concept.
- **No new syncs**: this change introduces no new cross-concept synchronization.
  The `InterruptSignal` address-routing protocol is unchanged (only its
  `state` field's value type changes); the SAP decode path is internal to
  `schema-aligned-parser`; the tool schema derivation is internal to
  `tools-node`/`tool`.

> The registry-check `0 spec concept references checked` count is correct:
> this change has no `specs/` directory yet (the specs artifact is created
> next). Once specs exist, registry-check's pass 3 will verify every
> `Concept`/`Concept/action` cited in the specs' "Concepts Used (behavioral)"
> tables against the registry.

## Concepts relevant to THIS change

<!-- Orientation for spec authors: the inventory entries this change will
     reuse (they feed the specs' "Concepts Used" tables) and a preview of
     what it introduces (they feed "Concepts Introduced"). The specs' tables
     remain the commitments; this is a working excerpt. -->

### Reused (existing concepts the specs will cite)

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `smithy4s.Document` | sealed trait (ADT: `DNumber`, `DString`, `DBoolean`, `DArray`, `DObject`, `DNull`) | `smithy4s` | reuse — RHS of the new `JsonValue` type alias |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` | reuse — jsoniter-scala-backed JSON I/O for `Document` and `Schema[A]` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | reuse — unifies the two schema systems (SAP + tool codec) |
| `JsonishValue` | enum (`Null`, `Bool`, `Num`, `Str`, `Arr`, `Obj`, `Markdown`, `AnyOf`) | `org.adk4s.structured.sap` | reuse — already exists; this change adds the string→`JsonishValue` parser that produces it |
| `CompletionState` | enum (`Pending`, `Incomplete`, `Complete`) | `org.adk4s.structured.sap` | reuse — used by the new parser |
| `CoercionFlag` | enum (`StringToInt`, `StringToBool`, `StringToFloat`, `IntToFloat`, `FloatToInt`, `SingleToArray`, `ObjectToString`, `StrippedNonAlphaNumeric`, `DefaultFromNoValue`, `CaseInsensitive`) | `org.adk4s.structured.sap` | reuse — becomes load-bearing once `TypeCoercer` is wired up |
| `CoercionScore` | trait | `org.adk4s.structured.sap` | reuse — total order for candidate selection (Ring 6 target) |
| `EnumMatching` | object | `org.adk4s.structured.sap` | reuse — called from the completed `TypeCoercer` |
| `ToolSchemaError` | sealed trait (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) | `org.adk4s.core.tools` | reuse — this change makes the decoder actually produce the specific cases (variants list corrected in this inventory check) |
| `InterruptSignal` | sealed trait (derives ReadWriter; `Simple`, `Stateful`, `Composite`) | `org.adk4s.core.interrupt` | reuse — field-type migration only (`Stateful.state`, `Composite.state`: `ujson.Value` → `JsonValue`); ADT shape and routing protocol unchanged |
| `Document` | case class (`Retriever.Document`) | `org.adk4s.core.component` | reuse — field-type migration only (`metadata: Map[String, ujson.Value]` → `Map[String, JsonValue]`). **Naming note**: collides with `smithy4s.Document` — always referenced as `org.adk4s.core.component.Document` or via the `JsonValue` alias |
| `Demo` | case class (`input: ujson.Value, output: ujson.Value`) | `org.adk4s.optimize` | reuse — field-type migration only (→ `JsonValue`) |
| `TraceEntry` | case class (`path: String, input: ujson.Value, output: ujson.Value`) | `org.adk4s.eval` | reuse — field-type migration only (→ `JsonValue`) |
| `EvaluationResult[I, O]` | case class (`score: Double, rows: Vector[EvalRow[I, O]]`) | `org.adk4s.eval` | reuse — JSON export/import round-trip + error-reporting fixes (field types of contained `TraceEntry` change) |
| `PredictorKernel` | PureScala model (Ring 6) | `org.adk4s.verified` | reuse — precedent VERIFIED-MIRROR pattern this change follows for the `TypeCoercer` scoring algorithm |

### Introduced (new concepts this change will add to the inventory during apply)

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `org.adk4s.core.json.JsonValue` | type alias (`= smithy4s.Document`) | `org.adk4s.core.json` | introduce — ADK4S's own internal, immutable JSON value type |
| `JsonValueCodec` (name TBD in design) | object (`toUjson: JsonValue => ujson.Value`, `fromUjson: ujson.Value => JsonValue`) | `org.adk4s.core.json` | introduce — single, explicit conversion point at the llm4s boundary |
| jsonish string parser (name TBD, e.g. `JsonishParser.parse`) | function `String => JsonishValue` | `org.adk4s.structured.sap` | introduce — tolerant parse producing the ambiguity-preserving AST |
| `TypeCoercer.coerce[A]` | typeclass method `(JsonishValue, ParsingContext, Schema[A]) => Either[ParsingError, BamlValueWithFlags[A]]` | `org.adk4s.structured.sap` | introduce — completes the stubbed `parseAndCoerce` |
| `BamlValueWithFlags[A]` | case class `(value: A, flags: Vector[CoercionFlag], score: CoercionScore)` | `org.adk4s.structured.sap` | introduce — already named in the `type-aware-sap-coercion` spec, not yet implemented |
| `ParsingContext` | case class | `org.adk4s.structured.sap` | introduce — path/config/depth context threaded through coercion |
| `ParsingError` (SAP-specific, if distinct from existing `ParseError`) | sealed trait | `org.adk4s.structured.sap` | introduce — TBD in design; may reuse the existing `org.adk4s.structured.core.ParseError` |
| schema-derived tool codec (name TBD in design) | derivation (smithy4s `Schema[A]`-based, replacing `ToolInfer`) | `org.adk4s.core.tools` | introduce — JSON Schema + decoder + encoder from one source |

> These will be appended to the project inventory's main tables during the
> apply phase (Step 12) when the code lands, with provenance
> `spec:migrate-json-codec/<spec>`. They are recorded here so the specs'
> "Concepts Introduced" tables and the apply phase do not re-create them.
