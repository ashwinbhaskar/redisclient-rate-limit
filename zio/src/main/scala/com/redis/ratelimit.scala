package com.redis

import scala.collection.mutable
import java.time.ZonedDateTime
import zio._
import com.redis.common._
import java.util.concurrent.TimeUnit

package object ratelimit {

  private val clients: mutable.Map[Config, RedisClient] =
    mutable.Map.empty

  implicit class RateLimitOps[R, A](val z: RIO[R, A]) {
    def withRateLimit(key: String, config: Config): RIO[R, A] =
      rateLimited(z, key, config)

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
      .attempt(
        clients
          .getOrElseUpdate(config, new RedisClient(config.host, config.port))
      )
      .flatMap(client =>
        rateLimited(z, key, config.maxTokens, config.timeWindowInSec)
          .provideSomeLayer[R](ZLayer.succeed(client))
      )

  private def rateLimited[R, A](
      z: RIO[R, A],
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  ): RIO[RedisClient with R, A] = {
    val lastResetTimeKey = key ++ lastResetTimeSuffix
    val counterKey = key ++ counterSuffix
    for {
      redisClient <- ZIO.service[RedisClient]
      nowInEpochSec <- Clock.currentTime(TimeUnit.SECONDS) // ZIO.succeed(ZonedDateTime.now.toEpochSecond)
      remainingTokens <- ZIO
        .attempt(
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
        .mapError(e => RedisConnectionError(e.getMessage))
      _ <- ZIO.unless(remainingTokens >= 0)(
        ZIO.fail(RateLimitExceeded)
      )
      _ <- ZIO.attempt(redisClient.set(lastResetTimeKey, nowInEpochSec))
      a <- z
    } yield a
  }
}
