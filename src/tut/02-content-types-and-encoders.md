---
title: Content Types and Encoders
layout: default
---

# Content Types and Encoders

In many cases, you'll have to send content to REST services.  Most of the time, you aren't going to want to pass a `Buf`
(a collection of bytes) to a REST service.  Rather, you would probably prefer to pass some representation of a request,
and featherbed is equipped to handle that.  As long as there is a `featherbed.content.Encoder[T, CT]` in implicit
scope, featherbed can take care of marshalling a value of type `T` into a representation in MIME type `CT`.

If that sounds confusing, don't worry.  Featherbed provides modules for dealing with common content types.  If you want
to implement a content type for yourself, you can read about it later on (it's not that hard, as long as you understand
typeclasses and singleton literals.)

Let's take a look at how we might interact with a service that accepts JSON payloads.  We'll use the provided module
`featherbed-circe`, which provides automatic JSON encoding and decoding using the excellent Circe library
from the Typelevel stack.

First, the same setup as before:

```tut:book
import com.twitter.util.{Future,Await}
import com.twitter.finagle.{Service,Http}
import com.twitter.finagle.http.{Request,Response}
import java.net.InetSocketAddress

val server = Http.serve(new InetSocketAddress(8766), new Service[Request, Response] {
  def apply(request: Request): Future[Response] = Future {
    val rep = Response()
    rep.contentString = s"${request.method} ${request.uri} :: ${request.contentString}"
    rep
  }
})

import java.net.URL
val client = new featherbed.Client(new URL("http://localhost:8766/api/"))
```

Importing `featherbed.circe._` brings an implicit derivation from `io.circe.Encoder[A]` to
`featherbed.content.Encoder[A, "application/json"]`.  As long as there is a Circe `Encoder[A]`
in implicit scope, we will be able to pass `A` directly as content in featherbed requests:

```tut:book
import io.circe.generic.auto._
import featherbed.circe._

case class Foo(someText : String, someInt : Int)
val req = client.post("foo/bar").withContent(Foo("Hello world!", 42), "application/json")
val result = Await.result {
   req map {
    response => response.contentString
  }
}
```

Here we used `io.circe.generic.auto._` to automatically derive a JSON codec for `Foo` - but if you have need to encode
a particular data type into JSON in a certain way, you can also specify an implicit Circe `Encoder` value in the data
type's companion object.  See the Circe documentation for more details about JSON encoding and decoding.

### A Note About Evaluation

You may have noticed that above we created a value called `req`, which held the result of specifying the request
type and its parameters.  We later `map`ped over that value to specify a transformation of the response.

It's important to note that the request itself **is not performed** until the call to `map` or `flatMap`. Until
that call is made, you will have an instance of some kind of request, but you will not have a `Future` representing
the response.  That is, the request itself is *lazy*.  The reason this is important to note is that `req` itself can
actually be used to make the same request again.  If another call is made to `req.map` or `req.flatMap`, a new
request of the same parameters will be initiated and a new `Future` will be returned.  This can be a useful and
powerful thing, but it can also bite you if you're unaware.

For more information about lazy tasks, take a look at scalaz's `Task` or cats's `Eval`.  Again, this is important to
note, and is different than what people are used to with Finagle's `Future` (which is not lazy).

### A Note About Types

You may have also noticed that we specified a content type string, `"application/json"`.  From this, the request knew
to encode the `Foo` object as JSON.  It may not seem obvious, but this decision was actually made *at compile time*.
When `featherbed.circe._` was imported, we gained a typelevel specification that requests being made with
"application/json" can be encoded as long as the payload's type has an available Circe `Encoder`.  This is accomplished
by treating `"application/json"` as a value of *type* `"application/string"` rather than a value of type `String`. For
more information about singleton literals and their (amazing) implications, check out some of the projects in
Typelevel Scala (particularly Shapeless).  Scala can do some amazing things (but it does need a little help once in a while.)

Next, read about [Response Decoding and Validation](03-response-decoding-and-validation.html)
