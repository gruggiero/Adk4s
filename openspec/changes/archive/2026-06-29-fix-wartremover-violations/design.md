# Design: Incrementally Re-enable Excluded WartRemover Warts

## Package Structure

This change introduces **no new packages**. All refactors occur within
existing packages per `capability-profile.md`. The layer rules below are the
existing project rules, restated to show where each spec's edits land.

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| Structured core (pure) | `org.adk4s.structured.core`, `org.adk4s.structured.sap` | stdlib, smithy4s, ujson | fs2, cats-effect, llm4s, typesafe-config | No outbound infrastructure imports |
| Core components (effectful) | `org.adk4s.core.component` | cats-effect, fs2, llm4s, ujson, structured-llm | workflows4s, logback | Effect discipline |
| Core tools (effectful) | `org.adk4s.core.tools` | cats-effect, fs2, llm4s, ujson | workflows4s | Effect discipline |
| Core types (pure) | `org.adk4s.core.types` | stdlib, cats (Eq/Show/Order only) | fs2, cats-effect, llm4s | Pure value types |
| Core error (pure) | `org.adk4s.core.error` | stdlib, llm4s LLMError, structured-llm StructuredLLMError, interrupt InterruptSignal | fs2, cats-effect | Sealed error algebra |
| Orchestration (effectful) | `org.adk4s.orchestration.*` | cats-effect, fs2, workflows4s, adk4s-core, structured-llm | logback, http | Workflow layer |
| Examples (edge) | `org.adk4s.examples.*` | everything | — | Edge code, relaxed wart set |
| Generated smithy4s | `target/.../src_managed` | — | — | Excluded from all checks |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| (none) | — | No new packages. `NodeKeyError` is added to the existing `org.adk4s.core.error` package. |

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `org.adk4s.core.types.NodeKey.from` | Total smart constructor `String => Either[NodeKeyError, NodeKey]` | Yes (pure, no F) — candidate for `verified` module mirror if desired |
| `JsonFixMiddleware.replaceSingleQuotes` (refactored recursion) | Pure `String => String` character rewrite | Yes (pure) |
| `JsonFixMiddleware.applyHeuristicFixes` (refactored fold) | Pure `String => String` fix pipeline | Yes (pure) |
| `Tool.buildObjectSchema` dispatch (refactored pattern match) | Pure `ujson.Value => ObjectSchema[ujson.Value]` | Yes (pure) |
| `ToolInfer.getFieldNames` / `decodeProduct` (compiletime) | Inline macro — NOT a Ring 6 candidate (compiletime, not runtime Stainless) | No |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `Tool.invokable.run` | `F[ujson.Value]` (`Sync[F]`) | Tool execution; `F.raiseError(ToolExecutionError)` replaces `throw` |
| `GraphExecutor.execute` | `IO[Out]` | Graph execution; `IO.raiseError(AdkError)` replaces `IO.raiseError(new Exception)` |
| `AgentRunner.run` | `IO[RunResult]` | Interrupt/resume; `IO.raiseError(AgentInterruptedException)` replaces `throw` |
| Test mocks (`ComponentMockLLMClient`, `RetrieverSpec`) | `Ref[IO, Int]` | Call counting replaces `var` |

## Type Strategy — Invalid-State Prevention

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| Empty/reserved `NodeKey` unrepresentable | Good | Smart constructor `NodeKey.from: Either[NodeKeyError, NodeKey]`; `unsafeApply` deprecated/removed | Opaque type + total constructor; `Left` is the only failure path (no throw) |
| `Tool` schema dispatch never `asInstanceOf` | Best | Pattern match over the `"type"` string literal → construct the matching `SchemaDefinition[String]` variant directly; default branch → `StringSchema` | The match is exhaustive over the known type strings; no cast needed |
| `ToolInfer` `constValue` never cast | Good | `scala.compiletime.constValueOpt[head]: Option[String]` + `match` handling `None` | `constValueOpt` is the total alternative to `constValue`+cast |
| `ToolInfer` tuple decode never cast | Good | Build the tuple via `*:` recursively and let `m.fromTuple` accept the constructed `Tuple` without an explicit `asInstanceOf` (the type ascription flows from the recursive `decodeFields` return) | If the compiler still requires a cast at the `m.fromTuple` boundary, fall back to a `scala.reflect.TypeTest` / `Mirror`-driven reconstruction; design.md commits to the no-cast path, with a fallback noted in implementation |
| `WIOGraph`/`GraphExecutor` node dispatch never cast | Best | Pattern match over the sealed `WIONode` variants (each variant carries its concrete `O` type); thread `O` via the variant, not via a cast | Sealed exhaustiveness guarantees all node kinds handled; compiler enforces |
| `JsonFixMiddleware` no `var` | Best | Tail-recursive `loop` / `foldLeft` over the fix steps — mutable state is impossible to express | Recursion makes mutation unrepresentable |
| No `.get` on `Option` | Good | `.getOrElse` / `.fold` / pattern match at each site | Total operations; `None` handled explicitly |
| No `.init`/`.last` | Good | `dropRight(1)` / `lastOption` | Total on empty collections |
| No default arguments | Good | Companion `apply` overloads / explicit call-site args | Defaults unrepresentable; factories preserve old values |
| No `s"..."` widening to `Any` | Good | `+` concatenation keeps pieces typed `String` | No widening |

