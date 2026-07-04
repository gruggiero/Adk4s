package org.adk4s.memory

import hedgehog.Gen
import hedgehog.Range
import java.time.Instant
import org.adk4s.core.component.RetrieverConfig

/**
 * Hedgehog generators for spec:agent-memory and spec:memory-retriever-bridge
 * property tests.
 *
 * All generators are CONSTRUCTIVE (no `suchThat` filtering). Edge cases are
 * covered by the range bounds and `Gen.element1` choices.
 */
object Generators:

  /** Generates a `SourceType` from the 5 closed cases. */
  val genSourceType: Gen[SourceType] =
    Gen.element1(
      SourceType.Conversation,
      SourceType.Document,
      SourceType.StructuredData,
      SourceType.ToolResult,
      SourceType.ExternalApi
    )

  /** Generates an alpha-numeric string of 1..20 chars. */
  val genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 20))

  /** Generates an alpha-numeric query string of 1..10 chars. */
  val genQuery: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 10))

  /** Generates a non-empty alpha-numeric term of 3..8 chars. */
  val genTerm: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(3, 8))

  /** Generates surrounding alpha-numeric text of 0..10 chars. */
  val genSurrounding: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(0, 10))

  /** Generates an `Instant` from a bounded epoch-millis range. */
  val genInstant: Gen[Instant] =
    Gen.long(Range.linear(0L, 4102444800000L)).map(Instant.ofEpochMilli)

  /** Generates an optional Instant (50% Some, 50% None). */
  val genOptionalInstant: Gen[Option[Instant]] =
    Gen.choice1(genInstant.map(Some(_)), Gen.constant(Option.empty[Instant]))

  /** Generates an `Episode` with constructive fields. */
  val genEpisode: Gen[Episode] =
    for
      content    <- genContent
      sourceType <- genSourceType
      timestamp  <- genInstant
      groupId    <- Gen.constant(None)
      metadata   <- Gen.constant(Map.empty[String, String])
    yield Episode(content, sourceType, timestamp, groupId, metadata)

  /** Generates a list of 0..10 episodes. */
  val genEpisodes: Gen[List[Episode]] =
    Gen.list(genEpisode, Range.linear(0, 10))

  /** Generates k from 0..20. */
  val genK: Gen[Int] =
    Gen.int(Range.linear(0, 20))

  /** Generates k from 1..20 (avoids the trivial k=0 empty case). */
  val genKPositive: Gen[Int] =
    Gen.int(Range.linear(1, 20))

  /** Generates a `TemporalScope` from a bounded instant. */
  val genScope: Gen[TemporalScope] =
    genInstant.map(TemporalScope.apply)

  /** Generates an optional alpha-numeric provenance string. */
  val genProvenance: Gen[Option[String]] =
    Gen.choice1(
      Gen.string(Gen.alphaNum, Range.linear(1, 10)).map(Some(_)),
      Gen.constant(Option.empty[String])
    )

  /** Generates a small payload map (0..3 entries). */
  val genPayload: Gen[Map[String, String]] =
    Gen.list(
      for
        k <- Gen.string(Gen.alphaNum, Range.linear(1, 5))
        v <- Gen.string(Gen.alphaNum, Range.linear(1, 5))
      yield (k, v),
      Range.linear(0, 3)
    ).map(_.toMap)

  /** Generates a `MemoryHit` with constructive fields. */
  val genHit: Gen[MemoryHit] =
    for
      text       <- genContent
      score      <- Gen.double(Range.linearFrac(0.0, 1.0))
      validFrom  <- genOptionalInstant
      validTo    <- genOptionalInstant
      provenance <- genProvenance
      payload    <- genPayload
    yield MemoryHit(text, score, validFrom, validTo, provenance, payload)

  // ── Bridge-specific generators (spec:memory-retriever-bridge) ─────────────

  /** Generates a factory k from 1..20. */
  val genFactoryK: Gen[Int] =
    Gen.int(Range.linear(1, 20))

  /** Generates a config topK from 0..20. */
  val genTopK: Gen[Int] =
    Gen.int(Range.linear(0, 20))

  /** Generates a minScore from 0.0..1.0. */
  val genMinScore: Gen[Double] =
    Gen.double(Range.linearFrac(0.0, 1.0))

  /** Generates a `RetrieverConfig` from constructive topK + minScore. */
  val genConfig: Gen[RetrieverConfig] =
    for
      topK     <- genTopK
      minScore <- genMinScore
    yield RetrieverConfig(topK = topK, minScore = minScore)
