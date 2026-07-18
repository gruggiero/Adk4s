---

name: openspec-scan-concepts
description: >
  Scan the Scala 3 codebase to discover and catalogue existing domain concepts
  (opaque types, sealed traits, enums, service traits, case classes, Smithy models,
  ScalaCheck generators). Populates or updates the concept-inventory.md artifact
  in the current OpenSpec change directory.
globs:

  - "openspec/changes/*/concept-inventory.md"
  - "src/main/scala/**/*.scala"
  - "src/test/scala/**/*.scala"
  - "src/main/smithy/**/*.smithy"
metadata:
  generatedBy: verified-scala3-schema/1.1.0

---

# Scan Concepts Skill

## Purpose

Scan the project source tree and produce a structured concept inventory. This
inventory is the mechanism that prevents duplicate type creation when specs are
implemented sequentially in the verified-scala3 workflow.

## When to Use

- When creating the `concept-inventory` artifact during `/opsx:continue` or `/opsx:ff`
- When manually invoked via `/opsx:scan` to refresh the inventory
- Before implementing any spec during `/opsx:apply` (concept check step)

## Procedure

### Option A: Run the scanner script (if available)

If `openspec/schemas/verified-scala3/scanner/concept-scanner.scala` exists:

```bash
scala-cli run openspec/schemas/verified-scala3/scanner/concept-scanner.scala -- . --output openspec/changes/<CHANGE_NAME>/concept-inventory.md
```

### Option B: Manual scan (default)

Scan the project source tree systematically. For each category below, search the
indicated directories and extract the specified patterns.

#### 1. Opaque Types (Iron Refined)

Search: `src/main/scala/` recursively

Pattern to find:

```
opaque type <Name> = <Underlying> :| <Constraint>
```

Also look for constraint type aliases:

```
type <Name>Constraint = <Constraint Expression>
```

Resolve aliases: if the opaque type uses a constraint alias name, record the
resolved constraint expression in the inventory.

For each match, record:

| Column | Value |
|--------|-------|
| Type | The opaque type name |
| Underlying | The base type (String, Long, Int, etc.) |
| Iron Constraint | The full constraint expression (with aliases resolved) |
| Package | The `package` declaration in the file |
| Introduced By | `scan:<filename>` |

#### 2. Sealed Traits and Enums

Search: `src/main/scala/` recursively

Patterns to find:

```
sealed trait <Name>
sealed abstract class <Name>
enum <Name>:
  case <Variant1>(fields)
  case <Variant2>(fields)
```

For enums, extract ALL case variants. Do not confuse `case class` definitions
(top-level) with enum `case` variants (indented inside enum block).

For each match, record:

| Column | Value |
|--------|-------|
| Type | The type name |
| Kind | `sealed trait`, `sealed abstract class`, or `enum` |
| Variants | Comma-separated list of case names (for enums) or `—` |
| Package | The `package` declaration in the file |
| Introduced By | `scan:<filename>` |

#### 3. Case Classes (Domain Value Objects)

Search: `src/main/scala/` recursively (focus on domain packages)

Pattern to find:

```
case class <Name>(
  field1: Type1,
  field2: Type2
)
```

EXCLUDE enum case variants (they are indented and inside an enum block).
Only record top-level case classes.

For each match, record:

| Column | Value |
|--------|-------|
| Type | The case class name |
| Fields | Comma-separated field declarations (name: Type) |
| Package | The `package` declaration in the file |
| Introduced By | `scan:<filename>` |

#### 4. Service Traits (Tagless Final)

Search: `src/main/scala/` recursively

Pattern to find:

```
trait <Name>[F[_]]
trait <Name>[F[_]: Async]
trait <Name>[F[_]: Concurrent]
```

For each match, also extract all `def` declarations inside the trait body.

For each match, record:

| Column | Value |
|--------|-------|
| Trait | The trait name (without type parameter) |
| Type Param | The type parameter, e.g., `F[_]` |
| Methods | Comma-separated list of method names |
| Package | The `package` declaration in the file |
| Introduced By | `scan:<filename>` |

#### 5. Smithy Models

Search: `src/main/smithy/`, `src/main/resources/` for `*.smithy` files

Patterns to find:

