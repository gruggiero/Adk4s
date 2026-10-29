package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Focused unit coverage for the pure classification/register functions —
 * the Ring-5 mutation-coverage suite (probatio-core/stryker cannot see
 * the cli-side oracle, which needs `Subcommand` and the filesystem).
 *
 * The spec-derived oracle with full scenario traceability is
 * `migration.MigrationProtocolSpec` (cli); this suite exercises the same
 * contract surface on synthetic inputs so that register mutants are
 * killable in this module.
 *
 * spec: unported-tool-register — Ring 5 note (stryker4s.conf)
 */
final class UnportedToolRegisterSpec extends ProbatioSuite:

  import UnportedToolRegister.DirListing
  import UnportedToolRegister.Report

  private val registeredTool: UnportedTool =
    UnportedTool("reg", "tools/reg.sh", PortBlocker.NotOnEnforcementPath, List("docs/a.md"))

  // ── isRevertTarget ──────────────────────────────────────────────────
  test("isRevertTarget: *.predecessor.bak is a revert target, anything else is a tool"):
    assert(UnportedToolRegister.isRevertTarget("x.sh.predecessor.bak"))
    assert(UnportedToolRegister.isRevertTarget("dir/x.predecessor.bak"))
    assert(!UnportedToolRegister.isRevertTarget("x.sh"))
    assert(!UnportedToolRegister.isRevertTarget("x.bak"))
    assert(!UnportedToolRegister.isRevertTarget("x.predecessor.bak.sh"))

  // ── toolDirectories ─────────────────────────────────────────────────
  test("toolDirectories names the scanner and hooks directories"):
    assertEquals(
      UnportedToolRegister.toolDirectories,
      List(
        "openspec/schemas/verified-scala3/scanner",
        "openspec/schemas/verified-scala3/hooks"
      )
    )

  // ── classifyAll ─────────────────────────────────────────────────────
  test("classifyAll: ported-only classifies Ported, register-only classifies Registered"):
    val report: Report = UnportedToolRegister.classifyAll(
      List("tools/a.sh", "tools/b.sh"),
      Map("tools/a.sh" -> "a"),
      List(registeredTool.copy(path = "tools/b.sh")),
      Set("docs/a.md")
    )
    assertEquals(
      report.classified.toSet,
      Set(
        "tools/a.sh" -> ToolSurfaceClassification.Ported("a"),
        "tools/b.sh" -> ToolSurfaceClassification.Registered(registeredTool.copy(path = "tools/b.sh"))
      )
    )
    assert(report.unclassified.isEmpty)
    assert(report.doublyClassified.isEmpty)
    assert(report.isClean)

  test("classifyAll: neither-surface membership is unclassified, both is doubly classified"):
    val report: Report = UnportedToolRegister.classifyAll(
      List("tools/none.sh", "tools/both.sh"),
      Map("tools/both.sh" -> "both"),
      List(registeredTool.copy(path = "tools/both.sh")),
      Set("docs/a.md")
    )
    assertEquals(report.unclassified, List("tools/none.sh"))
    assertEquals(report.doublyClassified, List("tools/both.sh"))
    // A doubly-classified tool still carries its ported classification.
    assert(
      report.classified.contains(
        "tools/both.sh" -> ToolSurfaceClassification.Ported("both")
      )
    )
    assert(!report.isClean)

  test("classifyAll: revert targets in the input are not tools"):
    val report: Report = UnportedToolRegister.classifyAll(
      List("tools/a.sh.predecessor.bak"),
      Map.empty,
      List.empty,
      Set.empty
    )
    assert(report.classified.isEmpty)
    assert(report.unclassified.isEmpty)
    assert(report.isClean)

  test("classifyAll: unresolved citations reach the report"):
    val entry: UnportedTool = registeredTool.copy(citedBy = List("docs/gone.md"))
    val report: Report      =
      UnportedToolRegister.classifyAll(List.empty, Map.empty, List(entry), Set("docs/a.md"))
    assertEquals(report.unresolvedCitations, List(entry -> "docs/gone.md"))
    assert(!report.isClean)

  // ── unresolvedCitations ─────────────────────────────────────────────
  test("unresolvedCitations: pairs each entry with exactly its absent citations"):
    val a: UnportedTool = registeredTool.copy(citedBy = List("docs/here.md", "docs/gone.md"))
    val b: UnportedTool = registeredTool.copy(name = "b", path = "tools/b.sh", citedBy = List("docs/also-gone.md"))
    assertEquals(
      UnportedToolRegister.unresolvedCitations(List(a, b), Set("docs/here.md")),
      List(a -> "docs/gone.md", b -> "docs/also-gone.md")
    )
    assertEquals(
      UnportedToolRegister.unresolvedCitations(List(a, b), Set("docs/here.md", "docs/gone.md", "docs/also-gone.md")),
      List.empty
    )

  // ── checkSurface ────────────────────────────────────────────────────
  test("checkSurface: clean classification is Ran carrying the report"):
    UnportedToolRegister.checkSurface(
      List(DirListing.Read("tools", List("a.sh"))),
      Map("tools/a.sh" -> "a"),
      List.empty,
      Set.empty
    ) match
      case Outcome.Ran(report) =>
        assert(report.isClean)
        assertEquals(
          report.classified,
          List("tools/a.sh" -> ToolSurfaceClassification.Ported("a"))
        )
      case other => fail(s"clean surface must be Ran, got: $other")

  test("checkSurface: dirty classification is Finding naming every problem"):
    val entry: UnportedTool =
      UnportedTool("dup", "tools/dup.sh", PortBlocker.NotOnEnforcementPath, List("docs/gone.md"))
    UnportedToolRegister.checkSurface(
      List(DirListing.Read("tools", List("dup.sh", "free.sh"))),
      Map("tools/dup.sh" -> "dup"),
      List(entry),
      Set.empty
    ) match
      case Outcome.Finding(detail) =>
        assert(detail.contains("free.sh"), s"names the unclassified tool: $detail")
        assert(detail.contains("dup.sh"), s"names the doubly classified tool: $detail")
        assert(detail.contains("docs/gone.md"), s"names the unresolved citation: $detail")
      case other => fail(s"dirty surface must be Finding, got: $other")

  test("checkSurface: any unreadable directory is Undetermined naming it"):
    UnportedToolRegister.checkSurface(
      List(
        DirListing.Unreadable("tools/vault"),
        DirListing.Read("tools", List("a.sh"))
      ),
      Map("tools/a.sh" -> "a"),
      List.empty,
      Set.empty
    ) match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("tools/vault"), s"names the unreadable directory: $reason")
      case other => fail(s"unreadable directory must be Undetermined, got: $other")

  // ── parseRegister / renderRegister ──────────────────────────────────
  test("renderRegister ∘ parseRegister round-trips all three blockers"):
    val entries: List[UnportedTool] = List(
      UnportedTool("a", "tools/a.sh", PortBlocker.GatedOnSpike("spike one"), List("docs/a.md")),
      UnportedTool("b", "tools/b.sh", PortBlocker.NotOnEnforcementPath, List.empty),
      UnportedTool("c", "tools/c.sh", PortBlocker.SupersededByPortedTool("graph"), List("docs/x.md", "docs/y.md"))
    )
    assertEquals(
      UnportedToolRegister.parseRegister(UnportedToolRegister.renderRegister(entries)),
      Right(entries)
    )
    assertEquals(
      UnportedToolRegister.parseRegister(UnportedToolRegister.renderRegister(List.empty)),
      Right(List.empty)
    )

  test("parseRegister: a blocker outside the closed set is a Left, never a defaulted entry"):
    val doc: String =
      "| Tool | Path | Blocker | Cited by |\n" +
        "|------|------|---------|----------|\n" +
        "| a | tools/a.sh | because reasons | docs/a.md |\n"
    assert(UnportedToolRegister.parseRegister(doc).isLeft)

  test("parseRegister: prose and non-register tables are ignored; register rows parse"):
    val doc: String =
      "# Unported Tool Register\n\n" +
        "Some prose.\n\n" +
        "| Tool | Path | Blocker | Cited by |\n" +
        "|------|------|---------|----------|\n" +
        "| a | tools/a.sh | NotOnEnforcementPath | docs/a.md |\n"
    assertEquals(
      UnportedToolRegister.parseRegister(doc),
      Right(
        List(
          UnportedTool("a", "tools/a.sh", PortBlocker.NotOnEnforcementPath, List("docs/a.md"))
        )
      )
    )

  // ── Surgical kills (Ring 5 remediation) ─────────────────────────────
  // Each test names the mutant class it kills.

  // isClean `&&`→`||`: with no unclassified tools the mutant
  // short-circuits to clean even when doublyClassified/unresolved are
  // non-empty — checkSurface must still report Finding.
  test("checkSurface: doubly classified + unresolved citation without unclassified is still Finding"):
    val entry: UnportedTool =
      UnportedTool("dup", "tools/dup.sh", PortBlocker.NotOnEnforcementPath, List("docs/gone.md"))
    UnportedToolRegister.checkSurface(
      List(DirListing.Read("tools", List("dup.sh"))),
      Map("tools/dup.sh" -> "dup"),
      List(entry),
      Set.empty
    ) match
      case Outcome.Finding(detail) =>
        assert(detail.contains("dup.sh"))
        assert(detail.contains("docs/gone.md"))
      case other => fail(s"non-empty problem lists must be Finding, got: $other")

  // Row-filter `>=`→`==`: a row with extra trailing cells still parses
  // from its first four columns — it must not be silently dropped.
  test("parseRegister: a row with extra cells parses its first four columns"):
    val doc: String =
      "| Tool | Path | Blocker | Cited by |\n" +
        "|------|------|---------|----------|\n" +
        "| a | tools/a.sh | NotOnEnforcementPath | docs/a.md | extra |\n"
    assertEquals(
      UnportedToolRegister.parseRegister(doc),
      Right(
        List(
          UnportedTool("a", "tools/a.sh", PortBlocker.NotOnEnforcementPath, List("docs/a.md"))
        )
      )
    )

  // isSeparatorRow `forall`→`exists` (row and char levels): a row that
  // merely CONTAINS a dash cell — or a cell that merely contains a dash —
  // is not a separator; it parses as a row (and here fails on its
  // blocker, so the parse must be a Left, not a silently skipped row).
  test("parseRegister: a row that merely contains dash cells is not a separator"):
    val doc: String =
      "| Tool | Path | Blocker | Cited by |\n" +
        "|------|------|---------|----------|\n" +
        "| - | tools-a.sh | -x | docs-a.md |\n"
    assert(UnportedToolRegister.parseRegister(doc).isLeft)

  // Line-filter `&&`→`||` and `|`→`""`: a prose line that only ENDS in a
  // pipe — or only STARTS with one — is not a table row and must not be
  // parsed as an entry.
  test("parseRegister: lines that only start with or only end with a pipe are not rows"):
    val doc: String =
      "| Tool | Path | Blocker | Cited by |\n" +
        "|------|------|---------|----------|\n" +
        "prose | with | pipes | ending in |\n" +
        "| a | tools/a.sh | NotOnEnforcementPath | docs/a.md |\n" +
        "| x | y | z | w\n"
    assertEquals(
      UnportedToolRegister.parseRegister(doc),
      Right(
        List(
          UnportedTool("a", "tools/a.sh", PortBlocker.NotOnEnforcementPath, List("docs/a.md"))
        )
      )
    )

  // parseBlocker `&&`→`||` and `")"`→`""`: malformed closed-set spellings
  // must parse-fail — `GatedOnSpike(x` (no close), a bare `text)` suffix
  // match, and `SupersededByPortedTool` with no close are all outside
  // the closed set.
  test("parseRegister: malformed blocker spellings are Left, never parsed"):
    List("GatedOnSpike(x", "SupersededByPortedTool(graph", "anything)").foreach {
      (blockerText: String) =>
        val doc: String =
          "| Tool | Path | Blocker | Cited by |\n" +
            "|------|------|---------|----------|\n" +
            s"| a | tools/a.sh | $blockerText | docs/a.md |\n"
        assert(
          UnportedToolRegister.parseRegister(doc).isLeft,
          s"blocker '$blockerText' must not parse"
        )
    }

  // renderRegister string-literal mutants: the rendered document carries
  // its header and terminates in a newline — not just the row content.
  test("renderRegister: output carries the register header and a trailing newline"):
    val rendered: String = UnportedToolRegister.renderRegister(List(registeredTool))
    assert(rendered.startsWith("| Tool | Path | Blocker | Cited by |\n"))
    assert(rendered.contains("|------|"))
    assert(rendered.endsWith("\n"))

  // ── Property: blockers survive serialisation ────────────────────────
  // spec: unported-tool-register — Property: every-entry-carries-a-blocker
  property("register serialisation preserves every entry's blocker"):
    for entries <- genEntries.forAll
    yield
      val rendered: String = UnportedToolRegister.renderRegister(entries)
      UnportedToolRegister.parseRegister(rendered) match
        case Right(back) => Result.assert(back == entries)
        case Left(err)   => Result.failure.log(s"rendered register must re-parse: $err")

  private def genBlocker: Gen[PortBlocker] =
    Gen.frequency1(
      1 -> Gen.string(Gen.alphaNum, Range.linear(1, 12)).map(PortBlocker.GatedOnSpike(_)),
      1 -> Gen.constant(PortBlocker.NotOnEnforcementPath),
      1 -> Gen.string(Gen.alphaNum, Range.linear(1, 12)).map(PortBlocker.SupersededByPortedTool(_))
    )

  private def genEntries: Gen[List[UnportedTool]] =
    (for
      name    <- Gen.string(Gen.alphaNum, Range.linear(1, 10))
      blocker <- genBlocker
      citedBy <- Gen
        .string(Gen.alphaNum, Range.linear(1, 10))
        .map((s: String) => s"docs/$s.md")
        .list(Range.linear(0, 3))
    yield UnportedTool(name, s"tools/$name.sh", blocker, citedBy))
      .list(Range.linear(0, 6))
