package org.sinemenda.probatio.packaging

import java.nio.file.Path

/**
 * The release-step entry point (R-N3). Reads the directory of
 * downloaded release artifacts, builds a `ReleaseManifest`, and runs
 * `ReleaseValidator.validateAll` over it. A release that is not
 * complete is not delivered: any issue fails the step with the full
 * issue list.
 *
 * Usage: `ReleaseCheck <artifacts-dir> <release-version>`
 *
 * Wired into `release-probatio.yml` as the gate between artifact
 * download and release publication. Invoked via `runMain`, which is
 * non-forked — failures use `sys.error` (a thrown exception fails the
 * task); `sys.exit` would terminate the sbt JVM.
 *
 * spec: native-gate-delivery — Requirement: A release SHALL be complete before it is delivered
 */
object ReleaseCheck:

  /**
   * Whether an environment lookup reports CI. Provenance is an observed
   * fact, not an assertion: GitHub Actions sets `CI=true`; a locally-run
   * check reports false and `validateCIProvenance` blocks the release.
   * The environment is passed as a lookup so the decision is testable.
   */
  private[packaging] def isCIEnvironment(env: String => Option[String]): Boolean =
    env("CI").contains("true")

  /**
   * Builds the manifest from `dir` and validates it. `Right` carries the
   * completion report; `Left` carries the reason the release is not
   * delivered — a manifest that could not be built, or the full issue
   * list. `private[packaging]` so the gate is testable without a JVM
   * exit.
   */
  private[packaging] def run(
    dir: Path,
    version: String,
    builtFromCI: Boolean
  ): Either[String, String] =
    ReleaseManifestIO.fromDirectory(dir, version, builtFromCI) match
      case Left(err) =>
        Left(s"release manifest could not be built: $err")
      case Right(manifest) =>
        ReleaseValidator.validateAll(manifest) match
          case Nil =>
            Right(
              s"release manifest complete: ${manifest.artifacts.size} artifacts for $version"
            )
          case issues =>
            Left(
              s"release manifest incomplete — release not delivered:\n" +
                issues.map(i => s"  - $i").mkString("\n")
            )

  def main(args: Array[String]): Unit =
    args.toList match
      case dir :: version :: Nil =>
        run(Path.of(dir), version, isCIEnvironment(sys.env.get)) match // danger-scan:allow env-lookup — sys.env.get returns Option, not an unsafe get
          case Right(report) => println(report)
          case Left(err)     => sys.error(err)
      case _ => // danger-scan:allow arity-rejection — wrong argument count maps to usage error, never a valid manifest
        sys.error("usage: ReleaseCheck <artifacts-dir> <release-version>")
