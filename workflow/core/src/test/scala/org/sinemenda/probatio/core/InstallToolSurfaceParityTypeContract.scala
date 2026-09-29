package org.sinemenda.probatio.core

/**
 * Typed contract for install-tool-surface-parity (spec 7 of
 * `repair-probatio-cutover`, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath,
 * `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `InstallTarget` is a two-variant enum: `AllPresentHarnesses` (the
 *    predecessor's `--agent`-absent and `--agent all` behaviour) and
 *    `NamedHarness(name)` carrying the supplied token verbatim — the
 *    predecessor diagnoses an unrecognised agent at apply time (exit 0),
 *    so the surface does not reject the token at the boundary. There is
 *    no single-directory variant: a target that can name one directory
 *    permits the narrowed install this spec removes.
 *  - `InstallMode` is a two-variant enum, `DryRun`/`Apply`, and a
 *    REQUIRED parameter of the install entrypoint with no default — a
 *    write-by-default installer is unconstructible.
 *  - `PrerequisiteProbe` carries name AND finding; `PrerequisiteReport`
 *    holds probes, never bare names, so checked-and-present is
 *    distinguishable from not-checked. `missing`/`missingCount` are
 *    derived — the report and its count cannot disagree.
 *  - `InstallSurface` holds the predecessor's declared sets verbatim:
 *    seven prerequisites, three skill agent directories, three known
 *    harnesses with their marker directories and hook destinations.
 *    The `.devin/skills` vs `DriftScan.installRoots` asymmetry is
 *    recorded, not reconciled here (see spec Implementation Anchors).
 *
 * spec: install-tool-surface-parity — Concepts Introduced (new): InstallTarget, InstallMode, PrerequisiteProbe, PrerequisiteReport
 */
final class InstallToolSurfaceParityTypeContract extends ProbatioSuite:

  // ── InstallTarget — which harnesses the hook installer wires ──────
  val allPresentTargetSig: InstallTarget =
    InstallTarget.AllPresentHarnesses

  val namedHarnessSig: String => InstallTarget.NamedHarness =
    InstallTarget.NamedHarness.apply

  val namedHarnessProbeSig: InstallTarget => Option[String] =
    (t: InstallTarget) => t.namedHarness

  // ── InstallMode — report or write, never a default ────────────────
  val dryRunSig: InstallMode =
    InstallMode.DryRun

  val applySig: InstallMode =
    InstallMode.Apply

  val writesSig: InstallMode => Boolean =
    (m: InstallMode) => m.writes

  // ── PrerequisiteProbe / PrerequisiteReport — probes, not names ────
  val probeSig: (String, Boolean) => PrerequisiteProbe =
    PrerequisiteProbe.apply

  val reportSig: List[PrerequisiteProbe] => PrerequisiteReport =
    PrerequisiteReport.apply

  val missingSig: PrerequisiteReport => List[PrerequisiteProbe] =
    (r: PrerequisiteReport) => r.missing

  val reportAllPresentSig: PrerequisiteReport => Boolean =
    (r: PrerequisiteReport) => r.allPresent

  val missingCountSig: PrerequisiteReport => Int =
    (r: PrerequisiteReport) => r.missingCount

  // ── InstallSurface — the predecessor's declared sets ──────────────
  val declaredPrerequisitesSig: List[String] =
    InstallSurface.declaredPrerequisites

  val skillAgentDirsSig: List[String] =
    InstallSurface.skillAgentDirs

  val knownHarnessesSig: List[String] =
    InstallSurface.knownHarnesses

  val harnessMarkerDirsSig: List[(String, String)] =
    InstallSurface.harnessMarkerDirs

  val hookDestinationsSig: List[(String, String)] =
    InstallSurface.hookDestinations

  // ── The pinned surface evaluates ───────────────────────────────────
  test("InstallTarget is closed over two variants; NamedHarness keeps the supplied token"):
    val named: InstallTarget = InstallTarget.NamedHarness("pi")
    assertEquals(named.namedHarness, Some("pi"))
    assertEquals(InstallTarget.AllPresentHarnesses.namedHarness, None)
    // Two variants, exhaustiveness-escalated: a third would fail this match.
    val kinds: List[String] = List(named, InstallTarget.AllPresentHarnesses).map {
      case InstallTarget.NamedHarness(_)     => "named"
      case InstallTarget.AllPresentHarnesses => "all"
    }
    assertEquals(kinds, List("named", "all"))

  test("InstallMode.writes is true only for Apply"):
    assertEquals(InstallMode.Apply.writes, true)
    assertEquals(InstallMode.DryRun.writes, false)
    assertEquals(InstallMode.values.length, 2)

  test("PrerequisiteReport.missing holds the absent probes; the count cannot disagree"):
    val report: PrerequisiteReport = PrerequisiteReport(
      List(
        PrerequisiteProbe("bash", present = true),
        PrerequisiteProbe("jq", present = false),
        PrerequisiteProbe("bats", present = false)
      )
    )
    assertEquals(report.missing.map((p: PrerequisiteProbe) => p.name), List("jq", "bats"))
    assertEquals(report.missingCount, 2)
    assertEquals(report.allPresent, false)
    assertEquals(
      PrerequisiteReport(List(PrerequisiteProbe("bash", present = true))).allPresent,
      true
    )

  test("the declared sets match the predecessor verbatim"):
    assertEquals(
      InstallSurface.declaredPrerequisites,
      List("bash", "git", "jq", "python3", "shellcheck", "bats", "shfmt")
    )
    assertEquals(
      InstallSurface.skillAgentDirs,
      List(".claude/skills", ".pi/skills", ".devin/skills")
    )
    assertEquals(InstallSurface.knownHarnesses, List("claude", "pi", "devin"))
    assertEquals(
      InstallSurface.harnessMarkerDirs,
      List("claude" -> ".claude", "pi" -> ".pi", "devin" -> ".devin")
    )
    assertEquals(
      InstallSurface.hookDestinations,
      List(
        "claude" -> ".claude/settings.json",
        "pi"     -> ".pi/extensions/verified-scala3-gate.ts",
        "devin"  -> ".devin/hooks.v1.json"
      )
    )

end InstallToolSurfaceParityTypeContract
