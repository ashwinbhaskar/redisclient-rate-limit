package com.redis.common

case class Config(
    host: String,
    port: Int,
    maxTokens: Long,
    timeWindowInSec: Long
)
