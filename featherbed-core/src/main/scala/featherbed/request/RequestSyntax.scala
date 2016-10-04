package featherbed
package request

import java.io.File
import java.net.{URL, URLEncoder}
import java.nio.charset.{Charset, StandardCharsets}
import scala.language.experimental.macros

import cats.data._, Validated._
import cats.implicits._
import cats.instances.list._
import com.twitter.finagle.Filter
import com.twitter.finagle.http._
import com.twitter.finagle.http.Status._
import com.twitter.util.Future
import featherbed.content.{Decoder, Encoder}
import featherbed.littlemacros.CoproductMacros
import featherbed.support.{ContentType, DecodeAll, RuntimeContentType}
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
case class InvalidResponse(response: Response, reason: String) extends Throwable(reason)
case class ErrorResponse(request: Request, response: Response) extends Throwable("Error response received")

trait RequestTypes { self: Client =>

  sealed trait RequestSyntax[Accept <: Coproduct, Self <: RequestSyntax[Accept, Self]] { self: Self =>

    val url: URL
    val charset: Charset
    val headers: List[(String, String)]
    val filters: Filter[Request, Response, Request, Response]

    def withHeader(name: String, value: String): Self = withHeaders((name, value))
    def withHeaders(headers: (String, String)*): Self
    def withCharset(charset: Charset): Self
    def withUrl(url: URL): Self
    def addFilter(filter: Filter[Request, Response, Request, Response]): Self
    def resetFilters: Self
    def setFilters(filter: Filter[Request, Response, Request, Response]): Self = resetFilters.addFilter(filter)

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

    private def cloneRequest(in: Request) = {
      val out = Request()
      out.uri = in.uri
      out.content = in.content
      in.headerMap.foreach {
        case (k, v) => out.headerMap.put(k, v)
      }
      out
    }

    private def handleRequest(request: Request, numRedirects: Int = 0): Future[Response] =
      (filters andThen httpClient)(request) flatMap {
      rep => rep.status match {
        case Continue =>
          Future.exception(InvalidResponse(
            rep,
            "Received unexpected 100/Continue, but request body was already sent."
          ))
        case SwitchingProtocols =>
          Future.exception(InvalidResponse(
            rep,
            "Received unexpected 101/Switching Protocols, but no switch was requested."
          ))
        case s if s.code >= 200 && s.code < 300 =>
          Future(rep)
        case MultipleChoices =>
          Future.exception(InvalidResponse(rep, "300/Multiple Choices is not yet supported in featherbed."))
        case MovedPermanently | Found | SeeOther | TemporaryRedirect =>
          val attempt = for {
            tooMany <- if (numRedirects > 5)
                Left("Too many redirects; giving up")
              else
                Right(())
            location <- Either.fromOption(
              rep.headerMap.get("Location"),
              "Redirect required, but location header not present")
            newUrl <- Either.catchNonFatal(url.toURI.resolve(location))
              .leftMap(_ => s"Could not resolve Location $location")
            canHandle <- if (newUrl.getHost != url.getHost)
                Left("Location points to another host; this isn't supported by featherbed")
              else
                Right(())
          } yield {
            val newReq = cloneRequest(request)
            newReq.uri = List(Option(newUrl.getPath), Option(newUrl.getQuery).map("?" + _)).flatten.mkString
            handleRequest(newReq, numRedirects + 1)
          }
          attempt.fold(
            err => Future.exception(InvalidResponse(rep, err)),
            identity
          )
        case other => Future.exception(ErrorResponse(request, rep))
      }
    }


    protected def decodeResponse[T](rep: Response)(implicit decodeAll: DecodeAll[T, Accept]) =
      rep.contentType flatMap ContentType.contentTypePieces match {
        case None => Future.exception(InvalidResponse(rep, "Content-Type header is not present"))
        case Some(RuntimeContentType(mediaType, _)) => decodeAll.instances.find(_.contentType == mediaType) match {
          case Some(decoder) =>
            decoder(rep) match {
              case Valid(decoded) =>
                Future(decoded)
              case Invalid(errs) =>
                Future.exception(InvalidResponse(rep, errs.map(_.getMessage).toList.mkString("; ")))
            }
          case None =>
            Future.exception(InvalidResponse(rep, s"No decoder was found for $mediaType"))
        }
      }

