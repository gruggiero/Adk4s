# Eino Framework Analysis - Error Handling and Validation

## 1. Error Types

### Sentinel Errors

```go
// compose/error.go
var ErrExceedMaxSteps = errors.New("exceeds max steps")
var ErrGraphCompiled = errors.New("graph has been compiled, cannot be modified")
var ErrChainCompiled = errors.New("chain has been compiled, cannot be modified")

// schema/stream.go
var ErrNoValue = errors.New("no value")
var ErrRecvAfterClosed = errors.New("recv after stream closed")
```

### Structured Errors

```go
// Internal error with node path tracking
type internalError struct {
    typ       internalErrorType  // "NodeRunError" or "GraphRunError"
    nodePath  NodePath           // Path to failing node
    origError error              // Wrapped original error
}

func (i *internalError) Error() string {
    sb := strings.Builder{}
    sb.WriteString(string("[" + i.typ + "] "))
    sb.WriteString(i.origError.Error())
    if len(i.nodePath.path) > 0 {
        sb.WriteString("\n------------------------\n")
        sb.WriteString("node path: [")
        // ... format path
    }
    return sb.String()
}

func (i *internalError) Unwrap() error {
    return i.origError
}
```

### Error Wrapping

```go
// Wrap errors with node context
func wrapGraphNodeError(nodeKey string, err error) error {
    if ok := isInterruptError(err); ok {
        return err  // Don't wrap interrupt errors
    }
    var ie *internalError
    ok := errors.As(err, &ie)
    if !ok {
        return &internalError{
            typ:       internalErrorTypeNodeRun,
            nodePath:  NodePath{path: []string{nodeKey}},
            origError: err,
        }
    }
    // Prepend node key to existing path
    ie.nodePath.path = append([]string{nodeKey}, ie.nodePath.path...)
    return ie
}
```

### Scala 3 Translation

```scala
// Sealed trait hierarchy for errors
sealed trait GraphError extends Throwable:
  def message: String
  override def getMessage: String = message

case class ExceedMaxStepsError(steps: Int, max: Int) extends GraphError:
  def message: String = s"Exceeded max steps: $steps > $max"

case class GraphCompiledError() extends GraphError:
  def message: String = "Graph has been compiled, cannot be modified"

case class NodeRunError(
  nodePath: List[String],
  cause: Throwable
) extends GraphError:
  def message: String = 
    s"[NodeRunError] ${cause.getMessage}\nnode path: [${nodePath.mkString(", ")}]"

case class TypeMismatchError(
  fromNode: String,
  toNode: String,
  fromType: String,
  toType: String
) extends GraphError:
  def message: String = 
    s"Type mismatch: $fromNode ($fromType) -> $toNode ($toType)"

// Error accumulation
import cats.data.NonEmptyChain
type GraphErrors = NonEmptyChain[GraphError]
```

### LLM4S Error Hierarchy

**LLM4S provides a comprehensive error hierarchy**:

```scala
import org.llm4s.error.LLMError

// LLM4S sealed error hierarchy
sealed trait LLMError:
  def message: String
  def isRecoverable: Boolean  // Indicates if retry might help
  def formatted: String       // Human-readable format

case class APIError(
  message: String, 
  statusCode: Option[Int],
  provider: String
) extends LLMError:
  def isRecoverable: Boolean = statusCode.exists(c => c >= 500 || c == 429)

case class RateLimitError(
  message: String,
  retryAfter: Option[Duration]
) extends LLMError:
  def isRecoverable: Boolean = true

case class AuthenticationError(message: String) extends LLMError:
  def isRecoverable: Boolean = false

case class NetworkError(
  message: String,
  cause: Option[Throwable]
) extends LLMError:
  def isRecoverable: Boolean = true

case class InvalidRequestError(message: String) extends LLMError:
  def isRecoverable: Boolean = false

case class ContentFilterError(message: String) extends LLMError:
  def isRecoverable: Boolean = false
```

### structured-llm Error Types

**structured-llm adds parsing-specific errors**:

