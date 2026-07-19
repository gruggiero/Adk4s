# Design: [Change Title]

## Package Structure

<!-- Define the package layout and layer dependency rules.
     These rules are enforced by the architecture rules (Ring 2) and must
     be PROJECT-SPECIFIC: derive the forbidden imports from the stack
     detected in openspec/capability-profile.md. -->

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| Domain (pure) | `[base].domain` | Nothing (self-contained) | <!-- detected actor framework, protobuf runtime, HTTP, messaging, DB clients --> | No outbound imports |
| Service | `[base].service` | Domain only | <!-- infrastructure libs --> | `allowed: { from: service, to: [domain] }` |
| Actor/Runtime | `[base].runtime` | Domain, Service | — | <!-- may import actors, persistence, messaging --> |
| Endpoint | `[base].endpoint` | Service, generated API models | — | <!-- may import HTTP framework --> |
| Generated code | <!-- path --> | — | — | Excluded from checks: <!-- which --> |

### New Packages

<!-- List any new packages this change introduces, with their layer assignment. -->

| Package | Layer | Purpose |
|---------|-------|---------|
| <!-- e.g. domain.transfer --> | <!-- Domain --> | <!-- Transfer-related value types --> |

## Effect Boundaries

<!-- Clearly separate pure code (formal verification candidates) from
     effectful code. This drives Ring 6 applicability. Actor state
     transitions should delegate to pure functions wherever possible. -->

### Pure Code (Ring 6 candidates)

<!-- Functions with no side effects, no I/O, no mutable state.
     These can be verified by Stainless if annotated with contracts. -->

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| <!-- e.g. domain.InterestCalculator.compute --> | <!-- Interest computation --> | <!-- Yes --> |

### Effectful Code

<!-- Service methods, I/O, database access, HTTP calls, actor behaviors.
     Tested by Ring 3 (test kits + ScalaCheck) and monitored by Ring 9. -->

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| <!-- e.g. service.AccountService[F[_]] --> | <!-- F: Async / Behavior[Cmd] --> | <!-- Account operations --> |

## Type Strategy — Invalid-State Prevention

<!-- For EVERY invariant in the specs, place its enforcement on this
     hierarchy and justify the placement. "Risky" requires justification;
     "Bad" is forbidden.

       Best:    invalid state is impossible to express
       Good:    invalid state is rejected by smart constructor
       Okay:    invalid state is rejected by validator
       Risky:   invalid state reaches evaluator and returns fallback
       Bad:     invalid state silently maps to valid behavior  ◄ FORBIDDEN

     Techniques for "Best":
     - Split broad enums into narrower algebras (e.g. EqualityOp / OrderOp /
       SetOp / RangeOp instead of one CmpOp used everywhere) so an operation
       cannot even receive an inapplicable operator
     - Separate raw and typed ASTs: RawExpr → desugar → typecheck →
       TypedExpr[A] → eval; only the typechecker produces TypedExpr
     - Indexed ADTs/GADTs: Expr[BooleanSort], Expr[IntSort], ... so the
       compiler carries the invariant
     - Compile-negative tests (assertDoesNotCompile) prove unconstructibility -->

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| <!-- e.g. Count never receives IN/BETWEEN --> | <!-- Best --> | <!-- CountThresholdOp enum split + compile-negative test --> | <!-- --> |

## Refined Type Strategy

<!-- Which domain values get opaque types with constraints (Iron or the
     detected refined-type library) vs. plain types.

     RULE OF THUMB:
     - API boundary values → constrained opaque type + smart constructor
     - Persisted identifiers → constrained opaque type + smart constructor
     - Internal computation intermediates → plain type is acceptable
     - Human-readable strings (names, descriptions) → String is acceptable -->

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| <!-- e.g. TransactionId --> | <!-- String --> | <!-- UUID format --> | <!-- API + DB identifier --> |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| <!-- e.g. ownerName: String --> | <!-- Human-readable, no structural constraint --> |

## IDL Model Layout

<!-- Structure for API operations in the DETECTED IDL (Smithy, protobuf, ...).
     This drives type generation, codec generation, and Ring 9 span validation. -->

### Services

| Service | Operations | IDL File |
|---------|-----------|----------|
| <!-- e.g. AccountService --> | <!-- Withdraw, Deposit, GetBalance --> | <!-- src/main/smithy/account.smithy --> |

### Structures

| Structure | Fields | Used By |
|-----------|--------|---------|
| <!-- e.g. WithdrawInput --> | <!-- accountId: AccountId, amount: Amount --> | <!-- AccountService.Withdraw --> |

## Error Strategy

<!-- How errors are modeled, propagated, and exposed.
     No swallowed errors. No default branches returning valid domain values. -->

### Error Modeling

<!-- Each service gets a sealed enum for its error types.
     Error variants are data-carrying (not empty marker cases). -->

| Error Enum | Variants | Used By |
|------------|----------|---------|
| <!-- TransactionError --> | <!-- InsufficientFunds(available, requested), AccountNotFound(id) --> | <!-- AccountService --> |

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure | `Either[E, A]` | `validateAmount(a): Either[ValidationError, Amount]` |
| Pure → Effect | lift into the effect type | <!-- per detected stack --> |
| Service → API | explicit error channel | <!-- per detected HTTP/IDL stack --> |

## Compatibility Story (Ring 4)

<!-- REQUIRED if any spec touches persisted or wire data (events, snapshots,
     JSON, protobuf, Smithy, message payloads). Delete otherwise. -->

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| <!-- persisted events --> | <!-- protobuf --> | <!-- old fixture decoding + round-trip --> | <!-- EventCompatSpec --> |
| <!-- snapshots --> | | | |
| <!-- API payloads --> | <!-- JSON --> | <!-- round-trip + unknown-field behavior --> | |

**Fixture obligation**: `old fixture bytes/json → decode → expected domain value`
and `new value → encode → decode → same value`.

## Verification Map

<!-- For each module, state which rings apply. This feeds directly into
     implementation-order.md and the per-spec ring pipeline.
     R8 (adversarial review) applies to every code-changing module. -->

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| <!-- domain types --> | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| <!-- service logic --> | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | ✅ |
| <!-- pure algorithms --> | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — |
| <!-- persistence/wire --> | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | <!-- ✅ --> | ✅ | — |

## Technical Decisions

### Decision: [Title]

**Context**: [What situation prompted this decision]
**Options considered**: [Brief list of alternatives]
**Decision**: [What was chosen and why]
**Consequences**: [What follows from this decision]

<!-- Add more decision records as needed.
     Use the ADR (Architecture Decision Record) format. -->
