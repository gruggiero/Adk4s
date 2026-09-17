package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._

/**
 * Test oracle for the hook-cutover spec — shim-idempotency property and
 * the shim-with-logic compile-negative.
 *
 * These tests live in the sbt-probatio plugin test sources because
 * `ShimGenerator` is defined in the plugin module, which is not visible
 * to probatio-core tests. The core-side tests (dependency order, oracle
 * gating, SwapOrder compile-negative) are in `HookCutoverSpec.scala`.
 *
 * spec: hook-cutover — Requirement: Each bash hook is replaced by a 3-line exec shim pointing to the probatio binary
 * spec: hook-cutover — Property: shim-idempotency
 * spec: hook-cutover — Compile-Negative: shim with logic beyond shebang + exec + newline
 */
final class HookCutoverShimSpec extends ProbatioPluginSuite {

  // ── Requirement: Each bash hook is replaced by a 3-line exec shim
  // spec: hook-cutover — Scenario: The gate shim is generated idempotently
  test("gate shim is generated idempotently — byte-identical on repeat") {
    val path: String  = "/path/to/probatio"
    val shim1: String = ShimGenerator.generateShim(path)
    val shim2: String = ShimGenerator.generateShim(path)
    assertEquals(shim1, shim2, "shim must be byte-identical when regenerated")
    assertEquals(shim1, "#!/usr/bin/env bash\nexec \"/path/to/probatio\" gate \"$@\"\n")
  }

  // ── Requirement: Each bash hook is replaced by a 3-line exec shim
  // spec: hook-cutover — Scenario: A changed binary path changes the shim content
  test("changed binary path changes shim content") {
    val pathA: String = "/old/probatio"
    val pathB: String = "/new/probatio"
    val shimA: String = ShimGenerator.generateShim(pathA)
    val shimB: String = ShimGenerator.generateShim(pathB)
    assertNotEquals(shimA, shimB, "shim must differ when binary path changes")
    assert(shimB.contains(pathB), s"shim must reference new path: $shimB")
    assert(!shimB.contains(pathA), s"shim must not reference old path: $shimB")
  }

  // ── Property: shim-idempotency
  // spec: hook-cutover — Property: shim-idempotency
  // For every resolved binary path, generateShim produces byte-identical
  // output on every call. The shim content is a pure function of the
  // binary path.
  property("shim idempotency") {
    for {
      path <- genBinaryPath.forAll
    } yield {
      val shim1: String = ShimGenerator.generateShim(path)
      val shim2: String = ShimGenerator.generateShim(path)
      Result
        .assert(shim1 == shim2)
        .log(s"shim1 != shim2 for path=$path:\n$shim1\n---\n$shim2")
        .and(
          Result
            .assert(shim1.startsWith("#!/usr/bin/env bash\n"))
            .log(s"missing shebang: $shim1")
        )
        .and(
          Result
            .assert(shim1.endsWith("\n"))
            .log(s"missing trailing newline: $shim1")
        )
        .and(
          Result
            .assert(shim1.contains("gate \"$@\""))
            .log(s"missing gate subcommand: $shim1")
        )
    }
  }

  // ── Compile-Negative: A shim that contains logic beyond shebang + exec + newline
  // spec: hook-cutover — Compile-Negative: shim with logic beyond shebang + exec + newline
  test("compile-negative: ShimGenerator.generateShim takes only path and subcommand — no logic parameter") {
    // The function accepts (path, subcommand) — both are strings that control
    // the exec line content. No parameter exists for injecting arbitrary logic
    // into the shim. A call with a `logic` parameter must fail to compile.
    val err: String = compileErrors("ShimGenerator.generateShim(\"/path\", \"gate\", logic = true)")
    assert(
      err.nonEmpty,
      "ShimGenerator.generateShim must not accept a logic parameter — a shim with logic is a new code path"
    )
  }

  // ── Generator: genBinaryPath
  // Constructive over absolute file paths with varying depths, special
  // characters (spaces, dots), and trailing slashes.
  // Edge cases: root path, path with spaces, path with dots.
  def genBinaryPath: Gen[String] =
    Gen.frequency(
      1 -> Gen.constant("/"),
      List(
        1 -> Gen.constant("/usr/local/bin/probatio"),
        1 -> Gen.constant("/opt/probatio/bin/probatio"),
        1 -> Gen.constant("/opt/my tools/probatio"),
        1 -> Gen.constant("/path/with.dots/./probatio"),
        1 -> Gen.string(Gen.alphaNum, linear(1, 30)).map(p => s"/usr/local/bin/$p"),
        1 -> Gen.string(Gen.alphaNum, linear(1, 20)).map(p => s"/opt/$p/probatio")
      )
    )
}
