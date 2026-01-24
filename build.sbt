ThisBuild / scalaVersion := "3.7.3"
ThisBuild / organization := "org.adk4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions += "-Wconf:src=target/.*:s"

lazy val `structured-llm` = (project in file("structured-llm"))
  .dependsOn(
    ProjectRef(file("../../business4s/workflows4s"), "workflows4s-core"),
    ProjectRef(file("../../llm4s/llm4s"), "core")
  )
  .settings(
    name := "structured-llm",
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // Typesafe Config for configuration
      "com.typesafe" % "config" % "1.4.3",

      // Smithy4s for schema definitions
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-json" % smithy4sVersion.value,

      // Testing
      "org.scalameta" %% "munit" % "1.0.3" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test
    ),

    // Scala 3 settings
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xkind-projector:underscores",
      "-source:future"
    )
  )

lazy val `structured-llm-test-models` = (project in file("structured-llm-test-models"))
  .dependsOn(`structured-llm` % "compile->compile")
  .settings(
    name := "structured-llm-test-models",
    libraryDependencies ++= Seq(
      // Typesafe Config for configuration
      "com.typesafe" % "config" % "1.4.3",

      // Testing dependencies
      "org.scalameta" %% "munit" % "1.0.3" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test
    )
  )
  .enablePlugins(Smithy4sCodegenPlugin)
