import sbt._

object Dependencies {
  object V { // Versions
    // Scala

    val redisClient = "3.41"
    val catsEffect = "3.7.1"
    val zio = "2.1.26"

    // Test
    val testcontainersScalaVersion = "0.44.1"
    val scalaTest = "3.2.20"
  }

  object L { // Libraries
    // Scala
    val redisClient = "net.debasishg" %% "redisclient" % V.redisClient
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val zio = "dev.zio" %% "zio" % V.zio

  }

  object T { // Test dependencies
    // Scala
    val testContainer =
      "com.dimafeng" %% "testcontainers-scala-scalatest" % V.testcontainersScalaVersion % Test
    val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % Test
    val zioTest = "dev.zio" %% "zio-test" % V.zio % Test
    val zioTestSbt = "dev.zio" %% "zio-test-sbt" % V.zio % Test
  }

  object B { // Benchmark dependencies (bench is not published)
    val testContainer =
      "com.dimafeng" %% "testcontainers-scala-core" % V.testcontainersScalaVersion
  }
}
