# LLM4S Agent Memory System

## Overview

LLM4S provides a comprehensive agent memory system in `org.llm4s.agent.memory`. It supports conversation history, entity tracking, knowledge storage, user facts, and task outcomes — all with semantic search, composable filters, and multiple storage backends.

**Key Features**:
- Typed memory entries (Conversation, Entity, Knowledge, UserFact, Task, Custom)
- Composable filter DSL with `and`, `or`, `not` operators
- Semantic search via embeddings
- Multiple backends (InMemory, SQLite, VectorMemoryStore)
- High-level `MemoryManager` for agent integration
- Entity extraction and memory consolidation
- Configurable auto-cleanup

---

## Core Data Types

### Memory

```scala
final case class Memory(
  id: MemoryId,
  content: String,
  memoryType: MemoryType,
  metadata: Map[String, String] = Map.empty,
  timestamp: Instant = Instant.now(),
  importance: Option[Double] = None,        // 0.0 to 1.0
  embedding: Option[Array[Float]] = None    // Pre-computed embedding
) {
  def withMetadata(key: String, value: String): Memory
  def withMetadata(newMetadata: Map[String, String]): Memory
  def withImportance(score: Double): Memory
  def withEmbedding(vector: Array[Float]): Memory
  def isEmbedded: Boolean
  def source: Option[String]
  def conversationId: Option[String]
  def agentId: Option[String]
}
```

### Smart Constructors

```scala
object Memory {
  // Conversation memory from a message
  def fromConversation(content: String, role: String, conversationId: Option[String] = None): Memory

  // Entity fact
  def forEntity(entityId: EntityId, entityName: String, fact: String, entityType: String = "unknown"): Memory

  // Knowledge from external source
  def fromKnowledge(content: String, source: String, chunkIndex: Option[Int] = None): Memory

  // User preference/fact
  def userFact(fact: String, userId: Option[String] = None): Memory

  // Task completion record
  def fromTask(taskDescription: String, outcome: String, success: Boolean): Memory
}
```

### MemoryType

```scala
sealed trait MemoryType { def name: String }

object MemoryType {
  case object Conversation extends MemoryType  // Auto-captured from agent interactions
  case object Entity extends MemoryType        // Facts about people, orgs, concepts
  case object Knowledge extends MemoryType     // External documents / knowledge bases
  case object UserFact extends MemoryType      // User preferences, background
  case object Task extends MemoryType          // Completed tasks and outcomes
  case class Custom(name: String) extends MemoryType

  def fromString(s: String): MemoryType
  val builtIn: Set[MemoryType]
}
```

### Type-Safe IDs

```scala
final case class MemoryId(value: String) extends AnyVal
object MemoryId {
  def generate(): MemoryId  // UUID-based
}

final case class EntityId(value: String) extends AnyVal
object EntityId {
  def generate(): EntityId
  def fromName(name: String): EntityId  // Normalized: lowercase, spaces → underscores
}
```

---

## MemoryFilter DSL

Composable predicates for filtering memories during recall.

### Filter Types

```scala
sealed trait MemoryFilter {
  def matches(memory: Memory): Boolean
  def and(other: MemoryFilter): MemoryFilter
  def or(other: MemoryFilter): MemoryFilter
  def not: MemoryFilter
  def &&(other: MemoryFilter): MemoryFilter   // Alias for and
  def ||(other: MemoryFilter): MemoryFilter   // Alias for or
  def unary_! : MemoryFilter                  // Alias for not
}

object MemoryFilter {
  case object All extends MemoryFilter                                    // Match all
  case object None extends MemoryFilter                                   // Match none
  case class ByType(memoryType: MemoryType) extends MemoryFilter
  case class ByTypes(memoryTypes: Set[MemoryType]) extends MemoryFilter
  case class ByMetadata(key: String, value: String) extends MemoryFilter
  case class HasMetadata(key: String) extends MemoryFilter
  case class MetadataContains(key: String, substring: String) extends MemoryFilter
  case class ByEntity(entityId: EntityId) extends MemoryFilter
  case class ByConversation(conversationId: String) extends MemoryFilter
  case class ByTimeRange(after: Option[Instant], before: Option[Instant]) extends MemoryFilter
  case class MinImportance(threshold: Double) extends MemoryFilter
  case class ContentContains(substring: String, caseSensitive: Boolean = false) extends MemoryFilter
  case class And(left: MemoryFilter, right: MemoryFilter) extends MemoryFilter
  case class Or(left: MemoryFilter, right: MemoryFilter) extends MemoryFilter
  case class Not(filter: MemoryFilter) extends MemoryFilter
  case class Custom(predicate: Memory => Boolean) extends MemoryFilter
}
```

