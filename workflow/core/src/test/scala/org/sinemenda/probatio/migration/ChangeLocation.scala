package org.sinemenda.probatio.migration

import hedgehog.Gen
import hedgehog.Range

/**
 * `ChangeLocation` — where a named change's directory is (spec 4,
 * archive-safe-fixtures).
 *
 * Archiving a change moves its directory from `openspec/changes/<name>` to
 * `openspec/changes/archive/<date>-<name>` (or a bare `archive/<name>`).
 * A test that names a change's directory by its active-area path breaks the
 * moment the change is archived — the reported defect: the recorded
 * predecessor control of `repair-probatio-cutover` read fine until the
 * change was archived, and `DifferentialHarnessSpec`'s well-formedness
 * check failed on a fixture that was present the whole time, under the
 * archive.
 *
 * `ChangeLocation.resolve` is the shared resolver — the generalisation of
 * the feature-freeze guard's corpus resolver (`FixtureCorpus.resolve` in
 * `org.sinemenda.probatio.guard`), lifted from "the specs dir of a corpus"
 * to "the directory of a change". It is the ONLY way test code locates a
 * change's artifacts: a reader resolves the change by name, then reads its
 * fixture under the resolved directory.
 *
 * `Absent(searched)` is a required-field carrier, not a bare flag — an
 * absence that cannot say where it looked is unconstructible (the same
 * shape as the guard's `CorpusResolution.NotFound`).
 *
 * spec: archive-safe-fixtures — Concepts Introduced (new): ChangeLocation
 * spec: archive-safe-fixtures — Requirement: Test code locates a change through the archive-aware resolver
 */
enum ChangeLocation:

  /** The change is in the active area: `dir = openspecDir/changes/<name>`. */
  case Active(dir: os.Path)

  /**
   * The change is in the archive:
   * `dir = openspecDir/changes/archive/<date>-<name>`. A bare
   * `archive/<name>` entry carries no date prefix, so `date` is an
   * `Option` — an empty-string date would be a representable
   * invalid state.
   */
  case Archived(dir: os.Path, date: Option[String])

  /**
   * The change is in neither place. `searched` names every location that
   * was probed — the active change directory and the archive root — so an
   * unlocatable fixture is always diagnosable.
   *
   * spec: archive-safe-fixtures — Compile-Negative: An absent location that names no searched place
   */
  case Absent(searched: List[os.Path])

  /** The located change directory, when the change was found. */
  def foundDir: Option[os.Path] = this match
    case Active(d)      => Some(d)
    case Archived(d, _) => Some(d)
    case Absent(_)      => None

  /** True iff the change was located (active or archived). */
  def isResolved: Boolean = foundDir.nonEmpty

  /** True iff the change was found in neither place. */
  def isAbsent: Boolean = foundDir.isEmpty

object ChangeLocation:

  /**
   * Every location `resolve` probes for `changeName`, in search order:
   * the active change directory, the archive root, then each archive
   * entry matching the name. The list is computed from the same
   * arguments as `resolve`, so a resolution — found or absent — always
   * names where it looked: an archive that matched twice names both.
   *
   * spec: archive-safe-fixtures — Scenario: Adversarial — a change in neither place is not silently accepted
   * spec: archive-safe-fixtures — Scenario: Edge case — a change archived more than once resolves to the latest
   */
  def searchedLocations(changeName: String, openspecDir: os.Path): List[os.Path] = ???

  /**
   * Locate the change `changeName` under `openspecDir`: the active
   * directory wins when the name is present in both areas; among archive
   * matches the most recent date wins; a name present in neither place
   * resolves to `Absent` carrying `searchedLocations`.
   *
   * spec: archive-safe-fixtures — Requirement: Test code locates a change through the archive-aware resolver
   */
  def resolve(changeName: String, openspecDir: os.Path): ChangeLocation = ???

/**
 * Generators and materialisation helpers for the spec-4 oracle —
 * `resolution-is-location-independent` and
 * `absent-names-every-searched-location`.
 *
 * Everything is constructive: sought names and sibling names come from
 * disjoint alphabets, and double-archived date pairs are generated
 * strictly ordered — no filtering.
 *
 * spec: archive-safe-fixtures — Property: resolution-is-location-independent (generator strategy)
 * spec: archive-safe-fixtures — Property: absent-names-every-searched-location (generator strategy)
 */
