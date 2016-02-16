# Basic Usage

Assuming an HTTP server exists at `localhost:8765`:

```scala
scala> import com.twitter.util.Future
import com.twitter.util.Future

scala> import com.twitter.finagle.{Service,Http}
import com.twitter.finagle.{Service, Http}

scala> import com.twitter.finagle.http.{Request,Response}
import com.twitter.finagle.http.{Request, Response}

scala> import java.net.InetSocketAddress
import java.net.InetSocketAddress

scala> val server = Http.serve(new InetSocketAddress(8765), new Service[Request, Response] {
     |   def apply(request: Request): Future[Response] = Future {
     |     val rep = Response()
     |     rep.contentString = s"${request.method} ${request.uri} :: ${request.contentString}"
     |     rep
     |   }
     | })
server: com.twitter.finagle.ListeningServer = Group(/0:0:0:0:0:0:0:0:8765)
```

Create a `Client`, passing the base URL to the REST endpoints:

```scala
scala> import java.net.URL
import java.net.URL

scala> val client = new featherbed.Client(new URL("http://localhost:8765/api/"))
client: featherbed.Client = featherbed.Client@2ae26f61
```
*Note:* It is important to put a trailing slash on your URL.  This is because the resource path you'll pass in below
is evaluated as a relative URL to the base URL given.  Without a trailing slash, the `api` directory above would be
lost when the relative URL is resolved.

Now you can make some requests:

```scala
scala> import com.twitter.util.Await
import com.twitter.util.Await

scala> Await.result {
     |   val request = client.get("test/resource")
     |   request map {
     |     response => response.contentString
     |   }
     | }
res0: String = "GET /api/test/resource :: "
```

The result of making a request is a `Future` that must be mapped (or `flatMap`ped) over.  Normally, you wouldn't use
`Await` in real code, because you don't want to block Finagle's event loop.  But here, it demonstrates the result of
mapping the `Future[Response]` to a `Future[String]` which will contain the response's content.

Besides `get`, the other REST verbs are also available; the process of specifying a request has a fluent API which
can be used to fine-tune the request that will be sent.

```scala
scala> import java.nio.charset.StandardCharsets._
import java.nio.charset.StandardCharsets._

scala> Await.result {
     |   client
     |     .post("another/resource")
     |     .withForm(
     |       "foo" -> "foz",
     |       "bar" -> "baz")
     |     .withCharset(UTF_8)
     |     .withHeaders("X-Foo" -> "scooby-doo")
     |     .map {
     |       response => response.contentString
     |     }
     | }
res1: String = POST /api/another/resource :: foo=foz&bar=baz
```

```scala
scala> Await.result {
     |   client.head("head/request").map(_.headerMap)
     | }
res2: com.twitter.finagle.http.HeaderMap = Map(Content-Length -> 26)
```

```scala
scala> import com.twitter.io.Buf
import com.twitter.io.Buf

scala> Await.result {
     |   client.put("put/request").withContent(Buf.Utf8("Hello world!"), "text/plain") map {
     |     response => response.statusCode
     |   }
     | }
res3: Int = 200
```

```scala
scala> Await.result {
     |   client.delete("delete/request") map {
     |     response => response.statusCode
     |   }
     | }
res4: Int = 200
```


Next, read about [Content types and Encoders]("02-content-types-and-encoders.md")
