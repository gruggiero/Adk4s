package org.sinemenda.probatio.core

/**
 * Schema-policy migration mechanics (spec 6).
 *
 * The v14 rename (verified-scala3 → probatio) touches schema metadata,
 * the generatedBy stamp, the hook control env var, and the cache/state
 * directory. The migration mechanics — env-var alias resolution, cache-dir
 * auto-migration, stamp classification — are modelled here as pure functions
 * so they can be property-tested independently of the shell/yaml layer.
 *
 * spec: port-scanner-to-probatio/schema-policy — Requirement: Skill stamp renamed and old stamp treated as pre-rename
 * spec: port-scanner-to-probatio/schema-policy — Requirement: Environment variable migrated with deprecated alias for one major
 * spec: port-scanner-to-probatio/schema-policy — Requirement: Cache and state directories auto-migrated on first run
 */

// ── Stamp classification (R-V2: generatedBy stamp rename) ────────────────

/**
 * The format of a `generatedBy` stamp found in an installed skill copy.
 *
 * - `Legacy` — the pre-rename stamp (`verified-scala3-schema/<N>`)
 * - `New`    — the post-rename stamp (`probatio-schema/<N>`)
 *
 * spec: schema-policy — Concepts Introduced: generatedBy stamp rename
 */
enum StampFormat:
  case Legacy
  case New

/**
 * The drift detector's classification of a single scanned stamp.
 *
 * Disjoint sum with exactly four cases. The `PreRename` variant exists
 * specifically so the legacy stamp branch returns it (not `DriftWarning`),
 * enforced by compile-negative: the legacy branch MUST NOT return
 * `DriftWarning`.
 *
 * spec: schema-policy — Requirement: Skill stamp renamed and old stamp treated as pre-rename
 * spec: schema-policy — Implementation Anchor: StampClassification
 */
enum StampClassification:
  case Matching
  case DriftWarning(expected: Int, found: Int)
  case PreRename(found: Int)
  case NoSkill

/**
 * A stamp entry found at one install root: format + version, or absent.
 */
final case class RootStamp(rootPath: String, stamp: Option[(StampFormat, Int)])

/**
 * A scan across all install roots.
 */
final case class StampScan(roots: List[RootStamp])

/**
 * A line emitted by the drift scan classification.
 *
 * - `NoSkillLine`         — explicit "no skill installed" line
 * - `MigrationMessage`    — one-time migration message for a legacy stamp
 * - `DriftWarningLine`    — real drift (new stamp, version mismatch)
 *
 * spec: schema-policy — Property: stamp-rename-migration-message
 */
enum DriftLine:
  case NoSkillLine
  case MigrationMessage(rootPath: String, found: Int)
  case DriftWarningLine(rootPath: String, expected: Int, found: Int)

// ── Env var resolution (R-V2: env var migration) ─────────────────────────

/**
 * The resolved value of the hook control env var.
 *
 * - `Default` — neither the new nor the legacy name is set; the gate
 *   proceeds with its default behaviour (hooks on)
 * - `Value(v)` — the env var resolves to a concrete value (`off`, `on`,
 *   `trace`)
 */
enum ResolvedValue:
  case Default
  case Value(v: String)

/**
 * A deprecation warning emitted when the legacy env var name is read as
 * an alias. Names the old name, the new name, and the one-major alias
 * window so the consumer knows how long they have to migrate.
 *
 * spec: schema-policy — Requirement: Environment variable migrated with deprecated alias for one major
 */
final case class DeprecationWarning(
  oldName: String,
  newName: String,
  majorWindow: Int
)

/** Warnings emitted during env var resolution. */
type Warnings = List[DeprecationWarning]

/**
 * The four states of the hook control env var, constructive over
 * (neither set, legacy only, new only, both set) × value space.
 *
 * spec: schema-policy — Property: env-var-alias-honored-with-warning
 * spec: schema-policy — Generator strategy: genEnvVarSetting
 */
enum EnvVarSetting:
  case Neither
  case LegacyOnly(value: String)
  case NewOnly(value: String)
  case Both(newVal: String, legacyVal: String)

/**
 * The result of resolving the hook control env var: the resolved value
 * plus any deprecation warnings. The `Warnings` component is structurally
 * required so the deprecation warning cannot be silently dropped
 * (compile-negative enforced).
 *
 * spec: schema-policy — Requirement: Environment variable migrated with deprecated alias for one major
 * spec: schema-policy — Implementation Anchor: resolveHookEnv
 */
final case class EnvResolution(resolved: ResolvedValue, warnings: Warnings)

// ── Cache directory migration (R-V2: cache dir migration) ────────────────

/**
 * The state of the cache and state directory pair (legacy + new).
 *
 * - `legacyExists`    — does `~/.cache/verified-scala3/` exist?
 * - `newExists`       — does `~/.cache/probatio/` exist?
 * - `legacyContents`  — files in the legacy directory (if it exists)
 * - `newDirContents`  — files in the new directory (if it exists)
 *
 * spec: schema-policy — Property: cache-dir-migration-idempotent
 * spec: schema-policy — Generator strategy: genCacheState
 */
final case class CacheState(
  legacyExists: Boolean,
  newExists: Boolean,
  legacyContents: List[String],
  newDirContents: List[String]
)

// ── Pure functions ────────────────────────────────────────────────────────

