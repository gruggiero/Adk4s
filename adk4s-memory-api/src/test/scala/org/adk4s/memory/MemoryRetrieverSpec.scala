package org.adk4s.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import java.time.Instant
import org.adk4s.core.component.{ Document, RetrieverConfig }
import ujson.{Num, Str}

// Test oracle for spec:memory-retriever-bridge.
// Tests written from the spec + approved typed contract ONLY, before
// implementation. They compile against the stub main sources (??? bodies)
// and are EXPECTED TO FAIL at runtime until Step 3.
// Every test cites its source: `// spec: memory-retriever-bridge - Scenario: <heading>`
class MemoryRetrieverSpec extends HedgehogSuite:
  import Generators.*

  private val now: Instant = Instant.parse("2025-01-01T00:00:00Z")

  /** Hedgehog's `assert`/`assertEquals` return `Result` objects that are silently
    * discarded in `test` blocks (only `property` blocks check them). These
    * helpers delegate to the real munit assertions via `withMunitAssertions`,
    * which throw on failure.
    */
  private def assertM(cond: => Boolean)(implicit loc: munit.Location): Unit =
    withMunitAssertions(a => a.assert(cond))

  private def assertM(cond: => Boolean, clue: => Any)(implicit loc: munit.Location): Unit =
    withMunitAssertions(a => a.assert(cond, clue))

  private def assertEqualsM[A, B](obtained: A, expected: B)(implicit
      ev: B <:< A,
      loc: munit.Location
  ): Unit =
    withMunitAssertions(a => a.assertEquals(obtained, expected))

  private def assertEqualsM[A, B](obtained: A, expected: B, clue: => Any)(implicit
      ev: B <:< A,
      loc: munit.Location
  ): Unit =
    withMunitAssertions(a => a.assertEquals(obtained, expected, clue))

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryRetriever implements Retriever with both retrieve and retrieveStream
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: retrieve maps recall hits to Documents ─────────────────────

  test("retrieve maps recall hits to Documents") {
    // spec: memory-retriever-bridge — Scenario: retrieve maps recall hits to Documents
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("Alice works at Meta", SourceType.Conversation, now, Some("g1")))
      r     = MemoryRetriever(mem, k = 5)
      docs <- r.retrieve("Meta", RetrieverConfig())
    yield docs
    val docs = result.unsafeRunSync()
    assertM(docs.nonEmpty)
    assertEqualsM(docs.headOption.map(_.content), Some("Alice works at Meta"))
  }

  // ── Scenario: retrieveStream emits the same documents as retrieve ────────

  test("retrieveStream emits the same documents as retrieve") {
    // spec: memory-retriever-bridge — Scenario: retrieveStream emits the same documents as retrieve
    val result = for
      mem    <- InMemoryAgentMemory.create[IO]
      _      <- mem.remember(Episode("Alice works at Meta", SourceType.Conversation, now, Some("g1")))
      r       = MemoryRetriever(mem, k = 5)
      batch  <- r.retrieve("Meta", RetrieverConfig())
      stream <- r.retrieveStream("Meta", RetrieverConfig()).compile.toList
    yield (batch, stream)
    val (batch, stream) = result.unsafeRunSync()
    assertEqualsM(stream, batch)
  }

  // ── Scenario: empty memory yields empty retrieve ─────────────────────────

  test("empty memory yields empty retrieve") {
    // spec: memory-retriever-bridge — Scenario: empty memory yields empty retrieve
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      r     = MemoryRetriever(mem, k = 5)
      docs <- r.retrieve("anything", RetrieverConfig())
    yield docs
    val docs = result.unsafeRunSync()
    assertEqualsM(docs, Nil)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryRetriever maps MemoryHit to Document with synthesized id and metadata
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: score carried as ujson.Num ─────────────────────────────────

  test("score carried as ujson.Num") {
    // spec: memory-retriever-bridge — Scenario: score carried as ujson.Num
    val hit = MemoryHit(text = "test", score = 0.5, provenance = None, payload = Map.empty)
    val doc = MemoryRetriever.toDocument(hit)
    assertEqualsM(doc.metadata("score"), Num(0.5))
  }

  // ── Scenario: provenance omitted when None ───────────────────────────────

  test("provenance omitted when None") {
    // spec: memory-retriever-bridge — Scenario: provenance omitted when None
    val hit = MemoryHit(text = "test", score = 0.5, provenance = None, payload = Map.empty)
    val doc = MemoryRetriever.toDocument(hit)
    assertM(!doc.metadata.contains("provenance"), "provenance key must not be present when None")
  }

  // ── Scenario: provenance included as ujson.Str when Some ─────────────────

  test("provenance included as ujson.Str when Some") {
    // spec: memory-retriever-bridge — Scenario: provenance included as ujson.Str when Some
    val hit = MemoryHit(text = "test", score = 0.5, provenance = Some("g1"), payload = Map.empty)
    val doc = MemoryRetriever.toDocument(hit)
    assertEqualsM(doc.metadata("provenance"), Str("g1"))
  }

  // ── Scenario: payload entries become ujson.Str values ────────────────────

  test("payload entries become ujson.Str values") {
    // spec: memory-retriever-bridge — Scenario: payload entries become ujson.Str values
    val hit = MemoryHit(text = "test", score = 0.5, provenance = None, payload = Map("k1" -> "v1"))
    val doc = MemoryRetriever.toDocument(hit)
    assertEqualsM(doc.metadata("k1"), Str("v1"))
  }

  // ── Scenario: synthesized id is stable for the same hit ──────────────────

  test("synthesized id is stable for the same hit") {
    // spec: memory-retriever-bridge — Scenario: synthesized id is stable for the same hit
    val hit = MemoryHit(
      text = "Alice works at Meta",
      score = 0.9,
      provenance = Some("g1"),
      payload = Map("role" -> "user")
    )
    val d1 = MemoryRetriever.toDocument(hit)
    val d2 = MemoryRetriever.toDocument(hit)
    assertEqualsM(d1.id, d2.id)
    assertM(d1.id.nonEmpty, "id must be non-empty")
  }

  // ── Scenario: content maps from hit.text ─────────────────────────────────

  test("content maps from hit.text") {
    // spec: memory-retriever-bridge — Scenario: content maps from hit.text
    val hit = MemoryHit(text = "hello world", score = 1.0)
    val doc = MemoryRetriever.toDocument(hit)
    assertEqualsM(doc.content, "hello world")
  }

  // ── Scenario: different hits produce different ids ───────────────────────
  // Kills mutants that empty/alter the synthesizeId input string: if the
  // id input is always empty (or separator is removed), different hits would
  // get the same id.

  test("different hits produce different ids") {
    // spec: memory-retriever-bridge — Scenario: different hits produce different ids
    val hit1 = MemoryHit(text = "alpha", score = 0.9, provenance = Some("g1"), payload = Map("k" -> "v"))
    val hit2 = MemoryHit(text = "beta", score = 0.9, provenance = Some("g1"), payload = Map("k" -> "v"))
    val hit3 = MemoryHit(text = "alpha", score = 0.5, provenance = Some("g1"), payload = Map("k" -> "v"))
    val hit4 = MemoryHit(text = "alpha", score = 0.9, provenance = None, payload = Map("k" -> "v"))
    val hit5 = MemoryHit(text = "alpha", score = 0.9, provenance = Some("g1"), payload = Map("k" -> "w"))
    val ids = List(hit1, hit2, hit3, hit4, hit5).map(MemoryRetriever.toDocument(_).id)
    assertEqualsM(ids.distinct.size, 5, s"all 5 ids must be distinct, got ${ids.distinct.size}")
  }

  // ── Scenario: None provenance vs Some("") provenance get different ids ────
  // Kills mutant: getOrElse("") → getOrElse("Stryker was here!"). If the
  // default for None changes, None and Some("Stryker was here!") would collide.

  test("None provenance vs Some(provenance) get different ids") {
    // spec: memory-retriever-bridge — Scenario: None vs Some provenance ids
    val hitNone = MemoryHit(text = "same", score = 0.5, provenance = None, payload = Map.empty)
    val hitSome = MemoryHit(text = "same", score = 0.5, provenance = Some("Stryker was here!"), payload = Map.empty)
    val idNone = MemoryRetriever.toDocument(hitNone).id
    val idSome = MemoryRetriever.toDocument(hitSome).id
    assertM(idNone != idSome, "None and Some provenance must produce different ids")
  }

  // ── Scenario: multi-entry payload vs single-entry payload get different ids ─
  // Kills mutant: ";" separator → "" in payload string. Without the separator,
  // Map("a"->"b","c"->"d") becomes "a=bc=d" which collides with Map("a"->"bc=d").

  test("multi-entry payload vs single-entry payload get different ids") {
    // spec: memory-retriever-bridge — Scenario: payload separator distinguishes ids
    val hitMulti = MemoryHit(text = "same", score = 0.5, provenance = None, payload = Map("a" -> "b", "c" -> "d"))
    val hitSingle = MemoryHit(text = "same", score = 0.5, provenance = None, payload = Map("a" -> "bc=d"))
    val idMulti = MemoryRetriever.toDocument(hitMulti).id
    val idSingle = MemoryRetriever.toDocument(hitSingle).id
    assertM(idMulti != idSingle, "multi-entry and single-entry payloads must produce different ids")
  }

  // ── Scenario: payload keys cannot clobber reserved metadata ──────────────
  // If payload contains "score" or "provenance", the reserved values must win.

  test("payload keys cannot clobber reserved metadata") {
    // spec: memory-retriever-bridge — Scenario: reserved metadata keys take precedence
    val hit = MemoryHit(
      text = "test",
      score = 0.5,
      provenance = Some("g1"),
      payload = Map("score" -> "evil", "provenance" -> "evil", "k1" -> "v1")
    )
    val doc = MemoryRetriever.toDocument(hit)
    assertEqualsM(doc.metadata("score"), Num(0.5))
    assertEqualsM(doc.metadata("provenance"), Str("g1"))
    assertEqualsM(doc.metadata("k1"), Str("v1"))
  }

  // ── Scenario: minScore boundary — score == minScore is included ──────────
  // Kills mutant: >= → > in the minScore filter. When score exactly equals
  // minScore, >= includes it but > excludes it.

  test("minScore boundary: score == minScore is included") {
    // spec: memory-retriever-bridge — Scenario: minScore boundary
    // "alpha beta gamma" with query "alpha beta" → score 1.0
    // "alpha gamma" with query "alpha beta" → score 0.5
    // minScore = 0.5 should include BOTH (>= not >)
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("alpha beta gamma", SourceType.Document, now))
      _    <- mem.remember(Episode("alpha gamma", SourceType.Document, now))
      r     = MemoryRetriever(mem, k = 10)
      docs <- r.retrieve("alpha beta", RetrieverConfig(topK = 10, minScore = 0.5))
    yield docs
    val docs = result.unsafeRunSync()
    // Both hits (1.0 and 0.5) should be included — 0.5 >= 0.5 is true
    assertM(docs.size == 2, s"expected 2 docs (score >= minScore), got ${docs.size}")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryRetriever honors RetrieverConfig.topK as an upper bound
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: config.topK tighter than factory k ─────────────────────────

  test("config.topK tighter than factory k") {
    // spec: memory-retriever-bridge — Scenario: config.topK tighter than factory k
    val eps = (1 to 10).map(i => Episode(s"widgets $i", SourceType.Document, now)).toList
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- eps.traverse_(mem.remember)
      r     = MemoryRetriever(mem, k = 10)
      docs <- r.retrieve("widgets", RetrieverConfig(topK = 2, minScore = 0.0))
    yield docs
    val docs = result.unsafeRunSync()
    assertM(docs.size <= 2, s"size must be <= 2, got ${docs.size}")
  }

  // ── Scenario: factory k tighter than config.topK ─────────────────────────

  test("factory k tighter than config.topK") {
    // spec: memory-retriever-bridge — Scenario: factory k tighter than config.topK
    val eps = (1 to 10).map(i => Episode(s"widgets $i", SourceType.Document, now)).toList
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- eps.traverse_(mem.remember)
      r     = MemoryRetriever(mem, k = 2)
      docs <- r.retrieve("widgets", RetrieverConfig(topK = 10, minScore = 0.0))
    yield docs
    val docs = result.unsafeRunSync()
    assertM(docs.size <= 2, s"size must be <= 2, got ${docs.size}")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryRetriever filters by RetrieverConfig.minScore
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: minScore filters out low-score hits ────────────────────────

  test("minScore filters out low-score hits") {
    // spec: memory-retriever-bridge — Scenario: minScore filters out low-score hits
    // "alpha beta gamma" with query "alpha beta" → score 1.0 (substring match)
    // "alpha gamma" with query "alpha beta" → score 0.5 (1 of 2 terms match)
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("alpha beta gamma", SourceType.Document, now))
      _    <- mem.remember(Episode("alpha gamma", SourceType.Document, now))
      r     = MemoryRetriever(mem, k = 10)
      docs <- r.retrieve("alpha beta", RetrieverConfig(topK = 10, minScore = 0.8))
    yield docs
    val docs = result.unsafeRunSync()
    // Only the 1.0-score hit should survive minScore = 0.8
    assertM(docs.size == 1, s"expected 1 doc, got ${docs.size}")
    assertEqualsM(docs.headOption.map(_.content), Some("alpha beta gamma"))
  }

  // ── Scenario: minScore = 0.0 keeps all hits ──────────────────────────────

  test("minScore = 0.0 keeps all hits") {
    // spec: memory-retriever-bridge — Scenario: minScore = 0.0 keeps all hits
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("alpha beta gamma", SourceType.Document, now))
      _    <- mem.remember(Episode("alpha gamma", SourceType.Document, now))
      r     = MemoryRetriever(mem, k = 10)
      docs <- r.retrieve("alpha beta", RetrieverConfig(topK = 10, minScore = 0.0))
    yield docs
    val docs = result.unsafeRunSync()
    // Both hits (1.0 and 0.5) should be returned
    assertM(docs.size == 2, s"expected 2 docs, got ${docs.size}")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  property("bridge-size-bound: retrieve size <= min(k, config.topK)") {
    // spec: memory-retriever-bridge — Property: bridge-size-bound
    // Generator strategy: genEpisodes, genQuery, genFactoryK (1..20), genTopK (0..20)
    // Classify by topK < k, topK == k, topK > k, topK == 0
    for
      episodes <- genEpisodes.forAll
      query    <- genQuery.forAll
      k        <- genFactoryK.forAll
      topK     <- genTopK.forAll
    yield
      val docs = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- episodes.traverse_(mem.remember)
        r     = MemoryRetriever(mem, k)
        docs <- r.retrieve(query, RetrieverConfig(topK = topK, minScore = 0.0))
      yield docs).unsafeRunSync()
      (docs.size <= math.min(k, topK)) ==== true
  }

  property("bridge-minScore-filter: all docs have score >= minScore") {
    // spec: memory-retriever-bridge — Property: bridge-minScore-filter
    // Generator strategy: genEpisodes, genQuery, genMinScore (0.0..1.0)
    // Classify by minScore == 0.0, minScore > 0.5, minScore > 1.0 (yields empty)
    for
      episodes <- genEpisodes.forAll
      query    <- genQuery.forAll
      minScore <- genMinScore.forAll
    yield
      val docs = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- episodes.traverse_(mem.remember)
        r     = MemoryRetriever(mem, k = 20)
        docs <- r.retrieve(query, RetrieverConfig(topK = 20, minScore = minScore))
      yield docs).unsafeRunSync()
      (docs.forall(d => d.metadata("score").num >= minScore)) ==== true
  }

  property("bridge-stream-equals-retrieve: stream == batch") {
    // spec: memory-retriever-bridge — Property: bridge-stream-equals-retrieve
    // Generator strategy: genEpisodes, genQuery, genConfig (constructive)
    // Classify by result.isEmpty, result.size == 1, result.size > 1
    for
      episodes <- genEpisodes.forAll
      query    <- genQuery.forAll
      config   <- genConfig.forAll
    yield
      val (batch, stream) = (for
        mem    <- InMemoryAgentMemory.create[IO]
        _      <- episodes.traverse_(mem.remember)
        r       = MemoryRetriever(mem, k = 20)
        batch  <- r.retrieve(query, config)
        stream <- r.retrieveStream(query, config).compile.toList
      yield (batch, stream)).unsafeRunSync()
      (batch == stream) ==== true
  }

  property("bridge-id-stability: toDocument(hit).id is deterministic") {
    // spec: memory-retriever-bridge — Property: bridge-id-stability
    // Generator strategy: genHit (constructive from genText, genScore, genProvenance, genPayload)
    // Run the mapping twice and compare ids.
    for hit <- genHit.forAll
    yield
      val d1 = MemoryRetriever.toDocument(hit)
      val d2 = MemoryRetriever.toDocument(hit)
      (d1.id == d2.id && d1.id.nonEmpty) ==== true
  }
