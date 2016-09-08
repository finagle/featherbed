---
title: Error Handling
layout: default
---

# Error Handling

In the [previous section](04-response-decoding-and-validation.html), we saw how we can decode responses automatically
to an algebraic data type using `send[T]`. We also saw how the decoded response is wrapped in a `Validated` data type,
to capture any failures in decoding the response.

Requests can fail for other reasons besides malformed responses, though. What happens if the server returns an HTTP
error, like `404 Not Found` or `401 Unauthorized`? Let's set up another dummy server that returns errors, so we can
explore the various ways of handling them:

```tut:book
import com.twitter.util.{Future,Await}
import com.twitter.finagle.{Service,Http}
import com.twitter.finagle.http.{Request,Response,Status,Version}
import java.net.{URL, InetSocketAddress}
import featherbed.request.ErrorResponse
import featherbed.circe._
import io.circe.generic.auto._

val server = Http.serve(new InetSocketAddress(8768), new Service[Request, Response] {
  def response(status: Status, content: String) = {
    val rep = Response(Version.Http11, status)
    rep.contentString = content
    rep.contentType = "application/json"
    Future.value(rep)
  }
  
  def apply(request: Request): Future[Response] = request.uri match {
    case "/api/success" => response(Status.Ok, """{"foo": "bar"}""")
    case "/api/not/found" => response(
        Status.NotFound,
        """{"error": "The thing couldn't be found"}"""
      )
    case "/api/unauthorized" => response(
        Status.Unauthorized,
        """{"error": "Not authorized to access the thing"}"""
      )
    case "/api/server/error" => response(
        Status.InternalServerError,
        """{"error": "Something went terribly wrong"}"""
      )
  }
})

// the type of the successful response
case class Foo(foo: String)

// the client
val client = new featherbed.Client(new URL("http://localhost:8768/api/"))
```

When using the `send[T]` method, the resulting `Future` will *fail* if the server returns an HTTP error. This means that
in order to handle an error, you must handle it at the `Future` level using the `Future` API:

```tut:book:nofail
val req = client.get("not/found").accept("application/json")

Await.result {
  req.send[Foo]().handle {
    case ErrorResponse(request, response) =>
      throw new Exception(s"Error response $response to request $request")
  }
}
```

This isn't a very useful error handler, but it demonstrates how errors can be intercepted at the `Future` level. The
`handle` or `rescue` methods of `Future` can be used to recover from the failure. See their API docs for more
information. The exception that's returned in a `Future` which failed due to a server error response is of type
`ErrorResponse`, which contains the request and response.

Often, the a REST API will be set up to return some meaningful representation of errors in the same content type as its
responses. In the example above, our dummy server is set up to return JSON errors in a well-defined structure. To
capture this, we can use the `send[Error, Success]` method instead of `send[T]`:

```tut:book
// ADT for errors
case class Error(error: String)

val req = client.get("not/found").accept("application/json")

Await.result(req.send[Error, Foo])
```

Instead of an exception, we're capturing the server errors in an `Xor[Error, Foo]`. `Xor` is another data type from
cats, which captures failures in a similar way to `Validated`. This is a typical pattern in Scala functional programming
for dealing with operations which may fail. The benefit is that the well-defined error type is also automatically
decoded for us. However, if the error can't be decoded, this will still result in a failed `Future`, which fails on the
decoding rather than the server error.

Next, read about [Building REST Clients](06-building-rest-clients.html)