## Refined Type Strategy

No refined-type library (Iron) is present (capability-profile.md). The
project uses plain Scala 3 `opaque type` + smart constructors. This change
does not introduce a refined-type dependency.

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| (none new) | — | — | `NodeKey` already exists as an opaque type; this change only adds a total `from` constructor. |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `NodeKeyError.invalidKey: String` | Carries the rejected input for diagnostics; no structural constraint needed |

## IDL Model Layout

**Not applicable.** This change touches no IDL (Smithy) operations,
structures, or wire formats. The `structured-llm-test-models` Smithy schemas
are test-only and unchanged.

## Error Strategy

### Error Modeling

The existing `AdkError` sealed hierarchy (21 variants) is the single error
algebra. This change adds exactly one variant:

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `AdkError` (existing) + `NodeKeyError` (new) | `NodeKeyError(invalidKey: String)` | `NodeKey.from` |

No new error enums are created. `ToolDispatchResult` (tentative in the
asinstanceof spec) is **dropped** — the design resolves the dispatch via a
plain pattern match over the `"type"` string, so no new sealed trait is
needed. This means the asinstanceof spec's "Concepts Introduced" resolves to
**none** (the tentative row is withdrawn by this design decision).

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure | `Either[AdkError, A]` | `NodeKey.from(s): Either[NodeKeyError, NodeKey]` |
| Pure → Effect | lift via `F.fromEither` / `F.raiseError` | `F.fromEither(NodeKey.from(s))` |
| Effect → Effect | `F.raiseError(AdkError)` (replaces `throw` and `IO.raiseError(new Exception)`) | `F.raiseError(ToolExecutionError(name, cause))` |
| Interrupt boundary | `F.raiseError(AgentInterruptedException(signal))` caught by `.attempt` | `ToolsNode` → `AgentRunner` (unchanged semantics) |

**No swallowed errors.** The `ToolInfer.decodeProduct` `try/catch` (which
swallows `Exception` into `Left(e.getMessage)`) is refactored to a
`Either`-returning decode that propagates a typed `ToolSchemaError` instead
of catching raw exceptions — this also removes the `catch` (covered by the
`.scalafix.conf` `NoKeywordTry/Catch/Finally` rule aspirationally, and by
`Wart.Throw` once the `throw` inside is gone).

## Compatibility Story (Ring 4)

**Not applicable.** This change touches no persisted events, snapshots, wire
formats, or message payloads. `InterruptSignal` / `AddressSegment` derive
`ReadWriter` (upickle) but their serialization shape is unchanged — the
refactor only changes how `message` strings are built (`s"..."` → `+`), not
the serialized fields. The `InterruptSignal` wire format is byte-identical
pre/post refactor (verified by the any-string-plus-any spec's reference-oracle
property).

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `org.adk4s.core.types` (NodeKey) | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅* | — | ✅ | — |
| `org.adk4s.core.error` (NodeKeyError) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.core.tools` (Tool, ToolInfer, ToolSchema, JsonFixMiddleware) | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅* | — | ✅ | — |
| `org.adk4s.core.component` (Tool dispatch) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `org.adk4s.orchestration.wiograph` (WIOGraph, WIONode) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `org.adk4s.orchestration.execution` (GraphExecutor) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `org.adk4s.orchestration.agent` (AgentRunner interrupt) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.structured.sap` (SAP recovery — unchanged) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| Test sources (mocks, assertions) | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — |
| `build.sbt` (wart exclusion list) | ✅ | ✅ | — | — | — | — | — | — | ✅ | — |

✅* = Ring 6 candidate only for the pure functions listed in "Pure Code";
the `verified` module mirror is optional and not required for this change.
R9 (telemetry) is absent (no otel4s).

## Technical Decisions

### Decision: Resolve `AsInstanceOf` via pattern match, drop `ToolDispatchResult`

