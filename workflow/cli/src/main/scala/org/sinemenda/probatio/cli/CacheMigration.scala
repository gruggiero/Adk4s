package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.CacheState
import org.sinemenda.probatio.core.SchemaPolicy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow fail-open — a migration that cannot run never fails the tool

/**
 * The caller `SchemaPolicy.migrateCache` never had (spec 10 of
 * `repair-probatio-cutover`).
 *
 * The kernel is pure: `CacheState => CacheState`. This adapter performs
 * the two impure halves — reading the on-disk state into a `CacheState`
 * and materialising the result. The kernel decides WHAT the migration
 * is; this file only translates between the filesystem and the model.
 *
 * Fail-open throughout: a directory that cannot be listed or a copy that
 * cannot complete degrades to no migration and no error — the tool's
 * verdicts are never hostage to a cache-directory rename.
 *
 * The state directory `<git-dir>/verified-scala3-gate` is NOT migrated —
 * its rename is part of the recorded deferral (`RenameDeferral`), not
 * this caller. The kernel's `CacheState` models the user cache pair only.
 *
 * spec: schema-rename-completion — Requirement: The cache and state directory migration runs on first use
 * spec: schema-rename-completion — Proof Obligation: The migration has a caller in the shipped tool
 */
object CacheMigration:

  /** The previous cache directory name under `$HOME/.cache/` (pre-rename). */
  val legacyDirName: String = "verified-scala3"

  /** The current cache directory name under `$HOME/.cache/`. */
  val currentDirName: String = "probatio"

  /**
   * Run the migration once for the environment's home directory.
   * `$HOME` is honoured over the JVM's `user.home` — the predecessor
   * scanned `$HOME`, so under an overridden HOME the cache must resolve
   * where the predecessor looked.
   */
  def migrateOnce(env: Map[String, String]): Unit =
    try migrateOnceAt(userHome(env))
    catch case NonFatal(_) => () // danger-scan:allow fail-open — a migration that cannot run never fails the tool

  /**
   * Snapshot the cache pair, run the kernel, materialise the decision.
   *
   * The kernel prescribes a filesystem effect only in one shape — legacy
   * present, current absent — so only then does this adapter write: the
   * current directory is created and the previous directory's top-level
   * regular files are copied in. Both-present keeps the current directory
   * untouched (existing contents win); neither-present performs no write
   * — a fresh environment migrates nothing.
   *
   * spec: schema-rename-completion — Scenario: Happy path — a previous directory is migrated once
   * spec: schema-rename-completion — Scenario: Edge case — a fresh environment with neither directory migrates nothing
   * spec: schema-rename-completion — Scenario: Adversarial — a previous directory is not migrated over an existing current one
   */
  private def migrateOnceAt(home: Path): Unit =
    val cacheRoot: Path = home.resolve(".cache")
    val legacy: Path    = cacheRoot.resolve(legacyDirName)
    val current: Path   = cacheRoot.resolve(currentDirName)
    snapshot(legacy, current) match
      case Some(before) =>
        val after: CacheState = SchemaPolicy.migrateCache(before)
        if before.legacyExists && !before.newExists then materialize(legacy, current, after.newDirContents)
      // an unreadable cache directory is could-not-determine, never empty —
      // deciding on a partial snapshot would latch a wrong terminal state
      case None => ()

  /** `$HOME`, else the JVM's `user.home` — the codebase's home convention. */
  private def userHome(env: Map[String, String]): Path =
    env
      .get("HOME")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(
        Paths.get(System.getProperty("user.home"))
      ) // danger-scan:allow home-fallback — user.home is the last resort when HOME is unset

  /**
   * Snapshot the cache pair for the kernel. `None` when either directory
   * exists but cannot be listed — the kernel decides only on complete
   * reads, so an unreadable input produces NO migration decision rather
   * than one computed on silently-emptied contents.
   */
  private def snapshot(legacy: Path, current: Path): Option[CacheState] =
    for
      legacyContents <- topLevelFiles(legacy)
      newContents    <- topLevelFiles(current)
    yield CacheState(
      legacyExists = Files.isDirectory(legacy),
      newExists = Files.isDirectory(current),
      legacyContents = legacyContents,
      newDirContents = newContents
    )

  /**
   * The names of the top-level regular files in `dir`, sorted — the
   * kernel's flat contents model. `Some(Nil)` for an absent directory;
   * `None` when the directory exists but the listing failed.
   */
  private def topLevelFiles(dir: Path): Option[List[String]] =
    if !Files.isDirectory(dir) then Some(List.empty[String])
    else
      try
        Some(
          Using.resource(Files.list(dir)) { (stream: java.util.stream.Stream[Path]) =>
            stream
              .iterator()
              .asScala
              .toList
              .filter((p: Path) => Files.isRegularFile(p))
              .map((p: Path) => p.getFileName.toString)
              .sorted
          }
        )
      // a failed listing is could-not-determine, never empty — it aborts the snapshot
      catch case NonFatal(_) => None // danger-scan:allow could-not-determine

  /**
   * Materialise the kernel's migrated state (`newDirContents =
   * legacyContents`): copy each listed legacy file into the current
   * directory.
   *
   * All-or-nothing: the copy runs in a `*.migrating` sibling and is
   * moved into place only when every file landed — `current` is never
   * observed partially populated, so a mid-copy failure cannot latch a
   * half-migrated state that `newExists` would then mask on the next
   * run. A name that is not a regular file in the legacy directory is
   * skipped, never invented.
   */
  private def materialize(legacy: Path, current: Path, contents: List[String]): Unit =
    val staging: Path = current.resolveSibling(current.getFileName.toString + ".migrating")
    deleteRecursively(staging) // a stale staging dir from an earlier crashed run
    Files.createDirectories(staging)
    try
      contents.foreach { (name: String) =>
        val src: Path = legacy.resolve(name)
        val dst: Path = staging.resolve(name)
        if Files.isRegularFile(src) then Files.copy(src, dst)
      }
      Files.move(staging, current)
    // `current` was never created, so absorbing here lets the next run retry
    catch case NonFatal(_) => deleteRecursively(staging) // danger-scan:allow fail-open

  /** Remove `dir` and everything under it, if present. */
  private def deleteRecursively(dir: Path): Unit =
    if Files.exists(dir) then
      Using.resource(Files.walk(dir)) { (stream: java.util.stream.Stream[Path]) =>
        stream
          .iterator()
          .asScala
          .toList
          .reverse // children before parents
          .foreach { (p: Path) =>
            val _ = Files.deleteIfExists(p)
          }
      }

end CacheMigration
