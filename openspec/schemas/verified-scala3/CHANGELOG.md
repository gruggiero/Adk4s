Changelog:
 13 — A PROSE-ONLY CONTROL PLANE, AND THE UNIVERSAL TIER THAT FIXES IT. The
      completion tier (Tier A Stop) was promoted on 2/3 harnesses (Claude
      Code, Devin) while the universal pre-execution tier (PreToolUse /
      tool_call) shipped nothing — no adapter config wired it. Approval
      evidence lived in the agent's own output stream (prose self-assessment)
      with no machine-decidable predicate. The defect: an agent could write
      "I verified this" and the gate had no pre-execution surface to refuse
      the edit that followed, and no provenance to distinguish a fresh-context
      review from self-assessment. The fix is the pre-execution `tool-call`
      gate (specs 1–2: oracle ordering lock, human-grant lock), wired into
      all three adapter configs at this version; harness-observed grants
      (spec 2); and Ring 8 provenance (spec 4: session field on adversarial-
      review rows, checkpoint compares to implementing session). The
      completion tier remains a 2/3-harness backstop (pi has no blocking
      completion event — verified absent, not an oversight); the pre-execution
      tier is the universal gate. The next agent's honest picture of what is
      and isn't enforced depends on knowing this asymmetry: on pi, the
      completion gate does not exist, and the pre-execution tool_call handler
      is the only enforcement surface. (2026-08-14)
 12 — A DEFINITION OF "CORRECT", AND A SUBSTRATUM TO CARRY IT. The workflow's
      goal is that the LLM writes correct code, but "correct" was never
      defined — only a procedure was ("passes rings 0-9"), which is not the
      same thing and leaves an agent nothing decidable at the moment it makes
      a claim. Read as a fault dataset rather than as history, this changelog
      records ONE defect eight times: a check reported PASS on evidence it
      never obtained (v5 unanchored diff; v8 unresolvable Source; v9 a suite
      never written; v9 W5; v10 Ring 6 skipped by reflex; v11 N/A on a repo
      with 31 concept files; v11 generatedBy never read; v11 two parsers
      reading a legitimate notation as nothing and reporting OK).
      Every instance was committed by an agent fully INTENDING to be correct.
      Motivation was never the missing ingredient, so this version adds no
      exhortation — Step 13 is already at maximum prose intensity and the
      violations happened around it, not in defiance of it. It adds a
      definition (bound / resolved / discharged), the operative rule "never
      let a claim outrun its evidence", and two corollaries closing the v11
      defects. `discharged` is the clause with no mechanism anywhere in the
      workflow today; the evidence ledger supplies it.
      Also: the zero-dependency portability rule ("bash + git only, no jq")
      is replaced by a DECLARED PREREQUISITE SET (bash, git, jq at runtime;
      shellcheck, bats, shfmt in CI). The reasoning is retained verbatim —
      "a check that only runs on one machine is a check that stops running" —
      and now served by declaring and installing the set rather than by
      having none. Consequence: hand-rolled JSON escaping is no longer
      required, and bats is available as a real test framework for the
      workflow's own 11 shell scripts, which had no tests at all.
 11 — APPLICABILITY IS A MACHINE FACT. A review marked the ALTITUDE rule
      "N/A" on a repository holding 30 concept files, having assumed
      openspec/concepts/ did not exist without ever looking. The reasoning
      that followed was sound; the fact it rested on was invented. A
      conditional check has two halves — does this rule APPLY here (a
      repository fact), and does this spec OBEY it (judgment) — and only the
      second belongs to the reviewer. spec-lint.sh now prints a CONTEXT
      block unconditionally, stating each conditional check's applicability,
      INCLUDING the negative case, so that even "N/A" is machine-attested.
      Checks 3, 6, 17 and 18 are wired to it; a report may not record N/A
      for a check the CONTEXT block says APPLIES.
      New mechanical coverage of the altitude rule itself:
      F10 — a behavioural registry exists but a spec with requirements has
           no "## Concepts Used (behavioral)" section. The structural half
           needs no semantics, so it should never have been judgment.
      W7 — code-identifier candidates inside a Given/When/Then clause: build
           commands, source files, fully-qualified names, and tokens the
           project's own two ledgers classify as code (present in
           concept-inventory.md, absent from the registry). Candidates, not
           verdicts — and silence is not a pass, since it matches shapes,
           not prose.
      A second cause, found while verifying the first: the skills carry
      "generatedBy: verified-scala3-schema/<N>" and NOTHING EVER READ IT.
      The field sat at 7.0.0 while the schema reached 11, and the installed
      copy was older still — it stopped at check 12 and did not contain the
      altitude rule at all. An agent can follow stale instructions perfectly
      and miss a check added three versions later. spec-lint.sh now reads
      the field across the common install roots and prints INSTRUCTION DRIFT
      on mismatch. Recorded-but-never-checked is the defect class this
      workflow exists to remove; it had one of its own.
      Two silent-empty-scan parser bugs fixed, both the same shape — a
      parser that accepted one legitimate notation and read the other as
      nothing, then reported OK:
       - spec-lint inventory lookup: backticked AND bare type cells (adk4s
         writes one, graphStore the other), plus a zero-row parse warning.
       - registry-check pass 3: Concepts Used rows cited BARE, not only
         backticked or linked. adk4s's active change was reading as ZERO
         references checked. Table header rows are now dropped generically
         (the row a |---| separator follows), so a second table in the
         section no longer leaks its header as a concept name, and a table
         with rows but no parsed reference FAILS instead of passing.
 10 — Ring 6 by ALGORITHMIC purity (the verified-mirror pattern). Ring 6 was
      being skipped by reflex with two rationales that are statements about
      the shipped code's TYPES, not about the algorithm: "our code uses
      Iron/cats/opaque/Mirror/inline/IO, not a PureScala fit" and "Stainless
      pins an older Scala version". Neither is grounds to skip. The pattern
      (proven in accordant4s, two instances) is: a LEAF mirror module pinned
      to the Stainless frontend version depending on nothing project-local;
      a PureScala model that reduces the algorithm to its OBSERVABLE EFFECT;
      a mandatory BRIDGE PROPERTY TEST running shipped code and model on the
      same generated inputs; and a scope note naming every law delegated
      back to Ring 3. See templates/verified-mirror.md. New: design triage
      table, capability-profile detection, apply Step 10 procedure, W6.
  9 — closing the obligation gaps the v8 audit left open:
      F8 — a TYPED source ("Property: X", "Scenario: Y") must name a heading
           that EXISTS in the spec; a typo previously resolved to nothing.
      F9 — every code-shaped Artifact token must resolve to a tracked file.
           OPT-IN (--artifacts) because a spec is written BEFORE its tests
           exist: the artifact column is a commitment at planning time and a
           fact only after implementation, so it is checked at apply Step 12.
      W5 — a requirement claiming a state is IMPOSSIBLE but enforced only by
           tests is flagged: the ladder's top tiers exist for that claim
           shape. Silenced by "tier-justified: <why not>" in Enforcement.
      W4 — now fires on ANY ordinal reference, not only when a spec uses
           ordinals exclusively; a mixed table is no safer.
      Found on adoption: an obligation naming a test suite that was never
      written (discharged by a manual run behind an aspirational name).
  8 — traceable proof obligations: the Proof-Obligations `Source` column now
      has a MANDATED format (every cell must NAME what the obligation comes
      from — "Requirement: <exact title>" preferred, "Requirement N" allowed,
      or a typed non-requirement source such as "Property: <name>"), and
      spec-lint.sh enforces it mechanically: F6 (Source names a resolvable
      reference), F7 (every requirement is named by >= 1 obligation —
      reachability, not row-counting), W4 (ordinal-only references are
      positional and break on reorder). Motivation: an audit of real specs
      found three incompatible Source conventions across projects, one of
      which named no requirement at all — so check 12 could pass on a table
      whose requirement->obligation binding was unverifiable.
      BREAKING for existing specs that used untyped Source prose.
  7 — code intelligence (option B): semantic search recipes driving a
      headless Metals MCP endpoint from schema scripts — metals-call.sh
      (bridge + resolve), impact-scan.sh (Step 0 public-type-change impact
      scan: semantic reference set + syntactic catch-all detection),
      removal-audit.sh (Step 12 orphan audit with --suggest) — plus the
      openspec-code-intel skill. All degrade to git grep deterministically;
      CI checks stay bash+git-grep only. Capability profile gains a "Code
      Intelligence" section (endpoint, Metals version, detected not
      assumed). Semantic answers are trusted only post-compile.
      concept-scanner extraction rewritten on Scalameta trees (dialects.
      Scala3) — the "SEMANTIC scanner" claim is now true: no more regex
      phantom types from prose, nested declarations qualified as
      Outer.Inner (matching Metals naming), sealed-trait variants
      enumerated, parse failures counted and reported.
  6 — project-scoped living documents: capability-profile.md and
      concept-inventory.md move to openspec/ root (siblings of the
      openspec/concepts/ registry) instead of being re-created per change —
      per-change copies drifted, died with archiving, and destroyed the
      "Introduced By spec:X" provenance. The per-change artifacts become
      thin check-reports (capability-check.md, inventory-check.md) that
      create-if-missing / verify / refresh the project files and record
      change-relevant deltas. Scanner is now MULTI-MODULE (discovers every
      src/ root — previously a silent empty scan on multi-module builds).
  5 — correctness-evidence release: Ring 0 escalates match-exhaustiveness to error
      when fatal warnings are off; oracle-polarity run (red / green-by-design) at the
      test-oracle gate; generator faithfulness (generators are part of the approved
      oracle); per-spec baseline SHA + commit (fixes Ring 5 diff targeting, gives
      checkpoints a durable identity); cross-module regression before checkpoint;
      typed contract becomes a permanent API-conformance assertion after promotion;
      Ring 8 moved BEFORE Rings 5/6/7 and made fresh-context; concurrency oracle
      rules (deterministic test kits, seed capture, repeat runs); mechanical
      spec-lint.sh + danger-scan.sh; scanner-diff concept delta; build-dependency
      delta; risk-tiered human gates; tasks.md regenerated from
      implementation-progress.md; ships openspec-property-tests and
      openspec-adversarial-review skills; stack-agnostic template vocabulary;
      registry-check verifies symbols file-scoped and cited actions against the
      actions block.
  4 — behavioral concept registry: fold-field verification, spec-reference lint,
      CI templates, install-skills.
  2.1 — first concept-registry support.
  2 — ring pipeline + typed contract + test oracle.
