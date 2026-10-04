package com.redis

import zio._
import com.redis.common._
import com.redis.common.Config
import java.util.concurrent.TimeUnit

package object ratelimit {

  implicit class RateLimitOps[R, A](val z: RIO[R, A]) {

    /** Runs the effect only if `key` has made fewer than `config.maxTokens`
      * calls in the last `config.timeWindowInSec` seconds; otherwise fails with
      * [[com.redis.common.RateLimitExceeded]].
      *
      * Uses a connection pool per Redis host and port, so it is safe to call
      * concurrently. Prefer this overload over the one needing a `RedisClient`.
      */
    def withRateLimit(key: String, config: Config): RIO[R, A] =
      rateLimited(z, key, config)

    /** Runs the effect only if `key` has made fewer than `maxTokens` calls in
      * the last `timeWindowInSec` seconds; otherwise fails with
      * [[com.redis.common.RateLimitExceeded]].
      *
      * A scala-redis `RedisClient` is a single connection and is not
      * thread-safe. This library serialises its own calls on the client, but
      * other code using the same client concurrently can still corrupt replies.
      * The overload taking a [[com.redis.common.Config]] uses a pool instead.
      */
    def withRateLimit(
        key: String,
        maxTokens: Long,
        timeWindowInSec: Long
    ): RIO[R with RedisClient, A] =
      rateLimited(z, key, maxTokens, timeWindowInSec)
  }

  private def rateLimited[R, A](
      z: RIO[R, A],
      key: String,
      config: Config
  ): RIO[R, A] =
    ZIO
      .succeed(ClientCache.pool(config.host, config.port))
      .flatMap(pool =>
        checkLimit(key, config.maxTokens, config.timeWindowInSec)(
          pool.withClient(_)
        )
      ) *> z

  private def rateLimited[R, A](
      z: RIO[R, A],
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  ): RIO[RedisClient with R, A] =
    ZIO.serviceWithZIO[RedisClient](redisClient =>
      checkLimit(key, maxTokens, timeWindowInSec)(run =>
        redisClient.synchronized(run(redisClient))
      )
    ) *> z

  private def checkLimit(
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  )(withClient: (RedisClient => Decision) => Decision): Task[Unit] =
    if (maxTokens <= 0) ZIO.fail(RateLimitExceeded)
    else if (timeWindowInSec <= 0)
      ZIO.fail(
        new IllegalArgumentException(
          s"timeWindowInSec must be positive, was $timeWindowInSec"
        )
      )
    else
      for {
        nowMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
        member <- ZIO.succeed(SlidingWindow.newMember(nowMs))
        decision <- ZIO
          .attemptBlocking(
            withClient(client =>
              ScriptRunner.run(
                client,
                key,
                maxTokens,
                timeWindowInSec,
                nowMs,
                member
              )
            )
          )
          .mapError(connectionError)
        _ <- ZIO.fail(RateLimitExceeded).unless(decision.allowed)
      } yield ()
}
