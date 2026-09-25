package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.migration.ControlledVariable
import org.sinemenda.probatio.migration.HermeticEnv

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Model-based parity oracle: the predecessor `chain-state.sh` is the model,
 * executed as a `bash` subprocess; the ported `ChainStateCmd` is the system
 * under test, run in-process.
 *
 * Corpus is constructive and fixed, mirroring the spec's generator matrix —
 * {0,1,3} spec documents × {0,1,5} requirements × {empty, matching,
 * stale-baseline, corrupt} ledgers — plus the adversarial shapes the spec
 * names (ordinal sources, dangling typed references, unmapped findings).
 * Each fixture is materialised once and the predecessor's report is
 * recorded once; the property then samples fixtures and requires the
 * port's report to equal the predecessor's as parsed JSON.
 *
 * Both arms run the DEGRADED path: `OPENSPEC_ROOT` points at a directory
 * with no `openspec/` tree, exactly as `chain-state.bats`' `report_of`
 * does — so `openspec-graph.py export` fails on both sides and the
 * fallback arm is what parity is checked over. (The bats fixtures run
 * degraded the same way; graph-mode parity is covered by the same
 * mechanism in environments where the export can run.)
 *
 * The predecessor's spec-lint calls go through `SPEC_LINT_OVERRIDE` to a
 * wrapper around the freshly-built binary — never the stale launcher —
 * so the model and the port execute the same spec-lint code.
 *
 * spec: chain-state-attribution — Property: verdict-parity-with-predecessor
 */
