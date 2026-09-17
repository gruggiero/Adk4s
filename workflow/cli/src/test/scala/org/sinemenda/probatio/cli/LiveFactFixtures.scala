package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Range
import org.sinemenda.probatio.core.ActiveChangeWithChainState
import org.sinemenda.probatio.core.ArtifactRef
import org.sinemenda.probatio.core.ArtifactScan
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.ChainStateUndetermined
import org.sinemenda.probatio.core.CheckId
import org.sinemenda.probatio.core.CheckOutcome
import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.InstallRootScan
import org.sinemenda.probatio.core.InstallRootState
import org.sinemenda.probatio.core.LintReport
import org.sinemenda.probatio.core.LintWarning
import org.sinemenda.probatio.core.ObligationRow
import org.sinemenda.probatio.core.RepositoryFacts
import org.sinemenda.probatio.core.RequirementBlock
import org.sinemenda.probatio.core.RequirementVerdict
import org.sinemenda.probatio.core.RootBase
import org.sinemenda.probatio.core.SpecDocument
import org.sinemenda.probatio.core.StampFormat
import org.sinemenda.probatio.core.UnresolvedEntry
import org.sinemenda.probatio.core.UnresolvedReason
import org.sinemenda.probatio.core.Verdict

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Constructive fixtures and generators for the live-fact-banner oracle.
 *
 * A `RepoShape` is a pure description of a repository state; `materialise`
 * writes it to a temporary directory tree. The oracle asserts that
 * `RepositoryFactsReader.read` returns exactly what the shape describes —
 * the anti-fabrication property.
 *
 * spec: live-fact-banner — Property: facts-reflect-repository (generator strategy)
 */
