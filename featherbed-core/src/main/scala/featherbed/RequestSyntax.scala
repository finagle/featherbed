package featherbed

import java.nio.charset.{StandardCharsets, Charset}

import _root_.support.{RuntimeContentType, ContentTypeSupport}
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.std.list._
import featherbed.content.{Decoder}
import featherbed.support._
import com.twitter.finagle.Service
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.http.RequestConfig.Yes
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util.Future
import shapeless._
import shapeless.ops.hlist.{ToTraversable, Filter, LiftAll, ToCoproduct}
import shapeless.ops.record.{ToMap, Keys, SelectAll}
import shapeless.record.recordOps

import scala.annotation.implicitNotFound


case class RequestBuildingError(errors: NonEmptyList[Throwable]) extends Throwable(s"Failed to build request: ${errors.unwrap.mkString(";")}")

@implicitNotFound("""The request of type $T cannot be built.  This is most likely because either:
  1. It requires a Content-Type to be defined, but one was not defined (using the withContent method)
  2. No Encoder is available for the request's Content-Type.  This is usually a matter of importing the right module.
  3. Due to a scala bug, a request sometimes needs to be assigned to a concrete value in order to be able to prove its type.  If
     you're calling map or flatMap directly on a `client.get(..).etc().etc()` expression, or using one directly in a for-comprehension,
     try assigning that request to a value and using the value instead.
  4. Something is missing from featherbed""")
sealed trait CanBuildRequest[T] {
  def build(t: T, charset: Charset) : ValidatedNel[Throwable, Request]
}

object CanBuildRequest {

  implicit def canBuildGetRequest[Accept <: Coproduct] : CanBuildRequest[GetRequest[Accept]] = new CanBuildRequest[GetRequest[Accept]] {
    def build(getRequest: GetRequest[Accept], charset: Charset) = Valid(getRequest.requestBuilder.buildGet())
  }
  implicit def canBuildPostRequestWithContent[Accept <: Coproduct, CT <: content.ContentType] : CanBuildRequest[PostRequest[Buf, CT, Some[Buf], Accept]] = new CanBuildRequest[PostRequest[Buf, CT, Some[Buf], Accept]] {
    def build(postRequest: PostRequest[Buf, CT, Some[Buf], Accept], charset: Charset) = Valid(postRequest.requestBuilder.buildPost(postRequest.content.get))   //I justify this Option#get because it is known at a typelevel to be Some.
  }
  implicit def canBuildFormPostRequest[Accept <: Coproduct] : CanBuildRequest[FormPostRequest[Accept]] = new CanBuildRequest[FormPostRequest[Accept]] {
    def build(formPostRequest: FormPostRequest[Accept], charset: Charset) = Valid(formPostRequest.requestBuilder.buildFormPost(formPostRequest.multipart))
  }
  implicit def canBuildPutRequest[Accept <: Coproduct, CT <: content.ContentType] : CanBuildRequest[PutRequest[Buf, CT, Some[Buf], Accept]] = new CanBuildRequest[PutRequest[Buf, CT, Some[Buf], Accept]] {
    def build(putRequest: PutRequest[Buf, CT, Some[Buf], Accept], charset: Charset) = Valid(putRequest.requestBuilder.buildPut(putRequest.content.get))
  }

  implicit def canBuildPostRequestWithEncoder[Accept <: Coproduct, A, CT](
    implicit encoder: content.Encoder[A, CT]) : CanBuildRequest[PostRequest[A, CT, Some[A], Accept]] = new CanBuildRequest[PostRequest[A, CT, Some[A], Accept]] {

    def build(postRequest: PostRequest[A, CT, Some[A], Accept], charset: Charset) = encoder(postRequest.content.get, charset).map {
      encoded => postRequest.requestBuilder.buildPost(encoded)
    }
  }

