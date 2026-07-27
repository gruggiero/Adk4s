# Spec: Optimizable Surface

<!-- This is a DELTA spec for Phase 0 of the DSPy port
     (docs/dspy-port-operative-plan.md). It introduces the erased predictor
     surface — the optimizable-surface concept (a typeclass + structural
     derivation), the predictor-state concept (pure tunable state at each
     LM-call site), a placeholder predictor, the optimizer-law testkit, and
     two toy optimizers as the acceptance test. The surface is declared
     FROZEN by this change's design.md: later changes to it require their
     own proposal.

     ALTITUDE: requirements and scenarios use behavioral vocabulary only
     (Concept/action references, domain terms, test vectors). Code
     identifiers — class names, error variants, derivation mechanics, build
     commands — live in Implementation Anchors and the Concepts Introduced
     table. The full typed contract (Scala signatures) lives in design.md. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| StructuredLLM/completeTemplate | The placeholder predictor wraps this action to carry an LLM-call site; Phase 0 does not invoke it, only carries the capability | [structured-llm.md](../../../concepts/structured-llm.md) |
| Prompt | The placeholder predictor carries a Prompt-shaped template as its tunable site's prompt scaffold | [prompt.md](../../../concepts/prompt.md) |
| Schema | The placeholder predictor carries an output Schema for its typed output | [schema.md](../../../concepts/schema.md) |
| optimizable-surface (NEW — created by this spec) | The capability that lets an optimizer enumerate and update predictor sites in an unknown program by structural derivation over its product shape | `openspec/concepts/optimizable-surface.md` (created at apply Step 12) |
| predictor-state (NEW — created by this spec) | The pure state shape (instructions, demos, frozen) carried at each predictor site | `openspec/concepts/predictor-state.md` (created at apply Step 12) |

Creating the `optimizable-surface.md` and `predictor-state.md` concept files is PART OF implementing this spec (apply Step 12).

## Concept Specifications (new concepts)

<!-- The concept specification blocks for the two new concepts. These are
     copied to openspec/concepts/ at apply Step 12. Requirements below
     reference these actions by name (Concept/action). -->

### Concept: predictor-state

```
concept predictor-state
purpose
    Pure, serializable-ready view of one LM-call site's tunable state.
    Optimizers read and write it via optimizable-surface actions.
state
    instructions: predictor-state -> String
    demos: predictor-state -> List[Demo]
    frozen: predictor-state -> Boolean
actions
    (none — pure data value)
operational principle
    A predictor state is plain immutable data with no behavior. The
    instructions string and demo list are the tunable content an optimizer
    reads and replaces. The frozen flag excludes a state from
    optimizable-surface/updateAll and causes optimizable-surface/updateEither
    to return a frozen-path error. A frozen state is still readable via
    optimizable-surface/predictors.
```

### Concept: optimizable-surface

```
concept optimizable-surface[P]
purpose
    The optimizer-facing capability of a program type P: enumerate every
    tunable LM-call site, read/replace its predictor state, and produce a
    new program value with one or more sites updated — without knowing
    P's concrete types.
state
    (none — stateless typeclass over P)
actions
    predictors [ program: P ]
        => [ sites: List[(PredictorPath, predictor-state)] ]
    update [ program: P ; path: PredictorPath ; f: predictor-state -> predictor-state ]
        => [ program: P ]
    update [ program ; path ; f ]
        => [ error: UnknownPath(path) ]
    updateEither [ program ; path ; f ]
        => [ program: P ]
    updateEither [ program ; path ; f ]
        => [ error: UnknownPath(path) ]
    updateEither [ program ; path ; f ]
        => [ error: FrozenPath(path) ]
    updateAll [ program ; f: (PredictorPath, predictor-state) -> predictor-state ]
        => [ program: P ]
    derived [ P: Product ]
        => [ surface: optimizable-surface[P] ]
operational principle
    predictors walks P's product structure by structural derivation: each
    field with the leaf-predictor capability contributes a leaf (path =
    field name); each field with its own optimizable-surface contributes
    its subtree with the field name prepended; each ordered collection of
    predictors contributes indexed leaves (segment = index as string);
    other fields are ignored. update and updateEither address a single
    site by path; updateAll applies f to every non-frozen site. The frozen
    flag excludes a site from updateAll and causes updateEither to return
    a frozen-path error. derived produces an optimizable-surface for any
    case-class program with no hand-written instance.

sync LeafDerivation
when {
    optimizable-surface/derived: encounters a field with the leaf-predictor
    capability
}
then {
    that field contributes a leaf site with path = field name
}

sync SubtreeDerivation
when {
    optimizable-surface/derived: encounters a field with its own
    optimizable-surface
}
then {
    that field's subtree sites are contributed with the field name
    prepended to each path
}

sync CollectionDerivation
when {
    optimizable-surface/derived: encounters an ordered-collection field of
    predictors
}
then {
    each element contributes a leaf site with path segment = index as string
}
```

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM[F[_]]` | service trait | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `ujson.Value` | JSON value (upickle/ujson, transitive via llm4s) | (upickle) |
| `AdkError` | sealed trait (error-modeling pattern reference) | `org.adk4s.core.error` |
| `AgentMemoryLaws` | main-scope munit laws suite (pattern reference) | `org.adk4s.memory.testkit` |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Demo` | case class | One input/output pair (`input: ujson.Value`, `output: ujson.Value`) — the few-shot example unit carried in predictor state. |
| `PredictorState` | case class | Pure, serializable-ready view of one LM-call site's tunable state: `instructions: String`, `demos: Vector[Demo]`, `frozen: Boolean`. |
| `PredictorPath` | case class | Stable address of a predictor inside a program: `segments: Vector[String]` (case-class field names, outermost first); `render: String` joins segments with `.`. |
| `Optimizable[P]` | typeclass | The optimizer-facing capability of a program type `P`: `predictors`, `update`, `updateAll`, `updateEither`. With `inline def derived` via `Mirror.ProductOf[P]`. |
| `HasPredictorState[Self]` | typeclass | Leaf capability: anything that *is* a predictor exposes `state(self): PredictorState` and `withState(self, s): Self`. The placeholder predictor implements it. |
| `PredictorKernel` | PureScala model (Ring 6, `verified` module, Scala 3.7.2) | Verified mirror of the predictor-enumeration algorithm: `Prog` tree (`Pred`/`Plain`/`Sub`/`Coll`), `paths` (pre-order leaf paths as `List[BigInt]` index chains), `updateAll`. Proves declaration-order enumeration, non-predictor exclusion, path-set preservation and round-trip identity. Bound to the shipped `Optimizable` by `PredictorModelBridgeSpec`. |
| `adk4s-optimize % Test → verified` build dependency | build wiring (Test scope) | One-line `build.sbt` edit making the Ring 6 mirror visible to the bridge test. TASTy is backward compatible (3.8.4 reads 3.7.2); `verified` keeps depending on nothing project-local. |
| `Predict0[F, I, O]` | case class (placeholder) | Minimal predictor wrapping `StructuredLLM.completeTemplate`, enough to carry `PredictorState`. Replaced by real `Predict` in Phase 2. Implements `HasPredictorState`. |
| `OptimizeError` | enum (extends Throwable) | `UnknownPath(path: PredictorPath)`, `FrozenPath(path: PredictorPath)`. Stands alone in `org.adk4s.optimize` (NOT extending `AdkError` — design.md decision). |
| `OptimizerLaws` | testkit (main-scope munit) | Laws any `P => F[P]` optimizer must satisfy: student unchanged, frozen bit-identical, path-set preserved. Pattern follows `AgentMemoryLaws`. |
| `UppercaseInstructions` | toy optimizer (test only) | Rewrites every instruction string to uppercase; derivation exerciser. |
| `StaticDemoInjector` | toy optimizer (test only) | Appends a fixed `Demo` to every predictor; derivation exerciser. |
| `adk4s-optimize` | sbt module | New module: `dependsOn(structured-llm)`, deps `cats-effect`, `fs2-core`, `ujson` (transitive). |

