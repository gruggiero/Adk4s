# Concept Inventory

<!-- This is a LIVING DOCUMENT. It is populated by scanning the existing codebase
     and updated after each spec's implementation during the apply phase.

     PURPOSE: Prevent duplicate creation of domain concepts when implementing
     specs sequentially. Before creating any new type, the apply phase MUST
     check this inventory and reuse existing concepts.

     MAINTENANCE RULES:
     - APPEND ONLY during apply (never remove or modify existing entries)
     - Each entry records which spec introduced it (traceability)
     - Package paths must be exact (used for import statements)
     - Constraints must be exact (used for Iron type verification) -->

## Opaque Types (Iron Refined)

<!-- Types with compile-time constraints via Iron. These make invalid
     states unrepresentable. Example:
     opaque type AccountId = String :| (MinLength[10] & MaxLength[10]) -->

| Type | Underlying | Iron Constraint | Package | Introduced By |
|------|-----------|-----------------|---------|---------------|
| <!-- AccountId --> | <!-- String --> | <!-- MinLength[10] & MaxLength[10] & Match["^[a-zA-Z0-9]+$"] --> | <!-- domain --> | <!-- spec:accounts --> |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching.
     Record ALL variants — the compiler enforces completeness. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| <!-- TransactionError --> | <!-- enum --> | <!-- InsufficientFunds, AccountNotFound, SameAccountTransfer --> | <!-- domain --> | <!-- spec:accounts --> |

## Case Classes (Domain Value Objects)

<!-- Immutable data carriers in domain packages. Record field types
     to enable concept reuse (e.g., reusing Account across specs). -->

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| <!-- Account --> | <!-- id: AccountId, ownerName: String, balance: Balance --> | <!-- domain --> | <!-- spec:accounts --> |

## Service Traits

<!-- Tagless final service interfaces parameterised on F[_].
     Record all methods to enable extension (adding methods to
     existing traits) rather than parallel creation. -->

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| <!-- AccountService[F[_]] --> | <!-- F: Async --> | <!-- getAccount, deposit, withdraw, transfer --> | <!-- service --> | <!-- spec:accounts --> |

## Smithy Models

<!-- Smithy IDL service and structure definitions that drive
     smithy4s code generation, JSON codecs, and Ring 9 span validators. -->

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|
| <!-- AccountService --> | <!-- service --> | <!-- Withdraw, Deposit, Transfer --> | <!-- src/main/smithy/account.smithy --> | <!-- spec:accounts --> |

## ScalaCheck Generators

<!-- Reusable generators for property-based tests. Record these to
     avoid duplicate generator creation across specs. -->

| Generator | Generates | Location | Introduced By |
|-----------|----------|----------|---------------|
| <!-- genAccountId --> | <!-- Gen[AccountId] --> | <!-- test/.../Generators.scala --> | <!-- spec:accounts --> |

## Cats Effect Resources and Middleware

<!-- Shared resources (connection pools, HTTP clients, caches) and
     middleware (logging, tracing, auth) that specs may depend on. -->

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| <!-- n/a --> | <!-- n/a --> | <!-- n/a --> | <!-- n/a --> | <!-- n/a --> |
