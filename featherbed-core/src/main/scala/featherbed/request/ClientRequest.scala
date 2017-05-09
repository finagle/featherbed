package featherbed
package request

import java.net.URL
import java.nio.charset.Charset
import scala.language.experimental.macros

import cats.syntax.either._
import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import featherbed.Client
import featherbed.content.{Form, MimeContent, ToQueryParams}
import featherbed.littlemacros._
import featherbed.support.DecodeAll
import shapeless._
import shapeless.labelled.FieldType
import shapeless.ops.hlist.SelectAll


/**
  * An [[HTTPRequest]] which is already paired with a [[Client]]
  */
case class ClientRequest[Meth <: String, Accept <: Coproduct, Content, ContentType](
  request: HTTPRequest[Meth, Accept, Content, ContentType],
  client: Client
) {

  def accept[A <: Coproduct]: ClientRequest[Meth, A, Content, ContentType] =
    copy[Meth, A, Content, ContentType](request = request.accept[A])

  def accept(mimeType: Witness.Lt[String]): ClientRequest[Meth, mimeType.T :+: CNil, Content, ContentType] =
    accept[mimeType.T :+: CNil]

  def accept(
    mimeTypeA: Witness.Lt[String],
    mimeTypeB: Witness.Lt[String]
  ): ClientRequest[Meth, mimeTypeA.T :+: mimeTypeB.T :+: CNil, Content, ContentType] =
    accept[mimeTypeA.T :+: mimeTypeB.T :+: CNil]

  def accept(
    mimeTypeA: Witness.Lt[String],
    mimeTypeB: Witness.Lt[String],
    mimeTypeC: Witness.Lt[String]
  ): ClientRequest[Meth, mimeTypeA.T :+: mimeTypeB.T :+: mimeTypeC.T :+: CNil, Content, ContentType] =
    accept[mimeTypeA.T :+: mimeTypeB.T :+: mimeTypeC.T :+: CNil]

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
    response => CodecFilter.decodeResponse[K, Accept](response)
  }

  def send[E, S]()(implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
    decodeSuccess: DecodeAll[S, Accept],
    decodeError: DecodeAll[E, Accept]
  ): Future[Either[E, S]] = sendValid().flatMap {
    rep => CodecFilter.decodeResponse[S, Accept](rep).map(Either.right[E, S])
  }.rescue {
    case ErrorResponse(_, rep) => CodecFilter.decodeResponse[E, Accept](rep).map(Either.left[E, S])
  }

  def sendZip[E, S]()(implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
    decodeSuccess: DecodeAll[S, Accept],
    decodeError: DecodeAll[E, Accept]
  ): Future[(Either[E, S], Response)] = sendValid().flatMap {
     rep => CodecFilter.decodeResponse[S, Accept](rep).map(Either.right[E, S]).map((_, rep))
   }.rescue {
     case ErrorResponse(_, rep) => CodecFilter.decodeResponse[E, Accept](rep).map(Either.left[E, S]).map((_, rep))
   }

  private def sendValid()(implicit
    canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]]
  ): Future[Response] = canBuildRequest.build(request).fold(
    errs => Future.exception(RequestBuildingError(errs)),
    req => CodecFilter.handleRequest(() => req, request.filters, request.buildUrl, client.httpClient, client.maxFollows)
  )


}

object ClientRequest extends RequestTypes[ClientRequest] {

  case class ContentToService[Meth <: String, Accept <: Coproduct, Content, Result](
    req: ClientRequest[Meth, Accept, None.type, None.type]
  ) extends AnyVal {
    def apply[ContentType <: String](contentType: Witness.Lt[ContentType])(implicit
      canBuildRequest: CanBuildRequest[HTTPRequest[Meth, Accept, Content, ContentType]],
      decodeAll: DecodeAll[Result, Accept]
    ): Service[Content, Result] = {
      new CodecFilter[Meth, Accept, Content, ContentType, Result](
        req.request.copy[Meth, Accept, None.type, ContentType](content = MimeContent(None, contentType.value)),
        req.client.maxFollows
      ) andThen req.client.httpClient
    }
  }

  class ClientRequestSyntax(client: Client) extends RequestSyntax[ClientRequest] with RequestTypes[ClientRequest] {

