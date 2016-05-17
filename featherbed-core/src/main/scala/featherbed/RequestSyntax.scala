package featherbed

import java.net.URL
import java.nio.charset.Charset
import scala.annotation.implicitNotFound
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.util.Try

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.data.Validated._
import cats.std.list._
import com.twitter.finagle.http._
import com.twitter.finagle.http.RequestConfig._
import com.twitter.io.Buf
import com.twitter.util.Future
import featherbed.support.{AcceptHeader, ContentTypeSupport, DecodeAll, RuntimeContentType}
import shapeless._

case class RequestBuildingError(errors: NonEmptyList[Throwable])
  extends Throwable(s"Failed to build request: ${errors.unwrap.mkString(";")}")

/**
  * Represents evidence that the request can be built.  For requests that include content,
  * this requires that an implicit [[content.Encoder]] is available for the given Content-Type.
  *
  * @tparam T request type
  */
@implicitNotFound("""The request of type $T cannot be built.  This is most likely because either:
  1. If the request is a POST, PUT, or PATCH request:
     a. The request requires a Content-Type to be defined, but one was not defined (using the withContent method)
     b. An Encoder is required for the request's Content-Type, but one was not available in implicit scope. This is
        usually a matter of importing the right module to obtain the Encoder instance.
  2. Something is missing from featherbed""")
sealed trait CanBuildRequest[T] {
  def build(t: T): ValidatedNel[Throwable, Request]
}

