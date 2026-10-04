package com.redis.common

sealed trait RateLimitError extends Exception

case class RedisConnectionError(msg: String) extends RateLimitError {
  override def getMessage: String = msg
}

// A singleton, so it records no stack trace and ignores suppressed exceptions;
// otherwise every addSuppressed (ZIO does this when squashing a Cause) would
// accumulate on this one shared instance.
object RateLimitExceeded
    extends Exception(null, null, false, false)
    with RateLimitError {
  override def getMessage: String = "Rate limit exceeded"
}
