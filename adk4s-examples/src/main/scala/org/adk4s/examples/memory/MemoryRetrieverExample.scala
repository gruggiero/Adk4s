package org.adk4s.examples.memory

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.toFoldableOps
import org.adk4s.core.component.{ Document, Retriever, RetrieverConfig }
import org.adk4s.memory.MemoryRetriever

import java.nio.file.{ Files, Path }

/**
 * Memory Retriever example.
 *
 * Demonstrates bridging `AgentMemory` into the existing `Retriever` interface
 * via `MemoryRetriever`. This lets any agent that already consumes `Retriever`
 * read from memory with no new plumbing.
 *
 * Flow:
 *   1. Create a `FileBackedAgentMemory` and teach it two facts
 *   2. Build a `Retriever` via `MemoryRetriever(memory, k = 5)`
 *   3. Call `retriever.retrieve(query, config)` — same API as any other retriever
 *   4. Print the returned `Document`s (content + metadata)
 */
object MemoryRetrieverExample extends IOApp.Simple:

  private val dataDir: Path =
    Path.of(System.getProperty("java.io.tmpdir"), "adk4s-memory-retriever-demo")

  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Memory Retriever Example")
      _ <- IO.println("═" * 60)
      _ <- IO.println(s"  Data dir: $dataDir")
      _ <- IO.println("")

      // Clean slate
      _ <- IO.blocking {
        if Files.exists(dataDir) then
          val file: Path = dataDir.resolve("episodes.jsonl")
          if Files.exists(file) then Files.delete(file)
      }

      // 1. Create file-backed memory and teach it three facts
      memory <- FileBackedAgentMemory[IO](dataDir)
      _ <- memory.remember(
        org.adk4s.memory.Episode(
          "My favorite color is blue",
          org.adk4s.memory.SourceType.Conversation,
          java.time.Instant.parse("2026-07-19T12:00:00Z"),
          Some("fact-1"),
          Map.empty
        )
      )
      _ <- memory.remember(
        org.adk4s.memory.Episode(
          "Alice works at Meta as a software engineer",
          org.adk4s.memory.SourceType.Conversation,
          java.time.Instant.parse("2026-07-19T12:01:00Z"),
          Some("fact-2"),
          Map.empty
        )
      )
      _ <- memory.remember(
        org.adk4s.memory.Episode(
          "Bob works at Google as a product manager",
          org.adk4s.memory.SourceType.Conversation,
          java.time.Instant.parse("2026-07-19T12:02:00Z"),
          Some("fact-3"),
          Map.empty
        )
      )

      // 2. Build a Retriever backed by the memory (k = 2 per spec)
      retriever = MemoryRetriever[IO](memory, k = 2)

      // 3. Retrieve with the standard Retriever API — spec query: "favorite color"
      _     <- IO.println("─" * 60)
      _     <- IO.println("  Query: 'favorite color' (k=2, expect ≤ 2 docs)")
      _     <- IO.println("─" * 60)
      docs1 <- retriever.retrieve("favorite color", RetrieverConfig(topK = 2, minScore = 0.0))
      _     <- printDocs(docs1)

      _     <- IO.println("")
      _     <- IO.println("─" * 60)
      _     <- IO.println("  Query: 'works at' (matches 2 of 3 facts)")
      _     <- IO.println("─" * 60)
      docs2 <- retriever.retrieve("works at", RetrieverConfig(topK = 2, minScore = 0.0))
      _     <- printDocs(docs2)

      _     <- IO.println("")
      _     <- IO.println("─" * 60)
      _     <- IO.println("  Query: 'nonexistent' (no matches)")
      _     <- IO.println("─" * 60)
      docs3 <- retriever.retrieve("nonexistent", RetrieverConfig(topK = 2, minScore = 0.0))
      _     <- printDocs(docs3)

      _ <- IO.println("")
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Memory Retriever example completed.")
      _ <- IO.println("═" * 60)
    yield ()

  private def printDocs(docs: List[Document]): IO[Unit] =
    if docs.isEmpty then IO.println("  No documents retrieved.")
    else
      IO.println(s"  Retrieved ${docs.length} document(s):") *>
        docs.traverse_ { (d: Document) =>
          val score: String =
            d.metadata.get("score").map(_.toString).getOrElse("N/A")
          val provenance: String =
            d.metadata.get("provenance").map(_.toString).getOrElse("N/A")
          IO.println(s"    id=${d.id.take(16)}... score=$score provenance=$provenance") *>
            IO.println(s"    content=\"${d.content}\"")
        }
