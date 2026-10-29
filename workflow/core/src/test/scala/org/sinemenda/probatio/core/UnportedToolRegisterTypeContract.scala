package org.sinemenda.probatio.core

/**
 * Typed contract for spec: unported-tool-register (spec 11 of
 * `repair-probatio-cutover`). Typed contract tier: **full** — the
 * two-variant classification is the mechanism.
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * public signatures via eta-expanded references. Zero runtime cost; any
 * later signature drift breaks `probatio-core/Test/compile`. The
 * spec-named compile-negative obligations live in
 * `SubcommandTypeContract` (cli) per the spec's Proof Obligations table.
 *
 * spec: unported-tool-register — Step 1: typed contract (full)
 * spec: unported-tool-register — Concepts Introduced (new): UnportedTool, PortBlocker, ToolSurfaceClassification
 */
final class UnportedToolRegisterTypeContract extends ProbatioSuite:

  // ── Enum shape pins — the closed sets ARE the mechanism ─────────────
  //
  // Both enums have parameterized cases, so no `values` array exists;
  // the exhaustive matches below pin the case set itself — a further
  // variant makes this file fail `probatio-core/Test/compile` under
  // -Werror exhaustiveness.

  // Exhaustive over both variants — no default arm; a third variant
  // breaks compilation under -Werror + exhaustiveness escalation.
  val classifyExhaustiveSig: ToolSurfaceClassification => String =
    (c: ToolSurfaceClassification) =>
      c match
        case ToolSurfaceClassification.Ported(_)     => "ported"
        case ToolSurfaceClassification.Registered(_) => "registered"

  // Exhaustive over all three blockers — same mechanism.
  val blockerExhaustiveSig: PortBlocker => String =
    (b: PortBlocker) =>
      b match
        case PortBlocker.GatedOnSpike(_)           => "gated"
        case PortBlocker.NotOnEnforcementPath      => "off-path"
        case PortBlocker.SupersededByPortedTool(_) => "superseded"

  // ── Signature pins (eta-expanded against the real declarations) ─────

  // UnportedTool.apply: (String, String, PortBlocker, List[String]) => UnportedTool —
  // the blocker and the citation list are BOTH required; an arity-
  // shortened construction is the compile-negative.
  val unportedToolApplySig: (String, String, PortBlocker, List[String]) => UnportedTool =
    UnportedTool.apply

  // PortBlocker variant constructors — the closed blocker set.
  val gatedOnSpikeSig: String => PortBlocker =
    PortBlocker.GatedOnSpike.apply

  val supersededSig: String => PortBlocker =
    PortBlocker.SupersededByPortedTool.apply

  val notOnEnforcementPathSig: PortBlocker =
    PortBlocker.NotOnEnforcementPath

  // ToolSurfaceClassification constructors — Ported carries the
  // subcommand's CLI name; Registered carries the register entry.
  val portedSig: String => ToolSurfaceClassification =
    ToolSurfaceClassification.Ported.apply

  val registeredSig: UnportedTool => ToolSurfaceClassification =
    ToolSurfaceClassification.Registered.apply

  // UnportedToolRegister.DirListing constructors — a directory read is
  // either carried or could-not-be-read; there is no third shape.
  val readSig: (String, List[String]) => UnportedToolRegister.DirListing =
    UnportedToolRegister.DirListing.Read.apply

  val unreadableSig: String => UnportedToolRegister.DirListing =
    UnportedToolRegister.DirListing.Unreadable.apply

  // UnportedToolRegister.Report.apply — the check's observable: what
  // classified, what did not, what classified twice, which citations do
  // not resolve.
  val reportApplySig: (
    List[(String, ToolSurfaceClassification)],
    List[String],
    List[String],
    List[(UnportedTool, String)]
  ) => UnportedToolRegister.Report =
    UnportedToolRegister.Report.apply

  val reportIsCleanSig: UnportedToolRegister.Report => Boolean =
    (r: UnportedToolRegister.Report) => r.isClean

  // UnportedToolRegister.isRevertTarget: String => Boolean —
  // a *.predecessor.bak file is a swap record's revert target, never a
  // tool to classify.
  val isRevertTargetSig: String => Boolean =
    UnportedToolRegister.isRevertTarget

  // UnportedToolRegister.toolDirectories: List[String] — the workflow's
  // tool directories, repo-root-relative. A `def` pin: the value is
  // supplied at Step 3 and must not be evaluated at contract-check time.
  def toolDirectoriesSig: List[String] =
    UnportedToolRegister.toolDirectories

  // UnportedToolRegister.classifyAll — the total classification over a
  // tool set: ported surface membership and register membership are the
  // only inputs; the Report names the neither and the both.
  val classifyAllSig: (
    List[String],
    Map[String, String],
    List[UnportedTool],
    Set[String]
  ) => UnportedToolRegister.Report =
    UnportedToolRegister.classifyAll

  // UnportedToolRegister.unresolvedCitations — every entry paired with
  // the citation that names no present document.
  val unresolvedCitationsSig: (
    List[UnportedTool],
    Set[String]
  ) => List[(UnportedTool, String)] =
    UnportedToolRegister.unresolvedCitations

  // UnportedToolRegister.checkSurface — the three-way verdict: an
  // unreadable directory is Undetermined; a dirty report is Finding;
  // clean is Ran.
  val checkSurfaceSig: (
    List[UnportedToolRegister.DirListing],
    Map[String, String],
    List[UnportedTool],
    Set[String]
  ) => Outcome[UnportedToolRegister.Report] =
    UnportedToolRegister.checkSurface

  // UnportedToolRegister.parseRegister — the committed register document
  // parses to entries or the parse fails; no silent defaults.
  val parseRegisterSig: String => Either[String, List[UnportedTool]] =
    UnportedToolRegister.parseRegister

  // UnportedToolRegister.renderRegister — entries render to the document
  // row format; parseRegister ∘ renderRegister round-trips.
  val renderRegisterSig: List[UnportedTool] => String =
    UnportedToolRegister.renderRegister

  // ── Runtime pins — the named variants are the whole surface ─────────

  test("ToolSurfaceClassification has exactly two variants"):
    val tool: UnportedTool = UnportedTool("t", "p", PortBlocker.NotOnEnforcementPath, Nil)
    val both: List[ToolSurfaceClassification] = List(
      ToolSurfaceClassification.Ported("graph"),
      ToolSurfaceClassification.Registered(tool)
    )
    assertEquals(
      both.map(classifyExhaustiveSig),
      List("ported", "registered"),
      "a third classification would reopen the neither-ported-nor-registered gap"
    )

  test("PortBlocker has exactly three closed variants"):
    val all: List[PortBlocker] = List(
      PortBlocker.GatedOnSpike("s"),
      PortBlocker.NotOnEnforcementPath,
      PortBlocker.SupersededByPortedTool("graph")
    )
    assertEquals(
      all.map(blockerExhaustiveSig),
      List("gated", "off-path", "superseded"),
      "the blocker is a closed enumeration — a free-text reason cannot be checked"
    )
