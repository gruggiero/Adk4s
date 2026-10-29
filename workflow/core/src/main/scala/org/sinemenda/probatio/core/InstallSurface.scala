package org.sinemenda.probatio.core

/**
 * Which harnesses the hook installer wires (spec 7 of
 * `repair-probatio-cutover`).
 *
 * The predecessor wires the harness named by `--agent`, or every harness
 * already present in the project when none is named (`--agent` absent or
 * `all`). Two variants and no third: a target able to name one agent
 * directory would permit the narrowed install this spec removes, so
 * `NamedHarness` names a HARNESS, never a directory.
 *
 * `name` is the supplied token verbatim — the predecessor accepts any
 * string after `--agent` and diagnoses an unrecognised one at apply time
 * (`unknown agent: x`, exit 0), so the surface keeps the token rather
 * than rejecting it at the boundary.
 *
 * spec: install-tool-surface-parity — Concepts Introduced (new): InstallTarget
 * spec: install-tool-surface-parity — Compile-Negative: A single-directory install target
 */
enum InstallTarget:

  /**
   * Every harness already present in the project — the predecessor's
   * `--agent`-absent and `--agent all` behaviour.
   */
  case AllPresentHarnesses

  /** The harness named by `--agent` — the supplied token, verbatim. */
  case NamedHarness(name: String)

object InstallTarget:

  extension (t: InstallTarget)
    /** The supplied harness name, when the target names one. */
    def namedHarness: Option[String] = t match
      case InstallTarget.NamedHarness(name)  => Some(name)
      case InstallTarget.AllPresentHarnesses => None

/**
 * Whether the hook installer reports what it would write or writes it
 * (spec 7 of `repair-probatio-cutover`).
 *
 * `DryRun` is the predecessor's default — a hook is a command the harness
 * EXECUTES, so installing one is a decision the invocation must take
 * explicitly. The mode is a required parameter of the install entrypoint
 * with no default: an installer that could default to `Apply` — swapped
 * in behind an unchanged invocation — modifies harness configuration
 * nobody asked it to touch.
 *
 * spec: install-tool-surface-parity — Concepts Introduced (new): InstallMode
 * spec: install-tool-surface-parity — Compile-Negative: An install mode defaulting to write
 */
enum InstallMode:

  /** Report each file that would be written; write nothing. */
  case DryRun

  /** Write the files and report each one written. */
  case Apply

object InstallMode:

  extension (m: InstallMode)
    /** True only when the invocation explicitly asked to write. */
    def writes: Boolean = m match
      case InstallMode.Apply  => true
      case InstallMode.DryRun => false

/**
 * One declared prerequisite and whether the probe found it (spec 7 of
 * `repair-probatio-cutover`).
 *
 * A probe carries its finding — presence is a field, not a position in a
 * list. A report built from names alone could not distinguish
 * checked-and-present from not-checked; that construction is a
 * compile-negative obligation.
 *
 * spec: install-tool-surface-parity — Concepts Introduced (new): PrerequisiteProbe
 */
final case class PrerequisiteProbe(name: String, present: Boolean)

/**
 * The result of probing the declared prerequisite set (spec 7 of
 * `repair-probatio-cutover`).
 *
 * Holds `PrerequisiteProbe`s — name AND finding — never bare names.
 * `missing` is derived, so the report and its count cannot disagree.
 *
 * spec: install-tool-surface-parity — Concepts Introduced (new): PrerequisiteReport
 * spec: install-tool-surface-parity — Compile-Negative: A prerequisite report built from names alone
 */
final case class PrerequisiteReport(probes: List[PrerequisiteProbe]):

  /** The probes that did not find their prerequisite. */
  val missing: List[PrerequisiteProbe] =
    probes.filter((p: PrerequisiteProbe) => !p.present)

  /** True when every declared prerequisite was found. */
  def allPresent: Boolean = missing.isEmpty

  /** How many prerequisites were not found. */
  def missingCount: Int = missing.length

/**
 * The closed sets both installers decide over (spec 7 of
 * `repair-probatio-cutover`) — the predecessor's declared lists, taken
 * verbatim. Closed here so the adapter cannot narrow them: a shorter
 * list is a different value, not a subset silently taken.
 */
object InstallSurface:

  /**
   * The seven prerequisites `install-skills.sh --check-installed`
   * probes, in the predecessor's probe order.
   */
  val declaredPrerequisites: List[String] =
    List("bash", "git", "jq", "python3", "shellcheck", "bats", "shfmt")

  /**
   * The three agent skill directories the skill installer writes to,
   * relative to the project root — the predecessor's `AGENT_DIRS`.
   * `.devin/skills` is intentionally present and intentionally absent
   * from `DriftScan.installRoots` — that asymmetry is recorded in the
   * spec's Implementation Anchors and reconciled by a later change, not
   * here.
   */
  val skillAgentDirs: List[String] =
    List(".claude/skills", ".pi/skills", ".devin/skills")

  /**
   * The harnesses the hook installer knows — the predecessor's
   * `claude|pi|devin` case arms, in the order its `for a in $AGENT`
   * loop wires them.
   */
  val knownHarnesses: List[String] =
    List("claude", "pi", "devin")

  /**
   * The marker directories the default-to-all-present selection probes,
   * paired with the harness each names — the predecessor's
   * `[ -d "$PROJECT/.claude" ]`-style detection, in detection order.
   */
  val harnessMarkerDirs: List[(String, String)] =
    List("claude" -> ".claude", "pi" -> ".pi", "devin" -> ".devin")

  /**
   * The configuration file each harness's wiring writes or merges,
   * relative to the project root — the predecessor's `dst` per agent,
   * in wiring order.
   */
  val hookDestinations: List[(String, String)] = List(
    "claude" -> ".claude/settings.json",
    "pi"     -> ".pi/extensions/verified-scala3-gate.ts",
    "devin"  -> ".devin/hooks.v1.json"
  )

end InstallSurface
