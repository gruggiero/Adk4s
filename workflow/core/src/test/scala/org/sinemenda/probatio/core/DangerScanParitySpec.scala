package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.util.matching.Regex

import DangerScanParitySpec.DangerFixture
import DangerScanParitySpec.FixtureFile
import DangerScanParitySpec.materialise

/**
 * Model-based parity oracle: the predecessor `danger-scan.sh` is the
 * model, executed as a `bash` subprocess; `DangerScanEngine` is the
 * system under test.
 *
 * `genDangerFixture` materialises a small git repository per sample —
 * a baseline commit, then changed `.scala` files spread over production
 * and test paths, each line drawn from a one-snippet-per-pattern pool
 * and independently justified or not, plus occasional `--also` files.
 * The assertion is the spec's: the set of reported (file, line,
 * pattern-label) triples equals the predecessor's.
 *
 * The port side replicates `ChangedFilesReader`'s enumeration
 * (`git diff --name-only HEAD -- '*.scala'`, the `/src/main/` scope
 * predicate, the `[ -f ]` existence guard, sorted dedupe) because the
 * reader lives in probatio-cli — the engine under test is pure.
 *
 * spec: danger-reconcile-engines — Property: danger-parity-with-predecessor
 */
final class DangerScanParitySpec extends ProbatioSuite:

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(80))

  // ── the generator ───────────────────────────────────────────────────

  /**
   * One trigger line per pattern class — each matches exactly one
   * predecessor pattern (a line matching two patterns would be removed
   * by a single allow-comment; keeping triggers distinct makes the
   * justified/unjustified split per-occurrence).
   */
  private val triggers: List[(DangerPattern, String)] = List(
    DangerPattern.UnsafeGet        -> "val x = opt.get",
    DangerPattern.UnsafeHead       -> "val y = xs.head",
    DangerPattern.CatchAll         -> "  case _ => default",
    DangerPattern.Cast             -> "val z = x.asInstanceOf[String]",
    DangerPattern.Blocking         -> "Await.result(f, timeout)",
    DangerPattern.Swallowed        -> "case scala.util.control.NonFatal(e) => log(e)",
    DangerPattern.UnreachableClaim -> "// this branch is unreachable",
    DangerPattern.LintOff          -> "val w = 1 // scalafix:off DisableSyntax"
  )

  private val cleanPool: List[String] = List(
    "val ok = 1",
    "def f(x: Int) = x + 1",
    "xs.map(_.toString)",
    "m.get(\"key\")",
    "xs.headOption",
    "case Left(e) => f(e)"
  )

  private def genPath(production: Boolean): Gen[String] =
    Gen
      .string(Gen.alphaNum, Range.linear(1, 6))
      .map(s => if production then s"pkg/src/main/scala/$s.scala" else s"pkg/src/test/scala/${s}Spec.scala")

  private def justifyAll(lines: List[String]): Gen[List[String]] =
    lines.foldRight(Gen.constant(List.empty[String])) { (l, acc) =>
      Gen.boolean.flatMap(j => acc.map(rest => (if j then l + " // danger-scan:allow r" else l) :: rest))
    }

  private def genLines: Gen[List[String]] =
    for
      hits     <- Gen.elementUnsafe(triggers.map(_._2)).list(Range.linear(0, 5))
      hitsJust <- justifyAll(hits)
      filler   <- Gen.elementUnsafe(cleanPool).list(Range.linear(1, 4))
    yield filler ++ hitsJust

  val genDangerFixture: Gen[DangerFixture] =
    for
      nChanged <- Gen.frequency1(
        85 -> Gen.int(Range.linear(1, 4)),
        15 -> Gen.constant(0)
      )
      changed <-
        (for
          path <- Gen.frequency1(60 -> genPath(true), 40 -> genPath(false))
          ls   <- genLines
        yield FixtureFile(path, ls))
          .list(Range.linear(nChanged, nChanged))
      also <-
        (for
          path <- Gen.frequency1(50 -> genPath(false), 50 -> genPath(true))
          ls   <- genLines
        yield FixtureFile(path, ls))
          .list(Range.linear(0, 2))
    yield DangerFixture(changed, also)

  // ── the property ────────────────────────────────────────────────────

  property("danger-parity-with-predecessor", coverConfig):
    for fx <- genDangerFixture.forAll
        .cover(8, "unsafe-get", (f: DangerFixture) => f.hasPattern(DangerPattern.UnsafeGet))
        .cover(8, "unsafe-head", (f: DangerFixture) => f.hasPattern(DangerPattern.UnsafeHead))
        .cover(8, "catch-all", (f: DangerFixture) => f.hasPattern(DangerPattern.CatchAll))
        .cover(8, "cast", (f: DangerFixture) => f.hasPattern(DangerPattern.Cast))
        .cover(8, "blocking", (f: DangerFixture) => f.hasPattern(DangerPattern.Blocking))
        .cover(8, "swallowed", (f: DangerFixture) => f.hasPattern(DangerPattern.Swallowed))
        .cover(8, "unreachable-claim", (f: DangerFixture) => f.hasPattern(DangerPattern.UnreachableClaim))
        .cover(8, "lint-off", (f: DangerFixture) => f.hasPattern(DangerPattern.LintOff))
        .cover(30, "justified", (f: DangerFixture) => f.hasJustified)
        .cover(10, "test-path-only", (f: DangerFixture) => f.testPathOnly)
        .cover(5, "no-changes", (f: DangerFixture) => f.changedFiles.isEmpty)
    yield materialise(fx) match
      case Left(reason) =>
        // Tooling unavailable or the model failed to run — a green
        // result produced without a comparison is the defect class
        // this property exists to kill. Fail, do not skip.
        Result.failure.log(s"model could not run: $reason")
      case Right((port, pred)) =>
        Result
          .assert(port == pred)
          .log(s"parity mismatch\n  port: $port\n  pred: $pred")

