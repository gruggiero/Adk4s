package org.adk4s.core.interrupt
package typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  Typed Contract for spec: memory-orchestration-events
//  Generated: 2026-07-19
//  Schema: verified-scala3
//
//  Verifies the two new `AgentEvent` variants (`MemoryRecalled`,
//  `MemoryWritten`) compile with the correct signatures and that the
//  exhaustiveness obligation is enforced: a pattern match over the seven
//  pre-existing variants with no catch-all MUST NOT compile after the two
//  new variants are added.
//
//  PLACEMENT: adk4s-core/src/test/scala/org/adk4s/core/interrupt/typecontract/
//  so that `sbt adk4s-core/Test/compile` genuinely compiles it against the
//  real project classpath.
//
//  LIFECYCLE — after implementation this file is a permanent API CONFORMANCE
//  ASSERTION. The `compileErrors` blocks reference the real main-source
//  types, so they serve as zero-cost signature pins.
//
//  Status: [x] Compiles via adk4s-core/Test/compile  [ ] Human-approved
// ═══════════════════════════════════════════════════════════════════════════

/** Type contract test suite for the two new `AgentEvent` variants.
  *
  * No behavioral tests here (those live in the test oracle:
  * `MemoryEventsSpec` in adk4s-orchestration).
  */
class MemoryEventsTypeContract extends munit.FunSuite:

  // ── Signature verification (compile-only, no runtime invocation) ──────────

  test("MemoryRecalled signature: runPath, query, hitCount") {
    val errors: String = compileErrors("""
      val e: org.adk4s.core.interrupt.AgentEvent.MemoryRecalled =
        org.adk4s.core.interrupt.AgentEvent.MemoryRecalled(
          runPath = org.adk4s.core.interrupt.RunPath.of("agent"),
          query = "hello",
          hitCount = 3
        )
      val rp: org.adk4s.core.interrupt.RunPath = e.runPath
      val q: String = e.query
      val hc: Int = e.hitCount
      ()
    """)
    assert(errors.isEmpty, s"MemoryRecalled must compile: $errors")
  }

  test("MemoryWritten signature: runPath, episodes") {
    val errors: String = compileErrors("""
      val e: org.adk4s.core.interrupt.AgentEvent.MemoryWritten =
        org.adk4s.core.interrupt.AgentEvent.MemoryWritten(
          runPath = org.adk4s.core.interrupt.RunPath.of("agent"),
          episodes = 2
        )
      val rp: org.adk4s.core.interrupt.RunPath = e.runPath
      val ep: Int = e.episodes
      ()
    """)
    assert(errors.isEmpty, s"MemoryWritten must compile: $errors")
  }

  test("MemoryRecalled and MemoryWritten extend AgentEvent") {
    val errors: String = compileErrors("""
      val r: org.adk4s.core.interrupt.AgentEvent =
        org.adk4s.core.interrupt.AgentEvent.MemoryRecalled(
          org.adk4s.core.interrupt.RunPath.of("a"), "q", 0
        )
      val w: org.adk4s.core.interrupt.AgentEvent =
        org.adk4s.core.interrupt.AgentEvent.MemoryWritten(
          org.adk4s.core.interrupt.RunPath.of("a"), 0
        )
      ()
    """)
    assert(errors.isEmpty, s"new variants must extend AgentEvent: $errors")
  }

  test("withPrependedStep is implemented for both new variants") {
    val errors: String = compileErrors("""
      val step: org.adk4s.core.interrupt.RunStep =
        org.adk4s.core.interrupt.RunStep("outer")
      val r: org.adk4s.core.interrupt.AgentEvent.MemoryRecalled =
        org.adk4s.core.interrupt.AgentEvent.MemoryRecalled(
          org.adk4s.core.interrupt.RunPath.of("a"), "q", 1
        ).withPrependedStep(step)
      val w: org.adk4s.core.interrupt.AgentEvent.MemoryWritten =
        org.adk4s.core.interrupt.AgentEvent.MemoryWritten(
          org.adk4s.core.interrupt.RunPath.of("a"), 1
        ).withPrependedStep(step)
      ()
    """)
    assert(errors.isEmpty, s"withPrependedStep must compile for new variants: $errors")
  }

  // ── Exhaustiveness obligation ─────────────────────────────────────────────
  // NOTE: The `compileErrors` macro in munit/Scala 3 does NOT apply the
  // project's `-Wconf:name=PatternMatchExhaustivity:e` flag, so an
  // inexhaustive match over `AgentEvent` (missing the two new variants) is
  // NOT flagged as an error inside `compileErrors`. The exhaustiveness
  // obligation is therefore enforced at RING 0 (`sbt adk4s-core/compile`
  // and `sbt adk4s-examples/compile`), where `-Wconf` IS active. The two
  // example files with exhaustive matches (`EventStreamExample.scala`,
  // `HierarchicalEventStreamExample.scala`) are updated in Step 3 to add
  // the new arms; if they were NOT updated, `sbt adk4s-examples/compile`
  // would fail. This type contract verifies the POSITIVE side: an
  // exhaustive match over all 9 variants compiles, and a catch-all match
  // compiles.

  test("Exhaustive match over all 9 variants compiles") {
    val errors: String = compileErrors("""
      def classify(e: org.adk4s.core.interrupt.AgentEvent): Int = e match
        case _: org.adk4s.core.interrupt.AgentEvent.MessageOutput     => 1
        case _: org.adk4s.core.interrupt.AgentEvent.ToolCallRequested  => 2
        case _: org.adk4s.core.interrupt.AgentEvent.ToolCallCompleted  => 3
        case _: org.adk4s.core.interrupt.AgentEvent.IterationCompleted => 4
        case _: org.adk4s.core.interrupt.AgentEvent.Interrupted        => 5
        case _: org.adk4s.core.interrupt.AgentEvent.ErrorOccurred      => 6
        case _: org.adk4s.core.interrupt.AgentEvent.TokenDelta         => 7
        case _: org.adk4s.core.interrupt.AgentEvent.MemoryRecalled     => 8
        case _: org.adk4s.core.interrupt.AgentEvent.MemoryWritten      => 9
    """)
    assert(errors.isEmpty, s"exhaustive match over all 9 variants must compile: $errors")
  }

  test("Match with catch-all compiles (new variants fall through)") {
    val errors: String = compileErrors("""
      def classify(e: org.adk4s.core.interrupt.AgentEvent): Int = e match
        case _: org.adk4s.core.interrupt.AgentEvent.MessageOutput     => 1
        case _: org.adk4s.core.interrupt.AgentEvent.ToolCallRequested  => 2
        case _: org.adk4s.core.interrupt.AgentEvent.ToolCallCompleted  => 3
        case _: org.adk4s.core.interrupt.AgentEvent.IterationCompleted => 4
        case _: org.adk4s.core.interrupt.AgentEvent.Interrupted        => 5
        case _: org.adk4s.core.interrupt.AgentEvent.ErrorOccurred      => 6
        case _: org.adk4s.core.interrupt.AgentEvent.TokenDelta         => 7
        case _                                                         => 0
    """)
    assert(errors.isEmpty, s"match with catch-all must compile: $errors")
  }