  /**
    * Helper method to supply an instance for a `foo.type` if its widened type has an instance.  Scala seems to have trouble directly
    * finding implicit typeclass instances for singletons with type arguments.  Shapeless' `Widen` typeclass can bridge the gap.
    */
  implicit def canBuildSingletonType[T, W >: T](implicit widen: Widen.Aux[T, W], canBuild: CanBuildRequest[W]) : CanBuildRequest[T] = new CanBuildRequest[T] {
    def build(req: T, charset: Charset) = canBuild.build(req, charset)
  }

}

case class InvalidResponse(response: Response, reason: String)


abstract class RequestSyntax[HasUrl,HasForm,Accept <: Coproduct](
  client: Client,
  dest: String,
  protected[featherbed] val requestBuilder: RequestBuilder[HasUrl, HasForm]){

  type Self <: RequestSyntax[HasUrl,HasForm,Accept]
  type SelfAccepting[A <: Coproduct]
  val charset: Charset

  def send[K]()(implicit ev: this.type <:< Self,
    canBuild: CanBuildRequest[Self],
    decodeAll: DecodeAll[K, Accept]) : Future[Validated[InvalidResponse,K]] =
    buildRequest match {
      case Valid(req) => for {
        conn <- client.httpClient()
        rep <- conn(req)
      } yield {
        rep.contentType flatMap ContentTypeSupport.contentTypePieces match {
          case None => Invalid(InvalidResponse(rep, "Content-Type header is not present"))
          case Some(RuntimeContentType(mediaType, params)) =>
            decodeAll.instances.find(_.contentType == mediaType) match {
              case Some(decoder) => decoder(rep).leftMap(errs => InvalidResponse(rep, errs.unwrap.map(_.getMessage).mkString("; ")))
              case None => Invalid(InvalidResponse(rep, s"No decoder was found for $mediaType"))
            }
        }
      }
      case Invalid(errs) => Future.exception(RequestBuildingError(errs))
    }

  def flatMap[K](f: Response => Future[K])(implicit canBuild: CanBuildRequest[Self], ev: this.type <:< Self) = buildRequest match {
    case Valid(req) => for {
      conn <- client.httpClient()
      rep <- conn(req)
      p <- f(rep)
    } yield p
    case Invalid(errs) => Future.exception(RequestBuildingError(errs))
  }


  def map[K](f: Response => K)(implicit canBuild: CanBuildRequest[Self], ev: this.type <:< Self) = buildRequest match {
    case Valid(req) => for {
      conn <- client.httpClient()
      rep <- conn(req)
    } yield f(rep)
    case Invalid(errs) => Future.exception(RequestBuildingError(errs))
  }

  protected def withBuilder(requestBuilder: RequestBuilder[HasUrl, HasForm]): Self


  /**
    * These methods which will rely on copy() from case classes will have to be boilerplated in subclasses, because
    * scala won't allow us to refer to the shape of a copy method or the widened subclass's type.
    */
  protected[featherbed] def buildRequest(implicit canBuild: CanBuildRequest[Self], ev: this.type <:< Self) : ValidatedNel[Throwable, Request] =
    canBuild.build(this : Self, charset)

  def withHeader(name: String, value: String) : Self = withBuilder(requestBuilder.addHeader(name, value))
  def withHeaders(headers: (String, String)*) : Self = withBuilder(requestBuilder = headers.foldLeft(requestBuilder) {
    case (b, (k, v)) => b.addHeader(k,v)
  })

  def accept[ContentTypes <: Coproduct] : SelfAccepting[ContentTypes]

  def withCharset(charset: Charset) : Self
}

case class GetRequest[Accept <: Coproduct] private[featherbed] (
    private val client: Client,
    private val dest: String,
    private val rb: RequestBuilder[Yes, Nothing],
    charset: Charset = Charset.defaultCharset
  ) extends RequestSyntax[Yes, Nothing, Accept](client, dest, rb) {

  type Self = GetRequest[Accept]
  override type SelfAccepting[A <: Coproduct] = GetRequest[A]

  def withBuilder(newRequestBuilder: RequestBuilder[Yes, Nothing]) = copy(rb = newRequestBuilder)
  def withCharset(newCharset : Charset) = copy(charset = newCharset)
  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] =
    copy[ContentTypes]()
}

