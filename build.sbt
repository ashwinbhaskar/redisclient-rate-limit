import Dependencies._

ThisBuild / scalaVersion     := "2.13.7"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "io.github.ashwinbhaskar"
ThisBuild / organizationName := "redis.ratelimit"

lazy val root = (project in file("."))
  .settings(
    name := "redis-rate-limit",
    scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings"))),
    libraryDependencies ++= Seq(
      L.redisClient,
      L.scalaLogging,
      L.logback,
      L.catsEffect,
      T.scalaTest,
      T.testContainer
    )
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