object CanBuildRequest {
  implicit def canBuildGetRequest[Accept <: Coproduct](implicit
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[GetRequest[Accept]] = new CanBuildRequest[GetRequest[Accept]] {
    def build(getRequest: GetRequest[Accept]) =
      getRequest.buildURL.map[Request] { url: URL =>
        getRequest
          .requestBuilder
          .url(url)
          .addHeader("Accept", accept.toString)
          .buildGet()
      }
  }

  implicit def canBuildPostRequestWithContentBuffer[Accept <: Coproduct, CT <: content.ContentType](implicit
    accept: AcceptHeader[Accept],
    witness: Witness.Aux[CT]
  ): CanBuildRequest[PostRequest[Buf, CT, Some[Buf], Accept]] =
    new CanBuildRequest[PostRequest[Buf, CT, Some[Buf], Accept]] {
      def build(postRequest: PostRequest[Buf, CT, Some[Buf], Accept]) =
      postRequest.buildURL.map[Request] { url: URL =>
        postRequest
          .requestBuilder
          .url(url)
          .addHeader("Accept", accept.toString)
          // I justify this Option#get because it is known at a type level to be Some.
          .buildPost(postRequest.content.get)
      }
    }

  implicit def canBuildFormPostRequest[Accept <: Coproduct](implicit
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[FormPostRequest[Accept]] = new CanBuildRequest[FormPostRequest[Accept]] {
    def build(formPostRequest: FormPostRequest[Accept]) =
      formPostRequest.buildURL.map[Request] { url: URL =>
        formPostRequest
          .requestBuilder
          .url(url)
          .addHeader("Accept", accept.toString)
          .buildFormPost(formPostRequest.multipart)
      }
  }

  implicit def canBuildPutRequest[Accept <: Coproduct, CT <: content.ContentType](implicit
    accept: AcceptHeader[Accept],
    witness: Witness.Aux[CT]
  ): CanBuildRequest[PutRequest[Buf, CT, Some[Buf], Accept]] =
    new CanBuildRequest[PutRequest[Buf, CT, Some[Buf], Accept]] {
      def build(putRequest: PutRequest[Buf, CT, Some[Buf], Accept]) =
      putRequest.buildURL.map[Request] { url: URL =>
        putRequest
          .requestBuilder
          .url(url)
          .addHeader("Accept", accept.toString)
          .buildPut(putRequest.content.get)  //Again, we know content is Some at type level
      }
    }

  implicit val canBuildHeadRequest = new CanBuildRequest[HeadRequest] {
    def build(headRequest: HeadRequest) = headRequest.buildURL.map[Request] { url: URL =>
      headRequest.requestBuilder.url(url).buildHead()
    }
  }

  implicit def canBuildDeleteRequest[Accept <: Coproduct](implicit
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[DeleteRequest[Accept]] = new CanBuildRequest[DeleteRequest[Accept]] {
    def build(deleteRequest: DeleteRequest[Accept]) =
      deleteRequest.buildURL.map[Request] { url: URL =>
        deleteRequest
          .requestBuilder
          .url(url)
          .addHeader("Accept", accept.toString)
          .buildDelete()
      }
  }

  implicit def canBuildPostRequestWithEncoder[Accept <: Coproduct, A, CT <: content.ContentType](implicit
    encoder: content.Encoder[A, CT],
    witness: Witness.Aux[CT],
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[PostRequest[A, CT, Some[A], Accept]] =
    new CanBuildRequest[PostRequest[A, CT, Some[A], Accept]] {
      def build(postRequest: PostRequest[A, CT, Some[A], Accept]) =
        postRequest.buildURL.ap[NonEmptyList[Throwable], Request] {
          encoder(postRequest.content.get, postRequest.charset).map[URL => Request] { buf: Buf => url: URL =>
            postRequest
              .requestBuilder
              .url(url)
              .addHeader("Accept", accept.toString)
              .buildPost(buf)
          }
        }
    }

  implicit def canBuildPutRequestWithEncoder[Accept <: Coproduct, A, CT <: content.ContentType](implicit
    encoder: content.Encoder[A, CT],
    witness: Witness.Aux[CT],
    accept: AcceptHeader[Accept]
  ): CanBuildRequest[PutRequest[A, CT, Some[A], Accept]] =
      new CanBuildRequest[PutRequest[A, CT, Some[A], Accept]] {
        def build(putRequest: PutRequest[A, CT, Some[A], Accept]) =
          putRequest.buildURL.ap[NonEmptyList[Throwable], Request] {
            encoder(putRequest.content.get, putRequest.charset).map[URL => Request] { buf: Buf => url: URL =>
              putRequest
                .requestBuilder
                .url(url)
                .addHeader("Accept", accept.toString)
                .buildPut(buf)
            }
          }
      }
}

/**
  * Represents a [[Response]] which was not valid, because:
  * 1. It did not conform to the specification of the `Accept` header that was specified in the request, and/or
  * 2. The Content-Type was missing or there was no available decoder, or
  * 3. Decoding of the request failed
  *
  * @param response The original [[Response]], so it can be further processed if desired
  * @param reason A [[String]] describing why the response was invalid
  */
case class InvalidResponse(response: Response, reason: String)

/**
  * Forms the base for specifying HTTP Requests.  This class should not be used directly.
  *
  * @param client The [[Client]] object that originated the request specification
  * @param url The destination of the request
  * @param requestBuilder The finagle [[RequestBuilder]] that is building the request
  * @tparam HasUrl Whether the URL has been specified
  * @tparam HasForm Whether content has been specified
  * @tparam Accept The Content-Type(s) which will be sent in the Accept header
  */
abstract class RequestSyntax[HasUrl, HasForm, Accept <: Coproduct] (
  client: Client,
  url: URL,
  path: String,
  params: Map[String, String],
  protected[featherbed] val requestBuilder: RequestBuilder[HasUrl, HasForm]
) {

  type Self <: RequestSyntax[HasUrl, HasForm, Accept]
  type SelfAccepting[A <: Coproduct]
  val charset: Charset

  /**
    * Send the request, decoding the response as [[K]]
    *
    * @tparam K The type to which the response will be decoded
    * @return A future which will contain a validated response
    */
  def send[K]()(implicit ev: this.type <:< Self,
    canBuild: CanBuildRequest[Self],
    decodeAll: DecodeAll[K, Accept]
  ): Future[Validated[InvalidResponse, K]] =
    buildRequest match {
      case Valid(req) => for {
        conn <- client.httpClient()
        rep <- conn(req)
      } yield {
        rep.contentType flatMap ContentTypeSupport.contentTypePieces match {
          case None => Invalid(InvalidResponse(rep, "Content-Type header is not present"))
          case Some(RuntimeContentType(mediaType, _)) =>
            decodeAll.instances.find(_.contentType == mediaType) match {
              case Some(decoder) =>
                decoder(rep).leftMap(errs => InvalidResponse(rep, errs.unwrap.map(_.getMessage).mkString("; ")))
              case None =>
                Invalid(InvalidResponse(rep, s"No decoder was found for $mediaType"))
            }
        }
      }
      case Invalid(errs) => Future.exception(RequestBuildingError(errs))
    }

  def flatMap[K](f: Response => Future[K])(implicit
    canBuild: CanBuildRequest[Self],
    ev: this.type <:< Self
  ): Future[K] = buildRequest match {
    case Valid(req) => for {
      conn <- client.httpClient()
      rep <- conn(req)
      p <- f(rep)
    } yield p
    case Invalid(errs) => Future.exception(RequestBuildingError(errs))
  }


  def map[K](f: Response => K)(implicit
    canBuild: CanBuildRequest[Self],
    ev: this.type <:< Self
  ): Future[K] = buildRequest match {
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
  protected[featherbed] def buildRequest(implicit
    canBuild: CanBuildRequest[Self],
    ev: this.type <:< Self
  ): ValidatedNel[Throwable, Request] = canBuild.build(this: Self)

  protected[featherbed] def buildURL: ValidatedNel[Throwable, URL] =
    Validated.fromTry[URL](Try(new URL(Request.queryString(url.toString + path, params)))).toValidatedNel

  def withHeader(name: String, value: String): Self = withBuilder(requestBuilder.addHeader(name, value))
  def withHeaders(headers: (String, String)*): Self = withBuilder(requestBuilder = headers.foldLeft(requestBuilder) {
    case (b, (k, v)) => b.addHeader(k, v)
  })

  /**
    * Specify which content types are accepted, using type syntax (i.e. Coproduct.`"text/plain","text/html"`.T)
    * The given content types will be included in the Accept header.  If the request already specified content
    * types, they will be replaced.
    *
    * @tparam ContentTypes The content types, specified as a shapeless.Coproduct of singleton literals
    * @return A request specification using the given accepted content types
    */
  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes]

  /**
    * Specify which content types are accepted, using literal syntax.  The strings passed in will be lifted to a
    * Coproduct type.
    *
    * The given content types will be included in the Accept header.  If the request already specified content
    * types, they will be replaced.
    *
    * @param types The MIME types to accept, given as Strings (i.e. "text/plain")
    * @return A request specification using the given accepted content types
    */
  def accept[T <: Coproduct](types: String*): SelfAccepting[T] = macro littlemacros.CoproductMacros.callAcceptCoproduct

  /**
    * Change the character set to use for sending the request
    *
    * @param charset The new [[Charset]]
    * @return A request specification which will use the given character set to send the request
    */
  def withCharset(charset: Charset): Self
}

private[featherbed] case class GetRequest[Accept <: Coproduct] (
    private val client: Client,
    private val url: URL,
    private val path: String,
    private val params: Map[String, String],
    private val rb: RequestBuilder[Nothing, Nothing],
    charset: Charset = Charset.defaultCharset
) extends RequestSyntax[Nothing, Nothing, Accept](client, url, path, params, rb) {

  type Self = GetRequest[Accept]
  override type SelfAccepting[A <: Coproduct] = GetRequest[A]

  def withBuilder(newRequestBuilder: RequestBuilder[Nothing, Nothing]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset: Charset): Self = copy(charset = newCharset)

  def withParams(newParams: Map[String, String]): Self = copy[Accept](params = params ++ newParams)

  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] =
    copy[ContentTypes]()
}

private[featherbed] case class PostRequest[
  Content,
  WithContentType,
  ContentProvided <: Option[Content],
  Accept <: Coproduct
] (
    private val client: Client,
    private val url: URL,
    private val path: String,
    private val params: Map[String, String],
    private val rb: RequestBuilder[Nothing, Nothing],
    private[featherbed] val content: Option[Content],
    charset: Charset = Charset.defaultCharset
) extends RequestSyntax[Nothing, Nothing, Accept](client, url, path, params, rb) {

  type Self = PostRequest[Content, WithContentType, ContentProvided, Accept]
  override type SelfAccepting[A <: Coproduct] = PostRequest[Content, WithContentType, ContentProvided, A]

  override protected def withBuilder(newRequestBuilder: RequestBuilder[Nothing, Nothing]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset: Charset): Self = copy(charset = newCharset)

  def withForm(fields: (String, String)*)(implicit ev: Content =:= Nothing): FormPostRequest[Accept] =
    new FormPostRequest(client, url, path, params, requestBuilder.addFormElement(fields: _*))

  def withFormFile(
    name: String,
    content: Buf,
    contentType: Option[String] = None,
    filename: Option[String] = None
  ): FormPostRequest[Accept] = new FormPostRequest(
    client,
    url,
    path,
    params,
    requestBuilder.add(FileElement(name, content, contentType, filename))
  )

  def withContent[A, ContentType <: featherbed.content.ContentType](
    newContent: A, contentType: ContentType
  )(implicit wit: Witness.Aux[contentType.type]): PostRequest[A, contentType.type, Some[A], Accept] = {
    copy[A, contentType.type, Some[A], Accept](
      content = Some(newContent),
      rb = requestBuilder.setHeader("Content-Type", contentType + s"; charset=${charset.name}")
    )
  }

  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] =
    copy[Content, WithContentType, ContentProvided, ContentTypes]()
}

private[featherbed] case class FormPostRequest[Accept <: Coproduct] (
    private val client: Client,
    private val url: URL,
    private val path: String,
    private val params: Map[String, String],
    private val rb: RequestBuilder[Nothing, Yes],
    private[featherbed] val multipart: Boolean = false,
    charset: Charset = Charset.defaultCharset
) extends RequestSyntax[Nothing, Yes, Accept](client, url, path, params, rb) {

  type Self = FormPostRequest[Accept]
  override type SelfAccepting[A <: Coproduct] = FormPostRequest[A]

  override protected def withBuilder(newRequestBuilder: RequestBuilder[Nothing, Yes]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset: Charset): Self = copy(charset = newCharset)

  def withForm(fields: (String, String)*): Self =
    copy(rb = requestBuilder.addFormElement(fields: _*))

  def withMultipart(multipart: Boolean): Self = copy(multipart = multipart)

  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] =
    copy[ContentTypes]()
}

private[featherbed] case class PutRequest[
  Content,
  WithContentType,
  ContentProvided <: Option[Content],
  Accept <: Coproduct
] (
    private val client: Client,
    private val url: URL,
    private val path: String,
    private val params: Map[String, String],
    private val rb: RequestBuilder[Nothing, Nothing],
    private[featherbed] val multipart: Boolean = false,
    private[featherbed] val content: ContentProvided,
    charset: Charset = Charset.defaultCharset
) extends RequestSyntax[Nothing, Nothing, Accept](client, url, path, params, rb) {

  type Self = PutRequest[Content, WithContentType, ContentProvided, Accept]
  override type SelfAccepting[A <: Coproduct] = PutRequest[Content, WithContentType, ContentProvided, A]

  protected def withBuilder(newRequestBuilder: RequestBuilder[Nothing, Nothing]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset: Charset): Self = copy(charset = newCharset)

  def withContent[A, ContentType <: featherbed.content.ContentType](
    newContent: A, contentType: ContentType
  ): PutRequest[A, contentType.type, Some[A], Accept] =
    copy[A, contentType.type, Some[A], Accept](
      content = Some(newContent),
      rb = requestBuilder.setHeader("Content-Type", contentType + s"; charset=${charset.name}")
    )

  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] =
    copy[Content, WithContentType, ContentProvided, ContentTypes]()
}

