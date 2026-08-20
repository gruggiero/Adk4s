package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Tests for the BannerEngine pure function.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: The drift, context, and banner engine is a pure function
 * spec: port-scanner-to-probatio/probatio-core — Requirement: The banner is assembled from live reads, not remembered state
 */
final class BannerEngineSpec extends ProbatioSuite:

  private def emptyInputs(schemaVersion: Int): BannerInputs =
    BannerInputs(
      schemaVersion = schemaVersion,
      skillInstallScan = List.empty,
      registryPresent = false,
      registryConceptCount = 0,
      inventoryPresent = false,
      inventoryTypeCount = 0,
      profilePresent = false,
      detectedTestKit = None,
      activeChanges = List.empty
    )

  // ── Scenario: identical inputs produce byte-identical output
  // spec: port-scanner-to-probatio/probatio-core — Scenario: identical inputs produce byte-identical output
  test("identical inputs produce byte-identical output"):
    val inputs: BannerInputs = emptyInputs(13)
    val output1: BannerOutput = BannerEngine.render(inputs)
    val output2: BannerOutput = BannerEngine.render(inputs)
    assertEquals(output1, output2)

  // ── Scenario: different inputs produce different output
  // spec: port-scanner-to-probatio/probatio-core — Scenario: different inputs produce different output
  test("different inputs produce different output"):
    val output1: BannerOutput = BannerEngine.render(emptyInputs(13))
    val output2: BannerOutput = BannerEngine.render(emptyInputs(14))
    assert(output1 != output2, "Different schema versions should produce different output")

  // ── Scenario: the invariant block is verbatim-match text
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the invariant block is verbatim-match text
  test("the invariant block is verbatim-match text"):
    val inputs: BannerInputs = emptyInputs(13)
    val output: BannerOutput = BannerEngine.render(inputs)
    val invariant: String = BannerEngine.invariantText(13)
    assert(output.payload.contains(invariant), s"banner payload must contain the verbatim invariant block, got: ${output.payload}")

  // ── Scenario: the session-context block reflects live chain state
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the session-context block reflects live chain state
  test("the session-context block reflects live chain state"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13,
      skillInstallScan = List.empty,
      registryPresent = true,
      registryConceptCount = 35,
      inventoryPresent = true,
      inventoryTypeCount = 203,
      profilePresent = true,
      detectedTestKit = Some("TestControl testkit"),
      activeChanges = List(
        ActiveChangeWithChainState(
          name = "port-scanner-to-probatio",
          artifactsPresent = List("proposal.md", "design.md"),
          nextArtifact = Some("implementation-order.md"),
          chainState = Some(Right(ChainStateReport(
            change = "port-scanner-to-probatio",
            baseline = "abc1234",
            total = 12, bound = 10, resolved = 8, discharged = 5,
            unresolved = List(UnresolvedEntry("s", "R3", List(UnresolvedReason.Unbound))),
            unmappedObligations = List.empty
          )))
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("port-scanner-to-probatio"), "banner must name the active change")
    assert(output.payload.contains("unresolved"), "banner must reflect the unresolved count")

  // ── Scenario: an unchanged payload injects nothing
  // spec: port-scanner-to-probatio/probatio-core — Scenario: an unchanged payload injects nothing
  test("an unchanged payload is byte-identical across invocations"):
    val inputs: BannerInputs = emptyInputs(13)
    val output1: BannerOutput = BannerEngine.render(inputs)
    val output2: BannerOutput = BannerEngine.render(inputs)
    assertEquals(output1.payload, output2.payload, "unchanged inputs produce unchanged payload")

  // ── Scenario: a changed payload re-injects in full
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a changed payload re-injects in full
  test("a changed payload re-injects in full"):
    val inputs1: BannerInputs = emptyInputs(13)
    val inputs2: BannerInputs = emptyInputs(14)
    val output1: BannerOutput = BannerEngine.render(inputs1)
    val output2: BannerOutput = BannerEngine.render(inputs2)
    assert(output1.payload != output2.payload, "changed inputs produce a changed payload")

  // ── Scenario: the trailer states facts are read from disk (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the trailer states facts are read from disk (adversarial)
  test("the trailer contains the READ FROM DISK text"):
    val inputs: BannerInputs = emptyInputs(13)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("READ FROM DISK"), s"banner must contain the READ FROM DISK trailer, got: ${output.payload.take(200)}")
    assert(output.payload.contains("facts, not"), "banner must contain 'facts, not'")
    assert(output.payload.contains("recollection"), "banner must contain 'recollection'")

  // ── Property: Banner engine produces byte-identical output for identical inputs
  // spec: port-scanner-to-probatio/probatio-core — Property: Banner engine produces byte-identical output for identical inputs
  property("banner engine produces byte-identical output for identical inputs"):
    for
      schemaVersion <- Gen.int(Range.linear(1, 20)).forAll
      registryPresent <- Gen.boolean.forAll
      inventoryPresent <- Gen.boolean.forAll
      profilePresent <- Gen.boolean.forAll
    yield
      val inputs: BannerInputs = BannerInputs(
        schemaVersion = schemaVersion,
        skillInstallScan = List.empty,
        registryPresent = registryPresent,
        registryConceptCount = if registryPresent then 35 else 0,
        inventoryPresent = inventoryPresent,
        inventoryTypeCount = if inventoryPresent then 203 else 0,
        profilePresent = profilePresent,
        detectedTestKit = if profilePresent then Some("TestControl testkit") else None,
        activeChanges = List.empty
      )
      val output1: BannerOutput = BannerEngine.render(inputs)
      val output2: BannerOutput = BannerEngine.render(inputs)
      Result.assert(output1 == output2)

  // ── Mutation-killing: invariant text contains schema version
  test("invariant text contains the schema version"):
    val invariant: String = BannerEngine.invariantText(13)
    assert(invariant.contains("13"), s"invariant must contain schema version 13, got: $invariant")
    assert(invariant.contains("NEVER LET A CLAIM OUTRUN ITS EVIDENCE"), "invariant must contain the invariant text")
    assert(invariant.contains("CLAIMS, not verdicts"), "invariant must contain CLAIMS text")

  // ── Mutation-killing: invariant text changes with schema version
  test("invariant text changes with schema version"):
    val inv13: String = BannerEngine.invariantText(13)
    val inv14: String = BannerEngine.invariantText(14)
    assert(inv13 != inv14, "different schema versions must produce different invariant text")
    assert(inv14.contains("14"), "invariant for v14 must contain 14")

  // ── Mutation-killing: banner contains registry presence info
  test("banner with registry present contains PRESENT and concept count"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      registryPresent = true, registryConceptCount = 35
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("PRESENT"), "banner must contain PRESENT for registry")
    assert(output.payload.contains("35"), "banner must contain concept count 35")
    assert(output.payload.contains("behavioural registry"), "banner must contain 'behavioural registry'")

  // ── Mutation-killing: banner with registry absent contains ABSENT
  test("banner with registry absent contains ABSENT"):
    val inputs: BannerInputs = emptyInputs(13).copy(registryPresent = false)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("ABSENT"), "banner must contain ABSENT when registry is not present")

  // ── Mutation-killing: banner with inventory present contains type count
  test("banner with inventory present contains PRESENT and type count"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      inventoryPresent = true, inventoryTypeCount = 203
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("PRESENT"), "banner must contain PRESENT for inventory")
    assert(output.payload.contains("203"), "banner must contain type count 203")
    assert(output.payload.contains("type inventory"), "banner must contain 'type inventory'")

  // ── Mutation-killing: banner with profile present contains capability profile
  test("banner with profile present contains PRESENT for capability profile"):
    val inputs: BannerInputs = emptyInputs(13).copy(profilePresent = true)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("capability profile"), "banner must contain 'capability profile'")

  // ── Mutation-killing: banner with test kit contains the kit name
  test("banner with detected test kit contains the kit name"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      profilePresent = true, detectedTestKit = Some("TestControl testkit")
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("TestControl testkit"), "banner must contain the test kit name")

  // ── Mutation-killing: banner with no skill installed contains the no-skill line
  test("banner with no skill installed contains no-skill text"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", None))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("no skill installed"), "banner must contain 'no skill installed' text")

  // ── Mutation-killing: banner with drift warnings contains drift text
  test("banner with drift warnings contains drift message"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", Some(12)))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("drift"), "banner must contain 'drift' text when drift exists")

  // ── Mutation-killing: banner with active change contains change name and counts
  test("banner with active change contains change name and chain-state counts"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13,
      skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(
        ActiveChangeWithChainState(
          name = "port-scanner-to-probatio",
          artifactsPresent = List("proposal.md", "design.md"),
          nextArtifact = Some("implementation-order.md"),
          chainState = Some(Right(ChainStateReport(
            change = "port-scanner-to-probatio",
            baseline = "abc1234",
            total = 12, bound = 10, resolved = 8, discharged = 5,
            unresolved = List(UnresolvedEntry("s", "R3", List(UnresolvedReason.Unbound))),
            unmappedObligations = List.empty
          )))
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("port-scanner-to-probatio"), "banner must contain change name")
    assert(output.payload.contains("proposal.md"), "banner must contain artifact name")
    assert(output.payload.contains("design.md"), "banner must contain artifact name")
    assert(output.payload.contains("implementation-order.md"), "banner must contain next artifact")
    assert(output.payload.contains("total 12"), "banner must contain total count")
    assert(output.payload.contains("bound 10"), "banner must contain bound count")
    assert(output.payload.contains("resolved 8"), "banner must contain resolved count")
    assert(output.payload.contains("discharged 5"), "banner must contain discharged count")
    assert(output.payload.contains("unresolved 1"), "banner must contain unresolved count")

  // ── Mutation-killing: banner with undetermined chain state contains UNDETERMINED
  test("banner with undetermined chain state contains UNDETERMINED"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13,
      skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(
        ActiveChangeWithChainState(
          name = "test-change",
          artifactsPresent = List("proposal.md"),
          nextArtifact = None,
          chainState = Some(Left(ChainStateUndetermined("test-change", "abc1234", "ledger unreadable")))
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("UNDETERMINED"), "banner must contain UNDETERMINED for undetermined chain state")
    assert(output.payload.contains("ledger unreadable"), "banner must contain the undetermined reason")

  // ── Mutation-killing: banner with no next artifact contains 'none'
  test("banner with no next artifact contains 'none'"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13,
      skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(
        ActiveChangeWithChainState(
          name = "test-change",
          artifactsPresent = List("proposal.md"),
          nextArtifact = None,
          chainState = None
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("none"), "banner must contain 'none' when no next artifact")

  // ── Mutation-killing: trailer text is verbatim
  test("trailer text contains the exact READ FROM DISK phrase"):
    assert(BannerEngine.trailerText.contains("READ FROM DISK"), "trailer must contain READ FROM DISK")
    assert(BannerEngine.trailerText.contains("facts, not"), "trailer must contain 'facts, not'")
    assert(BannerEngine.trailerText.contains("recollection"), "trailer must contain 'recollection'")
    assert(BannerEngine.trailerText.contains("finding, not a formality"), "trailer must contain 'finding, not a formality'")

  // ── Mutation-killing: banner contains the schema line with version
  test("banner contains the schema line with version number"):
    val inputs: BannerInputs = emptyInputs(13)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("schema"), "banner must contain 'schema'")
    assert(output.payload.contains("openspec/schemas/verified-scala3"), "banner must contain schema path")
    assert(output.payload.contains("v13"), "banner must contain version v13")

  test("banner with schema v14 contains v14"):
    val inputs: BannerInputs = emptyInputs(14)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("v14"), "banner must contain version v14")
    assert(!output.payload.contains("v13"), "banner must NOT contain v13 when schema is 14")

  // ── Mutation-killing: banner is multi-line (newline separator between lines)
  test("banner payload contains newlines as line separators"):
    val inputs: BannerInputs = emptyInputs(13)
    val output: BannerOutput = BannerEngine.render(inputs)
    // The mkString("\n") joins lines with newlines. If mutated to mkString(""),
    // the lines would be concatenated without separators. We check that the
    // number of lines matches the number of newline-separated segments.
    assert(output.lines.length > 1, "banner must have multiple lines")
    assertEquals(output.payload, output.lines.mkString("\n"), "payload must be lines joined by newlines")

  // ── Mutation-killing: no skill installed — context line
  test("banner with no skill installed contains 'no skill installed across any'"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", None))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("no skill installed across any"), "banner must contain 'no skill installed across any'")
    assert(output.payload.contains("searched roots"), "banner must contain 'searched roots'")

  // ── Mutation-killing: skill installed (no drift) — no noSkillInstalled line
  test("banner with skill installed does NOT contain 'no skill installed across any'"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", Some(13)))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(!output.payload.contains("no skill installed across any"), "banner must NOT contain 'no skill installed across any' when skill is installed")

  // ── Mutation-killing: drift warning in context line
  test("banner with drift warnings contains INSTRUCTION DRIFT"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", Some(12)))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("INSTRUCTION DRIFT"), "banner must contain 'INSTRUCTION DRIFT'")

  // ── Mutation-killing: registry present — full line text
  test("banner with registry present contains full PRESENT line"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      registryPresent = true, registryConceptCount = 35
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("behavioural registry  openspec/concepts/             PRESENT (35 concepts)"), "banner must contain full registry PRESENT line")

  // ── Mutation-killing: registry absent — full line text
  test("banner with registry absent contains full ABSENT line"):
    val inputs: BannerInputs = emptyInputs(13).copy(registryPresent = false)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("behavioural registry  openspec/concepts/             ABSENT"), "banner must contain full registry ABSENT line")

  // ── Mutation-killing: inventory present — full line text
  test("banner with inventory present contains full PRESENT line"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      inventoryPresent = true, inventoryTypeCount = 203
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("type inventory        openspec/concept-inventory.md  PRESENT (203 typed rows)"), "banner must contain full inventory PRESENT line")

  // ── Mutation-killing: inventory absent — full line text
  test("banner with inventory absent contains full ABSENT line"):
    val inputs: BannerInputs = emptyInputs(13).copy(inventoryPresent = false)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("type inventory        openspec/concept-inventory.md  ABSENT"), "banner must contain full inventory ABSENT line")

  // ── Mutation-killing: profile present — full line text
  test("banner with profile present contains full PRESENT line"):
    val inputs: BannerInputs = emptyInputs(13).copy(profilePresent = true)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("capability profile    openspec/capability-profile.md PRESENT"), "banner must contain full profile PRESENT line")

  // ── Mutation-killing: profile absent — full line text
  test("banner with profile absent contains full ABSENT line"):
    val inputs: BannerInputs = emptyInputs(13).copy(profilePresent = false)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("capability profile    openspec/capability-profile.md ABSENT"), "banner must contain full profile ABSENT line")

  // ── Mutation-killing: no test kit detected
  test("banner with no test kit contains 'no deterministic test kit detected'"):
    val inputs: BannerInputs = emptyInputs(13).copy(detectedTestKit = None)
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("no deterministic test kit detected"), "banner must contain 'no deterministic test kit detected'")

  // ── Mutation-killing: test kit detected
  test("banner with test kit contains 'deterministic test kit detected:'"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      profilePresent = true, detectedTestKit = Some("TestControl testkit")
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("deterministic test kit detected:"), "banner must contain 'deterministic test kit detected:'")

  // ── Mutation-killing: active change name line
  test("banner with active change contains 'active change' label and name"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13, skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(ActiveChangeWithChainState(
        name = "my-change", artifactsPresent = List("a.md"),
        nextArtifact = None, chainState = None
      ))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("active change"), "banner must contain 'active change' label")
    assert(output.payload.contains("my-change"), "banner must contain change name")

  // ── Mutation-killing: artifacts present with separator
  test("banner with multiple artifacts contains comma separator"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13, skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(ActiveChangeWithChainState(
        name = "c", artifactsPresent = List("a.md", "b.md"),
        nextArtifact = None, chainState = None
      ))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("a.md, b.md"), "banner must contain comma-separated artifacts")

  // ── Mutation-killing: chain state not computed
  test("banner with chainState=None contains '(not computed)'"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13, skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(ActiveChangeWithChainState(
        name = "c", artifactsPresent = List("a.md"),
        nextArtifact = None, chainState = None
      ))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("(not computed)"), "banner must contain '(not computed)'")

  // ── Mutation-killing: chain state header with change name
  test("banner with Right chain state contains 'chain state' label and change name"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13, skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(ActiveChangeWithChainState(
        name = "my-change", artifactsPresent = List("a.md"),
        nextArtifact = None,
        chainState = Some(Right(ChainStateReport(
          change = "my-change", baseline = "abc1234",
          total = 1, bound = 0, resolved = 0, discharged = 0,
          unresolved = List.empty, unmappedObligations = List.empty
        )))
      ))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("chain state"), "banner must contain 'chain state' label")
    assert(output.payload.contains("my-change"), "banner must contain change name in chain state")

  // ── Mutation-killing: unresolved entry with reasons and separator
  test("banner with unresolved entries contains requirement and reasons"):
    val inputs: BannerInputs = BannerInputs(
      schemaVersion = 13, skillInstallScan = List.empty,
      registryPresent = false, registryConceptCount = 0,
      inventoryPresent = false, inventoryTypeCount = 0,
      profilePresent = false, detectedTestKit = None,
      activeChanges = List(ActiveChangeWithChainState(
        name = "c", artifactsPresent = List("a.md"),
        nextArtifact = None,
        chainState = Some(Right(ChainStateReport(
          change = "c", baseline = "abc1234",
          total = 2, bound = 0, resolved = 0, discharged = 0,
          unresolved = List(UnresolvedEntry("s", "R1", List(UnresolvedReason.Unbound, UnresolvedReason.Unresolved))),
          unmappedObligations = List.empty
        )))
      ))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("R1"), "banner must contain requirement R1")
    assert(output.payload.contains("unbound"), "banner must contain reason 'unbound'")
    assert(output.payload.contains("unresolved"), "banner must contain reason 'unresolved'")
    // The comma separator between reasons is in the unresolved entry line:
    // "    R1 (unbound, unresolved)" — check for this specific pattern
    assert(output.payload.contains("unbound, unresolved"), "banner must contain comma-separated reasons in unresolved entry")

  // ── Mutation-killing: drift lines — no skill installed
  test("banner drift section with no skill contains 're-install to enable drift checking'"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", None))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("re-install to enable drift checking"), "banner must contain 're-install to enable drift checking'")

  // ── Mutation-killing: drift lines — warnings present
  test("banner drift section with warnings contains warning message"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", Some(12)))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    // The drift section (buildDriftLines) outputs "  ${w.message}" without the
    // "!! INSTRUCTION DRIFT:" prefix. The context section has that prefix.
    // We need to assert the drift-section-specific line exists.
    val driftSectionLine: Boolean = output.lines.exists { line =>
      line.startsWith("  drift: root") && !line.contains("INSTRUCTION DRIFT")
    }
    assert(driftSectionLine, "banner drift section must contain warning message without INSTRUCTION DRIFT prefix")
    assert(output.payload.contains("expected"), "banner must contain 'expected' in drift warning")

  // ── Mutation-killing: drift lines — no warnings, skill installed
  test("banner drift section with no warnings and skill installed is empty"):
    val inputs: BannerInputs = emptyInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", Some(13)))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(!output.payload.contains("re-install to enable drift checking"), "banner must NOT contain re-install when skill is installed")
    assert(!output.payload.contains("INSTRUCTION DRIFT"), "banner must NOT contain INSTRUCTION DRIFT when no drift")
    // The drift section should not have any drift warning lines
    val hasDriftSectionWarning: Boolean = output.lines.exists { line =>
      line.startsWith("  drift: root") && !line.contains("INSTRUCTION DRIFT")
    }
    assert(!hasDriftSectionWarning, "banner must NOT have drift-section warning lines when no drift")
