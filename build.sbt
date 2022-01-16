import Dependencies._

homepage := Some(url("https://github.com/ashwinbhaskar/redisclient-rate-limit"))
developers := List(Developer("ashwinbhaskar",
                             "Ashwin Bhaskar",
                             "ashwinbhskr@gmail.com",
                             url("https://github.com/ashwinbhaskar")))
scmInfo := Some(
  ScmInfo(url("https://github.com/ashwinbhaskar/redisclient-rate-limit"),
    "scm:git@github.com:ashwinbhaskar/redisclient-rate-limit.git"))
licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

publishMavenStyle := true
// Add sonatype repository settings
publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)

ThisBuild / scalaVersion     := "2.13.7"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "io.github.ashwinbhaskar"
ThisBuild / organizationName := "redis.ratelimit"

lazy val root = (project in file("."))
  .settings(
    name := "redis-rate-limit",
    scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings"))),
    libraryDependencies ++= Seq(
      L.redisClient,
      L.catsEffect,
      T.scalaTest,
      T.testContainer
    )
  )
