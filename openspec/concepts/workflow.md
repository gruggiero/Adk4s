# Concept: Workflow

## Concept specification

```
concept Workflow[I, O]
purpose
    A higher-level DSL for composing Lambda nodes with field mappings
    between nodes.
state
    nodes: Workflow -> Map[NodeKey, WorkflowNode[?, ?]]
    inputs: Workflow -> Map[NodeKey, List[(NodeKey, FieldMapping)]]
    endNode: Workflow -> Option[NodeKey]
actions
    addLambdaNode [ key: String ; lambda: Lambda[A, B] ]
        => [ WorkflowNodeBuilder[I, O, A, B] ]
    end
        => [ WorkflowEndBuilder[I, O] ]
    compile
        => [ runnable: Runnable[I, O] ]
    compile
        => [ error: UnsupportedOperationException ]   # NOT IMPLEMENTED
operational principle
    A builder adds Lambda or ChatModel nodes, wires field mappings between
    them, marks an end node, and calls compile. The DSL is a sketch —
    compile is not implemented and always raises.
```

## Implementation map

| Element | Code |
|---|---|
| class `Workflow` | `case class Workflow[I, O](nodes, inputs, endNode)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`) |
| action `addLambdaNode` | `Workflow.addLambdaNode[A, B](key, lambda): WorkflowNodeBuilder[I, O, A, B]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`) |
| action `end` | `Workflow.end: WorkflowEndBuilder[I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`) |
| action `compile` | `Workflow.compile: IO[Runnable[I, O]]` raises `UnsupportedOperationException` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`) |
| node `WorkflowNode` | `sealed trait WorkflowNode[I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/WorkflowNode.scala`) |
| mapping `FieldMapping` | `case class FieldMapping(from: FieldPath, to: FieldPath, fromNode: Option[NodeKey])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/FieldMapping.scala`) |
| runtime host | `org.adk4s.orchestration.workflow` |

## Deviations from the pattern

- `compile` is not implemented — it always raises `UnsupportedOperationException`. The Workflow concept is a sketch only; no user can run a Workflow end-to-end (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`).
- `inputs` (field mappings) are stored but never read by any code path, since compile is unimplemented (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`).
- No validation that field paths reference actual fields of their source nodes (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/FieldMapping.scala`).
