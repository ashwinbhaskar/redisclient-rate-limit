package com.redis

import zio.test._
import Assertion._
import zio._
import com.dimafeng.testcontainers.GenericContainer
import com.redis.ratelimit._
import com.redis.common._
import com.redis.common.Config
import java.time.Instant
import java.util.UUID

object RateLimitSpec extends ZIOSpecDefault {

  private type Port = Int

  final case class RedisAddress(host: String, port: Port)

  // One Redis for the whole suite. Set REDIS_HOST (and optionally REDIS_PORT)
  // to run against an existing Redis instead of starting a container.
  private val redisLayer: ZLayer[Any, Throwable, RedisAddress] =
    ZLayer.scoped {
      sys.env.get("REDIS_HOST") match {
        case Some(host) =>
          ZIO.succeed(
            RedisAddress(host, sys.env.getOrElse("REDIS_PORT", "6379").toInt)
          )
        case None =>
          ZIO
            .acquireRelease(
              ZIO.attemptBlocking {
                val c =
                  GenericContainer("redis:7.4", exposedPorts = Seq(6379))
                c.start()
                c
              }
            )(c => ZIO.attemptBlocking(c.stop()).ignore)
            .map(c => RedisAddress(c.host, c.mappedPort(6379)))
      }
    }

  // Tests share one Redis and run in parallel, so every test needs its own key.
  // (zio-test's TestRandom is deterministic per test, so don't use it here.)
  private val runId = UUID.randomUUID().toString
  private def uniqueKey(name: String): String = s"$name-$runId"

  private def rateLimitedAssert[A](
      z: RIO[RedisClient, A],
      expectedValue: A
  ): RIO[RedisAddress, TestResult] =
    for {
      address <- ZIO.service[RedisAddress]
      redisClient = new RedisClient(address.host, address.port)
      value <- z.provideLayer(ZLayer.succeed(redisClient))
    } yield assert(value)(equalTo(expectedValue))

  private def rateLimitedAssert[A](
      z: RIO[Config, A],
      expectedValue: A,
      portToConfig: Port => Config
  ): RIO[RedisAddress, TestResult] =
    for {
      address <- ZIO.service[RedisAddress]
      config = portToConfig(address.port).copy(host = address.host)
      value <- z.provideLayer(ZLayer.succeed(config))
    } yield assert(value)(equalTo(expectedValue))

  private def rateLimitedAssert[A](
      z: RIO[RedisClient, A],
      e: RateLimitError
  ): RIO[RedisAddress, TestResult] = {
    val r =
      for {
        address <- ZIO.service[RedisAddress]
        redisClient = new RedisClient(address.host, address.port)
        value <- z.provideLayer(ZLayer.succeed(redisClient))
      } yield value
    assertZIO(r.exit)(fails(equalTo(e)))
  }

  private def rateLimitedAssertFailure[A](
      z: RIO[Config, A],
      config: Config
  ): Task[TestResult] =
    assertZIO(z.provideLayer(ZLayer.succeed(config)).exit)(
      fails(isSubtype[RedisConnectionError](anything))
    )

  private def rateLimitedAssert[A](
      z: RIO[Config, A],
      e: RateLimitError,
      portToConfig: Port => Config
  ): RIO[RedisAddress, TestResult] = {
    val r =
      for {
        address <- ZIO.service[RedisAddress]
        config = portToConfig(address.port).copy(host = address.host)
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
              key = uniqueKey("external-not-reached"),
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
              key = uniqueKey("external-breached"),
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
              key = uniqueKey("external-window-expires"),
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
              rateLimitedEffect = effect.withRateLimit(
                uniqueKey("internal-not-reached"),
                config
              )
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
              rateLimitedEffect = effect.withRateLimit(
                uniqueKey("internal-breached"),
                config
              )
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
          val key = uniqueKey("internal-window-expires")

          val rateLimitBreached =
            for {
              config <- ZIO.service[Config]
              rateLimitedEffect = effect.withRateLimit(key, config)
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
            rateLimitedEffect = effect.withRateLimit(key, config)
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
              rateLimitedEffect = effect.withRateLimit(
                uniqueKey("internal-connection-error"),
                config
              )
              _ <- TestClock.setTime(Instant.now())
              _ <- rateLimitedEffect.replicateZIO(39)
              v <- rateLimitedEffect
            } yield v

          rateLimitedAssertFailure(
            replicated,
            Config("some-host", 6379, maxTokens = 40, timeWindowInSec = 10)
          )
        }
      ) @@ TestAspect.sequential // 4.0 shares one non-thread-safe RedisClient per Config
    ).provideShared(redisLayer)
}
