package featherbed.request

import java.net.URL
import java.nio.charset.Charset

import scala.language.experimental.macros

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.implicits._
import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.http.Status._
import com.twitter.util.Future
import featherbed.Client
import featherbed.content.{Form, MimeContent, MultipartForm}
import featherbed.littlemacros.CoproductMacros
import featherbed.support.{ContentType, DecodeAll, RuntimeContentType}
import shapeless.{CNil, Coproduct, HList, Witness}

case class ClientRequest[Meth <: Method, Accept <: Coproduct, Content, ContentType](
  request: HTTPRequest[Meth, Accept, Content, ContentType],
  client: Client
) {

  def accept[A <: Coproduct]: ClientRequest[Meth, A, Content, ContentType] =
    copy[Meth, A, Content, ContentType](request = request.accept[A])

  def accept[A <: Coproduct](types: String*): ClientRequest[Meth, A, Content, ContentType] =
    macro CoproductMacros.callAcceptCoproduct

  def withCharset(charset: Charset): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.withCharset(charset))

  def withHeader(name: String, value: String): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.withHeaders((name, value)))

  def withHeaders(headers: (String, String)*): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.withHeaders(headers: _*))

  def withUrl(url: URL): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.withUrl(url))

  def addFilter(
    filter: Filter[Request, Response, Request, Response]
  ): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.addFilter(filter))

  def prependFilter(
    filter: Filter[Request, Response, Request, Response]
  ): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.prependFilter(filter))

  def resetFilters(): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.resetFilters())

  def withQuery(query: String): ClientRequest[Meth, Accept, Content, ContentType] = copy(
    request = request.withQuery(query)
  )

  def withQueryParams(
    params: List[(String, String)]
  ): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.withQueryParams(params))

  def addQueryParams(
    params: List[(String, String)]
  ): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.addQueryParams(params))

  def withQueryParams(
    params: (String, String)*
  ): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.withQueryParams(params: _*))

  def addQueryParams(
    params: (String, String)*
  ): ClientRequest[Meth, Accept, Content, ContentType] =
    copy(request = request.addQueryParams(params: _*))


  def buildUrl: URL = request.buildUrl


  def send[K]()(implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
    decodeAll: DecodeAll[K, Accept]
  ): Future[K] = sendValid().flatMap {
    response => decodeResponse[K](response)
  }

  def send[E, S](implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
    decodeSuccess: DecodeAll[S, Accept],
    decodeError: DecodeAll[E, Accept]
  ): Future[Either[E, S]] = sendValid().flatMap {
    rep => decodeResponse[S](rep).map(Either.right[E, S])
  }.rescue {
    case ErrorResponse(_, rep) => decodeResponse[E](rep).map(Either.left[E, S])
  }

  def sendZip[E, S]()(implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
    decodeSuccess: DecodeAll[S, Accept],
    decodeError: DecodeAll[E, Accept]
  ): Future[(Either[E, S], Response)] = sendValid().flatMap {
     rep => decodeResponse[S](rep).map(Either.right[E, S]).map((_, rep))
   }.rescue {
     case ErrorResponse(_, rep) => decodeResponse[E](rep).map(Either.left[E, S]).map((_, rep))
   }

  private def sendValid()(implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]]
  ): Future[Response] = canBuildRequest.build(request).fold(
    errs => Future.exception(errs.head),
    req => handleRequest(() => req, request.filters, request.buildUrl, client.httpClient, client.maxFollows)
  )

  private def decodeResponse[K](rep: Response)(implicit decodeAll: DecodeAll[K, Accept]) =
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

  private def handleRequest(
    request: () => Request,
    filters: Filter[Request, Response, Request, Response],
    url: URL,
    httpClient: Service[Request, Response],
    remainingRedirects: Int
  ): Future[Response] = {
    val req = request()
    (filters andThen httpClient) (req) flatMap {
      rep =>
        rep.status match {
          case Continue =>
            Future.exception(InvalidResponse(
              rep,
              "Received unexpected 100/Continue, but request body was already sent."
            ))
          case SwitchingProtocols => Future.exception(InvalidResponse(
            rep,
            "Received unexpected 101/Switching Protocols, but no switch was requested."
          ))
          case s if s.code >= 200 && s.code < 300 =>
            Future(rep)
          case MultipleChoices =>
            Future.exception(InvalidResponse(rep, "300/Multiple Choices is not yet supported in featherbed."))
          case MovedPermanently | Found | SeeOther | TemporaryRedirect =>
            val attempt = for {
              tooMany <- if (remainingRedirects <= 0)
                Left("Too many redirects; giving up")
              else
                Right(())
              location <- Either.fromOption(
                rep.headerMap.get("Location"),
                "Redirect required, but location header not present")
              newUrl <- Either.catchNonFatal(url.toURI.resolve(location))
                .leftMap(_ => s"Could not resolve Location $location")
              canHandle <- if (newUrl.getHost != url.getHost)
                Either.left("Location points to another host; this isn't supported by featherbed")
              else
                Either.right(())
            } yield {
              val newReq = request()
              newReq.uri = List(Option(newUrl.getPath), Option(newUrl.getQuery).map("?" + _)).flatten.mkString
              handleRequest(() => newReq, filters, url, httpClient, remainingRedirects - 1)
            }
            attempt.fold(err => Future.exception(InvalidResponse(rep, err)), identity)
          case other => Future.exception(ErrorResponse(req, rep))
        }
    }
  }

}

object ClientRequest extends RequestTypes[ClientRequest] {

  class ClientRequestSyntax(client: Client) extends RequestSyntax[ClientRequest] with RequestTypes[ClientRequest] {

    def req[Meth <: Method, Accept <: Coproduct](
      method: Meth, url: URL,
      filters: Filter[Request, Response, Request, Response]
    ): ClientRequest[Meth, Accept, None.type, None.type] =
      ClientRequest(HTTPRequest.req(method, url, filters), client)
  }

  def apply(client: Client): ClientRequestSyntax = new ClientRequestSyntax(client)


  implicit class PostRequestOps[Accept <: Coproduct, Content, ContentType](
    val req: PostRequest[Accept, Content, ContentType]
  ) extends AnyVal {
    def withContent[Content, ContentType <: String](
      content: Content,
      contentType: ContentType)(implicit
      witness: Witness.Aux[contentType.type]
    ): PostRequest[Accept, Content, contentType.type] = req.copy(
      request = req.request.withContent(content, contentType)
    )

    def withParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept] = req.copy(
      request = req.request.withParams(first, rest: _*)
    )

  }

  implicit class PutRequestOps[Accept <: Coproduct, Content, ContentType](
    val req: PutRequest[Accept, Content, ContentType]
  ) extends AnyVal {
    def withContent[Content, ContentType <: String](
      content: Content,
      contentType: ContentType)(implicit
      witness: Witness.Aux[contentType.type]
    ): PutRequest[Accept, Content, contentType.type] = req.copy(
      request = req.request.withContent(content, contentType)
    )
  }

}