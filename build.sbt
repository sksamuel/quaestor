lazy val root = project
  .in(file("."))
  .settings(
    name := "scapedot",
    description := "Static code analysis for Scala 3",
    version := "0.1.0",
    scalaVersion := "3.0.0-RC3"
  )
