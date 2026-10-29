package org.sinemenda.probatio.guard

import org.sinemenda.probatio.migration.ChangeLocation

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
   * Every location `resolve` probes for `changeName`'s corpus, in search
   * order: the shared archive-aware resolver's searched locations with
   * each located change directory refined to its `specs/` — the active
   * area's spec directory, the archive root (probed for a change
   * directory matching the name), then each matching archived change's
   * spec directory, newest first.
   *
   * spec: feature-freeze-guard-integrity — Scenario: Adversarial — a corpus present in neither location is not silently accepted
   * spec: archive-safe-fixtures — Requirement: Test code locates a change through the archive-aware resolver
   */
  def searchedLocations(changeName: String, openspecDir: os.Path): List[os.Path] =
    // `os.SubPath` runtime segments, not literal `/` segments: the os-lib
    // literal-path macro makes `""` StringLiteral mutants a COMPILE error,
    // which aborts the Ring-5 run (spec-1's debugging trail).
    val archiveRoot: os.Path = openspecDir / os.SubPath("changes/archive")
    ChangeLocation.searchedLocations(changeName, openspecDir).map { (loc: os.Path) =>
      if loc == archiveRoot then loc else loc / os.SubPath("specs")
    }

  /**
   * Locate the specification corpus for `changeName` under `openspecDir`.
   *
   * The shared resolver decides WHERE the change is (active first, then
   * the newest archive match); this layer reads that location's `specs/`.
   * A located change whose spec directory holds no fixtures does not
   * resolve — an empty corpus is not a corpus, and the resolved location
   * is authoritative rather than a fall-through to a staler archive
   * entry. When no corpus is found, `NotFound` names every location
   * searched.
   *
   * spec: feature-freeze-guard-integrity — Requirement: The guard locates its corpus wherever the change resides
   * spec: archive-safe-fixtures — Requirement: Test code locates a change through the archive-aware resolver
   */
  def resolve(changeName: String, openspecDir: os.Path): CorpusResolution =
    val searched: List[os.Path] = searchedLocations(changeName, openspecDir)
    ChangeLocation.resolve(changeName, openspecDir).foundDir match
      case None => CorpusResolution.NotFound(searched)
      case Some(dir) =>
        val specsDir: os.Path = dir / os.SubPath("specs")
        fixtureFiles(specsDir) match
          case Nil   => CorpusResolution.NotFound(searched)
          case specs => CorpusResolution.Resolved(new FixtureCorpus(specs, specsDir))

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
