package org.adk4s.orchestration.memory
package typecontract

import cats.effect.IO
import fs2.Stream
import org.adk4s.core.interrupt.{AgentEvent, InterruptResult}
import org.adk4s.memory.{AgentMemory, MemoryHit, TemporalScope}
import org.adk4s.orchestration.agent.{AgentRunner, RunResult}
import org.llm4s.llmconnect.model.Message

import java.time.Instant

// ═══════════════════════════════════════════════════════════════════════════
//  Typed Contract for spec: memory-orchestration-hook
//  Generated: 2026-07-19
//  Schema: verified-scala3
//
//  The type declarations live in MAIN sources
//  (org.adk4s.orchestration.memory) with `???` bodies — this file verifies
//  their signatures compile correctly and that compile-negative obligations
//  are enforced, using `compileErrors` against the real classpath.
//
//  PLACEMENT: this file lives in the owning module's TEST sources:
//    adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/typecontract/
//  so that `sbt adk4s-orchestration/Test/compile` genuinely compiles it against
//  the real project classpath.
//
//  LIFECYCLE — after implementation (apply Step 3) this file is NOT deleted
//  or gutted: it becomes a permanent API CONFORMANCE ASSERTION. The
//  `compileErrors` blocks below already reference the real main-source types,
//  so they serve as zero-cost signature pins — any later signature drift
//  breaks adk4s-orchestration/Test/compile.
//
//  Status: [x] Compiles via adk4s-orchestration/Test/compile  [ ] Human-approved
//          [ ] Converted to conformance assertions after implementation
// ═══════════════════════════════════════════════════════════════════════════

// ── Concepts reused (from openspec/concept-inventory.md) ─────────────────────────
// org.adk4s.memory.AgentMemory[F]          — service trait — recall/remember capability
// org.adk4s.memory.Episode                 — case class — unit of experience (content, sourceType, timestamp, groupId, metadata)
// org.adk4s.memory.SourceType              — enum — Conversation/Document/StructuredData/ToolResult/ExternalApi
// org.adk4s.memory.EpisodeOutcome          — case class — result of remember
// org.adk4s.memory.MemoryHit               — case class — recalled fact (text, score, validFrom, validTo, provenance, payload)
// org.adk4s.memory.TemporalScope           — case class — point-in-time scoping (asOf: Instant)
// org.adk4s.orchestration.agent.AgentRunner — final class — the decorated runner (run/runWithEvents/resume)
// org.adk4s.orchestration.agent.RunResult   — sealed trait — Completed/Interrupted/Failed
// org.llm4s.llmconnect.model.Message        — sealed trait — UserMessage/AssistantMessage/SystemMessage/ToolMessage
// org.adk4s.core.interrupt.AgentEvent       — sealed trait — event stream variant
// org.adk4s.core.interrupt.InterruptResult  — case class — resume data

/** Type contract test suite — verifies signatures compile and compile-negative
  * obligations are enforced. No behavioral tests here (those live in the
  * test oracle: MemoryAwareRunnerSpec, MemoryHookSpec, MemoryPolicySpec).
  */
