# Proposal: Migrate JSON Codec Usage Off `ujson` in ADK4S-Owned Code

## Why

`ujson`/`upickle` are used throughout ADK4S, but they are **not a declared
dependency anywhere in the build** — they arrive transitively from
`org.llm4s:core:0.3.4`'s own `org.llm4s.toolapi` surface. That surface is
legitimately ujson-typed and ADK4S must keep speaking it there. The problem is
that ADK4S also uses `ujson.Value` for its **own** persisted and public data —
`InterruptSignal` checkpoint state, `Retriever.Document.metadata`, the
`adk4s-optimize` `Demo` few-shot record, and `adk4s-eval`'s `TraceEntry` /
`EvaluationResult` — none of which need to interoperate with llm4s at all.

This has concrete costs, established by direct code reading and by running the
parser against representative input during this session's review:

1. **Undeclared dependency in public signatures** (`Demo`, `TraceEntry`,
   `Dataset.fromJsonl`) — an llm4s upgrade that reshapes upickle usage breaks
   ADK4S's own type signatures with no `Dependencies.scala` line to point at.
   It also quietly defeats the Ring 2 purity rule in `build.sbt` that says
   `adk4s-eval`/`adk4s-optimize` "MUST NOT depend on … the llm4s LLM client" —
   true by import, false by dependency graph.
2. **Mutable AST held in compared/persisted values** — `ujson.Obj`/`Arr` wrap
   `LinkedHashMap`/`mutable.ArrayBuffer` with an in-place `update`. This
   contradicts the project's own "immutable data, no mutable variables" rule
   and is held by `InterruptSignal.Stateful`/`.Composite` (checkpointed,
   compared by `equals`) and by `Demo`, whose own scaladoc incorrectly claims
   "plain immutable data."
3. **`ujson.Num(Double)`-only numbers** silently truncate `Long` values above
   2^53 in `ToolSchema`/`ToolInfer`'s hand-rolled encode/decode.
4. **A live parsing bug**: `SchemaAlignedParser`'s regex-based quote-fixing
   (`fixQuotes`) runs on every candidate, including already-valid JSON, and
   corrupts any string value containing two apostrophes. Verified this session:
   `{"note": "it's fine, isn't it"}` — syntactically valid JSON — is rewritten
   to `{"note": "it"s fine, isn"t it"}` and fails to parse. This is not a
   contrived case; it is ordinary English LLM prose.
5. **A half-built replacement already exists and is disconnected.** The
   `type-aware-sap-coercion` spec (already merged into `openspec/specs/`)
   describes exactly the fix for (4): a `JsonishValue` tolerant-parse AST +
   `TypeCoercer` schema-driven coercion, BAML-style. The ADT
   (`JsonishValue`), `CoercionScore`, and `EnumMatching` exist in
   `structured-llm/src/main/scala/org/adk4s/structured/sap/`, but
   `TypeCoercer.parseAndCoerce` is a literal pass-through stub:
   `(response, Vector.empty)`. There is no function anywhere that parses a raw
   string into `JsonishValue`. `SchemaAlignedParser` never calls the coercer.
   The spec's own `BamlValueWithFlags[A]`, `ParsingContext`, and union-variant
   selection are documented but never implemented.
6. **Hand-rolled tool schema derivation** (`ToolInfer`, `ToolSchema.derive`)
   uses four `asInstanceOf` casts (against the project's "NEVER use
   asInstanceOf" rule), supports only six primitive types +
   `Option[primitive]` (no nested case classes, no collections, no enums), has
   a silent `case other => ujson.Str(other.toString)` fallback, and discards
   `ToolSchemaError`'s own rich cases (`MissingRequiredField`, `TypeMismatch`,
   `InvalidEnumValue`, each carrying a `path`) in favor of a flattened
   `getMessage` string — precisely the class of defect this project's own
   Ring 8 adversarial review exists to catch.
