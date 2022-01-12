package com.redis

import java.time.ZonedDateTime
import cats.effect.kernel.Sync
import cats.implicits._
import com.redis.RedisClient

package object ratelimit {
  sealed trait RedisRateLimitError extends Exception
  case class RedisConnectionError(msg: String) extends RedisRateLimitError
  object RateLimitExceeded extends RedisRateLimitError
  private val lastResetTimeSuffix = ":rate_limit_last_reset"
  private val counterSuffix = ":rate_limit_counter"
  private val luaCode =
    """
      -- ARGUMENTS IN ORDER - CurrentTimeStamp, WindowLength(in sec), LastUpdatedTimeStampKey, CounterKey, ThresholdValue
      local current_ts_string = ARGV[1]
      local current_ts = tonumber(current_ts_string)
      local window = tonumber(ARGV[2])

      local last_ts = tonumber(redis.call("GET", ARGV[3]))

      if (last_ts == nil or last_ts == '')
      then
          last_ts = 0
      end

      if((current_ts - last_ts) > window)
      then
          redis.call("SET", ARGV[4], ARGV[5])
          redis.call("SET", ARGV[3], current_ts_string)
      end

      return redis.call("DECR", ARGV[4])
    """

  def rateLimited[F[_], A](
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long
  )(fa: F[A])(implicit redisClient: RedisClient, F: Sync[F]): F[A] = {
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
      _ <- F.unlessA(remainingTokens > 0)(F.raiseError[Unit](RateLimitExceeded))
      _ <- F.blocking(redisClient.set(lastResetTimeKey, nowInEpochSec))
      a <- fa
    } yield a
  }
}
