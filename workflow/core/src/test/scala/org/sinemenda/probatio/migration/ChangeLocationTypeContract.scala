package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Typed contract for archive-safe fixtures (spec 4 of
 * `finish-probatio-replacement`, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath,
 * `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `ChangeLocation` is a closed enum of three variants — `Active(dir)`,
 *    `Archived(dir, date)`, `Absent(searched)` — the sealing is the
 *    constraint: a resolved change is in exactly one place, and an
 *    unresolved one always carries where it looked.
 *  - `Archived.date` is an `Option[String]` — the archive's bare-name
 *    convention (`archive/<name>`, which the corpus matcher already
 *    accepts) carries no date prefix, and an empty-string date would be
 *    a representable invalid state.
 *  - `Absent(searched)` requires the searched list — an absence that
 *    cannot say where it looked is unconstructible (the compile-negative
 *    obligation lands in `FeatureFreezeGuardIntegrityTypeContract`,
 *    per the spec's proof-obligations table).
 *  - `resolve(changeName, openspecDir)` is the shared resolver — the
 *    generalisation of the feature-freeze guard's corpus resolver to the
 *    change-directory level. `searchedLocations` computes the probe list
 *    for the same arguments, so every resolution — found or absent —
 *    can name where it looked.
 *  - `CorpusResolution` and `FixtureCorpus.resolve` keep their shapes:
 *    the generalisation is delegation — the corpus resolver reads the
 *    resolved location's `specs/` instead of scanning locations itself.
 *    The guard's verdict algebra (`guardOutcome`, `FeatureFreezeVerdict`)
 *    is untouched; "the guard keeps its behaviour" is preserved
 *    literally. The one ordering change is spec-pinned: among multiple
 *    archive entries the LATEST resolves, replacing the pre-existing
 *    oldest-first directory probe.
 *
 * spec: archive-safe-fixtures — Step 1: typed contract (minimal — the resolver and its verdict type)
 * spec: archive-safe-fixtures — Concepts Introduced (new): ChangeLocation
 */
final class ChangeLocationTypeContract extends ProbatioSuite:

  // ── ChangeLocation — the three variants ─────────────────────────────
  // Active: os.Path => ChangeLocation
  val activeSig: os.Path => ChangeLocation =
    ChangeLocation.Active.apply

  // Archived: (os.Path, Option[String]) => ChangeLocation
  val archivedSig: (os.Path, Option[String]) => ChangeLocation =
    ChangeLocation.Archived.apply

  // Absent: List[os.Path] => ChangeLocation — the searched list is a
  // required constructor field, so an undiagnosable absence does not
  // compile.
  val absentSig: List[os.Path] => ChangeLocation =
    ChangeLocation.Absent.apply

  // ── The shared resolver ─────────────────────────────────────────────
  // resolve: (String, os.Path) => ChangeLocation
  val resolveSig: (String, os.Path) => ChangeLocation =
    ChangeLocation.resolve

  // searchedLocations: (String, os.Path) => List[os.Path]
  val searchedLocationsSig: (String, os.Path) => List[os.Path] =
    ChangeLocation.searchedLocations

  // ── Projections ─────────────────────────────────────────────────────
  val foundDirSig: ChangeLocation => Option[os.Path] =
    (l: ChangeLocation) => l.foundDir

  val isResolvedSig: ChangeLocation => Boolean =
    (l: ChangeLocation) => l.isResolved

  val isAbsentSig: ChangeLocation => Boolean =
    (l: ChangeLocation) => l.isAbsent

  // ── The pinned surface evaluates (no resolve — bodies are ???) ─────
  test("the three variants are distinguishable by their projections"):
    val active: ChangeLocation   = ChangeLocation.Active(os.pwd)
    val archived: ChangeLocation = ChangeLocation.Archived(os.pwd, Some("2026-09-25"))
    val absent: ChangeLocation   = ChangeLocation.Absent(List(os.pwd))
    assertEquals(active.foundDir, Some(os.pwd))
    assertEquals(archived.foundDir, Some(os.pwd))
    assertEquals(absent.foundDir, None)
    assert(active.isResolved && archived.isResolved && !absent.isResolved)
    assert(absent.isAbsent && !active.isAbsent && !archived.isAbsent)