    def req[Meth <: String, Accept <: Coproduct](
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
    ): FormPostRequest[Accept, Form] = req.copy(
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

  implicit class PutToServiceOps[Accept <: Coproduct](
    val req: PutRequest[Accept, None.type, None.type]
  ) extends AnyVal {
    def toService[In, Out]: ContentToService[Method.Put, Accept, In, Out] = ContentToService(req)
  }

  case class PostMultipartFormToService[Accept <: Coproduct](
    req: PostRequest[Accept, None.type, None.type]
  ) extends AnyVal {
    def toService[In, Out](implicit
      canBuildRequest: CanBuildRequest[
        HTTPRequest[Method.Post, Accept, In, MimeContent.MultipartForm]
        ],
      decodeAll: DecodeAll[Out, Accept]
    ): Service[In, Out] =
      ContentToService[Method.Post, Accept, In, Out](req)
        .apply("multipart/form-data")
  }


  case class PostFormToService[Accept <: Coproduct](
    req: PostRequest[Accept, None.type, None.type]
  ) extends AnyVal {
    def toService[In, Out](implicit
      canBuildRequest: CanBuildRequest[
          HTTPRequest[Method.Post, Accept, In, MimeContent.WebForm]
        ],
      decodeAll: DecodeAll[Out, Accept]
    ): Service[In, Out] =
      ContentToService[Method.Post, Accept, In, Out](req)
        .apply("application/x-www-form-urlencoded")

    def multipart: PostMultipartFormToService[Accept] = PostMultipartFormToService(req)
  }

  implicit class PostToServiceOps[Accept <: Coproduct](
    val req: PostRequest[Accept, None.type, None.type]
  ) extends AnyVal {
    def toService[In, Out]: ContentToService[Method.Post, Accept, In, Out] = ContentToService(req)
    def form: PostFormToService[Accept] = PostFormToService(req)
  }

  implicit class GetToServiceOps[Accept <: Coproduct](
    val req: GetRequest[Accept]
  ) extends AnyVal {
    def toService[Result](implicit
      canBuildRequest: CanBuildRequest[HTTPRequest[Method.Get, Accept, None.type, None.type]],
      decodeAll: DecodeAll[Result, Accept]
    ): () => Future[Result] = new Function0[Future[Result]] {
      private val service = new CodecFilter[Method.Get, Accept, Request, None.type, Result](
        req.request, req.client.maxFollows
      ) andThen req.client.httpClient

      private val builtRequest = canBuildRequest.build(req.request).fold(
        errs => Future.exception(errs.head),
        req => Future.value(req)
      )

      def apply(): Future[Result] = for {
        request <- builtRequest
        result  <- service(request)
      } yield result
    }

    def toService[Params, Result](implicit
      canBuildRequest: CanBuildRequest[HTTPRequest[Method.Get, Accept, None.type, None.type]],
      decodeAll: DecodeAll[Result, Accept],
      toQueryParams: ToQueryParams[Params]
    ): Service[Params, Result] = {
      new QueryFilter[Params, Request, Result](
        params => {
          val withParams = req.addQueryParams(params)
          canBuildRequest.build(withParams.request).fold(
            errs => Future.exception(errs.head),
            req => Future.value(req)
          )
        }
      ) andThen new CodecFilter[Method.Get, Accept, Request, None.type, Result](
        req.request, req.client.maxFollows
      ) andThen req.client.httpClient
    }
  }

  implicit class HeadToServiceOps(val req: HeadRequest) extends AnyVal {
    def toService(implicit
      canBuildRequest: CanBuildRequest[HTTPRequest[Method.Head, CNil, None.type, None.type]]
    ): Service[Unit, Response] =
      new UnitToNone[Response] andThen
      new CodecFilter[Method.Head, CNil, None.type, None.type, Response](
        req.request, req.client.maxFollows
      ) andThen req.client.httpClient
  }

  implicit class DeleteToServiceOps[Accept <: Coproduct](val req: DeleteRequest[Accept]) extends AnyVal {
    def toService[Result](implicit
      canBuildRequest: CanBuildRequest[HTTPRequest[Method.Delete, Accept, None.type, None.type]],
      decodeAll: DecodeAll[Result, Accept]
    ): Service[Unit, Result] = new UnitToNone[Result] andThen
      new CodecFilter[Method.Delete, Accept, None.type, None.type, Result](
        req.request, req.client.maxFollows
      ) andThen req.client.httpClient
  }

  implicit class PatchToServiceOps[Accept <: Coproduct](
    val req: PatchRequest[Accept, None.type, None.type]
  ) extends AnyVal {
    def toService[In, Out]: ContentToService[Method.Patch, Accept, In, Out] = ContentToService(req)
  }

  private class UnitToNone[Rep] extends Filter[Unit, Rep, None.type, Rep] {
    def apply(request: Unit, service: Service[None.type, Rep]): Future[Rep] = service(None)
  }


}