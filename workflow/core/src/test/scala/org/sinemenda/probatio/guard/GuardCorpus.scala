package org.sinemenda.probatio.guard

/**
 * The feature-freeze guard's corpus types (spec 6,
 * feature-freeze-guard-integrity).
 *
 * The guard proves the port added no check and altered no verdict by
 * comparing, for every specification fixture in a corpus, the ported
 * spec-lint's verdict against the predecessor's. The corpus used to be read
 * from one hardcoded active-area path; archiving that change moved it, the
 * corpus read as empty, and the guard failed outright — a hard red where
 * the contract requires could-not-determine, and a shape that would pass
 * vacuously wherever the failure path is weaker.
 *
 * `FixtureCorpus` is the resolved corpus: a non-empty set of fixture paths
 * plus the directory they were found in. The constructor is private and the
 * only producer is `FixtureCorpus.resolve`, so a corpus that was never
 * located — or located empty — cannot be represented.
 *
 * `CorpusResolution` is the outcome of locating it: `Resolved` carries the
 * corpus; `NotFound` carries every location probed, so a not-found that
 * does not say where it looked is unconstructible.
 *
 * spec: feature-freeze-guard-integrity — Concepts Introduced (new): FixtureCorpus
 * spec: feature-freeze-guard-integrity — Concepts Introduced (new): CorpusResolution
 */

/**
 * A resolved fixture corpus: the specification fixtures the guard checks
 * verdict stability against, and where they live.
 *
 * `specs` are paths relative to `origin` (e.g. `spec-lint-engine/spec.md`),
 * so a corpus placed in the active area and the same corpus placed in the
 * archived area resolve to equal fixture sets — location independence is a
 * property of the fixtures, not of the directory that held them.
 *
 * A `final class`, not a case class: a private case-class constructor still
 * emits a PUBLIC `copy`, which would let a caller rebuild a resolved corpus
 * with `specs = Nil` — the exact empty-corpus hole this type exists to
 * close (the codebase's own convention, see `ArmTree` in `migration/ArmTypes`).
 *
 * spec: feature-freeze-guard-integrity — Compile-Negative: A fixture corpus built from an empty list
 */
final class FixtureCorpus private (
  val specs: List[String],
  val origin: os.Path
):
  /** The absolute path of one fixture. */
  def fixturePath(spec: String): os.Path = origin / os.RelPath(spec)

object FixtureCorpus:

  /**
   * The `spec.md` fixtures under `specsDir`, relative to it, sorted —
   * the corpus's location-independent identity. An unreadable or absent
   * directory yields no fixtures: never an exception, never a corpus.
   */
  private def fixtureFiles(specsDir: os.Path): List[String] =
    if !os.isDir(specsDir) then Nil
    else
      scala.util
        .Try(
          os.walk(specsDir)
            .filter((p: os.Path) => os.isFile(p) && p.last == "spec.md")
            .map((p: os.Path) => p.relativeTo(specsDir).toString)
            .toList
            .sorted
        )
        .getOrElse(Nil)

  /**
   * An archived change directory is `YYYY-MM-DD-<name>` — it matches when
   * its name equals `changeName` (a bare directory under `archive/`) or is
   * exactly `<date>-<changeName>`. The date prefix is required on the
   * dated arm: a suffix check would let `probatio-cutover` silently
   * resolve `…-complete-probatio-cutover`'s corpus.
   */
  private def matchesArchived(changeName: String, dirName: String): Boolean =
    dirName == changeName ||
      dirName.matches(s"\\d{4}-\\d{2}-\\d{2}-${java.util.regex.Pattern.quote(changeName)}")

  /**
   * Every location `resolve` probes for `changeName`'s corpus, in search
   * order: the active area's spec directory, the archive root (probed for a
   * change directory matching the name), then each matching archived
   * change's spec directory.
   *
   * spec: feature-freeze-guard-integrity — Scenario: Adversarial — a corpus present in neither location is not silently accepted
   */
  def searchedLocations(changeName: String, openspecDir: os.Path): List[os.Path] =
    // `os.SubPath` runtime segments, not literal `/` segments: the os-lib
    // literal-path macro makes `""` StringLiteral mutants a COMPILE error,
    // which aborts the Ring-5 run (spec-1's debugging trail).
    val activeSpecs: os.Path  = openspecDir / os.SubPath(s"changes/$changeName/specs")
    val archiveRoot: os.Path  = openspecDir / os.SubPath("changes/archive")
    val archived: List[os.Path] =
      if !os.isDir(archiveRoot) then Nil
      else
        scala.util
          .Try(
            os.list(archiveRoot)
              .filter((d: os.Path) => os.isDir(d) && matchesArchived(changeName, d.last))
              .map((d: os.Path) => d / os.SubPath("specs"))
              .toList
              .sortBy((p: os.Path) => p.toString)
          )
          .getOrElse(Nil)
    List(activeSpecs, archiveRoot) ++ archived

  /**
   * Locate the specification corpus for `changeName` under `openspecDir`.
   *
   * Probes `searchedLocations` in order and resolves to the first spec
   * directory containing at least one fixture; a directory that exists but
   * holds no fixtures does not resolve — an empty corpus is not a corpus.
   * When no probed location yields fixtures, `NotFound` names every
   * location searched.
   *
   * spec: feature-freeze-guard-integrity — Requirement: The guard locates its corpus wherever the change resides
   */
  def resolve(changeName: String, openspecDir: os.Path): CorpusResolution =
    val searched: List[os.Path] = searchedLocations(changeName, openspecDir)
    val candidates: List[os.Path] = searched.filter((p: os.Path) => p.last == "specs")
    candidates
      .map((d: os.Path) => d -> fixtureFiles(d))
      .collectFirst { case (d, specs) if specs.nonEmpty => new FixtureCorpus(specs, d) }
      match
        case Some(corpus) => CorpusResolution.Resolved(corpus)
        case None         => CorpusResolution.NotFound(searched)

/**
 * The outcome of locating a change's specification corpus.
 *
 * `NotFound` is a required-field carrier, not a bare flag: it cannot be
 * constructed without the list of locations searched, so an undiagnosable
 * not-found is unrepresentable.
 *
 * spec: feature-freeze-guard-integrity — Concepts Introduced (new): CorpusResolution
 * spec: feature-freeze-guard-integrity — Compile-Negative: A not-found resolution without the locations searched
 */
enum CorpusResolution:
  case Resolved(corpus: FixtureCorpus)
  case NotFound(searched: List[os.Path])

  /** True iff a corpus was located. */
  def isResolved: Boolean = this match
    case CorpusResolution.Resolved(_)  => true
    case CorpusResolution.NotFound(_)  => false

  /** True iff no probed location yielded a corpus. */
  def isNotFound: Boolean = !isResolved

  /** The resolved corpus, when one was located. */
  def corpusOption: Option[FixtureCorpus] = this match
    case CorpusResolution.Resolved(c) => Some(c)
    case CorpusResolution.NotFound(_) => None
