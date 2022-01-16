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
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

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
    val rateLimitedCall = rateLimited[IO, String](effect)(
      key = "user_id_1",
      maxTokens = 40,
      timeWindowInSec = 10
    )
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
    val rateLimitedCall = rateLimited[IO, String](effect)(
      key = "user_id_2",
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

  "rateLimited" should "allow requests after the time window expires" in withRedisPort {
    redisPort =>
      implicit val redisClient = new RedisClient("localhost", redisPort)
      import com.redis.ratelimit._
      val effect = IO.pure("foo")
      val rateLimitedCall = rateLimited[IO, String](effect)(
        key = "user_id_3",
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

  "rateLimited" should "should not allow burst of requests at window boundaries that exceed maxTokens" in withRedisPort {
    redisPort =>
      implicit val redisClient = new RedisClient("localhost", redisPort)
      import com.redis.ratelimit._
      val effect = IO.pure("foo")
      val rateLimitedCall = rateLimited[IO, String](effect)(
        key = "user_id_4",
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
        ) //Sleep till we reach the beginning of the next timeWindow
        _ <-
          rateLimitedCall //We make 2 quick calls at the beginning of the next timeWindow
        r <- rateLimitedCall
      } yield r).attempt

      result2.unsafeRunSync() match {
        case Left(th)     => assert(th == RateLimitExceeded)
        case Right(value) => fail("Should have failed")
      }
  }

  "rateLimited" should "fail with RedisConnectionError" in withRedisPort { _ =>
    val badPort = 2342
    implicit val redisClient = new RedisClient("localhost", badPort)
    import com.redis.ratelimit._
    val effect = IO.pure("foo")
    val rateLimitedCall = rateLimited[IO, String](effect)(
      key = "user_id_5",
      maxTokens = 40,
      timeWindowInSec = 10
    )
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
