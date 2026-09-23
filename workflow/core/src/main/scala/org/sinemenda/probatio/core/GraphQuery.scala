package org.sinemenda.probatio.core

/**
 * The five operations the tool performs — the predecessor's five
 * subcommand words as a closed enum, so dispatch is total and a query
 * named by a free string cannot silently fall through to a default.
 *
 *   - `Export`      — emit the whole graph as JSON (`--output`,
 *                     `--change-dir`, `--change` restrict the payload to
 *                     one change's obligations, as the predecessor's
 *                     argparse flags do)
 *   - `Stats`       — node/edge counts by kind plus the unlinkable
 *                     warning count
 *   - `Impact`      — the transitively-cited closure of `target`
 *   - `Obligations` — the reachability audit, restricted to `change`
 *                     when given (predecessor's optional positional)
 *   - `ConceptCode` — the code paths implementing `concept`
 *
 * spec: graph-tool-port — Requirement: The ported tool exposes the five operations
 */
enum GraphQuery:
  case Export(output: Option[String], changeDir: Option[String], change: Option[String])
  case Stats
  case Impact(target: String)
  case Obligations(change: Option[String])
  case ConceptCode(concept: String)
