package org.adk4s.memory.typecontract

import cats.Monad
import cats.effect.kernel.Sync
import java.time.Instant

/**
 * Typed contract for spec:agent-memory.
 *
 * After Step 3, the real types live in main sources (`org.adk4s.memory.*`).
 * This file retains compile-time signature verification and compile-negative
 * obligations that are test-side only.
 */
object AgentMemoryTypeContract:

  // ════════════════════════════════════════════════════════════════════════
  // Signature verification — proves the real types compile together
  // ════════════════════════════════════════════════════════════════════════

  def verifySignatures[F[_]](using Sync[F], Monad[F]): Unit =
    import org.adk4s.memory.*
    // Episode.conversation produces the right shape
    val ep: Episode             = Episode.conversation("hello", "g1", Instant.parse("2025-01-01T00:00:00Z"))
    val st: SourceType          = SourceType.Conversation
    val outcome: EpisodeOutcome = EpisodeOutcome.empty
    val ok: Boolean             = outcome.isSuccess
    val hit: MemoryHit          = MemoryHit("text", 0.9, Some(Instant.parse("2025-01-01T00:00:00Z")))
    val scope: TemporalScope    = TemporalScope(Instant.parse("2025-01-01T00:00:00Z"))
    // AgentMemory trait has remember/recall/rememberAll
    val mem: AgentMemory[F]         = ???
    val r1: F[EpisodeOutcome]       = mem.remember(ep)
    val r2: F[List[MemoryHit]]      = mem.recall("query", 5)
    val r3: F[List[MemoryHit]]      = mem.recall("query", 5, Some(scope))
    val r4: F[List[EpisodeOutcome]] = mem.rememberAll(List(ep))
    // InMemoryAgentMemory requires Sync
    val created: F[AgentMemory[F]] = InMemoryAgentMemory.create[F]
    // naiveScore is accessible
    val score: Double = InMemoryAgentMemory.naiveScore("hello world", "hello")
    (): Unit
