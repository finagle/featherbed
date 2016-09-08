---
title: Basic Usage
layout: default
---

# Basic Usage

Assuming an HTTP server exists at `localhost:8765`:

```scala
// set up a dummy HTTP service on port 8675 that just echoes some request information

import com.twitter.util.Future
// import com.twitter.util.Future

import com.twitter.finagle.{Service,Http}
// import com.twitter.finagle.{Service, Http}

import com.twitter.finagle.http.{Request,Response,Method}
// import com.twitter.finagle.http.{Request, Response, Method}

import java.net.InetSocketAddress
// import java.net.InetSocketAddress

val server = Http.serve(new InetSocketAddress(8765), new Service[Request, Response] {
  def apply(request: Request): Future[Response] = Future {
    val rep = Response()
    rep.headerMap.put("X-Foo", "Bar")
    if(request.method != Method.Head)
      rep.contentString = s"${request.method} ${request.uri} :: ${request.contentString}"
    rep
  }
})
// server: com.twitter.finagle.ListeningServer = Group(/0:0:0:0:0:0:0:0:8765)
```

Create a `Client`, passing the base URL to the REST endpoints:

```scala
import java.net.URL
// import java.net.URL

val client = new featherbed.Client(new URL("http://localhost:8765/api/"))
// client: featherbed.Client = featherbed.Client@646ec864
```
*Note:* It is important to put a trailing slash on your URL.  This is because the resource path you'll pass in below
is evaluated as a relative URL to the base URL given.  Without a trailing slash, the `api` directory above would be
lost when the relative URL is resolved.

Now you can make some requests:

```scala
import com.twitter.util.Await
// import com.twitter.util.Await

Await.result {
  val request = client.get("test/resource").send[Response]()
  request map {
    response => response.contentString
  }
}
// res2: String = "GET /api/test/resource :: "
```

The result of making a request is a `Future` that must be mapped (or `flatMap`ped) over.  Normally, you wouldn't use
`Await` in real code, because you don't want to block Finagle's event loop.  But here, it demonstrates the result of
mapping the `Future[Response]` to a `Future[String]` which will contain the response's content.

Besides `get`, the other REST verbs are also available; the process of specifying a request has a fluent API which
can be used to fine-tune the request that will be sent.

Here's an example of using a `POST` request to submit a web form-style request:

```scala
import java.nio.charset.StandardCharsets._
// import java.nio.charset.StandardCharsets._

Await.result {
  client
    .post("another/resource")
    .withParams(
      "foo" -> "foz",
      "bar" -> "baz")
    .withCharset(UTF_8)
    .withHeaders("X-Foo" -> "scooby-doo")
    .send[Response]()
    .map {
      response => response.contentString
    }
}
// res3: String = POST /api/another/resource :: foo=foz&bar=baz
```

Here's how you might send a `HEAD` request (note the lack of a type argument to `send()` for a HEAD request):

```scala
Await.result {
  client.head("head/request").send().map(_.headerMap)
}
// res4: com.twitter.finagle.http.HeaderMap = Map(X-Foo -> Bar, Content-Length -> 0)
```

A `DELETE` request:

```scala
Await.result {
  client.delete("delete/request").send[Response]() map {
    response => response.statusCode
  }
}
// res5: Int = 200
```

And a `PUT` request - notice how content can be provided to a `PUT` request by giving it a `Buf` buffer and a MIME type
to serve as the `Content-Type`:

```scala
import com.twitter.io.Buf
// import com.twitter.io.Buf

Await.result {
  client.put("put/request")
    .withContent(Buf.Utf8("Hello world!"), "text/plain")
    .send[Response]()
    .map {
      response => response.statusCode
    }
}
// res6: Int = 200
```

You can also provide content to a `POST` request in the same fashion:

```scala
import com.twitter.io.Buf
// import com.twitter.io.Buf

Await.result {
  client.post("another/post/request")
    .withContent(Buf.Utf8("Hello world!"), "text/plain")
    .send[Response]()
    .map {
      response => response.contentString
    }
}
// res7: String = POST /api/another/post/request :: Hello world!
```




Using a `Buf` for content enables specifying low-level content, but you're usually going to want to use a more
high-level interface to interact with a REST service. To see how that works, read about
[Content types and Encoders](03-content-types-and-encoders.html).
