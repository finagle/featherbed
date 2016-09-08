---
title: Response Decoding and Validation
layout: default
---

# Response Decoding and Validation

In the previous section, we looked at how we can import `Encoder` instances to make it so we
can treat case classes as HTTP request content.  We've also seen how a request specification
can be sent using `send[Response]` in order to send the request and create a `Future` representing
the response.

Once you have a `Future[Response]`, what then?  Of course, the `Response` will be more useful if
it's transformed into some typed data.  In a similar fashion to `Encoder`, we can make use of
an implicit `Decoder` to accomplish this.

First, the setup.  This time, our dummy server is going to be a little more complicated: for
requests to `/foo/good`, it will return the same JSON that we give it with an `application/json`
Content-Type.  For requests to `/foo/bad`, it will return some invalid JSON with an `application/json`
Content-Type.  For requests to `/foo/awful`, it will return some junk with a completely
unexpected Content-Type.

```tut:book
import com.twitter.util.{Future,Await}
import com.twitter.finagle.{Service,Http}
import com.twitter.finagle.http.{Request,Response}
import java.net.InetSocketAddress

val server = Http.serve(new InetSocketAddress(8767), new Service[Request, Response] {
  def apply(request: Request): Future[Response] = request.uri match {
    case "/api/foo/good" => Future {
        val rep = Response()
        rep.contentString = request.contentString
        rep.setContentTypeJson()
        rep
      }
    case "/api/foo/bad" => Future {
      val rep = Response()
      rep.contentString = "This text is not valid JSON!"
      rep.setContentTypeJson()
      rep
    }
    case "/api/foo/awful" => Future {
      val rep = Response()
      rep.contentString = "This text is not valid anything!"
      rep.setContentType("pie/pumpkin", "UTF-8")
      rep
    }
  }
})

import java.net.URL
val client = new featherbed.Client(new URL("http://localhost:8767/api/"))
```

To specify that a response should be decoded, use the `send[T]` method to initiate the request:

```tut:book:nofail
import featherbed.circe._
import io.circe.generic.auto._

case class Foo(someText: String, someInt: Int)

Await.result {
  val request = client.post("foo/good").withContent(Foo("Hello world", 42), "application/json")
  request.send[Foo]()
}
```

Oops! What happened? Like the error message explains, we can't compile that code because we have
to specify an `Accept` header and ensure that we're able to decode all of the types we specify
into `Foo`.  In scala type land, the `Accept` content types are a `Coproduct` of string literals
which can be specified using shapless's `Coproduct` syntax.  In this case, we only want `application/json`.

```tut:book
import shapeless.Coproduct

// Specifies only "application/json" as an acceptable response type
val example1 = client.post(
    "foo/good"
  ).withContent(
    Foo("Hello world", 42), "application/json"
  ).accept[Coproduct.`"application/json"`.T]

// Specifies that both "application/json" and "text/xml" are acceptable
// Note that Featherbed doesn't currently ship with an XML decoder; it's just for sake of example.
val example2 = client.post(
    "foo/good"
  ).withContent(
    Foo("Hello world", 42), "application/json"
  ).accept[Coproduct.`"application/json", "text/xml"`.T]

```

That ``Coproduct.`"a", "b"`.T `` syntax is specifying a *type* that encompasses the possible response MIME types that
the request will handle. If you think the syntax is a little bit ugly, you're right! There's an alternative syntax:

```tut:book
val example3 = client.post(
    "foo/good"
  ).withContent(
    Foo("Hello world", 42), "application/json"
  ).accept("application/json", "text/xml")
```

This uses a small macro to lift those `String` arguments into a `Coproduct` type, which looks a lot nicer and more
readable. However, Scala sometimes has trouble inferring that type when subsequent methods are called on the request,
so make sure you call `accept` last when using that syntax.

Let's make an actual request, again using only `"application/json"` (since we have a decoder for that from circe):

```tut:book
import shapeless.Coproduct

Await.result {
  val request = client.post("foo/good")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]()
}
```

Look at that!  The JSON that came back was automatically decoded into a `Foo`!  But what's that `Valid`
thing around it?  As we're about to see, when you're interacting with a server, you can't be sure that
you'll get what you expect.  The server might send malformed JSON, or might not send JSON at all. To
handle this in an idiomatic way, the `Future` returned by `send[K]` will fail with `InvalidResponse` if
the response can't be decoded.  The `InvalidResponse` contains a message about why the response was invalid, 
as well as the `Response` itself (so you can process it further if you like).

Let's see what that looks like:

```tut:book:nofail
Await.result {
  val request = client.post("foo/bad")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]()
}
```

Here, since we didn't handle the `InvalidResponse`, awaiting the future resulted in an exception being thrown. Instead,
you can `handle` the failed future and recover in some way. A typical pattern is to capture the error in something like
an `Xor` (in cats) or an `Either` (in Scala's standard library):

```tut:book
import cats.data.Xor
import featherbed.request.InvalidResponse

Await.result {
  val request = client.post("foo/bad")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]().map(Xor.right).handle {
    case err @ InvalidResponse(rep, reason) => Xor.left(err)
  }
}
```

This example maps the `Future`'s successful result into an `Xor.Right`, and the `InvalidResponse` case into `Xor.Left`,
which represents the failure. The `Xor` can be handled further by the application.

Alternatively, you might want to use some default `Foo` in the event that the response can't be decoded:

```tut:book
Await.result {
  val request = client.post("foo/bad")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]().map(Xor.right).handle {
    case InvalidResponse(rep, reason) =>
      println(s"ERROR: response decoding failed: $reason")
      Foo("Default", 0)
  }
}
```

Similarly, if the response's content-type isn't one of the accepted MIME types, a different `InvalidResponse` is given:

```tut:book:nofail
Await.result {
  val request = client.post("foo/awful")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]()
}
```

```tut:invisible
Await.result(server.close())
```

As you can see, these different failure scenarios provide different messages about what failure occured,
and give the original `Response`.  In the first case, we get back Circe's parsing error.  In the second
case, we get a message that the content type wasn't expected and therefore there isn't a decoder for it.
This helps us deal with inevitable runtime failures resulting from external systems.

Next, read about [Error Handling](05-error-handling.html)
