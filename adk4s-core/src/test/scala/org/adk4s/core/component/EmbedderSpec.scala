package org.adk4s.core.component

import cats.effect.*
import cats.effect.unsafe.implicits.global
import munit.*
import scala.util.Random

class EmbedderSpec extends CatsEffectSuite:

  test("Embed single text") {
    val embedder = Embedder.mock[IO](1536)

    val result = embedder.embed("sample text").unsafeRunSync()

    assertEquals(result.length, 1536, "Should have correct dimension")
    assert(result.forall(v => v >= 0.0 && v <= 1.0), "All values should be in [0.0, 1.0]")
  }

  test("Embed multiple texts") {
    val embedder = Embedder.mock[IO](768)

    val result = embedder.embedBatch(List("text1", "text2", "text3")).unsafeRunSync()

    assertEquals(result.embeddings.length, 3, "Should embed 3 texts")
    assertEquals(result.embeddings.head.length, 768, "First vector should have 768 dimensions")
    assertEquals(result.embeddings(1).length, 768, "Second vector should have 768 dimensions")
    assertEquals(result.embeddings(2).length, 768, "Third vector should have 768 dimensions")
  }

  test("Query embedding dimension") {
    val embedder = Embedder.mock[IO](384)

    val result = embedder.dimension.unsafeRunSync()

    assertEquals(result, 384, "Should return correct dimension")
  }

  test("Mock embedder generates random vectors") {
    val embedder = Embedder.mock[IO](10)

    val vector = embedder.embed("any text").unsafeRunSync()

    assertEquals(vector.length, 10, "Should have 10 dimensions")
    assert(vector.forall(v => v >= 0.0 && v <= 1.0), "All values should be in [0.0, 1.0]")
  }

  test("Mock embedder batch generates independent vectors") {
    val embedder = Embedder.mock[IO](5)

    val result = embedder.embedBatch(List("a", "b", "c")).unsafeRunSync()

    assertEquals(result.embeddings.length, 3, "Should generate 3 vectors")
    assert(result.embeddings(0) != result.embeddings(1), "Vectors should be different")
    assert(result.embeddings(1) != result.embeddings(2), "Vectors should be different")
    assert(result.embeddings(0) != result.embeddings(2), "Vectors should be different")
  }

  test("EmbeddingResult includes usage information") {
    val embedder = Embedder.mock[IO](1536)

    val result = embedder.embedBatch(List("hello world", "test text")).unsafeRunSync()

    assert(result.usage.isDefined, "Should have usage information")
    assertEquals(result.usage.get.promptTokens, 4, "Should count 4 tokens (2 + 2)")
    assertEquals(result.usage.get.totalTokens, 4, "Total should equal prompt tokens")
  }

  test("Multiple calls produce different vectors") {
    val embedder = Embedder.mock[IO](100)

    val vector1 = embedder.embed("same text").unsafeRunSync()
    val vector2 = embedder.embed("same text").unsafeRunSync()

    assert(vector1 != vector2, "Random vectors should differ")
  }

  test("Mock embedder with default dimension") {
    val embedder = Embedder.mock[IO]()

    val result = embedder.dimension.unsafeRunSync()

    assertEquals(result, 1536, "Should have default dimension of 1536")
  }
