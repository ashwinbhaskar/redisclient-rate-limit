package com.redis

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.BeforeAndAfterAll
import com.dimafeng.testcontainers.GenericContainer
import com.redis.RedisClient
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import scala.concurrent.duration._
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

  "rateLimited" should "never deny steady traffic below the limit" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val call = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("steady"),
      maxTokens = 2,
      timeWindowInSec = 1
    )

    // At most 2 calls fall in any 1s window. 4.0 denied the third call,
    // because every allowed call restarted the window.
    val results =
      (call.attempt <* IO.sleep(600.millis)).replicateA(6).unsafeRunSync()

    assert(results.forall(_ == Right("foo")), results)
  }

  "rateLimited with config" should "allow exactly maxTokens of many concurrent calls" in {
    implicit val config =
      Config(redisHost, redisPort, maxTokens = 50, timeWindowInSec = 60)
    val call = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("concurrent-config")
    )

    val results = List.fill(200)(call.attempt).parSequence.unsafeRunSync()

    assert(results.count(_.isRight) == 50)
    assert(results.collect { case Left(e) => e }.forall(_ == RateLimitExceeded))
  }

  "rateLimited" should "allow exactly maxTokens of many concurrent calls on a shared client" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val call = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("concurrent-shared"),
      maxTokens = 50,
      timeWindowInSec = 60
    )

    val results = List.fill(200)(call.attempt).parSequence.unsafeRunSync()

    assert(results.count(_.isRight) == 50)
    assert(results.collect { case Left(e) => e }.forall(_ == RateLimitExceeded))
  }

  "rateLimited" should "expire its key one window after the last call" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val key = uniqueKey("ttl")

    rateLimited[IO, String](
      IO.pure("foo"),
      key,
      maxTokens = 5,
      timeWindowInSec = 60
    )
      .unsafeRunSync()

    val ttl = redisClient.pttl(SlidingWindow.redisKey(key, 5, 60))
    assert(ttl.exists(t => t > 0 && t <= 60000), ttl)
  }

  "rateLimited" should "keep different limits on the same key independent" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val key = uniqueKey("independent")
    val strict = rateLimited[IO, String](
      IO.pure("foo"),
      key,
      maxTokens = 2,
      timeWindowInSec = 60
    )
    val loose = rateLimited[IO, String](
      IO.pure("foo"),
      key,
      maxTokens = 5,
      timeWindowInSec = 60
    )

    val (strictResults, looseResults) =
      (strict.attempt.replicateA(3), loose.attempt.replicateA(5)).tupled
        .unsafeRunSync()

    assert(strictResults.count(_.isRight) == 2)
    assert(looseResults.forall(_.isRight), looseResults)
  }

  "rateLimited" should "recover when Redis has lost the cached script" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val call = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("noscript"),
      maxTokens = 5,
      timeWindowInSec = 60
    )

    call.unsafeRunSync()
    redisClient.scriptFlush

    assert(call.attempt.unsafeRunSync() == Right("foo"))
  }

  "rateLimited" should "keep the message and cause of a connection error" in {
    implicit val redisClient = new RedisClient("localhost", 2342)
    val result = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("error-cause"),
      maxTokens = 5,
      timeWindowInSec = 60
    ).attempt.unsafeRunSync()

    result match {
      case Left(e: RedisConnectionError) =>
        assert(e.getMessage != null)
        assert(e.getCause != null)
      case other => fail(s"Expected RedisConnectionError, got $other")
    }
  }

  "rateLimited" should "deny every call when maxTokens is 0" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val result = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("zero-tokens"),
      maxTokens = 0,
      timeWindowInSec = 60
    ).attempt.unsafeRunSync()

    assert(result == Left(RateLimitExceeded))
  }

  "rateLimited" should "reject a time window that isn't positive" in {
    implicit val redisClient = new RedisClient(redisHost, redisPort)
    val result = rateLimited[IO, String](
      IO.pure("foo"),
      key = uniqueKey("zero-window"),
      maxTokens = 5,
      timeWindowInSec = 0
    ).attempt.unsafeRunSync()

    assert(result.left.exists(_.isInstanceOf[IllegalArgumentException]), result)
  }

  "the rate limit script" should "count a re-sent call only once" in {
    val client = new RedisClient(redisHost, redisPort)
    val key = uniqueKey("idempotent")
    val now = System.currentTimeMillis()

    // scala-redis re-sends a command after a dropped connection; the script
    // may already have run for that member.
    val first = ScriptRunner.run(client, key, 1, 60, now, "member-1")
    val resent = ScriptRunner.run(client, key, 1, 60, now, "member-1")
    val other = ScriptRunner.run(client, key, 1, 60, now, "member-2")

    assert(first.allowed && first.remaining == 0)
    assert(resent.allowed)
    assert(!other.allowed)
    assert(other.retryAfterMs > 0 && other.retryAfterMs <= 60000)
  }
}
