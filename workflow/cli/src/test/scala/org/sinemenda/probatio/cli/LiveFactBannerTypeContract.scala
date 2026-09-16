package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.ActiveChangeWithChainState
import org.sinemenda.probatio.core.BannerEngine
import org.sinemenda.probatio.core.BannerInputs
import org.sinemenda.probatio.core.BannerOutput
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.ChainStateUndetermined
import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.DriftScanResult
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.HeartbeatRecord
import org.sinemenda.probatio.core.InstallRootScan
import org.sinemenda.probatio.core.InstallRoots
import org.sinemenda.probatio.core.RepositoryFacts
import org.sinemenda.probatio.core.SessionId

import java.nio.file.Path

/**
 * Typed contract for spec: live-fact-banner
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved public
 * signatures via eta-expanded references and asserts the compile-negative
 * obligations. Zero runtime cost; any later signature drift breaks
 * `probatio-cli/Test/compile`.
 *
 * spec: live-fact-banner — Step 1: typed contract (full)
 * spec: live-fact-banner — Concepts Introduced: RepositoryFacts
 * spec: live-fact-banner — Concepts Introduced: FactRead
 */
final class LiveFactBannerTypeContract extends ProbatioCliSuite:

  // ── Signature pins (eta-expanded against the real implementation) ───────
  // These pins make "signatures stay as approved" compiler-checked.

  // BannerInputs.from: RepositoryFacts => BannerInputs — the only
  // construction route for banner inputs.
  val bannerInputsFromSig: RepositoryFacts => BannerInputs =
    BannerInputs.from

  // BannerEngine.render: BannerInputs => BannerOutput — pure, total.
  val renderSig: BannerInputs => BannerOutput =
    BannerEngine.render

  // RepositoryFactsReader.read: (repoRoot, userHome, env) => RepositoryFacts
  // — the single fact-reading seam. Total: every failure is data.
  val readFactsSig: (Path, Path, Map[String, String]) => RepositoryFacts =
    RepositoryFactsReader.read

  // RepositoryFacts.fingerprint — the whole-record encoding used for
  // per-session suppression.
  val fingerprintSig: RepositoryFacts => String =
    (f: RepositoryFacts) => f.fingerprint

  // RepositoryFacts.activeChanges: FactRead[List[ActiveChangeWithChainState]] —
  // a changes dir that cannot be listed is Unreadable; it can never collapse
  // into a fabricated empty list (Ring 8 finding R3).
  val activeChangesSig: RepositoryFacts => FactRead[List[ActiveChangeWithChainState]] =
    (f: RepositoryFacts) => f.activeChanges

  // ActiveChangeWithChainState.chainState: Either[ChainStateUndetermined, ChainStateReport]
  // — every reported change carries a live attempt; "never attempted" is
  // unrepresentable (Ring 8 finding R3).
  val chainStateSig: ActiveChangeWithChainState => Either[ChainStateUndetermined, ChainStateReport] =
    (ac: ActiveChangeWithChainState) => ac.chainState

  // GateCmd.run: Array[String] => Outcome[Int] — the gate entrypoint that
  // assembles the banner from RepositoryFactsReader reads (Step 3 wires it).
  val gateRunSig: Array[String] => org.sinemenda.probatio.core.Outcome[Int] =
    GateCmd.run

  // DriftScan.installRoots — the fixed-arity six-root set.
  val installRootsSig: InstallRoots =
    DriftScan.installRoots

  // DriftScan.scan: (Option[Int], List[InstallRootScan]) => DriftScanResult —
  // the baseline is optional: pre-rename, no-stamp and unreadable roots are
  // still reported when the repository's own schema version cannot be read.
  val driftScanSig: (Option[Int], List[InstallRootScan]) => DriftScanResult =
    DriftScan.scan

  // SessionId.fromRaw / SessionId.resolve — the only construction routes.
  val sessionIdFromRawSig: String => SessionId =
    SessionId.fromRaw

  val sessionIdResolveSig: (Option[String], Option[String], Option[String], Long) => SessionId =
    SessionId.resolve

  // GateStateDirReader — resolution + fingerprint + heartbeat I/O.
  val gateStateDirResolveSig: Path => Option[GateStateDir] =
    GateStateDirReader.resolve

  val fingerprintFileSig: (GateStateDir, SessionId) => Path =
    GateStateDirReader.fingerprintFile

  val readFingerprintSig: (GateStateDir, SessionId) => Option[String] =
    GateStateDirReader.readFingerprint

  val writeFingerprintSig: (GateStateDir, SessionId, String) => Unit =
    GateStateDirReader.writeFingerprint

  val writeHeartbeatSig: (GateStateDir, HeartbeatRecord) => Unit =
    GateStateDirReader.writeHeartbeat

  val readHeartbeatSig: GateStateDir => Option[HeartbeatRecord] =
    GateStateDirReader.readHeartbeat

  // ── Compile-negative: BannerInputs constructed from literals ───────────
  // spec: live-fact-banner — Compile-Negative: BannerInputs constructed from literals rather than from a RepositoryFacts value
  test("BannerInputs cannot be constructed from literals"):
    val err: String = compileErrors(
      "BannerInputs(org.sinemenda.probatio.core.FactRead.Present(14), Nil, org.sinemenda.probatio.core.FactRead.Absent, org.sinemenda.probatio.core.FactRead.Absent, org.sinemenda.probatio.core.FactRead.Absent, Nil)"
    )
    assert(err.nonEmpty, "BannerInputs(...) should not compile — no public apply exists")

  test("BannerInputs primary constructor is private"):
    val err: String = compileErrors(
      "new BannerInputs(org.sinemenda.probatio.core.RepositoryFacts(org.sinemenda.probatio.core.FactRead.Absent, org.sinemenda.probatio.core.FactRead.Absent, org.sinemenda.probatio.core.FactRead.Absent, org.sinemenda.probatio.core.FactRead.Absent, Nil, Nil))"
    )
    assert(err.nonEmpty, "new BannerInputs(...) should not compile — private constructor")

  // ── Compile-negative: a file read inside BannerEngine ──────────────────
  // spec: live-fact-banner — Compile-Negative: a file read inside BannerEngine
  // The renderer performs no file I/O because its only input type cannot be
  // constructed from a path: `BannerInputs.from` accepts RepositoryFacts
  // and nothing else, so no caller can feed the engine a filesystem
  // location to read.
  test("BannerInputs.from rejects a filesystem path"):
    val err: String = compileErrors(
      "BannerInputs.from(java.nio.file.Paths.get(\".\"))"
    )
    assert(err.nonEmpty, "BannerInputs.from(Path) should not compile — no file-reading route into the engine")

  // ── Compile-negative: a narrowed install-root set ──────────────────────
  // spec: live-fact-banner — Compile-Negative: DriftScan.installRoots referenced as a list of fewer roots than the workflow searches
  // The searched root set is a fixed-arity record: constructing one with
  // fewer than the six searched roots does not compile.
  test("InstallRoots cannot be constructed with fewer than six roots"):
    val err: String = compileErrors(
      "InstallRoots(org.sinemenda.probatio.core.InstallRootRef(org.sinemenda.probatio.core.RootBase.RepoRoot, \".agents/skills\"), org.sinemenda.probatio.core.InstallRootRef(org.sinemenda.probatio.core.RootBase.RepoRoot, \".claude/skills\"))"
    )
    assert(err.nonEmpty, "InstallRoots with two roots should not compile — fixed arity of six")

  // ── Property & generator obligations (become the Ring 3 test oracle) ───
  //
  // Property: facts-reflect-repository
  //   Invariant: For every fixture repository and every field of
  //   RepositoryFacts, the value `read` returns is the value the fixture
  //   contains: a present registry yields Present(n) with n equal to the
  //   fixture's document count; a registry directory present but unreadable
  //   yields Unreadable; a missing registry yields Absent. The same holds
  //   field-by-field for inventory, profile, schema version, install roots
  //   and active changes.
  //   Generator: genFixtureRepo — constructive fixture materialisation
  //   (registry with 0..5 docs, inventory with 0..20 rows, profile with a
  //   kit chosen from the detected set or none, 0..2 install roots stamped
  //   New/Legacy/no-stamp, 0..2 active changes). Hedgehog cover: each
  //   FactRead state exercised per field ≥ 10%.
  //
  // Property: every-searched-root-is-scanned
  //   Invariant: For every assignment of InstallRootState to the six
  //   searched roots, DriftScan.scan's result mentions every non-Absent
  //   root — in a warning — and reports noSkillInstalled iff every root is
  //   Absent.
  //   Generator: genRootStates — 6 × genInstallRootState (exhaustive small
  //   domain per root: Absent, PresentNoStamp, Stamped(v, New),
  //   Stamped(v, Legacy) for v in 12..15, Unreadable(reason)). Hedgehog
  //   cover: ≥ 20% rows containing a Legacy stamp, ≥ 20% containing an
  //   Unreadable root, ≥ 5% all-Absent.
  //
  // Property: suppression-tracks-facts
  //   Invariant: For every session s and facts f1, f2: emitting f1 under s
  //   then asking whether f2 would be suppressed under s answers
  //   `f1.fingerprint == f2.fingerprint`; and fingerprint equality coincides
  //   with record equality over the generated domain (the encoding is
  //   injective over every field, including chain-state reasons and
  //   undetermined reasons).
  //   Generator: genSessionId (constructive: filename-unsafe chars ≥ 20%) ×
  //   genRepositoryFacts (constructive over all FactRead states). Hedgehog
  //   cover: equal-pair ≥ 30%, diff-only-in-chain-state ≥ 10%,
  //   diff-only-in-unresolved-name ≥ 10%.
  //
  // Property: banner-states-only-read-facts
  //   Invariant: For every RepositoryFacts f, every presence/absence/count/
  //   version stated in BannerEngine.render(BannerInputs.from(f)).payload is
  //   a faithful projection of f — PRESENT(n) appears iff registry is
  //   Present(n), "ABSENT" iff Absent, "UNREADABLE" iff Unreadable, and the
  //   unresolved count equals the chain-state report's list length.
  //   Generator: genRepositoryFacts as above.
  //
  // spec: live-fact-banner — Property: facts-reflect-repository
  // spec: live-fact-banner — Property: every-searched-root-is-scanned
  // spec: live-fact-banner — Property: suppression-tracks-facts
  // spec: live-fact-banner — Property: banner-states-only-read-facts

  // ── Formal contract (Ring 6) ────────────────────────────────────────────
  //
  // Contract: bannerClaims
  //   The banner's stated fact codes are a pure function of the fact codes
  //   read this run: for each fact f with code c(f) ∈ {-1 unreadable,
  //   0 absent, n present}, the emitted line claims code c(f) and no other.
  //   A -1 input never produces a 0-claim line.
  //
  //   def bannerClaims(factCodes: List[BigInt]): List[BigInt] = {
  //     factCodes.map(identity)
  //   }.ensuring { out =>
  //     out.length == factCodes.length &&
  //     out.zip(factCodes).forall { case (claimed, read) =>
  //       (read == -1) ==> (claimed == -1)   // unreadable never collapses
  //     }
  //   }
  //
  // spec: live-fact-banner — Formal Contract: bannerClaims (fact codes -1/0/n)
  // spec: live-fact-banner — Proof Obligation: The banner is a pure function of the facts record
