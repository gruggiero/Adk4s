package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.cli.ProbatioCliSuite
import org.sinemenda.probatio.cli.Subcommand
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.PortBlocker
import org.sinemenda.probatio.core.RenameDeferral
import org.sinemenda.probatio.core.ToolSurfaceClassification
import org.sinemenda.probatio.core.UnportedTool
import org.sinemenda.probatio.core.UnportedToolRegister

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.jdk.CollectionConverters.ListHasAsScala

/**
 * Test oracle for the migration-protocol spec — R-M4 (exactly one
 * implementation per seam) and the compile-negative for dual-implementation.
 *
 * These tests live in probatio-cli test sources because `MigrationState`
 * is defined in `MigrationTypes.scala` here (cli test sources cannot see
 * core test sources — `ToolId` is duplicated by design).
 *
 * spec: migration-protocol — Requirement: Exactly one implementation is active at each seam
 * spec: migration-protocol — Property: exactly-one-implementation-per-seam
 * spec: migration-protocol — Compile-Negative: MigrationState with dual-implementation seam
 */
final class MigrationProtocolSpec extends ProbatioCliSuite:

  import MigrationTypes.*

  // ── R-M4: Exactly one implementation is active at each seam
  // spec: migration-protocol — Requirement: Exactly one implementation is active at each seam
  // Scenario: A single-tool ported configuration is well-formed
  test("R-M4: single-tool ported config has exactly one implementation per seam"):
    val state: MigrationState = MigrationState(Set(ToolId.ChainState))
    // ChainState is ported; all others are on the predecessor.
    // Each tool is either ported or predecessor — never both.
    for tool <- ToolId.swapOrder do
      val isPorted: Boolean      = state.portedTools.contains(tool)
      val isPredecessor: Boolean = state.predecessorTools.contains(tool)
      assert(
        isPorted ^ isPredecessor,
        s"tool $tool is not exactly one implementation: ported=$isPorted, predecessor=$isPredecessor"
      )
    assert(
      state.portedTools.intersect(state.predecessorTools).isEmpty,
      "ported and predecessor tools overlap — dual implementation"
    )
    assert(
      state.portedTools.union(state.predecessorTools) == ToolId.swapOrder.toSet,
      "some tools are neither ported nor predecessor — missing implementation"
    )

  // ── R-M4: A dual-implementation configuration is rejected
  // spec: migration-protocol — Scenario: A dual-implementation configuration is rejected
  test("R-M4: full ported config has exactly one implementation per seam"):
    val state: MigrationState = MigrationState(ToolId.swapOrder.toSet)
    for tool <- ToolId.swapOrder do
      val isPorted: Boolean      = state.portedTools.contains(tool)
      val isPredecessor: Boolean = state.predecessorTools.contains(tool)
      assert(
        isPorted ^ isPredecessor,
        s"tool $tool is not exactly one implementation: ported=$isPorted, predecessor=$isPredecessor"
      )

  // ── Compile-Negative: MigrationState with dual-implementation seam
  // spec: migration-protocol — Compile-Negative Obligations
  // A MigrationState with a seam having implementationCount > 1 is
  // unconstructible — the type has no `both` field.
  test("compile-negative: MigrationState has no 'both' field — dual-implementation unconstructible"):
    val err: String = compileErrors("MigrationState(portedTools = Set(ToolId.ChainState), both = true)")
    assert(
      err.nonEmpty,
      "MigrationState should not have a 'both' field — a dual-implementation seam is unconstructible at the type level"
    )

  // ── Property: exactly-one-implementation-per-seam (all-valid-subsets)
  // spec: migration-protocol — Property: exactly-one-implementation-per-seam
  // For every seam configuration (all valid subsets of ToolId), exactly one
  // implementation is active per seam — no seam has both the predecessor
  // and the ported tool active.
  property("exactly one implementation per seam (all-valid-subsets)"):
    for portedSet <- genAllValidSubsets.forAll
    yield
      val state: MigrationState = MigrationState(portedSet)
      val noOverlap: Boolean    = state.portedTools.intersect(state.predecessorTools).isEmpty
      val allCovered: Boolean   = state.portedTools.union(state.predecessorTools) == ToolId.swapOrder.toSet
      Result.assert(noOverlap && allCovered)

  // ── Generator: genAllValidSubsets
  // Constructive over all valid subsets of ToolId.swapOrder (each tool
  // is independently ported or predecessor). Edge cases: empty set
  // (all predecessor), full set (all ported), single-tool.
  def genAllValidSubsets: Gen[Set[ToolId]] =
    for
      portedLedger     <- Gen.boolean
      portedChainState <- Gen.boolean
      portedSpecLint   <- Gen.boolean
      portedDangerScan <- Gen.boolean
      portedReconcile  <- Gen.boolean
      portedCheckpoint <- Gen.boolean
      portedGate       <- Gen.boolean
    yield Set(
      if portedLedger then Some(ToolId.Ledger) else None,
      if portedChainState then Some(ToolId.ChainState) else None,
      if portedSpecLint then Some(ToolId.SpecLint) else None,
      if portedDangerScan then Some(ToolId.DangerScan) else None,
      if portedReconcile then Some(ToolId.Reconcile) else None,
      if portedCheckpoint then Some(ToolId.Checkpoint) else None,
      if portedGate then Some(ToolId.Gate) else None
    ).flatten

  // ════════════════════════════════════════════════════════════════════
  // spec 10 of repair-probatio-cutover: schema-rename-completion
  //
  // The directory-rename deferral is a recorded VALUE — these scenarios
  // read it and check what it names.
  // ════════════════════════════════════════════════════════════════════

  /** The repository root, walked up from the test working directory. */
  private def repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    Iterator
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find((p: Path) => Files.isDirectory(p.resolve("openspec/changes")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  /**
   * The on-disk count of recorded changes that pin the schema's current
   * directory name — every `.openspec.yaml` under `openspec/changes`
   * declaring `schema: verified-scala3`. The deferral's recorded count
   * must agree with this, not with prose.
   */
  private def changesPinningCurrentName: Int =
    val changesDir: Path = repoRoot.resolve("openspec/changes")
    val found: List[Path] =
      Files
        .walk(changesDir)
        .filter((p: Path) => p.getFileName.toString == ".openspec.yaml")
        .filter((p: Path) => Files.readString(p).contains("schema: verified-scala3"))
        .toList
        .asScala
        .toList
    found.length

  // spec: schema-rename-completion — Scenario: Happy path — the deferral names the coupling and the blocked items
  test("schema-rename: the recorded deferral names the directory rename, the coupling, and the pinning count"):
    val recorded: List[RenameDeferral] = RenameDeferral.recorded
    assert(recorded.nonEmpty, "the deferral record must not be empty")
    recorded.foreach { (d: RenameDeferral) =>
      assertEquals(
        RenameDeferral.missing(d),
        List.empty,
        s"a recorded deferral must be complete — missing: ${RenameDeferral.missing(d)}"
      )
    }
    val directoryRename: List[RenameDeferral] = recorded.filter { (d: RenameDeferral) =>
      d.item.toLowerCase.contains("director") &&
      d.item.contains("verified-scala3")
    }
    assertEquals(
      directoryRename.length,
      1,
      s"exactly one deferral must name the schema directory rename, got: ${recorded.map(_.item)}"
    )
    directoryRename.headOption match
      case Some(d) =>
        assert(
          d.blockedBy.resolutionMechanism.toLowerCase.contains("directory"),
          s"the coupling must name directory-name resolution: ${d.blockedBy.resolutionMechanism}"
        )
        assert(
          d.blockedBy.configurationPin.contains("openspec/config.yaml") ||
            d.blockedBy.configurationPin.contains("config.yaml"),
          s"the coupling must name the pinning configuration: ${d.blockedBy.configurationPin}"
        )
        assertEquals(
          d.blockedBy.recordedChangesPinning,
          changesPinningCurrentName,
          "the recorded count must equal the on-disk count of changes pinning the current name"
        )
      case None => fail("unreachable — length asserted above")

  // spec: schema-rename-completion — Scenario: Adversarial — a deferral without a recorded reason is not accepted
  test("schema-rename: a deferral entry carrying no reason is reported incomplete"):
    val entry: RenameDeferral = RenameDeferral(
      item = "schema directory rename",
      reason = "",
      blockedBy = RenameDeferral.Coupling(
        resolutionMechanism = "the workflow tool resolves a schema by its directory name",
        configurationPin = "openspec/config.yaml pins schema: verified-scala3",
        recordedChangesPinning = 19
      )
    )
    val missing: List[RenameDeferral.Missing] = RenameDeferral.missing(entry)
    assert(
      missing.contains(RenameDeferral.Missing.Reason),
      s"the check must report the missing reason, got: $missing"
    )

  // ════════════════════════════════════════════════════════════════════
  // spec 11 of repair-probatio-cutover: unported-tool-register
  //
  // Oracle for the total-and-exclusive classification of the workflow's
  // tool surface. The real-tree tests read the actual tool directories
  // and the committed register document; the synthetic tests drive
  // classifyAll / checkSurface directly. The spec-named compile-negative
  // obligations live in SubcommandTypeContract per the Proof Obligations
  // table.
  //
  // spec: unported-tool-register — Step 2: test oracle
  // ════════════════════════════════════════════════════════════════════

  /** The committed register document, repo-root-relative. A `def`, not a `val` — a field here would be uninitialized when the earlier tests run under -Wsafe-init. */
  private def registerDocumentPath: String =
    "openspec/schemas/verified-scala3/unported-tools.md"

  /**
   * The ported surface: each ported tool's predecessor path to its
   * subcommand CLI name. The mapping is not nominal —
   * `openspec-graph.py` is `graph`, and `metals-start.sh` is `metals`
   * (this change ports its remaining `stop` operation, so the script is
   * fully superseded and carries no register entry).
   *
   * spec: unported-tool-register — Requirement: Every tool in the tree is either on the ported surface or in the register
   */
  private def portedSurface: Map[String, String] = Map(
    "openspec/schemas/verified-scala3/hooks/gate.sh"             -> "gate",
    "openspec/schemas/verified-scala3/hooks/install-hooks.sh"    -> "install-hooks",
    "openspec/schemas/verified-scala3/scanner/chain-state.sh"    -> "chain-state",
    "openspec/schemas/verified-scala3/scanner/checkpoint.sh"     -> "checkpoint",
    "openspec/schemas/verified-scala3/scanner/danger-scan.sh"    -> "danger-scan",
    "openspec/schemas/verified-scala3/scanner/install-skills.sh" -> "install-skills",
    "openspec/schemas/verified-scala3/scanner/ledger.sh"         -> "ledger",
    "openspec/schemas/verified-scala3/scanner/reconcile.sh"      -> "reconcile",
    "openspec/schemas/verified-scala3/scanner/spec-lint.sh"      -> "spec-lint",
    "openspec/schemas/verified-scala3/scanner/openspec-graph.py" -> "graph",
    "openspec/schemas/verified-scala3/scanner/metals-start.sh"   -> "metals"
  )

  /**
   * Adapter: read one tool directory into a `DirListing` — executable
   * regular-file entry names only. Executability is an adapter fact: the
   * pure core cannot see file modes. A directory that cannot be read is
   * `Unreadable`.
   */
  private def readToolDirectory(rel: String): UnportedToolRegister.DirListing =
    val dir: Path = repoRoot.resolve(rel)
    if !Files.isDirectory(dir) then UnportedToolRegister.DirListing.Unreadable(rel)
    else
      val stream = Files.list(dir)
      try
        val entries: List[String] = stream
          .iterator()
          .asScala
          .toList
          .filter((p: Path) => Files.isRegularFile(p) && Files.isExecutable(p))
          .map((p: Path) => p.getFileName.toString)
        UnportedToolRegister.DirListing.Read(rel, entries)
      finally stream.close() // scalafix:ok DisableSyntax.NoKeywordFinally

  /** Adapter: every tool directory listing the check consumes. */
  private def readToolListings: List[UnportedToolRegister.DirListing] =
    UnportedToolRegister.toolDirectories.map(readToolDirectory)

  /**
   * Adapter: the repo-relative paths of every file present in the tree —
   * `git ls-files -co --exclude-standard` (tracked + untracked, ignoring
   * build output). A citation to a document absent from the tree does
   * not resolve; a committed-but-not-yet-created doc does not either —
   * the set is what the check can see, not what git remembers alone.
   */
  private def presentDocuments: Set[String] =
    // spec: hermetic-test-processes — via the shared helper; the child
    // sees the fixed base only.
    val r: org.sinemenda.probatio.migration.HermeticResult =
      org.sinemenda.probatio.migration.HermeticEnv.capture(
        List("git", "-C", repoRoot.toString, "ls-files", "-co", "--exclude-standard"),
        org.sinemenda.probatio.migration.HermeticEnv.empty
      )
    assertEquals(r.exitCode, 0, "git ls-files must succeed — the citation domain is the real tree")
    r.out.linesIterator.filter(_.nonEmpty).toSet

  /** The committed register document, parsed. A missing or unparseable document is a failure, not a skip. */
  private def committedRegister: List[UnportedTool] =
    val docPath: Path = repoRoot.resolve(registerDocumentPath)
    assert(
      Files.isRegularFile(docPath),
      s"the committed register document must exist: $registerDocumentPath"
    )
    UnportedToolRegister.parseRegister(Files.readString(docPath)) match
      case Right(entries) => entries
      case Left(err)      => fail(s"the committed register must parse: $err")

  /** The tool paths a set of listings contributes — directory-prefixed, revert targets excluded. */
  private def toolPathsOf(listings: List[UnportedToolRegister.DirListing]): List[String] =
    listings.flatMap {
      case UnportedToolRegister.DirListing.Read(dir, entries) =>
        entries
          .filterNot(UnportedToolRegister.isRevertTarget)
          .map((e: String) => s"$dir/$e")
      case UnportedToolRegister.DirListing.Unreadable(_) =>
        List.empty
    }

  // spec: unported-tool-register — Requirement: Every tool in the tree is either on the ported surface or in the register
  // The ported half of the classification IS `Subcommand`: the adapter's
  // surface map must cover every subcommand and nothing else.
  test("unported-register: the ported surface covers every Subcommand exactly"):
    assertEquals(
      portedSurface.values.toSet,
      Subcommand.values.map(Subcommand.cliName).toSet,
      "the ported surface must be exactly the subcommand CLI names"
    )
    portedSurface.keys.foreach { (rel: String) =>
      assert(
        Files.isExecutable(repoRoot.resolve(rel)),
        s"ported predecessor path must exist and be executable: $rel"
      )
    }
    // metals-start.sh is `metals` — start AND stop are on the ported
    // surface (this change ports stop), so the script leaves the
    // unported set entirely.
    assertEquals(
      portedSurface.get("openspec/schemas/verified-scala3/scanner/metals-start.sh"),
      Some("metals"),
      "metals-start.sh is fully superseded by the metals subcommand"
    )

  // spec: unported-tool-register — Requirement: A tool that becomes ported leaves the register
  // Step-1 gate decision: `metals-start.sh` leaves the unported set by
  // porting its remaining `stop` operation into the `metals` subcommand.
  // The ported classification of the script is only honest if `metals
  // stop` exists — this test pins it. Per the predecessor script, stop
  // with no recorded instance is a clean no-op (exit 0), never an
  // unknown subaction.
  test("unported-register: 'metals stop' is a recognized subaction — stop ported from metals-start.sh"):
    org.sinemenda.probatio.cli.LiveFactFixtures.withTempDir("metals-stop") { (root: Path) =>
      org.sinemenda.probatio.cli.MetalsCmd.run(Array("stop", root.toString)) match
        case Outcome.Ran(0) => ()
        case other =>
          fail(s"metals stop with no recorded instance must be a clean no-op (Ran(0)), got: $other")
    }

  // spec: unported-tool-register — Requirement: A tool that becomes ported leaves the register
  // The ported stop preserves the predecessor's live-instance branch:
  // `kill <recorded pid>`, then `mcp.pid` and `mcp.url` are removed.
  test("unported-register: 'metals stop' kills the recorded instance and removes the discovery files"):
    org.sinemenda.probatio.cli.LiveFactFixtures.withTempDir("metals-stop-live") { (root: Path) =>
      val meta: Path    = root.resolve(".metals")
      val pidFile: Path = meta.resolve("mcp.pid")
      val urlFile: Path = meta.resolve("mcp.url")
      Files.createDirectories(meta)
      // spec: hermetic-test-processes — the live process is needed for its
      // pid; construction still goes through the shared helper.
      val proc: java.lang.Process =
        org.sinemenda.probatio.migration.HermeticEnv
          .processBuilder(List("sleep", "60"), org.sinemenda.probatio.migration.HermeticEnv.empty)
          .start()
      try
        Files.writeString(pidFile, proc.pid().toString)
        Files.writeString(urlFile, "http://localhost:8399/mcp")
        org.sinemenda.probatio.cli.MetalsCmd.run(Array("stop", root.toString)) match
          case Outcome.Ran(0) => ()
          case other =>
            fail(s"metals stop on a recorded live instance must succeed, got: $other")
        assert(
          proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS),
          "the recorded process must be terminated"
        )
        assert(!Files.exists(pidFile), "mcp.pid must be removed")
        assert(!Files.exists(urlFile), "mcp.url must be removed")
      finally if proc.isAlive then proc.destroyForcibly() // scalafix:ok DisableSyntax.NoKeywordFinally
    }

  // spec: unported-tool-register — Requirement: A tool that becomes ported leaves the register
  // Ring-8 remediation: the predecessor resolves ROOT via
  // `cd "$ROOT" && pwd` under `set -e` — a nonexistent root exits
  // non-zero, never a silent no-op.
  test("unported-register: 'metals stop' on a nonexistent root is a finding"):
    org.sinemenda.probatio.cli.MetalsCmd.run(
      Array("stop", "/nonexistent-probatio-test-root-9f8e7d6c")
    ) match
      case Outcome.Finding(_) => ()
      case other =>
        fail(s"metals stop on a nonexistent root must fail like the predecessor's cd, got: $other")

  // spec: unported-tool-register — Scenario: Happy path — every present tool classifies
  // spec: unported-tool-register — Proof Obligation: The register is complete for the current tree
  test("unported-register: every executable in the real tool directories classifies"):
    val listings: List[UnportedToolRegister.DirListing] = readToolListings
    val outcome: Outcome[UnportedToolRegister.Report] =
      UnportedToolRegister.checkSurface(listings, portedSurface, committedRegister, presentDocuments)
    outcome match
      case Outcome.Ran(report) =>
        val expectedTools: Set[String] = toolPathsOf(listings).toSet
        assert(report.isClean, s"the real tool surface must classify clean: $report")
        assertEquals(
          report.classified.map(_._1).toSet,
          expectedTools,
          "every executable tool in the directories must be classified"
        )
      case Outcome.Finding(detail) =>
        fail(s"the real tool surface must classify clean, got Finding: $detail")
      case Outcome.Undetermined(reason) =>
        fail(s"the real tool directories must be readable, got Undetermined: $reason")
    // The fixture must actually contain revert targets, and none may be
    // classified as a tool.
    val revertTargets: List[String] = readToolListings.flatMap {
      case UnportedToolRegister.DirListing.Read(dir, entries) =>
        entries.filter(UnportedToolRegister.isRevertTarget).map((e: String) => s"$dir/$e")
      case UnportedToolRegister.DirListing.Unreadable(_) => List.empty
    }
    assert(revertTargets.nonEmpty, "the fixture must contain *.predecessor.bak revert targets")

  // spec: unported-tool-register — Scenario: Adversarial — a tool in neither is reported
  test("unported-register: a tool in neither the ported surface nor the register is reported"):
    val report: UnportedToolRegister.Report = UnportedToolRegister.classifyAll(
      List("tools/phantom.sh"),
      Map.empty,
      List.empty,
      Set.empty
    )
    assertEquals(report.unclassified, List("tools/phantom.sh"))
    assert(report.doublyClassified.isEmpty)
    assert(!report.isClean)
    UnportedToolRegister.checkSurface(
      List(UnportedToolRegister.DirListing.Read("tools", List("phantom.sh"))),
      Map.empty,
      List.empty,
      Set.empty
    ) match
      case Outcome.Finding(detail) =>
        assert(
          detail.contains("phantom.sh"),
          s"the finding must name the unclassified tool: $detail"
        )
      case other =>
        fail(s"an unclassified tool must terminate with the finding status, got: $other")

  // spec: unported-tool-register — Scenario: Error path — an unreadable tool directory is could-not-determine
  test("unported-register: an unreadable tool directory is could-not-determine naming it"):
    val listings: List[UnportedToolRegister.DirListing] = List(
      UnportedToolRegister.DirListing.Unreadable("openspec/schemas/verified-scala3/vault"),
      UnportedToolRegister.DirListing.Read("tools", List("a.sh"))
    )
    UnportedToolRegister.checkSurface(
      listings,
      Map("tools/a.sh" -> "a"),
      List.empty,
      Set.empty
    ) match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains("openspec/schemas/verified-scala3/vault"),
          s"the could-not-determine must name the unreadable directory: $reason"
        )
      case other =>
        fail(
          s"an unreadable directory must be Undetermined — no tool may be judged on an unread directory, got: $other"
        )

  // spec: unported-tool-register — Scenario: Happy path — an entry names its blocker and citations
  test("unported-register: the spike-gated entries name the investigation and their citations"):
    val gated: List[(UnportedTool, String)] = committedRegister.collect {
      case t @ UnportedTool(_, _, PortBlocker.GatedOnSpike(spike), _) => (t, spike)
    }
    assert(
      gated.nonEmpty,
      "the register must record at least one tool gated on an unrun investigation"
    )
    gated.foreach { case (tool: UnportedTool, spike: String) =>
      assert(spike.nonEmpty, s"${tool.name}: the blocker must name the investigation")
      assert(tool.name.nonEmpty, "the entry must name the tool")
      assert(tool.path.nonEmpty, s"${tool.name}: the entry must name the tool's location")
      assert(
        tool.citedBy.nonEmpty,
        s"${tool.name}: the entry must list the workflow instructions citing the tool"
      )
    }

  // spec: unported-tool-register — Scenario: Happy path — every citation resolves
  test("unported-register: every citation in the committed register resolves"):
    val unresolved: List[(UnportedTool, String)] =
      UnportedToolRegister.unresolvedCitations(committedRegister, presentDocuments)
    assertEquals(
      unresolved.map { case (t: UnportedTool, c: String) => s"${t.name} -> $c" },
      List.empty,
      "every committed citation must resolve to a document present in the tree"
    )

  // spec: unported-tool-register — Scenario: Adversarial — a citation naming an absent document is reported
  test("unported-register: a citation naming an absent document is reported"):
    val entry: UnportedTool = UnportedTool(
      "phantom",
      "tools/phantom.sh",
      PortBlocker.NotOnEnforcementPath,
      List("docs/real.md", "docs/ghost.md")
    )
    val unresolved: List[(UnportedTool, String)] =
      UnportedToolRegister.unresolvedCitations(List(entry), Set("docs/real.md"))
    assertEquals(
      unresolved,
      List(entry -> "docs/ghost.md"),
      "the check must pair the entry with the unresolvable citation"
    )
    UnportedToolRegister.checkSurface(
      List(UnportedToolRegister.DirListing.Read("tools", List("phantom.sh"))),
      Map.empty,
      List(entry),
      Set("docs/real.md")
    ) match
      case Outcome.Finding(detail) =>
        assert(
          detail.contains("phantom") && detail.contains("docs/ghost.md"),
          s"the finding must name the entry and the unresolvable citation: $detail"
        )
      case other =>
        fail(s"an unresolvable citation must terminate with the finding status, got: $other")

  // spec: unported-tool-register — Scenario: Happy path — the newly ported tool has no register entry
  test("unported-register: the ported traceability tool has no register entry"):
    val entries: List[UnportedTool] = committedRegister
    assert(
      entries.forall((t: UnportedTool) => !t.path.endsWith("openspec-graph.py")),
      s"openspec-graph.py is ported — it must not appear in the register: ${entries.map(_.path)}"
    )
    val report: UnportedToolRegister.Report = UnportedToolRegister.classifyAll(
      List("openspec/schemas/verified-scala3/scanner/openspec-graph.py"),
      portedSurface,
      entries,
      presentDocuments
    )
    assertEquals(
      report.classified,
      List(
        "openspec/schemas/verified-scala3/scanner/openspec-graph.py" ->
          ToolSurfaceClassification.Ported("graph")
      )
    )

  // spec: unported-tool-register — Scenario: Adversarial — a tool both ported and registered is reported
  test("unported-register: a tool both ported and registered is reported as doubly classified"):
    val entry: UnportedTool =
      UnportedTool("dup", "tools/dup.sh", PortBlocker.NotOnEnforcementPath, List.empty)
    val report: UnportedToolRegister.Report = UnportedToolRegister.classifyAll(
      List("tools/dup.sh"),
      Map("tools/dup.sh" -> "dup"),
      List(entry),
      Set.empty
    )
    assertEquals(report.doublyClassified, List("tools/dup.sh"))
    assert(report.unclassified.isEmpty)
    assert(!report.isClean)
    UnportedToolRegister.checkSurface(
      List(UnportedToolRegister.DirListing.Read("tools", List("dup.sh"))),
      Map("tools/dup.sh" -> "dup"),
      List(entry),
      Set.empty
    ) match
      case Outcome.Finding(detail) =>
        assert(
          detail.contains("dup.sh"),
          s"the finding must name the doubly classified tool: $detail"
        )
      case other =>
        fail(s"a doubly classified tool must terminate with the finding status, got: $other")

  // ── Property: classification-is-total-and-exclusive ─────────────────
  // spec: unported-tool-register — Property: classification-is-total-and-exclusive
  //
  // genToolSet — constructive over sets of 0–12 tools, each independently
  // assigned a state drawn from {on the ported surface only, in the
  // register only, in both, in neither}. Edge cases by construction:
  // the empty set, all ported, all registered, one in both, one in
  // neither.

  /** One tool's membership plan — the two independent classification inputs. */
  final private case class ToolPlan(name: String, onSurface: Boolean, registered: Boolean):
    def path: String = s"tools/$name"

  private def genToolSet: Gen[List[ToolPlan]] =
    val perTool: Gen[List[ToolPlan]] =
      (for
        onSurface  <- Gen.boolean
        registered <- Gen.boolean
      yield (onSurface, registered))
        .list(Range.linear(0, 12))
        .map(_.zipWithIndex.map { case ((s: Boolean, r: Boolean), i: Int) =>
          ToolPlan(s"tool-$i.sh", s, r)
        })
    Gen.frequency1(
      60 -> perTool,
      10 -> Gen
        .int(Range.linear(1, 12))
        .map(n => List.tabulate(n)(i => ToolPlan(s"tool-$i.sh", onSurface = true, registered = false))),
      10 -> Gen
        .int(Range.linear(1, 12))
        .map(n => List.tabulate(n)(i => ToolPlan(s"tool-$i.sh", onSurface = false, registered = true))),
      10 -> Gen.constant(List.empty[ToolPlan]),
      10 -> Gen.constant(
        List(
          ToolPlan("both.sh", onSurface = true, registered = true),
          ToolPlan("neither.sh", onSurface = false, registered = false)
        )
      )
    )

  property("classification is total and exclusive"):
    for tools <- genToolSet.forAll
        .cover(25, "has-neither", (ts: List[ToolPlan]) => ts.exists(t => !t.onSurface && !t.registered))
        .cover(20, "has-both", (ts: List[ToolPlan]) => ts.exists(t => t.onSurface && t.registered))
        .cover(5, "empty", (ts: List[ToolPlan]) => ts.isEmpty)
    yield
      val surface: Map[String, String] =
        tools
          .filter(_.onSurface)
          .map((t: ToolPlan) => t.path -> t.name.stripSuffix(".sh"))
          .toMap
      val register: List[UnportedTool] =
        tools
          .filter(_.registered)
          .map((t: ToolPlan) => UnportedTool(t.name, t.path, PortBlocker.NotOnEnforcementPath, List("docs/cite.md")))
      val report: UnportedToolRegister.Report =
        UnportedToolRegister.classifyAll(
          tools.map(_.path),
          surface,
          register,
          Set("docs/cite.md")
        )
      val expectedNeither: Set[String] =
        tools.filter(t => !t.onSurface && !t.registered).map(_.path).toSet
      val expectedBoth: Set[String] =
        tools.filter(t => t.onSurface && t.registered).map(_.path).toSet
      val allPaths: Set[String] = tools.map(_.path).toSet
      Result.all(
        List(
          Result
            .assert(report.unclassified.toSet == expectedNeither)
            .log(s"unclassified=${report.unclassified} expected=$expectedNeither"),
          Result
            .assert(report.doublyClassified.toSet == expectedBoth)
            .log(s"doublyClassified=${report.doublyClassified} expected=$expectedBoth"),
          Result
            .assert(
              (report.classified.map(_._1) ++ report.unclassified).toSet == allPaths &&
                report.classified.map(_._1).toSet.intersect(report.unclassified.toSet).isEmpty
            )
            .log("every tool classified exactly once — never both, never neither"),
          Result
            .assert(report.unresolvedCitations.isEmpty)
            .log(s"all generated citations are present: ${report.unresolvedCitations}")
        )
      )

  // ── Property: every-entry-carries-a-blocker ─────────────────────────
  // spec: unported-tool-register — Property: every-entry-carries-a-blocker
  //
  // genRegister — constructive over entry lists of size 0–8, each entry's
  // blocker drawn from the closed blocker enumeration. The type makes the
  // negative case unconstructible; the property confirms the guarantee
  // holds through serialisation (render ∘ parse round-trips).

  private def genBlocker: Gen[PortBlocker] =
    Gen.frequency1(
      1 -> Gen.string(Gen.alphaNum, Range.linear(1, 20)).map(PortBlocker.GatedOnSpike(_)),
      1 -> Gen.constant(PortBlocker.NotOnEnforcementPath),
      1 -> Gen.string(Gen.alphaNum, Range.linear(1, 12)).map(PortBlocker.SupersededByPortedTool(_))
    )

  private def genEntry: Gen[UnportedTool] =
    for
      name    <- Gen.string(Gen.alphaNum, Range.linear(1, 12))
      blocker <- genBlocker
      citedBy <- Gen
        .string(Gen.alphaNum, Range.linear(1, 16))
        .map((s: String) => s"docs/$s.md")
        .list(Range.linear(0, 4))
    yield UnportedTool(name, s"tools/$name.sh", blocker, citedBy)

  private def genRegister: Gen[List[UnportedTool]] =
    genEntry.list(Range.linear(0, 8))

  property("every entry carries a blocker from the closed set"):
    for register <- genRegister.forAll
    yield
      val rendered: String = UnportedToolRegister.renderRegister(register)
      UnportedToolRegister.parseRegister(rendered) match
        case Right(back) =>
          Result.all(
            List(
              Result
                .assert(back == register)
                .log(s"render/parse round-trip drift:\n  in=$register\n  out=$back"),
              Result
                .assert(back.forall(e => isClosedBlocker(e.blocker)))
                .log("every parsed blocker is a member of the closed set")
            )
          )
        case Left(err) =>
          Result.failure.log(s"a rendered register must re-parse: $err\n$rendered")

  /** Every blocker is one of the three closed variants — by construction. */
  private def isClosedBlocker(b: PortBlocker): Boolean =
    b match
      case PortBlocker.GatedOnSpike(spike)         => spike.nonEmpty
      case PortBlocker.NotOnEnforcementPath        => true
      case PortBlocker.SupersededByPortedTool(sub) => sub.nonEmpty

  // ── Property: citations-resolve-for-the-real-register ───────────────
  // spec: unported-tool-register — Property: citations-resolve-for-the-real-register
  //
  // Enumerated, not sampled: the domain is the committed register's
  // entries, discovered at test time — a newly added entry is covered
  // automatically.
  property("every committed citation resolves (enumerated)"):
    for _ <- Gen.constant(()).forAll
    yield
      val unresolved: List[(UnportedTool, String)] =
        UnportedToolRegister.unresolvedCitations(committedRegister, presentDocuments)
      Result
        .assert(unresolved.isEmpty)
        .log(s"unresolved committed citations: $unresolved")
