package org.adk4s.examples.eino.components

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.chunking.ChunkingConfig
import org.llm4s.chunking.DocumentChunk
import org.llm4s.rag.loader.LoadResult
import org.llm4s.rag.loader.TextLoader

/**
 * Eino equivalent: components/document
 *
 * Demonstrates document loading via llm4s RAG:
 * - TextLoader for raw text ingestion
 * - DocumentChunker with different strategies (Simple, Sentence)
 * - Chunk metadata inspection
 */
object DocumentLoaderExample extends IOApp.Simple:

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Document Loader Example (Eino: components/document)")

      // 1. TextLoader — load raw text as a document
      _ <- ExampleUtils.printSubSection("1. TextLoader — Raw Text Ingestion")
      sampleText = """Artificial intelligence (AI) is intelligence demonstrated by machines.
                     |AI research has been defined as the field of study of intelligent agents.
                     |Machine learning is a subset of AI that focuses on learning from data.
                     |Deep learning uses neural networks with many layers.
                     |Natural language processing enables computers to understand human language.""".stripMargin
      textLoader: TextLoader = TextLoader(sampleText, "sample-doc")
      loadResults: Iterator[LoadResult] = textLoader.load()
      successCount: Int = loadResults.count {
        case _: LoadResult.Success => true
        case _ => false
      }
      _ <- IO.println(s"   Loaded documents: $successCount")

      // 2. Simple chunking — fixed-size chunks
      _ <- ExampleUtils.printSubSection("2. Simple Chunking (fixed-size)")
      simpleConfig: ChunkingConfig = ChunkingConfig(
        targetSize = 100,
        maxSize = 150,
        overlap = 20,
        minChunkSize = 20
      )
      simpleChunker = ChunkerFactory.simple()
      simpleChunks: List[DocumentChunk] = simpleChunker.chunk(sampleText, simpleConfig).toList
      _ <- IO.println(s"   Text length: ${sampleText.length} chars")
      _ <- IO.println(s"   Target size: ${simpleConfig.targetSize}, overlap: ${simpleConfig.overlap}")
      _ <- IO.println(s"   Chunks produced: ${simpleChunks.size}")
      _ <- simpleChunks.zipWithIndex.foldLeft(IO.unit) { case (acc, (chunk: DocumentChunk, idx: Int)) =>
        acc *> IO.println(s"   Chunk $idx (${chunk.content.length} chars): ${chunk.content.take(60)}...")
      }

      // 3. Sentence chunking — split on sentence boundaries
      _ <- ExampleUtils.printSubSection("3. Sentence Chunking")
      sentenceConfig: ChunkingConfig = ChunkingConfig(
        targetSize = 150,
        maxSize = 250,
        overlap = 30,
        minChunkSize = 30
      )
      sentenceChunker = ChunkerFactory.sentence()
      sentenceChunks: List[DocumentChunk] = sentenceChunker.chunk(sampleText, sentenceConfig).toList
      _ <- IO.println(s"   Strategy: sentence")
      _ <- IO.println(s"   Chunks produced: ${sentenceChunks.size}")
      _ <- sentenceChunks.zipWithIndex.foldLeft(IO.unit) { case (acc, (chunk: DocumentChunk, idx: Int)) =>
        acc *> IO.println(s"   Chunk $idx (${chunk.content.length} chars): ${chunk.content.take(80)}...")
      }

      // 4. Chunk metadata — chunkWithSource populates sourceFile;
      //    offsets are not tracked by SimpleChunker, so we compute them manually.
      _ <- ExampleUtils.printSubSection("4. Chunk Metadata")
      sourceChunks: Seq[DocumentChunk] = simpleChunker.chunkWithSource(sampleText, "sample-doc.txt", simpleConfig)
      enrichedChunks: Seq[DocumentChunk] = sourceChunks.foldLeft((List.empty[DocumentChunk], 0)) {
        case ((acc: List[DocumentChunk], offset: Int), chunk: DocumentChunk) =>
          val startOffset: Int = sampleText.indexOf(chunk.content, offset)
          val resolvedStart: Int = if startOffset >= 0 then startOffset else offset
          val endOffset: Int = resolvedStart + chunk.content.length
          val enriched: DocumentChunk = chunk.copy(
            metadata = chunk.metadata.copy(
              startOffset = Some(resolvedStart),
              endOffset = Some(endOffset)
            )
          )
          (acc :+ enriched, resolvedStart + 1)
      }._1
      _ <- enrichedChunks.headOption match
        case Some(chunk: DocumentChunk) =>
          IO.println(s"   First chunk index: ${chunk.index}") *>
            IO.println(s"   Source file: ${chunk.metadata.sourceFile.getOrElse("N/A")}") *>
            IO.println(s"   Start offset: ${chunk.metadata.startOffset.getOrElse("N/A")}") *>
            IO.println(s"   End offset: ${chunk.metadata.endOffset.getOrElse("N/A")}")
        case None =>
          IO.println("   No chunks available")

      // 5. Markdown chunking
      _ <- ExampleUtils.printSubSection("5. Markdown Chunking")
      markdownText = """# Introduction
                       |AI is transforming the world.
                       |
                       |## Machine Learning
                       |ML is a subset of AI that learns from data.
                       |
                       |## Deep Learning
                       |DL uses neural networks with many layers.
                       |
                       |```python
                       |model = Sequential()
                       |model.add(Dense(64, activation='relu'))
                       |```""".stripMargin
      markdownChunker = ChunkerFactory.markdown()
      markdownChunks: List[DocumentChunk] = markdownChunker.chunk(markdownText).toList
      _ <- IO.println(s"   Strategy: markdown")
      _ <- IO.println(s"   Chunks produced: ${markdownChunks.size}")
      _ <- markdownChunks.zipWithIndex.foldLeft(IO.unit) { case (acc, (chunk: DocumentChunk, idx: Int)) =>
        acc *> IO.println(s"   Chunk $idx: ${chunk.content.take(60).replace("\n", "\\n")}...")
      }

      _ <- IO.println("\nDocument loader example completed.")
    yield ()
