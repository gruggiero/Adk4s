package org.sinemenda.probatio.cli

import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.ArtifactRef
import org.sinemenda.probatio.core.ArtifactScan
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.ChainStateUndetermined
import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.InstallRootScan
import org.sinemenda.probatio.core.InstallRootState
import org.sinemenda.probatio.core.RepositoryFacts
import org.sinemenda.probatio.core.RootBase
import org.sinemenda.probatio.core.StampFormat
import org.sinemenda.probatio.core.UnmappedObligation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.ChainStub
import LiveFactFixtures.ChangeShape
import LiveFactFixtures.InventoryShape
import LiveFactFixtures.Materialised
import LiveFactFixtures.ProfileShape
import LiveFactFixtures.RegistryShape
import LiveFactFixtures.RepoShape
import LiveFactFixtures.RootDocShape
import LiveFactFixtures.SchemaShape
import LiveFactFixtures.bracket
import LiveFactFixtures.fixtureArtifacts
import LiveFactFixtures.genRepoShape
import LiveFactFixtures.git
import LiveFactFixtures.withMaterialised
import LiveFactFixtures.withTempDir

/**
 * Test oracle for RepositoryFactsReader: the facts the reader returns are
 * exactly the facts the repository contains.
 *
 * spec: live-fact-banner — Property: facts-reflect-repository
 * spec: live-fact-banner — Proof Obligation: Read facts equal the repository's state
 */