    /**
      * Send the request, decoding the response as [[K]]
      *
      * @tparam K The type to which the response will be decoded
      * @return A future which will contain a validated response
      */
    protected def sendRequest[K](implicit
      canBuild: CanBuildRequest[Self],
      decodeAll: DecodeAll[K, Accept]
    ): Future[K] =
      buildRequest match {
        case Valid(req) => handleRequest(req).flatMap { rep =>
          rep.contentType.getOrElse("*/*") match {
            case ContentType(RuntimeContentType(mediaType, _)) =>
              decodeAll.findInstance(mediaType) match {
                case Some(decoder) =>
                  decoder(rep)
                    .leftMap(errs => InvalidResponse(rep, errs.map(_.getMessage).toList.mkString("; ")))
                    .fold(
                      Future.exception(_),
                      Future(_)
                    )
                case None =>
                  Future.exception(InvalidResponse(rep, s"No decoder was found for $mediaType"))
              }
            case other => Future.exception(InvalidResponse(rep, s"Content-Type $other is not valid"))
          }
        }
        case Invalid(errs) => Future.exception(RequestBuildingError(errs))
      }

    protected def sendZipRequest[Error, Success](implicit
      canBuild: CanBuildRequest[Self],
      decodeAllSuccess: DecodeAll[Success, Accept],
      decodeAllError: DecodeAll[Error, Accept]
    ): Future[(Either[Error, Success], Response)] = buildRequest match {
      case Valid(req) => handleRequest(req)
        .flatMap {
          rep => decodeResponse[Success](rep).map(Right[Error, Success]).map((_, rep))
        }.rescue {
          case ErrorResponse(_, rep) => decodeResponse[Error](rep).map(Left[Error, Success]).map((_, rep))
        }
      case Invalid(errs) => Future.exception(RequestBuildingError(errs))
    }

