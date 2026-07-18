# Concept: Retriever

## Concept specification

```
concept Retriever[F[_]]
purpose
    Document retrieval abstraction with configurable top-k and minimum
    score, returning a List[Document] or a Stream[Document].
state
    config: Retriever -> RetrieverConfig(topK, minScore)
actions
    retrieve [ query: String ; config: RetrieverConfig ]
        => [ docs: F[List[Document]] ]
    retrieveStream [ query: String ; config: RetrieverConfig ]
        => [ docs: Stream[F, Document] ]
    fromFunction [ f: (String, RetrieverConfig) => F[List[Document]] ]
        => [ Retriever[F] ]
    empty
        => [ Retriever[F] ]   # returns Nil / Stream.empty
operational principle
    A caller obtains a Retriever via fromFunction (delegating to a user
    function) or empty (no-op). retrieve returns a list; retrieveStream
    loads the list and emits it as a stream. The empty retriever returns
    Nil / Stream.empty.
```

## Implementation map

| Element | Code |
|---|---|
| trait `Retriever` | `trait Retriever[F[_]]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| state `RetrieverConfig` | `final case class RetrieverConfig(topK: Int = 5, minScore: Double = 0.0)` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| state `Document` | `final case class Document(id, content, metadata: Map[String, ujson.Value])` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| action `retrieve` | `Retriever.retrieve(query, config): F[List[Document]]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| action `retrieveStream` | `Retriever.retrieveStream(query, config): Stream[F, Document]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| factory `fromFunction` | `Retriever.fromFunction[F](f)` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| factory `empty` | `Retriever.empty: Retriever[IO]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`) |
| runtime host | `org.adk4s.core.component` |

## Deviations from the pattern

- `retrieveStream` loads all documents into memory via `Stream.eval(f(query, config)).flatMap(docs => Stream.emits(docs))` — not a true lazy stream (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`).
- `RetrieverConfig.topK` and `minScore` are not validated (negative topK, NaN minScore pass through) (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`).
- `fromFunction` propagates errors directly without wrapping in `AdkError` (`adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`).
