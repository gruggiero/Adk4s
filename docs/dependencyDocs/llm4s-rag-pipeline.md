# LLM4S RAG Pipeline

## Overview

LLM4S provides a **complete, production-grade RAG (Retrieval-Augmented Generation) pipeline** in the `org.llm4s.rag` package. It supports document ingestion from multiple sources, chunking with multiple strategies, hybrid search (vector + keyword), reranking, permission-aware queries, and LLM-powered answer generation.

**Key Features**:
- Builder-pattern pipeline construction
- Multiple document loaders (file, directory, URL, web crawler, S3)
- Document extraction (PDF, DOCX, DOC, HTML, JSON, XML, CSV, Markdown, plain text)
- Multiple chunking strategies (simple, sentence, markdown, semantic)
- Hybrid search with vector + keyword fusion
- Cross-encoder reranking (Cohere, LLM-based)
- Permission-aware search and ingestion
- Incremental sync with version tracking
- Async parallel ingestion
- RAG evaluation metrics (RAGAS)

---

## Core Components

### RAG Class

**Purpose**: High-level pipeline orchestrating ingestion, search, and answer generation.

```scala
final class RAG private (
  val config: RAGConfig,
  private val embeddingClient: EmbeddingClient,
  private val embeddingModelConfig: EmbeddingModelConfig,
  private val chunker: DocumentChunker,
  private val hybridSearcher: HybridSearcher,
  private val reranker: Option[Reranker],
  private val llmClient: Option[LLMClient],
  private val tracer: Option[Tracing],
  private val registry: DocumentRegistry
) extends Closeable
```

**Construction** via builder:
```scala
val rag = RAG.builder()
  .withEmbeddings(EmbeddingProvider.OpenAI)
  .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
  .build()
  .toOption.get

// With LLM for answer generation
val ragWithLLM = RAG.builder()
  .withEmbeddings(EmbeddingProvider.OpenAI)
  .withLLM(llmClient)
  .build()
  .toOption.get
```

---

## Ingestion API

### From Files and Directories

```scala
// Ingest a single file (supports .txt, .md, .pdf, .docx, etc.)
rag.ingest("./docs/report.pdf"): Result[Int]

// Ingest with metadata
rag.ingest("./docs/report.pdf", Map("department" -> "engineering")): Result[Int]

// Ingest from Path
rag.ingest(Path.of("./docs")): Result[Int]

// Ingest a directory (recursively)
rag.ingestPath(Path.of("./docs"), Map("source" -> "local")): Result[Int]
```

### From Raw Text

```scala
rag.ingestText(
  content = "Scala is a functional programming language...",
  documentId = "scala-intro",
  metadata = Map("topic" -> "programming")
): Result[Int]  // Returns number of chunks created
```

### From Raw Bytes (Source-Agnostic)

```scala
// Ingest from bytes (works for S3, HTTP responses, databases, etc.)
rag.ingestBytes(
  content = pdfBytes,
  filename = "report.pdf",       // Used for format detection
  documentId = "report-2024",
  metadata = Map("year" -> "2024")
): Result[Int]

// Batch ingest from bytes
rag.ingestBytesMultiple(
  documents = Iterator(
    (bytes1, "doc1.pdf", "doc1", Map.empty),
    (bytes2, "doc2.docx", "doc2", Map.empty)
  )
): Result[LoadStats]
```

### From DocumentLoader

```scala
import org.llm4s.rag.loader._

// File loader
rag.ingest(DocumentLoaders.file("./report.pdf")): Result[LoadStats]

// Directory loader
rag.ingest(DocumentLoaders.directory("./docs")): Result[LoadStats]

// URL loader
rag.ingest(DocumentLoaders.url(Seq("https://example.com/doc"))): Result[LoadStats]

// Web crawler
rag.ingest(DocumentLoaders.webCrawler("https://docs.example.com", maxDepth = 3)): Result[LoadStats]

// Combine loaders
val combined = DocumentLoaders.directory("./docs") ++ DocumentLoaders.url(urls)
rag.ingest(combined): Result[LoadStats]
```

### Pre-Chunked Content

```scala
rag.ingestChunks(
  documentId = "manual-chunks",
  chunks = Seq("Chunk 1 text...", "Chunk 2 text..."),
  metadata = Map("source" -> "custom")
): Result[Int]
```

### Async Parallel Ingestion

```scala
import scala.concurrent.ExecutionContext.Implicits.global

rag.ingestAsync(loader): Future[Result[LoadStats]]
```

---

## Document Loaders

### DocumentLoader Trait

