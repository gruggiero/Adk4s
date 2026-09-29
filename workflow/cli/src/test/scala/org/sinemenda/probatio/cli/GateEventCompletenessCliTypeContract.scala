package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.EnvResolution
import org.sinemenda.probatio.core.GateEvent
import org.sinemenda.probatio.core.HarnessPayload
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.SessionId
import org.sinemenda.probatio.core.SpecPhase

import java.nio.file.Path

/**
 * Typed contract for the spec-8 gate CLI adapters (gate-event-completeness,
 * Step 1).
 *
 * Pins the public shapes the Step-2 oracle exercises against the built
 * artifact — compiled under the real probatio-cli classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `GateCmd.Event` IS `GateEvent` — a type alias plus the companion
 *    object value, so `Event.PostBash` resolves and every match is
 *    exhaustiveness-escalated against the single six-case domain.
 *  - `HarnessPayloadReader` is the input-channel adapter: `consumesPayload`
 *    names the four payload events, `parse` applies jq `// empty` field
 *    semantics, `readChannel` is the top-level once-only read (TTY-guarded).
 *  - `GateStateDirReader` gains the spec-8 state surface — phase files
 *    (total, unrecognised → Oracle), presentation markers (session-scoped
 *    and any-session globs), grant tokens, refusal markers with the
 *    Boolean write contract (false → fail open), the checkpoint-output
 *    sweep, and spec-dir discovery. All read paths fail open.
 *  - `RefusalKind` closes the three refusal-marker kinds; `markerPrefix`
 *    is the total name map.
 *  - `CliContext.hooksControl` resolves the hook-control env var through
 *    `SchemaPolicy.resolveHookEnv` — the alias window is a core decision;
 *    `readEscapeHatch` (the semantically-inverted `=1` check) is REMOVED.
 *  - `GateCmd.run` reads the input channel AT MOST ONCE at the top level
 *    (the `() => Option[String]` seam); `run(args, env)` stays the
 *    no-channel test seam.
 *  - The four tier bodies (`runPostEdit`, `runToolCall`, `runPostBash`,
 *    `runCompletion`) are pinned `private` — the oracle exercises them
 *    through `GateCmd.run` against the built artifact, never in-process.
 *
 * spec: gate-event-completeness — Requirement: The gate handles every event its installed adapters emit
 * spec: gate-event-completeness — Requirement: The blocking tiers consult repository state and fail open when it is unavailable
 * spec: gate-event-completeness — Scenario: Adversarial — the escape hatch bypasses both checks under either name
 */
final class GateEventCompletenessCliTypeContract extends ProbatioCliSuite:

  // ── GateCmd.Event — the alias to the six-case core domain ───────────
  val eventSig: GateCmd.Event               = GateCmd.Event.PostBash
  val eventIsGateEvent: GateEvent           = eventSig
  val runSig: Array[String] => Outcome[Int] = GateCmd.run
  val runEnvSig: (Array[String], Map[String, String]) => Outcome[Int] =
    GateCmd.run(_, _)

  // ── HarnessPayloadReader — the input channel adapter ────────────────
  val consumesSig: GateEvent => Boolean          = HarnessPayloadReader.consumesPayload
  val parseSig: String => Option[HarnessPayload] = HarnessPayloadReader.parse
  val emptySig: HarnessPayload                   = HarnessPayloadReader.Empty
  val readChannelSig: Boolean => Option[String]  = HarnessPayloadReader.readChannel

  // ── GateStateDirReader — the spec-8 state surface ───────────────────
  val phaseFileSig: (GateStateDir, String, String) => Path =
    GateStateDirReader.phaseFile
  val readPhaseSig: (GateStateDir, String, String) => SpecPhase =
    GateStateDirReader.readPhase
  val writePhaseSig: (GateStateDir, String, String, SpecPhase) => Unit =
    GateStateDirReader.writePhase

  val presentationFileSig: (GateStateDir, String, String, SessionId) => Path =
    GateStateDirReader.presentationFile
  val readPresHashSig: (GateStateDir, String, String, SessionId) => Option[String] =
    GateStateDirReader.readPresentationHash
  val anyPresSig: (GateStateDir, String, String) => Boolean =
    GateStateDirReader.hasAnySessionPresentation
  val sessionPresSig: (GateStateDir, SessionId) => Boolean =
    GateStateDirReader.hasSessionPresentation
  val sessionPresListSig: (GateStateDir, SessionId) => List[(String, String)] =
    GateStateDirReader.sessionPresentations
  val writePresSig: (GateStateDir, String, String, SessionId, String) => Unit =
    GateStateDirReader.writePresentation

  val grantFileSig: (GateStateDir, String, String, SessionId) => Path =
    GateStateDirReader.grantFile
  val hasGrantSig: (GateStateDir, String, String, SessionId) => Boolean =
    GateStateDirReader.hasGrant
  val anyGrantSig: (GateStateDir, String, String) => Boolean =
    GateStateDirReader.hasAnySessionGrant
  val writeGrantSig: (GateStateDir, String, String, SessionId, String) => Unit =
    GateStateDirReader.writeGrant

  val refusalFileSig: (GateStateDir, RefusalKind, SessionId) => Path =
    GateStateDirReader.refusalFile
  val hasRefusalSig: (GateStateDir, RefusalKind, SessionId) => Boolean =
    GateStateDirReader.hasRefusal
  val writeRefusalSig: (GateStateDir, RefusalKind, SessionId) => Boolean =
    GateStateDirReader.writeRefusal
  val clearRefusalsSig: (GateStateDir, SessionId) => Unit =
    GateStateDirReader.clearRefusals

  val sweepSig: (GateStateDir, Path => Option[String]) => Unit =
    GateStateDirReader.sweepCheckpointOutputs
  val specDirsSig: Path => List[String] = GateStateDirReader.specDirs

  // ── RefusalKind — the closed marker-kind domain ─────────────────────
  val refusalKinds: List[RefusalKind] =
    List(RefusalKind.ToolCall, RefusalKind.Grant, RefusalKind.Completion)
  val markerPrefixSig: RefusalKind => String = RefusalKind.markerPrefix

  // ── CliContext — the alias-aware hook-control resolution ────────────
  val hooksControlSig: (Map[String, String], Int) => EnvResolution =
    CliContext.hooksControl

  test("the alias makes Event.PostBash the core GateEvent.PostBash"):
    assertEquals(eventIsGateEvent, GateEvent.PostBash)

  test("consumesPayload names the four payload events"):
    assertEquals(consumesSig(GateEvent.PostBash), true)
    assertEquals(consumesSig(GateEvent.ToolCall), true)
    assertEquals(consumesSig(GateEvent.PostEdit), true)
    assertEquals(consumesSig(GateEvent.Completion), true)
    assertEquals(consumesSig(GateEvent.SessionStart), false)
    assertEquals(consumesSig(GateEvent.PromptSubmit), false)

  test("markerPrefix names the predecessor's three marker files"):
    assertEquals(markerPrefixSig(RefusalKind.ToolCall), "tool-call-refused-")
    assertEquals(markerPrefixSig(RefusalKind.Grant), "grant-refused-")
    assertEquals(markerPrefixSig(RefusalKind.Completion), "completion-refused-")

  test("hooksControl honours the legacy alias within the window"):
    val res: EnvResolution =
      hooksControlSig(Map("VERIFIED_SCALA3_HOOKS" -> "off"), 14)
    assertEquals(res.warnings.nonEmpty, true)

end GateEventCompletenessCliTypeContract
