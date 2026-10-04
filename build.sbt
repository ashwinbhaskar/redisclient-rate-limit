import Dependencies._

val scalaVersion213 = "2.13.18"

ThisBuild / scalaVersion := scalaVersion213
ThisBuild / organization := "io.github.ashwinbhaskar"
ThisBuild / organizationName := "redis.ratelimit"
ThisBuild / homepage := Some(
  url("https://github.com/ashwinbhaskar/redisclient-rate-limit")
)
ThisBuild / developers := List(
  Developer(
    "ashwinbhaskar",
    "Ashwin Bhaskar",
    "ashwinbhskr@gmail.com",
    url("https://github.com/ashwinbhaskar")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/ashwinbhaskar/redisclient-rate-limit"),
    "scm:git@github.com:ashwinbhaskar/redisclient-rate-limit.git"
  )
)
ThisBuild / licenses := List(License.Apache2)
ThisBuild / versionScheme := Some("early-semver")

// Versions come from git tags (sbt-dynver via sbt-ci-release); publishing goes
// to the Sonatype Central Portal via `sbt ci-release`.

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := Set(organization.value %% moduleName.value % "4.0.0")
)

lazy val root = (project in file("."))
  .settings(
    name := "redis-rate-limit",
    publish / skip := true,
    mimaPreviousArtifacts := Set.empty
  )
  .aggregate(common, catsEffect, zio)

lazy val common = (project in file("common"))
  .settings(
    name := "redis-rate-limit-common",
    libraryDependencies ++= Seq(
      L.redisClient
    )
  )
  .settings(mimaSettings)

lazy val catsEffect = (project in file("cats-effect"))
  .settings(
    name := "redis-rate-limit-ce",
    libraryDependencies ++= Seq(
      L.catsEffect,
      T.scalaTest,
      T.testContainer
    )
  )
  .settings(mimaSettings)
  .dependsOn(common)

lazy val zio = (project in file("zio"))
  .settings(
    name := "redis-rate-limit-zio",
    libraryDependencies ++= Seq(
      L.zio,
      T.zioTest,
      T.zioTestSbt,
      T.testContainer
    ),
    testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
  )
  .settings(mimaSettings)
  .dependsOn(common)
