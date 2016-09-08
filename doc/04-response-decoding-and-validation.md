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

```scala
import com.twitter.util.{Future,Await}
// import com.twitter.util.{Future, Await}

import com.twitter.finagle.{Service,Http}
// import com.twitter.finagle.{Service, Http}

import com.twitter.finagle.http.{Request,Response}
// import com.twitter.finagle.http.{Request, Response}

import java.net.InetSocketAddress
// import java.net.InetSocketAddress

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
// server: com.twitter.finagle.ListeningServer = Group(/0:0:0:0:0:0:0:0:8767)

import java.net.URL
// import java.net.URL

val client = new featherbed.Client(new URL("http://localhost:8767/api/"))
// client: featherbed.Client = featherbed.Client@1b74c1fb
```

To specify that a response should be decoded, use the `send[T]` method to initiate the request:

```scala
import featherbed.circe._
// import featherbed.circe._

import io.circe.generic.auto._
// import io.circe.generic.auto._

case class Foo(someText: String, someInt: Int)
// defined class Foo

Await.result {
  val request = client.post("foo/good").withContent(Foo("Hello world", 42), "application/json")
  request.send[Foo]()
}
// <console>:29: error: In order to decode a request to Foo, it must be known that a decoder exists to Foo from
// all the content types that you Accept, which is currently shapeless.:+:[String("*/*"),shapeless.CNil].
// You may have forgotten to specify Accept types with the `accept(..)` method,
// or you may be missing Decoder instances for some content types.
// 
//          request.send[Foo]()
//                           ^
```

Oops! What happened? Like the error message explains, we can't compile that code because we have
to specify an `Accept` header and ensure that we're able to decode all of the types we specify
into `Foo`.  In scala type land, the `Accept` content types are a `Coproduct` of string literals
which can be specified using shapless's `Coproduct` syntax.  In this case, we only want `application/json`.

```scala
import shapeless.Coproduct
// import shapeless.Coproduct

// Specifies only "application/json" as an acceptable response type
val example1 = client.post(
    "foo/good"
  ).withContent(
    Foo("Hello world", 42), "application/json"
  ).accept[Coproduct.`"application/json"`.T]
// example1: client.PostRequest[Foo,String("application/json"),shapeless.:+:[String("application/json"),shapeless.CNil]] = PostRequest(http://localhost:8767/api/foo/good,Foo(Hello world,42),List(),UTF-8)

// Specifies that both "application/json" and "text/xml" are acceptable
// Note that Featherbed doesn't currently ship with an XML decoder; it's just for sake of example.
val example2 = client.post(
    "foo/good"
  ).withContent(
    Foo("Hello world", 42), "application/json"
  ).accept[Coproduct.`"application/json", "text/xml"`.T]
// example2: client.PostRequest[Foo,String("application/json"),shapeless.:+:[String("application/json"),shapeless.:+:[String("text/xml"),shapeless.CNil]]] = PostRequest(http://localhost:8767/api/foo/good,Foo(Hello world,42),List(),UTF-8)
```

That ``Coproduct.`"a", "b"`.T `` syntax is specifying a *type* that encompasses the possible response MIME types that
the request will handle. If you think the syntax is a little bit ugly, you're right! There's an alternative syntax:

```scala
val example3 = client.post(
    "foo/good"
  ).withContent(
    Foo("Hello world", 42), "application/json"
  ).accept("application/json", "text/xml")
// example3: client.PostRequest[Foo,String("application/json"),shapeless.:+:[String("application/json"),shapeless.:+:[String("text/xml"),shapeless.CNil]]] = PostRequest(http://localhost:8767/api/foo/good,Foo(Hello world,42),List(),UTF-8)
```

This uses a small macro to lift those `String` arguments into a `Coproduct` type, which looks a lot nicer and more
readable. However, Scala sometimes has trouble inferring that type when subsequent methods are called on the request,
so make sure you call `accept` last when using that syntax.

Let's make an actual request, again using only `"application/json"` (since we have a decoder for that from circe):

```scala
import shapeless.Coproduct
// import shapeless.Coproduct

Await.result {
  val request = client.post("foo/good")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]()
}
// res4: Foo = Foo(Hello world,42)
```

