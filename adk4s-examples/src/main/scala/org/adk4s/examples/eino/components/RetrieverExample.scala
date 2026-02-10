package org.adk4s.examples.eino.components

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.chunking.ChunkingConfig
import org.llm4s.chunking.DocumentChunk
import org.llm4s.vectorstore.VectorRecord
import org.llm4s.vectorstore.VectorStore

/**
 * Eino equivalent: components/retriever
 *
 * Demonstrates retrieval via llm4s RAG components:
 * - In-memory vector store simulation (no external DB needed)
 * - Document chunking + indexing
 * - Semantic search (simulated with keyword matching for mock)
 *
 * Note: For production use, configure SQLiteVectorStore, PgVectorStore,
 * or QdrantVectorStore with a real EmbeddingClient.
 */
object RetrieverExample extends IOApp.Simple:

  // Simple in-memory "retriever" that simulates vector search with keyword matching
  final case class SimpleRetriever(documents: List[(String, String)]):
    def search(query: String, topK: Int): List[(String, String, Double)] =
      val queryWords: Set[String] = query.toLowerCase.split("\\s+").toSet
      documents
        .map { case (id: String, content: String) =>
          val contentWords: Set[String] = content.toLowerCase.split("\\s+").toSet
          val overlap: Int = queryWords.intersect(contentWords).size
          val score: Double = if queryWords.nonEmpty then overlap.toDouble / queryWords.size else 0.0
          (id, content, score)
        }
        .sortBy(-_._3)
        .take(topK)

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Retriever Example (Eino: components/retriever)")

      // 1. Prepare documents — chunk and index
      _ <- ExampleUtils.printSubSection("1. Document Preparation (Chunk + Index)")
      documents = List(
        ("doc1", "Cats are independent animals that purr when content. They are excellent hunters."),
        ("doc2", "Dogs are loyal companions that bark to communicate. They love playing fetch."),
        ("doc3", "Birds can fly and sing beautiful songs. Many species migrate seasonally."),
        ("doc4", "Fish live in water and breathe through gills. Some species are colorful."),
        ("doc5", "Machine learning uses algorithms to learn patterns from data automatically."),
        ("doc6", "Neural networks are inspired by biological brain structures for computation."),
        ("doc7", "Natural language processing helps computers understand human text and speech."),
        ("doc8", "Computer vision enables machines to interpret and analyze visual information.")
      )
      _ <- IO.println(s"   Indexed ${documents.size} documents")

      // 2. Semantic search (simulated)
      _ <- ExampleUtils.printSubSection("2. Semantic Search")
      retriever = SimpleRetriever(documents)
      query1 = "animals that make sounds"
      results1: List[(String, String, Double)] = retriever.search(query1, 3)
      _ <- IO.println(s"   Query: \"$query1\"")
      _ <- results1.foldLeft(IO.unit) { case (acc, (id: String, content: String, score: Double)) =>
        acc *> IO.println(f"   [$id] score=$score%.2f: ${content.take(60)}...")
      }

      // 3. Different query
      _ <- ExampleUtils.printSubSection("3. AI-Related Search")
      query2 = "machine learning neural networks"
      results2: List[(String, String, Double)] = retriever.search(query2, 3)
      _ <- IO.println(s"   Query: \"$query2\"")
      _ <- results2.foldLeft(IO.unit) { case (acc, (id: String, content: String, score: Double)) =>
        acc *> IO.println(f"   [$id] score=$score%.2f: ${content.take(60)}...")
      }

      // 4. Chunking + retrieval pipeline
      _ <- ExampleUtils.printSubSection("4. Chunk + Retrieve Pipeline")
      longDoc = """Machine learning is a branch of artificial intelligence.
                  |It uses statistical techniques to give computers the ability to learn.
                  |Supervised learning uses labeled training data.
                  |Unsupervised learning finds hidden patterns in data.
                  |Reinforcement learning learns through trial and error.""".stripMargin
      chunker = ChunkerFactory.sentence()
      config: ChunkingConfig = ChunkingConfig(targetSize = 100, maxSize = 150, overlap = 20, minChunkSize = 20)
      chunks: List[DocumentChunk] = chunker.chunk(longDoc, config).toList
      _ <- IO.println(s"   Document chunked into ${chunks.size} pieces")
      chunkDocs: List[(String, String)] = chunks.map((c: DocumentChunk) => (s"chunk-${c.index}", c.content))
      chunkRetriever = SimpleRetriever(chunkDocs)
      query3 = "learning from labeled data"
      results3: List[(String, String, Double)] = chunkRetriever.search(query3, 2)
      _ <- IO.println(s"   Query: \"$query3\"")
      _ <- results3.foldLeft(IO.unit) { case (acc, (id: String, content: String, score: Double)) =>
        acc *> IO.println(f"   [$id] score=$score%.2f: ${content.take(80)}...")
      }

      _ <- IO.println("\nRetriever example completed.")
      _ <- IO.println("Note: For production, use llm4s VectorStore + EmbeddingClient for real semantic search.")
    yield ()