7. `smithy4s.Document` + jsoniter-scala are **already declared, first-class
   dependencies** (via `smithy4s-json`), immutable, exact on `Long`/`BigDecimal`,
   and the fastest JSON backend available to the project per current
   benchmarks (jsoniter ~0.13µs vs upickle ~0.38µs, objektwerks/json,
   Dec 2025, Scala 3.8.0-RC3). Nothing needs to be added to the build to fix
   any of the above.

## What Changes

This change:

- Declares `upickle`/`ujson` explicitly in `Dependencies.scala` wherever used,
  closing the undeclared-transitive-dependency gap (item 1).
- Introduces `org.adk4s.core.json.JsonValue` as a type alias over
  `smithy4s.Document` — ADK4S's own immutable JSON value type — and a single
  boundary adapter converting to/from `ujson.Value` at the llm4s edge. `ujson`
  remains the type at that edge; it stops being the type everywhere else.
- Migrates `InterruptSignal.Stateful`/`.Composite.state`,
  `Retriever.Document.metadata`, `adk4s-optimize`'s `Demo`, and `adk4s-eval`'s
  `TraceEntry`/`EvaluationResult` from `ujson.Value` to `JsonValue`.
- Replaces `DynamicTypeBuilder.DynamicValue.parse`'s ujson-then-hand-walk
  conversion with a direct `smithy4s.json.Json.read[Document]` parse.
- Completes the `type-aware-sap-coercion` spec's existing (but unimplemented)
  design: a real string → `JsonishValue` tolerant parser, `TypeCoercer`
  wired into `SchemaAlignedParser` as the actual decode path (replacing the
  regex-over-string cleaning pipeline that causes the apostrophe bug),
  `BamlValueWithFlags`/`ParsingContext`/union-variant selection implemented,
  and `JsonFixMiddleware`'s tool-argument repair folded into the same tolerant
  parser (it already has the correct quote-state-tracking scanner that SAP's
  regex lacks).
- Retires `ToolInfer`/`ToolSchema.derive`'s hand-rolled `Mirror`-based
  encode/decode in favor of a schema-derived codec (smithy4s `Schema[A]` +
  jsoniter), removing all four `asInstanceOf` sites, extending supported
  shapes beyond six primitives, and populating `ToolSchemaError`'s existing
  rich cases instead of discarding them.
- Fixes `adk4s-eval`'s unnecessary string round-trips (`writeJs(...).render()`
  on an already-built tree; `read[I](tree.render())` instead of
  `Value.transform`) and its misleading "malformed JSON" error when the real
  cause is a schema mismatch.

### Affected Capabilities

- `specs/json-value-model/spec.md` (**NEW**) — the `JsonValue`/`Document`
  internal type, the llm4s boundary adapter, and the architectural rule that
  `ujson.Value` may only appear in code that directly interoperates with
  `org.llm4s.toolapi`.
- `specs/agent-interrupt-resume/spec.md` (MODIFIED) — `InterruptSignal.state`
  field type migration.
- `specs/memory-retriever-bridge/spec.md` (MODIFIED) — `Document.metadata`
  field type migration.
- `specs/eval-core/spec.md` (MODIFIED) — `TraceEntry`/`EvaluationResult`
  field type migration + JSON export/import round-trip and error-reporting
  fixes.
- `specs/optimizable-surface/spec.md` (MODIFIED) — `Demo` field type
  migration.
- `specs/dynamic-type-builder/spec.md` (MODIFIED) — `DynamicValue.parse`
  internal implementation swap (signature unchanged).
- `specs/type-aware-sap-coercion/spec.md` (MODIFIED) — completes the
  already-specified `JsonishValue`/`TypeCoercer` design: real tolerant parser,
  wiring into `SchemaAlignedParser`, `BamlValueWithFlags`/`ParsingContext`/
  union-variant selection, absorption of `JsonFixMiddleware`.
