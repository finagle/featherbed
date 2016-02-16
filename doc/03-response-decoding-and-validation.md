# Response Decoding and Validation

In the previous section, we looked at how we can import `Encoder` instances to make it so we
can treat case classes as HTTP request content.  We've also seen how a request specification
can be `map`ped or `flatMap`ped over in order to send the request and create a `Future` representing
the response.

Once you have a `Future[Response]`, what then?  Of course, the `Response` will be more useful if
it's transformed into some typed data.  In a similar fashion to `Encoder`, we can make use of
an implicit `Decoder` to accomplish this.

First, the setup.  This time, our dummy server is going to be a little more complicated: for
requests to `/foo/good`, it will return the same JSON that we give it with an `application/json`
Content-Type.  For requests to `/foo/bad`, it will return some invalid JSON with an `application/json`
Content-Type.  For requests to `/foo/awful`, it will return some junk with a completely
unexpected Content-Type.

```scala
scala> import com.twitter.util.{Future,Await}
import com.twitter.util.{Future, Await}

scala> import com.twitter.finagle.{Service,Http}
import com.twitter.finagle.{Service, Http}

scala> import com.twitter.finagle.http.{Request,Response}
import com.twitter.finagle.http.{Request, Response}

scala> import java.net.InetSocketAddress
import java.net.InetSocketAddress

scala> val server = Http.serve(new InetSocketAddress(8767), new Service[Request, Response] {
     |   def apply(request: Request): Future[Response] = request.uri match {
     |     case "/api/foo/good" => Future {
     |         val rep = Response()
     |         rep.contentString = request.contentString
     |         rep.setContentTypeJson()
     |         rep
     |       }
     |     case "/api/foo/bad" => Future {
     |       val rep = Response()
     |       rep.contentString = "This text is not valid JSON!"
     |       rep.setContentTypeJson()
     |       rep
     |     }
     |     case "/api/foo/awful" => Future {
     |       val rep = Response()
     |       rep.contentString = "This text is not valid anything!"
     |       rep.setContentType("pie/pumpkin", "UTF-8")
     |       rep
     |     }
     |   }
     | })
server: com.twitter.finagle.ListeningServer = Group(/0:0:0:0:0:0:0:0:8767)

scala> import java.net.URL
import java.net.URL

scala> val client = new featherbed.Client(new URL("http://localhost:8767/api/"))
client: featherbed.Client = featherbed.Client@7f83b401
```

To specify that a response should be decoded, use the `send[T]` method to initiate the request:

```scala
scala> import featherbed.circe._
import featherbed.circe._

scala> import io.circe.generic.auto._
import io.circe.generic.auto._

scala> case class Foo(someText: String, someInt: Int)
defined class Foo

scala> Await.result {
     |   val request = client.post("foo/good")
     |     .withContent(Foo("Hello world", 42), "application/json")
     | 
     |   request.send[Foo]()
     | }
<console>:30: error: In order to decode a request to $A, it must be known that a decoder exists to $A from all the content types that you Accept, which is currently shapeless.:+:[String("*/*"),shapeless.CNil].
You may have forgotten to specify Accept types with the `accept[T <: Coproduct]` method, or you may be missing Decoder instances for some content types.

         request.send[Foo]()
                          ^
```

Oops! What happened? Like the error message explains, we can't compile that code because we have
to specify an `Accept` header and ensure that we're able to decode all of the types we specify
into `Foo`.  In scala type land, the `Accept` content types are a `Coproduct` of string literals
which can be specified using shapless's `Coproduct` syntax.  In this case, we only want `application/json`.

```scala
scala> import shapeless.Coproduct
import shapeless.Coproduct

scala> Await.result {
     |   val request = client.post("foo/good")
     |     .withContent(Foo("Hello world", 42), "application/json")
     |     .accept[Coproduct.`"application/json"`.T]
     | 
     |   request.send[Foo]()
     | }
res1: cats.data.Validated[featherbed.InvalidResponse,Foo] = Valid(Foo(Hello world,42))
```

Look at that!  The JSON that came back was automatically decoded into a `Foo`!  But what's that `Valid`
thing around it?  As we're about to see, when you're interacting with a server, you can't be sure that
you'll get what you expect.  The server might send malformed JSON, or might not send JSON at all. To
handle this in an idiomatic way, `send[Foo]()` actually returns a `Future[Validated[InvalidResponse, Foo]]`.
What this means is that the result of the `Future` will be a `cats.data.Validated`, which will be either
`Valid(Foo(...))` or `Invalid(InvalidResponse)`.  The `InvalidResponse` contains a message about why the
response was invalid, as well as the `Response` itself (so you can process it further if you like).
Let's see what that looks like:

```scala
scala> Await.result {
     |   val request = client.post("foo/bad")
     |     .withContent(Foo("Hello world", 42), "application/json")
     |     .accept[Coproduct.`"application/json"`.T]
     | 
     |   request.send[Foo]()
     | }
res2: cats.data.Validated[featherbed.InvalidResponse,Foo] = Invalid(InvalidResponse(Response("HTTP/1.1 Status(200)"),expected json value got T (line 1, column 1)))

scala> Await.result {
     |   val request = client.post("foo/awful")
     |     .withContent(Foo("Hello world", 42), "application/json")
     |     .accept[Coproduct.`"application/json"`.T]
     | 
     |   request.send[Foo]()
     | }
res3: cats.data.Validated[featherbed.InvalidResponse,Foo] = Invalid(InvalidResponse(Response("HTTP/1.1 Status(200)"),No decoder was found for pie/pumpkin))
```

As you can see, these different failure scenarios provide different messages about what failure occured,
and give the original `Response`.  In the first case, we get back Circe's parsing error.  In the second
case, we get a message that the content type wasn't expected and therefore there isn't a decoder for it.
This helps us deal with inevitable runtime failures resulting from external systems.

Next, read about [Building REST Clients](04-building-rest-clients.md)
