# Concept: [Name]

## Concept specification

```
concept [Name] [T]                    # T = external identity type params
purpose
    [one sentence, user-facing]
state
    [component]: T -> [Type]          # relational, Alloy-style
actions
    [action] [ arg: Type ; ... ] => [ result: Type ]
    [action] [ arg: Type ; ... ] => [ error: "message" ]
operational principle
    [archetypal scenario showing how the concept fulfills its purpose]
```

## Implementation map

<!-- The ONLY place code identifiers live for this concept. Every row is
     verified by scanner/registry-check.sh — keep symbols exact. -->

| Element | Code |
|---|---|
| state | `[StateType.field]` (`[path/to/file]`) |
| action `[name]` | `[Command]` → `[Handler.method]` → event `[Event]` (`[file]`) |
| query | `[how reads happen]` |
| runtime host | `[actor / service / module]` |

## Value domains

<!-- Provenance of every externally-defined value set this concept relies on
     (classification tables, code mappings, enum vocabularies). If the
     authoritative source is outside the repo, say where it is and when it
     was last confirmed. Specs citing an unconfirmed domain must mark it
     MUST-CONFIRM. Delete this section if not applicable. -->

| Domain | Values / source | Confirmed |
|---|---|---|
| [e.g. staleness clock format] | [source of truth] | [date / MUST-CONFIRM] |

## Synchronizations

```
sync [SyncName]
when {
    [Concept/action]: [ args ] => [ results ]
}
where {
    [reads of other concepts' state]
}
then {
    [OtherConcept/action]: [ args ]
}
```

impl: `[imperative code realizing this sync]`
Deviation: [e.g. best-effort — failure logged, caller still sees success]

## Deviations from the pattern

- [where the code breaks concept independence, as observations — do not
  idealize; the registry describes the code that exists]
