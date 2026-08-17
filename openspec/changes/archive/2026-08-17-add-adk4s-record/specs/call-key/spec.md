# Spec: call-key (canonical content-hash key)

<!-- Delta spec for the add-adk4s-record change. Defines the canonical
     content-hash key over LLM, tool, and embedding calls: CallKey,
     CanonicalForm, CallKind, canonicalization rules, tool-call id
     normalization, and keyVersion isolation. This is the highest-risk pure
     kernel in the change — a single un-normalized tool-call id or unordered
     collection iteration silently empties every cache beyond turn one. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | The model call surface whose requests are canonicalized; `generate` requests carry the fields entering the canonical form | `openspec/concepts/chat-model.md` |
| [Tool](../../../../concepts/tool.md) | Tool definitions (name, description, parameter schema) enter the canonical form; tool-call ids are normalized (REC-4) | `openspec/concepts/tool.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` |
| `Embedder[F[_]]` | trait | `org.adk4s.core.component` |
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` |
| `ToolInput` | case class (`name`, `arguments`, `callId`) | `org.adk4s.core.tools` |
| `ToolOutput` | case class (`name`, `result`, `callId`, `isError`) | `org.adk4s.core.tools` |
| `NodeKey` | opaque type (`String :| NonEmpty & Not[Reserved]`, Iron RefinedType) | `org.adk4s.core.types` |
| `Positive` | type alias (`Int :| numeric.Positive`, Iron) | `org.adk4s.core.types` |
| `NonEmpty` | type alias (`Not[Blank]`, Iron) | `org.adk4s.core.types` |
| `JsonValue` | type alias (`= smithy4s.Document`) | `org.adk4s.core.json` |
| `JsonValueCodec` | object (`toUjson`/`fromUjson`) | `org.adk4s.core.json` |
| `CompletionOptions` | (llm4s) | `llm4s` |
| `Conversation` / `Message` / `AssistantMessage` | (llm4s) | `llm4s` |
| `ToolFunction` / `ToolRegistry` | (llm4s) | `llm4s` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `CallKey` | opaque type (`String`) | Content hash of a canonical request form; stable across processes and JVMs |
| `CanonicalForm` | generated case class (`keyVersion: Int`, `kind: CallKind`, `body: CanonicalBody`) | Generated from Smithy IDL via smithy4s codegen. Inspectable canonical form; diffable when keys mismatch (REC-8). Body is a typed union (ModelBody/ToolBody/EmbeddingBody) — type-safe, immutable, BigDecimal-precise. ujson confined to llm4s boundary via `JsonValueCodec`. |
| `CallKind` | generated enum (`MODEL`, `TOOL`, `EMBEDDING`) | Generated from Smithy IDL. Discriminates canonicalization strategy. |
| `CanonicalBody` | generated union (`model: ModelBody`, `tool: ToolBody`, `embedding: EmbeddingBody`) | Generated from Smithy IDL. Type-safe body per call kind. |
| `RolloutId` | opaque type (`String :| NonEmpty`, Iron RefinedType) | Deliberate-resampling discriminator; absent = deterministic mode |
| `keyVersion` | constant (`Int`) | Canonicalization algorithm version; incremented on breaking canonicalization change |

## ADDED Requirements

### Requirement: CallKey is a cryptographic digest of a canonical request form

The system SHALL compute a `CallKey` as a cryptographic digest of a
canonical form of the call request, such that the digest is stable across
processes and JVM invocations.

**Given** a call request (model call, tool call, or embedding call)
**When** the system computes its `CallKey`
**Then** the key is a `String` digest of the request's `CanonicalForm`,
deterministic across repeated computations in the same and different
processes

**Rationale**: A content-hash key is the foundation of deterministic replay
and caching. If the key is not stable across processes, replay diverges
silently.

#### Scenario: Same request yields same key across computations

**Given** a fixed model call request (provider, model, messages, tools,
options)
**When** the `CallKey` is computed twice in two separate JVM processes
**Then** both keys are equal

#### Scenario: Different call kinds produce different key spaces

**Given** a model call request and a tool call request
**When** their `CallKey`s are computed
**Then** the keys are different (the `CallKind` discriminator enters the
canonical form)

### Requirement: Canonical form includes all output-affecting fields

The system SHALL include in the canonical form, for a model call: provider
id, model id, the ordered message list, the ordered tool-definition list
(name, description, parameter schema), the system prompt, the completion
options that affect output (`temperature`, `maxTokens`, `topP`,
`stopSequences`), the output schema where one is supplied, the `RolloutId`
where one is supplied, and the `keyVersion` constant.

**Given** a model call request with all output-affecting fields populated
**When** the canonical form is constructed
**Then** every listed field is present in the `CanonicalForm.body`

**Rationale**: Omitting any output-affecting field produces key collisions
where different requests map to the same key, corrupting replay.

#### Scenario: Temperature enters the canonical form

**Given** two identical requests differing only in `temperature` (0.0 vs 1.0)
**When** their `CallKey`s are computed
**Then** the keys are different

#### Scenario: Tool definitions enter the canonical form

**Given** two identical requests differing only in the tool-definition list
(one has an extra tool)
**When** their `CallKey`s are computed
**Then** the keys are different

### Requirement: Canonical form excludes non-output-affecting fields

The system SHALL exclude from the canonical form: wall-clock timestamps,
provider request ids, latency, token-usage figures, and any field that does
not affect the model's output distribution.

**Given** two identical requests differing only in a non-output-affecting
field (e.g. provider request id, latency)
**When** their `CallKey`s are computed
**Then** the keys are equal

**Rationale**: Including non-output-affecting fields destroys hit rates
without improving correctness.

#### Scenario: Provider request id does not affect the key

**Given** two identical requests with different provider-generated request ids
**When** their `CallKey`s are computed
**Then** the keys are equal

#### Scenario: Token usage does not affect the key

**Given** two identical requests where one carries prior token-usage metadata
and the other does not
**When** their `CallKey`s are computed
**Then** the keys are equal

### Requirement: Provider-generated tool-call ids are normalized to positional identifiers

The system SHALL normalize provider-generated tool-call identifiers to
positional identifiers (`call_0`, `call_1`, ...) in call order before
hashing, applied consistently to both the assistant message's tool-call ids
and the matching tool-reply message ids, so that the assistant/tool pairing
survives normalization.

**Given** a conversation containing an assistant message with tool calls
carrying provider-generated ids (`call_abc123`, `call_def456`)
**When** the canonical form is constructed
**Then** the tool-call ids are replaced with `call_0`, `call_1` in call
order, and the corresponding tool-reply message ids are replaced with the
same positional identifiers

**Rationale**: Providers mint tool-call ids randomly per response. If a raw
id enters the hash, every conversation turn after the first tool call has
an unrepeatable key and the cache never hits beyond turn one.

#### Scenario: Multi-turn conversation with tool calls normalizes consistently

**Given** a two-turn conversation where turn 1 has tool calls with ids
`call_abc123` and `call_def456`, and tool replies referencing those ids
**When** the canonical form is constructed
**Then** the assistant message's tool-call ids become `call_0`, `call_1`,
and the tool replies reference `call_0`, `call_1` respectively

#### Scenario: Different provider ids for same logical conversation yield same key

**Given** two conversations with identical logical content but different
provider-generated tool-call ids
**When** their `CallKey`s are computed
**Then** the keys are equal

### Requirement: Canonicalization is a pure total function

The system SHALL implement canonicalization as a pure, total function with
no dependency on ambient time, locale, iteration order of unordered
collections, or system entropy.

**Given** the same call request
**When** canonicalization is called multiple times
**Then** the result is identical regardless of when, where, or in what
context the function runs

**Rationale**: Any ambient nondeterminism in canonicalization makes the key
unrepeatable across processes, silently emptying caches.

#### Scenario: No wall-clock dependency

**Given** the same request canonicalized at two different times
**When** the canonical forms are compared
**Then** they are equal

#### Scenario: No unordered iteration dependency

**Given** a request containing tool definitions stored in a Map or Set
**When** the canonical form is constructed
**Then** the tool definitions appear in a deterministic (sorted) order in
the canonical form body

### Requirement: Key sensitivity to output-affecting field changes

The system SHALL produce different `CallKey` values for two requests that
differ in any field named in the canonical-form inclusion requirement.

**Given** two requests differing in any output-affecting field
**When** their `CallKey`s are computed
**Then** the keys are different

**Rationale**: Key collisions between semantically different requests
corrupt replay by serving the wrong cached result.

#### Scenario: Different model ids produce different keys

**Given** two identical requests differing only in model id
**When** their `CallKey`s are computed
**Then** the keys are different

### Requirement: Key insensitivity to non-output-affecting field changes

The system SHALL produce identical `CallKey` values for two requests that
differ only in fields excluded from the canonical form, including
regenerated tool-call ids.

**Given** two requests differing only in excluded fields
**When** their `CallKey`s are computed
**Then** the keys are equal

**Rationale**: This is the complement of key sensitivity — together they
are the real specification of the canonicalizer.

#### Scenario: Regenerated tool-call ids produce same key

**Given** two identical conversations where tool-call ids are different
random strings but the logical content is the same
**When** their `CallKey`s are computed
**Then** the keys are equal

### Requirement: Canonical form is inspectable

The system SHALL expose the canonical form as an inspectable value (not
only its digest), so that a key mismatch can be diagnosed by diffing
canonical forms rather than comparing hashes.

**Given** a call request
**When** the canonical form is computed
**Then** the `CanonicalForm` value (with `keyVersion`, `kind`, and `body`)
is available for inspection and serialization

**Rationale**: When two keys mismatch, diffing the canonical forms reveals
which field diverged. Comparing only hashes gives no diagnostic
information.

#### Scenario: Canonical form diff reveals field difference

**Given** two requests differing in model id
**When** their canonical forms are diffed
**Then** the diff shows the model id field as different

### Requirement: RolloutId discriminates keys for deliberate resampling

The system SHALL include a `RolloutId`, where one is supplied, in the
canonical form, such that distinct rollout ids produce distinct keys for
otherwise identical requests, and equal rollout ids produce equal keys.

**Given** two otherwise identical requests with different `RolloutId` values
**When** their `CallKey`s are computed
**Then** the keys are different

**Rationale**: Deliberate resampling (BestOfN, bootstrap rounds) requires
that each rollout gets its own cache entry. Without the rollout id, all
samples of the same prompt collapse to one cached result.

#### Scenario: Same rollout id yields same key

**Given** two identical requests with the same `RolloutId`
**When** their `CallKey`s are computed
**Then** the keys are equal

#### Scenario: Absent rollout id is deterministic

**Given** two identical requests with no `RolloutId` supplied
**When** their `CallKey`s are computed
**Then** the keys are equal (deterministic mode)

### Requirement: RolloutId is a non-empty refined type

The system SHALL model `RolloutId` as an opaque type backed by
`String :| NonEmpty` (Iron RefinedType), so that an empty rollout id cannot
be constructed as a `RolloutId`.

**Given** a string value being refined into a `RolloutId`
**When** the value is empty
**Then** refinement fails and returns a `Left` (or fails compilation for
inline literals)

**Rationale**: An empty rollout id is semantically indistinguishable from
absent, which would silently collapse distinct rollups. The Iron
`RefinedType` pattern follows the `NodeKey` precedent.

#### Scenario: Non-empty string constructs successfully

**Given** the string `"run-42"`
**When** it is refined via `RolloutId.refineEither`
**Then** the result is `Right(RolloutId("run-42"))`

#### Scenario: Empty string is rejected

**Given** the empty string `""`
**When** it is refined via `RolloutId.refineEither`
**Then** the result is `Left(...)` with a non-empty constraint message

### Requirement: keyVersion isolates canonicalization algorithm changes

The system SHALL include a `keyVersion` integer in every canonical form,
and SHALL increment it whenever canonicalization changes in any way that
alters computed keys, such that records written under a different
`keyVersion` are treated as absent by lookup rather than as errors.

**Given** a recorder holding records written under `keyVersion = 1`
**When** a lookup is performed with `keyVersion = 2`
**Then** the lookup returns `None` (record treated as absent), not an error

**Rationale**: When the canonicalization algorithm changes, old records
must not be served (they were hashed under a different algorithm) but must
also not surface as `Left` results (they are well-formed records under
their own version). This gives append-only and bounded backends the
divergent behavior they need without divergent code.

#### Scenario: Version bump makes old records invisible

**Given** a recorder with a record written under `keyVersion = 1`
**When** the key version is incremented to 2 and the same request is looked
up
**Then** the lookup misses (returns `None`)

#### Scenario: Same version yields same key

**Given** two identical requests with the same `keyVersion`
**When** their `CallKey`s are computed
**Then** the keys are equal

## Properties (Ring 3)

### Property: key-determinism

**Invariant**: Canonicalizing the same request twice, in different
processes, yields equal keys.

**Generator strategy**: `genModelRequest` — constructive over provider ids,
model ids, message lists, tool-definition lists, completion options, and
optional rollout ids. Edge cases: single-message conversation, no tools,
empty system prompt, temperature = 0.0, rollout id absent. Coverage labels:
`cover 80 "has_tools"`, `cover 20 "no_tools"`, `cover 50 "has_rollout"`.

```
forAll { (req: ModelRequest) =>
  val k1 = CallKey.fromCanonical(CanonicalForm.from(req))
  val k2 = CallKey.fromCanonical(CanonicalForm.from(req))
  k1 === k2
}
```

### Property: key-sensitivity

**Invariant**: For every generated mutation of an output-affecting field
(REC-2 fields), the key changes.

**Generator strategy**: `genRequestMutation` — constructive ADT-based
mutation generator producing `(ModelRequest, RequestMutation)` pairs where
`RequestMutation` is one of: `ChangeProvider`, `ChangeModel`,
`ReorderMessages`, `ChangeTemperature`, `ChangeMaxTokens`, `ChangeTopP`,
`ChangeStopSequences`, `AddTool`, `RemoveTool`, `ChangeToolSchema`,
`ChangeSystemPrompt`, `ChangeRolloutId`. Each mutation alters exactly one
output-affecting field. Edge cases: mutation to boundary values
(temperature 0.0, maxTokens 1, empty stop sequences).

```
forAll { (base: ModelRequest, mutation: RequestMutation) =>
  val mutated = mutation.apply(base)
  val kBase = CallKey.fromCanonical(CanonicalForm.from(base))
  val kMutated = CallKey.fromCanonical(CanonicalForm.from(mutated))
  kBase =!= kMutated
}
```

### Property: key-insensitivity

**Invariant**: For every generated mutation of a non-output-affecting field
(REC-3 fields), including tool-call id regeneration, the key is unchanged.

**Generator strategy**: `genNonAffectingMutation` — constructive ADT-based
mutation generator producing `(ModelRequest, NonAffectingMutation)` pairs
where `NonAffectingMutation` is one of: `RegenerateToolCallIds`,
`ChangeProviderRequestId`, `ChangeLatency`, `ChangeTokenUsage`,
`ChangeTimestamp`. Edge cases: all tool-call ids regenerated, zero latency,
zero token usage.

```
forAll { (base: ModelRequest, mutation: NonAffectingMutation) =>
  val mutated = mutation.apply(base)
  val kBase = CallKey.fromCanonical(CanonicalForm.from(base))
  val kMutated = CallKey.fromCanonical(CanonicalForm.from(mutated))
  kBase === kMutated
}
```

### Property: rollout-separation

**Invariant**: Distinct rollout ids produce distinct keys; equal rollout
ids produce equal keys; absent rollout id is deterministic.

**Generator strategy**: `genRolloutPair` — constructive over pairs of
optional `RolloutId` values with a guarantee that 50% are equal, 50% are
different, and some are absent. Edge cases: both absent, one absent.

```
forAll { (req: ModelRequest, r1: Option[RolloutId], r2: Option[RolloutId]) =>
  val k1 = CallKey.fromCanonical(CanonicalForm.from(req, r1))
  val k2 = CallKey.fromCanonical(CanonicalForm.from(req, r2))
  (r1 === r2) ==> (k1 === k2) && (r1 =!= r2) ==> (k1 =!= k2)
}
```

### Property: normalization-idempotence

**Invariant**: Normalizing tool-call ids twice yields the same result as
normalizing once.

**Generator strategy**: `genConversationWithToolCalls` — constructive over
multi-turn conversations with 0–3 tool calls per turn, random provider ids.
Edge cases: zero tool calls, single tool call, tool calls across multiple
turns.

```
forAll { (conv: Conversation) =>
  val normalizedOnce = normalizeToolCallIds(conv)
  val normalizedTwice = normalizeToolCallIds(normalizedOnce)
  normalizedOnce === normalizedTwice
}
```

### Property: normalization-order-preservation

**Invariant**: Normalization preserves call order and the assistant/tool
pairing relation.

**Generator strategy**: `genConversationWithToolCalls` — same as above.

```
forAll { (conv: Conversation) =>
  val normalized = normalizeToolCallIds(conv)
  val pairings = extractToolCallPairings(normalized)
  pairings.forall { case (assistantIdx, toolReplyIdx) =>
    normalized.messages(assistantIdx).toolCalls.map(_.id) ==
    normalized.messages(toolReplyIdx).map(_.toolCallId)
  } &&
  pairings.zipWithIndex.forall { case ((a, t), i) =>
    normalized.messages(a).toolCalls.head.id == s"call_$i"
  }
}
```

### Property: version-isolation

**Invariant**: Records written under `keyVersion = n` are invisible to
lookups at `keyVersion = n+1`.

**Generator strategy**: `genVersionedRecord` — constructive over
`CallRecord` values with `keyVersion` in {1, 2}. Edge cases: version 1
record, version 2 record.

```
forAll { (record: CallRecord, lookupVersion: Int) =>
  val writeVersion = record.keyVersion
  val visible = writeVersion == lookupVersion
  // lookup at lookupVersion sees the record iff versions match
  visible == (writeVersion == lookupVersion)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `RolloutId("")` | Empty rollout id is semantically absent; must not be constructible | `assertDoesNotCompile("RolloutId(\"\")")` or `assertLeft(RolloutId.refineEither(""))` |
| `CanonicalForm` without `keyVersion` | Every canonical form must carry its version for isolation | `assertDoesNotCompile("CanonicalForm(kind, body)")` (missing keyVersion) |

## Formal Contracts (Ring 6)

### Contract: normalizeToolCallIds — idempotence

**Precondition** (`require`): the conversation is non-null (always true in
PureScala)
**Postcondition** (`ensuring`): `normalize(normalize(conv)) == normalize(conv)`

```scala
def normalize(conv: List[Msg]): List[Msg] = {
  // model: Msg is Assistant(toolCalls: List[String]) | ToolReply(toolCallId: String) | Other
  // positional replacement in call order
}.ensuring(result => normalize(result) == result)
```

### Contract: normalizeToolCallIds — order preservation

**Precondition** (`require`): input is a valid conversation
**Postcondition** (`ensuring`): for every assistant/tool-reply pair, the
normalized id at position i is `call_i` and the pairing relation is
preserved.

```scala
def normalize(conv: List[Msg]): List[Msg] = {
  // ...
}.ensuring(result => pairings(result).zipWithIndex.forall {
  case ((a, t), i) => result(a).toolCalls.head == s"call_$i" &&
                      result(t).toolCallId == s"call_$i"
})
```

> **Delegated to Ring 3**: key-determinism across processes (RL2),
> key-sensitivity (RL3), key-insensitivity (RL4), rollout-separation (RL6),
> version-isolation (RL12) — these require generated inputs over
    llm4s types not modelable in PureScala. The Ring 6 model covers only
    the normalization algorithm's idempotence and order-preservation, which
    are the properties where a bug is both easy to introduce and invisible
    in ordinary tests.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| CallKey is a stable digest | Requirement: CallKey is a cryptographic digest of a canonical request form | property test (key-determinism) | `CallKeySpec.scala` |
| Canonical form includes output-affecting fields | Requirement: Canonical form includes all output-affecting fields | property test (key-sensitivity) + scenario test | `CallKeySpec.scala` |
| Canonical form excludes non-output-affecting fields | Requirement: Canonical form excludes non-output-affecting fields | property test (key-insensitivity) + scenario test | `CallKeySpec.scala` |
| Tool-call ids normalized to positional | Requirement: Provider-generated tool-call ids are normalized to positional identifiers | property test (normalization-idempotence, normalization-order-preservation) | `CallKeySpec.scala` |
| Canonicalization is pure total | Requirement: Canonicalization is a pure total function | static rule (AR-REC-1: no ambient nondeterminism) + static rule (AR-REC-2: no unordered iteration) + property test (key-determinism) | `.scalafix.conf`, `CallKeySpec.scala` |
| Key sensitivity | Requirement: Key sensitivity to output-affecting field changes | property test (key-sensitivity) | `CallKeySpec.scala` |
| Key insensitivity | Requirement: Key insensitivity to non-output-affecting field changes | property test (key-insensitivity) | `CallKeySpec.scala` |
| Canonical form inspectable | Requirement: Canonical form is inspectable | scenario test | `CallKeySpec.scala` |
| RolloutId discriminates keys | Requirement: RolloutId discriminates keys for deliberate resampling | property test (rollout-separation) | `CallKeySpec.scala` |
| RolloutId non-empty | Requirement: RolloutId is a non-empty refined type | type system (Iron RefinedType) + compile-negative test + property test | `CallKeySpec.scala`, `CallKeyTypeContract.scala` |
| keyVersion isolation | Requirement: keyVersion isolates canonicalization algorithm changes | property test (version-isolation) + scenario test | `CallKeySpec.scala` |
| Normalization idempotence (formal) | Property: normalization-idempotence | formal contract (Ring 6 verified-mirror) | `verified/NormalizationModel.scala`, `NormalizationBridgeSpec.scala` |
| Normalization order preservation (formal) | Property: normalization-order-preservation | formal contract (Ring 6 verified-mirror) | `verified/NormalizationModel.scala`, `NormalizationBridgeSpec.scala` |
| No ambient nondeterminism in canonicalization | Requirement: Canonicalization is a pure total function | static rule (AR-REC-1) | `.scalafix.conf` |
| No unordered iteration in canonicalization | Requirement: Canonicalization is a pure total function | static rule (AR-REC-2) | `.scalafix.conf` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `CallKey` | opaque type | `org.adk4s.record` | `opaque type CallKey = String` |
| `CanonicalForm` | generated case class | `org.adk4s.record.canonical` | Generated from `canonical_form.smithy` via smithy4s codegen. `keyVersion: Int, kind: CallKind, body: CanonicalBody` |
| `CallKind` | generated enum | `org.adk4s.record.canonical` | `MODEL`, `TOOL`, `EMBEDDING`. Generated from Smithy IDL. |
| `CanonicalBody` | generated union | `org.adk4s.record.canonical` | `model: ModelBody`, `tool: ToolBody`, `embedding: EmbeddingBody` |
| `RolloutId` | opaque type (Iron) | `org.adk4s.record` | `String :| NonEmpty`, reuses `NonEmpty` from `org.adk4s.core.types` |
| `Canonicalization` | object | `org.adk4s.record.canonical` | pure canonicalization functions; no fs2, no cats-effect, no llm4s LLM client |
| `normalizeToolCallIds` | function | `org.adk4s.record.canonical` | positional id normalization (REC-4) |
| `keyVersion` | val | `org.adk4s.record` | current canonicalization version constant |
| `AR-REC-1` | Scalafix arch rule | `.scalafix.conf` | no `System.currentTimeMillis`/`Instant.now`/`UUID.randomUUID`/`.hashCode` in `org.adk4s.record.canonical` |
| `AR-REC-2` | Scalafix arch rule | `.scalafix.conf` | no direct `Map`/`Set` iteration without sort in `org.adk4s.record.canonical` |
| `NormalizationModel` | PureScala model | `org.adk4s.verified` | Ring 6 mirror of tool-call id normalization |
| `NormalizationBridgeSpec` | bridge test | `adk4s-record/src/test` | runs shipped `normalizeToolCallIds` and model on same generated inputs |