Look at that!  The JSON that came back was automatically decoded into a `Foo`!  But what's that `Valid`
thing around it?  As we're about to see, when you're interacting with a server, you can't be sure that
you'll get what you expect.  The server might send malformed JSON, or might not send JSON at all. To
handle this in an idiomatic way, the `Future` returned by `send[K]` will fail with `InvalidResponse` if
the response can't be decoded.  The `InvalidResponse` contains a message about why the response was invalid, 
as well as the `Response` itself (so you can process it further if you like).

Let's see what that looks like:

```scala
Await.result {
  val request = client.post("foo/bad")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]()
}
// featherbed.request.InvalidResponse
//   at featherbed.request.RequestTypes$RequestSyntax$$anonfun$sendRequest$1$$anonfun$apply$12.apply(RequestSyntax.scala:161)
//   at featherbed.request.RequestTypes$RequestSyntax$$anonfun$sendRequest$1$$anonfun$apply$12.apply(RequestSyntax.scala:161)
//   at scala.Function1$$anonfun$andThen$1.apply(Function1.scala:52)
//   at cats.data.Validated.fold(Validated.scala:13)
//   at cats.data.Validated.bimap(Validated.scala:94)
//   at cats.data.Validated.leftMap(Validated.scala:144)
//   at featherbed.request.RequestTypes$RequestSyntax$$anonfun$sendRequest$1.apply(RequestSyntax.scala:161)
//   at featherbed.request.RequestTypes$RequestSyntax$$anonfun$sendRequest$1.apply(RequestSyntax.scala:155)
//   at com.twitter.util.Future$$anonfun$flatMap$1.apply(Future.scala:986)
//   at com.twitter.util.Future$$anonfun$flatMap$1.apply(Future.scala:985)
//   at com.twitter.util.Promise$Transformer.liftedTree1$1(Promise.scala:112)
//   at com.twitter.util.Promise$Transformer.k(Promise.scala:112)
//   at com.twitter.util.Promise$Transformer.apply(Promise.scala:122)
//   at com.twitter.util.Promise$Transformer.apply(Promise.scala:103)
//   at com.twitter.util.Promise$$anon$1.run(Promise.scala:366)
//   at com.twitter.concurrent.LocalScheduler$Activation.run(Scheduler.scala:178)
//   at com.twitter.concurrent.LocalScheduler$Activation.submit(Scheduler.scala:136)
//   at com.twitter.concurrent.LocalScheduler.submit(Scheduler.scala:207)
//   at com.twitter.concurrent.Scheduler$.submit(Scheduler.scala:92)
//   at com.twitter.util.Promise.runq(Promise.scala:350)
//   at com.twitter.util.Promise.updateIfEmpty(Promise.scala:721)
//   at com.twitter.util.Promise.update(Promise.scala:694)
//   at com.twitter.util.Promise.setValue(Promise.scala:670)
//   at com.twitter.concurrent.AsyncQueue.offer(AsyncQueue.scala:111)
//   at com.twitter.finagle.netty3.transport.ChannelTransport.handleUpstream(ChannelTransport.scala:55)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.handler.codec.http.HttpContentDecoder.messageReceived(HttpContentDecoder.java:108)
//   at org.jboss.netty.channel.SimpleChannelUpstreamHandler.handleUpstream(SimpleChannelUpstreamHandler.java:70)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.handler.codec.http.HttpChunkAggregator.messageReceived(HttpChunkAggregator.java:145)
//   at org.jboss.netty.channel.SimpleChannelUpstreamHandler.handleUpstream(SimpleChannelUpstreamHandler.java:70)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.channel.Channels.fireMessageReceived(Channels.java:296)
//   at org.jboss.netty.handler.codec.frame.FrameDecoder.unfoldAndFireMessageReceived(FrameDecoder.java:459)
//   at org.jboss.netty.handler.codec.replay.ReplayingDecoder.callDecode(ReplayingDecoder.java:536)
//   at org.jboss.netty.handler.codec.replay.ReplayingDecoder.messageReceived(ReplayingDecoder.java:435)
//   at org.jboss.netty.channel.SimpleChannelUpstreamHandler.handleUpstream(SimpleChannelUpstreamHandler.java:70)
//   at org.jboss.netty.handler.codec.http.HttpClientCodec.handleUpstream(HttpClientCodec.java:92)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.channel.SimpleChannelHandler.messageReceived(SimpleChannelHandler.java:142)
//   at com.twitter.finagle.netty3.channel.ChannelStatsHandler.messageReceived(ChannelStatsHandler.scala:68)
//   at org.jboss.netty.channel.SimpleChannelHandler.handleUpstream(SimpleChannelHandler.java:88)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.channel.SimpleChannelHandler.messageReceived(SimpleChannelHandler.java:142)
//   at com.twitter.finagle.netty3.channel.ChannelRequestStatsHandler.messageReceived(ChannelRequestStatsHandler.scala:32)
//   at org.jboss.netty.channel.SimpleChannelHandler.handleUpstream(SimpleChannelHandler.java:88)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:559)
//   at org.jboss.netty.channel.Channels.fireMessageReceived(Channels.java:268)
//   at org.jboss.netty.channel.Channels.fireMessageReceived(Channels.java:255)
//   at org.jboss.netty.channel.socket.nio.NioWorker.read(NioWorker.java:88)
//   at org.jboss.netty.channel.socket.nio.AbstractNioWorker.process(AbstractNioWorker.java:108)
//   at org.jboss.netty.channel.socket.nio.AbstractNioSelector.run(AbstractNioSelector.java:337)
//   at org.jboss.netty.channel.socket.nio.AbstractNioWorker.run(AbstractNioWorker.java:89)
//   at org.jboss.netty.channel.socket.nio.NioWorker.run(NioWorker.java:178)
//   at org.jboss.netty.util.ThreadRenamingRunnable.run(ThreadRenamingRunnable.java:108)
//   at org.jboss.netty.util.internal.DeadLockProofWorker$1.run(DeadLockProofWorker.java:42)
//   at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142)
//   at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617)
//   at java.lang.Thread.run(Thread.java:745)
```