### Convenience Constructors

```scala
MemoryFilter.conversations                     // Only conversation memories
MemoryFilter.entities                          // Only entity memories
MemoryFilter.knowledge                         // Only knowledge memories
MemoryFilter.userFacts                         // Only user fact memories
MemoryFilter.tasks                             // Only task memories
MemoryFilter.after(instant)                    // After a timestamp
MemoryFilter.before(instant)                   // Before a timestamp
MemoryFilter.between(start, end)               // Time range
MemoryFilter.forConversation(conversationId)   // Specific conversation
MemoryFilter.forEntity(entityId)               // Specific entity
MemoryFilter.important(minScore = 0.5)         // High-importance only
MemoryFilter.all(filter1, filter2, ...)        // AND multiple filters
MemoryFilter.any(filter1, filter2, ...)        // OR multiple filters
```

### Usage Examples

```scala
// Recent important entity memories
val filter = MemoryFilter.entities &&
             MemoryFilter.important(0.7) &&
             MemoryFilter.after(Instant.now().minus(7, ChronoUnit.DAYS))

// Conversation or knowledge memories about a topic
val filter2 = (MemoryFilter.conversations || MemoryFilter.knowledge) &&
              MemoryFilter.ContentContains("scala")

// Everything except task memories
val filter3 = !MemoryFilter.tasks
```

---

## MemoryStore

### Trait

```scala
trait MemoryStore {
  def store(memory: Memory): Result[MemoryStore]
  def storeAll(memories: Seq[Memory]): Result[MemoryStore]
  def get(id: MemoryId): Result[Option[Memory]]
  def recall(filter: MemoryFilter = MemoryFilter.All, limit: Int = 100): Result[Seq[Memory]]
  def search(query: String, topK: Int = 10, filter: MemoryFilter = MemoryFilter.All): Result[Seq[ScoredMemory]]
  def getEntityMemories(entityId: EntityId): Result[Seq[Memory]]
  def getConversation(conversationId: String, limit: Int = 100): Result[Seq[Memory]]
  def delete(id: MemoryId): Result[MemoryStore]
  def deleteMatching(filter: MemoryFilter): Result[MemoryStore]
  def update(id: MemoryId, update: Memory => Memory): Result[MemoryStore]
  def count(filter: MemoryFilter = MemoryFilter.All): Result[Long]
  def exists(id: MemoryId): Result[Boolean]
  def clear(): Result[MemoryStore]
  def recent(limit: Int = 10, filter: MemoryFilter = MemoryFilter.All): Result[Seq[Memory]]
  def important(threshold: Double = 0.5, limit: Int = 100): Result[Seq[Memory]]
}
```

### ScoredMemory

```scala
final case class ScoredMemory(
  memory: Memory,
  score: Double    // 0.0 to 1.0, higher is more relevant
)
```

### Implementations

| Implementation | Backend | Use Case |
|---|---|---|
| `InMemoryStore` | In-memory map | Testing, prototyping |
| `SQLiteMemoryStore` | SQLite | Persistent single-node |
| `VectorMemoryStore` | VectorStore | Semantic search via embeddings |

### MemoryStoreConfig

```scala
final case class MemoryStoreConfig(
  maxMemories: Option[Int] = None,
  defaultEmbeddingDimensions: Int = 1536,
  enableAutoCleanup: Boolean = false,
  cleanupThreshold: Int = 10000
)

// Presets
MemoryStoreConfig.default
MemoryStoreConfig.testing                        // maxMemories = 1000
MemoryStoreConfig.production(maxMemories = 100000) // With auto-cleanup
```

---

## MemoryManager

High-level interface for agent memory integration.

### Trait

```scala
trait MemoryManager {
  def store: MemoryStore

  // Recording
  def recordMessage(message: Message, conversationId: String, importance: Option[Double] = None): Result[MemoryManager]
  def recordConversation(messages: Seq[Message], conversationId: String): Result[MemoryManager]
  def recordEntityFact(entityId: EntityId, entityName: String, fact: String, entityType: String = "unknown", importance: Option[Double] = None): Result[MemoryManager]
  def recordUserFact(fact: String, userId: Option[String] = None, importance: Option[Double] = None): Result[MemoryManager]
  def recordKnowledge(content: String, source: String, metadata: Map[String, String] = Map.empty): Result[MemoryManager]
  def recordTask(description: String, outcome: String, success: Boolean, importance: Option[Double] = None): Result[MemoryManager]

  // Retrieval (formatted for LLM context injection)
  def getRelevantContext(query: String, maxTokens: Int = 2000, filter: MemoryFilter = MemoryFilter.All): Result[String]
  def getConversationContext(conversationId: String, maxMessages: Int = 20): Result[String]
  def getEntityContext(entityId: EntityId): Result[String]
  def getUserContext(userId: Option[String] = None): Result[String]

  // Maintenance
  def consolidateMemories(olderThan: Instant, minCount: Int = 10): Result[MemoryManager]
  def extractEntities(text: String, conversationId: Option[String] = None): Result[MemoryManager]
  def stats: Result[MemoryStats]
}
```

