package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.migration.HermeticEnv
import org.sinemenda.probatio.migration.HermeticResult

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using
import scala.util.matching.Regex

import SpecLintFixtures.poHeader
import SpecLintFixtures.reqBlock
import SpecLintFixtures.specHeader

/**
 * Model-based parity oracle: the predecessor `spec-lint.sh` is the model,
 * executed as a subprocess; the engine is the system under test.
 *
 * The corpus is constructive and fixed: every `spec.md` under `openspec/`
 * plus a set of document shapes ported from the predecessor's own bats
 * fixtures. The predecessor's findings are computed once per corpus
 * document (each through a real `bash` invocation with the artifact check
 * enabled), then `genCorpusDocument` samples cases and the engine's
 * findings must equal the predecessor's — identifier paired with line —
 * in the predecessor's emission order.
 *
 * spec: spec-lint-engine — Property: verdict-parity-with-predecessor
 * spec: spec-lint-engine — Requirement: The lint engine reproduces the predecessor's verdict on every fixture
 */
final class SpecLintParitySpec extends ProbatioSuite:

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(150))

  private lazy val repoContext: LintContext =
    SpecLintParitySpec.readRepoContext(SpecLintParitySpec.repoRoot)

  private lazy val trackedFiles: Set[String] =
    SpecLintParitySpec.gitTrackedFiles(SpecLintParitySpec.repoRoot)

  private def artifactTracked(base: String): Boolean =
    trackedFiles.exists(_.contains(base))

  /** The engine's finding stream as `"FAIL F7 line 42: msg"` strings. */
  private def portFindings(text: String): List[String] =
    val doc: SpecDocument = SpecDocumentParser.parse("corpus", text)
    SpecLintEngine.lint(doc, repoContext, checkArtifacts = true, artifactTracked) match
      case Outcome.Ran(report) =>
        report.findings.collect {
          case CheckOutcome.Fail(check, line, msg) =>
            s"FAIL ${CheckId.asString(check)}${line.fold("")(l => s" line $l")}: $msg"
          case CheckOutcome.Warn(w) =>
            s"WARN ${w.code}${w.line.fold("")(l => s" line $l")}: ${w.message}"
        }
      case other => fail(s"engine run must be Ran, got $other")

  // ── Property: verdict-parity-with-predecessor ───────────────────────
  // spec: spec-lint-engine — Property: verdict-parity-with-predecessor
  property("verdict-parity-with-predecessor", coverConfig):
    // Class-aware sampling: the corpus is fixed and skewed — the real
    // spec documents almost all carry findings — so each verdict class
    // is sampled at a guaranteed share rather than its corpus proportion.
    val failsOnly: List[SpecLintParitySpec.CorpusCase] =
      SpecLintParitySpec.corpus.filter(_.predecessorFindings.exists(_.startsWith("FAIL")))
    val clean: List[SpecLintParitySpec.CorpusCase] =
      SpecLintParitySpec.corpus.filter(_.predecessorFindings.isEmpty)
    val warnsOnly: List[SpecLintParitySpec.CorpusCase] =
      SpecLintParitySpec.corpus.filter { c =>
        c.predecessorFindings.exists(_.startsWith("WARN")) &&
        !c.predecessorFindings.exists(_.startsWith("FAIL"))
      }
    // Weights are deliberately above the cover thresholds: frequency
    // sampling is stochastic, so a threshold at the sampling share is a
    // coin-flip per run. 40/40/20 keeps each label's mean comfortably
    // above its requirement.
    val buckets: List[(Int, Gen[SpecLintParitySpec.CorpusCase])] =
      List(40 -> failsOnly, 40 -> clean, 20 -> warnsOnly).collect { case (w, first :: rest) =>
        w -> Gen.element(first, rest)
      }
    val genCase: Gen[SpecLintParitySpec.CorpusCase] =
      buckets match
        case (w, g) :: rest => Gen.frequency1(w -> g, rest*)
        case Nil            => fail("parity corpus is empty")
    for c <- genCase.forAll
        .cover(
          30,
          "has-failures",
          (c: SpecLintParitySpec.CorpusCase) => c.predecessorFindings.exists(_.startsWith("FAIL"))
        )
        .cover(
          20,
          "clean",
          (c: SpecLintParitySpec.CorpusCase) => c.predecessorFindings.isEmpty
        )
        .cover(
          10,
          "has-warnings-only",
          (c: SpecLintParitySpec.CorpusCase) =>
            c.predecessorFindings.exists(_.startsWith("WARN")) &&
              !c.predecessorFindings.exists(_.startsWith("FAIL"))
        )
    yield
      val port: List[String] = portFindings(c.text)
      Result
        .assert(port == c.predecessorFindings)
        .log(
          s"parity mismatch on '${c.name}'\n" +
            s"  port: ${port.mkString(" | ")}\n" +
            s"  pred: ${c.predecessorFindings.mkString(" | ")}"
        )

