// Plugin versions — kept in sync with project/Versions.scala
addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix"   % "0.14.7")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.6.1")
addSbtPlugin("org.scoverage"  % "sbt-scoverage"  % "2.4.4")
addSbtPlugin("com.eed3si9n"   % "sbt-assembly"   % "2.3.1")
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.18.55")

// Ring 1 — WartRemover static analysis
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.5.8")

// Ring 5 — Mutation testing
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.21.0")

// Ring 6 — Stainless formal verification (bundled jar, not on Maven Central)
// The jar in project/lib/sbt-stainless.jar provides ch.epfl.lara.sbt.stainless.StainlessPlugin
