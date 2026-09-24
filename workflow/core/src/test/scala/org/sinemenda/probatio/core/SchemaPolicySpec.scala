package org.sinemenda.probatio.core

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range

/**
 * Test oracle for the schema-policy migration mechanics (spec 6).
 *
 * Properties and scenarios are derived from the SPEC, not from the
 * implementation. The typed contract (SchemaPolicy.scala) provides the
 * shapes; this file exercises them against the behavioural contract.
 *
 * spec: port-scanner-to-probatio/schema-policy — Properties (Ring 3)
 * spec: port-scanner-to-probatio/schema-policy — Compile-Negative Obligations
 */
final class SchemaPolicySpec extends ProbatioSuite:

  // ── Generators ──────────────────────────────────────────────────────────

  /**
   * genEnvVarSetting — constructive over the four states:
   * (neither set, legacy only, new only, both set) × value space.
   * Pairs each setting with its expected (resolved, should-warn) so the
   * property avoids a pattern match on the sealed enum (exhaustiveness
   * checker limitation under -Werror with parameterized enum cases).
   */
  private val genEnvVarSetting: Gen[(EnvVarSetting, ResolvedValue, Boolean)] =
    Gen.element1(
      (EnvVarSetting.Neither, ResolvedValue.Default, false),
      (EnvVarSetting.LegacyOnly("off"), ResolvedValue.Value("off"), true),
      (EnvVarSetting.LegacyOnly("on"), ResolvedValue.Value("on"), true),
      (EnvVarSetting.LegacyOnly("trace"), ResolvedValue.Value("trace"), true),
      (EnvVarSetting.NewOnly("off"), ResolvedValue.Value("off"), false),
      (EnvVarSetting.NewOnly("on"), ResolvedValue.Value("on"), false),
      (EnvVarSetting.NewOnly("trace"), ResolvedValue.Value("trace"), false),
      (EnvVarSetting.Both("off", "on"), ResolvedValue.Value("off"), false),
      (EnvVarSetting.Both("on", "off"), ResolvedValue.Value("on"), false),
      (EnvVarSetting.Both("trace", "off"), ResolvedValue.Value("trace"), false)
    )

  /** Generator for cache contents: 0–5 files with distinct names. */
  private val genCacheContents: Gen[List[String]] =
    Gen.string(Gen.alpha, Range.linear(3, 8)).list(Range.linear(0, 5))

  /**
   * genCacheState — constructive over (legacy-exists, new-exists) ×
   * genCacheContents. A directory's contents are drawn only when the
   * directory exists: a contents-without-directory state is not a
   * filesystem state.
   */
  private val genCacheState: Gen[CacheState] =
    for
      legacyExists <- Gen.boolean
      newExists    <- Gen.boolean
      legacy       <- if legacyExists then genCacheContents else Gen.constant(List.empty[String])
      newDir       <- if newExists then genCacheContents else Gen.constant(List.empty[String])
    yield CacheState(legacyExists, newExists, legacy, newDir)

  /** Generator for stamp format. */
  private val genStampFormat: Gen[StampFormat] =
    Gen.element1(StampFormat.Legacy, StampFormat.New)

  /** Generator for a version number (1–20). */
  private val genVersion: Gen[Int] = Gen.int(Range.linear(1, 20))

  /** Generator for a root path string. */
  private val genRootPath: Gen[String] =
    Gen.element1(
      ".claude/skills",
      ".agents/skills",
      ".pi/skills",
      "$HOME/.claude/skills",
      "$HOME/.agents/skills",
      "$HOME/.zcode/skills"
    )

  /** Generator for a single root stamp (present or absent). */
  private val genRootStamp: Gen[RootStamp] =
    for
      path    <- genRootPath
      present <- Gen.boolean
      fmt     <- genStampFormat
      ver     <- genVersion
    yield RootStamp(path, if present then Some((fmt, ver)) else None)

  /**
   * genStampScan — constructive over the six install roots, each carrying
   * (stamp-format: Legacy | New, version: Int) or absent.
   */
  private val genStampScan: Gen[StampScan] =
    genRootStamp.list(Range.linear(0, 6)).map(StampScan.apply)

  // ── Property: env-var-alias-honored-with-warning ────────────────────────

  // spec: schema-policy — Property: env-var-alias-honored-with-warning
  property("env-var-alias-honored-with-warning"):
    for (setting, expectedResolved, expectWarnings) <- genEnvVarSetting.forAll
    yield
      val result: EnvResolution = SchemaPolicy.resolveHookEnv(setting, 14)
      Result
        .assert(result.resolved == expectedResolved)
        .and(Result.assert(result.warnings.nonEmpty == expectWarnings))

  // ── Property: cache-dir-migration-idempotent ────────────────────────────

  // spec: schema-policy — Property: cache-dir-migration-idempotent
  property("cache-dir-migration-idempotent"):
    for state <- genCacheState.forAll
    yield
      val afterFirst: CacheState  = SchemaPolicy.migrateCache(state)
      val afterSecond: CacheState = SchemaPolicy.migrateCache(afterFirst)
      Result
        .assert(afterFirst.newDirContents == expectedAfterMigration(state))
        .and(Result.assert(afterSecond.newDirContents == afterFirst.newDirContents))

  /** Pure model of the expected new-dir contents after migration. */
  private def expectedAfterMigration(state: CacheState): List[String] =
    if state.newExists then state.newDirContents
    else if state.legacyExists then state.legacyContents
    else List.empty

  // ── Property: stamp-rename-migration-message ────────────────────────────

  // spec: schema-policy — Property: stamp-rename-migration-message
  property("stamp-rename-migration-message"):
    for scan <- genStampScan.forAll
    yield
      val schemaVersion: Int               = 14
      val lines: List[DriftLine]           = SchemaPolicy.classifyDrift(scan, schemaVersion)
      val stamps: List[(StampFormat, Int)] = scan.roots.flatMap(_.stamp)
      stamps match
        case Nil =>
          Result.assert(lines == List(DriftLine.NoSkillLine))
        case _ =>
          val migrationCount: Int = stamps.count(_._1 == StampFormat.Legacy)
          val driftCount: Int     = stamps.count(s => s._1 == StampFormat.New && s._2 != schemaVersion)
          val actualMigrations: Int = lines.count {
            case DriftLine.MigrationMessage(_, _) => true
            case _                                => false
          }
          val actualDrifts: Int = lines.count {
            case DriftLine.DriftWarningLine(_, _, _) => true
            case _                                   => false
          }
          Result
            .assert(lines.count(_ == DriftLine.NoSkillLine) == 0)
            .and(Result.assert(actualMigrations == migrationCount))
            .and(Result.assert(actualDrifts == driftCount))

  // ── Scenario tests: env var ─────────────────────────────────────────────

  // spec: schema-policy — Scenario: legacy name honored as alias
  test("legacy name honored as alias — LegacyOnly(off) resolves to Value(off)"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.LegacyOnly("off"), 14)
    assertEquals(result.resolved, ResolvedValue.Value("off"))

  // spec: schema-policy — Scenario: legacy name emits deprecation warning on stderr
  test("legacy name emits deprecation warning — LegacyOnly produces non-empty warnings"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.LegacyOnly("off"), 14)
    assert(result.warnings.nonEmpty, "LegacyOnly must produce a deprecation warning")
    result.warnings.headOption match
      case Some(w) =>
        assert(w.oldName.nonEmpty, "warning must name the old env var")
        assert(w.newName.nonEmpty, "warning must name the new env var")
        assert(w.majorWindow > 0, "warning must state the one-major alias window")
      case None => fail("warnings must be non-empty for LegacyOnly")

  // spec: schema-policy — Scenario: new name takes precedence over legacy alias
  test("new name takes precedence over legacy alias — Both uses newVal, no warnings"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.Both("on", "off"), 14)
    assertEquals(result.resolved, ResolvedValue.Value("on"))
    assert(result.warnings.isEmpty, "Both must not warn — new name is authoritative")

  // spec: schema-policy — Scenario: legacy name not read after one major
  test("neither set resolves to Default with no warnings"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.Neither, 14)
    assertEquals(result.resolved, ResolvedValue.Default)
    assert(result.warnings.isEmpty, "Neither must not warn")

  test("NewOnly resolves to the new value with no warnings"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.NewOnly("trace"), 14)
    assertEquals(result.resolved, ResolvedValue.Value("trace"))
    assert(result.warnings.isEmpty, "NewOnly must not warn")

  // spec: schema-policy — Scenario: legacy name not read after one major
  test("legacy name not read after one major — LegacyOnly at v16 resolves to Default"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.LegacyOnly("off"), 16)
    assertEquals(result.resolved, ResolvedValue.Default)
    assert(result.warnings.isEmpty, "LegacyOnly beyond alias window must not warn")

  test("legacy name still honored at v15 (last version of alias window)"):
    val result: EnvResolution = SchemaPolicy.resolveHookEnv(EnvVarSetting.LegacyOnly("off"), 15)
    assertEquals(result.resolved, ResolvedValue.Value("off"))
    assert(result.warnings.nonEmpty, "LegacyOnly within alias window must warn")

  // ── Scenario tests: cache dir migration ─────────────────────────────────

  // spec: schema-policy — Scenario: legacy directory migrated to new path on first run
  test("legacy directory migrated to new path on first run"):
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = false,
      legacyContents = List("heartbeat", "suppression", "trace.log"),
      newDirContents = List.empty
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List("heartbeat", "suppression", "trace.log"))
    assert(result.newExists, "newExists must be true after migration")

  // spec: schema-policy — Scenario: second run after migration performs no migration
  test("second run after migration performs no migration"):
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = false,
      legacyContents = List("heartbeat"),
      newDirContents = List.empty
    )
    val afterFirst: CacheState  = SchemaPolicy.migrateCache(state)
    val afterSecond: CacheState = SchemaPolicy.migrateCache(afterFirst)
    assertEquals(afterSecond.newDirContents, afterFirst.newDirContents)

  // spec: schema-policy — Scenario: fresh install with no legacy directory
  test("fresh install with no legacy directory creates new dir empty"):
    val state: CacheState = CacheState(
      legacyExists = false,
      newExists = false,
      legacyContents = List.empty,
      newDirContents = List.empty
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List.empty)
    assert(result.newExists, "newExists must be true after fresh install")

  // spec: schema-policy — Scenario: legacy directory absent but new directory present
  test("legacy directory absent but new directory present uses new dir"):
    val state: CacheState = CacheState(
      legacyExists = false,
      newExists = true,
      legacyContents = List.empty,
      newDirContents = List("existing")
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List("existing"))

  test("both exist — new wins, no migration"):
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = true,
      legacyContents = List("old"),
      newDirContents = List("new")
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List("new"))

  // ── Scenario tests: stamp classification ────────────────────────────────

  // spec: schema-policy — Scenario: new stamp compared against schema version
  test("new stamp at matching version produces Matching"):
    val result: StampClassification = SchemaPolicy.classifyStamp(StampFormat.New, 14, 14)
    assertEquals(result, StampClassification.Matching)

  // spec: schema-policy — Scenario: new stamp with version mismatch reports drift
  test("new stamp with version mismatch reports DriftWarning"):
    val result: StampClassification = SchemaPolicy.classifyStamp(StampFormat.New, 13, 14)
    result match
      case StampClassification.DriftWarning(expected, found) =>
        assertEquals(expected, 14)
        assertEquals(found, 13)
      case other => fail(s"Expected DriftWarning, got $other")

  // spec: schema-policy — Scenario: old stamp produces migration message, not drift
  test("old stamp at any version produces PreRename, not DriftWarning"):
    val result: StampClassification = SchemaPolicy.classifyStamp(StampFormat.Legacy, 14, 14)
    result match
      case StampClassification.PreRename(found) => assertEquals(found, 14)
      case other                                => fail(s"Expected PreRename, got $other")

  test("old stamp at mismatched version still produces PreRename, not DriftWarning"):
    val result: StampClassification = SchemaPolicy.classifyStamp(StampFormat.Legacy, 13, 14)
    result match
      case StampClassification.PreRename(found) => assertEquals(found, 13)
      case other                                => fail(s"Expected PreRename, got $other")

  // spec: schema-policy — Scenario: no skill installed produces explicit no-skill line
  test("no skill installed produces explicit NoSkillLine"):
    val scan: StampScan = StampScan(
      List(
        RootStamp(".claude/skills", None),
        RootStamp(".agents/skills", None)
      )
    )
    val lines: List[DriftLine] = SchemaPolicy.classifyDrift(scan, 14)
    assertEquals(lines, List(DriftLine.NoSkillLine))

  test("all-legacy scan produces migration messages for each root"):
    val scan: StampScan = StampScan(
      List(
        RootStamp(".claude/skills", Some((StampFormat.Legacy, 13))),
        RootStamp(".agents/skills", Some((StampFormat.Legacy, 12)))
      )
    )
    val lines: List[DriftLine] = SchemaPolicy.classifyDrift(scan, 14)
    assertEquals(lines.length, 2)
    lines.foreach { line =>
      line match
        case DriftLine.MigrationMessage(_, _) => assert(true)
        case other                            => fail(s"Expected MigrationMessage, got $other")
    }

  test("all-new-matching scan produces no lines and no NoSkillLine"):
    val scan: StampScan = StampScan(
      List(
        RootStamp(".claude/skills", Some((StampFormat.New, 14)))
      )
    )
    val lines: List[DriftLine] = SchemaPolicy.classifyDrift(scan, 14)
    assertEquals(lines, List.empty)

  test("all-new-mismatched scan produces DriftWarningLine for each root"):
    val scan: StampScan = StampScan(
      List(
        RootStamp(".claude/skills", Some((StampFormat.New, 13))),
        RootStamp(".agents/skills", Some((StampFormat.New, 12)))
      )
    )
    val lines: List[DriftLine] = SchemaPolicy.classifyDrift(scan, 14)
    assertEquals(lines.length, 2)

  test("mixed scan produces migration + drift lines"):
    val scan: StampScan = StampScan(
      List(
        RootStamp(".claude/skills", Some((StampFormat.Legacy, 13))),
        RootStamp(".agents/skills", Some((StampFormat.New, 12))),
        RootStamp(".pi/skills", Some((StampFormat.New, 14)))
      )
    )
    val lines: List[DriftLine] = SchemaPolicy.classifyDrift(scan, 14)
    val migrations: Int        = lines.count { case DriftLine.MigrationMessage(_, _) => true; case _ => false }
    val drifts: Int            = lines.count { case DriftLine.DriftWarningLine(_, _, _) => true; case _ => false }
    assertEquals(migrations, 1)
    assertEquals(drifts, 1)

  // ── Compile-Negative Obligations ────────────────────────────────────────

  // spec: schema-policy — Compile-Negative: reading legacy env var without warning
  test("resolveHookEnv returns EnvResolution with Warnings — no silent alias"):
    // The return type EnvResolution structurally requires a Warnings component.
    // A function returning only ResolvedValue (no warnings) would not typecheck
    // against this contract. This compile-negative verifies the type shape.
    val err: String = compileErrors(
      "val r: ResolvedValue = SchemaPolicy.resolveHookEnv(EnvVarSetting.LegacyOnly(\"off\"), 14)"
    )
    assert(err.nonEmpty, "resolveHookEnv must return EnvResolution (with Warnings), not just ResolvedValue")

  // spec: schema-policy — Compile-Negative: treating legacy stamp as ordinary drift
  test("classifyStamp(Legacy, v) does not return DriftWarning — PreRename is required"):
    // The sealed StampClassification type has a PreRename variant.
    // The legacy branch must return PreRename, not DriftWarning.
    val result: StampClassification = SchemaPolicy.classifyStamp(StampFormat.Legacy, 13, 14)
    result match
      case StampClassification.PreRename(_) => assert(true)
      case StampClassification.DriftWarning(_, _) =>
        fail("Legacy stamp must return PreRename, not DriftWarning — false alarm defect")
      case other => fail(s"Expected PreRename, got $other")

  // spec: schema-policy — Compile-Negative: migrating when new dir exists
  test("migrateCache is idempotent — migrating when new dir exists is a no-op"):
    // The migration branch precondition requires !newExists || !legacyExists
    // for the migration to run. When newExists=true, no migration occurs.
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = true,
      legacyContents = List("old"),
      newDirContents = List("existing")
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List("existing"))
    assert(
      result.newDirContents != state.legacyContents,
      "must not migrate legacy contents when new dir already exists"
    )

  // ── Sealed type checks ──────────────────────────────────────────────────

  test("StampClassification is sealed — no fifth variant"):
    val err: String = compileErrors(
      "val v: StampClassification = new StampClassification { }"
    )
    assert(err.nonEmpty, "StampClassification should be sealed — no new variants")

  test("DriftLine is sealed — no fourth variant"):
    val err: String = compileErrors(
      "val v: DriftLine = new DriftLine { }"
    )
    assert(err.nonEmpty, "DriftLine should be sealed — no new variants")

  test("EnvVarSetting is sealed — no fifth variant"):
    val err: String = compileErrors(
      "val v: EnvVarSetting = new EnvVarSetting { }"
    )
    assert(err.nonEmpty, "EnvVarSetting should be sealed — no new variants")

  // ════════════════════════════════════════════════════════════════════
  // spec 10 of repair-probatio-cutover: schema-rename-completion
  //
  // The migration kernel is already implemented and verified — these
  // scenarios are GREEN-BY-DESIGN at the pure level; the caller the spec
  // supplies is covered by SubprocessConformanceSpec.
  // ════════════════════════════════════════════════════════════════════

  // spec: schema-rename-completion — Scenario: Happy path — a previous directory is migrated once
  test("schema-rename: a previous directory's contents appear under the current one"):
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = false,
      legacyContents = List("heartbeat", "suppression"),
      newDirContents = List.empty
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List("heartbeat", "suppression"))
    assert(result.newExists, "the current directory must exist after migration")

  // spec: schema-rename-completion — Scenario: Happy path — a second run performs no migration
  test("schema-rename: a second run moves nothing"):
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = false,
      legacyContents = List("heartbeat"),
      newDirContents = List.empty
    )
    val once: CacheState  = SchemaPolicy.migrateCache(state)
    val twice: CacheState = SchemaPolicy.migrateCache(once)
    assertEquals(twice, once, "a second migration must be a no-op")

  // spec: schema-rename-completion — Scenario: Edge case — a fresh environment with neither directory migrates nothing
  test("schema-rename: neither directory present migrates nothing and reports no error"):
    val state: CacheState = CacheState(
      legacyExists = false,
      newExists = false,
      legacyContents = List.empty,
      newDirContents = List.empty
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result.newDirContents, List.empty, "nothing to move — nothing appears")
    assertEquals(result.legacyContents, List.empty)

  // spec: schema-rename-completion — Scenario: Adversarial — a previous directory is not migrated over an existing current one
  test("schema-rename: an existing current directory is never overwritten by the previous one"):
    val state: CacheState = CacheState(
      legacyExists = true,
      newExists = true,
      legacyContents = List("stale-a", "stale-b"),
      newDirContents = List("current")
    )
    val result: CacheState = SchemaPolicy.migrateCache(state)
    assertEquals(result, state, "with both present the state must be unchanged")
    assert(
      result.newDirContents.forall(c => !state.legacyContents.contains(c)) ||
        result.newDirContents == state.newDirContents,
      "no previous-directory content may appear under the current one"
    )

  // ── Property: migration-is-idempotent ───────────────────────────────
  // spec: schema-rename-completion — Property: migration-is-idempotent
  //
  // For every directory state, applying the migration twice yields the
  // same result as applying it once, and no state loses content.
  property("migration-is-idempotent"):
    for state <- genCacheState.forAll
    yield
      val once: CacheState  = SchemaPolicy.migrateCache(state)
      val twice: CacheState = SchemaPolicy.migrateCache(once)
      Result
        .assert(once == twice)
        .log("migrate(migrate(s)) != migrate(s)")
        .and(
          Result
            .assert(state.newDirContents.forall(once.newDirContents.contains))
            .log("current-directory content was lost")
        )
        .and(
          Result
            .assert(state.legacyContents.forall(once.legacyContents.contains))
            .log("previous-directory content was lost")
        )