### MemoryStats

```scala
final case class MemoryStats(
  totalMemories: Long,
  byType: Map[MemoryType, Long],
  entityCount: Long,
  conversationCount: Long,
  embeddedCount: Long,
  oldestMemory: Option[Instant],
  newestMemory: Option[Instant]
)
```

### MemoryManagerConfig

```scala
final case class MemoryManagerConfig(
  autoRecordMessages: Boolean = true,
  autoExtractEntities: Boolean = false,
  defaultImportance: Double = 0.5,
  contextTokenBudget: Int = 2000,
  consolidationEnabled: Boolean = false
)

// Presets
MemoryManagerConfig.default
MemoryManagerConfig.testing           // Auto-record off
MemoryManagerConfig.fullFeatured      // Everything on
```

---

## Usage Patterns

### Pattern 1: Basic Conversation Memory

```scala
val store = InMemoryStore()
val manager = SimpleMemoryManager(store, MemoryManagerConfig.default)

// Record messages
val updated = for {
  m1 <- manager.recordMessage(UserMessage("What is Scala?"), "conv-1")
  m2 <- m1.recordMessage(AssistantMessage("Scala is a functional programming language."), "conv-1")
} yield m2

// Retrieve context for next query
updated.flatMap(_.getConversationContext("conv-1")) match {
  case Right(context) => println(context)
  case Left(error) => println(error.message)
}
```

### Pattern 2: Entity Tracking

```scala
val entityId = EntityId.fromName("Alice")

for {
  m1 <- manager.recordEntityFact(entityId, "Alice", "Works at Acme Corp", "person", Some(0.8))
  m2 <- m1.recordEntityFact(entityId, "Alice", "Prefers Scala over Java", "person", Some(0.6))
  context <- m2.getEntityContext(entityId)
} yield context
```

### Pattern 3: Semantic Search

```scala
// Using VectorMemoryStore for semantic search
store.search(
  query = "functional programming languages",
  topK = 5,
  filter = MemoryFilter.knowledge
) match {
  case Right(results) =>
    results.foreach { scored =>
      println(s"Score ${scored.score}: ${scored.memory.content.take(80)}...")
    }
  case Left(error) => println(error.message)
}
```

### Pattern 4: Memory-Augmented Agent

```scala
// Before each LLM call, inject relevant context
val relevantContext = manager.getRelevantContext(
  query = userQuery,
  maxTokens = 2000,
  filter = MemoryFilter.conversations || MemoryFilter.entities
)

relevantContext match {
  case Right(context) =>
    val systemPrompt = s"""You are a helpful assistant.
      |
      |Relevant context from previous interactions:
      |$context""".stripMargin

    val conversation = Conversation(Seq(
      SystemMessage(systemPrompt),
      UserMessage(userQuery)
    ))
    client.complete(conversation)

  case Left(error) =>
    // Fall back to no context
    client.complete(Conversation(Seq(UserMessage(userQuery))))
}
```

### Pattern 5: Persistent Memory with SQLite

```scala
val store = SQLiteMemoryStore("./agent-memory.db", MemoryStoreConfig.production())
val manager = SimpleMemoryManager(store, MemoryManagerConfig.fullFeatured)

// Memories persist across restarts
manager.stats match {
  case Right(stats) =>
    println(s"Total memories: ${stats.totalMemories}")
    println(s"Entities: ${stats.entityCount}")
    println(s"Conversations: ${stats.conversationCount}")
  case Left(error) => println(error.message)
}
```

---

## Next Steps

- **RAG Pipeline**: See `llm4s-rag-pipeline.md` for document ingestion and retrieval
- **Guardrails**: See `llm4s-guardrails.md` for input/output validation
- **Orchestration**: See `llm4s-orchestration.md` for multi-agent coordination
- **Agent Patterns**: See `llm4s-agent-patterns.md` for agent execution model