/** The git/subprocess harness for `DangerScanParitySpec`. */
object DangerScanParitySpec:

  /** A changed file: repo-relative path plus its worktree content. */
  final case class FixtureFile(path: String, lines: List[String])

  /** A materialised-fixture description. */
  final case class DangerFixture(
    changedFiles: List[FixtureFile],
    alsoFiles: List[FixtureFile]
  ):
    /** Any occurrence line carries a justification comment. */
    def hasJustified: Boolean =
      (changedFiles ++ alsoFiles).exists(_.lines.exists(_.contains("danger-scan:allow")))

    /** Any line that begins with `p`'s trigger text (unjustified or not). */
    def hasPattern(p: DangerPattern): Boolean =
      (changedFiles ++ alsoFiles).exists(f => f.lines.exists(l => l.startsWith(triggerOf(p))))
    def testPathOnly: Boolean =
      changedFiles.nonEmpty && changedFiles.forall(_.path.contains("/src/test/"))

  private def triggerOf(p: DangerPattern): String = p match
    case DangerPattern.UnsafeGet        => "val x = opt.get"
    case DangerPattern.UnsafeHead       => "val y = xs.head"
    case DangerPattern.CatchAll         => "  case _ => default"
    case DangerPattern.Cast             => "val z = x.asInstanceOf[String]"
    case DangerPattern.Blocking         => "Await.result(f, timeout)"
    case DangerPattern.Swallowed        => "case scala.util.control.NonFatal(e) => log(e)"
    case DangerPattern.UnreachableClaim => "// this branch is unreachable"
    case DangerPattern.LintOff          => "val w = 1 // scalafix:off DisableSyntax"

  private val repoRoot: Path =
    LazyList
      .unfold(Paths.get("").toAbsolutePath.normalize)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(Paths.get("").toAbsolutePath)

  private val predecessor: Path =
    repoRoot.resolve("openspec/schemas/verified-scala3/scanner/danger-scan.sh.predecessor.bak")

  private val hitRe: Regex  = "^  \\[([a-z-]+)\\] ([0-9]+):(.*)$".r
  private val fileRe: Regex = "^danger-scan: (.+)$".r

  private def run(dir: Path, args: List[String]): Option[(Int, String)] =
    try
      val pb: ProcessBuilder = new ProcessBuilder(args*)
      pb.directory(dir.toFile)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      p.getErrorStream.readAllBytes() // drain stderr so the process cannot block
      Some(p.waitFor() -> out)
    catch case _: java.io.IOException => None

  private def git(dir: Path, args: List[String]): Option[String] =
    run(dir, "git" +: args).collect { case (0, out) => out.trim }

  /**
   * Build the fixture's repository, run the predecessor and the engine
   * over identical inputs, and return both hit sets as
   * `(file, line, label)`. `Left` when git/bash cannot run — the
   * property fails rather than reporting a comparison that never
   * happened.
   */
  def materialise(
    fx: DangerFixture
  ): Either[String, (Set[(String, Int, String)], Set[(String, Int, String)])] =
    val dir: Path = Files.createTempDirectory("danger-parity")
    val repo: Either[String, Unit] =
      for
        _ <- git(dir, List("init", "-q")).toRight("git init failed")
        _ <- git(
          dir,
          List("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "base")
        ).toRight("git baseline commit failed")
      yield ()
    repo.flatMap { (_: Unit) =>
      // worktree state: every changed file is written AND staged so
      // `git diff --name-only HEAD` reports it (untracked files are
      // invisible to git diff — the predecessor sees staged additions).
      fx.changedFiles.foreach { f =>
        val p: Path = dir.resolve(f.path)
        Files.createDirectories(p.getParent)
        Files.writeString(p, f.lines.mkString("\n") + "\n", StandardCharsets.UTF_8)
      }
      fx.alsoFiles.foreach { f =>
        val p: Path = dir.resolve(f.path)
        Files.createDirectories(p.getParent)
        Files.writeString(p, f.lines.mkString("\n") + "\n", StandardCharsets.UTF_8)
      }
      git(dir, List("add", "-A"))
      // ── predecessor model ──
      val alsoArgs: List[String] =
        if fx.alsoFiles.isEmpty then Nil else "--also" +: fx.alsoFiles.map(_.path)
      val predOut: Option[String] = run(dir, "bash" +: predecessor.toString +: "HEAD" +: alsoArgs).map(_._2)
      predOut match
        case None => Left("predecessor script could not launch (bash unavailable)")
        case Some(out) =>
          val predHits: Set[(String, Int, String)] =
            out.linesIterator
              .foldLeft((Option.empty[String], Set.empty[(String, Int, String)])) { case ((file, acc), line) =>
                line match
                  case fileRe(name) if !name.startsWith("no production") => (Some(name), acc)
                  case hitRe(label, num, _) =>
                    (file, file.fold(acc)(f => acc + ((f, num.toInt, label))))
                  case _ => (file, acc)
              }
              ._2
          // ── port: replicate the reader's enumeration, then engine.scan ──
          git(dir, List("diff", "--name-only", "HEAD", "--", "*.scala")) match
            case None => Left("git diff failed")
            case Some(diffOut) =>
              val changed: List[String] =
                diffOut.linesIterator.toList.filter(DangerScanEngine.isProductionPath)
              val scope: List[String] = (changed ++ fx.alsoFiles.map(_.path)).distinct.sorted
              val files: List[DangerScanEngine.ScannedFile] =
                scope.flatMap { path =>
                  val p: Path = dir.resolve(path)
                  if Files.isRegularFile(p) && Files.isReadable(p) then
                    Some(
                      DangerScanEngine.ScannedFile(
                        path,
                        Files.readString(p, StandardCharsets.UTF_8).linesIterator.toList
                      )
                    )
                  else None
                }
              val portHits: Set[(String, Int, String)] =
                DangerScanEngine
                  .scan(files)
                  .hits
                  .map(h => (h.file, h.line, DangerPattern.label(h.pattern)))
                  .toSet
              Right((portHits, predHits))
    }
