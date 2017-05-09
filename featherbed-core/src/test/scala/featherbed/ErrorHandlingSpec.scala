package featherbed

import cats.data.{NonEmptyList, Validated}
import cats.data.Validated.{Invalid, Valid}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.io.Buf
import com.twitter.util.{Await, Future}
import featherbed.content.{Decoder, Encoder}
import featherbed.fixture.ClientTest
import featherbed.request._
import org.scalamock.scalatest.MockFactory
import org.scalatest.FreeSpec
import shapeless.Witness

class ErrorHandlingSpec extends FreeSpec with MockFactory with ClientTest {

  case class TestResponse(foo: String, bar: Int)
  case class TestError(baz: String, buzz: Boolean)

  val testResponse = TestResponse("foo", 22)
  val testError = TestError("baz", true)

  sealed trait TestContent
  case object GoodContent extends TestContent
  case object BadContent extends TestContent

  implicit val testContentEncoder: Encoder[TestContent, Witness.`"test/content"`.T] = Encoder.of("test/content") {
      case (GoodContent, _) => Valid(Buf.Utf8("Good")).toValidatedNel
      case (BadContent, _) => Invalid(NonEmptyList(new Exception("Bad"), Nil))
    }

  case object DecodingError extends Throwable

  implicit val testResponseDecoder: Decoder.Aux[Witness.`"test/response"`.T, TestResponse] =
    Decoder.of("test/response") {
      response =>
        val Regex = """TestResponse\((.*),(\d+)\)""".r
        response.contentString match {
          case Regex(str, int) => Validated.catchNonFatal(int.toInt).map(TestResponse(str, _)).toValidatedNel
          case _ => Invalid(DecodingError).toValidatedNel
        }
    }

  implicit val testErrorDecoder: Decoder.Aux[Witness.`"test/response"`.T, TestError] = Decoder.of("test/response") {
    response =>
      val Regex = """TestError\((.*),(true|false)\)""".r
      response.contentString match {
        case Regex(str, b) => Validated.catchNonFatal(b.toBoolean).map(TestError(str, _)).toValidatedNel
        case _ => Invalid(DecodingError).toValidatedNel
      }
  }


  val receiver = mockFunction[Request, Response]("receiveRequest")

  object InterceptRequest extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      Future(receiver(request))
    }
  }

  val client = mockClient("http://example.com/api/v1/", InterceptRequest)

  "send[K]" - {

    "returns failed future when request is invalid" in {
      val content: TestContent = BadContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")

      intercept[RequestBuildingError](Await.result(req.send[TestResponse]()))
    }

    "returns failed future when request fails" - {
      (400 to 404) ++ (500 to 503) foreach { code =>
        code.toString in {
          receiver expects * returning {
            val response = Response()
            response.statusCode = code
            response
          }
          val content: TestContent = GoodContent
          val req = client.post("foo")
            .withContent(content, "test/content")
            .accept("test/response")

          intercept[ErrorResponse](Await.result(req.send[TestResponse]()))
        }
      }
    }

    "returns failed future when response has unacceptable content type" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "foo/bar"
        response.contentString = testResponse.toString
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      intercept[InvalidResponse](Await.result(req.send[TestResponse]()))
    }

    "returns failed future when response fails to decode" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "test/response"
        response.contentString = "not json"
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      intercept[InvalidResponse](Await.result(req.send[TestResponse]()))
    }

    "returns successful future when everything works" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "test/response"
        response.contentString = testResponse.toString
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      val result = Await.result(req.send[TestResponse]())
      assert(result == testResponse)
    }

  }

  "send[Error, Success]" - {

    "returns failed future when request is invalid" in {
      val content: TestContent = BadContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")

      intercept[RequestBuildingError](Await.result(req.send[TestError, TestResponse]()))
    }

    "returns successful future when request fails with valid error response" - {
      (400 to 404) ++ (500 to 503) foreach { code =>
        code.toString in {
          receiver expects * returning {
            val response = Response()
            response.statusCode = code
            response.contentType = "test/response"
            response.contentString = testError.toString
            response
          }
          val content: TestContent = GoodContent
          val req = client.post("foo")
            .withContent(content, "test/content")
            .accept("test/response")

          val result = Await.result(req.send[TestError, TestResponse]())
          assert(result == Left(testError))
        }
      }
    }

    "returns failed future when response has unacceptable content type" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "foo/bar"
        response.contentString = testResponse.toString
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      intercept[InvalidResponse](Await.result(req.send[TestError, TestResponse]()))
    }

    "returns failed future when successful response fails to decode" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "test/response"
        response.contentString = "not json"
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      intercept[InvalidResponse](Await.result(req.send[TestError, TestResponse]()))
    }

    "returns failed future when error response fails to decode" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 400
        response.contentType = "test/response"
        response.contentString = "not json"
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      intercept[InvalidResponse](Await.result(req.send[TestError, TestResponse]()))
    }

    "returns successful future when everything works" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "test/response"
        response.contentString = testResponse.toString
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      val result = Await.result(req.send[TestError, TestResponse]())
      assert(result == Right(testResponse))
    }
  }

  "sendZip[Error, Success]" - {
    "returns successful future when request fails with valid error response" - {
      (400 to 404) ++ (500 to 503) foreach { code =>
        code.toString in {
          receiver expects * returning {
            val response = Response()
            response.statusCode = code
            response.contentType = "test/response"
            response.contentString = testError.toString
            response
          }
          val content: TestContent = GoodContent
          val req = client.post("foo")
            .withContent(content, "test/content")
            .accept("test/response")

          val result = Await.result(req.sendZip[TestError, TestResponse]())
          assert(result._1 == Left(testError))
          assert(result._2.isInstanceOf[Response])
        }
      }
    }

    "returns successful future when everything works" in {
      receiver expects * returning {
        val response = Response()
        response.statusCode = 200
        response.contentType = "test/response"
        response.contentString = testResponse.toString
        response
      }
      val content: TestContent = GoodContent
      val req = client.post("foo")
        .withContent(content, "test/content")
        .accept("test/response")
      val result = Await.result(req.sendZip[TestError, TestResponse]())
      assert(result._1 == Right(testResponse))
      assert(result._2.isInstanceOf[Response])
    }
  }
}
