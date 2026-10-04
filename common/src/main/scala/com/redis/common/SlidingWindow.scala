package com.redis.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

/** Outcome of one rate-limit check. */
private[redis] final case class Decision(
    allowed: Boolean,
    remaining: Long,
    retryAfterMs: Long
)

/** Sliding-window log: at most `limit` calls in any window of `window_ms`.
  *
  * Each allowed call is a member of a sorted set scored by its timestamp.
  * Entries older than the window are dropped before counting, so the limit
  * holds for every window rather than for fixed buckets, and steady traffic
  * under the limit is never denied. The key expires one window after the last
  * allowed call.
  *
  * The script is idempotent per member: scala-redis re-sends a command after a
  * dropped connection, and the first attempt may already have run.
  */
private[redis] object SlidingWindow {

  val script: String =
    """-- KEYS[1] = sorted set; ARGV = limit, window_ms, now_ms, member
      |local limit, window, now = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
      |if redis.call('ZSCORE', KEYS[1], ARGV[4]) then
      |  return {1, limit - redis.call('ZCARD', KEYS[1]), 0}
      |end
      |redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
      |local count = redis.call('ZCARD', KEYS[1])
      |if count < limit then
      |  redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
      |  redis.call('PEXPIRE', KEYS[1], window)
      |  return {1, limit - count - 1, 0}
      |end
      |local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
      |local retry = window
      |if #oldest > 0 then
      |  retry = math.max(1, tonumber(oldest[2]) + window - now)
      |end
      |return {0, 0, retry}
      |""".stripMargin

  val sha: String =
    MessageDigest
      .getInstance("SHA-1")
      .digest(script.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** The limit is part of the key so that different limits on the same user key
    * don't share (and corrupt) one window.
    */
  def redisKey(key: String, maxTokens: Long, timeWindowInSec: Long): String =
    s"rate_limit:{$key}:$maxTokens:$timeWindowInSec"

  /** Unique per logical call; must be created once per call, inside the effect.
    */
  def newMember(nowMs: Long): String =
    s"$nowMs-${ThreadLocalRandom.current().nextLong()}"

  def decision(reply: List[Option[Long]]): Decision =
    reply match {
      case List(Some(allowed), Some(remaining), Some(retryAfterMs)) =>
        Decision(allowed == 1L, remaining, retryAfterMs)
      case other =>
        throw new IllegalStateException(
          s"Unexpected reply from rate limit script: $other"
        )
    }
}
