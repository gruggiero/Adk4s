package org.adk4s.core.component

import cats.effect.*
import cats.effect.unsafe.implicits.global
import munit.*
import upickle.default.*

class RetrieverSpec extends CatsEffectSuite:

  test("Retrieve documents") {
    val retriever = Retriever.fromFunction[IO]((query: String, config: RetrieverConfig) =>
      IO.pure(List(
        Document("1", s"Result for $query"),
        Document("2", s"Another result for $query")
      ))
    )

    val result = retriever.retrieve("test query").unsafeRunSync()

    assertEquals(result.length, 2, "Should retrieve 2 documents")
    assert(result.headOption.getOrElse(fail("expected non-empty list")).content.contains("test query"), "Should contain query in content")
  }

  test("Retrieve documents as stream") {
    val retriever = Retriever.fromFunction[IO]((query: String, config: RetrieverConfig) =>
      IO.pure(List(
        Document("1", s"Result for $query"),
        Document("2", s"Another result for $query")
      ))
    )

    val result = retriever.retrieveStream("streaming docs").compile.toList.unsafeRunSync()

    assertEquals(result.length, 2, "Should stream 2 documents")
  }

  test("Empty retriever returns empty list") {
    val result = Retriever.empty.retrieve("any query").unsafeRunSync()

    assertEquals(result, Nil, "Should return empty list")
  }

  test("Empty retriever returns empty stream") {
    val result = Retriever.empty.retrieveStream("any query").compile.toList.unsafeRunSync()

    assertEquals(result, Nil, "Should return empty list")
  }

  test("Apply custom configuration") {
    val receivedConfig: Ref[IO, Option[RetrieverConfig]] = Ref.of[IO, Option[RetrieverConfig]](None).unsafeRunSync()
    val retriever = Retriever.fromFunction[IO]((query: String, config: RetrieverConfig) =>
      receivedConfig.update(_ => Some(config)).as(List(Document("1", s"Result with topK=${config.topK}")))
    )

    val customConfig = RetrieverConfig(topK = 10, minScore = 0.8)
    retriever.retrieve("test", customConfig).unsafeRunSync()

    val rc = receivedConfig.get.unsafeRunSync()
    assert(rc.isDefined, "Should receive config")
    val config = rc.getOrElse(fail("expected config"))
    assertEquals(config.topK, 10, "Should use custom topK")
    assertEquals(config.minScore, 0.8, "Should use custom minScore")
  }

  test("Create Retriever from function") {
    val calledWithQuery: Ref[IO, Option[String]] = Ref.of[IO, Option[String]](None).unsafeRunSync()
    val calledWithConfig: Ref[IO, Option[RetrieverConfig]] = Ref.of[IO, Option[RetrieverConfig]](None).unsafeRunSync()
    val retriever = Retriever.fromFunction[IO]((query: String, config: RetrieverConfig) =>
      calledWithQuery.update(_ => Some(query)) *> calledWithConfig.update(_ => Some(config)).as(List(Document("1", "Result")))
    )

    retriever.retrieve("test query").unsafeRunSync()

    val q = calledWithQuery.get.unsafeRunSync()
    val c = calledWithConfig.get.unsafeRunSync()
    assert(q.isDefined, "Should call function with query")
    assertEquals(q.getOrElse(fail("expected query")), "test query", "Should pass correct query")
    assert(c.isDefined, "Should call function with config")
  }

  test("Document with metadata") {
    val doc = Document(
      "doc1",
      "Sample content",
      Map("category" -> ujson.Str("tech"), "priority" -> ujson.Num(5))
    )

    assertEquals(doc.id, "doc1", "Should have correct id")
    assertEquals(doc.content, "Sample content", "Should have correct content")
    assertEquals(doc.metadata.size, 2, "Should have 2 metadata fields")
    assertEquals(doc.metadata("category"), ujson.Str("tech"), "Should preserve category")
    assertEquals(doc.metadata("priority"), ujson.Num(5), "Should preserve priority")
  }
