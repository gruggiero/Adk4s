package org.adk4s.memory.testkit

import cats.effect.IO
import org.adk4s.memory.AgentMemory

/** Type contract for spec:memory-testkit.
  *
  * Verifies that `AgentMemoryLaws` has the correct signatures. This file
  * contains NO behavioral tests — those live in `AgentMemoryLawsSpec.scala`.
  *
  * Signature tests use `compileErrors` to verify compilation without invoking
  * the stub bodies (which throw `???`).
  */
class AgentMemoryLawsTypeContract extends munit.FunSuite:

  // ── Signature verification (compile-only, no runtime invocation) ──────────

  test("AgentMemoryLaws is a case class with indexesContent: Boolean") {
    val errors: String = compileErrors("""
      val laws: org.adk4s.memory.testkit.AgentMemoryLaws =
        org.adk4s.memory.testkit.AgentMemoryLaws(indexesContent = true)
    """)
    assert(errors.isEmpty, s"Signature must compile: $errors")
  }

  test("AgentMemoryLaws methods return IO[Boolean] for AgentMemory[IO]") {
    val errors: String = compileErrors("""
      def check(mem: org.adk4s.memory.AgentMemory[cats.effect.IO]): Unit =
        val laws = org.adk4s.memory.testkit.AgentMemoryLaws(indexesContent = true)
        val k: cats.effect.IO[Boolean]   = laws.kBound(mem)
        val s: cats.effect.IO[Boolean]   = laws.scoreOrdering(mem)
        val r: cats.effect.IO[Boolean]   = laws.recallAfterRemember(mem)
        val t: cats.effect.IO[Boolean]   = laws.temporalIgnorability(mem)
        val a: cats.effect.IO[Boolean]   = laws.all(mem)
        ()
    """)
    assert(errors.isEmpty, s"Signatures must compile: $errors")
  }

  test("AgentMemoryLaws.now is a java.time.Instant") {
    val errors: String = compileErrors("""
      val n: java.time.Instant = org.adk4s.memory.testkit.AgentMemoryLaws.now
    """)
    assert(errors.isEmpty, s"Signature must compile: $errors")
  }
