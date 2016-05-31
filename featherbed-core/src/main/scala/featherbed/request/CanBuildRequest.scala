package featherbed.request

import java.net.URL
import scala.annotation.implicitNotFound

import featherbed.Client
import featherbed.content
import featherbed.support.AcceptHeader

import cats.data._, Xor._, Validated._
import cats.std.list._
import cats.syntax.traverse._
import com.twitter.finagle.http.{FormElement, Request, RequestBuilder}
import com.twitter.finagle.http.RequestConfig.Yes
import com.twitter.io.Buf
import shapeless.{Coproduct, Witness}


case class RequestBuildingError(errors: NonEmptyList[Throwable])
  extends Throwable(s"Failed to build request: ${errors.unwrap.mkString(";")}")

trait RequestBuilding {
  self: Client with RequestTypes =>

  /**
    * Represents evidence that the request can be built.  For requests that include content,
    * this requires that an implicit [[content.Encoder]] is available for the given Content-Type.
    *
    * @tparam T request type
    */
  @implicitNotFound(
    """The request of type $T cannot be built.  This is most likely because either:
    1. If the request is a POST, PUT, or PATCH request:
       a. The request requires a Content-Type to be defined, but one was not defined (using the withContent method)
       b. An Encoder is required for the request's Content-Type, but one was not available in implicit scope. This is
          usually a matter of importing the right module to obtain the Encoder instance.
    2. Something is missing from featherbed""")
  sealed trait CanBuildRequest[T] {
    def build(t: T): ValidatedNel[Throwable, Request]
  }

  object CanBuildRequest {

    private def baseBuilder[Accept <: Coproduct, Self <: RequestSyntax[Accept, Self]](
      request: RequestSyntax[Accept, Self]
    )(
      implicit
      accept: AcceptHeader[Accept]
    ): RequestBuilder[Yes, Nothing] = request.buildHeaders(
      RequestBuilder().url(request.url).addHeader("Accept", accept.toString)
    )

    implicit def canBuildGetRequest[Accept <: Coproduct](
      implicit
      accept: AcceptHeader[Accept]
    ): CanBuildRequest[GetRequest[Accept]] = new CanBuildRequest[GetRequest[Accept]] {
      def build(getRequest: GetRequest[Accept]) = Valid(
        baseBuilder(getRequest).buildGet()
      )
    }

    implicit def canBuildPostRequestWithContentBuffer[Accept <: Coproduct, CT <: content.ContentType](
      implicit
      accept: AcceptHeader[Accept],
      witness: Witness.Aux[CT]
    ): CanBuildRequest[PostRequest[Buf, CT, Accept]] =
      new CanBuildRequest[PostRequest[Buf, CT, Accept]] {
        def build(postRequest: PostRequest[Buf, CT, Accept]) = Valid(
          baseBuilder(postRequest)
            .addHeader("Content-Type", s"${witness.value}; charset=${postRequest.charset.name}")
            .buildPost(postRequest.content)
        )
      }

    implicit def canBuildFormPostRequest[Accept <: Coproduct](
      implicit
      accept: AcceptHeader[Accept]
    ): CanBuildRequest[FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]]] =
      new CanBuildRequest[FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]]] {
        def build(
          formPostRequest: FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]]
        ) = {
          formPostRequest.form match {
            case Right(elems) =>
              val initial = elems.head.map(baseBuilder(formPostRequest).add)
              val builder = elems.tail.foldLeft(initial) {
                (builder, elem) => builder andThen {
                  b => elem.map {
                    e => b.add(e)
                  }
                }
              }
              // Finagle takes care of Content-Type header
              builder.map(_.buildFormPost(formPostRequest.multipart))
          }
        }
      }

    implicit def canBuildPutRequestWithContentBuffer[Accept <: Coproduct, CT <: content.ContentType](
      implicit
      accept: AcceptHeader[Accept],
      witness: Witness.Aux[CT]
    ): CanBuildRequest[PutRequest[Buf, CT, Accept]] =
      new CanBuildRequest[PutRequest[Buf, CT, Accept]] {
        def build(putRequest: PutRequest[Buf, CT, Accept]) = Valid(
          baseBuilder(putRequest)
            .addHeader("Content-Type", s"${witness.value}; charset=${putRequest.charset.name}")
            .buildPut(putRequest.content)
        )
      }

    implicit val canBuildHeadRequest = new CanBuildRequest[HeadRequest] {
      def build(headRequest: HeadRequest) = Valid(
        headRequest.buildHeaders(
          RequestBuilder().url(headRequest.url)
        ).buildHead()
      )
    }

    implicit def canBuildDeleteRequest[Accept <: Coproduct](
      implicit
      accept: AcceptHeader[Accept]
    ): CanBuildRequest[DeleteRequest[Accept]] = new CanBuildRequest[DeleteRequest[Accept]] {
      def build(deleteRequest: DeleteRequest[Accept]) = Valid(
        baseBuilder(deleteRequest).buildDelete()
      )
    }

    implicit def canBuildPostRequestWithEncoder[Accept <: Coproduct, A, CT <: content.ContentType](
      implicit
      encoder: content.Encoder[A, CT],
      witness: Witness.Aux[CT],
      accept: AcceptHeader[Accept]
    ): CanBuildRequest[PostRequest[A, CT, Accept]] =
      new CanBuildRequest[PostRequest[A, CT, Accept]] {
        def build(postRequest: PostRequest[A, CT, Accept]) = encoder(postRequest.content, postRequest.charset).map {
          buf => baseBuilder(postRequest)
            .addHeader("Content-Type", s"${witness.value}; charset=${postRequest.charset.name}")
            .buildPost(buf)
        }
      }

    implicit def canBuildPutRequestWithEncoder[Accept <: Coproduct, A, CT <: content.ContentType](
      implicit
      encoder: content.Encoder[A, CT],
      witness: Witness.Aux[CT],
      accept: AcceptHeader[Accept]
    ): CanBuildRequest[PutRequest[A, CT, Accept]] =
      new CanBuildRequest[PutRequest[A, CT, Accept]] {
        def build(putRequest: PutRequest[A, CT, Accept]) = encoder(putRequest.content, putRequest.charset).map {
          buf => baseBuilder(putRequest)
            .addHeader("Content-Type", s"${witness.value}; charset=${putRequest.charset.name}")
            .buildPut(buf)
        }
      }
  }

}
