package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._

/**
 * Tests for ShimGenerator — the pure function that generates 3-line hook
 * shims idempotently.
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: probatioGateShim regenerates hook shims idempotently
 * spec: port-scanner-to-probatio/sbt-plugin — Property: shim-idempotency
 */
final class ShimGeneratorSpec extends ProbatioPluginSuite {

  // ── Property: shim-idempotency ──────────────────────────────────────────
  // spec: sbt-plugin — Property: shim-idempotency
  property("shim-idempotency: running twice produces byte-identical output") {
    for {
      resolvedPath <- Gen.string(Gen.alphaNum, linear(1, 50)).map(p => s"/usr/local/bin/$p").forAll
    } yield {
      val shim1: String = ShimGenerator.generateShim(resolvedPath)
      val shim2: String = ShimGenerator.generateShim(resolvedPath)
      Result.assert(shim1 == shim2)
        .log(s"shim1 != shim2:\n$shim1\n---\n$shim2")
        .and(
          Result.assert(shim1.linesIterator.length == 2)
            .log(s"expected 2 lines (shebang + exec), got ${shim1.linesIterator.length}")
        )
        .and(
          Result.assert(shim1.startsWith("#!/usr/bin/env bash\n"))
            .log(s"missing shebang: $shim1")
        )
        .and(
          Result.assert(shim1.contains("gate \"$@\""))
            .log(s"missing gate subcommand: $shim1")
        )
    }
  }

  // ── Scenario: shim is three lines ───────────────────────────────────────
  // spec: sbt-plugin — Scenario: shim is three lines
  test("shim contains exactly shebang, exec, and trailing newline") {
    val path: String = "/usr/local/bin/probatio"
    val shim: String = ShimGenerator.generateShim(path)
    val lines: Array[String] = shim.split("\n", -1)
    // 3 lines: shebang, exec, trailing empty (from trailing newline)
    assertEquals(lines.length, 3, s"expected 3 parts (shebang, exec, trailing), got ${lines.length}: $shim")
    assertEquals(lines(0), "#!/usr/bin/env bash")
    assertEquals(lines(1), s"""exec "$path" gate "$$@"""")
    assertEquals(lines(2), "")
  }

  // ── Scenario: running twice produces byte-identical output ──────────────
  // spec: sbt-plugin — Scenario: running twice produces byte-identical output
  test("running ShimGenerator twice produces byte-identical output") {
    val path: String = "/opt/probatio/bin/probatio"
    val shim1: String = ShimGenerator.generateShim(path)
    val shim2: String = ShimGenerator.generateShim(path)
    assertEquals(shim1, shim2)
  }

  // ── Scenario: shim content changes when binary path changes ─────────────
  // spec: sbt-plugin — Scenario: shim content changes when binary path changes
  test("shim content changes when binary path changes") {
    val pathA: String = "/usr/local/bin/probatio"
    val pathB: String = "/opt/probatio/bin/probatio"
    val shimA: String = ShimGenerator.generateShim(pathA)
    val shimB: String = ShimGenerator.generateShim(pathB)
    assertNotEquals(shimA, shimB, "shim should differ when binary path changes")
    assert(shimB.contains(pathB), s"shim should reference path B ($pathB): $shimB")
    assert(!shimB.contains(pathA), s"shim should NOT reference path A ($pathA): $shimB")
  }

  // ── Scenario: shim with path containing spaces ──────────────────────────
  // Edge case from spec: path with spaces
  test("shim with path containing spaces is correctly quoted") {
    val path: String = "/opt/my tools/probatio"
    val shim: String = ShimGenerator.generateShim(path)
    assert(shim.contains(s"""exec "$path" gate "$$@""""), s"exec line should quote path with spaces: $shim")
  }

  // ── Scenario: shim always ends with trailing newline ────────────────────
  test("shim always ends with trailing newline") {
    val path: String = "/usr/local/bin/probatio"
    val shim: String = ShimGenerator.generateShim(path)
    assert(shim.endsWith("\n"), s"shim must end with trailing newline: repr=${shim.map(c => if (c == '\n') "\\n" else c.toString).mkString}")
  }
}
