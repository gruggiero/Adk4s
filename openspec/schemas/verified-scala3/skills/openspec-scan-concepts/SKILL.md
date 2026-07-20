---

name: openspec-scan-concepts
description: >
  Scan the Scala 3 codebase (multi-module) to discover and catalogue existing
  domain concepts (opaque types, sealed traits, enums, service traits, case
  classes, Smithy models, property generators). Creates or verifies the
  PROJECT-scoped living inventory at openspec/concept-inventory.md; the
  per-change inventory-check.md artifact records the verification result.
globs:

  - "openspec/concept-inventory.md"
  - "**/src/main/scala/**/*.scala"
  - "**/src/test/scala/**/*.scala"
  - "**/src/main/smithy/**/*.smithy"
metadata:
  generatedBy: verified-scala3-schema/7.0.0

---

# Scan Concepts Skill

## Purpose

Scan the project source tree and maintain the PROJECT-scoped concept
inventory at `openspec/concept-inventory.md` (a living document, sibling of
the behavioral registry `openspec/concepts/`). The inventory prevents
duplicate type creation when specs are implemented sequentially, and its
`Introduced By` provenance accumulates ACROSS changes.

## When to Use

- When creating the `concept-inventory` artifact (inventory-check.md) during
  `/opsx:continue` or `/opsx:ff`
- When manually invoked via `/opsx:scan` to verify the inventory
- Before implementing any spec during `/opsx:apply` (concept check step)

## Create vs Verify — never re-create an existing inventory

- `openspec/concept-inventory.md` MISSING → CREATE it with a full scan
  (Option A/B below).
- It EXISTS → VERIFY it: spot-check rows against the source (scan into a
  scratch file and diff relevant sections); fix stale rows PRESERVING their
  `Introduced By` provenance. A from-scratch re-scan replaces `spec:<name>`
  provenance with `scan:<file>` — that loss is exactly what project scoping
  exists to prevent.
- Either way, record the result in the change's `inventory-check.md`
  (template: `templates/inventory-check.md`).

## Procedure

### Option A: Run the scanner script (if available)

If `openspec/schemas/verified-scala3/scanner/concept-scanner.scala` exists:

```bash
# creation (inventory missing):
openspec/schemas/verified-scala3/scanner/scan.sh . --output openspec/concept-inventory.md
# verification (inventory exists) — scan to scratch and compare:
openspec/schemas/verified-scala3/scanner/scan.sh . --output /tmp/inventory-scan.md
```

The scanner is SEMANTIC and MULTI-MODULE: it PARSES sources with Scalameta
(dialects.Scala3) rather than regexing lines — no phantom types from prose,
nested declarations reported qualified (`Outer.Inner`, matching Metals
naming), sealed-trait variants enumerated from same-file subtypes — and it
discovers every `src/` root in the repo (`<module>/src/main/scala`,
top-level `src/`, nested modules), so a multi-module build is scanned
completely rather than silently yielding 0. It reports files that fail to
parse on stderr — treat a nonzero parse-failure count as an INCOMPLETE
scan and fall back to a manual scan for those files only.

### Option B: Manual scan (default)

Scan the project source tree systematically — EVERY module's source roots
(`**/src/main/scala/`, `**/src/test/scala/`), not just a top-level `src/`.
For each category below, search the indicated directories and extract the
specified patterns.

#### 1. Refined / Opaque Types

Search: every module’s `src/main/scala/` recursively (`**/src/main/scala/`)

Patterns to find (Iron-style refined types AND plain opaque types with
smart constructors — record whichever the project actually uses):

```
opaque type <Name> = <Underlying> :| <Constraint>
opaque type <Name> = <Underlying>
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
| Constraint | The full constraint expression (aliases resolved), or the smart constructor for plain opaque types |
| Package | The `package` declaration in the file |
| Introduced By | `scan:<filename>` |

#### 2. Sealed Traits and Enums

Search: every module’s `src/main/scala/` recursively (`**/src/main/scala/`)

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

Search: every module’s `src/main/scala/` recursively (`**/src/main/scala/`) (focus on domain packages)

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

Search: every module’s `src/main/scala/` recursively (`**/src/main/scala/`)

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
| Implementations | Known implementors across the repo — the scanner links extends clauses CROSS-FILE, including anonymous factory implementations (`new Trait[F]: ...`) reported as `Owner (anonymous)` |
| Package | The `package` declaration in the file |
| Introduced By | `scan:<filename>` |

#### 5. Smithy Models

Search: every module’s `src/main/smithy/` and `src/main/resources/` for `*.smithy` files

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

#### 6. Property Generators

Search: every module’s `src/test/scala/` recursively (`**/src/test/scala/`)

Patterns to find (for the DETECTED property framework — ScalaCheck and
Hedgehog both use a `Gen` type; only ScalaCheck has `Arbitrary`):

```
val gen<Name>: Gen[<Type>] = ...
val gen<Name> = Gen.<method>(...)
def gen<Name>: Gen[<Type>] = ...
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

1. CREATION: write the inventory to the PROJECT path
   `openspec/concept-inventory.md`, using the template structure from
   `openspec/schemas/verified-scala3/templates/concept-inventory.md` and
   replacing the placeholder comment rows with actual scan results.
   VERIFICATION: diff the scratch scan against the existing inventory; fix
   stale rows in place, preserving each row's `Introduced By` value.

2. If the project is empty (no Scala source files), create the inventory with
   empty tables (header rows only, no data rows).

3. Write the change's `inventory-check.md` report (template:
   `openspec/schemas/verified-scala3/templates/inventory-check.md`) with the
   consistency-check and registry-pass results.

4. Report a summary:
   ```
   Concept scan complete (created | verified openspec/concept-inventory.md):
   - N opaque types
   - N sealed traits/enums
   - N case classes
   - N service traits
   - N Smithy models
   - N property generators
   - stale rows fixed: N (provenance preserved)
   ```

## Important Rules

- SCAN what exists. Do NOT invent or assume concepts.
- NEVER re-create an existing inventory from scratch — provenance
  (`spec:<change>/<spec>`) accumulates across changes and a fresh scan
  destroys it.
- Record EXACT package paths (they will be used for import statements).
- Record EXACT constraint expressions (they will be used for type verification).
- If a file has multiple packages (unusual), use the first one.
- If a concept appears in multiple files, record the definition site (not usage sites).
- The PROJECT inventory is a LIVING DOCUMENT — apply Step 12 appends to it
  after each spec implementation.
