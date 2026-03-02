ThisBuild / scalaVersion := "3.7.3"
ThisBuild / organization := "org.adk4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions += "-Wconf:src=target/.*:s"

// --- scalafix ---
ThisBuild / scalafixDependencies += "ch.epfl.scala" %% "scalafix-rules" % "0.12.1"
ThisBuild / scalafixOnCompile := false  // Disabled for now
Test / scalafix / unmanagedSources := Seq.empty

lazy val `structured-llm` = (project in file("structured-llm"))
  .dependsOn(
    ProjectRef(file("../../llm4s/llm4s"), "core")
  )
  .settings(
    name := "structured-llm",
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // Typesafe Config for configuration
      "com.typesafe" % "config" % "1.4.3",

      // Workflows4s
      "org.business4s" %% "workflows4s-core" % "0.4.2",

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
    ),
    // Disable scalafix for this test/example module
    Compile / scalafix / unmanagedSources := Seq.empty
  )
  .enablePlugins(Smithy4sCodegenPlugin)

lazy val `adk4s-core` = (project in file("adk4s-core"))
  .dependsOn(
    `structured-llm`,
    ProjectRef(file("../../llm4s/llm4s"), "core")
  )
  .settings(
    name := "adk4s-core",
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // fs2 for streaming
      "co.fs2" %% "fs2-core" % "3.9.4",
      "co.fs2" %% "fs2-io" % "3.9.4",

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



lazy val `adk4s-orchestration` = (project in file("adk4s-orchestration"))
  .dependsOn(
    `adk4s-core`,
    `structured-llm`
  )
  .settings(
    name := "adk4s-orchestration",
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // Workflows4s
      "org.business4s" %% "workflows4s-core" % "0.4.2",

      // fs2 for streaming
      "co.fs2" %% "fs2-core" % "3.9.4",
      "co.fs2" %% "fs2-io" % "3.9.4",

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

lazy val `adk4s-examples` = (project in file("adk4s-examples"))
  .dependsOn(
    `adk4s-core`,
    `adk4s-orchestration`,
    `structured-llm`,
    `structured-llm-test-models`,
    ProjectRef(file("../../llm4s/llm4s"), "core")
  )
  .settings(
    name := "adk4s-examples",
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // Workflows4s
      "org.business4s" %% "workflows4s-core" % "0.4.2",
      "org.business4s" %% "workflows4s-bpmn" % "0.4.2",

      // fs2 for streaming
      "co.fs2" %% "fs2-core" % "3.9.4",
      "co.fs2" %% "fs2-io" % "3.9.4",

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",

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
