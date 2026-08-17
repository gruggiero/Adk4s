package org.adk4s.record

import io.github.iltotore.iron.RefinedType
import org.adk4s.core.error.ConfigError
import org.adk4s.core.tools.ToolInput
import org.adk4s.core.types.NonEmpty
import org.adk4s.record.canonical.CanonicalForm
import org.adk4s.record.canonical.Canonicalization
import smithy4s.json.Json

import java.security.MessageDigest

// ── CallKey — opaque type backed by a SHA-256 hex digest string ────────
// spec: add-adk4s-record/call-key — Requirement: CallKey is a stable digest
opaque type CallKey = String

object CallKey:
  /**
   * Compute a CallKey from a CanonicalForm via SHA-256 digest of the
   * canonical JSON serialization.
   *
   * Uses smithy4s.json.Json.write for deterministic serialization via
   * the generated Schema[CanonicalForm].
   */
  def fromCanonical(form: CanonicalForm): CallKey =
    val json      = Json.writeBlob(form).toUTF8String
    val digest    = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(json.getBytes("UTF-8"))
    val hexString = new StringBuilder(hashBytes.length * 2)
    for b <- hashBytes do
      val v = b & 0xff
      if v < 16 then hexString.append('0')
      hexString.append(Integer.toHexString(v))
    hexString.toString

  /** Construct a CallKey directly from a digest string (for testing / replay). */
  def fromDigest(digest: String): CallKey = digest

  extension (k: CallKey)
    def value: String  = k
    def render: String = k

  given CanEqual[CallKey, CallKey] = CanEqual.derived

// ── CanonicalForm companion — construction helpers ─────────────────────
// The CanonicalForm case class is generated from Smithy IDL. These helpers
// provide the convenience constructors that the spec requires.
object CanonicalFormOps:
  /** Construct a canonical form from a model call request. */
  def from(req: ModelCallRequest): CanonicalForm =
    Canonicalization.fromModelCall(req)

  /** Construct a canonical form from a tool call input. */
  def fromToolCall(input: ToolInput): CanonicalForm =
    Canonicalization.fromToolCall(input)

  /** Construct a canonical form from an embedding request. */
  def fromEmbedding(text: String, model: String): CanonicalForm =
    Canonicalization.fromEmbedding(text, model)

  /** Round-trip: reconstruct from JSON. */
  def fromJson(json: String): Either[String, CanonicalForm] =
    Json.read[CanonicalForm](smithy4s.Blob(json))(using CanonicalForm.schema) match
      case Right(form) => Right(form)
      case Left(err)   => Left(err.getMessage)

// ── RolloutId — Iron-refined non-empty string for deliberate resampling ─
// spec: add-adk4s-record/call-key — Requirement: RolloutId is a non-empty refined type
type RolloutId = RolloutId.T

object RolloutId extends RefinedType[String, NonEmpty]:
  /** Runtime smart constructor returning a structured ConfigError on failure. */
  def refineEither(s: String): Either[ConfigError, RolloutId] =
    either(s) match
      case Right(r) => Right(r)
      case Left(_)  => Left(ConfigError("RolloutId", s, "NonEmpty"))

// ── keyVersion — current canonicalization algorithm version ─────────────
// spec: add-adk4s-record/call-key — Requirement: keyVersion isolates canonicalization algorithm changes
val keyVersion: Int = 1
