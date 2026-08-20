package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._

/**
 * Tests for InstallResolver — the pure function that models the probatioInstall
 * resolution order: prebuilt binary → assembly JAR → (opt-in) local native-image.
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image
 * spec: port-scanner-to-probatio/sbt-plugin — Property: install-resolution-order
 */
final class InstallResolverSpec extends ProbatioPluginSuite {

  // ── Property: install-resolution-order ──────────────────────────────────
  // spec: sbt-plugin — Property: install-resolution-order
  property("install-resolution-order: each fallback emits exactly one distinct log line") {
    for {
      scenario <- Gen.element1(
        ResolutionScenario.PrebuiltAvailable,
        ResolutionScenario.PrebuiltChecksumInvalid,
        ResolutionScenario.JarFallback,
        ResolutionScenario.NativeImage
      ).forAll
    } yield {
      val result: ResolutionResult = InstallResolver.resolve(scenario)
      val firstLine: String = result.logLines.headOption.getOrElse("")
      Result.assert(result.logLines.nonEmpty)
        .log("no fallback should be silent — logLines must be nonEmpty")
        .and(Result.assert(result.logLines.length == 1)
          .log(s"expected exactly 1 log line, got ${result.logLines.length}: ${result.logLines}"))
        .and(
          (scenario match {
            case ResolutionScenario.PrebuiltAvailable | ResolutionScenario.NativeImage =>
              Result.assert(firstLine.startsWith("[info]"))
                .log(s"prebuilt/native-image should emit [info], got: $firstLine")
            case ResolutionScenario.PrebuiltChecksumInvalid | ResolutionScenario.JarFallback =>
              Result.assert(firstLine.startsWith("[warn]"))
                .log(s"JAR fallback should emit [warn], got: $firstLine")
          })
        )
    }
  }

  // ── Scenario: prebuilt binary resolves successfully ─────────────────────
  // spec: sbt-plugin — Scenario: prebuilt binary resolves successfully
  test("prebuilt binary available → [info] log line, binary path") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.PrebuiltAvailable)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[info]"), s"should be [info]: $line")
    assert(line.contains("prebuilt"), s"should mention 'prebuilt': $line")
    assert(result.path.nonEmpty, "should have a resolved path")
  }

  // ── Scenario: assembly JAR fallback emits a distinct log line ───────────
  // spec: sbt-plugin — Scenario: assembly JAR fallback emits a distinct log line
  test("prebuilt unavailable → JAR fallback with [warn] log line") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.JarFallback)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[warn]"), s"should be [warn]: $line")
    assert(line.contains("JAR"), s"should mention 'JAR': $line")
  }

  // ── Scenario: checksum invalid → JAR fallback with [warn] ───────────────
  test("prebuilt checksum invalid → JAR fallback with [warn] log line") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.PrebuiltChecksumInvalid)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[warn]"), s"should be [warn]: $line")
  }

  // ── Scenario: local native-image is opt-in only ─────────────────────────
  // spec: sbt-plugin — Scenario: local native-image is opt-in only
  test("native-image scenario emits [info] and has a path") {
    val result: ResolutionResult = InstallResolver.resolve(ResolutionScenario.NativeImage)
    assertEquals(result.logLines.length, 1, "exactly one log line")
    val line: String = result.logLines.headOption.getOrElse(fail("logLines should not be empty"))
    assert(line.startsWith("[info]"), s"should be [info]: $line")
    assert(result.path.nonEmpty, "should have a resolved path")
  }

  // ── Scenario: no fallback is silent (adversarial) ───────────────────────
  // spec: sbt-plugin — Scenario: no fallback is silent (adversarial)
  test("no resolution scenario produces zero log lines") {
    val scenarios: List[ResolutionScenario] = ResolutionScenario.values.toList
    for (scenario <- scenarios) {
      val result: ResolutionResult = InstallResolver.resolve(scenario)
      assert(result.logLines.nonEmpty, s"scenario $scenario should not be silent — logLines is empty")
    }
  }

  // ── Scenario: prebuilt [info] and JAR [warn] are distinguishable ────────
  test("prebuilt [info] and JAR [warn] log lines are distinguishable") {
    val prebuilt: String = InstallResolver.resolve(ResolutionScenario.PrebuiltAvailable).logLines.headOption.getOrElse(fail("prebuilt logLines empty"))
    val jar: String = InstallResolver.resolve(ResolutionScenario.JarFallback).logLines.headOption.getOrElse(fail("jar logLines empty"))
    assertNotEquals(prebuilt, jar, "prebuilt and JAR log lines must be distinguishable")
    assert(prebuilt.startsWith("[info]"), s"prebuilt should be [info]: $prebuilt")
    assert(jar.startsWith("[warn]"), s"JAR should be [warn]: $jar")
  }

  // ── Scenario: ResolutionScenario is a sealed enum with exactly 4 cases ──
  test("ResolutionScenario has exactly 4 cases") {
    assertEquals(ResolutionScenario.values.length, 4, s"expected 4 cases, got ${ResolutionScenario.values.length}")
  }
}
