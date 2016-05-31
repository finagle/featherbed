package featherbed
package request

import java.io.File
import java.net.{URL, URLEncoder}
import java.nio.charset.{Charset, StandardCharsets}
import scala.language.experimental.macros

import featherbed.content.Encoder
import featherbed.littlemacros.CoproductMacros
import featherbed.support.{ContentTypeSupport, DecodeAll, RuntimeContentType}

import cats.data._, Xor._, Validated._
import cats.std.list._
import com.twitter.finagle.http._
import com.twitter.util.Future
import shapeless.{CNil, Coproduct, Witness}


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

trait RequestTypes { self: Client =>

  sealed trait RequestSyntax[Accept <: Coproduct, Self <: RequestSyntax[Accept, Self]] { self: Self =>

    val url: URL
    val charset: Charset
    val headers: List[(String, String)]

    def withHeader(name: String, value: String): Self = withHeaders((name, value))
    def withHeaders(headers: (String, String)*): Self
    def withCharset(charset: Charset): Self
    def withUrl(url: URL): Self

    def withQuery(query: String): Self = withUrl(new URL(url, url.getFile + "?" + query))
    def withQueryParams(params: List[(String, String)]): Self = withQuery(
      params.map {
        case (key, value) => URLEncoder.encode(key, charset.name) + "=" + URLEncoder.encode(value, charset.name)
      }.mkString("&")
    )
    def addQueryParams(params: List[(String, String)]): Self = withQuery(
      Option(url.getQuery).map(_ + "&").getOrElse("") + params.map {
        case (key, value) => URLEncoder.encode(key, charset.name) + "=" + URLEncoder.encode(value, charset.name)
      }.mkString("&")
    )

    def withQueryParams(params: (String, String)*): Self = withQueryParams(params.toList)
    def addQueryParams(params: (String, String)*): Self = addQueryParams(params.toList)


    protected[featherbed] def buildHeaders[HasUrl](
      rb: RequestBuilder[HasUrl, Nothing]
    ): RequestBuilder[HasUrl, Nothing] =
      headers.foldLeft(rb) {
        case (builder, (key, value)) => builder.addHeader(key, value)
      }

    protected[featherbed] def buildRequest(implicit
      canBuild: CanBuildRequest[Self]
    ): ValidatedNel[Throwable, Request] = canBuild.build(this: Self)

    /**
      * Send the request, decoding the response as [[K]]
      *
      * @tparam K The type to which the response will be decoded
      * @return A future which will contain a validated response
      */
    protected def sendRequest[K](implicit
      canBuild: CanBuildRequest[Self],
      decodeAll: DecodeAll[K, Accept]
    ): Future[Validated[InvalidResponse, K]] =
      buildRequest match {
        case Valid(req) => for {
          conn <- httpClient()
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

  }

  case class GetRequest[Accept <: Coproduct](
    url: URL,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8
  ) extends RequestSyntax[Accept, GetRequest[Accept]] {

    def accept[AcceptTypes <: Coproduct]: GetRequest[AcceptTypes] = copy[AcceptTypes]()
    def accept[AcceptTypes <: Coproduct](types: String*): GetRequest[AcceptTypes] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): GetRequest[Accept] = copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): GetRequest[Accept] = copy(charset = charset)
    def withUrl(url: URL): GetRequest[Accept] = copy(url = url)

    def send[K]()(implicit
      canBuild: CanBuildRequest[GetRequest[Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[Validated[InvalidResponse, K]] = sendRequest[K](canBuild, decodeAll)

  }

  case class PostRequest[Content, ContentType, Accept <: Coproduct] (
    url: URL,
    content: Content,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8
  ) extends RequestSyntax[Accept, PostRequest[Content, ContentType, Accept]] {

    def accept[AcceptTypes <: Coproduct]: PostRequest[Content, ContentType, AcceptTypes] =
      copy[Content, ContentType, AcceptTypes]()
    def accept[AcceptTypes <: Coproduct](types: String*): PostRequest[Content, ContentType, AcceptTypes] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): PostRequest[Content, ContentType, Accept] =
      copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): PostRequest[Content, ContentType, Accept] =
      copy(charset = charset)
    def withUrl(url: URL): PostRequest[Content, ContentType, Accept] =
      copy(url = url)

    def withContent[T, Type <: String](
      content: T,
      typ: Type)(implicit
      witness: Witness.Aux[typ.type]
    ): PostRequest[T, typ.type, Accept] =
      copy[T, typ.type, Accept](content = content)


    def withParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val firstElement = Valid(SimpleElement(first._1, first._2))
      val restElements = rest.toList.map {
        case (key, value) => Valid(SimpleElement(key, value))
      }
      FormPostRequest(
        url,
        Right(NonEmptyList(firstElement, restElements)),
        multipart = false,
        headers,
        charset)
    }

    def addParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      withParams(first, rest: _*)
    }