```scala
import org.adk4s.structured.core.{StructuredLLMError, ParseError}

// Structured LLM errors wrap LLM4S errors
sealed trait StructuredLLMError extends Throwable:
  def message: String

case class LLMCallFailed(underlying: LLMError, prompt: Prompt) extends StructuredLLMError
case class ParseFailed(errors: List[ParseError], rawResponse: String) extends StructuredLLMError
case class EmptyResponse(prompt: Prompt) extends StructuredLLMError

// Parse errors are granular
sealed trait ParseError:
  def message: String

case class JsonSyntaxError(message: String, position: Option[Int]) extends ParseError
case class SchemaViolation(message: String, path: String, expectedType: String) extends ParseError
case class MissingRequiredField(fieldName: String, path: String) extends ParseError
case class UnexpectedEnumValue(value: String, allowedValues: List[String]) extends ParseError
case class NoJsonFound(rawResponse: String) extends ParseError
```

### Workflows4s Error Types

**Workflows4s uses typed errors in WIO**:

```scala
import workflows4s.wio.WIO

// Define domain-specific errors
sealed trait AgentWorkflowError
case class LLMFailure(error: LLMError) extends AgentWorkflowError
case class ToolExecutionFailed(toolName: String, cause: Throwable) extends AgentWorkflowError
case class MaxStepsExceeded(steps: Int) extends AgentWorkflowError
case class TimeoutError(duration: Duration) extends AgentWorkflowError

// WIO carries error type
val workflow: WIO[String, AgentWorkflowError, String, Ctx.type] = ???

// Error handling is type-safe
val recovered: WIO[String, Nothing, String, Ctx.type] =
  workflow.handleErrorWith {
    case LLMFailure(error) if error.isRecoverable => retryWorkflow
    case MaxStepsExceeded(_) => WIO.pure("Stopped due to max steps")
    case error => WIO.raiseError(error)
  }
```

**ADK4S Recommendation**: Use sealed trait hierarchies for errors. LLM4S provides `LLMError`, structured-llm provides `StructuredLLMError` and `ParseError`. Compose these with domain-specific errors for Workflows4s.

---

## 2. Validation Patterns

### Graph Construction Validation

```go
func (g *graph) addNode(key string, node *graphNode, options *graphAddNodeOpts) (err error) {
    // Check for reserved keys
    if key == END || key == START {
        return fmt.Errorf("node '%s' is reserved, cannot add manually", key)
    }
    
    // Check for duplicates
    if _, ok := g.nodes[key]; ok {
        return fmt.Errorf("node '%s' already present", key)
    }
    
    // Check state requirements
    if options.needState && g.stateGenerator == nil {
        return fmt.Errorf("node '%s' needs state but graph state is not enabled", key)
    }
    
    // Check handler type compatibility
    if options.processor != nil {
        if g.stateType != options.processor.preStateType {
            return fmt.Errorf("node[%s]'s pre handler state type mismatch", key)
        }
    }
    
    g.nodes[key] = node
    return nil
}
```

### Edge Type Validation

```go
func (g *graph) addEdgeWithMappings(startNode, endNode string, ...) (err error) {
    // Validate node existence
    if _, ok := g.nodes[startNode]; !ok && startNode != START {
        return fmt.Errorf("edge start node '%s' needs to be added first", startNode)
    }
    
    // Validate type compatibility
    startType := g.getNodeOutputType(startNode)
    endType := g.getNodeInputType(endNode)
    
    result := checkAssignable(startType, endType)
    if result == assignableTypeMustNot {
        return fmt.Errorf("type mismatch: %s -> %s", startType, endType)
    }
    
    // Add runtime check if needed
    if result == assignableTypeMay {
        g.handlerOnEdges[startNode][endNode] = append(
            g.handlerOnEdges[startNode][endNode], 
            g.getNodeGenericHelper(endNode).inputConverter,
        )
    }
    
    return nil
}
```

### Compile-Time Validation

