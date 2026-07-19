package org.adk4s.orchestration.memory

import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.memory.MemoryHit

/**
 * Test oracle for spec:memory-orchestration-hook — `MemoryPolicy` scenarios
 * and the "render is pure and total" property.
 *
 * Tests written from the spec + approved typed contract ONLY, before
 * implementation. They compile against the stub main sources (`???` bodies)
 * and are EXPECTED TO FAIL at runtime until Step 3.
 *
 * Every test cites its source: `// spec: memory-orchestration-hook — Scenario: <heading>`
 */
class MemoryPolicySpec extends HedgehogSuite with MunitAssertHelpers:
  import Generators.*

  // ── Scenarios ────────────────────────────────────────────────────────────

  test("Default render formats two hits") {
    // spec: memory-orchestration-hook — Scenario: Default render formats two hits
    val hits: List[MemoryHit] = List(
      MemoryHit("a", 0.9),
      MemoryHit("b", 0.8)
    )
    val rendered: String = MemoryPolicy.defaultRender(hits)
    // Then: output contains "Relevant memory:", "a", and "b" each on a distinct line
    assertM(rendered.contains("Relevant memory:"), s"expected 'Relevant memory:' in: $rendered")
    assertM(rendered.contains("a"), s"expected 'a' in: $rendered")
    assertM(rendered.contains("b"), s"expected 'b' in: $rendered")
  }

  test("Default render on empty list") {
    // spec: memory-orchestration-hook — Scenario: Default render on empty list
    val rendered: String = MemoryPolicy.defaultRender(Nil)
    // Then: output is the empty String
    assertM(rendered.isEmpty, s"expected empty string, got: '$rendered'")
  }

  test("Custom render is honored") {
    // spec: memory-orchestration-hook — Scenario: Custom render is honored
    val customRender: List[MemoryHit] => String = _.map(_.text).mkString("[", ",", "]")
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 3,
      writeUserInput = true,
      writeAssistantOutput = true,
      render = customRender
    )
    val hits: List[MemoryHit] = List(MemoryHit("x", 0.9), MemoryHit("y", 0.8))
    val rendered: String = policy.render(hits)
    // Then: the injected context block uses the custom bracket format
    assertEqualsM(rendered, "[x,y]", s"expected '[x,y]', got: '$rendered'")
  }

  test("MemoryPolicy rejects negative recallK") {
    // spec: memory-orchestration-hook — boundary: recallK must be >= 0
    intercept[IllegalArgumentException]:
      MemoryPolicy(recallK = -1)
  }

  test("MemoryPolicy default has recallK = 5") {
    // spec: memory-orchestration-hook — boundary: default recallK = 5
    val p: MemoryPolicy = MemoryPolicy.default
    assertEqualsM(p.recallK, 5, s"expected recallK=5, got ${p.recallK}")
  }

  test("MemoryPolicy default has both write flags true") {
    // spec: memory-orchestration-hook — boundary: default write flags
    val p: MemoryPolicy = MemoryPolicy.default
    assertM(p.writeUserInput, "expected writeUserInput=true by default")
    assertM(p.writeAssistantOutput, "expected writeAssistantOutput=true by default")
  }

  // ── Properties (Ring 3) ──────────────────────────────────────────────────

  property("render is pure and total") {
    // spec: memory-orchestration-hook — Property: render is pure and total
    // Invariant: defaultRender(hs) == defaultRender(hs) (deterministic),
    // never throws (total), and for Nil the output is "".
    // Generator strategy: genHitList (Range.linear 0 6)
    for hits <- genHitList.forAll
      yield
        val r1: String = MemoryPolicy.defaultRender(hits)
        val r2: String = MemoryPolicy.defaultRender(hits)
        val deterministic: Boolean = r1 == r2
        val emptyImpliesEmpty: Boolean = !hits.isEmpty || r1.isEmpty
        val nonNull: Boolean = r1 != null
        (deterministic && emptyImpliesEmpty && nonNull) ==== true
  }