case class PostRequest[
  Content,
  WithContentType,
  ContentProvided <: Option[Content],
  Accept <: Coproduct
] private[featherbed] (
    private val client: Client,
    private val dest : String,
    private val rb: RequestBuilder[Yes, Nothing],
    private[featherbed] val content: Option[Content],
    charset: Charset = Charset.defaultCharset
  ) extends RequestSyntax[Yes, Nothing, Accept](client, dest, rb) {

  type Self = PostRequest[Content, WithContentType, ContentProvided, Accept]
  override type SelfAccepting[A <: Coproduct] = PostRequest[Content, WithContentType, ContentProvided, A]

  override protected def withBuilder(newRequestBuilder: RequestBuilder[Yes, Nothing]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset : Charset) = copy(charset = newCharset)

  def withForm(fields: (String, String)*)(implicit ev: Content =:= Nothing) =
    new FormPostRequest(client, dest, requestBuilder.addFormElement(fields:_*))

  def withFormFile(name: String, content: Buf, contentType: Option[String] = None, filename: Option[String] = None) =
    new FormPostRequest(client, dest, requestBuilder.add(FileElement(name, content, contentType, filename)))

  def withContent[A, ContentType <: featherbed.content.ContentType](newContent : A, contentType: ContentType)(implicit wit: Witness.Aux[contentType.type]) = {
    copy[A, contentType.type, Some[A], Accept](content = Some(newContent), rb = requestBuilder.setHeader("Content-Type", contentType + s"; charset=${charset.name}"))
  }

  def accept[ContentTypes <: Coproduct] : SelfAccepting[ContentTypes] =
    copy[Content, WithContentType, ContentProvided, ContentTypes]()
}

case class FormPostRequest[Accept <: Coproduct] private[featherbed] (
    private val client: Client,
    private val dest : String,
    private val rb: RequestBuilder[Yes, Yes],
    private[featherbed] val multipart : Boolean = false,
    charset: Charset = Charset.defaultCharset
  ) extends RequestSyntax[Yes, Yes, Accept](client, dest, rb) {

  type Self = FormPostRequest[Accept]
  override type SelfAccepting[A <: Coproduct] = FormPostRequest[A]

  override protected def withBuilder(newRequestBuilder: RequestBuilder[Yes, Yes]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset : Charset) = copy(charset = newCharset)

  def withForm(fields: (String, String)*) = copy(rb = requestBuilder.addFormElement(fields:_*))
  def withMultipart(multipart : Boolean) = copy(multipart = multipart)
  def accept[ContentTypes <: Coproduct] : SelfAccepting[ContentTypes] =
    copy[ContentTypes]()
}

case class PutRequest[
  Content,
  WithContentType <: featherbed.content.ContentType,
  ContentProvided <: Option[Content],
  Accept <: Coproduct
] private[featherbed] (
    private val client: Client,
    private val dest : String,
    private val rb: RequestBuilder[Yes, Nothing],
    private[featherbed] val multipart : Boolean = false,
    private[featherbed] val content: ContentProvided,
    charset: Charset = Charset.defaultCharset
  ) extends RequestSyntax[Yes, Nothing, Accept](client, dest, rb) {

  type Self = PutRequest[Content, WithContentType, ContentProvided, Accept]
  override type SelfAccepting[A <: Coproduct] = PutRequest[Content, WithContentType, ContentProvided, A]

  override protected def withBuilder(newRequestBuilder: RequestBuilder[Yes, Nothing]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset : Charset) = copy(charset = newCharset)

  def withContent[A, ContentType <: featherbed.content.ContentType](newContent : A, contentType: ContentType) =
    copy[A, contentType.type, Some[A], Accept](content = Some(newContent), rb = requestBuilder.setHeader("Content-Type", contentType + s"; charset=${charset.name}"))

  def accept[ContentTypes <: Coproduct] : SelfAccepting[ContentTypes] =
    copy[Content, WithContentType, ContentProvided, ContentTypes]()
}