```go
func (g *graph) compile(ctx context.Context, opt *graphCompileOptions) (*composableRunnable, error) {
    if g.buildError != nil {
        return nil, g.buildError
    }
    
    // Validate start/end nodes exist
    if len(g.startNodes) == 0 {
        return nil, errors.New("start node not set")
    }
    if len(g.endNodes) == 0 {
        return nil, errors.New("end node not set")
    }
    
    // Validate all types could be inferred
    for _, v := range g.toValidateMap {
        if len(v) > 0 {
            return nil, fmt.Errorf("types cannot be inferred: %v", g.toValidateMap)
        }
    }
    
    // Validate no duplicate field mappings
    for key := range g.fieldMappingRecords {
        toMap := make(map[string]bool)
        for _, mapping := range g.fieldMappingRecords[key] {
            if _, ok := toMap[mapping.to]; ok {
                return nil, fmt.Errorf("duplicate mapping target: %s", mapping.to)
            }
            toMap[mapping.to] = true
        }
    }
    
    // Validate DAG if in DAG mode
    if runType == runTypeDAG {
        if err := validateDAG(r.chanSubscribeTo, controlPredecessors); err != nil {
            return nil, err
        }
    }
    
    return r.toComposableRunnable(), nil
}
```

### Scala 3 Translation

```scala
// Validation using Validated
import cats.data.{Validated, ValidatedNec}
import cats.syntax.all.*

def addNode[A, B](
  key: String, 
  node: Node[A, B]
): ValidatedNec[GraphError, Graph[I, O]] =
  (
    validateNotReserved(key),
    validateNotDuplicate(key),
    validateStateRequirements(key, node)
  ).mapN { (_, _, _) =>
    copy(nodes = nodes + (key -> node))
  }

def validateNotReserved(key: String): ValidatedNec[GraphError, Unit] =
  if key == START || key == END then
    ReservedNodeKeyError(key).invalidNec
  else
    ().validNec

def addEdge(from: String, to: String): ValidatedNec[GraphError, Graph[I, O]] =
  (
    validateNodeExists(from),
    validateNodeExists(to),
    validateTypeCompatibility(from, to)
  ).mapN { (_, _, _) =>
    copy(edges = edges + (from -> to))
  }

// Compile with accumulated validation
def compile: ValidatedNec[GraphError, IO[Runnable[I, O]]] =
  (
    validateStartNodes,
    validateEndNodes,
    validateAllTypesInferred,
    validateNoFieldMappingConflicts,
    validateAcyclicIfDAG
  ).mapN { (_, _, _, _, _) =>
    buildRunnable
  }
```

**ADK4S Recommendation**: Use `ValidatedNec` for accumulating validation errors. This allows reporting all errors at once rather than failing on the first.

---

## 3. Runtime Error Handling

### Panic Recovery

```go
// Safe execution with panic recovery
func parallelRunToolCall(ctx context.Context, run func(...), tasks []toolCallTask, opts ...tool.Option) {
    for i := 1; i < len(tasks); i++ {
        go func(t *toolCallTask) {
            defer func() {
                panicErr := recover()
                if panicErr != nil {
                    t.err = safe.NewPanicErr(panicErr, debug.Stack())
                }
            }()
            run(ctx, t, opts...)
        }(&tasks[i])
    }
}

// PanicErr wraps panic with stack trace
type PanicErr struct {
    panicErr any
    stack    []byte
}

func (p *PanicErr) Error() string {
    return fmt.Sprintf("panic: %v\n%s", p.panicErr, string(p.stack))
}
```

### Stream Error Handling

```go
// Errors propagated through stream
func (sw *StreamWriter[T]) Send(chunk T, err error) (closed bool) {
    item := streamItem[T]{chunk, err}
    select {
    case <-s.closed:
        return true
    case s.items <- item:
        return false
    }
}

// Consumer handles errors
for {
    chunk, err := sr.Recv()
    if err == io.EOF {
        break
    }
    if err != nil {
        return err  // Propagate error
    }
    process(chunk)
}
```

### Scala 3 Translation

```scala
// Cats Effect handles errors in IO
def runNode[A, B](node: Node[A, B], input: A): IO[B] =
  node.run(input).handleErrorWith { error =>
    IO.raiseError(NodeRunError(List(node.key), error))
  }

// Stream errors are naturally handled by fs2
def processStream[A](stream: fs2.Stream[IO, A]): fs2.Stream[IO, A] =
  stream.handleErrorWith { error =>
    fs2.Stream.raiseError[IO](StreamProcessingError(error))
  }

// Panic-like errors (defects) handled via IO
def safeRun[A](io: IO[A]): IO[Either[Throwable, A]] =
  io.attempt  // Catches all non-fatal errors
```

