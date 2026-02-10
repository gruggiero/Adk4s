package org.adk4s.core.interrupt

/** Identifies a step in the execution path — an agent or tool name. */
final case class RunStep(name: String)

/** Represents the execution path from the root agent to the current event source. */
opaque type RunPath = List[RunStep]

object RunPath:
  def apply(steps: List[RunStep]): RunPath = steps

  def empty: RunPath = List.empty[RunStep]

  def of(name: String): RunPath = List(RunStep(name))

  extension (path: RunPath)
    def steps: List[RunStep] = path

    def show: String =
      path.map(_.name).mkString(" > ")

    def appended(step: RunStep): RunPath =
      path :+ step

    def prepended(step: RunStep): RunPath =
      step :: path
