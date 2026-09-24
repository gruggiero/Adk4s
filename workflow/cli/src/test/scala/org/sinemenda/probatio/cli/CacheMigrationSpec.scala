package org.sinemenda.probatio.cli

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/**
 * JVM-level scenario coverage for `CacheMigration` — the impure adapter
 * wired into `ProbatioMain.runSubcommand` (spec 10 of
 * `repair-probatio-cutover`).
 *
 * `SubprocessConformanceSpec` exercises this code through the shipped
 * native binary; these tests drive the adapter directly so the Ring-5
 * mutation run can observe coverage — a subprocess oracle cannot kill
 * mutants in JVM bytecode it never loads.
 *
 * spec: schema-rename-completion — Requirement: The cache and state directory migration runs on first use
 */
final class CacheMigrationSpec extends ProbatioCliSuite:

  /** A sandboxed `$HOME` with a `.cache` root. */
  private def sandboxedHome(): Path =
    val home: Path = Files.createTempDirectory("cache-migration-home-")
    Files.createDirectories(home.resolve(".cache"))
    home

  private def legacy(home: Path): Path =
    home.resolve(".cache").resolve(CacheMigration.legacyDirName)

  private def current(home: Path): Path =
    home.resolve(".cache").resolve(CacheMigration.currentDirName)

  private def names(dir: Path): List[String] =
    if !Files.isDirectory(dir) then List.empty[String]
    else
      Using.resource(Files.list(dir)) { (stream: java.util.stream.Stream[Path]) =>
        stream
          .iterator()
          .asScala
          .toList
          .map((p: Path) => p.getFileName.toString)
          .sorted
      }

  // spec: schema-rename-completion — Scenario: Happy path — a previous directory is migrated once
  test("a legacy cache directory is migrated to the current path on first use"):
    val home: Path = sandboxedHome()
    Files.createDirectories(legacy(home))
    Files.writeString(legacy(home).resolve("heartbeat"), "hb")

    CacheMigration.migrateOnce(Map("HOME" -> home.toString))

    assert(Files.isDirectory(current(home)), "current cache dir must exist after migration")
    assertEquals(names(current(home)), List("heartbeat"))
    assertEquals(
      Files.readString(current(home).resolve("heartbeat")),
      "hb",
      "migrated file must carry the legacy content"
    )

  // spec: schema-rename-completion — Scenario: second run after migration performs no migration
  test("a second invocation is a no-op once the current directory exists"):
    val home: Path = sandboxedHome()
    Files.createDirectories(legacy(home))
    Files.writeString(legacy(home).resolve("heartbeat"), "hb")
    CacheMigration.migrateOnce(Map("HOME" -> home.toString))

    // Diverge the two directories; a non-idempotent second run would copy again.
    Files.writeString(current(home).resolve("marker"), "current-wins")
    Files.writeString(legacy(home).resolve("late-arrival"), "new")

    CacheMigration.migrateOnce(Map("HOME" -> home.toString))

    assertEquals(
      names(current(home)).sorted,
      List("heartbeat", "marker"),
      "the current directory must not gain late legacy arrivals"
    )

  // spec: schema-rename-completion — Scenario: Adversarial — a previous directory is not migrated over an existing current one
  test("an existing current directory is never overwritten by legacy contents"):
    val home: Path = sandboxedHome()
    Files.createDirectories(legacy(home))
    Files.createDirectories(current(home))
    Files.writeString(legacy(home).resolve("heartbeat"), "legacy-version")
    Files.writeString(current(home).resolve("heartbeat"), "current-version")

    CacheMigration.migrateOnce(Map("HOME" -> home.toString))

    assertEquals(
      Files.readString(current(home).resolve("heartbeat")),
      "current-version",
      "existing current contents win over legacy"
    )

  // spec: schema-rename-completion — Scenario: Edge case — a fresh environment with neither directory migrates nothing
  test("a fresh environment with neither directory migrates nothing"):
    val home: Path = sandboxedHome()

    CacheMigration.migrateOnce(Map("HOME" -> home.toString))

    assert(
      !Files.exists(current(home)),
      "a fresh environment must not gain a cache directory it never had"
    )

  test("an unreadable home degrades to no migration and no exception"):
    val nowhere: Path = Path.of("/nonexistent-home-for-cache-migration-spec")
    CacheMigration.migrateOnce(Map("HOME" -> nowhere.toString))
    assert(!Files.exists(nowhere), "no directories may be created for an absent home")

  // Ring-8 finding: an existing-but-unreadable legacy directory is
  // could-not-determine, not empty — migrating on a silently-emptied
  // listing would latch an empty `current` that `newExists` then masks
  // forever.
  test("an unreadable legacy directory produces no migration and no latched state"):
    val home: Path = sandboxedHome()
    Files.createDirectories(legacy(home))
    Files.writeString(legacy(home).resolve("heartbeat"), "hb")
    legacy(home).toFile.setReadable(false, false)
    try
      CacheMigration.migrateOnce(Map("HOME" -> home.toString))
      assert(
        !Files.exists(current(home)),
        "no current directory may be created from an unreadable snapshot"
      )
    finally { val _ = legacy(home).toFile.setReadable(true) }

    // …and once readable again, the migration runs — nothing was latched.
    CacheMigration.migrateOnce(Map("HOME" -> home.toString))
    assertEquals(names(current(home)), List("heartbeat"))

  // Ring-8 finding: a mid-copy failure must not leave a partially
  // populated `current` that a later run mistakes for a completed
  // migration.
  test("a failed copy leaves no current directory, so the next run retries"):
    val home: Path = sandboxedHome()
    Files.createDirectories(legacy(home))
    val unreadable: Path = legacy(home).resolve("locked")
    Files.writeString(unreadable, "secret")
    Files.writeString(legacy(home).resolve("heartbeat"), "hb")
    unreadable.toFile.setReadable(false, false)
    try CacheMigration.migrateOnce(Map("HOME" -> home.toString))
    finally { val _ = unreadable.toFile.setReadable(true) }

    assert(!Files.exists(current(home)), "a partial migration must not latch")
    assert(
      !Files.exists(home.resolve(".cache").resolve(CacheMigration.currentDirName + ".migrating")),
      "the staging directory must be cleaned up"
    )

    CacheMigration.migrateOnce(Map("HOME" -> home.toString))
    assertEquals(
      names(current(home)).sorted,
      List("heartbeat", "locked"),
      "the retried run migrates everything"
    )

end CacheMigrationSpec
