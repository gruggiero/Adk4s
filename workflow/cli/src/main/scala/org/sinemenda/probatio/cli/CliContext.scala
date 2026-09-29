package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.EnvResolution
import org.sinemenda.probatio.core.EnvVarSetting
import org.sinemenda.probatio.core.SchemaPolicy

/**
 * Resolved paths and env-var overrides read once at entrypoint start (R-P1 wiring).
 *
 * Carries the resolved paths (repo root, change dir, ledger file, git-dir) and
 * env-var overrides read once at entrypoint start; passed to core calls.
 * Avoids re-reading env per subcommand. An immutable case class constructed
 * once at dispatch — no mutation, no race conditions.
 *
 * The hook-control env var (`PROBATIO_HOOKS`, legacy alias
 * `VERIFIED_SCALA3_HOOKS`) is resolved here through
 * `SchemaPolicy.resolveHookEnv` — the one-major deprecation window is a
 * core decision, not a CLI one. `off` skips the gate entirely, which is
 * what bypasses every blocking check (predecessor parity).
 *
 * spec: cli-wiring — Concepts Introduced: CliContext
 * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
 * spec: gate-event-completeness — Scenario: Adversarial — the escape hatch bypasses both checks under either name
 */
final case class CliContext(
  repoRoot: String,
  changeDir: String,
  ledgerFile: String,
  gitDir: String,
  escapeHatch: Boolean
)

object CliContext:

  /**
   * Resolve a `CliContext` from the environment and explicit paths.
   *
   * Reads `PROBATIO_HOOKS` once (the escape hatch). All paths are resolved
   * by the caller and passed in — this function does not perform file I/O.
   * The escape hatch is `true` when `PROBATIO_HOOKS` is set to `"1"`.
   *
   * spec: cli-wiring — Scenario: The escape hatch bypasses the tool-call lock
   */
  def resolve(
    repoRoot: String,
    changeDir: String,
    ledgerFile: String,
    gitDir: String,
    escapeHatch: Boolean
  ): CliContext =
    CliContext(
      repoRoot = repoRoot,
      changeDir = changeDir,
      ledgerFile = ledgerFile,
      gitDir = gitDir,
      escapeHatch = escapeHatch
    )

  /**
   * Resolve the hook-control env var from a given environment: the
   * constructive `EnvVarSetting` over the two names, resolved through
   * `SchemaPolicy.resolveHookEnv`. `schemaVersion` drives the alias
   * window — callers pass `SchemaPolicy.renameVersion` when the repo's
   * schema version is unknown (fail safe: the alias stays honoured).
   *
   * spec: gate-event-completeness — Scenario: Adversarial — the escape hatch bypasses both checks under either name
   */
  def hooksControl(
    env: Map[String, String],
    schemaVersion: Int
  ): EnvResolution =
    val setting: EnvVarSetting =
      (
        env.get(SchemaPolicy.newEnvVarName).filter(_.nonEmpty),
        env.get(SchemaPolicy.legacyEnvVarName).filter(_.nonEmpty)
      ) match
        case (Some(newVal), Some(legacyVal)) => EnvVarSetting.Both(newVal, legacyVal)
        case (Some(newVal), None)            => EnvVarSetting.NewOnly(newVal)
        case (None, Some(legacyVal))         => EnvVarSetting.LegacyOnly(legacyVal)
        case (None, None)                    => EnvVarSetting.Neither
    SchemaPolicy.resolveHookEnv(setting, schemaVersion)
