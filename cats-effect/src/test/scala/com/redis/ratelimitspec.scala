package com.redis

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.BeforeAndAfterAll
import com.dimafeng.testcontainers.GenericContainer
import com.redis.RedisClient
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import scala.concurrent.duration.FiniteDuration
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.redis.ratelimit._
import com.redis.common._

class RateLimitSpec extends AnyFlatSpec with BeforeAndAfterAll {

  // One Redis for the whole suite. Set REDIS_HOST (and optionally REDIS_PORT)
  // to run against an existing Redis instead of starting a container.
  private var container: Option[GenericContainer] = None
  private var redisHost: String = _
  private var redisPort: Int = _

  override def beforeAll(): Unit =
    sys.env.get("REDIS_HOST") match {
      case Some(host) =>
        redisHost = host
        redisPort = sys.env.getOrElse("REDIS_PORT", "6379").toInt
      case None =>
        val c = GenericContainer("redis:7.4", exposedPorts = Seq(6379))
        c.start()
        container = Some(c)
        redisHost = c.host
        redisPort = c.mappedPort(6379)
    }

  override def afterAll(): Unit = container.foreach(_.stop())

  // Tests share one Redis, so every test needs its own key.
  private val runId = UUID.randomUUID().toString
  private def uniqueKey(name: String): String = s"$name-$runId"

  "rateLimited" should "not rate limit" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      effect,
      key = uniqueKey("not-rate-limit"),
      maxTokens = 40,
      timeWindowInSec = 10
    )
    val result = rateLimitedCall.attempt

    result.unsafeRunSync() match {
      case Left(th)     => fail(th)
      case Right(value) => assert(value == "foo")
    }
  }

  "rateLimited" should "rate limit" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      effect,
      key = uniqueKey("rate-limit"),
      maxTokens = 40,
      timeWindowInSec = 10
    )

    val result = (for {
      l <- rateLimitedCall.attempt.replicateA(40)
      r <- rateLimitedCall
    } yield r).attempt

    result.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Rate limited should have exceeded")
    }
  }

  "rateLimited with config" should "rate limit" in {
    implicit val config =
      Config(redisHost, redisPort, maxTokens = 40, timeWindowInSec = 10)
    val effect = IO.pure("foo")
    val rateLimitedCall =
      rateLimited[IO, String](effect, key = uniqueKey("config-rate-limit"))

    val result = (for {
      l <- rateLimitedCall.attempt.replicateA(40)
      r <- rateLimitedCall
    } yield r).attempt

    result.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Rate limited should have exceeded")
    }
  }

  "rateLimited" should "allow requests after the time window expires" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)

    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      effect,
      key = uniqueKey("window-expires"),
      maxTokens = 10,
      timeWindowInSec = 4
    )

    val result1 = (for {
      l <- rateLimitedCall.attempt.replicateA(10)
      r <- rateLimitedCall
    } yield r).attempt

    result1.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Rate limited should have exceeded")
    }

    val result2 = (for {
      _ <- IO.sleep(FiniteDuration.apply(5, TimeUnit.SECONDS))
      r <- rateLimitedCall
    } yield r).attempt

    result2.unsafeRunSync() match {
      case Left(th)     => fail(th)
      case Right(value) => assert(value == "foo")
    }
  }

  "rateLimited with config" should "allow requests after the time window expires" in {
    implicit val config =
      Config(redisHost, redisPort, maxTokens = 10, timeWindowInSec = 4)
    val effect = IO.pure("foo")
    val rateLimitedCall =
      rateLimited[IO, String](effect, key = uniqueKey("config-window-expires"))

    val result1 = (for {
      l <- rateLimitedCall.attempt.replicateA(10)
      r <- rateLimitedCall
    } yield r).attempt

    result1.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Rate limited should have exceeded")
    }

    val result2 = (for {
      _ <- IO.sleep(FiniteDuration.apply(5, TimeUnit.SECONDS))
      r <- rateLimitedCall
    } yield r).attempt

    result2.unsafeRunSync() match {
      case Left(th)     => fail(th)
      case Right(value) => assert(value == "foo")
    }
  }

  "rateLimited" should "should not allow burst of requests at window boundaries that exceed maxTokens" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)

    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      effect,
      key = uniqueKey("boundary-burst"),
      maxTokens = 6,
      timeWindowInSec = 5
    )

    val result1 = (for {
      _ <- rateLimitedCall
      _ <- IO.sleep(FiniteDuration(4, TimeUnit.SECONDS))
      _ <- rateLimitedCall.attempt.replicateA(
        4
      ) // We use up our tokens near the end of the timeWindow
      r <- rateLimitedCall
    } yield r).attempt

    result1.unsafeRunSync() match {
      case Left(th)     => fail(th)
      case Right(value) => assert(value == "foo")
    }

    val result2 = (for {
      _ <- IO.sleep(
        FiniteDuration(1, TimeUnit.SECONDS)
      ) // Sleep till we reach the beginning of the next timeWindow
      _ <-
        rateLimitedCall // We make 2 quick calls at the beginning of the next timeWindow
      r <- rateLimitedCall
    } yield r).attempt

    result2.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Should have failed")
    }
  }

  "rateLimited with config" should "should not allow burst of requests at window boundaries that exceed maxTokens" in {
    implicit val config =
      Config(redisHost, redisPort, maxTokens = 6, timeWindowInSec = 5)
    val effect = IO.pure("foo")
    val rateLimitedCall =
      rateLimited[IO, String](effect, key = uniqueKey("config-boundary-burst"))

    val result1 = (for {
      _ <- rateLimitedCall
      _ <- IO.sleep(FiniteDuration(4, TimeUnit.SECONDS))
      _ <- rateLimitedCall.attempt.replicateA(
        4
      ) // We use up our tokens near the end of the timeWindow
      r <- rateLimitedCall
    } yield r).attempt

    result1.unsafeRunSync() match {
      case Left(th)     => fail(th)
      case Right(value) => assert(value == "foo")
    }

    val result2 = (for {
      _ <- IO.sleep(
        FiniteDuration(1, TimeUnit.SECONDS)
      ) // Sleep till we reach the beginning of the next timeWindow
      _ <-
        rateLimitedCall // We make 2 quick calls at the beginning of the next timeWindow
      r <- rateLimitedCall
    } yield r).attempt

    result2.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Should have failed")
    }
  }

  "rateLimited" should "fail with RedisConnectionError" in {
    val badPort = 2342
    implicit val redisClient = new RedisClient("localhost", badPort)

    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      effect,
      key = uniqueKey("connection-error"),
      maxTokens = 40,
      timeWindowInSec = 10
    )
    val result = rateLimitedCall.attempt

    result.unsafeRunSync() match {
      case Left(_: RedisConnectionError) => succeed
      case Left(th)                      => fail(th)
      case Right(value) => fail("Should be RedisConnectionError")
    }
  }

  "rateLimited with config" should "fail with RedisConnectionError" in {
    val badPort = 2342
    implicit val config =
      Config("localhost", badPort, maxTokens = 40, timeWindowInSec = 10)
    val effect = IO.pure("foo")
    val rateLimitedCall =
      rateLimited[IO, String](
        effect,
        key = uniqueKey("config-connection-error")
      )
    val result = rateLimitedCall.attempt

    result.unsafeRunSync() match {
      case Left(_: RedisConnectionError) => succeed
      case Left(th)                      => fail(th)
      case Right(value) => fail("Should be RedisConnectionError")
    }
  }
}
