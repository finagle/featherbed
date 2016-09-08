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

```scala
import com.twitter.util.{Future,Await}
// import com.twitter.util.{Future, Await}

import com.twitter.finagle.{Service,Http}
// import com.twitter.finagle.{Service, Http}

import com.twitter.finagle.http.{Request,Response,Status,Version}
// import com.twitter.finagle.http.{Request, Response, Status, Version}

import java.net.{URL, InetSocketAddress}
// import java.net.{URL, InetSocketAddress}

import featherbed.request.ErrorResponse
// import featherbed.request.ErrorResponse

import featherbed.circe._
// import featherbed.circe._

import io.circe.generic.auto._
// import io.circe.generic.auto._

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
// server: com.twitter.finagle.ListeningServer = Group(/0:0:0:0:0:0:0:0:8768)

// the type of the successful response
case class Foo(foo: String)
// defined class Foo

// the client
val client = new featherbed.Client(new URL("http://localhost:8768/api/"))
// client: featherbed.Client = featherbed.Client@507d9270
```

When using the `send[T]` method, the resulting `Future` will *fail* if the server returns an HTTP error. This means that
in order to handle an error, you must handle it at the `Future` level using the `Future` API:

```scala
val req = client.get("not/found").accept("application/json")
// req: client.GetRequest[shapeless.:+:[String("application/json"),shapeless.CNil]] = GetRequest(http://localhost:8768/api/not/found,List(),UTF-8)

Await.result {
  req.send[Foo]().handle {
    case ErrorResponse(request, response) =>
      throw new Exception(s"Error response $response to request $request")
  }
}
// java.lang.Exception: Error response Response("HTTP/1.1 Status(404)") to request Request("GET /api/not/found", from 0.0.0.0/0.0.0.0:0)
//   at $anonfun$1.applyOrElse(<console>:31)
//   at $anonfun$1.applyOrElse(<console>:29)
//   at scala.runtime.AbstractPartialFunction.apply(AbstractPartialFunction.scala:36)
//   at com.twitter.util.Future$$anonfun$handle$1$$anonfun$applyOrElse$1.apply(Future.scala:1136)
//   at com.twitter.util.Try$.apply(Try.scala:13)
//   at com.twitter.util.Future$.apply(Future.scala:132)
//   at com.twitter.util.Future$$anonfun$handle$1.applyOrElse(Future.scala:1136)
//   at com.twitter.util.Future$$anonfun$handle$1.applyOrElse(Future.scala:1135)
//   at com.twitter.util.Future$$anonfun$rescue$1.apply(Future.scala:1016)
//   at com.twitter.util.Future$$anonfun$rescue$1.apply(Future.scala:1014)
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

This isn't a very useful error handler, but it demonstrates how errors can be intercepted at the `Future` level. The
`handle` or `rescue` methods of `Future` can be used to recover from the failure. See their API docs for more
information. The exception that's returned in a `Future` which failed due to a server error response is of type
`ErrorResponse`, which contains the request and response.

Often, the a REST API will be set up to return some meaningful representation of errors in the same content type as its
responses. In the example above, our dummy server is set up to return JSON errors in a well-defined structure. To
capture this, we can use the `send[Error, Success]` method instead of `send[T]`:

```scala
// ADT for errors
case class Error(error: String)
// defined class Error

val req = client.get("not/found").accept("application/json")
// req: client.GetRequest[shapeless.:+:[String("application/json"),shapeless.CNil]] = GetRequest(http://localhost:8768/api/not/found,List(),UTF-8)

Await.result(req.send[Error, Foo])
// res4: cats.data.Xor[Error,Foo] = Left(Error(The thing couldn't be found))
```

Instead of an exception, we're capturing the server errors in an `Xor[Error, Foo]`. `Xor` is another data type from
cats, which captures failures in a similar way to `Validated`. This is a typical pattern in Scala functional programming
for dealing with operations which may fail. The benefit is that the well-defined error type is also automatically
decoded for us. However, if the error can't be decoded, this will still result in a failed `Future`, which fails on the
decoding rather than the server error.

Next, read about [Building REST Clients](06-building-rest-clients.html)