```scala
trait DocumentLoader {
  def load(): Iterator[LoadResult]
  def estimatedCount: Option[Int] = None
  def description: String
  def ++(other: DocumentLoader): DocumentLoader
}
```

### Built-in Loaders

| Loader | Factory | Description |
|---|---|---|
| `FileLoader` | `DocumentLoaders.file(path)` | Single file (PDF, DOCX, text, etc.) |
| `DirectoryLoader` | `DocumentLoaders.directory(path)` | Recursive directory scan |
| `TextLoader` | `DocumentLoaders.text(id, content)` | Raw text content |
| `UrlLoader` | `DocumentLoaders.url(urls)` | HTTP/HTTPS URLs |
| `WebCrawlerLoader` | `DocumentLoaders.webCrawler(url, maxDepth)` | Web crawler with robots.txt support |
| `SourceBackedLoader` | Custom | S3 / cloud storage integration |

### LoadResult

```scala
sealed trait LoadResult {
  def isSuccess: Boolean
  def isFailure: Boolean
  def isSkipped: Boolean
  def toOption: Option[Document]
}

object LoadResult {
  case class Success(document: Document) extends LoadResult
  case class Failure(source: String, error: LLMError, recoverable: Boolean = false) extends LoadResult
  case class Skipped(source: String, reason: String) extends LoadResult
}
```

### LoadStats

```scala
final case class LoadStats(
  totalAttempted: Int,
  successful: Int,
  failed: Int,
  skipped: Int,
  errors: Seq[(String, LLMError)] = Seq.empty
) {
  def successRate: Double
  def hasErrors: Boolean
}
```

---

## Document Extraction

### DocumentExtractor Trait

```scala
trait DocumentExtractor {
  def extract(content: Array[Byte], filename: String, mimeType: Option[String] = None): Result[ExtractedDocument]
  def extractFromStream(input: InputStream, filename: String, mimeType: Option[String] = None): Result[ExtractedDocument]
  def detectMimeType(content: Array[Byte], filename: String): String
  def canExtract(mimeType: String): Boolean
}
```

### Supported Formats

| Format | Extension | MIME Type |
|---|---|---|
| Plain Text | `.txt` | `text/plain` |
| Markdown | `.md` | `text/markdown` |
| PDF | `.pdf` | `application/pdf` |
| Word (DOCX) | `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| Word (DOC) | `.doc` | `application/msword` |
| HTML | `.html` | `text/html` |
| JSON | `.json` | `application/json` |
| XML | `.xml` | `application/xml` |
| CSV | `.csv` | `text/csv` |

---

## Chunking Strategies

### DocumentChunker Trait

```scala
trait DocumentChunker {
  def chunk(text: String, config: ChunkingConfig = ChunkingConfig.default): Seq[DocumentChunk]
  def chunkWithSource(text: String, sourceFile: String, config: ChunkingConfig = ChunkingConfig.default): Seq[DocumentChunk]
}
```

### Available Strategies

| Strategy | Class | Best For |
|---|---|---|
| **Simple** | `SimpleChunker` | Basic character-based splitting |
| **Sentence** | `SentenceChunker` | Respects sentence boundaries |
| **Markdown** | `MarkdownChunker` | Preserves markdown structure (headings, code blocks) |
| **Semantic** | `SemanticChunker` | Splits at topic boundaries using embeddings |

### ChunkingConfig

```scala
final case class ChunkingConfig(
  targetSize: Int = 800,          // Target chunk size in characters (soft limit)
  maxSize: Int = 1200,            // Maximum chunk size (hard limit)
  overlap: Int = 150,             // Characters to overlap between chunks
  minChunkSize: Int = 100,        // Minimum size for a chunk
  preserveCodeBlocks: Boolean = true,
  preserveHeadings: Boolean = true
)

// Presets
ChunkingConfig.default  // 800 target, 150 overlap
ChunkingConfig.small    // 400 target, 75 overlap
ChunkingConfig.large    // 1500 target, 250 overlap
ChunkingConfig.noOverlap
```

### DocumentChunk

```scala
final case class DocumentChunk(
  content: String,
  index: Int,
  metadata: ChunkMetadata = ChunkMetadata.empty
)

