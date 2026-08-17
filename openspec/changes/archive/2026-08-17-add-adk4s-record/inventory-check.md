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

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-14
**Consistency check**: 1 stale comment fixed (listed below); 2 missing type-alias rows noted for apply-phase append

The project inventory (160 typed rows across Refined/Opaque, Type Aliases,
Sealed Traits/Enums, Case Classes, Service Traits, Objects, Smithy Models,
Property Generators, and Cats Effect Resources sections) was verified against
landed source. Package paths, constraint expressions, and generator locations
were spot-checked for every concept the proposal's "Existing Concepts to
Reuse" table references. One stale section comment was corrected; no recorded
row had a wrong package or constraint. Two pre-existing type aliases the
proposal reuses (`ToolMiddleware`, `ToolEndpoint`) are absent from the Type
Aliases section and will be appended during the apply phase (Step 12).

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| "Refined / Opaque Types" section comment | "No Iron/refined library is present in the stack ... plain `opaque type` newtypes WITHOUT Iron constraints" | "Iron IS present in the stack (iron + iron-cats 3.3.2 + iron-upickle) ... `add-iron-refined-types` migrated newtypes to Iron `RefinedType`" | n/a (section comment, no provenance column) |

> The stale comment contradicted the rows immediately below it (which
> correctly describe `NodeKey` as `NonEmpty & Not[Reserved] (Iron
> RefinedType)`, etc.). It predated the `add-iron-refined-types` migration
> and was never updated. The rows themselves were accurate.

## Missing rows to append during apply (Step 12)

| Type | Underlying | Package | Reason |
|------|-----------|---------|--------|
| `ToolMiddleware` | `ToolEndpoint => ToolEndpoint` | `org.adk4s.core.tools` | Pre-existing type alias reused by this change (recording `ToolMiddleware`); absent from Type Aliases section |
| `ToolEndpoint` | `Kleisli[IO, ToolInput, ToolOutput]` | `org.adk4s.core.tools` | Pre-existing type alias that `ToolMiddleware` is defined over; absent from Type Aliases section |

> These are pre-existing types, not new concepts. They are noted here so the
> apply phase appends them with `pre-existing` provenance. The proposal's
> "Existing Concepts to Reuse" table already references `ToolMiddleware`
> correctly (`org.adk4s.core.tools`, Kleisli endomorphism).

## Behavioral Concepts (registry pass)

`openspec/concepts/` exists (35 concept files). Registry-check run:

```
registry-check: OK (803 implementation-map tokens verified, 0 spec concept
references checked, 5 weak binding(s) to tighten)
```

The 5 weak bindings are pre-existing (not introduced by this change):
- `graph.md`: `GraphCompilationError` cited in `Graph.scala` but exists elsewhere
- `tools-node.md`: `executeToolCalls`, `executeFromToolCalls`, `toolCalls`, `toolsNode` cited in `ReactAgent.scala` but exist elsewhere

No new candidate concepts or syncs are flagged for human review from this
change at the proposal stage: `adk4s-record` introduces no new command/event
enum variants, no new message consumers/producers, and no new persisted state
components in the behavioral-registry sense. The new types (`CallKey`,
`CallRecord`, `Recorder[F]`, etc.) are type-level concepts that will be
added to the inventory during apply, not behavioral concepts requiring
registry entries. If the design phase determines that `Recorder[F]` constitutes
a behavioral concept (purpose/state/actions/operational principle), a
`openspec/concepts/recorder-sink.md` file will be created at that point.

## Concepts this change REUSES (cross-referenced with inventory)

Every concept in the proposal's "Existing Concepts to Reuse" table was
verified against the inventory and landed source:

| Proposal concept | Inventory row | Verified |
|------------------|---------------|----------|
| `ChatModel[F[_]]` | Service Traits, line 219 (`org.adk4s.core.component`, methods `generate`/`stream`/`streamContent`/`withConfig`) | ✅ package + methods match `ChatModel.scala` |
| `Embedder[F[_]]` | Service Traits, line 227 (`org.adk4s.core.component`, `embed`/`embedBatch`/`dimension`) | ✅ matches `Embedder.scala` |
| `InvokableTool[F[_]]` | Service Traits, line 222 (`run(ujson.Value): F[ujson.Value]`) | ✅ matches `Tool.scala:50` |
| `ToolMiddleware` | (missing from inventory — see Missing rows above) | ✅ verified in source: `type ToolMiddleware = ToolEndpoint => ToolEndpoint`, `ToolMiddleware.scala:7` |
| `ToolInput` | Case Classes, line 143 (`name`, `arguments`, `callId`) | ✅ matches `ToolTypes.scala:11` |
| `ToolOutput` | Case Classes, line 144 (`name`, `result`, `callId`, `isError`) | ✅ matches `ToolTypes.scala:24` |
| `AdkError` | Sealed Traits, line 78 (25 variants) | ✅ 25 `extends AdkError` matches in `AdkError.scala` |
| `JsonValue` | Type Aliases, line 66 (`= smithy4s.Document`, `org.adk4s.core.json`) | ✅ matches `JsonValue.scala:25` |
| `JsonValueCodec` | Objects, line 249 (`toUjson`/`fromUjson`) | ✅ matches `JsonValueCodec.scala:42,57` |
| `NodeKey` (Iron pattern) | Refined/Opaque, line 47 (`NonEmpty & Not[Reserved]`, Iron RefinedType) | ✅ matches `NodeKey.scala` |
| `Positive`/`NonNegative` | Refined/Opaque, lines 49–50 (`numeric.Positive`/`Positive0`, Iron) | ✅ matches `NodeKey.scala` constraints |
| `ModelStep[F[_]]` | Type Aliases, line 67 (`Kleisli[F, ModelRequest[F], ModelResponse]`) | ✅ matches `ModelStep.scala:12` |
| `ModelRequest[F[_]]` | Case Classes, line 207 (`systemPrompt`, `messages`, `tools`, `options`, `state`) | ✅ matches `ModelRequest.scala:16` |
| `DeterministicChatModel` | Case Classes, line 171 (`ChatModel[IO]` double, seed-based, `RecordedRequest` trace) | ✅ matches `DeterministicChatModel.scala:51` |
| `RecordedRequest` | Case Classes, line 175 (`renderedSystemPrompt`, `messages`, `toolNames`) | ✅ matches `DeterministicChatModel.scala:29` |
| `Observation` | Case Classes, line 173 (`finalAssistant`, `finalState`, `requestTraces`, `outcome`, `≍`) | ✅ matches `SimpleHarnessLoop.scala:48` |

**Reuse conclusion**: no duplicate creation risk. Every type the proposal
wraps (`ChatModel`, `Embedder`, `InvokableTool`, `ToolMiddleware`) or
references for key computation (`ToolInput.callId`, `CompletionOptions`,
`AssistantMessage.toolCalls`) is already cataloged. The Iron refined-type
pattern (`NodeKey` `RefinedType` / `refineEither`) is established and will
be followed for `RolloutId` and `maxEntries` with no new dependency.

## Concepts this change WILL INTRODUCE (for apply-phase append)

From the proposal's "New Concepts to Introduce" table — these will be appended
to the inventory during apply (Step 12) with `spec:add-adk4s-record/<spec>`
provenance:

| Concept | Kind | Target inventory section | Target package |
|---------|------|--------------------------|----------------|
| `CallKey` | opaque type (`String`) | Refined / Opaque Types | `org.adk4s.record` |
| `CanonicalForm` | final case class (`keyVersion`, `kind`, `body`) | Case Classes | `org.adk4s.record` |
| `CallKind` | enum (`Model`/`Tool`/`Embedding`) | Sealed Traits and Enums | `org.adk4s.record` |
| `RolloutId` | opaque type (`String :| NonEmpty`, Iron RefinedType) | Refined / Opaque Types | `org.adk4s.record` |
| `CallRecord` | enum ADT (`Succeeded`/`Failed`) | Sealed Traits and Enums | `org.adk4s.record` |
| `RecordedError` | final case class | Case Classes | `org.adk4s.record` |
| `Classification` | enum / opaque type | Sealed Traits and Enums | `org.adk4s.record` |
| `Recorder[F[_]]` | trait (`lookup`/`record`/`nextSeq`) | Service Traits | `org.adk4s.record` |
| `Recorder.noop`/`inMemory`/`file` | implementations | Objects | `org.adk4s.record` |
| `RecordedChatModel[F[_]]` | wrapper (decorator) | Case Classes / Objects | `org.adk4s.record` |
| `ToolMiddleware.recording` | factory method | (extends existing `ToolMiddleware` object) | `org.adk4s.record` |
| `RecordedEmbedder[F[_]]` | wrapper (decorator) | Case Classes / Objects | `org.adk4s.record` |
| `RecorderLaws` | class (main scope, Hedgehog properties) | Case Classes | `org.adk4s.record` |
| `RequestMutation` | ADT | Sealed Traits and Enums | `org.adk4s.record` |
| `Redaction` | function type | Type Aliases | `org.adk4s.record` |
| `keyVersion` | constant | (n/a — module-level val) | `org.adk4s.record` |
| `RecorderCoherenceModel` | PureScala model | Sealed Traits (model) | `org.adk4s.verified` |
| `NormalizationModel` | PureScala model | Sealed Traits (model) | `org.adk4s.verified` |
| `AR-REC-1`/`AR-REC-2` | Scalafix arch rules | (n/a — config, not Scala types) | `.scalafix.conf` |

> `RolloutId` is the only new Iron-refined type. It reuses the existing
> `NonEmpty` constraint (`Not[Blank]`) already defined in
> `org.adk4s.core.types` — no new constraint type is introduced. `maxEntries`
> reuses the existing `numeric.Positive` constraint. Both follow the `NodeKey`
> `RefinedType` precedent.
