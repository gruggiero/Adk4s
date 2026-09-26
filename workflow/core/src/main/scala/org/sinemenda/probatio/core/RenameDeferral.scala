package org.sinemenda.probatio.core

/**
 * One rename item deliberately not carried out, and the coupling that
 * blocks it (spec 10 of `repair-probatio-cutover`).
 *
 * The v14 rename left one item undone: the schema's directory still
 * carries the pre-rename name. That deferral is recorded here as a VALUE,
 * not a paragraph — a deferral a check can read is a deferral the next
 * change inherits rather than rediscovers. All three fields are required:
 * a deferral without a reason is an omission wearing a label, so the
 * type refuses arity-shortened construction and `missing` reports blank
 * fields as incomplete.
 *
 * spec: schema-rename-completion — Concepts Introduced (new): RenameDeferral
 * spec: schema-rename-completion — Requirement: The directory rename is deferred with its coupling recorded
 * spec: schema-rename-completion — Compile-Negative: A rename deferral without a reason
 */
final case class RenameDeferral(
  item: String,
  reason: String,
  blockedBy: RenameDeferral.Coupling
)

object RenameDeferral:

  /**
   * The blocking coupling, as data: `resolutionMechanism` names how the
   * workflow tool resolves a schema (by its directory name),
   * `configurationPin` names the project configuration entry that pins
   * that name, and `recordedChangesPinning` counts the recorded changes
   * that pin it in their own metadata. The count is an `Int` so a check
   * can compare it against the on-disk count rather than trusting prose.
   *
   * spec: schema-rename-completion — Scenario: Happy path — the deferral names the coupling and the blocked items
   */
  final case class Coupling(
    resolutionMechanism: String,
    configurationPin: String,
    recordedChangesPinning: Int
  )

  /**
   * A field whose absence makes a deferral entry incomplete. Named so the
   * check's report says WHAT is missing, not just THAT something is.
   *
   * spec: schema-rename-completion — Scenario: Adversarial — a deferral without a recorded reason is not accepted
   */
  enum Missing:
    case Item
    case Reason
    case ResolutionMechanism
    case ConfigurationPin
    case RecordedChanges

  /**
   * The completeness check: returns every part of a deferral that is not
   * carried. An empty result means the entry is complete; a non-empty
   * result names what it lacks.
   *
   * spec: schema-rename-completion — Scenario: Adversarial — a deferral without a recorded reason is not accepted
   */
  def missing(d: RenameDeferral): List[Missing] =
    List(
      Option.when(d.item.isBlank)(Missing.Item),
      Option.when(d.reason.isBlank)(Missing.Reason),
      Option.when(d.blockedBy.resolutionMechanism.isBlank)(Missing.ResolutionMechanism),
      Option.when(d.blockedBy.configurationPin.isBlank)(Missing.ConfigurationPin),
      Option.when(d.blockedBy.recordedChangesPinning <= 0)(Missing.RecordedChanges)
    ).flatten

  /**
   * The deferrals this change records — currently one: the schema
   * directory rename. A list, not a single value, so a later deferral is
   * an entry rather than a type change.
   *
   * A `def`, not a `val`: a `val` is evaluated in the companion's
   * initialiser, so an unimplemented record would poison every use of
   * `RenameDeferral.apply` — the type would be unconstructible, not
   * merely unrecorded.
   *
   * spec: schema-rename-completion — Scenario: Happy path — the deferral names the coupling and the blocked items
   */
  def recorded: List[RenameDeferral] = List(
    RenameDeferral(
      item = "schema directory rename: openspec/schemas/verified-scala3 → openspec/schemas/probatio",
      reason = "renaming the directory without an alias would break resolution of every change that pins " +
        "the current directory name — the deferral is recorded so the next change inherits the " +
        "decision rather than rediscovering it",
      blockedBy = Coupling(
        resolutionMechanism = "the workflow tool resolves a schema by its directory name",
        configurationPin = "openspec/config.yaml pins schema: verified-scala3",
        recordedChangesPinning = 20
      )
    )
  )

end RenameDeferral