Here, since we didn't handle the `InvalidResponse`, awaiting the future resulted in an exception being thrown. Instead,
you can `handle` the failed future and recover in some way. A typical pattern is to capture the error in something like
an `Xor` (in cats) or an `Either` (in Scala's standard library):

```scala
import cats.data.Xor
// import cats.data.Xor

import featherbed.request.InvalidResponse
// import featherbed.request.InvalidResponse

Await.result {
  val request = client.post("foo/bad")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]().map(Xor.right).handle {
    case err @ InvalidResponse(rep, reason) => Xor.left(err)
  }
}
// res6: cats.data.Xor[featherbed.request.InvalidResponse,Foo] = Left(featherbed.request.InvalidResponse)
```

This example maps the `Future`'s successful result into an `Xor.Right`, and the `InvalidResponse` case into `Xor.Left`,
which represents the failure. The `Xor` can be handled further by the application.

Alternatively, you might want to use some default `Foo` in the event that the response can't be decoded:

```scala
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
// ERROR: response decoding failed: expected json value got T (line 1, column 1)
// res7: Product with Serializable = Foo(Default,0)
```

Similarly, if the response's content-type isn't one of the accepted MIME types, a different `InvalidResponse` is given:

```scala
Await.result {
  val request = client.post("foo/awful")
    .withContent(Foo("Hello world", 42), "application/json")
    .accept("application/json")

  request.send[Foo]()
}
// featherbed.request.InvalidResponse
//   at featherbed.request.RequestTypes$RequestSyntax$$anonfun$sendRequest$1.apply(RequestSyntax.scala:167)
//   at featherbed.request.RequestTypes$RequestSyntax$$anonfun$sendRequest$1.apply(RequestSyntax.scala:155)
//   at com.twitter.util.Future$$anonfun$flatMap$1.apply(Future.scala:986)
//   at com.twitter.util.Future$$anonfun$flatMap$1.apply(Future.scala:985)
//   at com.twitter.util.Promise$Transformer.liftedTree1$1(Promise.scala:112)
//   at com.twitter.util.Promise$Transformer.k(Promise.scala:112)
//   at com.twitter.util.Promise$Transformer.apply(Promise.scala:122)
//   at com.twitter.util.Promise$Transformer.apply(Promise.scala:103)
//   at com.twitter.util.Promise$$anon$1.run(Promise.scala:366)
//   at com.twitter.concurrent.LocalScheduler$Activation.run(Scheduler.scala:178)
//   at com.twitter.concurrent.LocalScheduler$Activation.submit(Scheduler.scala:136)
//   at com.twitter.concurrent.LocalScheduler.submit(Scheduler.scala:207)
//   at com.twitter.concurrent.Scheduler$.submit(Scheduler.scala:92)
//   at com.twitter.util.Promise.runq(Promise.scala:350)
//   at com.twitter.util.Promise.updateIfEmpty(Promise.scala:721)
//   at com.twitter.util.Promise.update(Promise.scala:694)
//   at com.twitter.util.Promise.setValue(Promise.scala:670)
//   at com.twitter.concurrent.AsyncQueue.offer(AsyncQueue.scala:111)
//   at com.twitter.finagle.netty3.transport.ChannelTransport.handleUpstream(ChannelTransport.scala:55)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.handler.codec.http.HttpContentDecoder.messageReceived(HttpContentDecoder.java:108)
//   at org.jboss.netty.channel.SimpleChannelUpstreamHandler.handleUpstream(SimpleChannelUpstreamHandler.java:70)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.handler.codec.http.HttpChunkAggregator.messageReceived(HttpChunkAggregator.java:145)
//   at org.jboss.netty.channel.SimpleChannelUpstreamHandler.handleUpstream(SimpleChannelUpstreamHandler.java:70)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.channel.Channels.fireMessageReceived(Channels.java:296)
//   at org.jboss.netty.handler.codec.frame.FrameDecoder.unfoldAndFireMessageReceived(FrameDecoder.java:459)
//   at org.jboss.netty.handler.codec.replay.ReplayingDecoder.callDecode(ReplayingDecoder.java:536)
//   at org.jboss.netty.handler.codec.replay.ReplayingDecoder.messageReceived(ReplayingDecoder.java:435)
//   at org.jboss.netty.channel.SimpleChannelUpstreamHandler.handleUpstream(SimpleChannelUpstreamHandler.java:70)
//   at org.jboss.netty.handler.codec.http.HttpClientCodec.handleUpstream(HttpClientCodec.java:92)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.channel.SimpleChannelHandler.messageReceived(SimpleChannelHandler.java:142)
//   at com.twitter.finagle.netty3.channel.ChannelStatsHandler.messageReceived(ChannelStatsHandler.scala:68)
//   at org.jboss.netty.channel.SimpleChannelHandler.handleUpstream(SimpleChannelHandler.java:88)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline$DefaultChannelHandlerContext.sendUpstream(DefaultChannelPipeline.java:791)
//   at org.jboss.netty.channel.SimpleChannelHandler.messageReceived(SimpleChannelHandler.java:142)
//   at com.twitter.finagle.netty3.channel.ChannelRequestStatsHandler.messageReceived(ChannelRequestStatsHandler.scala:32)
//   at org.jboss.netty.channel.SimpleChannelHandler.handleUpstream(SimpleChannelHandler.java:88)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:564)
//   at org.jboss.netty.channel.DefaultChannelPipeline.sendUpstream(DefaultChannelPipeline.java:559)
//   at org.jboss.netty.channel.Channels.fireMessageReceived(Channels.java:268)
//   at org.jboss.netty.channel.Channels.fireMessageReceived(Channels.java:255)
//   at org.jboss.netty.channel.socket.nio.NioWorker.read(NioWorker.java:88)
//   at org.jboss.netty.channel.socket.nio.AbstractNioWorker.process(AbstractNioWorker.java:108)
//   at org.jboss.netty.channel.socket.nio.AbstractNioSelector.run(AbstractNioSelector.java:337)
//   at org.jboss.netty.channel.socket.nio.AbstractNioWorker.run(AbstractNioWorker.java:89)
//   at org.jboss.netty.channel.socket.nio.NioWorker.run(NioWorker.java:178)
//   at org.jboss.netty.util.ThreadRenamingRunnable.run(ThreadRenamingRunnable.java:108)
//   at org.jboss.netty.util.internal.DeadLockProofWorker$1.run(DeadLockProofWorker.java:42)
//   at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142)
//   at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617)
//   at java.lang.Thread.run(Thread.java:745)
```




As you can see, these different failure scenarios provide different messages about what failure occured,
and give the original `Response`.  In the first case, we get back Circe's parsing error.  In the second
case, we get a message that the content type wasn't expected and therefore there isn't a decoder for it.
This helps us deal with inevitable runtime failures resulting from external systems.

Next, read about [Error Handling](05-error-handling.html)
