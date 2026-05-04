// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.18"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "com.github.peanut321"

val chiselVersion = "7.7.0"

// 单核 VM 修复：Verilator 并行度至少为 1
Test / fork        := true
Test / javaOptions += "-XX:ActiveProcessorCount=2"

lazy val root = (project in file("."))
  .settings(
    name := "MyConvAccel",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