private[featherbed] case class HeadRequest (
  private val client: Client,
  private val url: URL,
  private val path: String,
  private val params: Map[String, String],
  private val rb: RequestBuilder[Nothing, Nothing],
  charset: Charset = Charset.defaultCharset
) extends RequestSyntax[Nothing, Nothing, Coproduct.`"*/*"`.T](client, url, path, params, rb) {

  type Self = HeadRequest
  override type SelfAccepting[A] = Self

  // Doesn't make sense to Accept in a HEAD request
  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] = this

  protected def withBuilder(newRequestBuilder: RequestBuilder[Nothing, Nothing]): HeadRequest =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset: Charset): HeadRequest = copy(charset = newCharset)
}

private[featherbed] case class DeleteRequest[Accept <: Coproduct] (
  private val client: Client,
  private val url: URL,
  private val path: String,
  private val params: Map[String, String],
  private val rb: RequestBuilder[Nothing, Nothing],
  charset: Charset = Charset.defaultCharset
) extends RequestSyntax[Nothing, Nothing, Accept](client, url, path, params, rb) {

  type Self = DeleteRequest[Accept]
  type SelfAccepting[A <: Coproduct] = DeleteRequest[A]

  def withBuilder(newRequestBuilder: RequestBuilder[Nothing, Nothing]): Self =
    copy(rb = newRequestBuilder)

  def withCharset(newCharset: Charset): Self = copy(charset = newCharset)

  def accept[ContentTypes <: Coproduct]: SelfAccepting[ContentTypes] = copy[ContentTypes]()
}
