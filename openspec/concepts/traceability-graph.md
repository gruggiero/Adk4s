# Concept: TraceabilityGraph

## Concept specification

```
concept TraceabilityGraph
purpose
    The one tool that answers reachability questions — does every
    requirement reach an enforcement artifact? — that neither text
    search nor a compiler index can answer. Built from the three
    declared sources (behavioural registry, type inventory, active
    specs) over strings; it learns nothing about the filesystem.
state
    nodes: TraceabilityGraph -> Set[GraphNode]
    edges: TraceabilityGraph -> List[Edge]
    unlinkable: TraceabilityGraph -> List[UnlinkableRow]
actions
    build [ registry: List[ConceptRegistryDoc] ; inventory: InventoryDoc ;
            specs: List[SpecGraphDoc] ; resolveCodePath: String => Boolean ;
            resolveArtifact: String => Boolean ]
        => [ build: GraphBuild ]
    audit [ graph: TraceabilityGraph ; change: Option[String] ;
            artifactResolves: String => Boolean ]
        => [ result: Option[ReachabilityResult] ]
    query [ graph: TraceabilityGraph ; query: GraphQuery ]
        => [ output: String ]
operational principle
    Every row a source parser reads is accounted for: bound to a node or
    edge, or recorded in the unlinkable set with a stated reason. A row
    is never silently dropped — a graph that omits what it could not
    bind answers reachability optimistically, which is the
    confidently-wrong failure mode the tool exists to detect.
```

## Implementation map

| Element | Code |
|---|---|
| trait `TraceabilityGraph` | `final case class TraceabilityGraph` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/TraceabilityGraph.scala`) |
| type `GraphNode` | `sealed trait GraphNode` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/GraphNode.scala`) |
| type `GraphEdge` | `enum GraphEdge` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/GraphEdge.scala`) |
| type `UnlinkableRow` | `final case class UnlinkableRow` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/UnlinkableRow.scala`) |
| action `build` | `TraceabilityGraph.build(registry, inventory, specs, resolveCodePath, resolveArtifact): GraphBuild` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/TraceabilityGraph.scala`) |
| action `audit` | `GraphAudit.audit(graph, change, artifactResolves): Option[ReachabilityResult]` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/GraphAudit.scala`) |
| wire format | `GraphWire.writeExport`/`readExport`/`writeChangePayload` (`workflow/core/src/main/scala/org/sinemenda/probatio/core/GraphWire.scala`) |
| kernel mirror | `ReachabilityKernel` (`verified/probatio/src/main/scala/org/sinemenda/probatio/core/ReachabilityKernel.scala`) |
| adapter | `GraphCmd` (`workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala`) |

## Deviations from the pattern

- The graph binds predecessor-exact table conventions (derived ids,
  `table_rows` section scans) rather than introducing a cleaner grammar
  — conformance to the executable predecessor is the contract.
- Resolution is injected as predicates (`resolveCodePath`,
  `artifactResolves`), so generated corpora decide conformance
  in-process without touching the filesystem.
