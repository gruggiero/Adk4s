package org.adk4s.memory
package typecontract

import cats.effect.IO
import cats.effect.kernel.Sync
import cats.Monad
import org.adk4s.core.component.{ Document, Retriever, RetrieverConfig }

/** Type contract for spec:memory-retriever-bridge.
  *
  * Verifies that `MemoryRetriever` has the correct signatures and that the
  * `Sync[F]` constraint is enforced at compile time. This file contains NO
  * behavioral tests — those live in `MemoryRetrieverSpec.scala`.
  *
  * Signature tests use `compileErrors` to verify compilation without invoking
  * the stub bodies (which throw `???`).
  */
class MemoryRetrieverTypeContract extends munit.FunSuite:

  // ── Signature verification (compile-only, no runtime invocation) ──────────

  test("MemoryRetriever.apply signature: returns Retriever[F], requires Sync[F]") {
    val errors: String = compileErrors("""
      def check[F[_]: Sync](mem: org.adk4s.memory.AgentMemory[F]): Unit =
        val r1: org.adk4s.core.component.Retriever[F] =
          org.adk4s.memory.MemoryRetriever.apply[F](mem)
        val r2: org.adk4s.core.component.Retriever[F] =
          org.adk4s.memory.MemoryRetriever.apply[F](mem, k = 5)
        val r3: org.adk4s.core.component.Retriever[F] =
          org.adk4s.memory.MemoryRetriever.apply[F](mem, k = 5, scope = None)
        val r4: org.adk4s.core.component.Retriever[F] =
          org.adk4s.memory.MemoryRetriever.apply[F](mem, scope = Some(
            org.adk4s.memory.TemporalScope(java.time.Instant.parse("2025-01-01T00:00:00Z"))
          ))
        ()
    """)
    assert(errors.isEmpty, s"Signature must compile: $errors")
  }

  test("MemoryRetriever.toDocument signature: MemoryHit => Document") {
    val errors: String = compileErrors("""
      def check(hit: org.adk4s.memory.MemoryHit): org.adk4s.core.component.Document =
        org.adk4s.memory.MemoryRetriever.toDocument(hit)
    """)
    assert(errors.isEmpty, s"Signature must compile: $errors")
  }

  // ── Compile-Negative: Sync[F] required on the factory ─────────────────────

  test("MemoryRetriever.apply without Sync[F] does not compile") {
    // spec: memory-retriever-bridge — Compile-Negative: Sync required
    // Only Monad[F] in scope, no Sync[F] → must not compile
    val errors: String = compileErrors("""
      def check[F[_]](mem: org.adk4s.memory.AgentMemory[F])(using F: cats.Monad[F]): Unit =
        val r: org.adk4s.core.component.Retriever[F] =
          org.adk4s.memory.MemoryRetriever.apply[F](mem)
        ()
    """)
    assert(errors.nonEmpty, "MemoryRetriever.apply must require Sync[F]")
  }
