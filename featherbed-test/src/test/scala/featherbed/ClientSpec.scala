package featherbed

import java.nio.charset.Charset

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.util.{Await, Future}
import featherbed.content.ToFormParams
import org.scalamock.scalatest.MockFactory
import org.scalatest.FreeSpec

class ClientSpec extends FreeSpec with MockFactory with ClientTest {

  val receiver = stubFunction[Request, Unit]("receiveRequest")

  object InterceptRequest extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      receiver(request)
      Future(Response())
    }
  }

  val client = mockClient("http://example.com/api/v1/", InterceptRequest)

  "GET requests" - {

    "Plain" - {
      "send" in {
        val req = client.get("foo/bar").accept("text/plain")

        intercept[Throwable](
          Await.result(
            for {
              rep <- req.send[String]()
            } yield ()))

        receiver verify request {
          req =>
            assert(req.uri == "/api/v1/foo/bar")
            assert(req.method == Method.Get)
            assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
        }
      }

      "toService" in {
        val service = client.get("foo/bar").accept("text/plain").toService[String]

        intercept[Throwable](
          Await.result(
            for {
              rep <- service()
            } yield ()))

        receiver verify request {
          req =>
            assert(req.uri == "/api/v1/foo/bar")
            assert(req.method == Method.Get)
            assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
        }
      }
    }

    "With query params" - {

      "send" in {
        val req = client
          .get("foo/bar")
          .withQueryParams("param" -> "value")
          .accept("text/plain")

        intercept[Throwable](
          Await.result(
            for {
              rep <- req.send[String]()
            } yield ()))

        receiver verify request {
          req =>
            assert(req.uri == "/api/v1/foo/bar?param=value")
            assert(req.method == Method.Get)
            assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
        }
      }
    }

    "toService" in {
      case class Params(
        first: Int,
        second: String
      )
      val service = client
        .get("foo/bar")
        .accept("text/plain")
        .toService[Params, String]

      intercept[Throwable](
        Await.result(
          for {
            rep <- service(Params(10, "foo"))
          } yield ()))

      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar?first=10&second=foo")
          assert(req.method == Method.Get)
          assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
      }
    }

  }


  "POST requests" - {
    "Plain" - {
      "send" in {
        val req = client
          .post("foo/bar")
          .withContent("Hello world", "text/plain")
          .accept("text/plain")

        intercept[Throwable](
          Await.result(
            for {
              rep <- req.send[String]()
            } yield ()))

        receiver verify request {
          req =>
            assert(req.uri == "/api/v1/foo/bar")
            assert(req.method == Method.Post)
            assert(req.headerMap("Content-Type") == s"text/plain; charset=${Charset.defaultCharset.name}")
            assert(req.contentString == "Hello world")
            assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
        }
      }

      "toService" in {
        val service = client.post("foo/bar").accept("text/plain").toService[String, String]("text/plain")
        intercept[Throwable] {
          Await.result {
            for {
              rep <- service("Hello world")
            } yield ()
          }
        }

        receiver verify request {
          req =>
            assert(req.uri == "/api/v1/foo/bar")
            assert(req.method == Method.Post)
            assert(req.headerMap("Content-Type") == s"text/plain; charset=${Charset.defaultCharset.name}")
            assert(req.contentString == "Hello world")
            assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
        }
      }
    }

    "Form" - {
      "send" in {
        val req = client
          .post("foo/bar")
          .withParams("foo" -> "bar", "bar" -> "baz")
          .accept("text/plain")

        intercept[Throwable](Await.result(req.send[String]()))

        receiver verify request {
          req =>
            assert(req.uri == "/api/v1/foo/bar")
            assert(req.method == Method.Post)
            assert(req.headerMap("Content-Type") == s"application/x-www-form-urlencoded")
            assert(req.contentString == "foo=bar&bar=baz")
            assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
        }
      }

      "toService" - {
        "string args" in {
          case class Params(foo: String, bar: String)
          val service = client.post("foo/bar").accept("text/plain").form.toService[Params, String]
          intercept[Throwable] {
            Await.result {
              for {
                result <- service(Params(foo = "bar", bar = "baz"))
              } yield ()
            }
          }

          receiver verify request {
            req =>
              assert(req.uri == "/api/v1/foo/bar")
              assert(req.method == Method.Post)
              assert(req.headerMap("Content-Type") == s"application/x-www-form-urlencoded")
              assert(req.contentString == "foo=bar&bar=baz")
              assert((req.accept.toSet diff Set("text/plain", "*/*; q=0")) == Set.empty)
          }
        }
      }
    }
  }

  "HEAD" - {
    "send" in {
      val req = client
        .head("foo/bar")

      Await.result(req.send[Response]())

      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar")
          assert(req.method == Method.Head)
      }
    }

    "toService" in {
      val service = client.head("foo/bar").toService
      Await.result(service())

      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar")
          assert(req.method == Method.Head)
      }
    }
  }

  "DELETE" - {
    "send" in {
      val req = client
        .delete("foo/bar")
        .accept("text/plain")

      intercept[Throwable](Await.result(req.send[String]()))

      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar")
          assert(req.method == Method.Delete)
      }
    }

    "toService" in {
      val service = client.delete("foo/bar").accept("text/plain").toService[String]
      intercept[Throwable](Await.result(service()))

      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar")
          assert(req.method == Method.Delete)
      }
    }
  }

  "PUT" - {
    "send" in {
      val req = client
        .put("foo/bar")
        .withContent("Hello world", "text/plain")
        .accept("text/plain")

      intercept[Throwable](Await.result(req.send[String]()))

      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar")
          assert(req.method == Method.Put)
          assert(req.contentType.contains(s"text/plain; charset=${Charset.defaultCharset.name}"))
          assert(req.contentString == "Hello world")
      }
    }

    "toService" in {
      val service = client.put("foo/bar").accept("text/plain").toService[String, String]("text/plain")
      intercept[Throwable](Await.result(service("Hello world")))
      receiver verify request {
        req =>
          assert(req.uri == "/api/v1/foo/bar")
          assert(req.method == Method.Put)
          assert(req.contentType.contains(s"text/plain; charset=${Charset.defaultCharset.name}"))
          assert(req.contentString == "Hello world")
      }
    }
  }

  ///---
  "sendZip" in {

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

  "sendZip: get with query params" in {

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

  "sendZip: post" in {
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

  "sendZip: post a form" in {
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

  "sendZip: delete" in {
    val req = client
      .delete("foo/bar")
      .accept("text/plain")

    intercept[Throwable](Await.result(req.sendZip[String, String]()))

    receiver verify request { req =>
      assert(req.uri == "/api/v1/foo/bar")
      assert(req.method == Method.Delete)
    }
  }

  "sendZip: put" in {
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

  "Compile fails when no encoder is available for the request" in {
    assertDoesNotCompile(
      """
        client.put("foo/bar").withContent("Hello world", "no/encoder").accept("*/*").send[Response]()
      """)
  }

  "Compile fails when no decoder is available for the response" in {
    assertDoesNotCompile(
      """
        client.get("foo/bar").accept("pie/pumpkin").send[String]()
      """.stripMargin
    )
  }
}
