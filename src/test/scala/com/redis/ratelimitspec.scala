package com.redis

import org.scalatest.flatspec.AnyFlatSpec
import com.dimafeng.testcontainers.GenericContainer
import com.redis.RedisClient
import cats.effect.IO
import cats.effect.implicits._
import cats.effect.unsafe.implicits.global
import cats.implicits._
import java.time.ZonedDateTime
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class RateLimitSpec extends AnyFlatSpec {

  private def withRedisPort(f: Int => Any) = {
    val redisContainer =
      GenericContainer("redis:latest", exposedPorts = Seq(6379))
    redisContainer.start()
    f(
      redisContainer.mappedPort(6379)
    )
    redisContainer.stop()
  }

  "rateLimited" should "not rate limit" in withRedisPort { redisPort =>
    implicit val redisClient = new RedisClient("localhost", redisPort)
    import com.redis.ratelimit._
    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      keyPrefix = "my_prefix",
      threshold = 40,
      windowInSec = 10,
      nowInEpochSec = ZonedDateTime.now.toEpochSecond
    )(effect)
    val result = rateLimitedCall.attempt

    result.unsafeRunSync() match {
      case Left(th)     => fail(th)
      case Right(value) => assert(value == "foo")
    }
  }

  "rateLimited" should "rate limit" in withRedisPort { redisPort =>
    implicit val redisClient = new RedisClient("localhost", redisPort)
    import com.redis.ratelimit._
    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      keyPrefix = "my_prefix",
      threshold = 40,
      windowInSec = 10,
      nowInEpochSec = ZonedDateTime.now.toEpochSecond
    )(effect)
    
    val result = (for {
      l <- rateLimitedCall.attempt.replicateA(40)
      r <- rateLimitedCall
    } yield r).attempt

    result.unsafeRunSync() match {
      case Left(th)     => assert(th == RateLimitExceeded)
      case Right(value) => fail("Rate limited should have exceeded")
    }
  }

  "rateLimited" should "fail with RedisConnectionError" in withRedisPort { _ =>
    val badPort = 2342
    implicit val redisClient = new RedisClient("localhost", badPort)
    import com.redis.ratelimit._
    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](
      keyPrefix = "my_prefix",
      threshold = 40,
      windowInSec = 10,
      nowInEpochSec = ZonedDateTime.now.toEpochSecond
    )(effect)
    val result = rateLimitedCall.attempt

    result.unsafeRunSync() match {
      case Left(th) =>
        assert(
          th == RedisConnectionError(
            "java.net.ConnectException: Connection refused (Connection refused)"
          )
        )
      case Right(value) => fail("Should be RedisConnectionError")
    }
  }
}