final class RepositoryFactsSpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  // ── Expected-value projection of a RepoShape ──────────────────────────

  private def expectedSchema(s: SchemaShape): FactRead[Int] = s match
    case SchemaShape.Versioned(v) => FactRead.Present(v)
    case SchemaShape.Absent       => FactRead.Absent
    case SchemaShape.Unparseable  => FactRead.Unreadable("")

  private def expectedRegistry(r: RegistryShape): FactRead[Int] = r match
    case RegistryShape.Docs(n) => FactRead.Present(n)
    case RegistryShape.Absent  => FactRead.Absent

  /**
   * The predecessor's inventory rule: first data cell of every table row,
   * backticks stripped, `Foo[A]` → `Foo`, capitalised identifiers only,
   * `Type` (the header cell) excluded, deduplicated.
   */
  private def expectedInventoryCount(i: InventoryShape): FactRead[Int] = i match
    case InventoryShape.Absent => FactRead.Absent
    case InventoryShape.Rows(types, junk) =>
      val normalised: List[String] = (types ++ junk).map { (cell: String) =>
        cell.replace("`", "").trim.replaceAll("\\[.*", "")
      }
      val valid: Int = normalised
        .filter(c => c.matches("[A-Z][A-Za-z0-9_]*") && c != "Type")
        .distinct
        .length
      FactRead.Present(valid)

  private def expectedProfile(p: ProfileShape): FactRead[Option[String]] = p match
    case ProfileShape.Absent => FactRead.Absent
    case ProfileShape.Present(kits) =>
      val kitsRe              = "(?i)(TestControl|TestKit|VirtualTime|TestScheduler)".r
      val found: List[String] = kitsRe.findAllIn(kits.mkString(" ")).toList.distinct.sorted
      if found.isEmpty then FactRead.Present(None) else FactRead.Present(Some(found.mkString(" ")))

  private def expectedRootState(d: RootDocShape): InstallRootState = d match
    case RootDocShape.Absent         => InstallRootState.Absent
    case RootDocShape.PresentNoStamp => InstallRootState.PresentNoStamp
    case RootDocShape.Stamped(v, f)  => InstallRootState.Stamped(v, f)
    case RootDocShape.Unreadable     => InstallRootState.Unreadable("")

  private def expectedArtifacts(
    c: ChangeShape,
    schema: SchemaShape
  ): FactRead[ArtifactScan] =
    schema match
      case SchemaShape.Absent => FactRead.Absent
      // The unparseable fixture carries no `artifacts:` section — the DAG
      // fact is absent from the file, so there is nothing to scan.
      case SchemaShape.Unparseable => FactRead.Absent
      case SchemaShape.Versioned(_) =>
        val present: List[String] =
          fixtureArtifacts.filter(a => c.artifactsPresent.contains(a.id)).map(_.id)
        val next: Option[ArtifactRef] =
          fixtureArtifacts.find(a => !c.artifactsPresent.contains(a.id))
        FactRead.Present(ArtifactScan(present, next))

  private def chainIsMeasured(stub: ChainStub): Boolean = stub match
    case ChainStub.Report(_, exit) if exit == 0 || exit == 1 => true
    case _                                                   => false

  private def assertFactState[A](label: String, actual: FactRead[A], expected: FactRead[A]): Result =
    (expected, actual) match
      case (FactRead.Present(e), FactRead.Present(a)) =>
        Result.assert(e == a).log(s"$label: expected Present($e), got Present($a)")
      case (FactRead.Absent, FactRead.Absent)               => Result.success
      case (FactRead.Unreadable(_), FactRead.Unreadable(_)) => Result.success
      case (e, a) =>
        Result.failure.log(s"$label: expected ${stateName(e)}, got ${stateName(a)}")

  private def stateName[A](f: FactRead[A]): String = f match
    case FactRead.Present(_)    => "Present"
    case FactRead.Absent        => "Absent"
    case FactRead.Unreadable(_) => "Unreadable"

  // ── Property: facts-reflect-repository ────────────────────────────────
  // spec: live-fact-banner — Property: facts-reflect-repository
  property("facts-reflect-repository", coverConfig):
    for shape <- genRepoShape.forAll
        .cover(
          40,
          "registry-present",
          (s: RepoShape) =>
            s.registry match
              case RegistryShape.Docs(_) => true
              case RegistryShape.Absent  => false
        )
        .cover(
          25,
          "registry-absent",
          (s: RepoShape) =>
            s.registry match
              case RegistryShape.Absent => true
              case _                    => false
        )
        .cover(
          20,
          "drift-at-user-root",
          (s: RepoShape) =>
            val schemaV: Option[Int] = s.schema match
              case SchemaShape.Versioned(v) => Some(v)
              case _                        => None
            s.rootDocs.drop(3).exists {
              case RootDocShape.Stamped(v, StampFormat.New)    => schemaV.exists(_ != v)
              case RootDocShape.Stamped(_, StampFormat.Legacy) => true
              case _                                           => false
            }
        )
        .cover(
          10,
          "pre-rename-stamp",
          (s: RepoShape) =>
            s.rootDocs.exists {
              case RootDocShape.Stamped(_, StampFormat.Legacy) => true
              case _                                           => false
            }
        )
        .cover(10, "no-install-anywhere", (s: RepoShape) => s.rootDocs.forall(_ == RootDocShape.Absent))
        .cover(40, "has-active-changes", (s: RepoShape) => s.changes.exists(c => !c.archived))
    yield withMaterialised(shape) { (m: Materialised) =>
      val f: RepositoryFacts =
        RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)

      val schemaR: Result    = assertFactState("schemaVersion", f.schemaVersion, expectedSchema(shape.schema))
      val registryR: Result  = assertFactState("registry", f.registry, expectedRegistry(shape.registry))
      val inventoryR: Result = assertFactState("inventory", f.inventory, expectedInventoryCount(shape.inventory))
      val profileR: Result   = assertFactState("profile", f.profile, expectedProfile(shape.profile))

      val rootsR: Result =
        val expectedScans: List[InstallRootScan] =
          DriftScan.installRoots.all.zip(shape.rootDocs).map { case (ref, docShape) =>
            val base: Path = ref.base match
              case RootBase.RepoRoot => m.repoRoot
              case RootBase.UserHome => m.userHome
            InstallRootScan(base.resolve(ref.relativePath).toString, expectedRootState(docShape))
          }
        val pathOk: Boolean =
          f.installRoots.map(_.rootPath) == expectedScans.map(_.rootPath)
        val stateOk: Boolean =
          f.installRoots.map(_.state).zip(expectedScans.map(_.state)).forall {
            case (InstallRootState.Unreadable(_), InstallRootState.Unreadable(_)) => true
            case (a, e)                                                           => a == e
          }
        Result
          .assert(f.installRoots.length == DriftScan.installRoots.length)
          .log(s"installRoots length: ${f.installRoots.length} != ${DriftScan.installRoots.length}")
          .and(Result.assert(pathOk).log("install root paths differ from the searched roots"))
          .and(Result.assert(stateOk).log(s"root states differ: ${f.installRoots.map(_.state)}"))

      val changesR: Result =
        val activeNames: Set[String] =
          shape.changes.filterNot(_.archived).map(_.name).toSet
        f.activeChanges match
          case FactRead.Present(changes) =>
            val namesOk: Boolean = changes.map(_.name).toSet == activeNames
            val perChangeOk: Boolean = changes.forall { ac =>
              shape.changes.find(_.name == ac.name) match
                case None => false
                case Some(cs) =>
                  val artsOk: Boolean =
                    (ac.artifacts, expectedArtifacts(cs, shape.schema)) match
                      case (FactRead.Present(a), FactRead.Present(e))       => a == e
                      case (FactRead.Absent, FactRead.Absent)               => true
                      case (FactRead.Unreadable(_), FactRead.Unreadable(_)) => true
                      case _                                                => false
                  val chainOk: Boolean = ac.chainState match
                    case Left(_) => !chainIsMeasured(shape.chainStub)
                    case Right(r) =>
                      shape.chainStub match
                        case ChainStub.Report(n, e) if e == 0 || e == 1 =>
                          r.unresolved.length == n
                        case _ => false
                  artsOk && chainOk
            }
            Result
              .assert(namesOk)
              .log(s"active change names ${changes.map(_.name)} != $activeNames")
              .and(Result.assert(perChangeOk).log(s"per-change facts differ: $changes"))
          case FactRead.Absent =>
            Result
              .assert(shape.changes.isEmpty)
              .log(s"changes Absent but shape materialised change dirs: $activeNames")
          case FactRead.Unreadable(_) =>
            Result
              .assert(false)
              .log("changes dir exists but could not be listed in a materialised fixture")

      schemaR.and(registryR).and(inventoryR).and(profileR).and(rootsR).and(changesR)
    }

  // ── Pinpoint scenario: an archived change is never reported active ────
  // spec: live-fact-banner — Scenario: Adversarial — an archived change is not reported as active
  test("an archived change is never reported as active"):
    val shape: RepoShape = RepoShape(
      schema = SchemaShape.Versioned(14),
      registry = RegistryShape.Absent,
      inventory = InventoryShape.Absent,
      profile = ProfileShape.Absent,
      rootDocs = List.fill(DriftScan.installRoots.length)(RootDocShape.Absent),
      changes = List(
        ChangeShape("live-change", Set("proposal"), archived = false),
        ChangeShape("dead-change", Set("proposal", "design"), archived = true)
      ),
      chainStub = ChainStub.Absent
    )
    withMaterialised(shape) { (m: Materialised) =>
      val f: RepositoryFacts = RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
      f.activeChanges match
        case FactRead.Present(changes) =>
          assertEquals(changes.map(_.name), List("live-change"))
        case other => fail(s"expected Present(activeChanges), got $other")
    }

  // ── Pinpoint scenario: a registry that exists but cannot be read ──────
  // spec: live-fact-banner — Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent
  test("a registry directory that cannot be read is Unreadable, not Absent"):
    withMaterialised(
      RepoShape(
        schema = SchemaShape.Absent,
        registry = RegistryShape.Docs(3),
        inventory = InventoryShape.Absent,
        profile = ProfileShape.Absent,
        rootDocs = List.fill(DriftScan.installRoots.length)(RootDocShape.Absent),
        changes = Nil,
        chainStub = ChainStub.Absent
      )
    ) { (m: Materialised) =>
      val conceptsDir: Path = m.repoRoot.resolve("openspec/concepts")
      conceptsDir.toFile.setReadable(false, false)
      conceptsDir.toFile.setExecutable(false, false)
      bracket(
        conceptsDir,
        { (d: Path) =>
          d.toFile.setReadable(true, false)
          d.toFile.setExecutable(true, false)
        }
      ) { (_: Path) =>
        val f: RepositoryFacts = RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
        f.registry match
          case FactRead.Unreadable(_) => ()
          case other                  => fail(s"expected Unreadable registry, got $other")
      }
    }

  // ── Pinpoint scenario: an unlistable changes dir is never an empty list ─
  // spec: live-fact-banner — SHALL NOT report an empty active-change list for a
  // repository that has active changes; a listing failure IS the fact (Unreadable)
  test("a changes directory that cannot be listed is Unreadable, never an empty list"):
    withMaterialised(
      RepoShape(
        schema = SchemaShape.Absent,
        registry = RegistryShape.Absent,
        inventory = InventoryShape.Absent,
        profile = ProfileShape.Absent,
        rootDocs = List.fill(DriftScan.installRoots.length)(RootDocShape.Absent),
        changes = List(ChangeShape("live-change", Set("proposal"), archived = false)),
        chainStub = ChainStub.Absent
      )
    ) { (m: Materialised) =>
      val changesDir: Path = m.repoRoot.resolve("openspec/changes")
      changesDir.toFile.setReadable(false, false)
      changesDir.toFile.setExecutable(false, false)
      bracket(
        changesDir,
        { (d: Path) =>
          d.toFile.setReadable(true, false)
          d.toFile.setExecutable(true, false)
        }
      ) { (_: Path) =>
        val f: RepositoryFacts = RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
        f.activeChanges match
          case FactRead.Unreadable(_) => ()
          case other                  => fail(s"expected Unreadable activeChanges, got $other")
      }
    }

  // ── Pinpoint scenario: registry count is recursive and excludes README ─
  // spec: live-fact-banner — Scenario: Happy path — a present registry is reported present with its exact count
  test("registry count is recursive and never counts README.md"):
    withMaterialised(
      RepoShape(
        schema = SchemaShape.Absent,
        registry = RegistryShape.Docs(0),
        inventory = InventoryShape.Absent,
        profile = ProfileShape.Absent,
        rootDocs = List.fill(DriftScan.installRoots.length)(RootDocShape.Absent),
        changes = Nil,
        chainStub = ChainStub.Absent
      )
    ) { (m: Materialised) =>
      val nested: Path = m.repoRoot.resolve("openspec/concepts/sub/deep")
      Files.createDirectories(nested)
      Files.write(nested.resolve("inner.md"), "x\n".getBytes)
      Files.write(nested.resolve("README.md"), "x\n".getBytes)
      val f: RepositoryFacts = RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
      assertEquals(f.registry, FactRead.Present(1))
    }

  // ── Pinpoint: the registry counts only .md regular files ──────────────
  // spec: live-fact-banner — Scenario: Happy path — a present registry is reported present with its exact count
  test("the registry counts only .md regular files — not .txt, not README.md, not directories"):
    withTempDir("reg-repo") { (repo: Path) =>
      val concepts: Path = repo.resolve("openspec/concepts")
      Files.createDirectories(concepts)
      Files.writeString(concepts.resolve("a.md"), "x\n")
      Files.writeString(concepts.resolve("b.txt"), "x\n")
      Files.writeString(concepts.resolve("README.md"), "x\n")
      Files.createDirectories(concepts.resolve("d.md")) // a directory named *.md
      val f: RepositoryFacts = RepositoryFactsReader.read(repo, repo, Map.empty)
      assertEquals(f.registry, FactRead.Present(1))
    }

  // ── Pinpoint: the inventory counts first cells of pipe-rows only ──────
  // spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
  test("the inventory counts first cells of pipe-rows only — a non-pipe row contributes nothing"):
    withTempDir("inv-repo") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec"))
      Files.writeString(
        repo.resolve("openspec/concept-inventory.md"),
        "| Type | File |\n" +
          "|------|------|\n" +
          "| Foo | a.scala |\n" +
          "junk | Bar | b.scala |\n" +
          "| `Baz[T]` | c.scala |\n"
      )
      val f: RepositoryFacts = RepositoryFactsReader.read(repo, repo, Map.empty)
      assertEquals(f.inventory, FactRead.Present(2))
    }

  // ── Pinpoint: unreadable facts carry their reason ────────────────────
  // spec: live-fact-banner — Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent
  test("an unreadable registry reason names the scan failure"):
    withTempDir("reg-unreadable") { (repo: Path) =>
      val concepts: Path = repo.resolve("openspec/concepts")
      Files.createDirectories(concepts)
      Files.writeString(concepts.resolve("c.md"), "x\n")
      concepts.toFile.setReadable(false, false)
      concepts.toFile.setExecutable(false, false)
      bracket(
        concepts,
        { (d: Path) =>
          d.toFile.setReadable(true, false)
          d.toFile.setExecutable(true, false)
        }
      ) { (_: Path) =>
        RepositoryFactsReader.read(repo, repo, Map.empty).registry match
          case FactRead.Unreadable(r) =>
            assert(r.contains("could not scan"), s"the reason must name the failure: $r")
          case other => fail(s"expected Unreadable registry, got $other")
      }
    }

  test("an unlistable changes directory is Unreadable and names the listing failure"):
    withTempDir("chg-unreadable") { (repo: Path) =>
      val changesDir: Path = repo.resolve("openspec/changes")
      Files.createDirectories(changesDir.resolve("c1"))
      changesDir.toFile.setReadable(false, false)
      changesDir.toFile.setExecutable(false, false)
      bracket(
        changesDir,
        { (d: Path) =>
          d.toFile.setReadable(true, false)
          d.toFile.setExecutable(true, false)
        }
      ) { (_: Path) =>
        RepositoryFactsReader.read(repo, repo, Map.empty).activeChanges match
          case FactRead.Unreadable(r) =>
            assert(r.contains("could not list"), s"the reason must name the failure: $r")
          case other => fail(s"expected Unreadable activeChanges, got $other")
      }
    }

  test("an unreadable install-root document is Unreadable and names the read failure"):
    withTempDir("doc-unreadable") { (repo: Path) =>
      val doc: Path =
        repo.resolve(".agents/skills/openspec-spec-lint/SKILL.md")
      Files.createDirectories(doc.getParent)
      Files.writeString(doc, "---\nname: x\n---\n")
      doc.toFile.setReadable(false, false)
      bracket(doc, (d: Path) => d.toFile.setReadable(true, false)) { (_: Path) =>
        val f: RepositoryFacts = RepositoryFactsReader.read(repo, repo, Map.empty)
        f.installRoots.map(_.state).headOption match
          case Some(InstallRootState.Unreadable(r)) =>
            assert(r.contains("could not read"), s"the reason must name the failure: $r")
          case other => fail(s"expected Unreadable repoAgents root state, got $other")
      }
    }

  // ── Pinpoint: schema version parsing ─────────────────────────────────
  // spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
  test("a version field whose value carries a second colon is unreadable, and the reason says why"):
    withTempDir("schema-colon") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/schemas/verified-scala3"))
      Files.writeString(
        repo.resolve("openspec/schemas/verified-scala3/schema.yaml"),
        "version: x:14\n"
      )
      RepositoryFactsReader.read(repo, repo, Map.empty).schemaVersion match
        case FactRead.Unreadable(r) =>
          assert(r.contains("not a number"), s"the reason must name the failure: $r")
        case other => fail(s"expected Unreadable schemaVersion, got $other")
    }

  // ── Pinpoint: the artifact DAG — block boundaries + generates quoting ─
  // spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
  test("the artifact DAG ends at the next top-level key, ignores pre-block lines, and strips generates quotes"):
    withTempDir("dag-repo") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/schemas/verified-scala3"))
      Files.writeString(
        repo.resolve("openspec/schemas/verified-scala3/schema.yaml"),
        "# schema fixture\n" +
          "  - id: stray\n" +
          "    generates: stray.md\n" +
          "version: 14\n" +
          "meta:\n" +
          "  - id: inside-meta\n" +
          "    generates: inside-meta.md\n" +
          "artifacts:\n" +
          "    generates: ghost.md\n" +
          "  - id: proposal\n" +
          "    generates: \"proposal.md\"\n" +
          "otherkey: x\n" +
          "  - id: after\n" +
          "    generates: after.md\n"
      )
      val chg: Path = repo.resolve("openspec/changes/c1")
      Files.createDirectories(chg)
      Files.writeString(chg.resolve("proposal.md"), "x\n")
      Files.writeString(chg.resolve("after.md"), "x\n")
      val f: RepositoryFacts = RepositoryFactsReader.read(repo, repo, Map.empty)
      f.activeChanges match
        case FactRead.Present(List(c)) =>
          c.artifacts match
            case FactRead.Present(scan) =>
              assertEquals(
                scan.present,
                List("proposal"),
                "only DAG artifacts inside the artifacts: block are scanned"
              )
              assertEquals(
                scan.next,
                Option(ArtifactRef("", "ghost.md")),
                "an orphan generates: line binds the empty id, and lines outside the block never enter the DAG"
              )
            case other => fail(s"expected Present artifacts, got $other")
        case other => fail(s"expected one active change, got $other")
    }

  test("a non-generates line inside the artifacts block contributes no artifact"):
    withTempDir("dag-extra") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/schemas/verified-scala3"))
      Files.writeString(
        repo.resolve("openspec/schemas/verified-scala3/schema.yaml"),
        "version: 14\n" +
          "artifacts:\n" +
          "  - id: proposal\n" +
          "    generates: proposal.md\n" +
          "    applies: always\n"
      )
      val chg: Path = repo.resolve("openspec/changes/c1")
      Files.createDirectories(chg)
      Files.writeString(chg.resolve("proposal.md"), "x\n")
      RepositoryFactsReader.read(repo, repo, Map.empty).activeChanges match
        case FactRead.Present(List(c)) =>
          c.artifacts match
            case FactRead.Present(scan) =>
              assertEquals(
                scan.next,
                Option.empty[ArtifactRef],
                "an indented non-generates line must not become a phantom artifact"
              )
            case other => fail(s"expected Present artifacts, got $other")
        case other => fail(s"expected one active change, got $other")
    }

  // ── Pinpoint: the chain-state seam ────────────────────────────────────

  /** Write an executable stub script; returns its path. */
  private def writeStub(path: Path, body: String): Path =
    Files.createDirectories(path.getParent)
    Files.writeString(path, "#!/usr/bin/env bash\n" + body, StandardCharsets.UTF_8)
    path.toFile.setExecutable(true)
    path

  /** A minimal repo containing exactly one active change, `c1`. */
  private def repoWithChange(tmp: Path): Path =
    val repo: Path = tmp.resolve("repo")
    Files.createDirectories(repo.resolve("openspec/changes/c1"))
    repo

  /** The chain-state result of the single active change `c1`. */
  private def chainOf(
    f: RepositoryFacts
  ): Either[ChainStateUndetermined, ChainStateReport] =
    f.activeChanges match
      case FactRead.Present(List(c)) =>
        assertEquals(c.name, "c1")
        c.chainState
      case other => fail(s"expected exactly one active change, got $other")

  // spec: live-fact-banner — Requirement: Active-change facts carry live chain-state, not a placeholder
  test(
    "the chain-state tool is invoked with the predecessor's argument order, and the report's fields come from the report"
  ):
    withTempDir("chain-args") { (tmp: Path) =>
      val repo: Path = repoWithChange(tmp)
      val stub: Path = writeStub(
        tmp.resolve("check-args.sh"),
        "[ \"$1\" = \"--change-dir\" ] || exit 90\n" +
          "[ \"$3\" = \"--change\" ] || exit 91\n" +
          "[ \"$4\" = \"c1\" ] || exit 92\n" +
          "[ \"$5\" = \"--baseline\" ] || exit 93\n" +
          "printf '%s' '{\"change\":\"reported-name\",\"baseline\":\"rep-base\",\"total\":2," +
          "\"bound\":2,\"resolved\":2,\"discharged\":1,\"unresolved\":[" +
          "{\"spec\":\"s\",\"requirement\":\"R\",\"reasons\":[\"unbound\"]}]," +
          "\"unmapped_obligations\":[{\"spec\":\"s\",\"line\":3,\"artifact\":\"a.md\"}]}'\n"
      )
      val f: RepositoryFacts =
        RepositoryFactsReader.read(repo, repo, Map("CHAIN_STATE_OVERRIDE" -> stub.toString))
      chainOf(f) match
        case Right(r) =>
          assertEquals(r.change, "reported-name")
          assertEquals(r.baseline, "rep-base")
          assertEquals(r.unresolved.length, 1)
          assertEquals(r.unmappedObligations, List(UnmappedObligation("s", 3, "a.md")))
        case Left(u) =>
          fail(s"a measured report must not be undetermined: ${u.reason}")
    }

  test("a tool exiting 1 with a valid report is still a measurement"):
    withTempDir("chain-exit1") { (tmp: Path) =>
      val repo: Path = repoWithChange(tmp)
      val stub: Path = writeStub(
        tmp.resolve("exit1.sh"),
        "printf '%s' '{\"change\":\"c1\",\"baseline\":\"b\",\"total\":1,\"bound\":1," +
          "\"resolved\":1,\"discharged\":1,\"unresolved\":[],\"unmapped_obligations\":[]}'\n" +
          "exit 1\n"
      )
      val f: RepositoryFacts =
        RepositoryFactsReader.read(repo, repo, Map("CHAIN_STATE_OVERRIDE" -> stub.toString))
      chainOf(f) match
        case Right(r) => assertEquals(r.total, 1)
        case Left(u)  => fail(s"exit 1 with a valid report is a measurement, got ${u.reason}")
    }

  test("a failing tool that prints a valid report is undetermined, and the reason names the exit code"):
    withTempDir("chain-exit2") { (tmp: Path) =>
      val repo: Path = repoWithChange(tmp)
      val stub: Path = writeStub(
        tmp.resolve("exit2.sh"),
        "printf '%s' '{\"change\":\"c1\",\"baseline\":\"b\",\"total\":1,\"bound\":1," +
          "\"resolved\":1,\"discharged\":1,\"unresolved\":[],\"unmapped_obligations\":[]}'\n" +
          "exit 2\n"
      )
      val f: RepositoryFacts =
        RepositoryFactsReader.read(repo, repo, Map("CHAIN_STATE_OVERRIDE" -> stub.toString))
      chainOf(f) match
        case Left(u) =>
          assert(
            u.reason.contains("chain-state.sh exit 2"),
            s"the reason must name the tool's exit code: ${u.reason}"
          )
        case Right(r) => fail(s"a failed tool must not produce a report: $r")
    }

  test("a report with a non-numeric total is undetermined with the exit-code reason"):
    withTempDir("chain-badtotal") { (tmp: Path) =>
      val repo: Path = repoWithChange(tmp)
      val stub: Path = writeStub(
        tmp.resolve("badtotal.sh"),
        "printf '%s' '{\"change\":\"c1\",\"baseline\":\"b\",\"total\":\"x\",\"bound\":1," +
          "\"resolved\":1,\"discharged\":1,\"unresolved\":[],\"unmapped_obligations\":[]}'\n"
      )
      val f: RepositoryFacts =
        RepositoryFactsReader.read(repo, repo, Map("CHAIN_STATE_OVERRIDE" -> stub.toString))
      chainOf(f) match
        case Left(u) =>
          assert(
            u.reason.contains("chain-state.sh exit 0"),
            s"the predecessor names the tool's exit code even for exit 0: ${u.reason}"
          )
        case Right(r) => fail(s"a non-numeric total must not produce a report: $r")
    }

  test("a missing chain-state tool is undetermined with the exit-127 reason"):
    withTempDir("chain-missing") { (tmp: Path) =>
      val repo: Path         = repoWithChange(tmp)
      val f: RepositoryFacts = RepositoryFactsReader.read(repo, repo, Map.empty)
      chainOf(f) match
        case Left(u) =>
          assert(
            u.reason.contains("chain-state.sh exit 127"),
            s"a missing tool is exit 127, matching the predecessor: ${u.reason}"
          )
        case Right(r) => fail(s"no tool must not produce a report: $r")
    }

  test("a report without change or baseline fields falls back to the invocation name and 'unknown'"):
    withTempDir("chain-fields") { (tmp: Path) =>
      val repo: Path = repoWithChange(tmp)
      val stub: Path = writeStub(
        tmp.resolve("nofields.sh"),
        "printf '%s' '{\"total\":1,\"bound\":1,\"resolved\":1,\"discharged\":1," +
          "\"unresolved\":[],\"unmapped_obligations\":[]}'\n"
      )
      val f: RepositoryFacts =
        RepositoryFactsReader.read(repo, repo, Map("CHAIN_STATE_OVERRIDE" -> stub.toString))
      chainOf(f) match
        case Right(r) =>
          assertEquals(r.change, "c1")
          assertEquals(r.baseline, "unknown")
        case Left(u) => fail(s"a valid report must not be undetermined: ${u.reason}")
    }

  // ── Pinpoint: the baseline is the git HEAD, or "unknown" ─────────────
  // spec: live-fact-banner — Requirement: Active-change facts carry live chain-state, not a placeholder
  test("the chain-state baseline is the git HEAD inside a repository and 'unknown' outside one"):
    // A materialised repo is not a git repository — the baseline degrades.
    withMaterialised(
      RepoShape(
        schema = SchemaShape.Absent,
        registry = RegistryShape.Absent,
        inventory = InventoryShape.Absent,
        profile = ProfileShape.Absent,
        rootDocs = List.fill(DriftScan.installRoots.length)(RootDocShape.Absent),
        changes = List(ChangeShape("c1", Set("proposal"), archived = false)),
        chainStub = ChainStub.Absent
      )
    ) { (m: Materialised) =>
      val f: RepositoryFacts = RepositoryFactsReader.read(m.repoRoot, m.userHome, m.env)
      f.activeChanges match
        case FactRead.Present(List(c)) =>
          c.chainState match
            case Left(u)    => assertEquals(u.baseline, "unknown")
            case Right(rep) => fail(s"expected undetermined without a tool, got $rep")
        case other => fail(s"expected one active change, got $other")
    }
    // Inside a real git repo the baseline is the HEAD commit.
    withTempDir("git-baseline") { (repo: Path) =>
      Files.createDirectories(repo.resolve("openspec/changes/c1"))
      assertEquals(git(repo, "init", "-q")._1, 0, "git init")
      git(repo, "config", "user.email", "t@t")
      git(repo, "config", "user.name", "t")
      Files.writeString(repo.resolve("openspec/changes/c1/proposal.md"), "x\n")
      git(repo, "add", "-A")
      assertEquals(git(repo, "commit", "-q", "-m", "init")._1, 0, "git commit")
      val (_, sha)           = git(repo, "rev-parse", "HEAD")
      val f: RepositoryFacts = RepositoryFactsReader.read(repo, repo, Map.empty)
      f.activeChanges match
        case FactRead.Present(List(c)) =>
          c.chainState match
            case Left(u)    => assertEquals(u.baseline, sha)
            case Right(rep) => assertEquals(rep.baseline, "unknown") // report's own field
        case other => fail(s"expected one active change, got $other")
    }

end RepositoryFactsSpec