/**
 * The corpus and the predecessor-model harness for `SpecLintParitySpec`.
 * Lives in a companion object so the one-time predecessor runs happen once
 * per JVM, not once per munit suite instance.
 */
object SpecLintParitySpec:

  /** One corpus document plus its recorded predecessor findings. */
  final case class CorpusCase(
    name: String,
    text: String,
    predecessorFindings: List[String]
  )

  // ── locating the repository and the predecessor ──────────────────────

  /** The repository root — the predecessor's own `git rev-parse` rule. */
  val repoRoot: Path =
    gitOut(Paths.get("").toAbsolutePath.normalize, List("rev-parse", "--show-toplevel")) match
      case Some(p) => Paths.get(p)
      case None =>
        val start: Path = Paths.get("").toAbsolutePath.normalize
        LazyList
          .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
          .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3"))) match
          case Some(p) => p
          case None =>
            sys.error(s"could not locate the repository root from $start")

  private val predecessor: Path =
    repoRoot.resolve("openspec/schemas/verified-scala3/scanner/spec-lint.sh.predecessor.bak")

  private def gitOut(dir: Path, args: List[String]): Option[String] =
    // spec: hermetic-test-processes — via the shared helper.
    try
      val r: HermeticResult =
        HermeticEnv.capture("git" +: args, HermeticEnv.empty, cwd = Some(dir.toFile))
      if r.exitCode == 0 && r.out.trim.nonEmpty then Some(r.out.trim) else None
    catch case _: java.io.IOException => None

  /** Every tracked file — the `git ls-files` base for the F9 predicate. */
  def gitTrackedFiles(root: Path): Set[String] =
    gitOut(root, List("ls-files")).map(_.linesIterator.toSet).getOrElse(Set.empty)

  // ── the repository's applicability facts, replicating the reader ────

  /**
   * Build the `LintContext` the predecessor computes for this repository —
   * registry presence and `# Concept:` headings, the inventory type names,
   * schema version and profile. Replicates the predecessor's own
   * extraction rules so the engine and the model see identical facts.
   */
  def readRepoContext(root: Path): LintContext =
    val conceptsDir: Path = root.resolve("openspec/concepts")
    val registry: FactRead[Int] =
      if !Files.isDirectory(conceptsDir) then FactRead.Absent
      else
        val n: Long = Using.resource(Files.walk(conceptsDir)) { walk =>
          walk
            .filter { (p: Path) =>
              Files.isRegularFile(p) &&
              p.getFileName.toString.endsWith(".md") &&
              p.getFileName.toString != "README.md"
            }
            .count()
        }
        FactRead.Present(n.toInt)
    val registryConcepts: FactRead[List[String]] =
      if !Files.isDirectory(conceptsDir) then FactRead.Absent
      else
        val headings: List[String] = Using.resource(Files.list(conceptsDir)) { stream =>
          stream
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
            .flatMap(p => Files.readString(p, StandardCharsets.UTF_8).linesIterator)
            .filter(_.startsWith("# Concept: "))
            .map(_.replaceFirst("^# Concept: *", "").replaceAll("[ \t]+$", ""))
            .toList
            .sorted
            .distinct
        }
        FactRead.Present(headings)
    val inventoryPath: Path = root.resolve("openspec/concept-inventory.md")
    val inventoryTypes: FactRead[List[String]] =
      if !Files.isRegularFile(inventoryPath) then FactRead.Absent
      else
        val cells: List[String] = Files
          .readString(inventoryPath, StandardCharsets.UTF_8)
          .linesIterator
          .filter(_.startsWith("|"))
          .flatMap { (line: String) =>
            val fields: Array[String] = line.split("\\|", -1)
            if fields.length > 1 then Some(fields(1)) else None
          }
          .toList
        val types: List[String] = cells
          .map(c => c.replace("`", "").trim.replaceAll("\\[.*", ""))
          .filter(c => c.matches("[A-Z][A-Za-z0-9_]*") && c != "Type")
          .distinct
          .sorted
        FactRead.Present(types)
    val schemaPath: Path = root.resolve("openspec/schemas/verified-scala3/schema.yaml")
    val schemaVersion: FactRead[Int] =
      if !Files.isRegularFile(schemaPath) then FactRead.Absent
      else
        Files
          .readString(schemaPath, StandardCharsets.UTF_8)
          .linesIterator
          .find(_.startsWith("version:"))
          .flatMap(l => l.substring(l.indexOf(':') + 1).trim.toIntOption)
          .map(FactRead.Present(_))
          .getOrElse(FactRead.Absent)
    val profilePath: Path = root.resolve("openspec/capability-profile.md")
    val profile: FactRead[Option[String]] =
      if !Files.isRegularFile(profilePath) then FactRead.Absent
      else
        val kits: List[String] =
          "(?i)(TestControl|TestKit|VirtualTime|TestScheduler)".r
            .findAllIn(Files.readString(profilePath, StandardCharsets.UTF_8))
            .toList
            .distinct
            .sorted
        FactRead.Present(if kits.isEmpty then None else Some(kits.mkString(" ")))
    LintContext(schemaVersion, registry, registryConcepts, inventoryTypes, profile, Nil)

  // ── running the predecessor model ────────────────────────────────────

  private val findingRe: Regex =
    "^\\s*(FAIL|WARN)\\s+([FW][0-9]+)(\\s+line\\s+([0-9]+))?\\s*:(.*)$".r

  /**
   * Run the predecessor on `text` as a single-spec change directory and
   * return its finding lines, normalized to `FAIL F7 line 42: msg` form
   * (leading indentation stripped).
   */
  def runPredecessor(root: Path, name: String, text: String): List[String] =
    val dir: Path = Files.createTempDirectory("spec-lint-parity")
    try
      val specsDir: Path = dir.resolve("specs")
      Files.createDirectories(specsDir)
      Files.writeString(specsDir.resolve("spec.md"), text, StandardCharsets.UTF_8)
      // spec: hermetic-test-processes — via the shared helper.
      val r: HermeticResult = HermeticEnv.capture(
        List("bash", predecessor.toString, "--artifacts", dir.toString),
        HermeticEnv.empty,
        cwd = Some(root.toFile)
      )
      val out: String = r.out
      val exit: Int   = r.exitCode
      if exit == 2 then sys.error(s"predecessor found no specs under $dir for '$name'")
      out.linesIterator.toList.flatMap { line =>
        line match
          case findingRe(kind, id, _, num, msg) =>
            Some(s"$kind $id${Option(num).fold("")(n => s" line $n")}:${msg}")
          case _ => None
      }
    finally deleteTree(dir) // scalafix:ok DisableSyntax.NoKeywordFinally

  private def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      if Files.isDirectory(p) then Using.resource(Files.list(p))(stream => stream.forEach(q => deleteTree(q)))
      Files.deleteIfExists(p)

  // ── the corpus ───────────────────────────────────────────────────────

  /** Every spec.md under openspec/ — the real corpus. */
  private def realSpecDocs(root: Path): List[(String, String)] =
    Using
      .resource(Files.walk(root.resolve("openspec"))) { walk =>
        walk
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString == "spec.md")
          .toList
      }
      .sortBy(_.toString)
      .map(p => root.relativize(p).toString -> Files.readString(p, StandardCharsets.UTF_8))

  /** The bats builder shapes, ported — plus targeted check fixtures. */
  private def batsFixtureDocs: List[(String, String)] =
    def rows(cells: List[String]): String =
      cells.map(c => s"| obligation | $c | manual | — |\n").mkString
    List(
      "bats-all-satisfied-3" -> (
        specHeader +
          reqBlock("Req 1") + reqBlock("Req 2") + reqBlock("Req 3") +
          poHeader +
          rows(List("Requirement: Req 1", "Requirement: Req 2", "Requirement: Req 3"))
      ),
      "bats-none-satisfied-2" -> (
        specHeader + reqBlock("Req 1") + reqBlock("Req 2") +
          "## Proof Obligations\n\n| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n" +
          "| unrelated obligation | Property: nothing-to-do-with-these | manual | — |\n"
      ),
      "bats-unbound-only" -> (
        specHeader + reqBlock("Solo Req") + poHeader +
          "| unrelated | Property: elsewhere | manual | — |\n"
      ),
      "bats-zero-requirements" -> (
        specHeader + poHeader + "| n/a | n/a | n/a | n/a |\n"
      ),
      "bats-combined-source" -> (
        specHeader + reqBlock("Combo Req") + poHeader +
          "| o | Requirement: Combo Req + Scenario: happy path | manual | — |\n"
      ),
      "bats-unmapped-obligation" -> (
        specHeader + reqBlock("Solo Req") + poHeader +
          "| o | Requirement: Solo Req | manual | — |\n" +
          "| orphan | Property: some-property | manual | — |\n"
      ),
      "no-po-section" -> (
        specHeader + reqBlock("Req Alpha")
      ),
      "property-no-strategy" -> (
        specHeader + "### Property: order-preservation\n\nbody text\n\n"
      ),
      "temporal-missing-response" -> (
        specHeader + "### Temporal: eventual flush\n\n**Trigger event**: write\n\n"
      ),
      "negative-no-scenario" -> (
        specHeader +
          "### Requirement: Alpha never leaks\n\n" +
          "The system SHALL never leak alpha.\n\n" +
          "**Given** x\n**When** y\n**Then** z\n\n" +
          poHeader + "| o | Requirement: Alpha never leaks | manual | — |\n"
      ),
      "vague-word" -> (
        specHeader +
          "### Requirement: Req Alpha\n\n" +
          "The system SHALL return a valid result in reasonable time.\n\n" +
          "**Given** x\n**When** y\n**Then** z\n\n" +
          "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
          poHeader + "| o | Requirement: Req Alpha | manual | — |\n"
      ),
      "ordinal-source" -> (
        specHeader + reqBlock("Req Alpha") + poHeader +
          "| o | Requirement 1 | manual | — |\n"
      ),
      "dangling-property-source" -> (
        specHeader + reqBlock("Req Alpha") +
          "### Property: sorted-ness\n\n**Generator strategy**: g\n\n" +
          poHeader +
          "| o | Requirement: Req Alpha | manual | — |\n" +
          "| p | Property: missing-widget | manual | — |\n"
      ),
      "formal-contracts-no-bridge" -> (
        specHeader + reqBlock("Req Alpha") +
          "## Formal Contracts (Ring 6)\n\nline one\nline two\nline three\n\n" +
          poHeader + "| o | Requirement: Req Alpha | manual | — |\n"
      ),
      "out-of-range-ordinal" -> (
        specHeader + reqBlock("Req Alpha") + poHeader +
          "| o | Requirement 9 | manual | — |\n"
      ),
      "impossible-weak-enforcement" -> (
        specHeader +
          "### Requirement: Bad states cannot be constructed\n\n" +
          "The system SHALL ensure a bad state cannot be constructed.\n\n" +
          "**Given** x\n**When** y\n**Then** z\n\n" +
          "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
          poHeader +
          "| o | Requirement: Bad states cannot be constructed | property test | — |\n"
      ),
      "w7-clause-tokens" -> (
        specHeader +
          "### Requirement: Req Alpha\n\n" +
          "The system SHALL do it.\n\n" +
          "**Given** `AlphaSpec.scala` exists\n**When** `sbt core/compile` runs\n**Then** `SpecLintEngine` is consulted\n\n" +
          "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
          poHeader + "| o | Requirement: Req Alpha | manual | — |\n"
      ),
      "empty-doc"  -> "",
      "prose-only" -> "# Notes\n\nNothing structured here.\n",
      // Ring-8 adversarial fixtures — shapes the first corpus missed:
      // check_source resolves against the LIVE heading arrays, so a Proof
      // Obligations section above the requirements sees n_reqs = 0.
      "po-before-requirements" -> (
        specHeader + poHeader +
          "| o | Requirement: Req Alpha | manual | — |\n" +
          "| o | Requirement 1 | manual | — |\n" +
          reqBlock("Req Alpha")
      ),
      // Digit-adjacent SHALL/MUST still count — the predecessor's class is
      // `[^A-Za-z]`, not `[^[:alnum:]]`.
      "normative-digit-adjacent" -> (
        specHeader +
          "### Requirement: Req Alpha\n\n" +
          "The system SHALL2 flag digit-adjacent normative words.\n\n" +
          "**Given** x\n**When** y\n**Then** z\n\n" +
          "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
          poHeader + "| o | Requirement: Req Alpha | manual | — |\n"
      ),
      // An empty-title requirement counts toward n_reqs but its body is
      // never scanned and its flush checks never fire.
      "empty-title-requirement" -> (
        specHeader +
          "### Requirement:\n\n" +
          "vague valid wording, no normative claim at all\n\n" +
          reqBlock("Req Beta") +
          poHeader + "| o | Requirement: Req Beta | manual | — |\n"
      ),
      // A typed source resolves against headings above the row only.
      "typed-source-declared-later" -> (
        specHeader + reqBlock("Req Alpha") + poHeader +
          "| o | Requirement: Req Alpha | manual | — |\n" +
          "| p | Property: later-prop | manual | — |\n" +
          "### Property: later-prop\n\n**Generator strategy**: g\n\n"
      ),
      // Empty-title property/temporal blocks produce no F3/F5.
      "empty-title-property-temporal" -> (
        specHeader +
          "### Property:\n\nno generator strategy here\n\n" +
          "### Temporal:\n\nno trigger or response\n\n"
      )
    )

  /**
   * The corpus: real spec documents plus the ported bats shapes, each
   * with its predecessor findings precomputed by a real subprocess run.
   */
  lazy val corpus: List[CorpusCase] =
    (batsFixtureDocs ++ realSpecDocs(repoRoot)).map { case (name, text) =>
      CorpusCase(name, text, runPredecessor(repoRoot, name, text))
    }

end SpecLintParitySpec
