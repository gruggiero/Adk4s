# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes.

     RULES:
     - One `## <n>. <spec-name>` section per spec, in implementation-order.md order
     - Per-spec checkboxes follow the schema cycle: typed contract (human gate) →
       test oracle (human gate) → implementation → applicable rings → concept-delta
       + inventory update + checkpoint
     - List only the rings that apply to that spec (skip those marked `—` in the
       Ring Applicability table)
     - Prerequisite work (build restructure, deps, static-analysis config) goes
       first in the owning spec's section
     - Every task is observable and stack-specific — never "implement the spec" -->

## 0. Build wiring (prerequisite for spec 1)

- [x] Add `adk4s-memory-api` lazy val to `build.sbt`: `.dependsOn(adk4s-core)`, `.settings(name := "adk4s-memory-api", libraryDependencies ++= Seq(catsEffect, fs2Core) ++ testDeps, scalacOptions ++= scala3Options)`
- [x] Create empty source tree `adk4s-memory-api/src/main/scala/org/adk4s/memory/` and `adk4s-memory-api/src/test/scala/org/adk4s/memory/`
- [x] Verify `sbt "adk4s-memory-api/compile"` succeeds on the empty tree
- [x] Verify `sbt "adk4s-memory-api/dependencyTree"` shows no `neo4j`/`lucene`/`http4s` (Ring 2 advisory check)

## 1. agent-memory

