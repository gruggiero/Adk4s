package org.sinemenda.probatio.packaging

import ujson.Obj
import ujson.Str
import ujson.Value
import upickle.default.ReadWriter
import upickle.default.readwriter

import java.nio.charset.StandardCharsets
import scala.util.matching.Regex

/**
 * The compiler toolchain a native binary was built with — a distribution
 * and a version — read from the binary rather than assumed from build
 * configuration.
 *
 * GraalVM native images embed a toolchain marker of the form
 * `GraalVM <version> Java <major> <edition>` (measured on the local build:
 * `GraalVM 22.3.1 Java 17 CE`). `readEmbedded` extracts that marker from a
 * binary's bytes. `parse` reads the recorded form `<distribution>/<version>`
 * (e.g. `GraalVM CE/21.0.2`) in the same vocabulary, so the recorded
 * identity of the tested binary and the embedded identity of a candidate
 * binary compare as equals when the toolchains are the same.
 *
 * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
 * spec: finish-probatio-replacement/delivery-verified — Concepts Introduced: ToolchainIdentity
 */
final case class ToolchainIdentity(distribution: String, version: ToolchainIdentity.Version)

object ToolchainIdentity:

  /**
   * A toolchain version — an opaque wrapper over String. The opaque type
   * has no public `apply`: `Version("")` does not compile, and `parse`
   * rejects the empty string, so an empty identity can never compare
   * equal to another empty one.
   */
  opaque type Version = String

  object Version:
    /** Constructs a version only from a non-empty string. */
    def parse(raw: String): Option[Version] =
      if raw.nonEmpty then Some(raw) else None

    extension (v: Version)
      /** The underlying version string. */
      def value: String = v

  /**
   * What the embedded-marker scan found for one native binary. `Found`
   * carries the identity the binary was built with; `Unreadable` is a
   * could-not-determine — it names the binary and the reason, and is
   * never a pass.
   */
  enum Embedded:
    /** The artifact file name the scan ran against. */
    def binary: String
    case Found(override val binary: String, identity: ToolchainIdentity)
    case Unreadable(override val binary: String, reason: String)

    /** Whether the scan produced an identity. */
    def readable: Boolean =
      this match
        case Embedded.Found(_, _)      => true
        case Embedded.Unreadable(_, _) => false

    /** The identity when the scan produced one. */
    def identityOption: Option[ToolchainIdentity] =
      this match
        case Embedded.Found(_, identity) => Some(identity)
        case Embedded.Unreadable(_, _)   => None

  /**
   * Parses the recorded form `<distribution>/<version>` — e.g.
   * `GraalVM CE/21.0.2`. The distribution half may contain spaces but
   * never a `/`; the version half must be non-empty. `None` for any other
   * shape.
   *
   * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
   */
  def parse(text: String): Option[ToolchainIdentity] =
    text.split("/", -1).toList match
      case distribution :: version :: Nil if distribution.nonEmpty =>
        Version.parse(version).map(v => ToolchainIdentity(distribution, v))
      case _ => None // danger-scan:allow type-rejection — a malformed recorded form is no identity, never a default

  /**
   * Scans a native binary's bytes for the embedded GraalVM toolchain
   * marker (`GraalVM <version> Java <major> <edition>`) and maps it to
   * the identity vocabulary (`<edition>` becomes the distribution's
   * suffix: `GraalVM CE`). `Unreadable(binary, reason)` when no marker
   * is found or the marker is malformed — a could-not-determine, never
   * a silent default.
   *
   * spec: finish-probatio-replacement/delivery-verified — Scenario: Error path — an unreadable toolchain identity is could-not-determine
   */
  // GraalVM embeds the toolchain marker in two shapes:
  //  - JDK 21+ community/Oracle builds: `GraalVM CE 21.0.2+13.1` — the
  //    `+<build>` qualifier is the pinned release's build id; the
  //    recorded identity is the release version, so it is dropped.
  //  - CE/EE ≤ 23.x for older JDKs: `GraalVM 22.3.1 Java 17 CE` —
  //    measured on the unpinned local build.
  // Both map to the recorded vocabulary `GraalVM <edition>/<version>`.
  private val jdk21Marker: Regex =
    """GraalVM[ \t]+(EE)[ \t]+([0-9][0-9.]*[0-9])""".r
  private val legacyMarker: Regex =
    """GraalVM[ \t]+([0-9][0-9.]*[0-9])[ \t]+Java[ \t]+[0-9]+[ \t]+(CE|EE)""".r

  private def identityOf(distribution: String, version: String): Option[ToolchainIdentity] =
    Version.parse(version).map(v => ToolchainIdentity(distribution, v))

  def readEmbedded(binary: String, bytes: Array[Byte]): Embedded =
    // ISO-8859-1 is byte-faithful: every byte maps to one char, so the
    // marker scan over binary bytes cannot corrupt or skip content.
    val text: String = new String(bytes, StandardCharsets.ISO_8859_1)
    val identity: Option[ToolchainIdentity] =
      jdk21Marker
        .findFirstMatchIn(text)
        .flatMap(m => identityOf(s"GraalVM ${m.group(1)}", m.group(2)))
        .orElse(
          legacyMarker
            .findFirstMatchIn(text)
            .flatMap(m => identityOf(s"GraalVM ${m.group(2)}", m.group(1)))
        )
    identity match
      case Some(found) => Embedded.Found(binary, found)
      case None        => Embedded.Unreadable(binary, "no embedded GraalVM toolchain marker")

  /**
   * `ToolchainIdentity` on the wire is its recorded form
   * `<distribution>/<version>`. An unparseable encoding is a decode
   * failure, never a default identity.
   */
  given ReadWriter[ToolchainIdentity] = readwriter[String].bimap(
    (t: ToolchainIdentity) => s"${t.distribution}/${t.version.value}",
    (s: String) =>
      parse(s) match
        case Some(t) => t
        case None =>
          sys.error(
            s"not a toolchain identity: '$s'"
          ) // danger-scan:allow decode-failure — an unparseable identity crashes the decode, never maps to a valid one
  )

  /**
   * `Embedded` on the wire is a tagged object carrying the binary name:
   * `{"binary": "p", "found": "<dist>/<version>"}` or
   * `{"binary": "p", "unreadable": "<reason>"}`. An unrecognised shape
   * is a decode failure.
   */
  given ReadWriter[Embedded] = readwriter[Value].bimap(
    (e: Embedded) =>
      e match
        case Embedded.Found(binary, identity) =>
          Obj(
            "binary" -> Str(binary),
            "found"  -> Str(s"${identity.distribution}/${identity.version.value}")
          )
        case Embedded.Unreadable(binary, reason) =>
          Obj("binary" -> Str(binary), "unreadable" -> Str(reason))
    ,
    (v: Value) =>
      v match
        case Obj(fields) if fields.contains("found") =>
          parse(fields("found").str) match
            case Some(t) => Embedded.Found(fields("binary").str, t)
            case None =>
              sys.error(
                s"unparseable embedded toolchain identity: ${v.render()}"
              ) // danger-scan:allow decode-failure — a corrupt record crashes the decode, never maps to a valid read
        case Obj(fields) if fields.contains("unreadable") =>
          Embedded.Unreadable(fields("binary").str, fields("unreadable").str)
        case other => // danger-scan:allow decode-failure — an unrecognised shape crashes the decode, never maps to a valid read
          sys.error(s"not an embedded toolchain read: ${other.render()}")
  )

end ToolchainIdentity