### LLM4S Runtime Error Handling

**LLM4S uses Either for all operations, integrating with IO**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.error.LLMError
import cats.effect.IO

val client: LLMClient = LLMClient.create()

// Convert Either to IO for integration with Cats Effect
def completeIO(conversation: Conversation): IO[Completion] =
  IO.fromEither(client.complete(conversation, CompletionOptions()))
    .adaptError { case error: LLMError => 
      new RuntimeException(error.formatted) 
    }

// Retry with exponential backoff for recoverable errors
def completeWithRetry(conversation: Conversation, maxRetries: Int = 3): IO[Completion] =
  def attempt(retriesLeft: Int): IO[Completion] =
    IO.fromEither(client.complete(conversation, CompletionOptions()))
      .handleErrorWith {
        case error: LLMError if error.isRecoverable && retriesLeft > 0 =>
          IO.sleep((maxRetries - retriesLeft + 1).seconds) *> attempt(retriesLeft - 1)
        case error: LLMError =>
          IO.raiseError(new RuntimeException(error.formatted))
      }
  attempt(maxRetries)
```

### Workflows4s Runtime Error Handling

**Workflows4s provides retry and error recovery patterns**:

```scala
import workflows4s.wio.WIO

// Retry pattern with WIO
val withRetry: WIO[String, Nothing, String, Ctx.type] =
  WIO.retry(
    workflow,
    maxAttempts = 3,
    backoff = exponentialBackoff(1.second)
  )

// Error recovery with fallback
val withFallback: WIO[String, Nothing, String, Ctx.type] =
  workflow.handleErrorWith { error =>
    WIO.runIO(_ => IO.pure(s"Fallback result due to: ${error.message}"))
  }

// Timeout handling
val withTimeout: WIO[String, TimeoutError, String, Ctx.type] =
  WIO.await(
    workflow,
    timeout = 30.seconds,
    onTimeout = TimeoutError(30.seconds)
  )
```

**ADK4S Recommendation**: 
- Use LLM4S `Either[LLMError, A]` and convert to `IO` with `IO.fromEither`
- Use `isRecoverable` to determine retry eligibility
- Use Workflows4s `WIO.retry` for workflow-level retries
- Rely on Cats Effect's error handling for IO composition

---

## 4. Interrupt and Resume

### Interrupt Error

```go
// Interrupt signals execution should pause
type interruptError struct {
    extra any  // User-provided data to persist
    state any  // Internal state to persist
}

func Interrupt(ctx context.Context, extra any) error {
    return &interruptError{extra: extra}
}

// Check if error is interrupt
func isInterruptError(err error) bool {
    var ie *interruptError
    return errors.As(err, &ie)
}
```

### Resume from Interrupt

```go
// Resume with checkpoint
func (r *runner) Resume(ctx context.Context, checkpoint *Checkpoint) (any, error) {
    // Restore state from checkpoint
    ctx = r.restoreState(ctx, checkpoint)
    
    // Continue from interrupted node
    return r.run(ctx, checkpoint.LastInput, checkpoint.LastNode)
}
```

### Scala 3 Translation

```scala
// Interrupt as a special error type
case class InterruptError[S](
  extra: Any,
  state: S,
  checkpoint: Checkpoint
) extends Throwable

// Resume capability
trait Resumable[I, O]:
  def resume(checkpoint: Checkpoint): IO[O]

// Using IO's cancellation for interrupt
def interruptible[A](io: IO[A]): IO[Either[Checkpoint, A]] =
  io.cancelable { case (checkpoint, cb) =>
    IO(cb(Left(checkpoint)))
  }
```

### Workflows4s Checkpointing and Resume

**Workflows4s provides native checkpointing via event sourcing**:

```scala
import workflows4s.wio.WIO
import workflows4s.runtime.{WorkflowInstance, ActiveWorkflow}

// Workflows are automatically checkpointed via events
// State can be reconstructed by replaying events