object LiveFactFixtures:

  // ════════════════════════════════════════════════════════════════════
  // Repository-shape descriptors (the generated domain)
  // ════════════════════════════════════════════════════════════════════

  /** The state of `openspec/schemas/verified-scala3/schema.yaml`. */
  enum SchemaShape:
    case Absent
    case Unparseable // file present, no parseable version field
    case Versioned(version: Int)

  /** The state of `openspec/concepts/` — document count to create. */
  enum RegistryShape:
    case Absent
    case Docs(count: Int) // a present registry with `count` documents

  /** The state of `openspec/concept-inventory.md`. */
  enum InventoryShape:
    case Absent
    case Rows(typeCells: List[String], junkCells: List[String])

  /** The state of `openspec/capability-profile.md`. */
  enum ProfileShape:
    case Absent
    case Present(kits: List[String]) // kit names embedded in the file

  /** The state of one install root's instruction document. */
  enum RootDocShape:
    case Absent
    case PresentNoStamp
    case Stamped(version: Int, format: StampFormat)
    case Unreadable

  /** What the chain-state seam answers for every active change. */
  enum ChainStub:
    case Absent                                  // no tool at all
    case Report(unresolvedCount: Int, exit: Int) // a fixed measured report
    case NullTotal                               // undetermined:true + total:null
    case Garbage(exit: Int)                      // unparseable output

  /**
   * One change directory. `archived` changes live under
   * `openspec/changes/archive/` and must never be reported active.
   */
  final case class ChangeShape(
    name: String,
    artifactsPresent: Set[String], // artifact ids materialised in the dir
    archived: Boolean
  )

  /** A complete generated repository shape. */
  final case class RepoShape(
    schema: SchemaShape,
    registry: RegistryShape,
    inventory: InventoryShape,
    profile: ProfileShape,
    rootDocs: List[RootDocShape], // exactly DriftScan.installRoots.length, in order
    changes: List[ChangeShape],
    chainStub: ChainStub
  )

  /** The mini artifact DAG written into a fixture's schema.yaml. */
  val fixtureArtifacts: List[ArtifactRef] = List(
    ArtifactRef("proposal", "proposal.md"),
    ArtifactRef("specs", "specs/**/*.md"),
    ArtifactRef("design", "design.md")
  )

  /** Which fact field a `changed` pair mutated. */
  enum MutatedField:
    case SchemaVersion, Registry, Inventory, Profile, InstallRoots, ActiveChanges

  // ════════════════════════════════════════════════════════════════════
  // Materialisation
  // ════════════════════════════════════════════════════════════════════

  final case class Materialised(
    repoRoot: Path,
    userHome: Path,
    env: Map[String, String]
  )

  private def write(path: Path, content: String): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))

  private def schemaYaml(s: SchemaShape): String = s match
    case SchemaShape.Versioned(v) =>
      val artifactLines: String = fixtureArtifacts
        .map((a: ArtifactRef) => s"  - id: ${a.id}\n    generates: ${a.generates}")
        .mkString("\n")
      s"name: probatio\nversion: $v\nartifacts:\n$artifactLines\n"
    case SchemaShape.Unparseable =>
      "name: probatio\nversion: notanumber\n"
    case SchemaShape.Absent =>
      ""

  /** Write a chain-state stub script; returns its path. */
  private def writeChainStub(tmp: Path, stub: ChainStub): Option[Path] =
    stub match
      case ChainStub.Absent => None
      case _ =>
        val body: String = stub match
          case ChainStub.Report(n, _) =>
            val entries: String =
              if n == 0 then "[]"
              else
                (0 until n)
                  .map(i => s"""{"spec":"s","requirement":"Req $i","reasons":["undischarged"]}""")
                  .mkString("[", ",", "]")
            s"""{"change":"c","baseline":"b","total":${n + 1},"bound":${n + 1},"resolved":${n + 1},""" +
              s""""discharged":1,"unresolved":$entries,"unmapped_obligations":[]}"""
          case ChainStub.NullTotal =>
            """{"change":"c","baseline":"b","undetermined":true,"reason":"stub","total":null,""" +
              """"bound":null,"resolved":null,"discharged":null,"unresolved":[],"unmapped_obligations":[]}"""
          case ChainStub.Garbage(_) =>
            "{not json"
          case ChainStub.Absent =>
            "" // unreachable
        val exitCode: Int = stub match
          case ChainStub.Report(_, e) => e
          case ChainStub.Garbage(e)   => e
          case _                      => 0
        val script: Path = tmp.resolve("fake-chain-state.sh")
        write(
          script,
          s"#!/usr/bin/env bash\ncat <<'REPORT'\n$body\nREPORT\nexit $exitCode\n"
        )
        script.toFile.setExecutable(true)
        Some(script)

  /**
   * Materialise a RepoShape into fresh temporary repo/home directories and
   * return the paths plus the env map the reader should receive
   * (`CHAIN_STATE_OVERRIDE` set when the shape provides a stub).
   */
  def materialise(shape: RepoShape): Materialised =
    val base: Path = Files.createTempDirectory("live-fact-shape")
    val repo: Path = base.resolve("repo")
    val home: Path = base.resolve("home")
    Files.createDirectories(repo.resolve("openspec"))
    Files.createDirectories(home)

    // schema.yaml (+ the artifact DAG it carries)
    shape.schema match
      case SchemaShape.Absent => ()
      case s =>
        write(
          repo.resolve("openspec/schemas/verified-scala3/schema.yaml"),
          schemaYaml(s)
        )

    // concept registry: N documents, plus a README.md that must not count
    shape.registry match
      case RegistryShape.Absent => ()
      case RegistryShape.Docs(n) =>
        val dir: Path = repo.resolve("openspec/concepts")
        Files.createDirectories(dir)
        write(dir.resolve("README.md"), "# readme — not a concept\n")
        (0 until n).foreach((i: Int) => write(dir.resolve(s"concept-$i.md"), s"# Concept: C$i\n"))

    // type inventory
    shape.inventory match
      case InventoryShape.Absent => ()
      case InventoryShape.Rows(types, junk) =>
        val rows: String =
          (types ++ junk).map(c => s"| $c | somewhere.scala |\n").mkString
        write(
          repo.resolve("openspec/concept-inventory.md"),
          s"| Type | File |\n|------|------|\n$rows"
        )

    // capability profile
    shape.profile match
      case ProfileShape.Absent => ()
      case ProfileShape.Present(kits) =>
        write(
          repo.resolve("openspec/capability-profile.md"),
          s"# profile\n${kits.mkString(" ")}\n"
        )

    // install roots: <base>/<rel>/openspec-spec-lint/SKILL.md
    DriftScan.installRoots.all
      .zip(shape.rootDocs)
      .foreach { case (ref, docShape) =>
        val baseDir: Path = ref.base match
          case RootBase.RepoRoot => repo
          case RootBase.UserHome => home
        val skillDir: Path = baseDir.resolve(ref.relativePath).resolve("openspec-spec-lint")
        docShape match
          case RootDocShape.Absent => ()
          case RootDocShape.PresentNoStamp =>
            write(skillDir.resolve("SKILL.md"), "---\nname: x\n---\n")
          case RootDocShape.Stamped(v, fmt) =>
            val stamp: String = fmt match
              case StampFormat.New    => s"probatio-schema/$v.0.0"
              case StampFormat.Legacy => s"verified-scala3-schema/$v.0.0"
            write(skillDir.resolve("SKILL.md"), s"---\ngeneratedBy: $stamp\n---\n")
          case RootDocShape.Unreadable =>
            val doc: Path = skillDir.resolve("SKILL.md")
            write(doc, "---\nname: x\n---\n")
            // The document exists but cannot be read — the fact is
            // Unreadable, never Absent.
            doc.toFile.setReadable(false, false)
      }

    // active + archived changes
    shape.changes.foreach { (c: ChangeShape) =>
      val parent: Path =
        if c.archived then repo.resolve("openspec/changes/archive")
        else repo.resolve("openspec/changes")
      val dir: Path = parent.resolve(c.name)
      Files.createDirectories(dir)
      fixtureArtifacts.foreach { (a: ArtifactRef) =>
        if c.artifactsPresent.contains(a.id) then
          if a.generates.contains("*") then Files.createDirectories(dir.resolve("specs"))
          else write(dir.resolve(a.generates), "x\n")
      }
    }

    val stubPath: Option[Path] = writeChainStub(base, shape.chainStub)
    val env: Map[String, String] = stubPath match
      case Some(p) => Map("CHAIN_STATE_OVERRIDE" -> p.toString)
      case None    => Map.empty
    Materialised(repo, home, env)

  /** Recursively delete a fixture tree, restoring permissions first. */
  def cleanup(m: Materialised): Unit =
    val root: Path = m.repoRoot.getParent
    deleteTree(root)

  /** Recursively delete a tree, restoring permissions on the way down. */
  def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      scala.util.Using.resource(Files.walk(p)) { walk =>
        walk.forEach { (q: Path) =>
          q.toFile.setReadable(true, false)
          q.toFile.setWritable(true, false)
        }
      }
      if Files.isDirectory(p) then scala.util.Using.resource(Files.list(p))(s => s.forEach(q => deleteTree(q)))
      Files.deleteIfExists(p)

  /**
   * acquire/use/release bracket for test fixtures — `release` always runs.
   * Expressed via `Using.Releasable` over an (acquired, release-action) pair
   * so the fixture needs no suppressed cleanup keywords.
   */
  private given pairReleasable[R]: scala.util.Using.Releasable[(R, R => Unit)] with
    def release(resource: (R, R => Unit)): Unit =
      resource._2(resource._1)

  def bracket[R, A](acquire: => R, release: R => Unit)(use: R => A): A =
    scala.util.Using.resource((acquire, release))((pair: (R, R => Unit)) => use(pair._1))

  /** Materialise `shape`, run `f`, always clean up. */
  def withMaterialised[A](shape: RepoShape)(f: Materialised => A): A =
    bracket(materialise(shape), cleanup)(f)

  /** Create a temp directory for `f`, always deleted after. */
  def withTempDir[A](prefix: String)(f: Path => A): A =
    bracket(Files.createTempDirectory(prefix), deleteTree)(f)

  /** Run `git` under `dir`; returns (exit code, captured stdout). */
  def git(dir: Path, args: String*): (Int, String) =
    val pb: ProcessBuilder = new ProcessBuilder(("git" +: args)*)
    pb.directory(dir.toFile)
    pb.redirectErrorStream(true)
    val p: Process  = pb.start()
    val out: String = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    (p.waitFor(), out.trim)

  // ════════════════════════════════════════════════════════════════════
  // Generators — constructive, no filtering
  // ════════════════════════════════════════════════════════════════════

  val genSchemaShape: Gen[SchemaShape] =
    Gen.frequency1(
      60 -> Gen.int(Range.linear(12, 16)).map(SchemaShape.Versioned(_)),
      25 -> Gen.constant(SchemaShape.Absent),
      15 -> Gen.constant(SchemaShape.Unparseable)
    )

  val genRegistryShape: Gen[RegistryShape] =
    Gen.frequency1(
      55 -> Gen.int(Range.linear(0, 12)).map(RegistryShape.Docs(_)),
      45 -> Gen.constant(RegistryShape.Absent)
    )

  private val genTypeCell: Gen[String] =
    for
      head   <- Gen.string(Gen.upper, Range.singleton(1))
      tail   <- Gen.string(Gen.alphaNum, Range.linear(0, 9))
      suffix <- Gen.frequency1(3 -> Gen.constant(""), 1 -> Gen.constant("[T]"))
      ticked <- Gen.boolean
    yield
      val raw: String = s"$head$tail$suffix"
      if ticked then s"`$raw`" else raw

  val genInventoryShape: Gen[InventoryShape] =
    Gen.frequency1(
      60 -> (for
        types <- genTypeCell.list(Range.linear(0, 20))
        junk  <- Gen.element("lowercase-name", List("Type", "42x", "")).list(Range.linear(0, 4))
      yield InventoryShape.Rows(types, junk)),
      40 -> Gen.constant(InventoryShape.Absent)
    )

  val genProfileShape: Gen[ProfileShape] =
    Gen.frequency1(
      60 -> Gen
        .element("TestControl", List("TestKit", "VirtualTime", "TestScheduler"))
        .list(Range.linear(0, 3))
        .map(ProfileShape.Present(_)),
      40 -> Gen.constant(ProfileShape.Absent)
    )

  val genRootDocShape: Gen[RootDocShape] =
    Gen.frequency1(
      65 -> Gen.constant(RootDocShape.Absent),
      10 -> Gen.int(Range.linear(12, 16)).map(v => RootDocShape.Stamped(v, StampFormat.New)),
      10 -> Gen.int(Range.linear(12, 16)).map(v => RootDocShape.Stamped(v, StampFormat.Legacy)),
      10 -> Gen.constant(RootDocShape.PresentNoStamp),
      5  -> Gen.constant(RootDocShape.Unreadable)
    )

  private val genChangeName: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(3, 10)).map(s => s"chg-$s")

  val genChangeShape: Gen[ChangeShape] =
    for
      name      <- genChangeName
      artifacts <- Gen.element("proposal", List("specs", "design")).list(Range.linear(0, 3)).map(_.toSet)
      archived  <- Gen.frequency1(1 -> Gen.constant(true), 5 -> Gen.constant(false))
    yield ChangeShape(name, artifacts, archived)

  val genChainStub: Gen[ChainStub] =
    Gen.frequency1(
      40 -> (for
        n    <- Gen.int(Range.linear(0, 6))
        exit <- Gen.frequency1(3 -> Gen.constant(0), 1 -> Gen.constant(1))
      yield ChainStub.Report(n, exit)),
      25 -> Gen.constant(ChainStub.Absent),
      20 -> Gen.int(Range.linear(2, 4)).map(ChainStub.Garbage(_)),
      15 -> Gen.constant(ChainStub.NullTotal)
    )

  /**
   * genRepositoryShape — the spec's constructive repository generator.
   *
   * spec: live-fact-banner — Property: facts-reflect-repository (generator)
   */
  val genRepoShape: Gen[RepoShape] =
    for
      schema    <- genSchemaShape
      registry  <- genRegistryShape
      inventory <- genInventoryShape
      profile   <- genProfileShape
      roots <- Gen.frequency1(
        // 15%: no instruction document at any searched root — an explicit
        // generator arm, not an emergent 0.65^6 conjunction.
        15 -> Gen.constant(List.fill(DriftScan.installRoots.length)(RootDocShape.Absent)),
        85 -> genRootDocShape.list(Range.singleton(DriftScan.installRoots.length))
      )
      nChanges  <- Gen.frequency1(55 -> Gen.int(Range.linear(1, 4)), 45 -> Gen.constant(0))
      changes   <- genChangeShape.list(Range.singleton(nChanges)).map(uniqNames)
      chainStub <- genChainStub
    yield RepoShape(schema, registry, inventory, profile, roots, changes, chainStub)

  private def uniqNames(changes: List[ChangeShape]): List[ChangeShape] =
    changes.zipWithIndex.map { case (c, i) => c.copy(name = s"${c.name}-$i") }

  // ════════════════════════════════════════════════════════════════════
  // RepositoryFacts generator (for banner + suppression properties)
  // ════════════════════════════════════════════════════════════════════

  def genFactRead[A](g: Gen[A]): Gen[FactRead[A]] =
    Gen.frequency1(
      50 -> g.map(FactRead.Present(_)),
      35 -> Gen.constant(FactRead.Absent),
      15 -> Gen.string(Gen.alpha, Range.linear(3, 20)).map(FactRead.Unreadable(_))
    )

  private val genRootState: Gen[InstallRootState] =
    Gen.frequency1(
      40 -> Gen.constant(InstallRootState.Absent),
      15 -> Gen.int(Range.linear(12, 16)).map(v => InstallRootState.Stamped(v, StampFormat.New)),
      15 -> Gen.int(Range.linear(12, 16)).map(v => InstallRootState.Stamped(v, StampFormat.Legacy)),
      15 -> Gen.constant(InstallRootState.PresentNoStamp),
      15 -> Gen.string(Gen.alpha, Range.linear(3, 12)).map(InstallRootState.Unreadable(_))
    )

  private val genReason: Gen[UnresolvedReason] =
    Gen.element(
      UnresolvedReason.Unbound,
      List(
        UnresolvedReason.Unresolved,
        UnresolvedReason.Undischarged,
        UnresolvedReason.Unattributable,
        UnresolvedReason.Failed
      )
    )

  /**
   * Spec-5 contract: entries and reports are constructible only through the
   * smart constructors. The generator derives counts from the reason mix so
   * every generated report satisfies the report contract's cross-checks.
   */
  private def entryOr(spec: String, req: String, reason: UnresolvedReason): UnresolvedEntry =
    UnresolvedEntry.of(spec, req, List(reason)) match
      case Some(e) => e
      case None    => ??? // unreachable: generated reasons are single non-empty values

  private def reportOr(
    total: Int,
    bound: Int,
    resolved: Int,
    discharged: Int,
    unresolved: List[UnresolvedEntry]
  ): ChainStateReport =
    ChainStateReport.fromCounts("c", "b", total, bound, resolved, discharged, unresolved, Nil) match
      case Right(r) => r
      case Left(_)  => ??? // unreachable: counts are derived from the reason mix

  private val genReport: Gen[ChainStateReport] =
    for
      nEntries <- Gen.int(Range.linear(0, 12))
      okCount  <- Gen.int(Range.linear(0, 12))
      reasons  <- genReason.list(Range.singleton(nEntries))
    yield
      // Unique requirement names keep the no-duplicate-pair clause satisfied;
      // one reason per entry makes the derived counts contract-consistent.
      val unresolved: List[UnresolvedEntry] =
        reasons.zipWithIndex.map((r, i) => entryOr("s", s"req-$i", r))
      val total: Int = unresolved.length + okCount
      val bound: Int = total - unresolved.count(_.reasons.contains(UnresolvedReason.Unbound))
      val resolved: Int = bound - unresolved.count(e =>
        e.reasons.contains(UnresolvedReason.Unresolved) || e.reasons.contains(UnresolvedReason.Unattributable)
      )
      val discharged: Int = resolved - unresolved.count(e =>
        e.reasons.contains(UnresolvedReason.Undischarged) || e.reasons.contains(UnresolvedReason.Failed)
      )
      reportOr(total, bound, resolved, discharged, unresolved)

  private val genChainState: Gen[Either[ChainStateUndetermined, ChainStateReport]] =
    Gen.frequency1(
      50 -> genReport.map(r => Right(r)),
      50 -> Gen
        .string(Gen.alpha, Range.linear(3, 20))
        .map(r => Left(ChainStateUndetermined("c", "b", r)))
    )

  private val genArtifacts: Gen[FactRead[ArtifactScan]] =
    genFactRead(
      for
        present <- Gen.element("proposal", List("specs", "design")).list(Range.linear(0, 3))
        next <- Gen.frequency1(
          1 -> Gen.constant(Option.empty[ArtifactRef]),
          2 -> Gen.element("tasks", List("design")).map(i => Some(ArtifactRef(i, s"$i.md")))
        )
      yield ArtifactScan(present, next)
    )

  private val genActiveChange: Gen[ActiveChangeWithChainState] =
    for
      name  <- genChangeName
      arts  <- genArtifacts
      chain <- genChainState
    yield ActiveChangeWithChainState(name, arts, chain)

  val genRepositoryFacts: Gen[RepositoryFacts] =
    for
      schema    <- genFactRead(Gen.int(Range.linear(12, 16)))
      registry  <- genFactRead(Gen.int(Range.linear(0, 40)))
      inventory <- genFactRead(Gen.int(Range.linear(0, 60)))
      profile <- genFactRead(
        Gen.frequency1(
          1 -> Gen.constant(Option.empty[String]),
          2 -> Gen.element("TestControl", List("TestKit")).map(Some(_))
        )
      )
      states <- genRootState.list(Range.singleton(DriftScan.installRoots.length))
      roots = DriftScan.installRoots.all
        .zip(states)
        .map { case (ref, st) => InstallRootScan(ref.relativePath, st) }
      changes <- genFactRead(genActiveChange.list(Range.linear(0, 3)))
    yield RepositoryFacts(schema, registry, inventory, profile, roots, changes)

  /**
   * genFactsPair — the spec's 50/50 constructive pair: the equal arm returns
   * the same record twice; the changed arm mutates exactly one field.
   *
   * spec: live-fact-banner — Property: suppression-tracks-facts (generator)
   */
  val genFactsPair: Gen[(RepositoryFacts, RepositoryFacts, Option[MutatedField])] =
    genRepositoryFacts.flatMap { (f1: RepositoryFacts) =>
      Gen.frequency1(
        50 -> Gen.constant((f1, f1, Option.empty[MutatedField])),
        50 -> mutateOneField(f1)
      )
    }

  private def mutateOneField(
    f1: RepositoryFacts
  ): Gen[(RepositoryFacts, RepositoryFacts, Option[MutatedField])] =
    def bump[A](fr: FactRead[A], alt: A): FactRead[A] = fr match
      case FactRead.Present(_)    => FactRead.Present(alt)
      case FactRead.Absent        => FactRead.Present(alt)
      case FactRead.Unreadable(_) => FactRead.Absent

    Gen.frequency1(
      16 -> Gen.constant((f1, f1.copy(schemaVersion = bump(f1.schemaVersion, 99)), Some(MutatedField.SchemaVersion))),
      16 -> Gen.constant((f1, f1.copy(registry = bump(f1.registry, 77)), Some(MutatedField.Registry))),
      16 -> Gen.constant((f1, f1.copy(inventory = bump(f1.inventory, 55)), Some(MutatedField.Inventory))),
      16 -> Gen.constant(
        (
          f1,
          f1.copy(profile = f1.profile match
            case FactRead.Present(_) => FactRead.Absent
            case _                   => FactRead.Present(Some("VirtualTime"))
          ),
          Some(MutatedField.Profile)
        )
      ),
      16 -> Gen.constant(
        (
          f1,
          f1.copy(installRoots = f1.installRoots match
            case h :: t => h.copy(state = InstallRootState.Unreadable("mutated")) :: t
            case Nil    => List(InstallRootScan("mutated-root", InstallRootState.Absent))
          ),
          Some(MutatedField.InstallRoots)
        )
      ),
      16 -> Gen.constant(
        (
          f1,
          f1.copy(activeChanges = f1.activeChanges match
            case FactRead.Present(h :: t) =>
              FactRead.Present(h.copy(name = h.name + "-mut") :: t)
            case _ =>
              FactRead.Present(
                List(
                  ActiveChangeWithChainState(
                    "mut",
                    FactRead.Absent,
                    Left(ChainStateUndetermined("mut", "b", "mutated"))
                  )
                )
              )
          ),
          Some(MutatedField.ActiveChanges)
        )
      )
    )

  /**
   * Build a `LintReport` attributing `verdicts` — the cli-side mirror of
   * probatio-core's `SpecLintFixtures.report` (cli tests do not see core
   * test classes). Goes through `LintReport.fromRun` against a synthetic
   * document — no test bypasses the smart constructor.
   */
  def lintReport(
    verdicts: List[RequirementVerdict],
    warnings: List[LintWarning],
    applicability: Map[String, String],
    lintSuccess: Boolean
  ): LintReport =
    val requirements: List[RequirementBlock] = verdicts.map { v =>
      RequirementBlock(
        title = v.requirement,
        line = 1,
        endLine = 2,
        hasNormative = true,
        negative = true,
        scenarioCount = 1,
        normativeText = ""
      )
    }
    val coveredTitles: List[String] =
      verdicts.collect { case v if v.verdict != Verdict.Unbound => v.requirement }
    val rows: List[ObligationRow] = coveredTitles.distinct.map { t =>
      ObligationRow(
        line = 0,
        fieldCount = 5,
        source = s"Requirement: $t",
        enforcement = "fixture check",
        artifact = "",
        raw = s"| obligation | Requirement: $t | fixture check | |"
      )
    }
    val hasResolved: Boolean = verdicts.exists(_.verdict == Verdict.Resolved)
    val artifactUnresolved: Option[Set[String]] =
      if hasResolved then Some(verdicts.collect { case v if v.verdict == Verdict.Bound => v.requirement }.toSet)
      else None
    val requirementRows: Map[String, List[ObligationRow]] =
      rows.groupBy(_.source.stripPrefix("Requirement: "))
    val document: SpecDocument = SpecDocument(
      name = "fixture",
      lines = Vector.empty,
      requirements = requirements,
      properties = Nil,
      temporals = Nil,
      scenarios = Nil,
      obligationRows = rows,
      dataRowCount = rows.size,
      bridgeRowCount = 0,
      hasProofObligations = true,
      formalContractsContentLines = 0,
      hasBehavioralConcepts = false,
      artifactRows = rows,
      chainRows = rows
    )
    val findings: List[CheckOutcome] =
      warnings.map(CheckOutcome.Warn(_)) ++
        (if lintSuccess then Nil
         else List(CheckOutcome.Fail(CheckId.F7, None, "synthetic failure")))
    LintReport.fromRun(
      document,
      findings,
      applicability,
      resolvedRows = rows,
      unresolvableRows = Nil,
      requirementRows = requirementRows,
      artifactUnresolved = artifactUnresolved
    )

end LiveFactFixtures
