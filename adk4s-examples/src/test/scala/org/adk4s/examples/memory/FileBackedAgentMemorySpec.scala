package org.adk4s.examples.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.memory.{
  AgentMemory,
  Episode,
  EpisodeOutcome,
  InMemoryAgentMemory,
  MemoryHit,
  SourceType,
  TemporalScope
}
import org.adk4s.memory.testkit.AgentMemoryLaws

import java.nio.file.{ Files, Path }
import java.time.Instant

/**
 * Test oracle for spec:cross-run-memory-example — FileBackedAgentMemory.
 *
 * Tests written from the spec + approved typed contract ONLY, before
 * implementation. They compile against the stub main sources (`???` bodies)
 * and are EXPECTED TO FAIL at runtime until Step 3.
 *
 * Covers:
 *   - AgentMemoryLaws(indexesContent = true).all (the 4 laws)
 *   - Property 1: remember-all then recall-all round-trip
 *   - Property 2: reload-across-instances preserves recall equivalence
 *   - Property 3: scoring delegates to InMemoryAgentMemory.naiveScore
 *   - Property 4: empty storage returns empty hits (no error)
 *   - Adversarial: broken double fails ≥ 1 law
 */
class FileBackedAgentMemorySpec extends HedgehogSuite:

  // ── Local generators (adk4s-memory-api's Generators are test-scope only) ──

  private val genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 20))

  private val genQuery: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 10))

  private val genSourceType: Gen[SourceType] =
    Gen.element1(
      SourceType.Conversation,
      SourceType.Document,
      SourceType.StructuredData,
      SourceType.ToolResult,
      SourceType.ExternalApi
    )

  private val genInstant: Gen[Instant] =
    Gen.long(Range.linear(0L, 4102444800000L)).map(Instant.ofEpochMilli)

  private val genEpisode: Gen[Episode] =
    for
      content    <- genContent
      sourceType <- genSourceType
      timestamp  <- genInstant
    yield Episode(content, sourceType, timestamp, None, Map.empty)

  private val genEpisodes: Gen[List[Episode]] =
    Gen.list(genEpisode, Range.linear(0, 10))

  private val genK: Gen[Int] =
    Gen.int(Range.linear(0, 20))

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Create a fresh temp directory for a test. */
  private def tempDir: IO[Path] =
    IO.blocking(Files.createTempDirectory("fbam-test"))

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: File-backed memory satisfies the AgentMemory laws
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Laws suite green ───────────────────────────────────────────

  test("FileBackedAgentMemory passes all four AgentMemoryLaws") {
    // spec: cross-run-memory-example — Scenario: Laws suite green
    val laws: AgentMemoryLaws = AgentMemoryLaws(indexesContent = true)
    val result: IO[Boolean] = for
      dir    <- tempDir
      mem    <- FileBackedAgentMemory[IO](dir)
      passed <- laws.all(mem)
    yield passed
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "AgentMemoryLaws(indexesContent = true).all must pass")
  }

  // ── Scenario: Laws suite red on a broken double (adversarial) ────────────

  test("broken double (recall returns Nil) fails at least one law") {
    // spec: cross-run-memory-example — Scenario: Laws suite red on a broken double
    val laws: AgentMemoryLaws = AgentMemoryLaws(indexesContent = true)
    val brokenMem: AgentMemory[IO] = new AgentMemory[IO]:
      def remember(episode: Episode): IO[EpisodeOutcome] =
        IO.pure(EpisodeOutcome.empty)
      def recall(
        query: String,
        k: Int,
        scope: Option[TemporalScope] = None
      ): IO[List[MemoryHit]] =
        IO.pure(Nil)
    val result: IO[Boolean] = laws.all(brokenMem)
    val passed: Boolean     = result.unsafeRunSync()
    assert(!passed, "A double whose recall always returns Nil must fail at least one law")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: File-backed memory persists episodes across process boundaries
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Teach in one instance, recall in a fresh instance ──────────

  test("teach in one instance, recall in a fresh instance at the same path") {
    // spec: cross-run-memory-example — Scenario: Teach in one instance, recall in a fresh instance
    val now: Instant = Instant.parse("2026-07-19T12:00:00Z")
    val episode: Episode = Episode(
      "My favorite color is blue",
      SourceType.Conversation,
      now,
      Some("demo"),
      Map.empty
    )
    val result: IO[List[String]] = for
      dir  <- tempDir
      m1   <- FileBackedAgentMemory[IO](dir)
      _    <- m1.remember(episode)
      m2   <- FileBackedAgentMemory[IO](dir)
      hits <- m2.recall("favorite color", 5, None)
    yield hits.map(_.text)
    val hits: List[String] = result.unsafeRunSync()
    assert(hits.nonEmpty, "fresh instance must recall the episode")
    assert(
      hits.exists(_.toLowerCase.contains("blue")),
      "first hit text must contain 'blue'"
    )
  }

  // ── Scenario: Empty file on first recall ─────────────────────────────────

  test("empty file on first recall returns Nil without error") {
    // spec: cross-run-memory-example — Scenario: Empty file on first recall
    val result: IO[List[MemoryHit]] = for
      dir  <- tempDir
      mem  <- FileBackedAgentMemory[IO](dir)
      hits <- mem.recall("anything", 5, None)
    yield hits
    val hits: List[MemoryHit] = result.unsafeRunSync()
    assertEquals(hits, Nil)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  property("remember-all-then-recall-all: round-trip preserves all episodes") {
    // spec: cross-run-memory-example — Property 1: round-trip
    // Generator strategy: genEpisodes (local, 0..10 episodes)
    // Constructive — no filtering. Edge cases: empty list, single, multiple.
    for episodes <- genEpisodes.forAll
    yield
      val result: IO[Boolean] = for
        dir  <- tempDir
        m1   <- FileBackedAgentMemory[IO](dir)
        _    <- m1.rememberAll(episodes)
        m2   <- FileBackedAgentMemory[IO](dir)
        hits <- m2.recall("", episodes.length.max(1), None)
      yield
        val expectedTexts: Set[String] = episodes.map(_.content).toSet
        val actualTexts: Set[String]   = hits.map(_.text).toSet
        actualTexts == expectedTexts
      result.unsafeRunSync() ==== true
  }

  property("reload-equivalence: fresh instance recalls same hits as original") {
    // spec: cross-run-memory-example — Property 2: reload equivalence
    // Generator strategy: genEpisode + genQuery + genK (local)
    // Constructive. Edge cases: empty query, matching, non-matching.
    for
      episode <- genEpisode.forAll
      query   <- genQuery.forAll
      k       <- genK.forAll
    yield
      val result: IO[Boolean] = for
        dir <- tempDir
        m1  <- FileBackedAgentMemory[IO](dir)
        _   <- m1.remember(episode)
        h1  <- m1.recall(query, k, None)
        m2  <- FileBackedAgentMemory[IO](dir)
        h2  <- m2.recall(query, k, None)
      yield h1 == h2
      result.unsafeRunSync() ==== true
  }

  property("scoring-delegates: FileBackedAgentMemory score equals naiveScore") {
    // spec: cross-run-memory-example — Property 3: scoring delegates
    // Generator strategy: genContent + genQuery (local)
    // Constructive. Edge cases: query is substring, partial match, no match.
    for
      content <- genContent.forAll
      query   <- genQuery.forAll
    yield
      val now: Instant = Instant.parse("2026-07-19T12:00:00Z")
      val ep: Episode  = Episode(content, SourceType.Conversation, now, None, Map.empty)
      val expectedScore: Double =
        InMemoryAgentMemory.naiveScore(content.toLowerCase, query.toLowerCase)
      val result: IO[Boolean] = for
        dir  <- tempDir
        mem  <- FileBackedAgentMemory[IO](dir)
        _    <- mem.remember(ep)
        hits <- mem.recall(query, 5, None)
      yield
        if expectedScore > 0.0 then hits.headOption.exists(_.score == expectedScore)
        else hits.isEmpty
      result.unsafeRunSync() ==== true
  }

  property("empty-storage-no-error: missing file returns Nil without raising") {
    // spec: cross-run-memory-example — Property 4: empty storage
    // Generator strategy: genQuery + genK (local)
    // Constructive. Edge cases: directory may not have episodes file.
    for
      query <- genQuery.forAll
      k     <- genK.forAll
    yield
      val result: IO[Boolean] = for
        dir <- tempDir
        mem <- FileBackedAgentMemory[IO](dir)
        r   <- mem.recall(query, k, None).attempt
      yield r match
        case Right(hits) => hits.isEmpty
        case Left(_)     => false
      result.unsafeRunSync() ==== true
  }
