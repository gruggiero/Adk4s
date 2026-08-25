package org.sinemenda.probatio.core

/**
 * Evidence that `checkpoint.sh` ran for a spec in some session (spec 9).
 *
 * The CLI layer reads the state directory for marker files and passes
 * their existence as a boolean per spec; the pure decision logic does
 * not read files.
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: PresentationMarker
 */
final case class PresentationMarker(specName: String, exists: Boolean)
