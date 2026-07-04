package org.adk4s.memory.testkit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import hedgehog.Gen
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.memory.{
  AgentMemory,
  Episode,
  InMemoryAgentMemory,
  SourceType
}

// Test oracle for spec:memory-testkit.
// Tests written from the spec + approved typed contract ONLY, before
// implementation. They compile against the stub main sources (??? bodies)
// and are EXPECTED TO FAIL at runtime until Step 3.
// Every test cites its source: `// spec: memory-testkit - Scenario: <heading>`
class AgentMemoryLawsSpec extends HedgehogSuite:

  private val laws: AgentMemoryLaws = AgentMemoryLaws(indexesContent = true)

  /** Real munit assertions (Hedgehog's assert/assertEquals are no-ops in test
    * blocks — see spec 1 Ring 5 notes).
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

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: AgentMemoryLaws encodes four behavioral laws gated by indexesContent
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: all four laws pass for InMemoryAgentMemory ─────────────────

  test("all four laws pass for InMemoryAgentMemory") {
    // spec: memory-testkit — Scenario: all four laws pass for InMemoryAgentMemory
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      ok  <- laws.all(mem)
    yield ok
    val ok = result.unsafeRunSync()
    assertM(ok, "all(mem) must be true for InMemoryAgentMemory")
  }

  // ── Scenario: recallAfterRemember is a no-op when indexesContent is false ─

  test("recallAfterRemember is a no-op when indexesContent is false") {
    // spec: memory-testkit — Scenario: recallAfterRemember is a no-op when indexesContent is false
    val falseLaws = AgentMemoryLaws(indexesContent = false)
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      ok  <- falseLaws.recallAfterRemember(mem)
    yield ok
    val ok = result.unsafeRunSync()
    assertM(ok, "recallAfterRemember must be true (no-op) when indexesContent is false")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: kBound law asserts recall returns at most k
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: kBound passes for InMemoryAgentMemory ──────────────────────

  test("kBound passes for InMemoryAgentMemory") {
    // spec: memory-testkit — Scenario: kBound passes for InMemoryAgentMemory
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      ok  <- laws.kBound(mem)
    yield ok
    val ok = result.unsafeRunSync()
    assertM(ok, "kBound(mem) must be true for InMemoryAgentMemory")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: scoreOrdering law asserts descending score
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: scoreOrdering passes for InMemoryAgentMemory ───────────────

  test("scoreOrdering passes for InMemoryAgentMemory") {
    // spec: memory-testkit — Scenario: scoreOrdering passes for InMemoryAgentMemory
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      ok  <- laws.scoreOrdering(mem)
    yield ok
    val ok = result.unsafeRunSync()
    assertM(ok, "scoreOrdering(mem) must be true for InMemoryAgentMemory")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: recallAfterRemember law asserts recall finds a remembered term
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: recallAfterRemember passes for InMemoryAgentMemory ─────────

  test("recallAfterRemember passes for InMemoryAgentMemory") {
    // spec: memory-testkit — Scenario: recallAfterRemember passes for InMemoryAgentMemory
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      ok  <- laws.recallAfterRemember(mem)
    yield ok
    val ok = result.unsafeRunSync()
    assertM(ok, "recallAfterRemember(mem) must be true for InMemoryAgentMemory")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: temporalIgnorability law asserts scope never errors
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: temporalIgnorability passes for InMemoryAgentMemory ────────

  test("temporalIgnorability passes for InMemoryAgentMemory") {
    // spec: memory-testkit — Scenario: temporalIgnorability passes for InMemoryAgentMemory
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      ok  <- laws.temporalIgnorability(mem)
    yield ok
    val ok = result.unsafeRunSync()
    assertM(ok, "temporalIgnorability(mem) must be true for InMemoryAgentMemory")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: adk4s-memory-testkit module publishes AgentMemoryLaws in main scope
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: testkit module compiles independently ──────────────────────

  test("testkit module compiles independently") {
    // spec: memory-testkit — Scenario: testkit module compiles independently
    // This test itself is proof the module compiles. If AgentMemoryLaws were
    // in Test scope of adk4s-memory-api, this import would fail.
    val l: AgentMemoryLaws = AgentMemoryLaws(indexesContent = true)
    assertEqualsM(l.indexesContent, true)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  property("laws-all-implies-conjunction: all == k && s && r && t") {
    // spec: memory-testkit — Property: laws-all-implies-conjunction
    // Generator strategy: genIndexesContent (Gen.boolean)
    // Classify by indexesContent
    for indexesContent <- Gen.boolean.forAll
    yield
      val l = AgentMemoryLaws(indexesContent)
      val (a, k, s, r, t) = (for
        mem <- InMemoryAgentMemory.create[IO]
        a   <- l.all(mem)
        k   <- l.kBound(mem)
        s   <- l.scoreOrdering(mem)
        r   <- l.recallAfterRemember(mem)
        t   <- l.temporalIgnorability(mem)
      yield (a, k, s, r, t)).unsafeRunSync()
      (a == (k && s && r && t)) ==== true
  }

  property("laws-pass-for-known-good-inmemory: all is true") {
    // spec: memory-testkit — Property: laws-pass-for-known-good-inmemory
    // Deterministic (no generator) — run the assertion once.
    for
      _  <- Gen.constant(()).forAll
      ok = (for
        mem <- InMemoryAgentMemory.create[IO]
        ok  <- AgentMemoryLaws(indexesContent = true).all(mem)
      yield ok).unsafeRunSync()
    yield
      ok ==== true
  }