final case class ChunkMetadata(
  sourceFile: Option[String] = None,
  startOffset: Option[Int] = None,
  endOffset: Option[Int] = None,
  headings: Seq[String] = Seq.empty,
  isCodeBlock: Boolean = false,
  language: Option[String] = None
)
```

---

## Vector Stores

### VectorStore Trait

```scala
trait VectorStore {
  def upsert(record: VectorRecord): Result[Unit]
  def upsertBatch(records: Seq[VectorRecord]): Result[Unit]
  def search(queryVector: Array[Float], topK: Int = 10, filter: Option[MetadataFilter] = None): Result[Seq[ScoredRecord]]
  def get(id: String): Result[Option[VectorRecord]]
  def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]]
  def delete(id: String): Result[Unit]
  def deleteBatch(ids: Seq[String]): Result[Unit]
  def deleteByPrefix(prefix: String): Result[Long]
  def deleteByFilter(filter: MetadataFilter): Result[Long]
  def count(filter: Option[MetadataFilter] = None): Result[Long]
  def list(limit: Int = 100, offset: Int = 0, filter: Option[MetadataFilter] = None): Result[Seq[VectorRecord]]
  def clear(): Result[Unit]
  def stats(): Result[VectorStoreStats]
  def close(): Unit
}
```

### Implementations

| Implementation | Backend | Use Case |
|---|---|---|
| `SQLiteVectorStore` | SQLite | Local development, single-node |
| `PgVectorStore` | PostgreSQL + pgvector | Production, multi-node |
| `QdrantVectorStore` | Qdrant | High-performance vector search |

### VectorRecord

```scala
final case class VectorRecord(
  id: String,
  embedding: Array[Float],
  content: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)
```

### MetadataFilter DSL

```scala
import org.llm4s.vectorstore.MetadataFilter._

// Simple filters
Equals("department", "engineering")
Contains("title", "scala")
HasKey("author")
In("category", Set("tech", "science"))

// Composable
val filter = Equals("dept", "eng") and Contains("title", "scala")
val filter2 = HasKey("author") or HasKey("editor")
val filter3 = !Equals("status", "draft")
```

---

## Keyword Index

```scala
trait KeywordIndex {
  def index(id: String, content: String, metadata: Map[String, String] = Map.empty): Result[Unit]
  def search(query: String, topK: Int = 10, filter: Option[MetadataFilter] = None): Result[Seq[ScoredRecord]]
  def delete(id: String): Result[Unit]
  def deleteByPrefix(prefix: String): Result[Long]
  def clear(): Result[Unit]
}
```

**Implementations**: `SQLiteKeywordIndex`, `PgKeywordIndex`

---

## Hybrid Search

The `HybridSearcher` combines vector similarity and keyword search with reciprocal rank fusion:

```scala
class HybridSearcher(
  val vectorStore: VectorStore,
  val keywordIndex: KeywordIndex,
  vectorWeight: Double = 0.7,   // Weight for vector results
  keywordWeight: Double = 0.3   // Weight for keyword results
)
```

---

## Reranking

### Reranker Trait

```scala
trait Reranker {
  def rerank(request: RerankRequest): Result[RerankResponse]
}

final case class RerankRequest(
  query: String,
  documents: Seq[String],
  topK: Option[Int] = None,
  returnDocuments: Boolean = true
)

final case class RerankResponse(
  results: Seq[RerankResult],
  metadata: Map[String, String] = Map.empty
)

final case class RerankResult(
  index: Int,
  score: Double,
  document: String
)
```

### Implementations

| Implementation | Provider | Description |
|---|---|---|
| `CohereReranker` | Cohere | Cross-encoder reranking via Cohere API |
| `LLMReranker` | Any LLM | LLM-as-judge reranking (slower, no API key needed) |

**Usage**:
```scala
val reranker = RerankerFactory.cohere(RerankProviderConfig(
  baseUrl = "https://api.cohere.ai",
  apiKey = sys.env("COHERE_API_KEY"),
  model = "rerank-english-v3.0"
))

val response = reranker.rerank(RerankRequest(
  query = "What is Scala?",
  documents = Seq("Scala is a programming language", "Python is popular"),
  topK = Some(5)
))
```

---

## Query API

### Semantic Search

```scala
// Search for relevant chunks
rag.query("What is Scala?"): Result[Seq[RAGSearchResult]]

// With custom topK
rag.query("What is Scala?", topK = Some(5)): Result[Seq[RAGSearchResult]]
```

### Search + Answer Generation

```scala
// Requires LLM client configured via .withLLM(client)
rag.queryWithAnswer("What is Scala?"): Result[RAGAnswerResult]
rag.queryWithAnswer("What is Scala?", topK = Some(5)): Result[RAGAnswerResult]
```

### Permission-Aware Search

```scala
// Requires SearchIndex configured via .withSearchIndex(index)
rag.queryWithPermissions(
  auth = UserAuthorization(principalIds = Set(PrincipalId("user-123"))),
  collectionPattern = CollectionPattern.descendants("confluence"),
  queryText = "deployment guide",
  topK = Some(10)
): Result[Seq[RAGSearchResult]]

