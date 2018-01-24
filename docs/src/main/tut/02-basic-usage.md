---
title: Basic Usage
layout: default
---

# Basic Usage

Assuming an HTTP server exists at `localhost:8765`:

```tut:book

// set up a dummy HTTP service on port 8675 that just echoes some request information

import com.twitter.util.Future
import com.twitter.finagle.{Service,Http}
import com.twitter.finagle.http.{Request,Response,Method}
import java.net.InetSocketAddress
val server = Http.serve(new InetSocketAddress(8765), new Service[Request, Response] {
  def apply(request: Request): Future[Response] = Future {
    val rep = Response()
    rep.headerMap.put("X-Foo", "Bar")
    if(request.method != Method.Head)
      rep.contentString = s"${request.method} ${request.uri} :: ${request.contentString}"
    rep
  }
})
```

Create a `Client`, passing the base URL to the REST endpoints:

```tut:book
import java.net.URL
val client = new featherbed.Client(new URL("http://localhost:8765/api/"))
```

*Note:* It is important to put a trailing slash on your URL.  This is because the resource path you'll pass in below
is evaluated as a relative URL to the base URL given.  Without a trailing slash, the `api` directory above would be
lost when the relative URL is resolved.

Now you can make some requests:

```tut:book
import com.twitter.util.Await

val request = client.get("test/resource").toService[Response]

Await.result {  
  request() map {
    response => response.contentString
  }
}
```

The result of making a request is a `Future` that must be mapped (or `flatMap`ped) over.  Normally, you wouldn't use
`Await` in real code, because you don't want to block Finagle's event loop.  But here, it demonstrates the result of
mapping the `Future[Response]` to a `Future[String]` which will contain the response's content.

Besides `get`, the other REST verbs are also available; the process of specifying a request has a fluent API which
can be used to fine-tune the request that will be sent.

Here's an example of using a `POST` request to submit a web form-style request:

```tut:book
import java.nio.charset.StandardCharsets._

val request = client.
  post("another/resource").
  withCharset(UTF_8).
  withHeaders("X-Foo" -> "scooby-doo").
  form.toService[Map[String, String], Response]

Await.result {
  request(Map("foo" -> "foz", "bar" -> "baz")) map {
    response => response.contentString
  }
}
```

Here's how you might send a `HEAD` request (note the lack of a type argument to `send()` for a HEAD request):

```tut:book
val request = client.head("head/request").toService

Await.result {
  request().map(_.headerMap)
}
```

A `DELETE` request (using the `send` syntax rather than `toService`):

```tut:book
Await.result {
  client.delete("delete/request").send[Response]() map {
    response => response.statusCode
  }
}
```

And a `PUT` request - notice how a `Content-Type` is provided to the `toService` method, and we use `Buf` in this
example to provide the content of the request.

```tut:book
import com.twitter.io.Buf

val request = client.
  put("put/request").
  toService[Buf, Response]("text/plain")
  
Await.result {
  request(Buf.Utf8("Hello world!")).map {
    response => response.statusCode
  }
}
```

You can also provide content to a `POST` request in the same fashion:

```tut:book
val request = client.
  post("another/post/request").
  toService[Buf, Response]("text/plain")

Await.result {
  request(Buf.Utf8("Hello world!")).map {
    response => response.contentString
  }
}
```

```tut:invisible
Await.result(server.close())
```

Using a `Buf` for content enables specifying low-level content, but you're usually going to want to use a more
high-level interface to interact with a REST service. To see how that works, read about
[Content types and Encoders](03-content-types-and-encoders.html).
