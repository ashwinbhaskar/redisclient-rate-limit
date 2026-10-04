package com.redis

package object common {

  private[redis] def connectionError(e: Throwable): RedisConnectionError = {
    val error = RedisConnectionError(e.getMessage)
    error.initCause(e)
    error
  }

  // Used by 4.0.x. Kept for binary compatibility; no longer used.
  @deprecated("No longer used by the library", "4.1.0")
  val lastResetTimeSuffix = ":rate_limit_last_reset"
  @deprecated("No longer used by the library", "4.1.0")
  val counterSuffix = ":rate_limit_counter"
  @deprecated("No longer used by the library", "4.1.0")
  val luaCode =
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
}