    def addFile[T, ContentType <: String](
      name: String,
      content: T,
      contentType: ContentType,
      filename: Option[String] = None)(implicit
      encoder: Encoder[T, ContentType]
    ): FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val element = encoder.apply(content, charset) map {
        buf => FileElement(name, buf, Some(contentType), filename)
      }
      FormPostRequest(
        url,
        Right(NonEmptyList(element, Nil)),
        multipart = true,
        headers,
        charset
      )
    }

    def send[K]()(implicit
      canBuild: CanBuildRequest[PostRequest[Content, ContentType, Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[Validated[InvalidResponse, K]] = sendRequest[K](canBuild, decodeAll)
  }

  case class FormPostRequest[
    Accept <: Coproduct,
    Elements <: Xor[None.type, NonEmptyList[ValidatedNel[Throwable, FormElement]]]
  ] (
    url: URL,
    form: Elements = Left(None),
    multipart: Boolean = false,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8
  ) extends RequestSyntax[Accept, FormPostRequest[Accept, Elements]] {

    def accept[AcceptTypes <: Coproduct]: FormPostRequest[AcceptTypes, Elements] =
      copy[AcceptTypes, Elements]()
    def accept[AcceptTypes <: Coproduct](types: String*): FormPostRequest[AcceptTypes, Elements] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): FormPostRequest[Accept, Elements] =
      copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): FormPostRequest[Accept, Elements] =
      copy(charset = charset)
    def withUrl(url: URL): FormPostRequest[Accept, Elements] =
      copy(url = url)
    def withMultipart(multipart: Boolean): FormPostRequest[Accept, Elements] =
      copy(multipart = multipart)

    private[request] def withParamsList(params: NonEmptyList[ValidatedNel[Throwable, FormElement]]) =
      copy[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]](
        form = Right(params)
      )

    def withParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val firstElement = Valid(SimpleElement(first._1, first._2))
      val restElements = rest.toList.map {
        case (key, value) => Valid(SimpleElement(key, value))
      }
      withParamsList(NonEmptyList(firstElement, restElements))
    }

    def addParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val firstElement = Valid(SimpleElement(first._1, first._2))
      val restElements = rest.toList.map {
        case (key, value) => Valid(SimpleElement(key, value)): ValidatedNel[Throwable, FormElement]
      }
      val newParams = NonEmptyList(firstElement, restElements)
      withParamsList(
        form match {
          case Left(None) => newParams
          case Right(currentParams) => newParams combine currentParams
        })
    }

    def addFile[T, ContentType <: String](
      name: String,
      content: T,
      contentType: ContentType,
      filename: Option[String] = None)(implicit
      encoder: Encoder[T, ContentType]
    ): FormPostRequest[Accept, Right[NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val element = encoder.apply(content, charset) map {
        buf => FileElement(name, buf, Some(contentType), filename)
      }
      withParamsList(NonEmptyList(element, form.fold(_ => List.empty, _.unwrap)))
    }

    def send[K]()(implicit
      canBuild: CanBuildRequest[FormPostRequest[Accept, Elements]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[Validated[InvalidResponse, K]] = sendRequest[K](canBuild, decodeAll)
  }

  case class PutRequest[Content, ContentType, Accept <: Coproduct](
    url: URL,
    content: Content,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8
  ) extends RequestSyntax[Accept, PutRequest[Content, ContentType, Accept]] {

    def accept[AcceptTypes <: Coproduct]: PutRequest[Content, ContentType, AcceptTypes] =
      copy[Content, ContentType, AcceptTypes]()
    def accept[AcceptTypes <: Coproduct](types: String*): PutRequest[Content, ContentType, AcceptTypes] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): PutRequest[Content, ContentType, Accept] =
      copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): PutRequest[Content, ContentType, Accept] =
      copy(charset = charset)
    def withUrl(url: URL): PutRequest[Content, ContentType, Accept] =
      copy(url = url)

    def withContent[T, Type <: String](
      content: T,
      typ: Type)(implicit
      witness: Witness.Aux[typ.type]
    ): PutRequest[T, typ.type, Accept] =
      copy[T, typ.type, Accept](content = content)

    def send[K]()(implicit
      canBuild: CanBuildRequest[PutRequest[Content, ContentType, Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[Validated[InvalidResponse, K]] = sendRequest[K](canBuild, decodeAll)
  }

  case class HeadRequest(
    url: URL,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8
  ) extends RequestSyntax[Nothing, HeadRequest] {

    def withHeaders(addHeaders: (String, String)*): HeadRequest = copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): HeadRequest = copy(charset = charset)
    def withUrl(url: URL): HeadRequest = copy(url = url)

    def send[Response]()(implicit
      canBuild: CanBuildRequest[HeadRequest],
      decodeAll: DecodeAll[Response, Nothing]
    ): Future[Validated[InvalidResponse, Response]] = sendRequest[Response](canBuild, decodeAll)
  }

  case class DeleteRequest[Accept <: Coproduct](
    url: URL,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8
  ) extends RequestSyntax[Accept, DeleteRequest[Accept]] {

    def accept[AcceptTypes <: Coproduct]: DeleteRequest[AcceptTypes] = copy[AcceptTypes]()
    def accept[AcceptTypes <: Coproduct](types: String*): DeleteRequest[AcceptTypes] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): DeleteRequest[Accept] =
      copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): DeleteRequest[Accept] = copy(charset = charset)
    def withUrl(url: URL): DeleteRequest[Accept] = copy(url = url)

    def send[K]()(implicit
      canBuild: CanBuildRequest[DeleteRequest[Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[Validated[InvalidResponse, K]] = sendRequest[K](canBuild, decodeAll)
  }

}
