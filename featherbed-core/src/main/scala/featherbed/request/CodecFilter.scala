package featherbed
package request

import java.net.URL

import cats.data.Validated.{Invalid, Valid}
import cats.syntax.either._
import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.http.Status._
import com.twitter.util.Future
import featherbed.content.MimeContent
import featherbed.support.{ContentType, DecodeAll, RuntimeContentType}
import shapeless.{Coproduct, Witness}

class CodecFilter[Meth <: String, Accept <: Coproduct, Content, ContentType, Result](
  request: HTTPRequest[Meth, Accept, None.type, ContentType],
  maxFollows: Int)(implicit
  canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
  decodeAll: DecodeAll[Result, Accept]
) extends Filter[Content, Result, Request, Response] {

  def apply(req: Content, service: Service[Request, Response]): Future[Result] = {
    canBuildRequest
      .build(request.copy[Meth, Accept, Content, ContentType](content = request.content.copy(content = req)))
      .fold(
        errs => Future.exception(RequestBuildingError(errs)),
        req => CodecFilter.handleRequest(() => req, request.filters, request.buildUrl, service, maxFollows))
      .flatMap {
        rep => CodecFilter.decodeResponse[Result, Accept](rep)
      }
  }

}

object CodecFilter {

  private[featherbed] def handleRequest(
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


  private[featherbed] def decodeResponse[K, Accept <: Coproduct](rep: Response)(implicit
    decodeAll: DecodeAll[K, Accept]
  ): Future[K] = {
    val RuntimeContentType(mediaType, _) = rep.contentType
      .flatMap(ContentType.contentTypePieces)
      .getOrElse(RuntimeContentType("*/*", Map.empty))

    decodeAll.findInstance(mediaType) match {
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


}