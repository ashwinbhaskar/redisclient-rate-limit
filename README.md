# Redis Rate Limit ![build](https://github.com/ashwinbhaskar/redisclient-rate-limit/actions/workflows/scala.yml/badge.svg)
A cats and zio friendly lightweight library that rate limits calls using a sliding window stored in Redis. Each check is a single `Lua` script, so it is atomic and takes one round trip. The library creates and reuses a pool of [scala-redis](https://github.com/debasishg/scala-redis) connections if you don't provide a redis client.

## Importing
Add the following to your `build.sbt`. Requires Java 11 or later.
```
libraryDependencies += "io.github.ashwinbhaskar" %% "redis-rate-limit-ce" % "4.1.0" // If you use cats effect (3.x)

libraryDependencies += "io.github.ashwinbhaskar" %% "redis-rate-limit-zio" % "4.1.0" // If you use zio (2.x)

```

## How it works

A call for a key is allowed if fewer than `maxTokens` calls for that key were allowed in the last `timeWindowInSec` seconds; otherwise it fails with `RateLimitExceeded` and the wrapped effect doesn't run. The window slides, so no window of `timeWindowInSec` seconds ever contains more than `maxTokens` allowed calls, including around window boundaries.

Each key and limit is stored in Redis as a sorted set named `rate_limit:{<key>}:<maxTokens>:<timeWindowInSec>`, with one entry per allowed call in the current window. The set expires one window after the last allowed call. Memory per key is proportional to `maxTokens`, which suits per-user limits; very large limits (hundreds of thousands of calls per window) use correspondingly more memory.

## Usage

Let's take a look at a scenario where we have to rate limit an API call to 5 calls per user in a time window of 5 seconds.

Passing a `Config` is recommended: the library then uses a connection pool per Redis host and port, which is safe to share across fibers. A scala-redis `RedisClient` is a single connection and is not thread-safe; if you pass one in, the library serialises its own calls on it, but other code using the same client at the same time can still corrupt its replies.

### ZIO

```scala
import zio._
import com.redis.RedisClient //From scala-redis library
import com.redis.common.{Config, RateLimitExceeded, RedisConnectionError} //This library
import com.redis.ratelimit._ //This library

val apiCall: RIO[HttpClient, String] = ???

val userId: String = ???

// Internally used Redis connection pool (recommended)
val redisHost: String = ???
val redisPort: Int = ???
val config = Config(redisHost, redisPort, maxTokens = 5, timeWindowInSec = 5)

val rateLimitedApiCall: RIO[HttpClient, String] = apiCall.withRateLimit(key = userId, config)


//Externally provided Redis Client
val redisClient = new RedisClient("localhost", 6379)

val rateLimitedWithClient: RIO[HttpClient with RedisClient, String] = apiCall.withRateLimit(key = userId, maxTokens = 5, timeWindowInSec = 5)

```

`zio._` also exports a `Config`, so import `com.redis.common.Config` by name as above.

### Cats Effect

```scala
import cats.effect.IO
import com.redis.RedisClient //From scala-redis library
import com.redis.common.{Config, RateLimitExceeded, RedisConnectionError} //This library
import com.redis.ratelimit._ //This library

// Internally used Redis connection pool (recommended)
val redisHost: String = ???
val redisPort: Int = ???
implicit val config: Config = Config(redisHost, redisPort, maxTokens = 5, timeWindowInSec = 5)
val userId: String = ???

val apiCall: IO[String] = ???

val result: IO[String] = rateLimited[IO, String](apiCall, key = userId) //uses a connection pool for the implicit config's host and port

result.handleErrorWith {
    case RateLimitExceeded => ???
    case e: RedisConnectionError => ??? // e.getCause has the underlying exception
    case other => IO.raiseError(other)
}

//Externally provided Redis Client
val host: String = ???
val port: Int = ???
implicit val redisClient: RedisClient = new RedisClient(host, port) //pass your own instance of redis client implicitly

val resultWithClient: IO[String] = rateLimited[IO, String](apiCall, key = userId, maxTokens = 5, timeWindowInSec = 5)
```

## Upgrading

See [CHANGELOG.md](CHANGELOG.md). 4.1.0 changes how limits are stored in Redis and fixes a bug where clients under the limit could be locked out.
