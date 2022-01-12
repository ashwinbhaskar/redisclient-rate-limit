import sbt._

object Dependencies {
  object V { // Versions
    // Scala

    val logback = "1.2.6"
    val scalaLogging = "3.9.4"
    val redisClient = "3.41"
    val catsEffect = "3.3.4"

    // Test
    val testcontainersScalaVersion = "0.39.12"
    val scalaTest = "3.2.7"

    // Compiler
    val betterMonadicFor = "0.3.1"
    val kindProjector = "0.13.2"
  }

  object L { // Libraries
    // Scala
    val logback = "ch.qos.logback" % "logback-classic" % V.logback
    val scalaLogging =
      "com.typesafe.scala-logging" %% "scala-logging" % V.scalaLogging
    val redisClient = "net.debasishg" %% "redisclient" % V.redisClient
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
  }

  object T { // Test dependencies
    // Scala
    val testContainer =
      "com.dimafeng" %% "testcontainers-scala-scalatest" % V.testcontainersScalaVersion % Test
    val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % Test
  }
}
