package featherbed

import java.net.URL
import java.nio.charset.Charset

import cats.data.Validated.{Invalid, Valid}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.Version.Http11
import com.twitter.finagle.http.{Method, Status, Response, Request}
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Await, Future}
import org.jboss.netty.handler.codec.http._
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import shapeless.{:+:, CNil, Witness}


class ClientSpec extends FlatSpec with MockFactory with ClientTest with BeforeAndAfterEach {

  val rec = stubFunction[Request, Unit]("receiveRequest")

  object F extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      rec(request)
      Future(Response())
    }
  }

  val client = mockClient("http://example.com/api/v1/", F)

  "Client" should "get" in {

    val req = client.get("foo/bar").accept("text/plain")

    Await.result(for {
      rep <- req.send[String]()
    } yield ())

    rec verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Get)
    }

  }

  it should "post" in {
    val req = client
      .post("foo/bar")
      .withContent("Hello world", "text/plain")


    Await.result(for {
      rep <- req
    } yield ())

    rec verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Post)
      assert(req.headerMap("Content-Type") == s"text/plain; charset=${Charset.defaultCharset.name}")
      assert(req.contentString == "Hello world")
    }

  }

}