- [x] Step 1 — typed contract: `AgentMemoryTypeContract.scala` in `adk4s-memory-api/src/test/scala/org/adk4s/memory/typecontract/` declaring signatures for `AgentMemory[F]` (no `Sync` on trait), `Episode`, `SourceType` (5-case enum), `EpisodeOutcome` (+ `empty` + `isSuccess`), `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory[F[_]: Sync]` (+ `create`). Compile via `sbt "adk4s-memory-api/Test/compile"`. (human gate)
- [x] Step 2 — test oracle: `AgentMemorySpec.scala` (munit `CatsEffectSuite`) with 19 scenarios + `Generators.scala` with `genEpisodes`/`genQuery`/`genK`/`genScope`/`genTerm`/`genSurrounding` (Hedgehog `Gen`) + 6 properties (`recall-k-bound`, `recall-score-ordering`, `recall-after-remember`, `temporal-ignorability`, `naiveScore-monotonicity`, `rememberAll-size-match`) + compile-negative stubs (`assertDoesNotCompile` for `trait AgentMemory[F[_]: Sync]` and 6th `SourceType` case). All tests FAIL (no implementation yet). (human gate)
- [x] Step 3 — implementation: `Episode.scala` (`Episode` + `SourceType` enum + `EpisodeOutcome` + `conversation` factory + `empty`), `MemoryHit.scala` (`MemoryHit` + `TemporalScope`), `AgentMemory.scala` (trait + `rememberAll` default via `Traverse` + `apply` companion), `InMemoryAgentMemory.scala` (`Ref[F, Vector[Episode]]` backing, `naiveScore`, `toHit`, `create` factory)
- [x] Ring 0: `sbt "adk4s-memory-api/compile"` passes with `-deprecation -feature -unchecked -Xkind-projector:underscores -source:future`
- [x] Ring 1: `sbt scalafmtCheck` passes; `sbt scalafixAll --check` passes; WartRemover (inherited `ThisBuild` wartremoverErrors) passes — no `var`/`null`/`throw`/`asInstanceOf`/`Any` introduced
- [x] Ring 2: `sbt "adk4s-memory-api/dependencyTree"` confirms no `neo4j`/`lucene`/`http4s`/`workflows4s`/`llm4s` in main scope
- [x] Ring 3: `sbt "adk4s-memory-api/test"` passes — all 19 scenarios + 6 Hedgehog properties green
- [x] Ring 5: retarget `stryker4s.conf` `mutate = ["**/memory/InMemoryAgentMemory.scala"]`; run `sbt "adk4s-memory-api/stryker4s"`; threshold ≥ 90% (break=90)
- [x] Ring 8: adversarial spec-compliance review — verify all 10 requirements + 19 scenarios + 6 properties are observed in the implementation; verify `⚠ VERIFY` items from proposal §5 resolved; verify no `AgentEvent` variants added
- [x] Concept-delta check: confirm exactly 7 new concepts (`AgentMemory`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory`) introduced — no more, no less; update `concept-inventory.md` "Concepts This Change Will Introduce" rows to "pre-existing"; checkpoint

## 2. memory-retriever-bridge

- [x] Step 1 — typed contract: `MemoryRetrieverTypeContract.scala` in `adk4s-memory-api/src/test/scala/org/adk4s/memory/typecontract/` declaring `MemoryRetriever.apply[F[_]: Sync](memory: AgentMemory[F], k: Int, scope: Option[TemporalScope]): Retriever[F]` + `toDocument(hit: MemoryHit): Document` signature + compile-negative stub (`assertDoesNotCompile` for `MemoryRetriever.apply[F]` without `Sync`). Compile via `sbt "adk4s-memory-api/Test/compile"`. (human gate)
- [x] Step 2 — test oracle: `MemoryRetrieverSpec.scala` (munit `CatsEffectSuite`) with 13 scenarios + 4 Hedgehog properties (`bridge-size-bound`, `bridge-minScore-filter`, `bridge-stream-equals-retrieve`, `bridge-id-stability`) + generators (`genFactoryK`, `genTopK`, `genMinScore` via `Range.linearFrac`, `genConfig`, `genHit`). All tests FAIL (no implementation yet). (human gate)
- [x] Step 3 — implementation: `MemoryRetriever.scala` — `apply` factory returning `Retriever[F]` implementing `retrieve` (calls `memory.recall(query, math.min(k, config.topK), scope)`, filters by `config.minScore`, maps via `toDocument`) and `retrieveStream` (via `Stream.eval`); `toDocument` synthesizes `id` (deterministic hash of hit fields), packs `score` as `ujson.Num`, `provenance` as `ujson.Str` when present, `payload` entries as `ujson.Str`
- [x] Ring 0: `sbt "adk4s-memory-api/compile"` passes
- [x] Ring 1: `sbt scalafmtCheck` passes; `sbt scalafixAll --check` passes; WartRemover passes
- [x] Ring 2: `dependencyTree` unchanged (no new deps — uses existing `cats-effect`/`fs2-core`/`ujson` transitive)
- [x] Ring 3: `sbt "adk4s-memory-api/test"` passes — all 13 scenarios + 4 Hedgehog properties green
- [x] Ring 5: retarget `stryker4s.conf` `mutate = ["**/memory/MemoryRetriever.scala"]`; run `sbt "adk4s-memory-api/stryker4s"`; threshold ≥ 90%
- [x] Ring 8: adversarial review — verify `Retriever`/`Document`/`RetrieverConfig` shapes match real `Retriever.scala`; verify `retrieveStream` equals `retrieve`; verify `score` carried as `ujson.Num`; verify `id` stability
- [x] Concept-delta check: confirm exactly 1 new concept (`MemoryRetriever`) introduced; update `concept-inventory.md`; checkpoint

## 3. memory-testkit

- [x] Step 1 — build wiring: add `adk4s-memory-testkit` lazy val to `build.sbt`: `.dependsOn(adk4s-memory-api)`, `.settings(name := "adk4s-memory-testkit", libraryDependencies ++= Seq(catsEffect, munit), scalacOptions ++= scala3Options)` — note `munit` is MAIN scope (not `% Test`); create source tree `adk4s-memory-testkit/src/main/scala/org/adk4s/memory/testkit/` and `adk4s-memory-testkit/src/test/scala/org/adk4s/memory/testkit/`
- [x] Step 1 — typed contract: `AgentMemoryLawsTypeContract.scala` in `adk4s-memory-testkit/src/test/scala/org/adk4s/memory/testkit/typecontract/` declaring `AgentMemoryLaws(indexesContent: Boolean)` + methods `kBound`/`scoreOrdering`/`recallAfterRemember`/`temporalIgnorability`/`all` signatures. Compile via `sbt "adk4s-memory-testkit/Test/compile"`. (human gate)
- [x] Step 2 — test oracle: `AgentMemoryLawsSpec.scala` (munit `CatsEffectSuite`) with 9 scenarios + 2 Hedgehog properties (`laws-all-implies-conjunction`, `laws-pass-for-known-good-inmemory`) + `genIndexesContent: Gen[Boolean]`. All tests FAIL (no implementation yet). (human gate)
- [x] Step 3 — implementation: `AgentMemoryLaws.scala` — case class with `indexesContent: Boolean`, fixed `now = Instant.parse("2025-01-01T00:00:00Z")`, `kBound` (remember 10 "widgets" episodes, `recall("widgets", 3)`, assert `size <= 3`), `scoreOrdering` (`recall("anything", 10)`, assert non-increasing), `recallAfterRemember` (gated by `indexesContent` — no-op `IO.pure(true)` when false; remember "Alice works at Meta", `recall("Meta", 5)`, assert non-empty when true), `temporalIgnorability` (`recall("x", 5, Some(scope)).attempt.isRight`), `all` (conjunction of the four via `mapN`)
- [x] Ring 0: `sbt "adk4s-memory-testkit/compile"` passes
- [x] Ring 1: `sbt scalafmtCheck` passes; `sbt scalafixAll --check` passes; WartRemover passes
- [x] Ring 2: `sbt "adk4s-memory-testkit/dependencyTree"` confirms no `neo4j`/`lucene`/`http4s`/`fs2`/`workflows4s`/`llm4s`; `munit` appears in main scope (intentional for testkit)
- [x] Ring 3: `sbt "adk4s-memory-testkit/test"` passes — all 9 scenarios + 2 Hedgehog properties green
- [x] Ring 8: adversarial review — verify `AgentMemoryLaws` is in main scope (not Test); verify downstream `libraryDependencies += "org.adk4s" %% "adk4s-memory-testkit" % version` would import `org.adk4s.memory.testkit.AgentMemoryLaws`; verify all 4 laws match the agent-memory spec's contract
- [x] Concept-delta check: confirm exactly 2 new concepts (`AgentMemoryLaws`, `adk4s-memory-testkit` module) introduced; update `concept-inventory.md`; checkpoint
