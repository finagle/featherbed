package featherbed
package request

import scala.annotation.implicitNotFound

import cats.data._
import cats.data.Validated._
import cats.implicits._
import com.twitter.finagle.http.{FormElement, Request, RequestBuilder}
import com.twitter.finagle.http.RequestConfig.Yes
import com.twitter.io.Buf
import featherbed.Client
import featherbed.content
import featherbed.content.{Form, MultipartForm, ToFormParams}
import featherbed.support.AcceptHeader
import shapeless.{Coproduct, Witness}

case class RequestBuildingError(errors: NonEmptyList[Throwable])
  extends Throwable(s"Failed to build request: ${errors.toList.mkString(";")}")


/**
  * Represents evidence that the request can be built.  For requests that include content,
  * this requires that an implicit [[content.Encoder]] is available for the given Content-Type.
  *
  * @tparam T request type
  */
@implicitNotFound(
  """The request of type ${T} cannot be built.  This is most likely because either:
  1. If the request is a POST, PUT, or PATCH request:
     a. The request requires a Content-Type to be defined, but one was not defined (using the withContent method)
     b. An Encoder is required for the request's Content-Type, but one was not available in implicit scope. This is
        usually a matter of importing the right module to obtain the Encoder instance.
  2. Something is missing from featherbed""")
sealed trait CanBuildRequest[T] {
  def build(t: T): ValidatedNel[Throwable, Request]
}

object CanBuildRequest {


  private def baseBuilder[Accept <: Coproduct](
    request: HTTPRequest[_, Accept, _, _])(implicit
    accept: AcceptHeader[Accept]
  ): RequestBuilder[Yes, Nothing] = {
    val builder = RequestBuilder().url(request.buildUrl).addHeader("Accept", accept.toString)
    request.headers.foldLeft(builder) {
      (accum, next) => accum.setHeader(next._1, next._2)
    }
  }

  implicit def canBuildGetRequest[Accept <: Coproduct](implicit
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[HTTPRequest.GetRequest[Accept]] =
    new CanBuildRequest[HTTPRequest.GetRequest[Accept]] {
      def build(getRequest: HTTPRequest.GetRequest[Accept]) = Valid(
        baseBuilder(getRequest).buildGet()
      )
    }

  implicit def canBuildPostRequestWithContentBuffer[Accept <: Coproduct, CT <: String](implicit
    accept: AcceptHeader[Accept],
    witness: Witness.Aux[CT]
  ): CanBuildRequest[HTTPRequest.PostRequest[Accept, Buf, CT]] =
    new CanBuildRequest[HTTPRequest.PostRequest[Accept, Buf, CT]] {
      def build(postRequest: HTTPRequest.PostRequest[Accept, Buf, CT]) = Valid(
        baseBuilder(postRequest)
          .addHeader("Content-Type", s"${witness.value}; charset=${postRequest.charset.name}")
          .buildPost(postRequest.content.content)
      )
    }

  implicit def canBuildFormPostRequest[Accept <: Coproduct, Content](implicit
    accept: AcceptHeader[Accept],
    toFormParams: ToFormParams[Content]
  ): CanBuildRequest[HTTPRequest.FormPostRequest[Accept, Content]] =
    new CanBuildRequest[HTTPRequest.FormPostRequest[Accept, Content]] {
      def build(
        formPostRequest: HTTPRequest.FormPostRequest[Accept, Content]
      ): Validated[NonEmptyList[Throwable], Request] = toFormParams(formPostRequest.content.content).map {
        elems => baseBuilder(formPostRequest).add(elems).buildFormPost(multipart = false)
      }
    }

  implicit def canBuildMultipartFormPostRequest[Accept <: Coproduct, Content](implicit
    accept: AcceptHeader[Accept],
    toFormParams: ToFormParams[Content]
  ): CanBuildRequest[HTTPRequest.MultipartFormRequest[Accept, Content]] =
    new CanBuildRequest[HTTPRequest.MultipartFormRequest[Accept, Content]] {
      def build(
        formPostRequest: HTTPRequest.MultipartFormRequest[Accept, Content]
      ): Validated[NonEmptyList[Throwable], Request] = toFormParams(formPostRequest.content.content).map {
        elems => baseBuilder(formPostRequest).add(elems).buildFormPost(multipart = true)
      }
    }

  implicit def canBuildPutRequestWithContentBuffer[Accept <: Coproduct, CT <: String](implicit
    accept: AcceptHeader[Accept],
    witness: Witness.Aux[CT]
  ): CanBuildRequest[HTTPRequest.PutRequest[Accept, Buf, CT]] =
    new CanBuildRequest[HTTPRequest.PutRequest[Accept, Buf, CT]] {
      def build(putRequest: HTTPRequest.PutRequest[Accept, Buf, CT]) = Valid(
        baseBuilder(putRequest)
          .addHeader("Content-Type", s"${witness.value}; charset=${putRequest.charset.name}")
          .buildPut(putRequest.content.content)
      )
    }

  implicit val canBuildHeadRequest = new CanBuildRequest[HTTPRequest.HeadRequest] {
    def build(headRequest: HTTPRequest.HeadRequest) = Valid(
      baseBuilder(headRequest).buildHead()
    )
  }

  implicit def canBuildDeleteRequest[Accept <: Coproduct](implicit
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[HTTPRequest.DeleteRequest[Accept]] =
    new CanBuildRequest[HTTPRequest.DeleteRequest[Accept]] {
      def build(deleteRequest: HTTPRequest.DeleteRequest[Accept]) = Valid(
        baseBuilder(deleteRequest).buildDelete()
      )
    }

  implicit def canBuildPostRequestWithEncoder[Accept <: Coproduct, Content, CT <: String](implicit
    encoder: content.Encoder[Content, CT],
    witness: Witness.Aux[CT],
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[HTTPRequest.PostRequest[Accept, Content, CT]] =
    new CanBuildRequest[HTTPRequest.PostRequest[Accept, Content, CT]] {
      def build(postRequest: HTTPRequest.PostRequest[Accept, Content, CT]) =
        encoder(postRequest.content.content, postRequest.charset).map {
          buf => baseBuilder(postRequest)
            .addHeader("Content-Type", s"${witness.value}; charset=${postRequest.charset.name}")
            .buildPost(buf)
        }
    }

  implicit def canBuildPutRequestWithEncoder[Accept <: Coproduct, Content, CT <: String](implicit
    encoder: content.Encoder[Content, CT],
    witness: Witness.Aux[CT],
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[HTTPRequest.PutRequest[Accept, Content, CT]] =
    new CanBuildRequest[HTTPRequest.PutRequest[Accept, Content, CT]] {
      def build(putRequest: HTTPRequest.PutRequest[Accept, Content, CT]) =
        encoder(putRequest.content.content, putRequest.charset).map {
          buf => baseBuilder(putRequest)
            .addHeader("Content-Type", s"${witness.value}; charset=${putRequest.charset.name}")
            .buildPut(buf)
        }
    }

  implicit def canBuildDefinedRequest[
    Meth <: String,
    Accept <: Coproduct
  ]: CanBuildRequest[HTTPRequest[Meth, Accept, Request, None.type]] =
    new CanBuildRequest[HTTPRequest[Meth, Accept, Request, None.type]] {
      def build(req: HTTPRequest[Meth, Accept, Request, None.type]) = Validated.valid(req.content.content)
    }
}

