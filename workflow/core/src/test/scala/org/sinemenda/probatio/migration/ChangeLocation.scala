package org.sinemenda.probatio.migration

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