// Permission-aware search + answer
rag.queryWithPermissionsAndAnswer(
  auth = auth,
  collectionPattern = pattern,
  question = "How to deploy?",
  topK = Some(10)
): Result[RAGAnswerResult]
```

### Permission-Aware Ingestion

```scala
rag.ingestWithPermissions(
  collectionPath = CollectionPath("confluence/engineering/docs"),
  documentId = "deploy-guide",
  content = "Deployment steps...",
  metadata = Map("author" -> "alice"),
  readableBy = Set(PrincipalId("team-engineering"))
): Result[Int]
```

---

## Sync and Versioning

### Incremental Sync

```scala
// Only process changed documents (compares content hashes)
rag.sync(loader): Result[SyncStats]

// Async sync with parallel change detection
rag.syncAsync(loader): Future[Result[SyncStats]]
```

### SyncStats

```scala
final case class SyncStats(
  added: Int,
  updated: Int,
  deleted: Int,
  unchanged: Int
) {
  def total: Int
  def changed: Int
  def hasChanges: Boolean
}
```

### Full Refresh

```scala
// Clear everything and re-ingest
rag.refresh(loader): Result[SyncStats]
rag.refreshAsync(loader): Future[Result[SyncStats]]
```

### Version Checking

```scala
rag.needsUpdate(document): Result[Boolean]
rag.deleteDocument(docId): Result[Unit]
```

---

## RAGConfig

```scala
final case class RAGConfig(
  chunkingConfig: ChunkingConfig,
  topK: Int = 10,
  loadingConfig: LoadingConfig,
  searchIndex: Option[SearchIndex] = None
)

final case class LoadingConfig(
  batchSize: Int = 10,
  parallelism: Int = 4,
  failFast: Boolean = false,
  skipEmptyDocuments: Boolean = true,
  enableVersioning: Boolean = true,
  useHints: Boolean = true
)
```

---

## RAG Result Types

```scala
final case class RAGSearchResult(
  content: String,
  score: Double,
  metadata: Map[String, String],
  documentId: Option[String],
  chunkIndex: Option[Int]
)

final case class RAGAnswerResult(
  answer: String,
  sources: Seq[RAGSearchResult],
  confidence: Option[Double]
)
```

---

## Complete Example

```scala
import org.llm4s.rag.RAG
import org.llm4s.rag.loader.DocumentLoaders
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.llmconnect.LLM

// 1. Build pipeline
val rag = RAG.builder()
  .withEmbeddings(EmbeddingProvider.OpenAI)
  .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
  .withLLM(LLM.client())
  .build()
  .toOption.get

// 2. Ingest documents
val loader = DocumentLoaders.directory("./docs") ++
             DocumentLoaders.url(Seq("https://docs.example.com/api"))

rag.ingest(loader) match {
  case Right(stats) =>
    println(s"Ingested: ${stats.successful} docs, ${stats.failed} failures")
  case Left(error) =>
    println(s"Error: ${error.message}")
}

// 3. Search
rag.query("How to configure authentication?") match {
  case Right(results) =>
    results.foreach { r =>
      println(s"Score ${r.score}: ${r.content.take(100)}...")
    }
  case Left(error) =>
    println(s"Search error: ${error.message}")
}

// 4. Search + Answer
rag.queryWithAnswer("How to configure authentication?") match {
  case Right(answer) =>
    println(s"Answer: ${answer.answer}")
    println(s"Sources: ${answer.sources.size}")
  case Left(error) =>
    println(s"Error: ${error.message}")
}

// 5. Incremental sync (only process changes)
rag.sync(loader) match {
  case Right(stats) =>
    println(s"Added: ${stats.added}, Updated: ${stats.updated}, Deleted: ${stats.deleted}")
  case Left(error) =>
    println(s"Sync error: ${error.message}")
}

// 6. Cleanup
rag.close()
```

---

## Environment Variables

```bash
# Embeddings
EMBEDDING_PROVIDER=openai
OPENAI_API_KEY=sk-...

# Reranking (optional)
COHERE_API_KEY=...

# LLM for answer generation (optional)
LLM_MODEL=openai/gpt-4o
```

---

## Next Steps

- **Memory System**: See `llm4s-memory-system.md` for agent memory
- **Guardrails**: See `llm4s-guardrails.md` for input/output validation and RAG guardrails
- **Orchestration**: See `llm4s-orchestration.md` for multi-agent DAG execution
- **Core API**: See `llm4s-core-api.md` for foundational concepts