// Define checkpoint-aware workflow
val checkpointedWorkflow: WIO[String, Nothing, String, Ctx.type] =
  WIO.runIO(_ => IO.pure("step1"))
    .checkpoint("after_step1")  // Named checkpoint
    .flatMap(_ => WIO.runIO(_ => IO.pure("step2")))
    .checkpoint("after_step2")

// Resume from checkpoint
def resumeWorkflow(instance: WorkflowInstance[IO]): IO[String] =
  instance.resume()  // Continues from last checkpoint

// Interrupt with signal
val interruptibleWorkflow: WIO[String, Nothing, String, Ctx.type] =
  WIO.handleSignal(
    SignalDef[InterruptSignal]("interrupt"),
    handler = signal => WIO.pure(s"Interrupted: ${signal.reason}")
  )(mainWorkflow)
```

### LLM4S Agent State for Resume

**LLM4S AgentState is immutable and can be serialized for resume**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}

// AgentState can be serialized and restored
case class AgentState(
  conversation: Conversation,
  pendingToolCalls: List[ToolCall],
  status: AgentStatus,
  stepCount: Int
)

// Save state for later resume
def saveCheckpoint(state: AgentState): IO[Unit] =
  IO(serializeState(state)).flatMap(writeToStorage)

// Resume from saved state
def resumeAgent(agent: Agent, savedState: AgentState): Either[LLMError, AgentState] =
  agent.step(savedState)  // Continue from saved state

// Interrupt pattern with max steps
def runWithInterrupt(agent: Agent, query: String, maxSteps: Int): IO[AgentState] =
  def loop(state: AgentState): IO[AgentState] =
    if state.stepCount >= maxSteps then
      saveCheckpoint(state) *> IO.pure(state)  // Interrupt and save
    else if state.status == AgentStatus.Complete then
      IO.pure(state)
    else
      IO.fromEither(agent.step(state)).flatMap(loop)
  
  IO.pure(agent.initialize(query)).flatMap(loop)
```

**ADK4S Recommendation**: 
- Use **Workflows4s event sourcing** for durable checkpointing and resume
- Use **LLM4S AgentState** serialization for agent-level checkpoints
- Model interrupts as signals in Workflows4s
- Use Cats Effect's cancellation mechanism for cooperative interruption

---

## 5. Error Messages

### Descriptive Error Messages

```go
// Include context in error messages
fmt.Errorf("node '%s' is reserved, cannot add manually", key)
fmt.Errorf("edge start node '%s' needs to be added to graph first", startNode)
fmt.Errorf("graph edge[%s]-[%s]: start node's output type[%s] and end node's input type[%s] mismatch",
    startNode, endNode, startNodeOutputType.String(), endNodeInputType.String())
fmt.Errorf("failed to invoke tool[name:%s id:%s]: %w", name, callID, err)
```

### Error Formatting

```go
func (i *internalError) Error() string {
    sb := strings.Builder{}
    sb.WriteString(string("[" + i.typ + "] "))
    sb.WriteString(i.origError.Error())
    if len(i.nodePath.path) > 0 {
        sb.WriteString("\n------------------------\n")
        sb.WriteString("node path: [")
        for j := 0; j < len(i.nodePath.path)-1; j++ {
            sb.WriteString(i.nodePath.path[j] + ", ")
        }
        sb.WriteString(i.nodePath.path[len(i.nodePath.path)-1])
        sb.WriteString("]")
    }
    return sb.String()
}
```

### Scala 3 Translation

```scala
// Rich error messages via Show typeclass
given Show[GraphError] with
  def show(e: GraphError): String = e match
    case NodeRunError(path, cause) =>
      s"""[NodeRunError] ${cause.getMessage}
         |------------------------
         |node path: [${path.mkString(", ")}]""".stripMargin
    case TypeMismatchError(from, to, fromType, toType) =>
      s"graph edge[$from]-[$to]: output type[$fromType] and input type[$toType] mismatch"
    case _ => e.message

// Use interpolators for error construction
def nodeNotFound(key: String): GraphError =
  NodeNotFoundError(s"Node '$key' not found in graph")
```

**ADK4S Recommendation**: Use the `Show` typeclass for consistent error formatting. Include all relevant context in error messages.
