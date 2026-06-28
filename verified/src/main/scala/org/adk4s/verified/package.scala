package org.adk4s.verified

/**
 *  Ring 6 — Stainless formal verification module.
 *
 *  This is a LEAF module pinned to Scala 3.7.2 (the version Stainless's
 *  bundled frontend supports). It contains pure-model mirrors of algorithms
 *  from the main modules, verified via Stainless verification conditions (VCs).
 *
 *  The module is NOT aggregated by root, so normal builds skip it.
 *  Run Ring 6 with: `sbt -J-Xmx6g ring6`
 *
 *  Place PureScala model files here (e.g. `OracleKernel.scala`) that mirror
 *  the algorithms to be formally verified. The real implementations in the
 *  main modules are pinned to the SAME algorithm by Ring 3 reference-oracle
 *  properties.
 */