```
service <Name> { ... }
structure <Name> { ... }
operation <Name> { ... }
```

For each match, record:

| Column | Value |
|--------|-------|
| Model | The model name |
| Kind | `service`, `structure`, or `operation` |
| Operations/Fields | For services: comma-separated operation names |
| Location | The `.smithy` file path |
| Introduced By | `scan:<filename>` |

#### 6. ScalaCheck Generators

Search: `src/test/scala/` recursively

Patterns to find:

```
val gen<Name>: Gen[<Type>] = ...
val gen<Name> = Gen.<method>(...)
implicit val arb<Name>: Arbitrary[<Type>] = ...
given Arbitrary[<Type>] = ...
```

For each match, record:

| Column | Value |
|--------|-------|
| Generator | The val/given name |
| Generates | `Gen[Type]` or `Arbitrary[Type]` |
| Location | The test file name |
| Introduced By | `scan:<filename>` |

#### 7. Cats Effect Resources and Middleware

This section CANNOT be auto-detected. Add a placeholder row:

```
| *(manual entry — not detectable by scanner)* | | | | |
```

Users should manually add entries for connection pools, HTTP clients, caches,
middleware pipelines, etc.

#### 8. Behavioral Concepts (registry pass)

The type inventory above catalogs *types*; the behavioral concept registry at
`openspec/concepts/` catalogs *behavior* (concepts in the Meng & Jackson
sense: purpose / state / actions / operational principle, plus
synchronizations). See `openspec/concepts/README.md` for the format.

During a scan, run this pass **against the registry** (do not regenerate it):

1. **Run the machine check.** Execute
   `openspec/schemas/verified-scala3/scanner/registry-check.sh .` — it
   verifies every Implementation-map symbol against the source tree, every
   declared fold field (`maps f1, f2, ...` convention) against its fold
   file, and every `Concept/action` cited by active change specs against the
   registry. A failing row MUST be fixed as part of the change that moved
   the code. (Manual fallback if the script is unavailable: grep each
   backticked symbol of each `## Implementation map` row yourself.)

2. **Detect new candidate concepts and syncs.** Flag for human review (do not
   auto-write concept files):
   - a new variant in a persistent entity's command/event enum that does not
     appear in any concept's action table;
   - a new message consumer handler, producer, or HTTP middleware not listed in
     any `## Synchronizations` section or in the README synchronization index;
   - a new persisted state component (e.g. a new `CustomerState` field) not
     owned by any concept — and for state-bearing concepts, verify the
     component is listed in a `maps ...` fold declaration (a field on the
     model shape that no fold writes is NOT part of the concept's state).

3. **Report** alongside the type-inventory summary:
   ```
   Behavioral registry check:
   - registry-check.sh: OK / FAILED (paste its summary line)
   - N implementation-map rows stale (list them)
   - N fold-field or spec-reference violations (list them)
   - N unregistered actions / syncs / state components (list them)
   ```

When a spec in the current change alters a concept's actions, state, or
synchronizations, updating the corresponding `openspec/concepts/*.md` file is
part of implementing that spec. Specs should reference behavior as
`Concept/action` (e.g. `Adesione/inserisci`) and link the concept file,
instead of re-enumerating implementation types in the spec body.

### After Scanning

1. Write the inventory to the concept-inventory artifact path:
   `openspec/changes/<CHANGE_NAME>/concept-inventory.md`

2. Use the template structure from:
   `openspec/schemas/verified-scala3/templates/concept-inventory.md`

3. Replace the template's placeholder comment rows with actual scan results.

4. If the project is empty (no Scala source files), create the inventory with
   empty tables (header rows only, no data rows).

5. Report a summary:
   ```
   Concept scan complete:
   - N opaque types
   - N sealed traits/enums
   - N case classes
   - N service traits
   - N Smithy models
   - N ScalaCheck generators
   ```

## Important Rules

- SCAN what exists. Do NOT invent or assume concepts.
- Record EXACT package paths (they will be used for import statements).
- Record EXACT constraint expressions (they will be used for type verification).
- If a file has multiple packages (unusual), use the first one.
- If a concept appears in multiple files, record the definition site (not usage sites).
- This is a LIVING DOCUMENT — it will be updated after each spec implementation.