## ADDED Requirements

### Requirement: predictor-state is pure immutable data

The system SHALL provide a predictor-state value with three fields: an instructions string, a list of demo examples (each an input/output pair of JSON values), and a frozen flag. The state SHALL be plain immutable data with no behavior.

**Given** a predictor-state with instructions "Answer the question", one demo, and frozen flag false
**When** the state's fields are read
**Then** the instructions string is "Answer the question", the demo list has one example, and the frozen flag is false

**Rationale**: This is the data every optimizer reads and writes. It must be plain immutable data (serializable-ready for Phase 2 persistence) with no behavior, so optimizers can manipulate it without knowing the predictor's concrete type.

#### Scenario: Default predictor-state

**Given** a predictor-state constructed with empty instructions, no demos, and frozen flag false
**When** the state's fields are read
**Then** the instructions string is empty, the demo list is empty, and the frozen flag is false

#### Scenario: Frozen predictor-state is constructible

**Given** a predictor-state with frozen flag true
**When** the frozen flag is read
**Then** it is true (a frozen state is a valid value — freezing is data, not a type-level distinction)

### Requirement: predictor path addressing

The system SHALL provide a predictor path value consisting of an ordered list of path segments where each segment is a case-class field name, outermost first. The path SHALL render to a dot-joined string for traces, save files, and logs.

**Given** a predictor path with segments ["outer", "inner"]
**When** the path is rendered
**Then** the result is the string "outer.inner"

