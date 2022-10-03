package com.redis

import zio.test._
import Assertion._
import zio.Scope
import zio._
import com.dimafeng.testcontainers.GenericContainer
import com.redis.ratelimit._
import com.redis.common._
import java.time.Instant

object RateLimitSpec extends ZIOSpecDefault {

  private type Port = Int
  private val redis =
    ZIO
      .acquireRelease(
        ZIO.succeed(GenericContainer("redis:latest", exposedPorts = Seq(6379)))
      )(container => ZIO.attempt(container.stop()).ignore)
      .tap(container => ZIO.attempt(container.start()))

  private def rateLimitedAssert[A](
      z: RIO[RedisClient, A],
      expectedValue: A
  ): RIO[Scope, TestResult] =
    for {
      container <- redis
      port = container.mappedPort(6379)
      redisClient = new RedisClient("localhost", port)
      value <- z.provideLayer(ZLayer.succeed(redisClient))
    } yield assert(value)(equalTo(expectedValue))

  private def rateLimitedAssert[A](
      z: RIO[Config, A],
      expectedValue: A,
      portToConfig: Port => Config
  ): RIO[Scope, TestResult] =
    for {
      container <- redis
      port = container.mappedPort(6379)
      config = portToConfig(port)
      value <- z.provideLayer(ZLayer.succeed(config))
    } yield assert(value)(equalTo(expectedValue))

  private def rateLimitedAssert[A](
      z: RIO[RedisClient, A],
      e: RateLimitError
  ): RIO[Scope, TestResult] = {
    val r =
      for {
        container <- redis
        port = container.mappedPort(6379)
        redisClient = new RedisClient("localhost", port)
        value <- z.provideLayer(ZLayer.succeed(redisClient))
      } yield value
    assertZIO(r.exit)(fails(equalTo(e)))
  }

  private def rateLimitedAssert[A](
      z: RIO[Config, A],
      e: RateLimitError,
      portToConfig: Port => Config
  ): RIO[Scope, TestResult] = {
    val r =
      for {
        container <- redis
        port = container.mappedPort(6379)
        config = portToConfig(port)
        value <- z.provideLayer(ZLayer.succeed(config))
      } yield value
    assertZIO(r.exit)(fails(equalTo(e)))
  }

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("Redis Rate Limit Spec")(
      suite("External Redis Client Rate Limit Spec")(
        test("should not rate limit when criteria not reached") {
          val effect = ZIO.succeed("foo")
          val rateLimitedEffect = effect
            .withRateLimit(
              key = "user_id_1",
              maxTokens = 40,
              timeWindowInSec = 10
            )

          val replicated =
            for {
              _ <- TestClock.setTime(Instant.now())
              _ <- rateLimitedEffect.replicateZIO(39)
              v <- rateLimitedEffect
            } yield v

          rateLimitedAssert(
            replicated,
            "foo"
          )
        },
        test("should rate limit when ratelimit is breached") {
          val effect = ZIO.succeed("foo")
          val rateLimitedEffect = effect
            .withRateLimit(
              key = "user_id_1",
              maxTokens = 40,
              timeWindowInSec = 10
            )

          val replicated =
            for {
              _ <- TestClock.setTime(Instant.now())
              _ <- rateLimitedEffect.replicateZIO(40)
              _ <- rateLimitedEffect
            } yield ()
          rateLimitedAssert(replicated, RateLimitExceeded)
        },
        test("should not rate limit after time window expires") {
          val effect = ZIO.succeed("foo")
          val rateLimitedEffect = effect
            .withRateLimit(
              key = "user_id_1",
              maxTokens = 40,
              timeWindowInSec = 4
            )

          val rateLimitBreached =
            for {
              _ <- rateLimitedEffect.replicateZIO(40)
              _ <- rateLimitedEffect
            } yield ()

          val result = for {
            _ <- TestClock.setTime(Instant.now())
            _ <- rateLimitBreached.catchSome { case e: RateLimitError =>
              ZIO.unit
            }
            _ <- TestClock.adjust(5.second)
            v <- rateLimitedEffect
          } yield v

          rateLimitedAssert(result, "foo")
        }
      ),
      suite("Internal Redis Client Rate Limit Spec")(
        test("should not rate limit when criteria not reached") {
          val effect = ZIO.succeed("foo")

          val replicated =
            for {
              config <- ZIO.service[Config]
              rateLimitedEffect = effect.withRateLimit("user_id_1", config)
              _ <- TestClock.setTime(Instant.now())
              _ <- rateLimitedEffect.replicateZIO(39)
              v <- rateLimitedEffect
            } yield v

          rateLimitedAssert(
            replicated,
            "foo",
            Config("localhost", _, maxTokens = 40, timeWindowInSec = 10)
          )
        },
        test("should rate limit when ratelimit is breached") {
          val effect = ZIO.succeed("foo")

          val replicated =
            for {
              config <- ZIO.service[Config]
              _ <- TestClock.setTime(Instant.now())
              rateLimitedEffect = effect.withRateLimit("user_id_1", config)
              _ <- rateLimitedEffect.replicateZIO(40)
              _ <- rateLimitedEffect
            } yield ()
          rateLimitedAssert(
            replicated,
            RateLimitExceeded,
            Config("localhost", _, maxTokens = 40, timeWindowInSec = 10)
          )
        },
        test("should not rate limit after time window expires") {
          val effect = ZIO.succeed("foo")

          val rateLimitBreached =
            for {
              config <- ZIO.service[Config]
              rateLimitedEffect = effect.withRateLimit("user_id_1", config)
              _ <- rateLimitedEffect.replicateZIO(40)
              _ <- rateLimitedEffect
            } yield ()

          val result = for {
            _ <- TestClock.setTime(Instant.now())
            _ <- rateLimitBreached.catchSome { case e: RateLimitError =>
              ZIO.unit
            }
            _ <- TestClock.adjust(5.second)
            config <- ZIO.service[Config]
            rateLimitedEffect = effect.withRateLimit("user_id_1", config)
            v <- rateLimitedEffect
          } yield v

          rateLimitedAssert(
            result,
            "foo",
            Config("localhost", _, maxTokens = 40, timeWindowInSec = 4)
          )
        },
        test("should give redis connection error when redis doesn't exist") {
          val effect = ZIO.succeed("foo")

          val replicated =
            for {
              config <- ZIO.service[Config]
              rateLimitedEffect = effect.withRateLimit("user_id_1", config)
              _ <- TestClock.setTime(Instant.now())
              _ <- rateLimitedEffect.replicateZIO(39)
              v <- rateLimitedEffect
            } yield v

          rateLimitedAssert(
            replicated,
            RedisConnectionError("java.net.UnknownHostException: some-host"),
            Config("some-host", _, maxTokens = 40, timeWindowInSec = 10)
          )
        }
      )
    )
}