final class ChainStateParitySpec extends ProbatioCliSuite:

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(120))

  // ── Property: verdict-parity-with-predecessor ────────────────────────
  // spec: chain-state-attribution — Property: verdict-parity-with-predecessor

  property("verdict-parity-with-predecessor", coverConfig):
    val corpus: List[ChainStateParitySpec.CorpusCase] = ChainStateParitySpec.corpus
    // Class-aware bucket sampling, as in SpecLintParitySpec: the corpus is
    // small and skewed, so each class is sampled at a guaranteed share.
    def bucket(p: ChainStateParitySpec.CorpusCase => Boolean): List[ChainStateParitySpec.CorpusCase] =
      corpus.filter(p)
    val undetermined: List[ChainStateParitySpec.CorpusCase] = bucket(_.predUndetermined)
    val hasUnmapped: List[ChainStateParitySpec.CorpusCase] =
      bucket(c => !c.predUndetermined && c.predUnmappedCount > 0)
    val hasUnresolved: List[ChainStateParitySpec.CorpusCase] =
      bucket(c => !c.predUndetermined && c.predUnresolvedCount > 0 && c.predUnmappedCount == 0)
    val clean: List[ChainStateParitySpec.CorpusCase] =
      bucket(c => !c.predUndetermined && c.predUnresolvedCount == 0 && c.predUnmappedCount == 0)
    val buckets: List[(Int, Gen[ChainStateParitySpec.CorpusCase])] =
      // Weights sit several standard deviations above each cover threshold:
      // with n=120 a 10%-weight/10%-cover class fails ~half the time.
      List(25 -> clean, 30 -> hasUnresolved, 25 -> undetermined, 20 -> hasUnmapped).collect { case (w, first :: rest) =>
        w -> Gen.element(first, rest)
      }
    val genCase: Gen[ChainStateParitySpec.CorpusCase] =
      buckets match
        case (w, g) :: rest => Gen.frequency1(w -> g, rest*)
        case Nil            => fail("parity corpus is empty")
    for c <- genCase.forAll
        .cover(
          15,
          "clean",
          (c: ChainStateParitySpec.CorpusCase) =>
            !c.predUndetermined && c.predUnresolvedCount == 0 && c.predUnmappedCount == 0
        )
        .cover(
          30,
          "has-unresolved",
          (c: ChainStateParitySpec.CorpusCase) => !c.predUndetermined && c.predUnresolvedCount > 0
        )
        .cover(15, "undetermined", (c: ChainStateParitySpec.CorpusCase) => c.predUndetermined)
        .cover(
          10,
          "has-unmapped",
          (c: ChainStateParitySpec.CorpusCase) => !c.predUndetermined && c.predUnmappedCount > 0
        )
    yield
      val (portStdout, _, portOutcome) = ChainStateParitySpec.runPort(c)
      val portJson: ujson.Value        = ujson.read(portStdout.trim)
      Result
        .assert(ChainStateParitySpec.normalise(portJson) == c.predNormalised)
        .log(
          s"parity mismatch on '${c.name}'\n" +
            s"  port: $portStdout\n  pred: ${c.predStdout}"
        )
        .and(
          Result
            .assert(Outcome.toExitCode(portOutcome) == c.predExit)
            .log(s"exit-code mismatch on '${c.name}': port ${Outcome.toExitCode(portOutcome)} vs pred ${c.predExit}")
        )

  // ── Property: parity-with-predecessor-on-the-undetermined-boundary ───
  // spec: chain-state-undetermined-fidelity — Property: parity-with-predecessor-on-the-undetermined-boundary
  //
  // genChangeFixture — constructive over a small closed alphabet: spec
  // shape {zero requirements, one requirement}, pre-pass behaviour drawn
  // from {completes, absent, non-executable, exits outside range, no
  // completion marker}, evidence record drawn from {absent, empty,
  // populated, corrupt}. The predecessor is executed as the model — each
  // stub terminates the way a real pre-pass would.

  property("undetermined boundary agrees with the predecessor", coverConfig):
    val corpus: List[ChainStateParitySpec.BoundaryCase] = ChainStateParitySpec.boundaryCorpus
    val undetermined: List[ChainStateParitySpec.BoundaryCase] =
      corpus.filter(_.predUndetermined)
    val measured: List[ChainStateParitySpec.BoundaryCase] =
      corpus.filter((c: ChainStateParitySpec.BoundaryCase) => !c.predUndetermined)
    val buckets: List[(Int, Gen[ChainStateParitySpec.BoundaryCase])] =
      List(60 -> undetermined, 40 -> measured).collect { case (w, first :: rest) =>
        w -> Gen.element(first, rest)
      }
    val genCase: Gen[ChainStateParitySpec.BoundaryCase] =
      buckets match
        case (w, g) :: rest => Gen.frequency1(w -> g, rest*)
        case Nil            => fail("boundary parity corpus is empty")
    for c <- genCase.forAll
        .cover(
          40,
          "boundary-undetermined",
          (c: ChainStateParitySpec.BoundaryCase) => c.predUndetermined
        )
        .cover(
          20,
          "boundary-measured",
          (c: ChainStateParitySpec.BoundaryCase) => !c.predUndetermined
        )
    yield
      val (portStdout, _, portOutcome) = ChainStateParitySpec.runPortBoundary(c)
      val portJson: ujson.Value        = ujson.read(portStdout.trim)
      Result
        .assert(ChainStateParitySpec.normalise(portJson) == c.predNormalised)
        .log(
          s"boundary parity mismatch on '${c.name}'\n" +
            s"  port: $portStdout\n  pred: ${c.predStdout}"
        )
        .and(
          Result
            .assert(Outcome.toExitCode(portOutcome) == c.predExit)
            .log(
              s"boundary exit-code mismatch on '${c.name}': " +
                s"port ${Outcome.toExitCode(portOutcome)} vs pred ${c.predExit}"
            )
        )

/**
 * The corpus and the predecessor-model harness for `ChainStateParitySpec`.
 * In a companion object so the one-time materialisation and predecessor
 * runs happen once per JVM, not once per sampled case.
 */