object SchemaPolicy:

  /** The legacy env var name (pre-rename). */
  val legacyEnvVarName: String = "VERIFIED_SCALA3_HOOKS"

  /** The new env var name (post-rename). */
  val newEnvVarName: String = "PROBATIO_HOOKS"

  /** The one-major alias window for the legacy env var. */
  val aliasMajorWindow: Int = 1

  /** The schema version at which the rename landed (v14). */
  val renameVersion: Int = 14

  /**
   * Resolve the hook control env var, honouring the legacy name as a
   * deprecated alias for one major version.
   *
   * - `Neither`        → `Default`, no warnings
   * - `LegacyOnly(v)`  → `Value(v)`, one `DeprecationWarning` (within window)
   *                     → `Default`, no warnings (beyond one-major window)
   * - `NewOnly(v)`     → `Value(v)`, no warnings
   * - `Both(newVal, _)`→ `Value(newVal)`, no warnings (new name authoritative)
   *
   * The `schemaVersion` parameter determines whether the legacy alias is
   * still honored: the alias is active from `renameVersion` (v14) through
   * `renameVersion + aliasMajorWindow` (v15). At v16 and beyond, the legacy
   * name is no longer read — `LegacyOnly` returns `Default` as if unset.
   *
   * spec: schema-policy — Requirement: Environment variable migrated with deprecated alias for one major
   * spec: schema-policy — Scenario: legacy name honored as alias
   * spec: schema-policy — Scenario: legacy name emits deprecation warning on stderr
   * spec: schema-policy — Scenario: new name takes precedence over legacy alias
   * spec: schema-policy — Scenario: legacy name not read after one major
   */
  def resolveHookEnv(setting: EnvVarSetting, schemaVersion: Int): EnvResolution =
    val aliasExpired: Boolean = schemaVersion > renameVersion + aliasMajorWindow
    setting match
      case EnvVarSetting.Neither =>
        EnvResolution(ResolvedValue.Default, List.empty)
      case EnvVarSetting.LegacyOnly(value) =>
        if aliasExpired then EnvResolution(ResolvedValue.Default, List.empty)
        else
          EnvResolution(
            ResolvedValue.Value(value),
            List(DeprecationWarning(legacyEnvVarName, newEnvVarName, aliasMajorWindow))
          )
      case EnvVarSetting.NewOnly(value) =>
        EnvResolution(ResolvedValue.Value(value), List.empty)
      case EnvVarSetting.Both(newVal, _) =>
        EnvResolution(ResolvedValue.Value(newVal), List.empty)

  /**
   * Migrate the cache directory from the legacy path to the new path.
   *
   * - legacy exists, new absent → move contents, new = legacy contents
   * - new exists                → no migration, new = prior contents
   * - neither exists            → fresh install, new = empty
   * - both exist                → no migration, new wins
   *
   * Idempotent: a second call is a no-op.
   *
   * spec: schema-policy — Requirement: Cache and state directories auto-migrated on first run
   * spec: schema-policy — Scenario: legacy directory migrated to new path on first run
   * spec: schema-policy — Scenario: second run after migration performs no migration
   * spec: schema-policy — Scenario: fresh install with no legacy directory
   * spec: schema-policy — Scenario: legacy directory absent but new directory present
   */
  def migrateCache(state: CacheState): CacheState =
    if state.newExists then state
    else if state.legacyExists then
      CacheState(legacyExists = true, newExists = true, state.legacyContents, state.legacyContents)
    else CacheState(legacyExists = false, newExists = true, List.empty, List.empty)

  /**
   * Classify a single stamp against the schema version.
   *
   * - `Legacy` format at any version → `PreRename` (migration message)
   * - `New` format, matching version  → `Matching` (no drift)
   * - `New` format, mismatched version → `DriftWarning` (real drift)
   *
   * spec: schema-policy — Requirement: Skill stamp renamed and old stamp treated as pre-rename
   * spec: schema-policy — Scenario: new stamp compared against schema version
   * spec: schema-policy — Scenario: new stamp with version mismatch reports drift
   * spec: schema-policy — Scenario: old stamp produces migration message, not drift
   */
  def classifyStamp(format: StampFormat, version: Int, schemaVersion: Int): StampClassification =
    format match
      case StampFormat.Legacy => StampClassification.PreRename(version)
      case StampFormat.New =>
        if version == schemaVersion then StampClassification.Matching
        else StampClassification.DriftWarning(schemaVersion, version)

  /**
   * Classify a full stamp scan, emitting drift lines.
   *
   * - No stamps at all → `NoSkillLine`
   * - Legacy stamps    → `MigrationMessage` per root
   * - New, mismatched  → `DriftWarningLine` per root
   * - New, matching    → no line
   *
   * spec: schema-policy — Property: stamp-rename-migration-message
   * spec: schema-policy — Scenario: no skill installed produces explicit no-skill line
   */
  def classifyDrift(scan: StampScan, schemaVersion: Int): List[DriftLine] =
    val stamps: List[(RootStamp, StampFormat, Int)] = scan.roots.flatMap { root =>
      root.stamp.map { case (fmt, ver) => (root, fmt, ver) }
    }
    if stamps.isEmpty then List(DriftLine.NoSkillLine)
    else
      stamps.flatMap { case (root, fmt, ver) =>
        fmt match
          case StampFormat.Legacy =>
            Some(DriftLine.MigrationMessage(root.rootPath, ver))
          case StampFormat.New =>
            if ver == schemaVersion then None
            else Some(DriftLine.DriftWarningLine(root.rootPath, schemaVersion, ver))
      }

end SchemaPolicy
