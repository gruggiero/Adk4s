package org.sinemenda.probatio.packaging

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes `probatio-sbom.spdx.json` for a release (R-N3).
 *
 * The release workflow invokes this via the `sbomGenerate` sbt task,
 * which supplies the resolved dependency list; the schema itself lives in
 * `Sbom` — this entrypoint only renders `Sbom.forRelease`. Each
 * dependency argument is `name|version|downloadLocation` (a `|` never
 * appears in Maven coordinates).
 *
 * spec: native-packaging — Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources
 * spec: finish-probatio-replacement/delivery-verified — Requirement: A release SHALL be complete before it is delivered
 */
object SbomGenerate:

  def main(args: Array[String]): Unit =
    args.toList match
      case out :: version :: deps =>
        val packages: List[SbomPackage] = deps.map { dep =>
          dep.split("\\|", -1).toList match
            case name :: depVersion :: location :: Nil =>
              SbomPackage(name, depVersion, location)
            case _ => // danger-scan:allow decode-failure — a malformed dep argument is a usage error, never a valid package
              sys.error(s"not a '<name>|<version>|<location>' dependency: '$dep'")
        }
        val sbom: Sbom = Sbom.forRelease(version, packages)
        Files.writeString(
          Path.of(out),
          Sbom.renderJson(sbom),
          StandardCharsets.UTF_8
        )
        ()
      case _ => // danger-scan:allow arity-rejection — wrong argument count maps to usage error, never a written file
        sys.error("usage: SbomGenerate <output-file> <release-version> <name|version|location>...")
