package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Tests for the BannerEngine pure function.
 *
 * Migrated to the spec-3 contract: `BannerInputs` is constructible only from
 * a `RepositoryFacts` value, and install-root scans use `InstallRootState`.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: The drift, context, and banner engine is a pure function
 * spec: port-scanner-to-probatio/probatio-core — Requirement: The banner is assembled from live reads, not remembered state
 * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
 */
final class BannerEngineSpec extends ProbatioSuite:

  private def emptyFacts(schemaVersion: Int): RepositoryFacts =
    RepositoryFacts(
      schemaVersion = FactRead.Present(schemaVersion),
      registry = FactRead.Absent,
      inventory = FactRead.Absent,
      profile = FactRead.Absent,
      installRoots = List.empty,
      activeChanges = FactRead.Present(List.empty)
    )

  private def emptyInputs(schemaVersion: Int): BannerInputs =
    BannerInputs.from(emptyFacts(schemaVersion))

  private def changes(cs: ActiveChangeWithChainState*): FactRead[List[ActiveChangeWithChainState]] =
    FactRead.Present(cs.toList)

  /**
   * Build an unresolved entry through the spec-5 smart constructor; every
   * fixture in this spec is contract-valid by construction.
   */
  private def entryOf(spec: String, req: String, reason: UnresolvedReason): UnresolvedEntry =
    UnresolvedEntry.of(spec, req, List(reason)).getOrElse(fail(s"unrepresentable entry: $spec/$req"))

  /**
   * Build a measured report through the spec-5 smart constructor; fixtures
   * must satisfy the report contract's count cross-checks.
   */
  private def reportOf(
    change: String,
    baseline: String,
    total: Int,
    bound: Int,
    resolved: Int,
    discharged: Int,
    unresolved: List[UnresolvedEntry],
    unmappedObligations: List[UnmappedObligation]
  ): ChainStateReport =
    ChainStateReport
      .fromCounts(change, baseline, total, bound, resolved, discharged, unresolved, unmappedObligations)
      .getOrElse(fail("fixture report violates the report contract"))

  // ── Scenario: identical inputs produce byte-identical output
  // spec: port-scanner-to-probatio/probatio-core — Scenario: identical inputs produce byte-identical output
  test("identical inputs produce byte-identical output"):
    val inputs: BannerInputs  = emptyInputs(13)
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
    val invariant: String    = BannerEngine.invariantText(13)
    assert(
      output.payload.contains(invariant),
      s"banner payload must contain the verbatim invariant block, got: ${output.payload}"
    )

  // ── Scenario: the session-context block reflects live chain state
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the session-context block reflects live chain state
  test("the session-context block reflects live chain state"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        registry = FactRead.Present(35),
        inventory = FactRead.Present(203),
        profile = FactRead.Present(Some("TestControl testkit")),
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "port-scanner-to-probatio",
            artifacts = FactRead.Present(
              ArtifactScan(
                List("proposal.md", "design.md"),
                Some(ArtifactRef("implementation-order", "implementation-order.md"))
              )
            ),
            chainState = Right(
              reportOf(
                change = "port-scanner-to-probatio",
                baseline = "abc1234",
                total = 5,
                bound = 4,
                resolved = 3,
                discharged = 2,
                unresolved = List(
                  entryOf("s", "R3", UnresolvedReason.Unbound),
                  entryOf("s", "R4", UnresolvedReason.Unresolved),
                  entryOf("s", "R5", UnresolvedReason.Undischarged)
                ),
                unmappedObligations = List.empty
              )
            )
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("port-scanner-to-probatio"), "banner must name the active change")
    assert(output.payload.contains("unresolved"), "banner must reflect the unresolved count")

  // ── Scenario: an unchanged payload injects nothing
  // spec: port-scanner-to-probatio/probatio-core — Scenario: an unchanged payload injects nothing
  test("an unchanged payload is byte-identical across invocations"):
    val inputs: BannerInputs  = emptyInputs(13)
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
    assert(
      output.payload.contains("READ FROM DISK"),
      s"banner must contain the READ FROM DISK trailer, got: ${output.payload.take(200)}"
    )
    assert(output.payload.contains("facts, not"), "banner must contain 'facts, not'")
    assert(output.payload.contains("recollection"), "banner must contain 'recollection'")

  // ── Property: Banner engine produces byte-identical output for identical inputs
  // spec: port-scanner-to-probatio/probatio-core — Property: Banner engine produces byte-identical output for identical inputs
  property("banner engine produces byte-identical output for identical inputs"):
    for
      schemaVersion    <- Gen.int(Range.linear(1, 20)).forAll
      registryPresent  <- Gen.boolean.forAll
      inventoryPresent <- Gen.boolean.forAll
      profilePresent   <- Gen.boolean.forAll
    yield
      val inputs: BannerInputs = BannerInputs.from(
        emptyFacts(schemaVersion).copy(
          registry = if registryPresent then FactRead.Present(35) else FactRead.Absent,
          inventory = if inventoryPresent then FactRead.Present(203) else FactRead.Absent,
          profile = if profilePresent then FactRead.Present(Some("TestControl testkit")) else FactRead.Absent
        )
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

  // ── spec 10 of repair-probatio-cutover: the emitted banner names the
  // current schema (the old name survives only inside the intentional
  // PRE-RENAME STAMP drift message, never as the banner's identity)
  // spec: schema-rename-completion — Requirement: Installed instruction documents carry the current stamp
  test("the emitted banner headers name probatio, never the pre-rename name"):
    val output: BannerOutput = BannerEngine.render(emptyInputs(14))
    assert(
      output.payload.contains("probatio — invariant (schema v14)"),
      s"invariant header must name probatio, got: ${output.payload}"
    )
    assert(
      output.payload.contains("probatio — session context (schema v14"),
      s"session-context header must name probatio, got: ${output.payload}"
    )
    assert(
      !output.payload.contains("verified-scala3 —"),
      "no banner line may present the pre-rename name as the banner's identity"
    )

  // ── Mutation-killing: banner contains registry presence info
  test("banner with registry present contains PRESENT and concept count"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(registry = FactRead.Present(35))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("PRESENT"), "banner must contain PRESENT for registry")
    assert(output.payload.contains("35"), "banner must contain concept count 35")
    assert(output.payload.contains("behavioural registry"), "banner must contain 'behavioural registry'")

  // ── Mutation-killing: banner with registry absent contains ABSENT
  test("banner with registry absent contains ABSENT"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(registry = FactRead.Absent)
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("ABSENT"), "banner must contain ABSENT when registry is not present")

  // ── Mutation-killing: banner with inventory present contains type count
  test("banner with inventory present contains PRESENT and type count"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(inventory = FactRead.Present(203))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("PRESENT"), "banner must contain PRESENT for inventory")
    assert(output.payload.contains("203"), "banner must contain type count 203")
    assert(output.payload.contains("type inventory"), "banner must contain 'type inventory'")

  // ── Mutation-killing: banner with profile present contains capability profile
  test("banner with profile present contains PRESENT for capability profile"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(profile = FactRead.Present(None))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("capability profile"), "banner must contain 'capability profile'")

  // ── Mutation-killing: banner with test kit contains the kit name
  test("banner with detected test kit contains the kit name"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(profile = FactRead.Present(Some("TestControl testkit")))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("TestControl testkit"), "banner must contain the test kit name")

  // ── Mutation-killing: banner with no skill installed contains the no-skill line
  test("banner with no skill installed contains no-skill text"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Absent))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("no openspec-spec-lint skill installed in the searched roots"),
      "banner must contain the no-skill-installed text"
    )

  // ── Mutation-killing: banner with drift warnings contains drift text
  test("banner with drift warnings contains drift message"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Stamped(12, StampFormat.New)))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("INSTRUCTION DRIFT"),
      "banner must contain 'INSTRUCTION DRIFT' text when drift exists"
    )

  // ── Mutation-killing: banner with active change contains change name and counts
  test("banner with active change contains change name and chain-state counts"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "port-scanner-to-probatio",
            artifacts = FactRead.Present(
              ArtifactScan(
                List("proposal.md", "design.md"),
                Some(ArtifactRef("implementation-order", "implementation-order.md"))
              )
            ),
            chainState = Right(
              reportOf(
                change = "port-scanner-to-probatio",
                baseline = "abc1234",
                total = 5,
                bound = 4,
                resolved = 3,
                discharged = 2,
                unresolved = List(
                  entryOf("s", "R3", UnresolvedReason.Unbound),
                  entryOf("s", "R4", UnresolvedReason.Unresolved),
                  entryOf("s", "R5", UnresolvedReason.Undischarged)
                ),
                unmappedObligations = List.empty
              )
            )
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("port-scanner-to-probatio"), "banner must contain change name")
    assert(output.payload.contains("proposal.md"), "banner must contain artifact name")
    assert(output.payload.contains("design.md"), "banner must contain artifact name")
    assert(output.payload.contains("implementation-order.md"), "banner must contain next artifact")
    assert(output.payload.contains("total 5"), "banner must contain total count")
    assert(output.payload.contains("bound 4"), "banner must contain bound count")
    assert(output.payload.contains("resolved 3"), "banner must contain resolved count")
    assert(output.payload.contains("discharged 2"), "banner must contain discharged count")
    assert(output.payload.contains("unresolved 3"), "banner must contain unresolved count")

  // ── Mutation-killing: banner with undetermined chain state contains undetermined
  test("banner with undetermined chain state contains undetermined"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "test-change",
            artifacts = FactRead.Present(ArtifactScan(List("proposal.md"), None)),
            chainState =
              Left(ChainStateUndetermined("test-change", "abc1234", UndeterminedReason.stated("ledger unreadable")))
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("undetermined"), "banner must contain undetermined for undetermined chain state")
    assert(output.payload.contains("ledger unreadable"), "banner must contain the undetermined reason")

  // ── Mutation-killing: banner with no next artifact contains 'none'
  test("banner with no next artifact contains 'none'"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "test-change",
            artifacts = FactRead.Present(ArtifactScan(List("proposal.md"), None)),
            chainState =
              Left(ChainStateUndetermined("test-change", "abc1234", UndeterminedReason.stated("ledger absent")))
          )
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
    assert(
      BannerEngine.trailerText.contains("finding, not a formality"),
      "trailer must contain 'finding, not a formality'"
    )

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
  test("banner with no skill installed names the searched roots"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Absent))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("no openspec-spec-lint skill installed in the searched roots"),
      "banner must name the searched roots when no skill is installed"
    )

  // ── Mutation-killing: skill installed (no drift) — no noSkillInstalled line
  test("banner with skill installed does NOT contain the no-skill-installed line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New)))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      !output.payload.contains("no openspec-spec-lint skill installed"),
      "banner must NOT contain the no-skill-installed line when skill is installed"
    )

  // ── Mutation-killing: drift warning in context line
  test("banner with drift warnings contains INSTRUCTION DRIFT"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Stamped(12, StampFormat.New)))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("INSTRUCTION DRIFT"), "banner must contain 'INSTRUCTION DRIFT'")

  // ── Mutation-killing: registry present — full line text
  test("banner with registry present contains full PRESENT line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(registry = FactRead.Present(35))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("behavioural registry  openspec/concepts/             PRESENT (35 concepts)"),
      "banner must contain full registry PRESENT line"
    )

  // ── Mutation-killing: registry absent — full line text
  test("banner with registry absent contains full ABSENT line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(registry = FactRead.Absent)
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("behavioural registry  openspec/concepts/             ABSENT"),
      "banner must contain full registry ABSENT line"
    )

  // ── Mutation-killing: inventory present — full line text
  test("banner with inventory present contains full PRESENT line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(inventory = FactRead.Present(203))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("type inventory        openspec/concept-inventory.md  PRESENT (203 typed rows)"),
      "banner must contain full inventory PRESENT line"
    )

  // ── Mutation-killing: inventory absent — full line text
  test("banner with inventory absent contains full ABSENT line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(inventory = FactRead.Absent)
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("type inventory        openspec/concept-inventory.md  ABSENT"),
      "banner must contain full inventory ABSENT line"
    )

  // ── Mutation-killing: profile present — full line text
  test("banner with profile present contains full PRESENT line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(profile = FactRead.Present(None))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("capability profile    openspec/capability-profile.md PRESENT"),
      "banner must contain full profile PRESENT line"
    )

  // ── Mutation-killing: profile absent — full line text
  test("banner with profile absent contains full ABSENT line"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(profile = FactRead.Absent)
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("capability profile    openspec/capability-profile.md ABSENT"),
      "banner must contain full profile ABSENT line"
    )

  // ── Mutation-killing: no test kit detected
  test("banner with no test kit contains 'no deterministic test kit detected'"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(profile = FactRead.Present(None))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("no deterministic test kit detected"),
      "banner must contain 'no deterministic test kit detected'"
    )

  // ── Mutation-killing: test kit detected
  test("banner with test kit contains 'deterministic test kit detected:'"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(profile = FactRead.Present(Some("TestControl testkit")))
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.payload.contains("deterministic test kit detected:"),
      "banner must contain 'deterministic test kit detected:'"
    )

  // ── Mutation-killing: active change name line
  test("banner with active change contains 'active change' label and name"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "my-change",
            artifacts = FactRead.Present(ArtifactScan(List("a.md"), None)),
            chainState =
              Left(ChainStateUndetermined("my-change", "abc1234", UndeterminedReason.stated("ledger absent")))
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("active change"), "banner must contain 'active change' label")
    assert(output.payload.contains("my-change"), "banner must contain change name")

  // ── Mutation-killing: artifacts present with separator
  test("banner with multiple artifacts contains comma separator"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "c",
            artifacts = FactRead.Present(ArtifactScan(List("a.md", "b.md"), None)),
            chainState = Left(ChainStateUndetermined("c", "abc1234", UndeterminedReason.stated("ledger absent")))
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("a.md, b.md"), "banner must contain comma-separated artifacts")

  // ── Mutation-killing: an unlistable changes dir renders UNREADABLE, not empty
  // spec: live-fact-banner — SHALL NOT report an empty active-change list for a
  // repository whose changes could not be listed
  test("banner with Unreadable activeChanges states UNREADABLE and no fabricated list"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = FactRead.Unreadable("openspec/changes could not be listed")
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("UNREADABLE"), "banner must state UNREADABLE")
    assert(
      output.payload.contains("could not be listed"),
      "banner must state the unreadable reason"
    )
    assert(
      !output.payload.contains("active change       "),
      "an unreadable changes dir must not fabricate a per-change line"
    )

  // ── Mutation-killing: chain state header with change name
  test("banner with Right chain state contains 'chain state' label and change name"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "my-change",
            artifacts = FactRead.Present(ArtifactScan(List("a.md"), None)),
            chainState = Right(
              reportOf(
                change = "my-change",
                baseline = "abc1234",
                total = 1,
                bound = 0,
                resolved = 0,
                discharged = 0,
                unresolved = List(entryOf("s", "R1", UnresolvedReason.Unbound)),
                unmappedObligations = List.empty
              )
            )
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("chain state"), "banner must contain 'chain state' label")
    assert(output.payload.contains("my-change"), "banner must contain change name in chain state")

  // ── Mutation-killing: unresolved entry with reasons and separator
  test("banner with unresolved entries contains requirement and reasons"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        activeChanges = changes(
          ActiveChangeWithChainState(
            name = "c",
            artifacts = FactRead.Present(ArtifactScan(List("a.md"), None)),
            chainState = Right(
              reportOf(
                change = "c",
                baseline = "abc1234",
                total = 2,
                bound = 1,
                resolved = 0,
                discharged = 0,
                unresolved = List(
                  entryOf("s", "R1", UnresolvedReason.Unbound),
                  entryOf("s", "R2", UnresolvedReason.Unresolved)
                ),
                unmappedObligations = List.empty
              )
            )
          )
        )
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(output.payload.contains("R1"), "banner must contain requirement R1")
    assert(output.payload.contains("R2"), "banner must contain requirement R2")
    assert(output.payload.contains("unbound"), "banner must contain reason 'unbound'")
    assert(output.payload.contains("unresolved"), "banner must contain reason 'unresolved'")

  // ── Mutation-killing: drift warning names the root and both versions
  test("banner drift warning names root, expected and found versions"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Stamped(12, StampFormat.New)))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      output.lines.exists(line => line.contains("skill at .claude/skills is schema v12, this schema is v13")),
      "banner must contain the drift warning naming the root and both versions"
    )
    assert(output.payload.contains("INSTRUCTION DRIFT"), "banner must contain 'INSTRUCTION DRIFT'")

  // ── Mutation-killing: no warnings when the installed stamp matches
  test("banner with a matching stamp emits no drift warnings"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(
        installRoots = List(InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New)))
      )
    )
    val output: BannerOutput = BannerEngine.render(inputs)
    assert(
      !output.payload.contains("INSTRUCTION DRIFT"),
      "banner must NOT contain INSTRUCTION DRIFT when no drift"
    )
    val hasDriftWarning: Boolean = output.lines.exists(line => line.contains("skill at"))
    assert(!hasDriftWarning, "banner must NOT have drift warning lines when no drift")

  // ── Mutation-killing: an unreadable fact is never rendered as absent
  test("banner with unreadable registry states UNREADABLE, not ABSENT"):
    val inputs: BannerInputs = BannerInputs.from(
      emptyFacts(13).copy(registry = FactRead.Unreadable("permission denied"))
    )
    val output: BannerOutput         = BannerEngine.render(inputs)
    val registryLine: Option[String] = output.lines.find(_.contains("behavioural registry"))
    registryLine match
      case Some(line) =>
        assert(line.contains("UNREADABLE"), s"unreadable registry must state UNREADABLE, got: $line")
        assert(!line.contains("ABSENT"), s"unreadable registry must NOT state ABSENT, got: $line")
      case None => fail("banner must contain a registry line")