object ChangeLocationGens:

  /** Where a generated change directory is placed inside a repository. */
  enum Placement:
    /** `changes/<name>`. */
    case InActiveArea
    /** `changes/archive/<date>-<name>`. */
    case ArchivedOnce(date: String)
    /** `changes/archive/<older>-<name>` and `changes/archive/<newer>-<name>`. */
    case ArchivedTwice(olderDate: String, newerDate: String)

  /**
   * A generated change placement: the change name, the fixture list
   * written at the location `resolve` must land on, the independently
   * generated fixture list written at the OLDER archive location when
   * the change is twice-archived (so latest-wins is observable at the
   * data level, not only in the returned path), and the placement.
   */
  final case class ChangePlacement(
    changeName: String,
    fixtures: List[String],
    olderArchiveFixtures: List[String],
    placement: Placement
  )

  /**
   * A generated absence: a sought name plus sibling change directories
   * the repository holds instead — each a `(name, archived)` pair. The
   * sought name is never placed; a `pre-<name>` archive sibling, when
   * generated, makes the absent case adversarial for a suffix-matching
   * resolver.
   */
  final case class AbsentFixture(
    name: String,
    others: List[(String, Boolean)]
  )

  private val genChangeName: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(3, 9)).map((n: String) => s"change-$n")

  private val genSubdir: Gen[String] =
    Gen.element("specs", List("fixtures", "nested", "docs"))

  private val genFileName: Gen[String] =
    Gen.element("spec.md", List("control.json", "a.txt", "notes.md"))

  /**
   * A fixture list of size 1–6: `<subdir>-<i>/<file>` relative paths —
   * the index suffix keeps paths unique while the small file-name
   * alphabet generates the same file name under different
   * subdirectories.
   */
  private val genFixtureList: Gen[List[String]] =
    for
      n     <- Gen.int(Range.linear(1, 6))
      dirs  <- genSubdir.list(Range.singleton(n))
      files <- genFileName.list(Range.singleton(n))
    yield dirs.zipWithIndex.map((d, i) => s"$d-$i").zip(files).map((d, f) => s"$d/$f")

  private def fmtDate(year: Int, month: Int, day: Int): String =
    f"$year%04d-$month%02d-$day%02d"

  private def genDate(yearLo: Int, yearHi: Int): Gen[String] =
    for
      y <- Gen.int(Range.linear(yearLo, yearHi))
      m <- Gen.int(Range.linear(1, 12))
      d <- Gen.int(Range.linear(1, 28))
    yield fmtDate(y, m, d)

  /**
   * A change name, a fixture list of size 1–6, and a placement drawn
   * from {active, archived once, archived twice under different dates}.
   * The twice-archived dates are strictly ordered by construction —
   * the older year range never overlaps the newer's.
   */
  val genChangePlacement: Gen[ChangePlacement] =
    for
      name      <- genChangeName
      fixtures  <- genFixtureList
      older     <- genFixtureList
      olderDate <- genDate(2020, 2024)
      newerDate <- genDate(2025, 2028)
      placement <- Gen.element(
        Placement.InActiveArea,
        List(
          Placement.ArchivedOnce(newerDate),
          Placement.ArchivedTwice(olderDate, newerDate)
        )
      )
    yield ChangePlacement(name, fixtures, older, placement)

  /**
   * A sought name that is never placed, plus 1–3 sibling changes (each
   * active or dated-archived) built from a disjoint alphabet — no
   * filtering. Half the draws also place `pre-<name>` as an archive
   * sibling: `pre-<name>` ends with `-<name>`, so a resolver that
   * matches on suffix would resolve a change that is not there.
   */
  val genAbsentName: Gen[AbsentFixture] =
    for
      name      <- Gen.string(Gen.alphaNum, Range.linear(3, 8)).map((n: String) => s"want-$n")
      n         <- Gen.int(Range.linear(1, 3))
      siblings  <- Gen
        .string(Gen.alphaNum, Range.linear(3, 8))
        .map((s: String) => s"other-$s")
        .list(Range.singleton(n))
      archived  <- Gen.boolean.list(Range.singleton(n))
      collision <- Gen.boolean
      others  =
        siblings.zip(archived) ++
          (if collision then List(s"pre-$name" -> true) else Nil)
    yield AbsentFixture(name, others)

  /** Write `relPaths` as files under `dir` (content = the relative path). */
  def writeFixtures(dir: os.Path, relPaths: List[String]): Unit =
    relPaths.foreach { (rel: String) =>
      val target: os.Path = dir / os.RelPath(rel)
      os.makeDir.all(target / os.up)
      os.write.over(target, rel)
    }

  /**
   * Materialise `p` inside `openspecDir`'s `changes/` tree; returns the
   * fixture list the resolved location must hold — the list written at
   * the newest archive location when the change is twice-archived.
   */
  def place(openspecDir: os.Path, p: ChangePlacement): List[String] =
    val archiveRoot: os.Path = openspecDir / "changes" / "archive"
    p.placement match
      case Placement.InActiveArea =>
        writeFixtures(openspecDir / "changes" / p.changeName, p.fixtures)
        p.fixtures
      case Placement.ArchivedOnce(date) =>
        writeFixtures(archiveRoot / s"$date-${p.changeName}", p.fixtures)
        p.fixtures
      case Placement.ArchivedTwice(older, newer) =>
        writeFixtures(archiveRoot / s"$older-${p.changeName}", p.olderArchiveFixtures)
        writeFixtures(archiveRoot / s"$newer-${p.changeName}", p.fixtures)
        p.fixtures

  /**
   * Materialise `f`'s sibling changes inside `openspecDir` — each an
   * active or dated-archive entry holding one fixture. `f.name` itself
   * is never placed.
   */
  def placeAbsentFixture(openspecDir: os.Path, f: AbsentFixture): Unit =
    f.others.foreach { (other: String, archived: Boolean) =>
      val dir: os.Path =
        if archived then openspecDir / "changes" / "archive" / s"2026-01-01-$other"
        else openspecDir / "changes" / other
      writeFixtures(dir, List("specs/spec.md"))
    }

  /** `f` runs with a fresh temporary `openspec/` root, removed afterwards. */
  def withTempOpenspec[A](f: os.Path => A): A =
    val tmp: os.Path      = os.temp.dir(prefix = "changeloc")
    val openspec: os.Path = tmp / "openspec"
    os.makeDir.all(openspec / "changes" / "archive")
    try f(openspec)
    finally os.remove.all(tmp) // scalafix:ok DisableSyntax.NoKeywordFinally
