# Redis Rate Limit ![build](https://github.com/ashwinbhaskar/redisclient-rate-limit/actions/workflows/scala.yml/badge.svg)
A cats and zio friendly lightweight library that rate limits using token bucket algorithm. The library executes redis commands as `Lua` code which makes the operations atomic. The library internally creates and memoizes a redis client using [scala-redis](https://github.com/debasishg/scala-redis) if redis client is not provided.

## Importing
Add the following to your `build.sbt`
```
libraryDependencies =+ "io.github.ashwinbhaskar" %% "redis-rate-limit-ce" % "4.0.0" // If you use cats effect (3.x)

libraryDependencies =+ "io.github.ashwinbhaskar" %% "redis-rate-limit-zio" % "4.0.0" // If you use zio (2.x)

```

## Usage

Let's take a look at a scenario where we have to rate limit an API call to 5 calls per user in a time window of 5 seconds.
### ZIO

```scala
import zio._
import com.redis.RedisClient //From scala-redis library
import com.redis.ratelimit._ //This library

val apiCall: RIO[HttpClient, String] = ???

val userId: String = ???

//Externally provided Redis Client
val redisClient = new RedisClient("localhost", 6379)

val rateLimitedApiCall: RIO[HttpClient with RedisClient, String] = apiCall.withRateLimit(key = userId, maxTokens = 5, timeWindowInSec = 5)


// Internally used Redis Client
val redisHost: String = ???
val redisPort: Int = ???
val config = Config(redisHost, redisPort, maxTokens = 5, timeWindowInSec = 5)

val rateLimitedApiCall: RIO[HttpClient, String] = apiCall.withRateLimit(key = userId, config)

```

### Cats Effect

```scala
import cats.effect.IO
import com.redis.RedisClient //From scala-redis library
import com.redis.ratelimit._ //This library


//Externally provided Redis Client
val host: String = ???
val port: Int = ???
implicit val redisClient: RedisClient = new RedisClient(host, port) //pass your own instance of redis client implicitely
val userId: String = ???

val apiCall: IO[String] = ???

val result: IO[String] = rateLimited[IO, String](apiCall, key = userId, maxTokens = 5, timeWindowInSec = 5)

//Internally Constructed Redis Client
val redisHost: String = ???
val redisPort: Int = ???
implicit val config = Config(redisHost, redisPort, maxTokens = 5, timeWindowInSec = 5)
val userId: String = ???

val apiCall: IO[String] = ???

val result: IO[String] = rateLimited[IO, String](apiCall, key = userId) //internally creates a redis client for the implicit config and keeps it in memory

result.handleErrorWith {
    case RedisConnectionError(msg) => ??? 
    case RateLimitExceeded => ???
}
```