class MemoryAwareRunnerTypeContract extends munit.FunSuite:

  // ── Signature verification (compile-only, no runtime invocation) ──────────

  test("MemoryPolicy.apply signature: smart constructor with defaults") {
    val errors: String = compileErrors("""
      val p1: org.adk4s.orchestration.memory.MemoryPolicy =
        org.adk4s.orchestration.memory.MemoryPolicy(recallK = 5)
      val p2: org.adk4s.orchestration.memory.MemoryPolicy =
        org.adk4s.orchestration.memory.MemoryPolicy(
          recallK = 3,
          scope = Some(org.adk4s.memory.TemporalScope(java.time.Instant.parse("2025-01-01T00:00:00Z"))),
          writeUserInput = true,
          writeAssistantOutput = false
        )
      val p3: org.adk4s.orchestration.memory.MemoryPolicy =
        org.adk4s.orchestration.memory.MemoryPolicy(
          recallK = 0,
          render = _.map(_.text).mkString("[", ",", "]")
        )
      ()
    """)
    assert(errors.isEmpty, s"MemoryPolicy.apply must compile: $errors")
  }

  test("MemoryPolicy.default signature: no-arg factory") {
    val errors: String = compileErrors("""
      val p: org.adk4s.orchestration.memory.MemoryPolicy =
        org.adk4s.orchestration.memory.MemoryPolicy.default
      ()
    """)
    assert(errors.isEmpty, s"MemoryPolicy.default must compile: $errors")
  }

  test("MemoryPolicy.defaultRender signature: List[MemoryHit] => String") {
    val errors: String = compileErrors("""
      val r: String =
        org.adk4s.orchestration.memory.MemoryPolicy.defaultRender(List.empty)
      ()
    """)
    assert(errors.isEmpty, s"MemoryPolicy.defaultRender must compile: $errors")
  }

  test("MemoryHook signature: preTurn and postTurn over Option[AgentMemory[IO]]") {
    val errors: String = compileErrors("""
      val hook: org.adk4s.orchestration.memory.MemoryHook =
        new org.adk4s.orchestration.memory.MemoryHook(
          None,
          org.adk4s.orchestration.memory.MemoryPolicy.default
        )
      val pre: cats.effect.IO[Option[String]] = hook.preTurn("query")
      val post: cats.effect.IO[Unit] = hook.postTurn("group-1", "query", "answer", java.time.Instant.now)
      ()
    """)
    assert(errors.isEmpty, s"MemoryHook signatures must compile: $errors")
  }

  test("MemoryAwareRunner signature: run/runWithEvents/resume wrapping AgentRunner") {
    val errors: String = compileErrors("""
      def check(runner: org.adk4s.orchestration.agent.AgentRunner): Unit =
        val decorator: org.adk4s.orchestration.memory.MemoryAwareRunner =
          new org.adk4s.orchestration.memory.MemoryAwareRunner(
            runner,
            None,
            org.adk4s.orchestration.memory.MemoryPolicy.default
          )
        val r1: cats.effect.IO[org.adk4s.orchestration.agent.RunResult] =
          decorator.run(List(org.llm4s.llmconnect.model.UserMessage("hi")))
        val r2: cats.effect.IO[org.adk4s.orchestration.agent.RunResult] =
          decorator.run(List(org.llm4s.llmconnect.model.UserMessage("hi")), maxSteps = 5)
        val r3: (cats.effect.IO[org.adk4s.orchestration.agent.RunResult],
                 fs2.Stream[cats.effect.IO, org.adk4s.core.interrupt.AgentEvent]) =
          decorator.runWithEvents(List(org.llm4s.llmconnect.model.UserMessage("hi")))
        val r4: cats.effect.IO[org.adk4s.orchestration.agent.RunResult] =
          decorator.resume("ckpt-id", List.empty)
        ()
    """)
    assert(errors.isEmpty, s"MemoryAwareRunner signatures must compile: $errors")
  }

  // ── Compile-Negative: MemoryAwareRunner requires an AgentRunner ────────────

  test("MemoryAwareRunner without AgentRunner does not compile") {
    // spec: memory-orchestration-hook — Compile-Negative: no-Runner construction
    // The decorator MUST wrap a real runner — there is no "memory-only" runner.
    // Passing None as the first arg is a type error (Option[Nothing] ≠ AgentRunner).
    val errors: String = compileErrors("""
      val decorator: org.adk4s.orchestration.memory.MemoryAwareRunner =
        new org.adk4s.orchestration.memory.MemoryAwareRunner(
          None,
          None,
          org.adk4s.orchestration.memory.MemoryPolicy.default
        )
      ()
    """)
    assert(errors.nonEmpty, "MemoryAwareRunner must require an AgentRunner as first arg")
  }

  // ── Compile-Negative: MemoryHook.postTurn takes strings, not RunResult ─────

  test("MemoryHook.postTurn does not accept a RunResult") {
    // spec: memory-orchestration-hook — Compile-Negative: postTurn takes strings
    // The Completed-only decision lives in MemoryAwareRunner, not the hook.
    // postTurn accepts (String, String, String, Instant), not RunResult.
    val errors: String = compileErrors("""
      def check(hook: org.adk4s.orchestration.memory.MemoryHook,
                result: org.adk4s.orchestration.agent.RunResult): Unit =
        hook.postTurn(result)
        ()
    """)
    assert(errors.nonEmpty, "MemoryHook.postTurn must not accept a RunResult")
  }

  // ── Compile-Negative: MemoryPolicy.rejects negative recallK at runtime ─────
  // (This is a runtime require, not a compile error — verified by a test in the oracle)

// ── Property & generator obligations (become the Ring 3 test oracle) ────
//
// Property: No-memory decorator is the identity
//   Invariant: For all msgs, policy, and underlying-runner behaviors, a
//   MemoryAwareRunner with memory = None produces a RunResult equal to the
//   underlying runner's and never calls AgentMemory.remember.
//   Generator: genMessages (non-empty list, Range.linear 1 8),
//              genPolicy (recallK 0-5, booleans, default render),
//              genRunResult (Completed/Interrupted/Failed)
//   cover: Completed >= 30%, Interrupted >= 30%, Failed >= 30%
//
// Property: postTurn fires iff Completed
//   Invariant: For all RunResult outcomes and non-None memories, remember is
//   called a positive number of times iff the outcome is Completed; for
//   Interrupted/Failed, remember is called zero times.
//   Generator: genRunResult, genPolicy (write flags booleans)
//   cover: Completed >= 33%, Interrupted >= 33%, Failed >= 33%
//
// Property: recall is called exactly once per run when recallK > 0
//   Invariant: For non-None memories and recallK > 0, recall is called exactly
//   once with k = policy.recallK and scope = policy.scope; for recallK = 0,
//   recall is called zero times.
//   Generator: genRecallK (0-10), genScope, genContent
//   cover: recallK == 0 >= 10%, recallK > 0 >= 80%
//
// Property: render is pure and total
//   Invariant: For all hit lists hs, defaultRender(hs) == defaultRender(hs)
//   (deterministic), never throws (total), and for Nil the output is "".
//   Generator: genHitList (0-6 hits)
//
// Property: groupId is shared across the turn's episodes
//   Invariant: For Completed outcomes with both write flags true, the two
//   Episodes share the same non-None groupId; with one flag true, the single
//   episode's groupId is Some(_).
//   Generator: genPolicy (booleans), genContent, genInstant
//   cover: both-true >= 40%, one-true >= 40%

// ── Formal contracts (Ring 6) — N/A for this spec ───────────────────────
// The hook is effectful IO wiring, not a PureScala module. Ring 6 skipped.
// The pure `render` function is property-tested instead.

// ── Temporal properties (Ring 9) — N/A ──────────────────────────────────
// No telemetry stack detected. Ring 9 skipped.