**Rationale**: Optimizers address individual LM-call sites by stable paths. The path must be a plain data value (not tied to the program's type) so an optimizer can enumerate paths, store them, and re-apply updates across program values.

#### Scenario: Single-segment path

**Given** a predictor path with a single segment "answer"
**When** the path is rendered
**Then** the result is "answer"

#### Scenario: Collection-index segment

**Given** a predictor path with segments ["steps", "1"] addressing element 1 of a collection field
**When** the path is rendered
**Then** the result is "steps.1"

#### Scenario: Empty path

**Given** a predictor path with no segments
**When** the path is rendered
**Then** the result is the empty string (the empty path is a valid value used as a root marker; it is never returned by optimizable-surface/predictors)

### Requirement: optimizable-surface/predictors enumerates in declaration order

`optimizable-surface/predictors` SHALL enumerate every predictor field of a program with its path and current predictor-state, in field declaration order, with paths equal to the field names. Non-predictor fields SHALL NOT appear in the enumeration.

**Given** a program with two predictor fields `a` and `b` (states A and B), and a non-predictor field `extra`
**When** `optimizable-surface/predictors` is called on the program
**Then** the result is exactly [path `a` -> state A, path `b` -> state B] in that order, and `extra` does not appear

**Rationale**: Optimizers need a complete, ordered inventory of tunable sites. Declaration order is stable (structural derivation preserves it) and matches the program author's mental model. Non-predictor fields are ignored so a program can carry arbitrary non-tunable state.

#### Scenario: Single predictor

**Given** a program with one predictor field `answer`
**When** `optimizable-surface/predictors` is called
**Then** the result has exactly one entry with path `answer`

#### Scenario: No predictors

**Given** a program with no predictor fields (only plain fields)
**When** `optimizable-surface/predictors` is called
**Then** the result is empty

### Requirement: optimizable-surface/predictors recurses into nested sub-programs and collections

`optimizable-surface/predictors` SHALL recurse into fields that are themselves optimizable sub-programs (per the SubtreeDerivation sync), prefixing the field name to every nested path. `optimizable-surface/predictors` SHALL recurse into ordered collections of predictors (per the CollectionDerivation sync), using the element index as the path segment.

**Given** an outer program with a sub-program field `inner`, where the sub-program has a predictor field `leaf`
**When** `optimizable-surface/predictors` is called on the outer program
**Then** the nested predictor appears with path `inner.leaf`

**Rationale**: Real programs nest predictors inside sub-programs and collections. The surface must walk the structure without the optimizer knowing the concrete types. Ordered collections are supported now (Phase 0); keyed collections are deferred to Phase 3.

#### Scenario: Ordered collection of predictors

**Given** a program with a collection field `steps` containing 3 predictors
**When** `optimizable-surface/predictors` is called
**Then** the result has 3 entries with paths `steps.0`, `steps.1`, `steps.2` in order

#### Scenario: Empty collection of predictors

**Given** a program with a collection field `steps` containing no predictors
**When** `optimizable-surface/predictors` is called
**Then** the result is empty

#### Scenario: Nested sub-program with no predictors

**Given** an outer program with a sub-program field `inner` where the sub-program has no predictor fields
**When** `optimizable-surface/predictors` is called on the outer program
**Then** the result is empty (recursion still happens, but yields nothing)

### Requirement: optimizable-surface/update is pure

`optimizable-surface/update` SHALL return a new program value with only the addressed predictor's state changed by the given function. The input program SHALL NOT be observably modified. `optimizable-surface/update` SHALL be total on paths returned by `optimizable-surface/predictors`; for paths not enumerated, it SHALL raise an unknown-path error.

**Given** a program with a predictor at path `a` having state A
**When** `optimizable-surface/update` is called with path `a` and a function that uppercases the instructions
**Then** the result is a new program whose predictor at `a` has uppercased instructions, all other predictors are unchanged, and the input program is unchanged

**Rationale**: Optimizers produce new program values by composing updates. Purity is mandatory — an optimizer that mutated the student program would violate the optimizer laws and break reproducibility.

#### Scenario: Adversarial purity — before snapshot unchanged

**Given** a program and a snapshot of `optimizable-surface/predictors` taken before the update
**When** `optimizable-surface/update` is called and then `optimizable-surface/predictors` is re-called on the original program
**Then** the re-enumeration equals the before-snapshot exactly (the input is not mutated)

#### Scenario: Unknown path raises an error

**Given** a program whose `optimizable-surface/predictors` yields paths {a, b}
**When** `optimizable-surface/update` is called with path `zzz`
**Then** an unknown-path error carrying path `zzz` is raised

#### Scenario: Update one predictor leaves others unchanged

**Given** a program with predictors at {a, b} having states A and B
**When** `optimizable-surface/update` is called with path `a` and a function setting instructions to "X"
**Then** the result's predictor at `b` still has state B (bit-identical)

### Requirement: optimizable-surface/updateEither returns typed errors

`optimizable-surface/updateEither` SHALL return either the updated program or a typed error, never raising. It SHALL return an unknown-path error for a path not enumerated by `optimizable-surface/predictors`, and a frozen-path error when the addressed predictor's state has the frozen flag set. `optimizable-surface/update` SHALL be defined in terms of `optimizable-surface/updateEither`, raising the error on the left branch.

**Given** a program with a predictor at path `a` whose predictor-state has the frozen flag set
**When** `optimizable-surface/updateEither` is called with path `a`
**Then** the result is a frozen-path error carrying path `a`

**Rationale**: The error channel must be explicit and total. The safe variant is the public API optimizers use; the raising variant is the convenience for paths known to be valid and unfrozen. Spec-lint demands the error path either way.

#### Scenario: Unknown path returns an error

**Given** a program with paths {a, b}
**When** `optimizable-surface/updateEither` is called with path `zzz`
**Then** the result is an unknown-path error carrying path `zzz`

#### Scenario: Valid unfrozen path returns the updated program

**Given** a program with a predictor at path `a` having frozen flag false
**When** `optimizable-surface/updateEither` is called with path `a` and a function setting instructions to "X"
**Then** the result is the updated program where the predictor at `a` has instructions "X"

### Requirement: optimizable-surface/updateAll skips frozen predictors

`optimizable-surface/updateAll` SHALL apply the given state function to every predictor whose predictor-state has frozen flag false and SHALL exclude every predictor whose frozen flag is true. Frozen predictors SHALL appear unchanged (bit-identical state) in the result.

**Given** a program with predictors at {a, b} where `a` is frozen and `b` is not
**When** `optimizable-surface/updateAll` is called with a function that uppercases instructions
**Then** the result's predictor at `a` has its original state (bit-identical), and the predictor at `b` has uppercased instructions

**Rationale**: Most optimizers tune every non-frozen site in one pass. The frozen exclusion is the guard that lets a program author pin a site (e.g. a system prompt) and trust that no optimizer will touch it.

#### Scenario: Adversarial — frozen predictor never touched by updateAll

**Given** a program with a frozen predictor at `a` (state A with frozen flag true)
**When** `optimizable-surface/updateAll` is called with a function that overwrites instructions to "OVERWRITE"
**Then** the result's predictor at `a` still has state A exactly — the frozen flag cannot be bypassed via updateAll

#### Scenario: All frozen — updateAll is a no-op

**Given** a program where every predictor has the frozen flag set
**When** `optimizable-surface/updateAll` is called
**Then** the result equals the input program (no predictor is touched)

#### Scenario: All unfrozen — updateAll touches every predictor

**Given** a program where every predictor has frozen flag false
**When** `optimizable-surface/updateAll` is called with a function setting instructions to "X"
**Then** every predictor in the result has instructions "X"

### Requirement: Frozen predictors remain readable via optimizable-surface/predictors

A predictor whose predictor-state has the frozen flag set SHALL still appear in `optimizable-surface/predictors` output with the frozen flag visible. Freezing SHALL NOT hide a predictor from enumeration; it SHALL only exclude it from `optimizable-surface/updateAll` and cause `optimizable-surface/updateEither` on its exact path to fail with a frozen-path error.

**Given** a program with a frozen predictor at `a`
**When** `optimizable-surface/predictors` is called
**Then** the entry for `a` is present and its predictor-state has the frozen flag set

**Rationale**: Optimizers and tracing tools must be able to observe frozen sites (to report them, to skip them, to display them) even though they cannot mutate them. Hiding frozen predictors would make the path set depend on the frozen flag, violating the path-set preservation law.

#### Scenario: Frozen predictor visible in enumeration

**Given** a program with predictors {a (frozen), b (unfrozen)}
**When** `optimizable-surface/predictors` is called
**Then** both `a` and `b` appear, and `a`'s predictor-state has the frozen flag set

### Requirement: Round-trip identity law

For every path returned by `optimizable-surface/predictors`, calling `optimizable-surface/update` with the identity function SHALL return a program equal to the input.

**Given** a program and a path from `optimizable-surface/predictors`
**When** `optimizable-surface/update` is called with that path and the identity function
**Then** the result equals the input program

**Rationale**: The identity update is the canonical round-trip. If it changes anything, `optimizable-surface/update` is not a pure state replacement and the surface is broken.

#### Scenario: Round-trip on a two-predictor program

**Given** a program with two predictors
**When** `optimizable-surface/update` is called with the identity function at each enumerated path
**Then** every result equals the input program

#### Scenario: Round-trip on a collection-nested predictor

**Given** a program with a collection of 3 predictors
**When** `optimizable-surface/update` is called with the identity function at the path of element 1
**Then** the result equals the input program

### Requirement: Leaf-predictor capability

The system SHALL provide a leaf-predictor capability that exposes a predictor's predictor-state for reading and produces a new predictor with a replaced predictor-state for writing. A field with this capability SHALL be treated as a leaf predictor by the LeafDerivation sync (path = field name). The placeholder predictor SHALL implement this capability.

**Given** a placeholder predictor carrying predictor-state with instructions "instr", no demos, and frozen flag false
**When** the leaf-predictor capability reads the predictor's state
**Then** the result is the predictor-state with instructions "instr", no demos, and frozen flag false

**Rationale**: The leaf capability decouples "what is a predictor" from the derivation mechanism. The Phase-0 placeholder implements it; Phase 2's real predictors will implement the same capability and drop into derived optimizable-surface instances unchanged.

#### Scenario: Replacing state produces a new predictor

**Given** a placeholder predictor with predictor-state S1
**When** the leaf-predictor capability replaces the state with S2
**Then** the result is a new predictor whose state is S2, and the original predictor is unchanged

### Requirement: Placeholder predictor wraps StructuredLLM/completeTemplate

The system SHALL provide a placeholder predictor that wraps `StructuredLLM/completeTemplate`, carrying a predictor-state and an output Schema. The placeholder SHALL implement the leaf-predictor capability. The placeholder SHALL NOT render demos into prompts in Phase 0 (state is carried but not consumed).

**Given** a placeholder predictor carrying predictor-state with instructions "instr" and one demo
**When** the predictor's state is read via the leaf-predictor capability
**Then** the demo is present in the predictor-state but is NOT injected into any Prompt (Phase 0 scope)

**Rationale**: The placeholder exists only to exercise the leaf-predictor capability and structural derivation with a real predictor-shaped value. It is replaced by the real predictor in Phase 2. Demo rendering is explicitly out of scope for Phase 0.

#### Scenario: Placeholder state is serializable-ready

**Given** a predictor-state from a placeholder predictor
**When** the state's fields are inspected
**Then** all fields (instructions string, demo list, frozen flag) are plain immutable values suitable for Phase 2 serialization (no closures, no effect type, no live references)

### Requirement: Typed error ADT for update failures

The system SHALL provide a typed error ADT for update failures with two variants: an unknown-path error carrying the offending predictor path, and a frozen-path error carrying the offending predictor path. The error ADT SHALL stand alone in the optimize module (NOT extending the core error hierarchy). Every match over the error ADT SHALL be exhaustive — no catch-all arm is permitted.

**Given** an unknown-path error carrying path `a.b`
**When** it is matched
**Then** the match must handle both the unknown-path and frozen-path variants or compilation fails (no catch-all allowed)

**Rationale**: The error ADT is the typed channel for `optimizable-surface/update` and `optimizable-surface/updateEither` failures. It stands alone per the cross-phase convention (bridge to the core error hierarchy later) to keep the optimize module decoupled. Exhaustiveness is mandatory so adding a variant in a later phase is a compile error, not a silent fall-through.

#### Scenario: Unknown-path error carries the offending path

**Given** an unknown-path error for path `a.b`
**When** the error's path is read
**Then** it is the predictor path with segments ["a", "b"]

#### Scenario: Frozen-path error carries the offending path

**Given** a frozen-path error for path `a`
**When** the error's path is read
**Then** it is the predictor path with segments ["a"]

### Requirement: optimizable-surface/derived via structural derivation

`optimizable-surface/derived` SHALL produce an optimizable-surface for any case-class program with no hand-written instance, following the LeafDerivation, SubtreeDerivation, and CollectionDerivation syncs. Each field with the leaf-predictor capability contributes a leaf; each field with its own optimizable-surface contributes its subtree; each ordered-collection field of predictors contributes indexed leaves; other fields are ignored.

**Given** a case-class program with a mix of predictor fields, optimizable sub-program fields, ordered-collection-of-predictor fields, and plain fields
**When** `optimizable-surface/derived` is summoned and `optimizable-surface/predictors` is called
**Then** leaves and subtree predictors appear with correctly prefixed paths, in declaration order, and plain fields do not appear

**Rationale**: Structural derivation is the Scala-3-native way to walk a product structure without runtime reflection. The mixed leaf/subtree/collection rule is the design-freezing risk called out in the plan; this requirement pins it.

#### Scenario: Derivation for a mixed program

**Given** a program with a leaf predictor field `leaf`, a sub-program field `sub` containing a predictor `inner`, a collection field `vec` of 2 predictors, and a plain field `plain`
**When** `optimizable-surface/predictors` is called
**Then** paths are `leaf`, `sub.inner`, `vec.0`, `vec.1` in declaration order, and `plain` does not appear

#### Scenario: Derivation requires no hand-written instance

**Given** a case-class program with only predictor fields
**When** `optimizable-surface/derived` is summoned
**Then** an optimizable-surface instance is available with no hand-written declaration

### Requirement: Two toy optimizers compile the same program through one optimizable-surface instance

The system SHALL include two structurally different toy optimizers that compile the same two-predictor toy program through the same derived optimizable-surface instance, without any type-level knowledge of the program beyond that instance. One toy optimizer SHALL rewrite every non-frozen predictor's instructions to uppercase via `optimizable-surface/updateAll`. The other SHALL append a fixed demo to every non-frozen predictor's demo list via `optimizable-surface/updateAll`. Both SHALL pass the optimizer laws.

**Given** a two-predictor toy program and a derived optimizable-surface for it
**When** the instruction-rewriting toy optimizer and the demo-injecting toy optimizer each compile the program
**Then** both produce a new program, both pass the optimizer laws, and neither has type-level knowledge of the program beyond the optimizable-surface instance

**Rationale**: This is the acceptance test for the surface. If two structurally different optimizers can compile the same program through one surface instance and pass the laws, the surface is usable by unknown future optimizers.

#### Scenario: Instruction-rewriting optimizer uppercases instructions

**Given** a program with two unfrozen predictors having instructions "a" and "b"
**When** the instruction-rewriting toy optimizer compiles the program via `optimizable-surface/updateAll`
**Then** both predictors have uppercased instructions ("A", "B")

#### Scenario: Demo-injecting optimizer appends a demo

**Given** a program with two unfrozen predictors each having an empty demo list
**When** the demo-injecting toy optimizer compiles the program via `optimizable-surface/updateAll`
**Then** both predictors have a demo list of size 1 (the fixed demo)

#### Scenario: Both toy optimizers leave frozen predictors untouched

**Given** a program with one frozen predictor at `a` and one unfrozen at `b`
**When** either toy optimizer compiles the program
**Then** the predictor at `a` is bit-identical to its input state

### Requirement: Optimizer laws testkit

The system SHALL provide an optimizer-laws testkit with three laws that any optimizer claiming law-compliance must satisfy: (a) purity — the student program passed in is unchanged after compile; (b) frozen-preserved — frozen predictors' states are bit-identical in the result; (c) path-set-preserved — the result has the same predictor path set as the student (optimizers tune state, never structure). The testkit SHALL follow the main-scope laws-suite pattern established by the memory laws testkit.

**Given** an optimizer and a program with a known optimizable-surface
**When** the optimizer laws are checked against the optimizer and the program
**Then** the result is true if and only if all three laws hold

**Rationale**: The laws are the contract every future optimizer (bootstrap, MIPRO, GEPA, instruction optimizer) must satisfy. Shipping them in Phase 0 means later phases write optimizers against a fixed contract, not an ad-hoc set of assertions.

#### Scenario: Laws pass for the instruction-rewriting optimizer

**Given** the instruction-rewriting toy optimizer and a two-predictor toy program
**When** the optimizer laws are checked
**Then** the result is true

#### Scenario: Laws pass for the demo-injecting optimizer

**Given** the demo-injecting toy optimizer and a two-predictor toy program
**When** the optimizer laws are checked
**Then** the result is true

#### Scenario: Laws fail for an optimizer that mutates structure

**Given** a buggy optimizer that adds a new predictor path to the result (violating path-set-preserved)
**When** the optimizer laws are checked
**Then** the result is false

#### Scenario: Laws fail for an optimizer that mutates the student

**Given** a buggy optimizer that mutates the input program's state (violating purity)
**When** the optimizer laws are checked
**Then** the result is false

### Requirement: Optimize module skeleton

The system SHALL provide a new optimize module that depends only on the structured-LLM capability, the effect system, and the streaming core. The module SHALL NOT depend on the orchestration layer, the tool-execution layer, the workflow engine, or the LLM client directly. The module SHALL be aggregated by the root build so a top-level compile includes it.

**Given** the new module is added to the build
**When** the module is compiled in isolation
**Then** it compiles without requiring the orchestration layer, the tool-execution layer, the workflow engine, or the LLM client

**Rationale**: The module is the home for the erased surface and all future optimizers. It depends only on the structured-LLM capability (for the placeholder predictor's wrapper) and effect/streaming libs, keeping it decoupled from the orchestration and tool-execution layers.

#### Scenario: Module compiles independently

**Given** the new module with its main sources
**When** an isolated compile of the module is performed
**Then** it compiles successfully

#### Scenario: Module tests pass

**Given** the new module with its test suite
**When** the module's tests are run
**Then** all tests pass including the optimizer-law checks and the Ring 3 properties

#### Scenario: Module has no forbidden dependencies

**Given** the new module's dependency tree
**When** the tree is inspected
**Then** it contains no orchestration-layer, tool-execution, workflow-engine, or direct LLM-client dependencies

## Properties (Ring 3)

### Property: predictors-declaration-order

**Invariant**: For all case-class programs with a derived optimizable-surface, `optimizable-surface/predictors` returns entries in field declaration order, and the set of paths equals exactly the predictor-bearing fields (leaves + subtree + collection-indexed), with non-predictor fields absent.

**Generator strategy**: `genTwoPredictorProgram: Gen[TwoPredictors]` — constructive, builds placeholder-predictor instances with random `instructions` (Gen.string1) and random `frozen` (Gen.boolean). Covers frozen/unfrozen. Classify by frozen-count.

```
forAll { (instrA: String, instrB: String, frozenA: Boolean, frozenB: Boolean) =>
  val p = TwoPredictors(pred(instrA, frozenA), pred(instrB, frozenB), extra = "x")
  val paths = Optimizable[TwoPredictors].predictors(p).map(_._1)
  paths === Vector(PredictorPath(Vector("a")), PredictorPath(Vector("b")))
}
```

### Property: nested-recursion-paths

**Invariant**: For all nested programs where an outer program has a sub-program field containing a predictor leaf, `optimizable-surface/predictors` yields exactly one entry with the prefixed path (per SubtreeDerivation sync).

**Generator strategy**: `genNestedProgram: Gen[Outer]` — constructive, random `instructions` for the leaf. Covers single-nest depth (Phase 0 scope).

```
forAll { (instr: String) =>
  val p = Outer(Inner(Predict0(..., PredictorState(instr, Vector.empty, false))))
  val paths = Optimizable[Outer].predictors(p).map(_._1)
  paths === Vector(PredictorPath(Vector("inner", "leaf")))
}
```

### Property: collection-recursion-paths

**Invariant**: For all programs with an ordered-collection field of n predictors, `optimizable-surface/predictors` yields exactly n entries with index-segment paths in order (per CollectionDerivation sync).

**Generator strategy**: `genPipeline: Gen[Pipeline]` — constructive, `Gen.int(Range.linear(0, 5)).flatMap(n => Gen.list(Range.linear(0, n), genPredict0))` lifted to Vector. Covers empty (n=0) and non-empty. Classify by size bucket {0, 1, 2-5}.

```
forAll { (n: Int) =>
  val steps = (0 until n).map(i => pred(s"step$i", false)).toVector
  val p = Pipeline(steps)
  val paths = Optimizable[Pipeline].predictors(p).map(_._1)
  paths === (0 until n).map(i => PredictorPath(Vector("steps", i.toString))).toVector
}
```

### Property: update-purity

**Invariant**: For all programs, all enumerated paths, and all state functions, `optimizable-surface/update` does not observably modify the input program: `optimizable-surface/predictors` before and after the call is equal.

**Generator strategy**: `genProgramAndPath: Gen[(TwoPredictors, PredictorPath)]` — constructive, builds a two-predictor program and selects one of its enumerated paths. `genStateFn: Gen[PredictorState => PredictorState]` — constructive, random instruction rewrite + random demo append.

```
forAll { (p: TwoPredictors, path: PredictorPath, f: PredictorState => PredictorState) =>
  val before = Optimizable[TwoPredictors].predictors(p)
  val _ = Optimizable[TwoPredictors].update(p, path, f)
  val after = Optimizable[TwoPredictors].predictors(p)
  before === after
}
```

### Property: update-only-target

**Invariant**: For all programs with predictors {(path_i, state_i)} and a chosen path_k, `optimizable-surface/update` at path_k changes only the state at path_k; all other states are bit-identical in the result.

**Generator strategy**: `genProgramAndPath` (as above) + `genStateFn`. Classify by whether the targeted predictor is frozen (expecting the raising update to fail — covered by the error-path property below for the safe variant).

```
forAll { (p: TwoPredictors, path: PredictorPath, f: PredictorState => PredictorState) =>
  val before = Optimizable[TwoPredictors].predictors(p).toMap
  val result = Optimizable[TwoPredictors].update(p, path, f)
  val after = Optimizable[TwoPredictors].predictors(result).toMap
  after.keySet === before.keySet &&
  after.removed(path) === before.removed(path)
}
```

### Property: round-trip-identity

**Invariant**: For all programs and all enumerated paths, `optimizable-surface/update` with the identity function returns a program equal to the input.

**Generator strategy**: `genProgramAndPath` (as above).

```
forAll { (p: TwoPredictors, path: PredictorPath) =>
  Optimizable[TwoPredictors].update(p, path, identity) === p
}
```

### Property: updateAll-skips-frozen

**Invariant**: For all programs and all state functions, `optimizable-surface/updateAll` leaves every frozen predictor's state bit-identical to its input state.

**Generator strategy**: `genProgramWithFrozen: Gen[TwoPredictors]` — constructive, at least one predictor frozen. `genStateFn`. Classify by frozen-count {1, 2}.

```
forAll { (p: TwoPredictors, f: (PredictorPath, PredictorState) => PredictorState) =>
  val frozenBefore = Optimizable[TwoPredictors].predictors(p).filter(_._2.frozen)
  val result = Optimizable[TwoPredictors].updateAll(p, f)
  val frozenAfter = Optimizable[TwoPredictors].predictors(result).filter(_._2.frozen)
  frozenBefore === frozenAfter
}
```

### Property: updateAll-path-set-preserved

**Invariant**: For all programs and all state functions, the path set of `optimizable-surface/updateAll` result equals the path set of the input.

**Generator strategy**: `genProgramAndPath` + `genStateFn`.

```
forAll { (p: TwoPredictors, f: (PredictorPath, PredictorState) => PredictorState) =>
  val beforePaths = Optimizable[TwoPredictors].predictors(p).map(_._1).toSet
  val afterPaths = Optimizable[TwoPredictors].predictors(Optimizable[TwoPredictors].updateAll(p, f)).map(_._1).toSet
  beforePaths === afterPaths
}
```

### Property: updateEither-unknown-path-error

**Invariant**: For all programs and all paths not in the enumerated path set, `optimizable-surface/updateEither` returns a left unknown-path error carrying the offending path.

**Generator strategy**: `genProgramAndBadPath: Gen[(TwoPredictors, PredictorPath)]` — constructive, builds a program and a path not in its enumeration (e.g. path `zzz`).

```
forAll { (p: TwoPredictors, badPath: PredictorPath) =>
  Optimizable[TwoPredictors].updateEither(p, badPath, identity) === Left(OptimizeError.UnknownPath(badPath))
}
```

### Property: updateEither-frozen-path-error

**Invariant**: For all programs and all paths addressing a frozen predictor, `optimizable-surface/updateEither` returns a left frozen-path error carrying the offending path.

**Generator strategy**: `genProgramWithFrozenAndFrozenPath: Gen[(TwoPredictors, PredictorPath)]` — constructive, builds a program with a frozen predictor and selects its path.

```
forAll { (p: TwoPredictors, frozenPath: PredictorPath) =>
  Optimizable[TwoPredictors].updateEither(p, frozenPath, identity) === Left(OptimizeError.FrozenPath(frozenPath))
}
```

### Property: frozen-still-readable

**Invariant**: For all programs, every frozen predictor appears in `optimizable-surface/predictors` output with the frozen flag visible in its predictor-state.

**Generator strategy**: `genProgramWithFrozen` (as above).

```
forAll { (p: TwoPredictors) =>
  val frozen = Optimizable[TwoPredictors].predictors(p).filter(_._2.frozen)
  frozen.nonEmpty ==> frozen.forall { case (_, s) => s.frozen === true }
}
```

### Property: optimizer-laws-purity

**Invariant**: For all programs and both toy optimizers, the student program is unchanged after compile (purity law).

**Generator strategy**: `genTwoPredictorProgram` (as above). Run for both toy optimizers.

```
forAll { (p: ToyProgram) =>
  for {
    before <- IO.pure(Optimizable[ToyProgram].predictors(p))
    _      <- UppercaseInstructions[IO, ToyProgram].compile(p)
    after  <- IO.pure(Optimizable[ToyProgram].predictors(p))
  } yield before === after
}
```

### Property: optimizer-laws-frozen-preserved

**Invariant**: For all programs with at least one frozen predictor and both toy optimizers, the frozen predictors' states are bit-identical in the result (frozen-preserved law).

**Generator strategy**: `genProgramWithFrozen` (as above).

```
forAll { (p: ToyProgram) =>
  for {
    frozenBefore <- IO.pure(Optimizable[ToyProgram].predictors(p).filter(_._2.frozen))
    result       <- UppercaseInstructions[IO, ToyProgram].compile(p)
    frozenAfter  <- IO.pure(Optimizable[ToyProgram].predictors(result).filter(_._2.frozen))
  } yield frozenBefore === frozenAfter
}
```

### Property: optimizer-laws-path-set-preserved

**Invariant**: For all programs and both toy optimizers, the result's path set equals the input's path set (path-set-preserved law).

**Generator strategy**: `genTwoPredictorProgram` (as above).

```
forAll { (p: ToyProgram) =>
  for {
    beforePaths <- IO.pure(Optimizable[ToyProgram].predictors(p).map(_._1).toSet)
    result      <- StaticDemoInjector[IO, ToyProgram].compile(p)
    afterPaths  <- IO.pure(Optimizable[ToyProgram].predictors(result).map(_._1).toSet)
  } yield beforePaths === afterPaths
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A catch-all `case _ => ...` in a match over the error ADT | Exhaustiveness escalation makes this a Ring 0 compile error; the spec mandates exhaustive matches | `assertDoesNotCompile("e match { case OptimizeError.UnknownPath(_) => () }")` — missing `FrozenPath` fails to compile |
| The error ADT extending the core error hierarchy | The spec mandates it stands alone in the optimize module (design.md decision); extending the core hierarchy would couple the optimize module to the core module | manual review (Ring 8) — verify the enum declaration does not extend `AdkError` |
| `optimizable-surface/update` returning `Unit` or mutating the input in place | The spec mandates purity; the update returns a new program | type system — update signature returns `P`; WartRemover `Var`/`throw` warts guard against in-place mutation |

## Formal Contracts (Ring 6)

Ring 6 applies via the VERIFIED-MIRROR pattern
(`openspec/schemas/verified-scala3/templates/verified-mirror.md`). The shipped
`Optimizable` cannot be verified directly — it uses `Mirror`/`inline`
derivation and `ujson.Value` payloads, and the Stainless frontend is pinned to
Scala 3.7.2 while this build is 3.8.4. Neither is grounds to skip: the
*algorithm* under `optimizable-surface/predictors` is a pure pre-order
traversal, and it survives reduction to observable effect.

### The abstraction

A program becomes a tree; a predictor-state becomes a `BigInt` identity; a path
becomes the `List[BigInt]` of child indices that reaches a leaf. Field
declaration order becomes list order — which is the whole point, since
declaration order is the law under proof.

```scala
// verified/src/main/scala/org/adk4s/verified/PredictorKernel.scala  (Scala 3.7.2)
sealed abstract class Prog
case class Pred(frozen: Boolean, state: BigInt) extends Prog  // a predictor leaf
case class Plain()                              extends Prog  // a non-predictor field
case class Sub(fields: List[Prog])              extends Prog  // nested program, decl order
case class Coll(elems: List[Prog])              extends Prog  // ordered collection

def paths(p: Prog, prefix: List[BigInt]): List[List[BigInt]]   // pre-order leaf paths
def updateAll(p: Prog, f: BigInt => BigInt): Prog              // skips frozen leaves
```

### Contract: paths — enumeration is complete, ordered, and predictor-only

**Precondition** (`require`): none — `paths` is total over `Prog`.
**Postcondition** (`ensuring`): the result length equals the number of `Pred`
leaves in the tree (completeness + `Plain` exclusion), and every returned path
is a valid index chain into the tree (well-formedness). Pre-order traversal of
`Sub`/`Coll` in list order gives declaration order.

```scala
def paths(p: Prog, prefix: List[BigInt]): List[List[BigInt]] = {
  decreases(p)
  p match {
    case Pred(_, _) => List(prefix)
    case Plain()    => Nil[List[BigInt]]()
    case Sub(fs)    => indexed(fs, prefix)
    case Coll(es)   => indexed(es, prefix)
  }
}.ensuring(_.size == countPreds(p))
```

### Contract: updateAll — path set preserved, frozen leaves untouched

**Precondition** (`require`): none.
**Postcondition** (`ensuring`): `paths(updateAll(p, f)) == paths(p)` (the update
changes states, never the shape), and every `Pred` whose `frozen` flag is set is
returned bit-identical.

```scala
def updateAll(p: Prog, f: BigInt => BigInt): Prog = {
  decreases(p)
  p match {
    case Pred(fr, st) => if (fr) Pred(fr, st) else Pred(fr, f(st))
    case Plain()      => Plain()
    case Sub(fs)      => Sub(fs.map(updateAll(_, f)))
    case Coll(es)     => Coll(es.map(updateAll(_, f)))
  }
}.ensuring(r => paths(r, Nil()) == paths(p, Nil()) && frozenStates(r) == frozenStates(p))
```

### Contract: round-trip identity

**Postcondition** (`ensuring`): `updateAll(p, x => x) == p` — updating with the
identity function returns an equal program.

### Bridge — how the shipped code is bound to the model

`PredictorModelBridgeSpec` (Hedgehog, in `adk4s-optimize` test sources) runs the
real `Optimizable` and the model on the SAME generated programs and asserts they
agree on exactly the proven invariants:

1. the real `optimizable-surface/predictors` path sequence, mapped to index
   chains, equals `PredictorKernel.paths` element-for-element (order included);
2. after `optimizable-surface/updateAll`, the real path set equals the model's;
3. the real frozen predictors are bit-identical exactly where the model's are.

Build wiring this spec commits to: `adk4s-optimize dependsOn(verified % Test)`.
TASTy is backward compatible, so the 3.8.4 module may read the 3.7.2 artifact —
never the reverse, which is why `verified` depends on nothing project-local.
`stainlessEnabled := false` by default, so the bridge test pays only a plain
compile of the model; verification is the separate `sbt -J-Xmx6g ring6` step.

### Scope — what is proven, and what is delegated

Target proofs (best-effort): the three contracts above, all quantifier-free or
structurally inductive with `decreases` measures.

DELEGATED to Ring 3, and named here rather than dropped:

| Law | Why not proven here | Covered by |
|---|---|---|
| Real derivation emits fields in *source* declaration order | `Mirror`-level property of the compiler, outside any PureScala model | Property: predictors-declaration-order |
| Nested/collection path prefixing matches the real derivation | the model proves the traversal shape; the *encoding* of segments is production behaviour | Property: nested-recursion-paths, Property: collection-recursion-paths |
| `updateEither` typed-error behaviour | error algebra is not modelled (no `ujson`/error ADT in PureScala) | Property: updateEither-unknown-path-error, Property: updateEither-frozen-path-error |

If a target VC diverges in z3 (the classic case being a `forall`/`exists`
quantifier), it moves into this table with its Ring 3 property named — it is
never silently dropped.

## Temporal Properties (Ring 9)

> Ring 9 does not apply (no telemetry stack detected). This section is
> intentionally omitted.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Predictor-state exposes instructions, demos and frozen flag as read data | Requirement: predictor-state is pure immutable data | scenario test | `Predict0Spec.scala` |
| Default predictor-state is empty instructions, no demos, not frozen | Requirement: predictor-state is pure immutable data + Scenario: Default predictor-state | scenario test | `Predict0Spec.scala` |
| A frozen predictor-state is constructible (freezing is data, not a type) | Requirement: predictor-state is pure immutable data + Scenario: Frozen predictor-state is constructible | scenario test | `Predict0Spec.scala` |
| Predictor path renders dot-joined, outermost segment first | Requirement: predictor path addressing | scenario test | `OptimizableSpec.scala` |
| Single-segment and collection-index paths render without separators/indices lost | Requirement: predictor path addressing + Scenario: Single-segment path + Scenario: Collection-index segment | scenario test | `OptimizableSpec.scala` |
| Empty path renders to the empty string and is never returned by `optimizable-surface/predictors` | Requirement: predictor path addressing + Scenario: Empty path | scenario test | `OptimizableSpec.scala` |
| `optimizable-surface/predictors` returns declaration order | Requirement: optimizable-surface/predictors enumerates in declaration order + Property: predictors-declaration-order | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/predictors` recurses into sub-programs (SubtreeDerivation) | Requirement: optimizable-surface/predictors recurses into nested sub-programs and collections + Property: nested-recursion-paths | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/predictors` recurses into collections (CollectionDerivation) | Requirement: optimizable-surface/predictors recurses into nested sub-programs and collections + Property: collection-recursion-paths | Hedgehog property | `OptimizableSpec.scala` |
| Non-predictor fields absent from `optimizable-surface/predictors` | Requirement: optimizable-surface/predictors enumerates in declaration order + Property: predictors-declaration-order | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/update` is pure (input unchanged) | Requirement: optimizable-surface/update is pure + Property: update-purity | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/update` changes only the targeted predictor | Requirement: optimizable-surface/update is pure + Property: update-only-target | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/update` raises unknown-path error for non-enumerated paths | Requirement: optimizable-surface/update is pure + Scenario: Unknown path raises an error | scenario test | `OptimizableSpec.scala` |
| `optimizable-surface/updateEither` returns unknown-path error for bad paths | Requirement: optimizable-surface/updateEither returns typed errors + Property: updateEither-unknown-path-error | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/updateEither` returns frozen-path error for frozen paths | Requirement: optimizable-surface/updateEither returns typed errors + Property: updateEither-frozen-path-error | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/updateEither` returns updated program for valid unfrozen paths | Requirement: optimizable-surface/updateEither returns typed errors + Scenario: Valid unfrozen path returns the updated program | scenario test | `OptimizableSpec.scala` |
| `optimizable-surface/updateAll` skips frozen predictors | Requirement: optimizable-surface/updateAll skips frozen predictors + Property: updateAll-skips-frozen | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/updateAll` preserves path set | Requirement: optimizable-surface/updateAll skips frozen predictors + Property: updateAll-path-set-preserved | Hedgehog property | `OptimizableSpec.scala` |
| `optimizable-surface/updateAll` is a no-op when all frozen | Requirement: optimizable-surface/updateAll skips frozen predictors + Scenario: All frozen — updateAll is a no-op | scenario test | `OptimizableSpec.scala` |
| Frozen predictors remain readable via `optimizable-surface/predictors` | Requirement: Frozen predictors remain readable via optimizable-surface/predictors + Property: frozen-still-readable | Hedgehog property | `OptimizableSpec.scala` |
| Round-trip identity: `optimizable-surface/update` with identity returns equal program | Requirement: Round-trip identity law + Property: round-trip-identity | Hedgehog property | `OptimizableSpec.scala` |
| Leaf-predictor capability state/withState round-trip | Requirement: Leaf-predictor capability + Scenario: Replacing state produces a new predictor | scenario test | `Predict0Spec.scala` |
| Placeholder predictor carries state but does not render demos | Requirement: Placeholder predictor wraps StructuredLLM/completeTemplate + Scenario: Placeholder state is serializable-ready | scenario test | `Predict0Spec.scala` |
| Unknown-path and frozen-path errors carry the offending path | Requirement: Typed error ADT for update failures + Scenario: Unknown-path error carries the offending path + Scenario: Frozen-path error carries the offending path | scenario test | `OptimizableSpec.scala` |
| Error ADT is exhaustive (no catch-all) | Requirement: Typed error ADT for update failures + Compile-Negative: catch-all arm over the error ADT | compile-negative test + exhaustiveness escalation (Ring 0) | `OptimizableSpec.scala` |
| Error ADT stands alone (not core error hierarchy) | Requirement: Typed error ADT for update failures + Compile-Negative: error ADT extending the core hierarchy | manual review (Ring 8) | adversarial review |
| `optimizable-surface/derived` works with no hand-written instance | Requirement: optimizable-surface/derived via structural derivation + Scenario: Derivation requires no hand-written instance | scenario test (summonInline) | `OptimizableSpec.scala` |
| Derivation covers a mixed program (leaf + nested + collection fields) | Requirement: optimizable-surface/derived via structural derivation + Scenario: Derivation for a mixed program | scenario test | `OptimizableSpec.scala` |
| Two toy optimizers compile same program via one optimizable-surface | Requirement: Two toy optimizers compile the same program through one optimizable-surface instance | scenario test | `ToyOptimizerSpec.scala` |
| Instruction-rewriting optimizer uppercases non-frozen instructions | Requirement: Two toy optimizers compile the same program through one optimizable-surface instance + Scenario: Instruction-rewriting optimizer uppercases instructions | scenario test | `ToyOptimizerSpec.scala` |
| Demo-injecting optimizer appends a demo to non-frozen predictors | Requirement: Two toy optimizers compile the same program through one optimizable-surface instance + Scenario: Demo-injecting optimizer appends a demo | scenario test | `ToyOptimizerSpec.scala` |
| Both toy optimizers leave frozen predictors untouched | Requirement: Two toy optimizers compile the same program through one optimizable-surface instance + Scenario: Both toy optimizers leave frozen predictors untouched | scenario test | `ToyOptimizerSpec.scala` |
| Optimizer laws purity law | Requirement: Optimizer laws testkit + Property: optimizer-laws-purity | Hedgehog property | `OptimizerLawsSpec.scala` |
| Optimizer laws frozen-preserved law | Requirement: Optimizer laws testkit + Property: optimizer-laws-frozen-preserved | Hedgehog property | `OptimizerLawsSpec.scala` |
| Optimizer laws path-set-preserved law | Requirement: Optimizer laws testkit + Property: optimizer-laws-path-set-preserved | Hedgehog property | `OptimizerLawsSpec.scala` |
| Laws pass for both toy optimizers | Requirement: Optimizer laws testkit + Scenario: Laws pass for the instruction-rewriting optimizer + Scenario: Laws pass for the demo-injecting optimizer | scenario test | `OptimizerLawsSpec.scala` |
| Optimizer laws fail for a structure-mutating optimizer | Requirement: Optimizer laws testkit + Scenario: Laws fail for an optimizer that mutates structure | scenario test (negative) | `OptimizerLawsSpec.scala` |
| Optimizer laws fail for a student-mutating optimizer | Requirement: Optimizer laws testkit + Scenario: Laws fail for an optimizer that mutates the student | scenario test (negative) | `OptimizerLawsSpec.scala` |
| Optimize module compiles independently | Requirement: Optimize module skeleton + Scenario: Module compiles independently | build verification (compile the module in isolation) | build verification |
| Optimize module tests pass | Requirement: Optimize module skeleton + Scenario: Module tests pass | module test run | build verification |
| Optimize module has no forbidden dependencies | Requirement: Optimize module skeleton + Scenario: Module has no forbidden dependencies | dependency-tree inspection + manual review (Ring 8) | adversarial review |
| Enumeration is complete, ordered and predictor-only (model) | Requirement: optimizable-surface/predictors enumerates in declaration order + Invariant: paths returns one entry per Pred leaf, in pre-order | formal contract (Ring 6) — `paths` ensuring clause, verified by Stainless | `PredictorKernel` |
| updateAll preserves the path set and leaves frozen leaves bit-identical (model) | Requirement: optimizable-surface/updateAll skips frozen predictors + Invariant: paths(updateAll(p, f)) == paths(p) | formal contract (Ring 6) — `updateAll` ensuring clause | `PredictorKernel` |
| Round-trip identity holds for all programs (model) | Requirement: Round-trip identity law + Invariant: updateAll(p, identity) == p | formal contract (Ring 6) | `PredictorKernel` |
| Shipped Optimizable conforms to the verified model | Requirement: optimizable-surface/predictors enumerates in declaration order + Requirement: optimizable-surface/predictors recurses into nested sub-programs and collections | bridge property test (Ring 3 + Ring 6) — real and model on the same generated programs, asserting path order, path set after updateAll, and frozen identity | `PredictorModelBridgeSpec` |
| PredictorPath is distinct from FieldPath (different domain) | Requirement: predictor path addressing + Criterion: inventory-check concept separation | manual review (Ring 8) | adversarial review |
| Collection-nested predictor round-trips | Requirement: optimizable-surface/predictors recurses into nested sub-programs and collections + Property: round-trip-identity on collection-nested + Criterion: Phase 0 exit | Hedgehog property | `OptimizableSpec.scala` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `PredictorKernel.scala` | new file (Ring 6 model) | `verified/src/main/scala/org/adk4s/verified/` | PureScala, Scala 3.7.2. Uses `stainless.collection.List`; `decreases` measures on `paths`/`updateAll`. Verified by `sbt -J-Xmx6g ring6`. |
| `PredictorModelBridgeSpec.scala` | new file (bridge test) | `adk4s-optimize/src/test/scala/org/adk4s/optimize/` | Hedgehog. Maps a generated program to `Prog`, compares real vs model. Compiles the model only — `stainlessEnabled := false` keeps the solver out of `test`. |
| `adk4s-optimize dependsOn(verified % Test)` | build step | `build.sbt` | Ring 6 bridge precondition; add when the module is created. |
| `Demo` | case class | `adk4s-optimize/src/main/scala/org/adk4s/optimize/Demo.scala` | new file |
| `PredictorState` | case class | `adk4s-optimize/src/main/scala/org/adk4s/optimize/PredictorState.scala` | new file |
| `PredictorPath` | case class | `adk4s-optimize/src/main/scala/org/adk4s/optimize/PredictorPath.scala` | new file; `render` joins segments with `.` |
| `Optimizable[P]` | typeclass | `adk4s-optimize/src/main/scala/org/adk4s/optimize/Optimizable.scala` | new file; `inline def derived` via `Mirror.ProductOf[P]` |
| `HasPredictorState[Self]` | typeclass | `adk4s-optimize/src/main/scala/org/adk4s/optimize/HasPredictorState.scala` | new file |
| `PredictorKernel` | PureScala model (Ring 6, `verified` module, Scala 3.7.2) | Verified mirror of the predictor-enumeration algorithm: `Prog` tree (`Pred`/`Plain`/`Sub`/`Coll`), `paths` (pre-order leaf paths as `List[BigInt]` index chains), `updateAll`. Proves declaration-order enumeration, non-predictor exclusion, path-set preservation and round-trip identity. Bound to the shipped `Optimizable` by `PredictorModelBridgeSpec`. |
| `adk4s-optimize % Test → verified` build dependency | build wiring (Test scope) | One-line `build.sbt` edit making the Ring 6 mirror visible to the bridge test. TASTy is backward compatible (3.8.4 reads 3.7.2); `verified` keeps depending on nothing project-local. |
| `Predict0[F, I, O]` | case class (placeholder) | `adk4s-optimize/src/main/scala/org/adk4s/optimize/Predict0.scala` | new file; wraps `StructuredLLM.completeTemplate`; implements `HasPredictorState` |
| `OptimizeError` | enum (extends Throwable) | `adk4s-optimize/src/main/scala/org/adk4s/optimize/OptimizeError.scala` | new file; `UnknownPath`, `FrozenPath`; stands alone (NOT `AdkError`) |
| `OptimizerLaws` | testkit (main-scope munit) | `adk4s-optimize/src/main/scala/org/adk4s/optimize/OptimizerLaws.scala` (or a `org.adk4s.optimize.testkit` companion — design.md) | new file; pattern follows `AgentMemoryLaws` |
| `UppercaseInstructions` | toy optimizer (test only) | `adk4s-optimize/src/test/scala/org/adk4s/optimize/UppercaseInstructions.scala` | new file |
| `StaticDemoInjector` | toy optimizer (test only) | `adk4s-optimize/src/test/scala/org/adk4s/optimize/StaticDemoInjector.scala` | new file |
| `adk4s-optimize` | sbt module | `build.sbt` + `project/Dependencies.scala` | new module; `dependsOn(structured-llm)`; deps `cats-effect`, `fs2-core`; test deps `munit`, `munit-cats-effect`, `hedgehog-munit`; aggregated by root |
| `optimizable-surface` concept doc | type/concept doc | `openspec/concepts/optimizable-surface.md` | created at apply Step 12 |
| `predictor-state` concept doc | type/concept doc | `openspec/concepts/predictor-state.md` | created at apply Step 12 |
| Module graph update | doc update | `openspec/project.md`, `openspec/capability-profile.md` | apply Step 12 adds `adk4s-optimize` to the module graph |
| Compile command | build step | `sbt "adk4s-optimize/compile"`, `sbt "adk4s-optimize/test"` | per-module compile/test |
