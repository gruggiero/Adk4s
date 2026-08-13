# Concept: CoreTypes

## Concept specification

```
concept CoreTypes
purpose
    Provide opaque type wrappers for domain identifiers (NodeKey, FieldPath,
    RunPath) and shared constraint aliases (Positive, NonNegative) used across
    the toolkit. NodeKey is the primary graph node identifier, constrained to
    be non-empty and not reserved (START/END).
state
    NodeKey : String :| (NonEmpty & Not[Reserved])
    ReservedNodeKey : enum { Start, End }
    Positive : Int :| numeric.Positive
    NonNegative : Int :| numeric.Positive0
actions
    refineNodeKey [ value: String ]
        => [ key: NodeKey ]
    refineNodeKey [ value: String ]
        => [ error: ConfigError("NodeKey", value, "NonEmpty & Not[Reserved]") ]
    reservedNodeKey [ r: ReservedNodeKey ]
        => [ value: String ]
```

## Implementation map

| Symbol | Kind | File |
|--------|------|------|
| `NodeKey` | opaque type | `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala` |
| `ReservedNodeKey` | enum | `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala` |
| `Positive` | type alias | `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala` |
| `NonNegative` | type alias | `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala` |
