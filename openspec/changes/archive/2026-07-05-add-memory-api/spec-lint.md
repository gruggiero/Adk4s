# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely.

     Lint run on 2026-07-03 against the three specs in this change.
     Two vague-word issues ("sensible results") were found and FIXED in
     agent-memory/spec.md and memory-testkit/spec.md before producing this
     report. One missing scenario (scope=None vs scope=Some equality) was
     added to agent-memory/spec.md. This report reflects the post-fix state. -->

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative statement containing SHALL or MUST before its `**Given**` clause
1c. Every requirement asserting "identical/same/preserved behavior" over an enum/dispatch parameter is backed by ≥1 scenario PER enum variant
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate", "sensible") without a concrete definition
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism
13. Every consumer-facing surface has a scenario asserting what the consumer observes
14. Every requirement asserting a specific returned error variant is type-feasible vs the producing API's return type

## Results

### Spec: specs/agent-memory/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 10 requirements have Given/When/Then clauses; all 19 scenarios have concrete Given/When/Then. |
| 1b | Normative SHALL/MUST before Given | ✅ | All 10 requirements open with a `SHALL` statement before the first `**Given**`. (Requirement "rememberAll default traverses remember" also uses `MAY` for the override clause — `MAY` is normative RFC 2119 but the lead statement uses `SHALL`.) |
| 1c | Per-variant scenarios for enum-dispatch | ✅ | No requirement asserts "identical behavior" over an enum-dispatch parameter. `SourceType` is a closed enum but no requirement dispatches on it with a "same behavior" claim. |
| 2 | Then observable | ✅ | Every `Then` asserts a concrete return value (`IO.pure(EpisodeOutcome.empty)`, `Nil`, `score == 1.0`, `hits.size <= k`, `.attempt` yields `Right`, `List[MemoryHit]` equal) or a compile outcome. No internal-intention `Then`. |
| 3 | Scenarios testable | ✅ | All scenarios use `IO`, `InMemoryAgentMemory.create[IO]`, `Episode`, `recall`/`remember` — all compile and run under `sbt "adk4s-memory-api/test"` with munit-cats-effect. The exhaustive-match scenario compiles under Scala 3.8.4. |
| 4 | Error paths specified | ✅ | The only error path is `recall` with `Some(scope)` on a non-temporal backend — specified as "completes without error (`.attempt` yields `Right`)" with a scenario. `remember` always succeeds (`EpisodeOutcome.empty` for the double). `recall` with no matches returns `Nil` (not an error). `k=0` returns `Nil`. No unspecified failure modes. |
| 5 | New concepts declared | ✅ | All 7 new concepts (`AgentMemory`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory`) appear in "Concepts Introduced". |
| 6 | Reused concepts resolved | ✅ | The "Concepts Used" table references `Retriever`, `Document`, `RetrieverConfig` — all present in concept-inventory.md. (Note: these are consumed by the bridge spec, not this spec's implementation; the table notes this.) |
| 7 | Generator strategies | ✅ | All 6 properties declare a generator strategy: `genEpisodes` (constructive, alphaNum + `Gen.element1` + `Gen.instant`), `genQuery`, `genK`, `genScope`, `genTerm`, `genSurrounding`. Edge cases and classify labels stated for each. |
| 8 | Temporal trigger/response | N/A | No temporal properties (Ring 9 not applied; section omitted with rationale). |
| 9 | No vague words | ✅ | Fixed: "sensible results" replaced with "completes without raising an error (`.attempt` yields `Right`), returning the same hits it would return for `None`". "valid time" in the Episode requirement is a bi-temporal domain term (valid time vs transaction time), not the vague adjective "valid" — it is defined inline as "when the described facts were true in the world". No other vague words found. |
| 10 | Unreachable claims proven | ✅ | No "unreachable" claims. The compile-negative obligations (`AgentMemory[F[_]: Sync]` on the trait, 6th `SourceType` case) are type-level prohibitions with `assertDoesNotCompile` tests. |
| 11 | Enum extension behavior | ✅ | `SourceType` is a NEW enum, not an extension of an existing one — no existing pattern matches to state behavior for. `AgentEvent` is NOT extended in this change (orchestration hook deferred per proposal). No type-widening/aliasing occurs. |
| 12 | Proof obligations complete | ✅ | The Proof Obligations table has 13 rows covering all 10 requirements, all 6 properties, both compile-negative obligations, and the dependency-tree manual check. Each row declares an enforcement mechanism (type system, Hedgehog property, scenario test, compile-negative test, manual review). |
| 13 | Consumer-facing surface | ✅ | `AgentMemory` is a capability consumed by agent code (not directly by an LLM). The consumer observes `remember`/`recall`/`rememberAll` signatures — asserted by the effect-polymorphism requirement and the `rememberAll`-size-match property. No IDL/LLM-facing surface in this spec. |
| 14 | Error-variant feasibility | ✅ | No requirement asserts a specific error variant. The only error-adjacent assertion is `.attempt.isRight` (feasible: `F[List[MemoryHit]]` is attemptable under `Sync`). |

**Verdict: PASS**

### Spec: specs/memory-retriever-bridge/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have Given/When/Then; all 13 scenarios have concrete clauses. |
| 1b | Normative SHALL/MUST before Given | ✅ | All 5 requirements open with `SHALL` before the first `**Given**`. |
| 1c | Per-variant scenarios for enum-dispatch | N/A | No enum-dispatch "same behavior" requirement. |
| 2 | Then observable | ✅ | Every `Then` asserts a concrete return value (`IO[List[Document]]` size, `content == "..."`, `metadata("score") == ujson.Num(...)`, `id` non-empty, `id` equal, compile failure). |
| 3 | Scenarios testable | ✅ | All scenarios use `IO`, `InMemoryAgentMemory`, `MemoryRetriever`, `RetrieverConfig`, `ujson.Num`/`ujson.Str` — all available in the detected stack. `retrieveStream.compile.toList` uses fs2 (in main scope per capability-profile). The compile-negative scenario (`MemoryRetriever.apply[F]` without `Sync`) is testable via `assertDoesNotCompile` (munit). |
| 4 | Error paths specified | ✅ | The only failure mode is the compile-time absence of `Sync[F]` — specified as "the code does not compile" with a scenario. `retrieve` on empty memory returns `Nil` (not an error). No runtime error paths. |
| 5 | New concepts declared | ✅ | `MemoryRetriever` appears in "Concepts Introduced". |
| 6 | Reused concepts resolved | ✅ | "Concepts Used" references `Retriever`, `Document`, `RetrieverConfig` (all in inventory) and `AgentMemory`, `MemoryHit`, `TemporalScope` (introduced by spec:agent-memory, listed in inventory's "Concepts This Change Will Introduce" section). |
| 7 | Generator strategies | ✅ | All 4 properties declare strategies: `genEpisodes`, `genQuery`, `genFactoryK`, `genTopK`, `genMinScore` (`Range.linearFrac`), `genConfig` (constructive from `genTopK` + `genMinScore`), `genHit` (constructive from `genText`/`genScore`/`genProvenance`/`genPayload`). Classify labels stated. |
| 8 | Temporal trigger/response | N/A | No temporal properties (Ring 9 not applied). |
| 9 | No vague words | ✅ | No vague words found. "Stable identifier" is defined by the `bridge-id-stability` property as "a pure function of the hit's fields, not random". |
| 10 | Unreachable claims proven | ✅ | No "unreachable" claims. The compile-negative obligation (`MemoryRetriever.apply[F]` without `Sync`) has an `assertDoesNotCompile` test. |
| 11 | Enum extension behavior | ✅ | No enum/GADT extension. `Retriever[F]` is implemented (not extended) — the bridge provides a new instance, not a new variant. No type-widening. |
| 12 | Proof obligations complete | ✅ | 9 rows covering all 5 requirements, all 4 properties, the compile-negative obligation, and the `⚠ VERIFY` resolution (Document shape). Each declares a mechanism. |
| 13 | Consumer-facing surface | ✅ | `MemoryRetriever` produces a `Retriever[F]` which is the consumer-facing surface agents already consume. Scenarios assert what the consumer observes: `Document.content`, `Document.metadata("score")`, `Document.id`, result size. The `RetrieverConfig.topK`/`minScore` consumer parameters are asserted in the "honors topK" and "filters by minScore" requirements. |
| 14 | Error-variant feasibility | ✅ | No specific error variant asserted. The compile-failure `Then` is a compile-time outcome, not a runtime error variant. |

**Verdict: PASS**

### Spec: specs/memory-testkit/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 6 requirements have Given/When/Then; all 9 scenarios have concrete clauses. |
| 1b | Normative SHALL/MUST before Given | ✅ | All 6 requirements open with `SHALL` before the first `**Given**`. |
| 1c | Per-variant scenarios for enum-dispatch | N/A | No enum-dispatch "same behavior" requirement. The `indexesContent` boolean gates `recallAfterRemember` but both values (`true`/`false`) have scenarios. |
| 2 | Then observable | ✅ | Every `Then` asserts a concrete boolean (`true`/`false`), a compile outcome, or a dependency-tree content check. |
| 3 | Scenarios testable | ✅ | All scenarios use `IO`, `InMemoryAgentMemory.create[IO]`, `AgentMemoryLaws` — all compile under `sbt "adk4s-memory-testkit/test"`. The `sbt "adk4s-memory-testkit/compile"` and `dependencyTree` scenarios are runnable shell commands. |
| 4 | Error paths specified | ✅ | No runtime error paths in the laws themselves (they return `IO[Boolean]`). The `temporalIgnorability` law asserts `.attempt.isRight` — the error path it tests is "recall with scope errors" → law returns `false`. The testkit-module scenarios assert compile success and dependency-tree content. |
| 5 | New concepts declared | ✅ | `AgentMemoryLaws` and `adk4s-memory-testkit` (sbt module) appear in "Concepts Introduced". |
| 6 | Reused concepts resolved | ✅ | "Concepts Used" references `AgentMemory`, `Episode`, `SourceType`, `MemoryHit`, `TemporalScope` — all listed in inventory's "Concepts This Change Will Introduce". |
| 7 | Generator strategies | ✅ | Both properties declare strategies: `genIndexesContent: Gen[Boolean]` (`Gen.boolean`) for the conjunction property; deterministic (no generator) for the known-good property. Classify labels stated. |
| 8 | Temporal trigger/response | N/A | No temporal properties (Ring 9 not applied). |
| 9 | No vague words | ✅ | Fixed: "sensible results" in the rationale replaced with "complete without error (`.attempt` yields `Right`), returning the same hits it would return for `None`". No other vague words. |
| 10 | Unreachable claims proven | ✅ | No "unreachable" claims. The compile-negative obligation (`AgentMemoryLaws` in Test scope) is enforced by manual review (Ring 8) — explicitly stated as manual. |
| 11 | Enum extension behavior | ✅ | No enum/GADT extension. |
| 12 | Proof obligations complete | ✅ | 9 rows covering all 6 requirements, both properties, the compile-negative/manual-review obligation, and the dependency-tree check. Each declares a mechanism (Hedgehog property, scenario test, manual review). |
| 13 | Consumer-facing surface | ✅ | `AgentMemoryLaws` is the consumer-facing surface for downstream backends. The "adk4s-memory-testkit module publishes AgentMemoryLaws" requirement asserts what the downstream consumer observes: `libraryDependencies += "org.adk4s" %% "adk4s-memory-testkit" % version` resolves the import. The scenario asserts the import compiles. |
| 14 | Error-variant feasibility | ✅ | No specific error variant asserted. Laws return `IO[Boolean]`. |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| `specs/agent-memory/spec.md` | PASS | 0 — two vague words fixed, one missing scenario added before this report |
| `specs/memory-retriever-bridge/spec.md` | PASS | 0 |
| `specs/memory-testkit/spec.md` | PASS | 0 — one vague word fixed before this report |

**Overall: all three specs PASS. Implementation may proceed to `design` and `implementation-order`.**

## Fixes Applied During Lint

1. `agent-memory/spec.md` line 164: "sensible results" → "completes `recall` without raising an error (i.e. `.attempt` yields `Right`) when called with a `Some(TemporalScope)`, returning the same hits it would return for `None`".
2. `agent-memory/spec.md`: added scenario "scope=None and scope=Some return identical hits for non-temporal backend" to back the "same hits as `None`" claim in the updated normative statement.
3. `memory-testkit/spec.md` line 107: "sensible results" → "complete without error (`.attempt` yields `Right`), returning the same hits it would return for `None`".

## Adversarial Review Notes (Ring 8)

Beyond the checklist, the following cross-spec coherence checks were performed:

- **`⚠ VERIFY` resolution (proposal §5)**: the `Retriever`/`Document`/`RetrieverConfig` shapes used in `memory-retriever-bridge` match the real `adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala` (verified during capability-profile/concept-inventory). `Document` has no `score` field; metadata is `Map[String, ujson.Value]`; `Retriever` has both `retrieve` and `retrieveStream`; `fromFunction` requires `Sync`. All reflected in the spec.
- **No `AgentEvent` extension**: confirmed against `AgentEvent.scala` (7 variants, no `MemoryRecalled`/`MemoryWritten`). The proposal defers these to a follow-up. No spec claims to add them.
- **`cats-core` sourcing**: the spec does not assume a standalone `cats-core` dependency; it references `Monad`/`Traverse`/`Functor` typeclasses available transitively via `cats-effect` (per capability-profile).
- **No heavy deps**: each spec's proof obligations include a manual-review row for `sbt "<module>/dependencyTree"` confirming no `neo4j`/`lucene`/`http4s`.
- **`Sync` constraint placement**: `AgentMemory[F]` trait has no `Sync` (compile-negative test); `InMemoryAgentMemory` requires `Sync` (on the class); `MemoryRetriever.apply` requires `Sync` (on the factory). This matches the proposal's design goal.
- **Cross-spec dependency**: `memory-retriever-bridge` and `memory-testkit` both depend on types introduced by `agent-memory`. The concept-inventory's "Concepts This Change Will Introduce" section lists them, so check 6 passes for the dependent specs.
