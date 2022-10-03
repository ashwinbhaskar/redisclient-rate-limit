package com.redis.common

sealed trait RateLimitError extends Exception
case class RedisConnectionError(msg: String) extends RateLimitError
object RateLimitExceeded extends RateLimitError