**Context**: The asinstanceof spec listed `ToolDispatchResult` as a tentative
new sealed trait for tool schema dispatch.
**Options considered**: (a) new sealed `ToolDispatchResult` wrapper;
(b) plain pattern match over the `"type"` string constructing the matching
`SchemaDefinition[String]` directly; (c) `scala.reflect.TypeTest`.
**Decision**: Option (b). The dispatch is already a `match` on the type
string; the only reason `asInstanceOf` appears is to unify
`IntegerSchema`/`NumberSchema`/`BooleanSchema` into `SchemaDefinition[String]`.
Constructing each variant directly as `SchemaDefinition[String]` (they already
extend it) removes the cast without a new type.
**Consequences**: `ToolDispatchResult` is withdrawn — the asinstanceof spec's
"Concepts Introduced" resolves to **none**. The concept delta check at apply
Step 12 will verify no new concept is introduced by that spec.

### Decision: `NodeKey.unsafeApply` kept as a deprecated bridge, not removed

**Context**: `unsafeApply` throws; removing it forces all call sites to handle
`Either` immediately, widening the blast radius.
**Options considered**: (a) remove `unsafeApply`, update all call sites;
(b) keep `unsafeApply` as `@deprecated`, implement via
`from(key).fold(e => throw e, identity)` — but this keeps a `throw`;
(c) keep `unsafeApply` as `@deprecated`, implement via
`from(key).getOrElse(throw new NodeKeyError(key))` — still `throw`.
**Decision**: Option (a) for main sources — remove `unsafeApply` and update
call sites to `from` + `Either` handling. The `throw` wart requires no
`throw` keyword in main sources, so any bridge that throws is unacceptable.
Call sites are finite and identified (grep of `unsafeApply`).
**Consequences**: Slightly larger diff for the throw spec, but it is the only
way to satisfy `Wart.Throw` without a deprecated throwing bridge. The
`NodeKey.from` total constructor is the new public API.

### Decision: `ToolInfer.decodeProduct` `try/catch` → typed `Either`

**Context**: `decodeProduct` wraps decoding in `try/catch` and returns
`Left(e.getMessage)`, swallowing the exception type.
**Options considered**: (a) keep `try/catch` (blocked by `Wart.Throw` only if
the body throws; but `.scalafix.conf` `NoKeywordTry` is aspirational, not
active); (b) refactor `decodeField` to return `Either[ToolSchemaError, A]`
and propagate via a `Monad`/`tailRecM` over the tuple.
**Decision**: Option (b). `decodeField` returns `Either[ToolSchemaError, A]`;
`decodeFields` folds over the tuple with `Either` short-circuiting. This
removes the `try/catch`, gives typed errors, and removes the
`asInstanceOf[m.MirroredElemTypes]` (the tuple is built case-by-case).
**Consequences**: `ToolSchemaError` (already in inventory) gains variants for
decode failures if not already present; the concept delta check verifies no
unexpected new variants. The `decodeProduct` signature changes from
`Either[String, I]` to `Either[ToolSchemaError, I]` — call sites updated.

### Decision: `WIOGraph`/`GraphExecutor` dispatch via sealed `WIONode` match

**Context**: Node compilation casts `node.asInstanceOf[...]` and
`output.asInstanceOf[O]` on erased generic types.
**Options considered**: (a) pattern match over the sealed `WIONode` variants
threading each variant's concrete `O`; (b) `TypeTest`/`ClassTag`-based
runtime check; (c) existential wrapper.
**Decision**: Option (a). `WIONode` is already a sealed trait with 10
variants carrying their concrete types. A match over the variants lets the
compiler track `O` per branch, eliminating the cast.
**Consequences**: The `toWIO`/`executeGraph` methods gain a `match` over
`WIONode` variants. This is the largest single refactor; existing
`WIOGraphTest`/`WIORunnableNodeTest`/`WIONodeModifierTest` serve as the
behavior-preservation oracle. If a variant's type parameters make the
no-cast path inexpressible, that specific branch may require a localized
`TypeTest` — recorded as a Risky-level obligation requiring adversarial
review, but the default target is Best (no cast).

### Decision: Incremental, depth-first ordering

**Context**: Seven warts to re-enable; doing them all at once risks
unreviewable diffs and confounded regressions.
**Options considered**: (a) one big PR; (b) depth-first per spec (each wart
fully refactored + re-enabled + verified before the next).
**Decision**: Option (b), per the verified-scala3 workflow. Ordering by
rising risk: OptionPartial → IterableOps → Var → Throw → AsInstanceOf →
DefaultArguments → Any/StringPlusAny.
**Consequences**: Seven independently shippable units; each re-enables its
wart in `build.sbt` so later specs cannot reintroduce earlier warts without
failing the build.
