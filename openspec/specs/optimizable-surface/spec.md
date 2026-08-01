# optimizable-surface Specification

## Purpose
TBD - created by archiving change add-optimizable-surface. Update Purpose after archive.
## Requirements
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

