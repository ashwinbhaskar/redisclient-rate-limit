package com.redis

import cats.effect.kernel.Sync
import cats.implicits._
import com.redis.RedisClient
import com.redis.common._

package object ratelimit {

  /** Runs `fa` only if `key` has made fewer than `config.maxTokens` calls in
    * the last `config.timeWindowInSec` seconds; otherwise fails with
    * [[com.redis.common.RateLimitExceeded]].
    *
    * Uses a connection pool per Redis host and port, so it is safe to call
    * concurrently. Prefer this overload over the one taking a `RedisClient`.
    */
  def rateLimited[F[_], A](fa: F[A], key: String)(implicit
      config: Config,
      F: Sync[F]
  ): F[A] =
    F.delay(ClientCache.pool(config.host, config.port))
      .flatMap(pool =>
        checkLimit(key, config.maxTokens, config.timeWindowInSec)(
          pool.withClient(_)
        )
      ) *> fa

  /** Runs `fa` only if `key` has made fewer than `maxTokens` calls in the last
    * `timeWindowInSec` seconds; otherwise fails with
    * [[com.redis.common.RateLimitExceeded]].
    *
    * A scala-redis `RedisClient` is a single connection and is not thread-safe.
    * This library serialises its own calls on `redisClient`, but other code
    * using the same client concurrently can still corrupt replies. The overload
    * taking a [[com.redis.common.Config]] uses a pool instead.
    */
  def rateLimited[F[_], A](
      fa: F[A],
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  )(implicit redisClient: RedisClient, F: Sync[F]): F[A] =
    checkLimit(key, maxTokens, timeWindowInSec)(run =>
      redisClient.synchronized(run(redisClient))
    ) *> fa

  private def checkLimit[F[_]](
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  )(withClient: (RedisClient => Decision) => Decision)(implicit
      F: Sync[F]
  ): F[Unit] =
    if (maxTokens <= 0) F.raiseError(RateLimitExceeded)
    else if (timeWindowInSec <= 0)
      F.raiseError(
        new IllegalArgumentException(
          s"timeWindowInSec must be positive, was $timeWindowInSec"
        )
      )
    else
      for {
        now <- F.realTime
        member <- F.delay(SlidingWindow.newMember(now.toMillis))
        decision <- F
          .blocking(
            withClient(client =>
              ScriptRunner.run(
                client,
                key,
                maxTokens,
                timeWindowInSec,
                now.toMillis,
                member
              )
            )
          )
          .adaptError { case e => connectionError(e) }
        _ <- F.raiseUnless(decision.allowed)(RateLimitExceeded)
      } yield ()
}
