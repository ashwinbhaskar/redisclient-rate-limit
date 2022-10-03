package com.redis

import scala.collection.mutable
import java.time.ZonedDateTime
import cats.effect.kernel.Sync
import cats.implicits._
import com.redis.RedisClient
import com.redis.common._

package object ratelimit {

  private val clients: mutable.Map[Config, RedisClient] =
    mutable.Map.empty

  def rateLimited[F[_], A](fa: F[A], key: String)(implicit
      config: Config,
      F: Sync[F]
  ): F[A] =
    F.delay(
      clients.getOrElseUpdate(config, new RedisClient(config.host, config.port))
    ).flatMap(implicit client =>
      rateLimited(fa, key, config.maxTokens, config.timeWindowInSec)
    )

  def rateLimited[F[_], A](
      fa: F[A],
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  )(implicit redisClient: RedisClient, F: Sync[F]): F[A] = {
    val lastResetTimeKey = key ++ lastResetTimeSuffix
    val counterKey = key ++ counterSuffix
    for {
      nowInEpochSec <- F.delay(ZonedDateTime.now.toEpochSecond)
      remainingTokens <- F
        .blocking(
          redisClient.evalInt(
            luaCode,
            List.empty,
            List(
              nowInEpochSec,
              timeWindowInSec,
              lastResetTimeKey,
              counterKey,
              maxTokens
            )
          )
        )
        .map(_.get)
        .handleErrorWith { case e: Exception =>
          F.raiseError(RedisConnectionError(e.getMessage))
        }
      _ <- F.unlessA(remainingTokens >= 0)(
        F.raiseError[Unit](RateLimitExceeded)
      )
      _ <- F.blocking(redisClient.set(lastResetTimeKey, nowInEpochSec))
      a <- fa
    } yield a
  }
}
