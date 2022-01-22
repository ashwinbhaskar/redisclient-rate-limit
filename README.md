# Redis Rate Limit ![build](https://github.com/ashwinbhaskar/redisclient-rate-limit/actions/workflows/scala.yml/badge.svg)
A cats friendly and lightweight library that does rate limiting using token bucket algorithm. The library executes the redis commands as `Lua` code which makes the operations atomic. This execution happens in a blocking effect (`Sync[F].blocking`).  The library internally creates and memoizes a redis client using [scala-redis](https://github.com/debasishg/scala-redis) when redis client is not provided.

## Importing
Add the following to your `build.sbt`
```
libraryDependencies =+ "io.github.ashwinbhaskar" %% "redis-rate-limit" % "2.0.1"
```

## Usage

```scala
import cats.effect.IO
import com.redis.RedisClient //From scala-redis library
import com.redis.ratelimit._ //This library

//Example Scenario: Don't allow more than 5 requests per user in any 5 second window

//METHOD 1
val host: String = ???
val port: Int = ???
implicit val redisClient: RedisClient = new RedisClient(host, port) //pass your own instance of redis client implicitely
val userId: String = ???

val apiCall: IO[String] = ???

val result: IO[String] = rateLimited[IO, String](apiCall, key = userId, maxTokens = 5, timeWindowInSec = 5)

//METHOD 2
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

