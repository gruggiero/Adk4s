package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test oracle for the danger-scan engine (spec 6).
 *
 * Step 1 scope: the compile-negative obligations. The Step-2 oracle —
 * the named scenarios and the two properties — lands in this file in
 * the next phase.
 *
 * spec: danger-reconcile-engines — Compile-Negative: A DangerReport constructed with an empty hit list and a hit count greater than zero
 * spec: danger-reconcile-engines — Compile-Negative: DangerPattern pattern match omitting a case
 */
final class DangerScanEngineSpec extends ProbatioSuite:

  // ── Compile-Negative: DangerReport summary cannot disagree ─────────
  // spec: danger-reconcile-engines — Compile-Negative: A DangerReport constructed with an empty hit list and a hit count greater than zero
  test("DangerReport cannot be constructed with hits and count that disagree"):
    val err: String = compileErrors(
      "DangerReport(hits = Nil, justifiedExcluded = 5)"
    )
    assert(
      err.nonEmpty,
      "DangerReport(Nil, 5) must not compile — the primary constructor is " +
        "private; reports are built by DangerReport.of partitioning the " +
        "scan's own occurrence list"
    )

  test("DangerReport primary constructor is private"):
    val err: String = compileErrors(
      "new DangerReport(Nil, 0)"
    )
    assert(err.nonEmpty, "new DangerReport(...) must not compile — private constructor")

  test("DangerReport.copy is sealed — the summary cannot be edited after construction"):
    val err: String = compileErrors(
      "DangerReport.of(Nil).copy(justifiedExcluded = 9)"
    )
    assert(err.nonEmpty, "copy must not compile — it would reopen the constructor invariant")

  // ── Compile-Negative: a new pattern class forces exhaustive matches ─
  // spec: danger-reconcile-engines — Compile-Negative: DangerPattern pattern match omitting a case
  // The match-level half is enforced at production compile time by
  // -Wconf:name=PatternMatchExhaustivity:e on DangerPattern.label's
  // match — a ninth case fails the build, so it cannot exist to test.
  // The pin asserts the enum is exactly the predecessor's eight classes.
  test("DangerPattern has no ninth case"):
    val err: String = compileErrors("DangerPattern.NinthCase")
    assert(
      err.nonEmpty,
      "DangerPattern.NinthCase must not compile — the enum is sealed at " +
        "the predecessor's eight pattern classes"
    )

  // ── Compile-Negative: the engine takes values, not paths ────────────
  test("the scan engine accepts file contents, not filesystem paths"):
    val err: String = compileErrors(
      "DangerScanEngine.scan(java.nio.file.Paths.get(\"a.scala\"))"
    )
    assert(
      err.nonEmpty,
      "scan(Path) must not compile — the engine takes List[ScannedFile]; " +
        "ChangedFilesReader owns the filesystem"
    )

  test("the scan engine sources perform no file I/O and no environment reads"):
    val moduleDir: Path = dangerModuleDir
    val engineSources: List[Path] = List(
      "DangerScanEngine.scala"
    ).map((name: String) => moduleDir.resolve(name))
    val forbidden: List[String] = List(
      "java.nio.file",
      "java.io.File",
      "System.getenv", // scalafix:ok DisableSyntax.NoSystemGetenv
      "scala.io.",
      "sys.process", // scalafix:ok DisableSyntax.NoScalaSysProcess
      "ProcessBuilder"
    )
    engineSources.foreach { path =>
      assert(Files.isRegularFile(path), s"engine source missing: $path")
      val text: String = Files.readString(path)
      forbidden.foreach { token =>
        assert(
          !text.contains(token),
          s"${path.getFileName} contains forbidden I/O token '$token'"
        )
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Step 2 oracle — scenarios and properties (expected RED until Step 3)
  // ════════════════════════════════════════════════════════════════════

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  /**
   * One trigger line per pattern class — each line matches exactly one
   * predecessor pattern (paired occurrences on one line would make the
   * justification property unprovable: one allow-comment covers the
   * whole line).
   */
  private val patternLines: List[(DangerPattern, String)] = List(
    DangerPattern.UnsafeGet        -> "val x = opt.get",
    DangerPattern.UnsafeHead       -> "val y = xs.head",
    DangerPattern.CatchAll         -> "  case _ => default",
    DangerPattern.Cast             -> "val z = x.asInstanceOf[String]",
    DangerPattern.Blocking         -> "Await.result(f, timeout)",
    DangerPattern.Swallowed        -> "case scala.util.control.NonFatal(e) => log(e)",
    DangerPattern.UnreachableClaim -> "// this branch is unreachable",
    DangerPattern.LintOff          -> "val w = 1 // scalafix:off DisableSyntax"
  )

  /** Clean filler — lines the predecessor's patterns must NOT match. */
  private val cleanLines: List[String] = List(
    "val ok = 1",
    "def f(x: Int) = x + 1",
    "xs.map(_.toString)",
    "m.get(\"key\")",      // map-style .get( is deliberately not flagged
    "xs.headOption",       // .headOption is the safe variant
    "case Left(e) => f(e)" // a typed case arm is not a catch-all
  )

  // ── Scenario: an unjustified occurrence is reported ─────────────────
  // spec: danger-reconcile-engines — Scenario: Happy path — an unjustified occurrence is reported
  test("an unjustified catch-all occurrence is reported with file, line, and pattern"):
    val file: DangerScanEngine.ScannedFile =
      DangerScanEngine.ScannedFile("a/src/main/scala/A.scala", List("val ok = 1", "  case _ => boom"))
    val report: DangerReport = DangerScanEngine.scan(List(file))
    assertEquals(report.hits.length, 1)
    val hit: DangerHit = report.hits.headOption.getOrElse(fail("a hit was asserted"))
    assertEquals(hit.file, "a/src/main/scala/A.scala")
    assertEquals(hit.line, 2)
    assertEquals(hit.pattern, DangerPattern.CatchAll)
    assert(!hit.justified)

  // ── Per-pattern pinpoints — one per predecessor class ───────────────
  patternLines.foreach { case (pattern, line) =>
    test(s"pattern ${DangerPattern.label(pattern)} fires on its trigger line"):
      val hits: List[DangerHit] =
        DangerScanEngine.scanLine("f.scala", 1, line)
      assertEquals(hits.map(_.pattern), List(pattern))
      assertEquals(hits.map(_.line), List(1))
  }

  cleanLines.foreach { line =>
    test(s"clean line does not trigger: ${line.take(30)}"):
      assertEquals(DangerScanEngine.scanLine("f.scala", 1, line), Nil)
  }

  // ── Scenario: a justified occurrence is excluded ────────────────────
  // spec: danger-reconcile-engines — Scenario: Happy path — a justified occurrence is excluded
  test("a same-line danger-scan:allow justification excludes the occurrence and counts it"):
    val file: DangerScanEngine.ScannedFile =
      DangerScanEngine.ScannedFile(
        "a/src/main/scala/A.scala",
        List("  case _ => default // danger-scan:allow total-on-sealed", "val ok = 1")
      )
    val report: DangerReport = DangerScanEngine.scan(List(file))
    assertEquals(report.hits, Nil)
    assertEquals(report.justifiedExcluded, 1)

  test("unreachable-claim matching is case-insensitive"):
    val hits: List[DangerHit] =
      DangerScanEngine.scanLine("f.scala", 1, "// UNREACHABLE state")
    assertEquals(hits.map(_.pattern), List(DangerPattern.UnreachableClaim))

  // The predecessor runs eight sequential `grep -nE` scans per file —
  // hits group by pattern declaration order, NOT by line order.
  test("scan emits hits pattern-major within a file, line-major within a pattern"):
    val file: DangerScanEngine.ScannedFile =
      DangerScanEngine.ScannedFile(
        "a/src/main/scala/A.scala",
        List(
          "val w = 1 // scalafix:off DisableSyntax", // line 1 — lint-off (pattern 8)
          "val x = opt.get",                         // line 2 — unsafe-get (pattern 1)
          "val y = xs.head",                         // line 3 — unsafe-head (pattern 2)
          "val z = other.get"                        // line 4 — unsafe-get (pattern 1)
        )
      )
    val report: DangerReport = DangerScanEngine.scan(List(file))
    assertEquals(
      report.hits.map(h => (DangerPattern.label(h.pattern), h.line)),
      List("unsafe-get" -> 2, "unsafe-get" -> 4, "unsafe-head" -> 3, "lint-off" -> 1)
    )

  // ── Scenario: test files are not in scope unless named ──────────────
  // spec: danger-reconcile-engines — Scenario: Edge case — test files are not in scope unless named by the caller
  test("the scope predicate excludes test paths and admits production paths"):
    assert(!DangerScanEngine.isProductionPath("a/src/test/scala/ASpec.scala"))
    assert(DangerScanEngine.isProductionPath("a/src/main/scala/A.scala"))
    assert(!DangerScanEngine.isProductionPath("a/src/it/scala/A.scala"))
    // predecessor-exact quirk: a repo-root src/main path lacks the leading slash
    assert(!DangerScanEngine.isProductionPath("src/main/scala/A.scala"))

  // ── Property: justification-excludes-exactly-its-own-occurrence ─────
  // spec: danger-reconcile-engines — Property: justification-excludes-exactly-its-own-occurrence
  property("justification-excludes-exactly-its-own-occurrence", coverConfig):
    for pair <- genFixturePair.forAll
        .cover(
          30,
          "same-file-both-occurrences",
          (p: (List[DangerScanEngine.ScannedFile], List[DangerScanEngine.ScannedFile])) => p._1.length == 1
        )
        .cover(
          30,
          "different-files",
          (p: (List[DangerScanEngine.ScannedFile], List[DangerScanEngine.ScannedFile])) => p._1.length > 1
        )
    yield
      val before: List[DangerHit] = DangerScanEngine.scan(pair._1).hits
      val after: List[DangerHit]  = DangerScanEngine.scan(pair._2).hits
      Result
        .assert(before.diff(after).length == 1)
        .log("exactly one occurrence removed")
        .and(
          Result
            .assert(after.toSet.subsetOf(before.toSet))
            .log("no other occurrence changed")
        )
        .and(
          Result
            .assert(
              DangerScanEngine.scan(pair._2).justifiedExcluded ==
                DangerScanEngine.scan(pair._1).justifiedExcluded + 1
            )
            .log("the excluded count gains exactly one")
        )

  /**
   * genDangerFixturePair — a file list carrying ≥2 unjustified
   * occurrences on distinct lines (constructive: every generated hit
   * line triggers exactly one pattern), then the same list with one
   * chosen occurrence's line gaining a `danger-scan:allow` suffix.
   */
  private lazy val genFixturePair: Gen[(List[DangerScanEngine.ScannedFile], List[DangerScanEngine.ScannedFile])] =
    for
      nHits    <- Gen.int(Range.linear(2, 5))
      sameFile <- Gen.boolean
      paths <-
        if sameFile then Gen.constant(List("a/src/main/scala/A.scala"))
        else Gen.int(Range.linear(2, nHits)).map(n => (1 to n).toList.map(i => s"a/src/main/scala/F$i.scala"))
      hitLines <- Gen
        .elementUnsafe(patternLines.map(_._2))
        .list(Range.linear(nHits, nHits))
      filler    <- Gen.elementUnsafe(cleanLines).list(Range.linear(0, 3))
      targetHit <- Gen.int(Range.linear(0, nHits - 1))
    yield
      // Distribute hit lines over the chosen paths — each hit on its own line.
      val files: List[DangerScanEngine.ScannedFile] =
        paths.zipWithIndex.map { case (p, i) =>
          val mine: List[String] = hitLines.zipWithIndex.collect {
            case (l, j) if j % paths.length == i => l
          }
          DangerScanEngine.ScannedFile(p, filler ++ mine)
        }
      // Hit j lands in file (j % paths.length) at line (filler.length +
      // j / paths.length) — the distribution is deterministic, so the
      // target line is known without searching.
      val target: (Int, Int) =
        (targetHit % paths.length, filler.length + (targetHit / paths.length))
      val justified: List[DangerScanEngine.ScannedFile] =
        files.zipWithIndex.map { case (f, i) =>
          if i != target._1 then f
          else
            f.copy(lines = f.lines.zipWithIndex.map { case (l, j) =>
              if j == target._2 then l + " // danger-scan:allow justified" else l
            })
        }
      (files, justified)

  // ── helpers ─────────────────────────────────────────────────────────

  /** Locate `probatio-core`'s main source directory. */
  private def dangerModuleDir: Path =
    val candidates: List[Path] = List(
      Paths.get("workflow/core/src/main/scala/org/sinemenda/probatio/core"),
      Paths.get("src/main/scala/org/sinemenda/probatio/core")
    )
    candidates.find((p: Path) => Files.isDirectory(p)) match
      case Some(dir) => dir
      case None =>
        fail(s"could not locate probatio-core sources from ${Paths.get("").toAbsolutePath}")