- `specs/unicode-quote-normalization/spec.md` (MODIFIED) — re-verifies existing
  Unicode smart-quote scenarios hold under the new tolerant-parse pipeline
  (this spec's own concern — raw-text normalization before parsing — is
  orthogonal to and unaffected by the regex-cleanup removal, but its test
  oracle is re-run against the new pipeline as a compatibility gate).
- `specs/structured-toolcall/spec.md` (MODIFIED) — `ToolSchema[A]` derivation
  and `ToolSchemaError` population change from hand-rolled to schema-derived.

### Out of Scope

- **`AdkToolInfo.parameters: ujson.Value`** stays `ujson.Value`. It is JSON
  Schema metadata handed directly to `org.llm4s.toolapi.ToolFunction` /
  `toOpenAITool`; it is boundary data, not ADK4S's own persisted state.
- **`Tool` / `InvokableTool` / `StreamableTool` / `ToolsNode` / `ToolWrapper` /
  `AgentTool`'s `run(arguments: ujson.Value): F[ujson.Value]` signatures**
  stay on `ujson.Value`. They exist to call `org.llm4s.toolapi.ToolFunction`,
  which is ujson-typed throughout llm4s 0.3.4. Converting them would add
  allocation at the hottest path (every tool call) for no benefit.
- **No new third-party JSON library.** No circe, no zio-json. Everything
  needed (`smithy4s.Document`, `smithy4s-json`'s jsoniter-scala backend) is
  already a declared dependency.
- **`llm4s` itself is not modified.** This change only touches how ADK4S
  consumes it.
- **Migrating stored/example data files** (if any example JSONL fixtures embed
  ujson-shaped JSON) is not needed — `JsonValue` and `ujson.Value` both
  serialize to the same JSON text; only the in-process representation changes.

## Approach

1. **Land the boundary type first.** Add `org.adk4s.core.json.JsonValue`
   (`type JsonValue = smithy4s.Document`) plus `JsonValue <-> ujson.Value`
   conversion functions in one file, colocated with the llm4s adapter code in
   `adk4s-core`. `DynamicTypeBuilder`'s existing `ujsonValueToDocument` becomes
   the seed for the ujson→Document direction; add the inverse.
2. **Migrate the four persisted/public types** (`InterruptSignal`,
   `Retriever.Document`, `Demo`, `TraceEntry`/`EvaluationResult`) to
   `JsonValue`, updating their `derives ReadWriter`/manual JSON code to use
   smithy4s `Schema`/`Json` instead of upickle where the type itself changes.
   Each of these is behind an existing spec (agent-interrupt-resume,
   memory-retriever-bridge, eval-core, optimizable-surface) — this is a field
   type change within each, not a new capability.
3. **Fix `DynamicTypeBuilder`** — smallest, most isolated change; makes
   `structured-llm` entirely `ujson`-free (its only other reference is a
   docstring), which also cleans up `adk4s-eval`/`adk4s-optimize`'s Ring 2
   purity story once (1)-(2) land.
4. **Build the real `JsonishValue` parser and wire `TypeCoercer` into
   `SchemaAlignedParser`.** This is the largest single piece: a tolerant
   string→`JsonishValue` parser (markdown-fence extraction, structural balance
   recovery, comment stripping — reusing `SchemaAlignedParser`'s existing
   candidate-building logic where it is genuinely structural rather than
   regex-over-string), then `TypeCoercer.coerce[A](JsonishValue, Schema[A]):
   Either[ParsingError, BamlValueWithFlags[A]]` per the existing spec's
   already-written requirements (string→int, string→bool, single→array, enum
   fuzzy matching via the existing `EnumMatching`, union-variant selection via
   `tryCast`-then-full-coerce, `CoercionScore`-based candidate selection).
   `SchemaAlignedParser.parse` becomes: parse to `JsonishValue`, coerce against
   `Schema[A]`, done — no more regex mutation of candidate strings before a
   parse is even attempted. `JsonFixMiddleware`'s quote-state-tracking scanner
   (the one piece of the two duplicate repairers that is actually correct)
   becomes the AST-parser's in-string-literal tracking, and
   `JsonFixMiddleware` itself is deleted in favor of calling the shared
   tolerant parser from `ToolsNode`'s argument-repair path.
5. **Replace `ToolInfer`/`ToolSchema.derive`.** Derive both the JSON Schema
   (for `AdkToolInfo.parameters`) and the decoder/encoder from a smithy4s
   `Schema[A]` (or `JsonCodecMaker.make[A]` if a case class has no natural
   Smithy shape), matching `structured-llm`'s existing `Schema[A]` design so
   there is one schema system instead of two. Populate `ToolSchemaError`'s
   existing cases from real decode failures instead of a flattened message.
6. **Fix `adk4s-eval`'s round-trips and error reporting** — small, independent,
   can land any time after step 2.

Steps 1-3 and 6 are independent and low-risk; step 4 is the correctness-critical
core (fixes the live apostrophe bug and completes an already-specified,
half-built capability); step 5 is the largest mechanical change but the least
architecturally novel (it follows `structured-llm`'s existing `Schema[A]`
pattern). They are sequenced so each spec lands with its own Ring 3 test oracle
rather than as one monolithic PR.

## Correctness Risk Level

**Risk**: high — this change touches (a) checkpoint/interrupt persistence
format (`InterruptSignal.state`), (b) the sole decode path for every
structured LLM response in the system (`SchemaAlignedParser`), and (c) tool
argument schema derivation used by every `ToolSchema.derive` call site. A
regression in (b) or (c) silently degrades LLM tool-calling and structured-output
reliability rather than failing loudly, and a wire-format mismatch in (a) can
strand an in-flight checkpoint. Each affected spec still gets its own
per-spec risk-scoped gate; several individual pieces (dynamic-type-builder,
eval-core round-trip fixes) are low risk in isolation.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover (this change should
      *reduce* WartRemover suppressions by removing the four `asInstanceOf`
      sites in `ToolInfer`/`ToolSchema`)
- [x] Ring 2: Architecture — this change's core thesis is a layering rule
      ("`ujson.Value` only at the llm4s boundary"); add a scalafix
      `DisableSyntax`/custom regex guard (mirroring the existing `NoConfigFactory`/
      `NoSysEnv` pattern in `.scalafix.conf`) that flags `ujson.Value` outside
      `adk4s-core`'s llm4s-adapter files, `adk4s-core/component/Tool.scala`,
      `ToolsNode.scala`, `ToolWrapper`, `AgentTool`, `StructuredToolFunction`,
      `ComponentRunnable`, and `AdkToolInfo`.
- [x] Ring 3: Property-based tests — MANDATORY, no waiver. In particular:
      the `type-aware-sap-coercion` spec's existing but never-exercised
      properties (score ordering, coercion-preserves-value, union
      short-circuit) become real oracles once the coercer is wired up; a new
      property covers the apostrophe-corruption regression (any string value
      containing balanced apostrophes round-trips through parse unchanged);
      a round-trip property covers each migrated type
      (`JsonValue`-encode-then-decode == identity) for `InterruptSignal`,
      `Demo`, `TraceEntry`, `EvaluationResult`. No concurrent/streaming
      behavior is introduced by this change (parsing and encoding are pure).
- [x] Ring 4: Wire/persistence compatibility — REQUIRED. `InterruptSignal`
      checkpoint state, `EvaluationResult.toJson`/`fromJson`, and
      `Dataset.fromJsonl` all change their in-process value type while the
      wire JSON text must stay byte-for-byte compatible with what the
      pre-change code produced/consumed. Round-trip against fixtures captured
      from the current implementation before any code changes.
- [x] Ring 5: Mutation testing — Stryker4s on changed files, threshold 90%
      (pure domain logic: `JsonishValue` parser, `TypeCoercer`, `JsonValue`
      adapter) / 85% (adapters: migrated field call sites), per
      `stryker4s.conf`'s existing break/low/high thresholds.
- [x] Ring 6: Formal verification — applies to the `TypeCoercer`
      candidate-selection algorithm ("given multiple successful coercions,
      the one with the lowest `CoercionScore` wins" — a pure fold with a
      total order over scores) and the enum-fuzzy-matching escalation
      (exact → punctuation-stripped → case-insensitive → both, each a
      monotonically increasing penalty). Both have a pure kernel expressible
      in PureScala independent of `smithy4s.Schema`/`Mirror`/`IO`, following
      the VERIFIED-MIRROR pattern already used by `adk4s-optimize`'s
      `PredictorKernel`. `JsonValue`/`Document` itself (a plain immutable ADT)
      is not a Ring 6 candidate — no decision/fold/law at its center.
- [ ] Ring 7: Model checking — not applicable; no new distributed/event-driven
      protocol is introduced (`InterruptSignal`'s existing address-routing
      protocol is unchanged, only its `state` field's value type changes).
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY. Explicitly
      worth adversarial attention: does the new `ToolSchema.derive` still have
      a silent stringly-typed fallback anywhere; does the tolerant parser ever
      silently drop a coercion flag instead of surfacing `ParsingError`; does
      the `JsonValue` boundary adapter round-trip every `Document` variant
      (including `DNull`) without loss.
- [ ] Ring 9: Telemetry — not applicable; no telemetry stack (otel4s) is
      present per `openspec/capability-profile.md`.

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract | Justification |
|------|------|------|
| `specs/json-value-model/spec.md` | Full | New domain type (`JsonValue`) + new boundary-adapter service methods |
| `specs/agent-interrupt-resume/spec.md` | Full | `InterruptSignal.state` field type is a persistence/serialization change |
| `specs/memory-retriever-bridge/spec.md` | Full | `Document.metadata` field type is a public API signature change |
| `specs/eval-core/spec.md` | Full | `TraceEntry`/`EvaluationResult` field type + JSON export/import format change |
| `specs/optimizable-surface/spec.md` | Full | `Demo` field type is a public API signature change |
| `specs/dynamic-type-builder/spec.md` | Minimal | Pure internal refactor — `DynamicValue.parse`'s signature and behavior are unchanged, only its parsing implementation |
| `specs/type-aware-sap-coercion/spec.md` | Full | New service methods (jsonish parser, `TypeCoercer.coerce`), evaluator logic change (SAP's decode path) |
| `specs/unicode-quote-normalization/spec.md` | Minimal | No new types; existing scenarios re-verified against the new pipeline |
| `specs/structured-toolcall/spec.md` | Full | `ToolSchema.derive`'s derivation strategy and `ToolSchemaError` population are an error-algebra and API-signature change |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `smithy4s.Document` | sealed trait (ADT: `DNumber`, `DString`, `DBoolean`, `DArray`, `DObject`, `DNull`) | `smithy4s` | Becomes the RHS of the new `JsonValue` type alias; already used by `DynamicTypeBuilder` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` | jsoniter-scala-backed JSON I/O for `Document` and any `Schema[A]` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | Reused as the target for the new `ToolSchema.derive` replacement, unifying the two schema systems |
| `JsonishValue` | enum (`Null`, `Bool`, `Num`, `Str`, `Arr`, `Obj`, `Markdown`, `AnyOf`) | `org.adk4s.structured.sap` | Already exists; this change adds the string→`JsonishValue` parser that produces it |
| `CompletionState` | enum | `org.adk4s.structured.sap` | Already exists; used by the new parser |
| `CoercionScore` | trait | `org.adk4s.structured.sap` | Already exists; becomes load-bearing once `TypeCoercer` is wired up |
| `CoercionFlag` | sealed trait | `org.adk4s.structured.sap` | Already exists |
| `EnumMatching` | object | `org.adk4s.structured.sap` | Already exists; called from the completed `TypeCoercer` |
| `ToolSchemaError` | sealed trait (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) | `org.adk4s.core.tools` | Already exists; this change makes the decoder actually produce the specific cases |
| `InterruptSignal` | sealed trait (derives ReadWriter) | `org.adk4s.core.interrupt` | Field-type migration only; ADT shape and routing protocol unchanged |
| `Retriever.Document` | case class | `org.adk4s.core.component` | Field-type migration only. **Naming note**: collides with `smithy4s.Document` — always referenced as `org.adk4s.core.component.Document` or via the new `JsonValue` alias to avoid ambiguity |
| `Demo` | case class | `org.adk4s.optimize` | Field-type migration only |
| `TraceEntry` / `EvaluationResult[I, O]` | case class | `org.adk4s.eval` | Field-type migration + export/import fixes |
| `PredictorKernel` | PureScala model (Ring 6) | `org.adk4s.verified` | Precedent VERIFIED-MIRROR pattern this change follows for the `TypeCoercer` scoring algorithm |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `org.adk4s.core.json.JsonValue` | type alias (`= smithy4s.Document`) | ADK4S's own internal, immutable JSON value type — replaces `ujson.Value` everywhere except the llm4s boundary |
| `JsonValueCodec` (name TBD in design) | object (`toUjson: JsonValue => ujson.Value`, `fromUjson: ujson.Value => JsonValue`) | The single, explicit conversion point at the llm4s boundary |
| jsonish string parser (name TBD in design, e.g. `JsonishParser.parse`) | function `String => JsonishValue` | Tolerant parse producing the ambiguity-preserving AST; replaces `SchemaAlignedParser`'s regex-cleaning candidate pipeline |
| `TypeCoercer.coerce[A]` | typeclass method | `(JsonishValue, ParsingContext, Schema[A]) => Either[ParsingError, BamlValueWithFlags[A]]` — completes the stubbed `parseAndCoerce` |
| `BamlValueWithFlags[A]` | case class | `(value: A, flags: Vector[CoercionFlag], score: CoercionScore)` — already named in the `type-aware-sap-coercion` spec, not yet implemented |
| `ParsingContext` | case class | Path/config/depth context threaded through coercion, per the existing spec |
| schema-derived tool codec (name TBD in design) | derivation (smithy4s `Schema[A]`-based, replacing `ToolInfer`) | JSON Schema + decoder + encoder from one source, supporting nested types/collections/enums beyond the current six primitives |

## Risks and Mitigations

- **Wire-format drift during migration** (Ring 4 concern): capture JSON
  fixtures from the *current* `ujson`-based encoders for `InterruptSignal`,
  `EvaluationResult`, and `Demo` before any code changes, and assert the new
  `JsonValue`-based encoders produce byte-identical (or semantically
  equivalent, whitespace aside) output against those fixtures.
- **`SchemaAlignedParser` behavior regression**: the existing
  `SchemaSamplesParsingSuite` (21 cases) and `SchemaAlignedParserUnitTest`
  must continue to pass unchanged — the `type-aware-sap-coercion` spec's own
  "Backward compatibility" requirement already commits to this; treat it as a
  regression gate, not just an aspiration.
- **`Document`/`Document` naming collision** between `smithy4s.Document` and
  `org.adk4s.core.component.Document` (the `Retriever` result type): resolved
  by the `JsonValue` alias — no code outside the adapter file should import
  `smithy4s.Document` directly.
- **Scope creep**: this proposal already spans 8 spec files. Each is
  implemented and gated independently per its own tasks/design artifacts
  (steps 1-3 and 6 can each land as an independent PR); step 4
  (`type-aware-sap-coercion`) and step 5 (`structured-toolcall`) are the two
  pieces large enough to warrant their own design review before implementation
  starts.