object ChainStateParitySpec:

  /** One fixture plus the recorded predecessor result. */
  final case class CorpusCase(
    name: String,
    fixtureDir: Path,
    baselineArg: String,
    predStdout: String,
    predExit: Int,
    predNormalised: ujson.Value,
    predUndetermined: Boolean,
    predUnresolvedCount: Int,
    predUnmappedCount: Int
  )

  /** A declarative fixture: spec-name → spec.md text, plus ledger lines. */
  final private case class Fixture(
    name: String,
    specs: Map[String, String],
    ledgerLines: List[String],
    progress: Option[String] = None,
    baselineArg: String = baseline
  )

  // ── fixture text (the bats shapes, verbatim) ─────────────────────────

  private val specHeader: String =
    """# Spec: Fixture
      |
      |## Concepts Used (behavioral)
      |
      || Concept | Role here | File |
      ||---------|-----------|------|
      || (none) | fixture spec | — |
      |
      |## Concepts Used (from inventory)
      |
      || Concept | Kind | Package |
      ||---------|------|---------|
      |
      |## ADDED Requirements
      |
      |""".stripMargin

  private def reqBlock(title: String): String =
    s"""### Requirement: $title
       |
       |The system SHALL do the thing named $title.
       |
       |**Given** a precondition
       |**When** an action
       |**Then** an observable outcome
       |
       |#### Scenario: happy path
       |
       |**Given** a specific setup
       |**When** a specific action
       |**Then** a specific assertion
       |
       |""".stripMargin

  private val poHeader: String =
    "## Proof Obligations\n\n| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n"

  private val resolvesArtifact: String =
    "openspec/schemas/verified-scala3/tests/correctness-invariant.bats"
  private val unresolvedArtifact: String =
    "tests/totally-fake-nonexistent-fixture-artifact.bats"

  private val baseline: String     = "00d3de1"
  private val fullBaseline: String = "00d3de1aa49141277dc4855a353f009fc7cc941a"
  private val change: String       = "fixture-change"

  private def ledgerJson(
    spec: String,
    obligation: String,
    artifact: String,
    exit: Int,
    baseline: String = baseline,
    ring: String = "manual"
  ): String =
    s"{\"v\":1,\"ts\":\"2026-09-17T00:00:00Z\",\"change\":\"$change\"," +
      s"\"spec\":\"$spec\",\"ring\":\"$ring\",\"obligation\":\"$obligation\"," +
      s"\"artifact\":\"$artifact\",\"command\":\"true\",\"exit\":$exit," +
      s"\"baseline\":\"$baseline\"}"

  private def oneSpec(body: String): Map[String, String] = Map("only" -> body)

  private def titleRow(obl: String, title: String, artifact: String): String =
    s"| $obl | Requirement: $title | manual | `$artifact` |\n"

  private val fixtures: List[Fixture] = List(
    // 1 spec, 3 requirements, all mapped + discharged — the clean arm.
    Fixture(
      "all-satisfied-3",
      oneSpec(
        specHeader + reqBlock("Req 1") + reqBlock("Req 2") + reqBlock("Req 3") + poHeader +
          titleRow("Text of obligation 1", "Req 1", resolvesArtifact) +
          titleRow("Text of obligation 2", "Req 2", resolvesArtifact) +
          titleRow("Text of obligation 3", "Req 3", resolvesArtifact)
      ),
      List(
        ledgerJson("only", "Text of obligation 1", resolvesArtifact, 0),
        ledgerJson("only", "Text of obligation 2", resolvesArtifact, 0),
        ledgerJson("only", "Text of obligation 3", resolvesArtifact, 0)
      )
    ),
    // 1 spec, 2 requirements, nothing bound — all unbound.
    Fixture(
      "none-satisfied-2",
      oneSpec(
        specHeader + reqBlock("Req 1") + reqBlock("Req 2") + poHeader +
          "| unrelated obligation | Property: nothing-to-do-with-these | manual | — |\n"
      ),
      Nil
    ),
    // One requirement, unbound only.
    Fixture(
      "unbound-only",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          "| unrelated | Property: elsewhere | manual | — |\n"
      ),
      Nil
    ),
    // Bound via exact title, artifact unresolved — F9 → unresolved.
    Fixture(
      "unresolved-artifact",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", unresolvedArtifact)
      ),
      Nil
    ),
    // Bound + resolved, empty ledger — undischarged.
    Fixture(
      "undischarged-only",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      Nil
    ),
    // Bound + resolved, only a red ledger row — failed (negative evidence).
    Fixture(
      "failed-run",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 1))
    ),
    // Bound + resolved, only a stale-baseline green row — undischarged.
    Fixture(
      "stale-baseline",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = "stale00"))
    ),
    // Zero requirements — the genuinely-empty clean arm.
    Fixture(
      "zero-requirements",
      oneSpec(specHeader + poHeader + "| n/a | n/a | n/a | n/a |\n"),
      Nil
    ),
    // No spec documents at all — undetermined.
    Fixture("no-specs", Map.empty, Nil),
    // Corrupt ledger — undetermined.
    Fixture(
      "corrupt-ledger",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List("{not json")
    ),
    // A discharged requirement plus an unmappable row carrying a finding —
    // unmapped_obligations non-empty with zero unresolved.
    Fixture(
      "unmapped-obligation",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact) +
          s"| orphan | Property: dangling-prop | manual | `$unresolvedArtifact` |\n"
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0))
    ),
    // Ordinal-sourced row: bound by spec-lint's loose binding, unmappable —
    // the degraded-mode unattributable case.
    Fixture(
      "ordinal-source",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          s"| ordinal obl | Requirement 1 | manual | `$resolvesArtifact` |\n"
      ),
      Nil
    ),
    // Combined `Requirement:` + `Scenario:` sources map by title.
    Fixture(
      "combined-source",
      oneSpec(
        specHeader + reqBlock("Combo Req") + poHeader +
          s"| combo obl | Requirement: Combo Req + Scenario: happy path | manual | `$resolvesArtifact` |\n"
      ),
      List(ledgerJson("only", "combo obl", resolvesArtifact, exit = 0))
    ),
    // Three spec documents with requirements spread across them.
    Fixture(
      "multi-spec-3",
      Map(
        "spec-a" -> (specHeader + reqBlock("Req A") + poHeader +
          titleRow("obl a", "Req A", resolvesArtifact)),
        "spec-b" -> (specHeader + reqBlock("Req B") + poHeader +
          titleRow("obl b", "Req B", resolvesArtifact)),
        "spec-c" -> (specHeader + reqBlock("Req C") + poHeader +
          "| unrelated | Property: elsewhere | manual | — |\n")
      ),
      List(ledgerJson("spec-a", "obl a", resolvesArtifact, exit = 0))
    ),
    // Manual-ring rows discharge — ledger.sh read does not filter by ring.
    Fixture(
      "manual-ring",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, ring = "manual"))
    ),
    // Five requirements, mixed verdicts — the bulk has-unresolved arm.
    Fixture(
      "mixed-5",
      oneSpec(
        specHeader + reqBlock("Req 1") + reqBlock("Req 2") + reqBlock("Req 3") +
          reqBlock("Req 4") + reqBlock("Req 5") + poHeader +
          titleRow("obl 1", "Req 1", resolvesArtifact) +
          titleRow("obl 2", "Req 2", unresolvedArtifact) +
          titleRow("obl 3", "Req 3", resolvesArtifact) +
          titleRow("obl 4", "Req 4", resolvesArtifact)
          // Req 5 is unbound — no row names it.
      ),
      List(
        ledgerJson("only", "obl 1", resolvesArtifact, exit = 0),
        ledgerJson("only", "obl 3", resolvesArtifact, exit = 1)
        // obl 4 has no row — undischarged.
      )
    ),
    // ── Ring-8 adversarial fixtures: the row-set and baseline divergences ──
    // An empty Source cell — spec-lint's source check skips the row but
    // its artifact pass still flags F9 there; chain-state's own awk row
    // set admits it, so the finding lands in unmapped_obligations.
    Fixture(
      "empty-source-f9",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact) +
          s"| empty-src obl | | manual | `$unresolvedArtifact` |\n"
      ),
      Nil
    ),
    // A comment Source cell — same path as the empty one.
    Fixture(
      "comment-source-f9",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact) +
          s"| comment-src obl | <!-- note --> | manual | `$unresolvedArtifact` |\n"
      ),
      Nil
    ),
    // An Obligation-prefixed data row — spec-lint's content-based header
    // exclusion drops it from ITS row set, but chain-state's structural
    // exclusion admits it, so the row joins Solo Req's mapped set; with
    // no ledger row for its obligation the requirement is undischarged.
    Fixture(
      "obligation-prefixed-row",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact) +
          s"| Obligation-shaped row | Requirement: Solo Req | manual | `$resolvesArtifact` |\n"
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline))
    ),
    // A `### Requirement:` heading inside the PO section stops spec-lint's
    // source scan (its row never binds) but not chain-state's awk — the
    // post-heading row still maps to Solo Req, and its F9 makes Solo Req
    // unresolved.
    Fixture(
      "heading-interruption-f9",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact) +
          "### Requirement: Decoy\n\nThe system SHALL hold the decoy.\n\n" +
          s"| post-heading obl | Requirement: Solo Req | manual | `$unresolvedArtifact` |\n"
      ),
      Nil
    ),
    // A `|` row BEFORE the separator — spec-lint admits it (binds Solo
    // Req, F9 fires on its line); chain-state's sep-gated awk skips it,
    // so the finding is silently dropped and Solo Req stays resolved.
    Fixture(
      "pre-separator-row",
      oneSpec(
        specHeader + reqBlock("Solo Req") +
          "## Proof Obligations\n\n" +
          s"| pre-sep obl | Requirement: Solo Req | manual | `$unresolvedArtifact` |\n" +
          "| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n" +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      Nil
    ),
    // An unresolvable --baseline — `git rev-parse` echoes the arg to
    // stdout before the `||` fallback echoes it again, so the resolved
    // baseline is "zzz\nzzz" and the literal row stays stale.
    Fixture(
      "unresolvable-baseline",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = "zzz")),
      baselineArg = "zzz"
    ),
    // A populated per-spec baseline map — the spec's own baseline admits
    // its row even though the effective baseline is unresolvable.
    Fixture(
      "baseline-map-match",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline)),
      progress = Some(
        "**BASELINE SHA**: `dead000`\n\n## Spec 1: only\n\n### Baseline\nSHA `00d3de1`\n"
      ),
      baselineArg = "dead000"
    ),
    // The effective baseline is the FIRST `**BASELINE SHA**` match
    // ANYWHERE in the file (the predecessor's `grep | head -1`), not the
    // gate arg. The marker deliberately sits on a later line and the row
    // is qualified at the file's SHA — only a full-file scan discharges it.
    Fixture(
      "effective-baseline-not-first-line",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline)),
      progress = Some(
        "# Progress\n\n## Spec 1: only\n\n- **BASELINE SHA**: `" + fullBaseline + "`\n"
      ),
      baselineArg = "zzz"
    ),
    // A non-`## Spec` `## ` heading clears in_baseline — the SHA line
    // after it is NOT captured, the map is empty, and the row stays stale.
    Fixture(
      "baseline-non-spec-heading",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline)),
      progress = Some(
        "**BASELINE SHA**: `dead000`\n\n## Spec 1: only\n\n### Baseline\n## Unrelated heading\n\nSHA `00d3de1`\n"
      ),
      baselineArg = "dead000"
    ),
    // The `SHA `` gate — a backticked hex on a line without the literal
    // `SHA ` marker is not a baseline capture.
    Fixture(
      "baseline-sha-gate",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline)),
      progress = Some(
        "**BASELINE SHA**: `dead000`\n\n## Spec 1: only\n\n### Baseline\nsee `00d3de1` for details\n"
      ),
      baselineArg = "dead000"
    ),
    // Corrupt ledger + non-empty baseline map — per-spec read failures
    // are traced and skipped, so the requirement is undischarged (exit
    // 1), never undetermined.
    Fixture(
      "baseline-map-corrupt-ledger",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List("{corrupt"),
      progress = Some(
        "**BASELINE SHA**: `dead000`\n\n## Spec 1: only\n\n### Baseline\nSHA `00d3de1`\n"
      ),
      baselineArg = "dead000"
    ),
    // R8-N7: a spec with TWO `## Spec` sections is read under EACH of
    // its baselines — the predecessor's TSV appends one line per
    // section, it does not dedup. The row's baseline matches only the
    // FIRST section's SHA; a last-wins Map would call it stale.
    Fixture(
      "baseline-duplicate-section",
      oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      List(ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline)),
      progress = Some(
        "**BASELINE SHA**: `dead000`\n\n## Spec 1: only\n\n### Baseline\nSHA `00d3de1`\n\n" +
          "## Spec 2: only\n\n### Baseline\nSHA `2ec4cbe`\n"
      ),
      baselineArg = "dead000"
    )
  )

  // ── locating the repository, the predecessor, and the spec-lint override ──

  private val repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  private val scannerDir: Path =
    repoRoot.resolve("openspec/schemas/verified-scala3/scanner")

  private val predecessor: Path =
    scannerDir.resolve("chain-state.sh.predecessor.bak")

  /**
   * The spec-lint the predecessor must see: the freshly-built port. Prefer
   * the native image; fall back to `java -jar` on the assembly archive —
   * archive invocation dispatches by the first argument (spec:
   * jar-launcher-dispatch), so no `probatio`-named shim is needed.
   */
  private lazy val specLintOverride: Path =
    val dir: Path     = Files.createTempDirectory("chain-state-spec-lint-override")
    val wrapper: Path = dir.resolve("spec-lint.sh")
    val native: Path  = repoRoot.resolve("workflow/cli/target/native-image/probatio")
    val jar: Path =
      repoRoot.resolve("workflow/cli/target/scala-3.8.4/probatio-cli-assembly-0.1.0-SNAPSHOT.jar")
    val body: String =
      if Files.isExecutable(native) then s"#!/usr/bin/env bash\nexec \"$native\" spec-lint \"$$@\"\n"
      else if Files.isRegularFile(jar) then
        s"#!/usr/bin/env bash\nexec java -jar \"$jar\" spec-lint \"$$@\"\n"
      else
        sys.error(
          "chain-state parity oracle needs a runnable spec-lint: build the " +
            "native image or the assembly jar first"
        )
    Files.writeString(wrapper, body, StandardCharsets.UTF_8)
    wrapper.toFile.setExecutable(true)
    wrapper

  // ── materialisation + the two arms ───────────────────────────────────

  /** Materialise `f` under a fresh temp directory; returns the fixture root. */
  private def materialise(f: Fixture): Path =
    val fx: Path = Files.createTempDirectory("chain-state-parity-" + f.name)
    f.specs.foreach { case (spec: String, text: String) =>
      val dir: Path = fx.resolve("specs").resolve(spec)
      Files.createDirectories(dir)
      Files.writeString(dir.resolve("spec.md"), text, StandardCharsets.UTF_8)
    }
    f.progress.foreach { (text: String) =>
      Files.writeString(
        fx.resolve("implementation-progress.md"),
        text,
        StandardCharsets.UTF_8
      )
    }
    Files.writeString(
      fx.resolve("evidence-ledger.jsonl"),
      f.ledgerLines.mkString("", "\n", if f.ledgerLines.isEmpty then "" else "\n"),
      StandardCharsets.UTF_8
    )
    // A directory guaranteed to have no openspec/ child — forces the
    // degraded arm on BOTH sides, as chain-state.bats' report_of does.
    Files.createDirectories(fx.resolve("no-openspec-root"))
    fx

  private def runPredecessor(fx: Path, baselineArg: String): (String, Int) =
    runPredecessor(fx, baselineArg, specLintOverride)

  private def runPredecessor(fx: Path, baselineArg: String, specLint: Path): (String, Int) =
    // spec: hermetic-test-processes — the two fixture seams are declared
    // controlled variables; nothing else of the invoking shell reaches the
    // predecessor.
    val env: HermeticEnv = HermeticEnv.build(
      Map(
        ControlledVariable.OpenspecRoot     -> fx.resolve("no-openspec-root").toString,
        ControlledVariable.SpecLintOverride -> specLint.toString
      )
    )
    val r: org.sinemenda.probatio.migration.HermeticResult = HermeticEnv.capture(
      List(
        "bash",
        predecessor.toString,
        "--change-dir",
        fx.toString,
        "--change",
        change,
        "--baseline",
        baselineArg
      ),
      env
    )
    (r.out, r.exitCode)

  def runPort(c: CorpusCase): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(
      ChainStateCmd.run(
        Array(
          "--change-dir",
          c.fixtureDir.toString,
          "--change",
          change,
          "--baseline",
          c.baselineArg
        ),
        Map(
          "OPENSPEC_ROOT"        -> c.fixtureDir.resolve("no-openspec-root").toString,
          "PROBATIO_SCANNER_DIR" -> scannerDir.toString
        )
      )
    )

  /**
   * Project a report to the comparable surface. Undetermined reports carry
   * implementation-specific reason text on both sides, so parity compares
   * the flag and identity fields only; determined reports compare whole.
   */
  def normalise(json: ujson.Value): ujson.Value =
    // `degraded` is the port's stated fact-source marker (spec:
    // graph-tool-port requires the report to declare when the graph did
    // not supply the requirement set). The predecessor has no vocabulary
    // for it — projected away so parity compares the measured surface.
    val projected: ujson.Value = json match
      case obj: ujson.Obj =>
        obj.value.remove("degraded")
        obj
      case other => other
    if projected.obj.get("undetermined").exists(_.bool) then
      ujson.Obj(
        "change"       -> projected("change"),
        "baseline"     -> projected("baseline"),
        "undetermined" -> ujson.Bool(true)
      )
    else projected

  lazy val corpus: List[CorpusCase] =
    fixtures.map { (f: Fixture) =>
      val fx: Path    = materialise(f)
      val (out, exit) = runPredecessor(fx, f.baselineArg)
      val json: ujson.Value =
        try ujson.read(out.trim)
        catch
          case e: Exception =>
            deleteTree(fx)
            sys.error(s"predecessor produced no JSON report for '${f.name}': ${e.getMessage}\n$out")
      val undetermined: Boolean = json.obj.get("undetermined").exists(_.bool)
      CorpusCase(
        f.name,
        fx,
        f.baselineArg,
        out,
        exit,
        normalise(json),
        undetermined,
        if undetermined then 0 else json("unresolved").arr.length,
        if undetermined then 0 else json.obj.get("unmapped_obligations").map(_.arr.length).getOrElse(0)
      )
    }

  // ════════════════════════════════════════════════════════════════════
  // The undetermined-boundary corpus — the spec 2 parity property's model
  // runs. Each case pairs a fixture with the pre-pass behaviour the
  // environment declares via SPEC_LINT_OVERRIDE, on both arms.
  //
  // spec: chain-state-undetermined-fidelity — Property: parity-with-predecessor-on-the-undetermined-boundary
  // ════════════════════════════════════════════════════════════════════

  /** How the pre-pass executable named by `SPEC_LINT_OVERRIDE` terminates. */
  private enum BoundaryStub:
    case Real          // the real spec-lint via the built-binary wrapper
    case Absent        // a path that does not exist
    case NonExecutable // a real file without the exec bit
    case BadExit       // exits 42 — outside the declared outcome range
    case NoMarker      // exits 1 with no recognised completion marker
    case FindingsRun   // exits 1 WITH a recognised completion marker — a genuine findings run

  /** The evidence-record state — the predecessor's `ledger.sh read` inputs. */
  private enum BoundaryLedger:
    case Absent, Empty, Populated, Corrupt

  /** One boundary fixture plus the recorded predecessor result. */
  final case class BoundaryCase(
    name: String,
    fixtureDir: Path,
    baselineArg: String,
    specLintOverride: Path,
    predStdout: String,
    predExit: Int,
    predNormalised: ujson.Value,
    predUndetermined: Boolean
  )

  private lazy val boundaryStubDir: Path =
    Files.createTempDirectory("chain-state-boundary-stubs")

  private def writeStub(name: String, body: String, executable: Boolean): Path =
    val p: Path = boundaryStubDir.resolve(name)
    if !Files.exists(p) then
      Files.writeString(p, body, StandardCharsets.UTF_8)
      p.toFile.setExecutable(executable)
    p

  private def boundaryStubPath(kind: BoundaryStub): Path = kind match
    case BoundaryStub.Real   => specLintOverride
    case BoundaryStub.Absent => boundaryStubDir.resolve("does-not-exist.sh")
    case BoundaryStub.NonExecutable =>
      writeStub("spec-lint-nonexec.sh", "#!/usr/bin/env bash\nexit 0\n", executable = false)
    case BoundaryStub.BadExit =>
      writeStub("spec-lint-badexit.sh", "#!/usr/bin/env bash\nexit 42\n", executable = true)
    case BoundaryStub.NoMarker =>
      writeStub(
        "spec-lint-nomarker.sh",
        "#!/usr/bin/env bash\necho 'spec-lint died inside find; no summary line'\nexit 1\n",
        executable = true
      )
    case BoundaryStub.FindingsRun =>
      // A genuine findings run: exit 1 (the declared lint-finding status)
      // with the recognised completion marker. The stub enumerates the
      // same `specs/**/spec.md` set both sides count, so the marker's N
      // agrees with each arm's own enumeration. No F7/F9 finding lines —
      // both sides then see an empty finding set and stay in parity.
      writeStub(
        "spec-lint-findings.sh",
        "#!/usr/bin/env bash\n" +
          "n=$(find \"$2/specs\" -name spec.md -type f 2>/dev/null | wc -l | tr -d ' ')\n" +
          "echo \"spec-lint: $n spec file(s), 1 FAIL, 0 WARN\"\n" +
          "exit 1\n",
        executable = true
      )

  /**
   * Materialise a boundary fixture — `specs/<spec>/spec.md` plus the
   * evidence record in the drawn state (`Absent` writes no file at all).
   */
  private def materialiseBoundary(
    name: String,
    specs: Map[String, String],
    ledgerKind: BoundaryLedger
  ): Path =
    val fx: Path = Files.createTempDirectory("chain-state-boundary-" + name)
    specs.foreach { case (spec: String, text: String) =>
      val dir: Path = fx.resolve("specs").resolve(spec)
      Files.createDirectories(dir)
      Files.writeString(dir.resolve("spec.md"), text, StandardCharsets.UTF_8)
    }
    ledgerKind match
      case BoundaryLedger.Absent => ()
      case BoundaryLedger.Empty =>
        Files.writeString(fx.resolve("evidence-ledger.jsonl"), "", StandardCharsets.UTF_8)
      case BoundaryLedger.Populated =>
        Files.writeString(
          fx.resolve("evidence-ledger.jsonl"),
          ledgerJson("only", "obl one", resolvesArtifact, exit = 0, baseline = fullBaseline) + "\n",
          StandardCharsets.UTF_8
        )
      case BoundaryLedger.Corrupt =>
        Files.writeString(
          fx.resolve("evidence-ledger.jsonl"),
          "{not json\n",
          StandardCharsets.UTF_8
        )
    Files.createDirectories(fx.resolve("no-openspec-root"))
    fx

  /**
   * The boundary corpus: 3 spec shapes × 6 pre-pass behaviours × 4
   * evidence-record states = 72 cases, each with the predecessor's
   * recorded verdict (the executable model).
   */
  lazy val boundaryCorpus: List[BoundaryCase] =
    val shapes: List[(String, Map[String, String])] = List(
      "zero-req" -> oneSpec(specHeader + poHeader + "| n/a | n/a | n/a | n/a |\n"),
      "one-req" -> oneSpec(
        specHeader + reqBlock("Solo Req") + poHeader +
          titleRow("obl one", "Solo Req", resolvesArtifact)
      ),
      "three-req" -> oneSpec(
        specHeader + reqBlock("Req 1") + reqBlock("Req 2") + reqBlock("Req 3") + poHeader +
          titleRow("obl one", "Req 1", resolvesArtifact) +
          titleRow("obl two", "Req 2", resolvesArtifact) +
          titleRow("obl three", "Req 3", resolvesArtifact)
      )
    )
    for
      (shapeName, specs) <- shapes
      stub               <- BoundaryStub.values.toList
      ledger             <- BoundaryLedger.values.toList
    yield
      val name: String   = s"$shapeName-${stub.toString.toLowerCase}-${ledger.toString.toLowerCase}"
      val fx: Path       = materialiseBoundary(name, specs, ledger)
      val stubPath: Path = boundaryStubPath(stub)
      val (out, exit)    = runPredecessor(fx, baseline, stubPath)
      val json: ujson.Value =
        try ujson.read(out.trim)
        catch
          case e: Exception =>
            deleteTree(fx)
            sys.error(s"predecessor produced no JSON report for boundary '$name': ${e.getMessage}\n$out")
      BoundaryCase(
        name,
        fx,
        baseline,
        stubPath,
        out,
        exit,
        normalise(json),
        json.obj.get("undetermined").exists(_.bool)
      )

  /** The ported arm under the same declared pre-pass as the model. */
  def runPortBoundary(c: BoundaryCase): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(
      ChainStateCmd.run(
        Array(
          "--change-dir",
          c.fixtureDir.toString,
          "--change",
          change,
          "--baseline",
          c.baselineArg
        ),
        Map(
          "OPENSPEC_ROOT"        -> c.fixtureDir.resolve("no-openspec-root").toString,
          "PROBATIO_SCANNER_DIR" -> scannerDir.toString,
          "SPEC_LINT_OVERRIDE"   -> c.specLintOverride.toString
        )
      )
    )

  private def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      if Files.isDirectory(p) then scala.util.Using.resource(Files.list(p))(s => s.forEach(q => deleteTree(q)))
      Files.deleteIfExists(p)
