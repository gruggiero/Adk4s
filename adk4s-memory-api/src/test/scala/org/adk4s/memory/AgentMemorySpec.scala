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

/**
 * Test oracle for spec:agent-memory.
 *
 * Tests written from the spec + approved typed contract ONLY, before
 * implementation. They compile against the stub main sources (??? bodies)
 * and are EXPECTED TO FAIL at runtime until Step 3.
 *
 * Every test cites its source: `// spec: agent-memory — Scenario: <heading>`
 */
class AgentMemorySpec extends HedgehogSuite:
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
  // Requirement: AgentMemory trait is effect-polymorphic with no Sync constraint
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: rememberAll default uses Traverse ──────────────────────────

  test("rememberAll default uses Traverse") {
    // spec: agent-memory — Scenario: rememberAll default uses Traverse
    val eps = List(
      Episode("e1", SourceType.Document, now),
      Episode("e2", SourceType.Document, now),
      Episode("e3", SourceType.Document, now)
    )
    val result: IO[List[EpisodeOutcome]] = for
      mem      <- InMemoryAgentMemory.create[IO]
      outcomes <- mem.rememberAll(eps)
    yield outcomes
    val outcomes = result.unsafeRunSync()
    assertEqualsM(outcomes.size, 3)
    assertEqualsM(outcomes, List.fill(3)(EpisodeOutcome.empty))
  }

  // ── Scenario: recall accepts default None scope ──────────────────────────

  test("recall accepts default None scope") {
    // spec: agent-memory — Scenario: recall accepts default None scope
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("hello world", SourceType.Conversation, now))
      hits <- mem.recall("hello", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertM(hits.nonEmpty)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Episode records valid-time timestamp and optional group/metadata
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Episode.conversation convenience factory ───────────────────

  test("Episode.conversation convenience factory") {
    // spec: agent-memory — Scenario: Episode.conversation convenience factory
    val ep = Episode.conversation("hello", "session-1", now)
    assertEqualsM(ep.sourceType, SourceType.Conversation)
    assertEqualsM(ep.content, "hello")
    assertEqualsM(ep.groupId, Some("session-1"))
    assertEqualsM(ep.timestamp, now)
    assertEqualsM(ep.metadata, Map.empty)
  }

  // ── Scenario: metadata defaults to empty map ─────────────────────────────

  test("metadata defaults to empty map") {
    // spec: agent-memory — Scenario: metadata defaults to empty map
    val ep = Episode("x", SourceType.Document, now)
    assertEqualsM(ep.metadata, Map.empty)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: SourceType is a closed enum of five cases
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: exhaustive match compiles ──────────────────────────────────

  test("SourceType exhaustive match compiles") {
    // spec: agent-memory — Scenario: exhaustive match compiles
    // This test verifies at compile time that a 5-arm match (no default)
    // compiles. If a 6th case were added, this would fail to compile.
    def classify(st: SourceType): String = st match
      case SourceType.Conversation   => "conv"
      case SourceType.Document       => "doc"
      case SourceType.StructuredData => "structured"
      case SourceType.ToolResult     => "tool"
      case SourceType.ExternalApi    => "api"
    assertEqualsM(classify(SourceType.Conversation), "conv")
    assertEqualsM(classify(SourceType.Document), "doc")
    assertEqualsM(classify(SourceType.StructuredData), "structured")
    assertEqualsM(classify(SourceType.ToolResult), "tool")
    assertEqualsM(classify(SourceType.ExternalApi), "api")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: EpisodeOutcome reports counts and errors with isSuccess
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: errors make isSuccess false ────────────────────────────────

  test("errors make isSuccess false") {
    // spec: agent-memory — Scenario: errors make isSuccess false
    val outcome = EpisodeOutcome(0, 0, 0, 0L, List("timeout"))
    assertM(!outcome.isSuccess)
  }

  // ── Scenario: empty has zero counts ──────────────────────────────────────

  test("empty has zero counts") {
    // spec: agent-memory — Scenario: empty has zero counts
    val e = EpisodeOutcome.empty
    assertEqualsM(e.entitiesExtracted, 0)
    assertEqualsM(e.relationshipsCreated, 0)
    assertEqualsM(e.edgesInvalidated, 0)
    assertEqualsM(e.processingTimeMs, 0L)
    assertEqualsM(e.errors, Nil)
    assertM(e.isSuccess)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryHit carries agent-facing text, score, and temporal window
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: non-temporal backend returns None windows ──────────────────

  test("non-temporal backend returns None windows") {
    // spec: agent-memory — Scenario: non-temporal backend returns None windows
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("test", SourceType.Document, now))
      hits <- mem.recall("test", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertM(hits.nonEmpty)
    assertM(hits.headOption.exists(_.validTo.isEmpty), "validTo should be None for non-temporal backend")
    assertM(hits.headOption.exists(_.validFrom.isEmpty), "validFrom should be None for non-temporal backend")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: recall returns at most k hits sorted by descending score
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: k=3 with 10 matches returns 3 ──────────────────────────────

  test("k=3 with 10 matches returns 3") {
    // spec: agent-memory — Scenario: k=3 with 10 matches returns 3
    val eps = (1 to 10).map(i => Episode(s"widgets $i", SourceType.Document, now)).toList
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- eps.traverse_(mem.remember)
      hits <- mem.recall("widgets", 3)
    yield hits
    val hits = result.unsafeRunSync()
    assertEqualsM(hits.size, 3)
    val scores = hits.map(_.score)
    assertM(scores == scores.sortBy(-_), "scores must be non-increasing")
  }

  // ── Scenario: k=0 returns empty list ─────────────────────────────────────

  test("k=0 returns empty list") {
    // spec: agent-memory — Scenario: k=0 returns empty list
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("anything", SourceType.Document, now))
      hits <- mem.recall("anything", 0)
    yield hits
    val hits = result.unsafeRunSync()
    assertEqualsM(hits, Nil)
  }

  // ── Scenario: no matches returns empty list ──────────────────────────────

  test("no matches returns empty list") {
    // spec: agent-memory — Scenario: no matches returns empty list
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("widgets", SourceType.Document, now))
      hits <- mem.recall("socks", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertEqualsM(hits, Nil)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: TemporalScope is ignorable by non-temporal backends
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: scope does not crash the in-memory double ──────────────────

  test("scope does not crash the in-memory double") {
    // spec: agent-memory — Scenario: scope does not crash the in-memory double
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      _   <- mem.remember(Episode("query content", SourceType.Document, now))
      r   <- mem.recall("query", 5, Some(TemporalScope(Instant.parse("2030-01-01T00:00:00Z")))).attempt
    yield r
    val r = result.unsafeRunSync()
    assertM(r.isRight, "recall with Some(scope) must not error")
  }

  // ── Scenario: scope=None and scope=Some return identical hits ────────────

  test("scope=None and scope=Some return identical hits for non-temporal backend") {
    // spec: agent-memory — Scenario: scope=None and scope=Some return identical hits for non-temporal backend
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("alpha", SourceType.Document, now))
      none <- mem.recall("alpha", 5, None)
      some <- mem.recall("alpha", 5, Some(TemporalScope(now)))
    yield (none, some)
    val (none, some) = result.unsafeRunSync()
    assertEqualsM(none.size, some.size)
    assertEqualsM(none.map(_.text), some.map(_.text))
    assertEqualsM(none.map(_.score), some.map(_.score))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: InMemoryAgentMemory is substring-indexed and Ref-backed
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: exact substring scores 1.0 ─────────────────────────────────

  test("exact substring scores 1.0") {
    // spec: agent-memory — Scenario: exact substring scores 1.0
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("the quick brown fox", SourceType.Document, now))
      hits <- mem.recall("quick brown", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertM(hits.nonEmpty)
    assertEqualsM(hits.headOption.map(_.score), Some(1.0))
  }

  // ── Scenario: partial term match scores fractionally ─────────────────────

  test("partial term match scores fractionally") {
    // spec: agent-memory — Scenario: partial term match scores fractionally
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("the quick brown fox", SourceType.Document, now))
      hits <- mem.recall("quick socks", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertM(hits.nonEmpty)
    assertEqualsM(hits.headOption.map(_.score), Some(0.5))
  }

  // ── Scenario: no term match returns no hit ───────────────────────────────

  test("no term match returns no hit") {
    // spec: agent-memory — Scenario: no term match returns no hit
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("the quick brown fox", SourceType.Document, now))
      hits <- mem.recall("elephant", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertEqualsM(hits, Nil)
  }

  // ── Scenario: remember returns EpisodeOutcome.empty ──────────────────────

  test("remember returns EpisodeOutcome.empty") {
    // spec: agent-memory — Scenario: remember returns EpisodeOutcome.empty
    val result = for
      mem     <- InMemoryAgentMemory.create[IO]
      outcome <- mem.remember(Episode("x", SourceType.Document, now))
    yield outcome
    val outcome = result.unsafeRunSync()
    assertEqualsM(outcome, EpisodeOutcome.empty)
  }

  // ── Scenario: create factory yields an empty memory ──────────────────────

  test("create factory yields an empty memory") {
    // spec: agent-memory — Scenario: create factory yields an empty memory
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      hits <- mem.recall("anything", 10)
    yield hits
    val hits = result.unsafeRunSync()
    assertEqualsM(hits, Nil)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: rememberAll default traverses remember
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: empty list yields empty outcomes ───────────────────────────

  test("empty list yields empty outcomes") {
    // spec: agent-memory — Scenario: empty list yields empty outcomes
    val result = for
      mem      <- InMemoryAgentMemory.create[IO]
      outcomes <- mem.rememberAll(Nil)
    yield outcomes
    val outcomes = result.unsafeRunSync()
    assertEqualsM(outcomes, Nil)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Compile-Negative Obligations
  // ════════════════════════════════════════════════════════════════════════

  test("SourceType has exactly 5 cases — no 6th case compiles") {
    // spec: agent-memory — Compile-Negative: 6th SourceType case
    val errors: String = compileErrors("""
      val bad: org.adk4s.memory.SourceType = org.adk4s.memory.SourceType.Sixth
    """)
    assertM(errors.nonEmpty, "SourceType.Sixth must not exist")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Edge-case tests (Ring 5 — mutation killing)
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: empty query is a substring of any content → score 1.0 ──────

  test("empty query matches with score 1.0") {
    // Kills mutant: contentLower.contains(queryLower) → false
    // Empty string is a substring of every string, so score must be 1.0.
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("hello world", SourceType.Document, now))
      hits <- mem.recall("", 5)
    yield hits
    val hits = result.unsafeRunSync()
    assertM(hits.nonEmpty)
    assertEqualsM(hits.headOption.map(_.score), Some(1.0))
  }

  // ── Scenario: naiveScore returns 1.0 for empty query ─────────────────────

  test("naiveScore returns 1.0 for empty query") {
    assertEqualsM(InMemoryAgentMemory.naiveScore("hello", ""), 1.0)
  }

  // ── Scenario: naiveScore returns 0.0 for whitespace-only query ───────────

  test("naiveScore returns 0.0 for whitespace-only query") {
    // Kills mutants: terms.isEmpty → terms.nonEmpty, terms.isEmpty → false
    // Whitespace-only query has no terms after filtering, so score must be 0.0
    // (not NaN from 0.0/0.0).
    assertEqualsM(InMemoryAgentMemory.naiveScore("hello", "   "), 0.0)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  property("recall-k-bound: recall returns at most k hits") {
    // spec: agent-memory — Property: recall-k-bound
    // Generator strategy: genEpisodes, genQuery, genK (0..20)
    // Classify by k == 0, k < episodes.size, k >= episodes.size
    for
      episodes <- genEpisodes.forAll
      query    <- genQuery.forAll
      k        <- genK.forAll
    yield
      val hits = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- episodes.traverse_(mem.remember)
        hits <- mem.recall(query, k)
      yield hits).unsafeRunSync()
      (hits.size <= k) ==== true
  }

  property("recall-score-ordering: scores are non-increasing") {
    // spec: agent-memory — Property: recall-score-ordering
    // Generator strategy: genEpisodes, genQuery, genKPositive (1..20)
    // Classify by hits.size == 0, == 1, > 1
    for
      episodes <- genEpisodes.forAll
      query    <- genQuery.forAll
      k        <- genKPositive.forAll
    yield
      val hits = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- episodes.traverse_(mem.remember)
        hits <- mem.recall(query, k)
      yield hits).unsafeRunSync()
      val scores = hits.map(_.score)
      (scores == scores.sortBy(-_)) ==== true
  }

  property("recall-after-remember: remembered term is found") {
    // spec: agent-memory — Property: recall-after-remember (gated by indexesContent)
    // Generator strategy: genTerm (3..8 chars), genK (1..10)
    // Classify by k == 1, k > 1
    for
      term <- genTerm.forAll
      k    <- Gen.int(Range.linear(1, 10)).forAll
    yield
      val hits = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- mem.remember(Episode(s"fact about $term here", SourceType.Document, now))
        hits <- mem.recall(term, k)
      yield hits).unsafeRunSync()
      (hits.nonEmpty) ==== true
  }

  property("temporal-ignorability: scope never errors") {
    // spec: agent-memory — Property: temporal-ignorability
    // Generator strategy: genEpisodes, genQuery, genK, genScope
    // Classify by episodes.isEmpty, episodes.nonEmpty
    for
      episodes <- genEpisodes.forAll
      query    <- genQuery.forAll
      k        <- genK.forAll
      scope    <- genScope.forAll
    yield
      val r = (for
        mem <- InMemoryAgentMemory.create[IO]
        _   <- episodes.traverse_(mem.remember)
        r   <- mem.recall(query, k, Some(scope)).attempt
      yield r).unsafeRunSync()
      (r.isRight) ==== true
  }

  property("naiveScore-monotonicity: substring > term-count > no-match") {
    // spec: agent-memory — Property: naiveScore-monotonicity
    // Generator strategy: genQuery (1..8), genSurrounding (0..10)
    // c1 = surrounding + query + surrounding (always contains query)
    // c2 = surrounding only (may or may not contain query)
    // Precondition per spec: "c2 does not contain q" — when c2 also contains
    // the query, both score 1.0 and the strict inequality does not hold.
    // We assert: c2.contains(query) || (s1 > s2) — i.e. either c2 also
    // contains the query (both 1.0), or c1 strictly outscores c2.
    for
      query       <- Gen.string(Gen.alphaNum, Range.linear(1, 8)).forAll
      surrounding <- genSurrounding.forAll
    yield
      val c1 = surrounding + query + surrounding
      val c2 = surrounding
      val s1 = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- mem.remember(Episode(c1, SourceType.Document, now))
        hits <- mem.recall(query, 1)
      yield hits.headOption.map(_.score).getOrElse(0.0)).unsafeRunSync()
      val s2 = (for
        mem  <- InMemoryAgentMemory.create[IO]
        _    <- mem.remember(Episode(c2, SourceType.Document, now))
        hits <- mem.recall(query, 1)
      yield hits.headOption.map(_.score).getOrElse(0.0)).unsafeRunSync()
      (c2.toLowerCase.contains(query.toLowerCase) || s1 > s2) ==== true
  }

  property("rememberAll-size-match: outcomes size equals episodes size") {
    // spec: agent-memory — Property: rememberAll-size-match
    // Generator strategy: genEpisodes (0..10)
    // Classify by eps.isEmpty, eps.size == 1, eps.size > 1
    for episodes <- genEpisodes.forAll
    yield
      val outcomes = (for
        mem      <- InMemoryAgentMemory.create[IO]
        outcomes <- mem.rememberAll(episodes)
      yield outcomes).unsafeRunSync()
      (outcomes.size == episodes.size) ==== true
  }