    protected def sendRequest[Error, Success](implicit
      canBuild: CanBuildRequest[Self],
      decodeAllSuccess: DecodeAll[Success, Accept],
      decodeAllError: DecodeAll[Error, Accept]
    ): Future[Either[Error, Success]] =
      sendZipRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError).map(_._1)

  }

  case class GetRequest[Accept <: Coproduct](
    url: URL,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8,
    filters: Filter[Request, Response, Request, Response]
  ) extends RequestSyntax[Accept, GetRequest[Accept]] {

    def accept[AcceptTypes <: Coproduct]: GetRequest[AcceptTypes] = copy[AcceptTypes]()
    def accept[AcceptTypes <: Coproduct](types: String*): GetRequest[AcceptTypes] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): GetRequest[Accept] = copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): GetRequest[Accept] = copy(charset = charset)
    def withUrl(url: URL): GetRequest[Accept] = copy(url = url)
    def addFilter(filter: Filter[Request, Response, Request, Response]): GetRequest[Accept] =
      copy(filters = filter andThen filters)
    def resetFilters: GetRequest[Accept] = copy(filters = Filter.identity[Request, Response])

    def send[K]()(implicit
      canBuild: CanBuildRequest[GetRequest[Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[K] = sendRequest[K](canBuild, decodeAll)

    def send[Error, Success]()(implicit
      canBuild: CanBuildRequest[GetRequest[Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[Either[Error, Success]] =
      sendRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)

    def sendZip[Error, Success]()(implicit
      canBuild: CanBuildRequest[GetRequest[Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[(Either[Error, Success], Response)] =
      sendZipRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)
  }

  case class PostRequest[Content, ContentType, Accept <: Coproduct] (
    url: URL,
    content: Content,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8,
    filters: Filter[Request, Response, Request, Response]
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
    def addFilter(filter: Filter[Request, Response, Request, Response]): PostRequest[Content, ContentType, Accept] =
      copy(filters = filter andThen filters)
    def resetFilters: PostRequest[Content, ContentType, Accept] = copy(filters = Filter.identity[Request, Response])

    def withContent[T, Type <: String](
      content: T,
      typ: Type)(implicit
      witness: Witness.Aux[typ.type]
    ): PostRequest[T, typ.type, Accept] =
      copy[T, typ.type, Accept](content = content)


    def withParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val firstElement = Valid(SimpleElement(first._1, first._2))
      val restElements = rest.toList.map {
        case (key, value) => Valid(SimpleElement(key, value))
      }
      FormPostRequest(
        url,
        Right(NonEmptyList(firstElement, restElements)),
        multipart = false,
        headers,
        charset,
        filters
      )
    }

    def addParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      withParams(first, rest: _*)
    }

    def addFile[T, ContentType <: String](
      name: String,
      content: T,
      contentType: ContentType,
      filename: Option[String] = None)(implicit
      encoder: Encoder[T, ContentType]
    ): FormPostRequest[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val element = encoder.apply(content, charset) map {
        buf => FileElement(name, buf, Some(contentType), filename)
      }
      FormPostRequest(
        url,
        Right(NonEmptyList(element, Nil)),
        multipart = true,
        headers,
        charset,
        filters
      )
    }

    def send[K]()(implicit
      canBuild: CanBuildRequest[PostRequest[Content, ContentType, Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[K] = sendRequest[K](canBuild, decodeAll)

    def send[Error, Success]()(implicit
      canBuild: CanBuildRequest[PostRequest[Content, ContentType, Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[Either[Error, Success]] = sendRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)

    def sendZip[Error, Success]()(implicit
      canBuild: CanBuildRequest[PostRequest[Content, ContentType, Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[(Either[Error, Success], Response)] =
      sendZipRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)
  }

  case class FormPostRequest[
    Accept <: Coproduct,
    Elements <: Either[None.type, NonEmptyList[ValidatedNel[Throwable, FormElement]]]
  ] (
    url: URL,
    form: Elements = Left(None),
    multipart: Boolean = false,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8,
    filters: Filter[Request, Response, Request, Response]
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
    def addFilter(filter: Filter[Request, Response, Request, Response]): FormPostRequest[Accept, Elements] =
      copy(filters = filter andThen filters)
    def resetFilters: FormPostRequest[Accept, Elements] = copy(filters = Filter.identity[Request, Response])

    private[request] def withParamsList(params: NonEmptyList[ValidatedNel[Throwable, FormElement]]) =
      copy[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]](
        form = Right(params)
      )

    def withParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val firstElement = Valid(SimpleElement(first._1, first._2))
      val restElements = rest.toList.map {
        case (key, value) => Valid(SimpleElement(key, value))
      }
      withParamsList(NonEmptyList(firstElement, restElements))
    }

    def addParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val firstElement = Valid(SimpleElement(first._1, first._2))
      val restElements = rest.toList.map {
        case (key, value) => Valid(SimpleElement(key, value)): ValidatedNel[Throwable, FormElement]
      }
      val newParams = NonEmptyList(firstElement, restElements)
      withParamsList(
        form match {
          case Left(None) => newParams
          case Right(currentParams) => newParams concat currentParams
        })
    }

    def addFile[T, ContentType <: String](
      name: String,
      content: T,
      contentType: ContentType,
      filename: Option[String] = None)(implicit
      encoder: Encoder[T, ContentType]
    ): FormPostRequest[Accept, Right[Nothing, NonEmptyList[ValidatedNel[Throwable, FormElement]]]] = {
      val element = encoder.apply(content, charset) map {
        buf => FileElement(name, buf, Some(contentType), filename)
      }
      withParamsList(NonEmptyList(element, form.fold(_ => List.empty, _.toList)))
    }

    def send[K]()(implicit
      canBuild: CanBuildRequest[FormPostRequest[Accept, Elements]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[K] = sendRequest[K](canBuild, decodeAll)

    def send[Error, Success]()(implicit
      canBuild: CanBuildRequest[FormPostRequest[Accept, Elements]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[Either[Error, Success]] = sendRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)

    def sendZip[Error, Success]()(implicit
      canBuild: CanBuildRequest[FormPostRequest[Accept, Elements]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[(Either[Error, Success], Response)] =
      sendZipRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)
  }

  case class PutRequest[Content, ContentType, Accept <: Coproduct](
    url: URL,
    content: Content,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8,
    filters: Filter[Request, Response, Request, Response]
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
    def addFilter(filter: Filter[Request, Response, Request, Response]): PutRequest[Content, ContentType, Accept] =
      copy(filters = filter andThen filters)
    def resetFilters: PutRequest[Content, ContentType, Accept] = copy(filters = Filter.identity[Request, Response])

    def withContent[T, Type <: String](
      content: T,
      typ: Type)(implicit
      witness: Witness.Aux[typ.type]
    ): PutRequest[T, typ.type, Accept] =
      copy[T, typ.type, Accept](content = content)

    def send[K]()(implicit
      canBuild: CanBuildRequest[PutRequest[Content, ContentType, Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[K] = sendRequest[K](canBuild, decodeAll)

    def send[Error, Success]()(implicit
      canBuild: CanBuildRequest[PutRequest[Content, ContentType, Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[Either[Error, Success]] = sendRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)

    def sendZip[Error, Success]()(implicit
      canBuild: CanBuildRequest[PutRequest[Content, ContentType, Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[(Either[Error, Success], Response)] =
      sendZipRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)
  }

  case class HeadRequest(
    url: URL,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8,
    filters: Filter[Request, Response, Request, Response]
  ) extends RequestSyntax[Nothing, HeadRequest] {

    def withHeaders(addHeaders: (String, String)*): HeadRequest = copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): HeadRequest = copy(charset = charset)
    def withUrl(url: URL): HeadRequest = copy(url = url)
    def addFilter(filter: Filter[Request, Response, Request, Response]): HeadRequest =
      copy(filters = filter andThen filters)
    def resetFilters: HeadRequest = copy(filters = Filter.identity[Request, Response])

    def send()(implicit
      canBuild: CanBuildRequest[HeadRequest],
      decodeAll: DecodeAll[Response, Nothing]
    ): Future[Response] = sendRequest[Response](canBuild, decodeAll)
  }

  case class DeleteRequest[Accept <: Coproduct](
    url: URL,
    headers: List[(String, String)] = List.empty,
    charset: Charset = StandardCharsets.UTF_8,
    filters: Filter[Request, Response, Request, Response]
  ) extends RequestSyntax[Accept, DeleteRequest[Accept]] {

    def accept[AcceptTypes <: Coproduct]: DeleteRequest[AcceptTypes] = copy[AcceptTypes]()
    def accept[AcceptTypes <: Coproduct](types: String*): DeleteRequest[AcceptTypes] =
      macro CoproductMacros.callAcceptCoproduct
    def withHeaders(addHeaders: (String, String)*): DeleteRequest[Accept] =
      copy(headers = headers ::: addHeaders.toList)
    def withCharset(charset: Charset): DeleteRequest[Accept] = copy(charset = charset)
    def withUrl(url: URL): DeleteRequest[Accept] = copy(url = url)
    def addFilter(filter: Filter[Request, Response, Request, Response]): DeleteRequest[Accept] =
      copy(filters = filter andThen filters)
    def resetFilters: DeleteRequest[Accept] = copy(filters = Filter.identity[Request, Response])

    def send[K]()(implicit
      canBuild: CanBuildRequest[DeleteRequest[Accept]],
      decodeAll: DecodeAll[K, Accept]
    ): Future[K] = sendRequest[K](canBuild, decodeAll)

    def send[Error, Success]()(implicit
      canBuild: CanBuildRequest[DeleteRequest[Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[Either[Error, Success]] = sendRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)

    def sendZip[Error, Success]()(implicit
      canBuild: CanBuildRequest[DeleteRequest[Accept]],
      decodeAllError: DecodeAll[Error, Accept],
      decodeAllSuccess: DecodeAll[Success, Accept]
    ): Future[(Either[Error, Success], Response)] =
      sendZipRequest[Error, Success](canBuild, decodeAllSuccess, decodeAllError)
  }

}
