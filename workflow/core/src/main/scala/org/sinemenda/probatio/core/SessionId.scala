package org.sinemenda.probatio.core

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A session identity whose filename-safe encoding is lossless.
 *
 * Constructed only via `fromRaw` / `resolve` — the opaque type has no public
 * `apply`, so a raw string cannot become a `SessionId` without passing
 * through the encoder's contract. `encoded` is injective: two distinct raw
 * identities always map to distinct state-file names, so a brand-new session
 * can never collide onto another session's suppression state.
 *
 * Forward reference (recorded at spec 3 Step 0): implementation-order.md
 * assigns `SessionId` to spec 7; spec 3 introduces it because suppression
 * keying and `gate-payload.bats`'s session tests need it now. Spec 7 uses it;
 * spec 8 records it as modified, not introduced.
 *
 * spec: live-fact-banner — Property: suppression-tracks-facts
 * spec: gate-event-completeness — Requirement: Two distinct sessions never share suppression state
 * spec: gate-event-completeness — Property: session-identity-encoding-is-injective
 */
opaque type SessionId = String

object SessionId:

  /**
   * Construct a session identity from a raw, adapter-supplied string.
   * The raw value is stored unmodified; `encoded` performs the lossless
   * filename-safe transform.
   */
  def fromRaw(raw: String): SessionId = raw

  /**
   * Resolve the session identity from the available signals, strongest
   * first, matching the predecessor's order:
   *   1. explicit `--session` argument (adapter-provided)
   *   2. harness session signal (`CLAUDE_CODE_SESSION_ID`)
   *   3. generic override (`VERIFIED_SCALA3_SESSION_ID`)
   *   4. `ppid-<parentPid>` fallback (inference, not a confirmed fact)
   *
   * Empty strings are treated as absent.
   *
   * spec: gate-event-completeness — Scenario: Edge case — an identity is resolved from the strongest available signal
   */
  def resolve(
    explicit: Option[String],
    harness: Option[String],
    genericOverride: Option[String],
    parentPid: Long
  ): SessionId =
    val chosen: Option[String] =
      explicit
        .filter(_.nonEmpty)
        .orElse(harness.filter(_.nonEmpty))
        .orElse(genericOverride.filter(_.nonEmpty))
    chosen match
      case Some(raw) => fromRaw(raw)
      case None      => fromRaw(s"ppid-$parentPid")

  extension (s: SessionId)
    /** The raw session identity, as supplied. */
    def raw: String = s

    /**
     * The lossless filename-safe encoding: base64 of the UTF-8 bytes with
     * `+`→`-`, `/`→`_`, `=`→`.` — the same character substitution the
     * predecessor applies (`jq -Rr '@base64' | tr '+/=' '-_.'`). Injective:
     * base64 is injective and the substitution is a bijection on the base64
     * output alphabet (the three replaced characters never otherwise appear).
     */
    def encoded: String =
      Base64.getEncoder
        .encodeToString(s.getBytes(StandardCharsets.UTF_8))
        .replace('+', '-')
        .replace('/', '_')
        .replace('=', '.')

end SessionId
