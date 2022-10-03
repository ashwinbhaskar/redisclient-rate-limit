import Dependencies._

val scalaVersion213 = "2.13.8"
val scalaVersion212 = "2.12.15"
val scalaVersion301 = "3.0.1"
val supportedScalaVersions = List(scalaVersion213)
ThisBuild / scalaVersion := scalaVersion213
ThisBuild / version := "4.0.0"
ThisBuild / organization := "io.github.ashwinbhaskar"
ThisBuild / organizationName := "redis.ratelimit"
ThisBuild / crossScalaVersions := supportedScalaVersions

lazy val commonSettings = Seq(
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),
  homepage := Some(
    url("https://github.com/ashwinbhaskar/redisclient-rate-limit")
  ),
  developers := List(
    Developer(
      "ashwinbhaskar",
      "Ashwin Bhaskar",
      "ashwinbhskr@gmail.com",
      url("https://github.com/ashwinbhaskar")
    )
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/ashwinbhaskar/redisclient-rate-limit"),
      "scm:git@github.com:ashwinbhaskar/redisclient-rate-limit.git"
    )
  ),
  licenses += (
    "Apache-2.0",
    url("http://www.apache.org/licenses/LICENSE-2.0")
  ),
  publishMavenStyle := true,
// Add sonatype repository settings
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),
  scalaVersion := scalaVersion213,
  version := "4.0.0",
  organization := "io.github.ashwinbhaskar",
  organizationName := "redis.ratelimit",
  crossScalaVersions := supportedScalaVersions
)

lazy val common = (project in file("common"))
  .settings(
    name := "redis-rate-limit-common",
    scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings"))),
    libraryDependencies ++= Seq(
      L.redisClient
    )
  ).settings(commonSettings)

lazy val catsEffect = (project in file("cats-effect"))
  .settings(
    name := "redis-rate-limit-ce",
    libraryDependencies ++= Seq(
      L.catsEffect,
      T.scalaTest,
      T.testContainer
    )
  )
  .settings(commonSettings)
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
  .settings(commonSettings)
  .dependsOn(common)
