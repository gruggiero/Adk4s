package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.InstallMode
import org.sinemenda.probatio.core.InstallTarget
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.PrerequisiteReport

import java.nio.file.Path

/**
 * Typed contract for the spec-7 installer CLI adapters
 * (install-tool-surface-parity, Step 1).
 *
 * Pins the public shapes the Step-2 oracle exercises against the built
 * artifact — compiled under the real probatio-cli classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `InstallSkillsCmd.probePrerequisites` is pure over an injected
 *    presence check — the adapter supplies `command -v`; the report
 *    names every declared prerequisite it probed (bats-oracle and
 *    scenario tests substitute the finding deterministically).
 *  - `InstallSkillsCmd.installEverywhere` takes the skills source and
 *    the project root explicitly — the schema location is resolved in
 *    the adapter (the binary lives inside the schema tree, as the
 *    predecessor resolves `$SCRIPT_DIR/../skills`), never from a flag
 *    the predecessor would reject.
 *  - `InstallHooksCmd.runInstaller` takes `mode: InstallMode` as a
 *    REQUIRED parameter with no default — `runInstaller(root)` does not
 *    compile; a write-by-default installer is unconstructible.
 *  - Both `run` signatures are unchanged at the CLI boundary
 *    (`Array[String] => Outcome[Int]`); the widened argument surface
 *    lands at Step 3 behind the same signature.
 *
 * spec: install-tool-surface-parity — Requirement: The skill installer probes the declared prerequisite set
 * spec: install-tool-surface-parity — Requirement: The hook installer reports before it writes
 */
final class InstallToolSurfaceParityCliTypeContract extends ProbatioCliSuite:

  // ── InstallSkillsCmd — probe + full install seams ─────────────────
  val skillsRunSig: Array[String] => Outcome[Int] =
    InstallSkillsCmd.run

  val probeSig: (String => Boolean) => PrerequisiteReport =
    InstallSkillsCmd.probePrerequisites

  val installEverywhereSig: (Path, Path) => Outcome[Int] =
    InstallSkillsCmd.installEverywhere

  // ── InstallHooksCmd — the mode-required install seam ──────────────
  val hooksRunSig: Array[String] => Outcome[Int] =
    InstallHooksCmd.run

  val runInstallerSig: (Path, Path, InstallTarget, InstallMode) => Outcome[Int] =
    InstallHooksCmd.runInstaller

  // ── The pinned surface evaluates ───────────────────────────────────
  test("the probe reports every declared prerequisite with its finding"):
    val report: PrerequisiteReport = probeSig((n: String) => n != "jq")
    assertEquals(report.probes.length, 7)
    assertEquals(report.missing.map((p: org.sinemenda.probatio.core.PrerequisiteProbe) => p.name), List("jq"))
    assertEquals(report.missingCount, 1)
    assertEquals(report.allPresent, false)

  test("an all-present probe reports clean"):
    val report: PrerequisiteReport = probeSig((_: String) => true)
    assertEquals(report.allPresent, true)
    assertEquals(report.missingCount, 0)

end InstallToolSurfaceParityCliTypeContract
