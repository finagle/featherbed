package featherbed

import java.nio.charset.Charset

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.util.{Await, Future}
import featherbed.support.DecodeAll
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import shapeless.{CNil, Coproduct, Witness}

class ClientSpec extends FlatSpec with MockFactory with ClientTest {

  val receiver = stubFunction[Request, Unit]("receiveRequest")

  object InterceptRequest extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      receiver(request)
      Future(Response())
    }
  }

  val client = mockClient("http://example.com/api/v1/", InterceptRequest)

  "Client" should "get" in {

    val req = client.get("foo/bar").accept("text/plain")

    intercept[Throwable](Await.result(for {
      rep <- req.send[String]()
    } yield ()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Get)
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "get with query params" in {

    val req = client
      .get("foo/bar")
      .withQueryParams("param" -> "value")
      .accept("text/plain")

    intercept[Throwable](Await.result(for {
      rep <- req.send[String]()
    } yield ()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar?param=value")
      assert(req.method == Method.Get)
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "post" in {
    val req = client
      .post("foo/bar")
      .withContent("Hello world", "text/plain")
      .accept("text/plain")

    intercept[Throwable](Await.result(for {
      rep <- req.send[String]()
    } yield ()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Post)
      assert(req.headerMap("Content-Type") == s"text/plain; charset=${Charset.defaultCharset.name}")
      assert(req.contentString == "Hello world")
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "post a form" in {
    val req = client
      .post("foo/bar")
      .withParams("foo" -> "bar", "bar" -> "baz")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.send[String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Post)
      assert(req.headerMap("Content-Type") == s"application/x-www-form-urlencoded")
      assert(req.contentString == "foo=bar&bar=baz")
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "make a head request" in {
    val req = client
      .head("foo/bar")

    Await.result(req.send())

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Head)
    }
  }

  it should "delete" in {
    val req = client
      .delete("foo/bar")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.send[String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Delete)
    }
  }

  it should "put" in {
    val req = client
      .put("foo/bar")
      .withContent("Hello world", "text/plain")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.send[String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Put)
      assert(req.contentType.contains(s"text/plain; charset=${Charset.defaultCharset.name}"))
      assert(req.contentString == "Hello world")
    }
  }

  ///---
  it should "sendZip: get" in {

    val req = client.get("foo/bar").accept("text/plain")

    intercept[Throwable](Await.result(for {
      rep <- req.sendZip[String, String]()
    } yield ()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Get)
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "sendZip: get with query params" in {

    val req = client
      .get("foo/bar")
      .withQueryParams("param" -> "value")
      .accept("text/plain")

    intercept[Throwable](Await.result(for {
      rep <- req.sendZip[String, String]()
    } yield ()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar?param=value")
      assert(req.method == Method.Get)
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "sendZip: post" in {
    val req = client
      .post("foo/bar")
      .withContent("Hello world", "text/plain")
      .accept("text/plain")

    intercept[Throwable](Await.result(for {
      rep <- req.sendZip[String, String]()
    } yield ()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Post)
      assert(req.headerMap("Content-Type") == s"text/plain; charset=${Charset.defaultCharset.name}")
      assert(req.contentString == "Hello world")
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "sendZip: post a form" in {
    val req = client
      .post("foo/bar")
      .withParams("foo" -> "bar", "bar" -> "baz")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.sendZip[String, String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Post)
      assert(req.headerMap("Content-Type") == s"application/x-www-form-urlencoded")
      assert(req.contentString == "foo=bar&bar=baz")
      assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
    }
  }

  it should "sendZip: delete" in {
    val req = client
      .delete("foo/bar")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.sendZip[String, String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Delete)
    }
  }

  it should "sendZip: put" in {
    val req = client
      .put("foo/bar")
      .withContent("Hello world", "text/plain")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.sendZip[String, String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Put)
      assert(req.contentType.contains(s"text/plain; charset=${Charset.defaultCharset.name}"))
      assert(req.contentString == "Hello world")
    }
  }

  "Compile" should "fail when no encoder is available for the request" in {
    assertDoesNotCompile(
      """
        client.put("foo/bar").withContent("Hello world", "no/encoder").accept("*/*").send[Response]()
      """)
  }

  it should "fail when no decoder is available for the response" in {
    assertDoesNotCompile(
      """
        client.get("foo/bar").accept("pie/pumpkin").send[String]()
      """.stripMargin
    )
  }
}
