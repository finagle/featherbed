package featherbed
package request

import java.io.File
import java.net.{URI, URL, URLEncoder}
import java.nio.charset.{Charset, StandardCharsets}
import scala.language.experimental.macros

import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import com.twitter.finagle.{Filter, Service, ServiceFactory}
import com.twitter.finagle.http.{Method => _, _}
import com.twitter.finagle.http.Status.{NoContent => _, _}
import com.twitter.util.Future
import featherbed.content.{Form, MimeContent, MultipartForm, ToFormParam}
import MimeContent.NoContent
import cats.data.{NonEmptyList, Validated}
import featherbed.littlemacros.CoproductMacros
import featherbed.support.DecodeAll
import shapeless._

case class HTTPRequest[
  Meth <: String,
  Accept <: Coproduct,
  Content,
  ContentType
](
  method: Meth,
  url: URL,
  content: MimeContent[Content, ContentType],
  query: Option[String] = None,
  headers: List[(String, String)] = List.empty,
  charset: Charset = StandardCharsets.UTF_8,
  filters: Filter[Request, Response, Request, Response] = Filter.identity
) {

  def accept[A <: Coproduct]: HTTPRequest[Meth, A, Content, ContentType] =
    copy[Meth, A, Content, ContentType]()

  def accept[A <: Coproduct](types: String*): HTTPRequest[Meth, A, Content, ContentType] =
    macro CoproductMacros.callAcceptCoproduct[A]

  def withCharset(charset: Charset): HTTPRequest[Meth, Accept, Content, ContentType] =
    copy(charset = charset)
  def withHeader(name: String, value: String): HTTPRequest[Meth, Accept, Content, ContentType] =
    withHeaders((name, value))
  def withHeaders(headers: (String, String)*): HTTPRequest[Meth, Accept, Content, ContentType] =
    copy(headers = this.headers ++ headers)

  def withUrl(url: URL): HTTPRequest[Meth, Accept, Content, ContentType] = copy(url = url)

  def addFilter(
    filter: Filter[Request, Response, Request, Response]
  ): HTTPRequest[Meth, Accept, Content, ContentType] = copy(filters = filters andThen filter)

  def prependFilter(
    filter: Filter[Request, Response, Request, Response]
  ): HTTPRequest[Meth, Accept, Content, ContentType] = copy(filters = filter andThen filters)

  def resetFilters(): HTTPRequest[Meth, Accept, Content, ContentType] =
    copy(filters = Filter.identity)

  def withQuery(query: String): HTTPRequest[Meth, Accept, Content, ContentType] = copy(
    query = Some(query)
  )

  def withQueryParams(
    params: List[(String, String)]
  ): HTTPRequest[Meth, Accept, Content, ContentType] = withQuery(
    params.map {
      case (key, value) => URLEncoder.encode(key, charset.name) + "=" + URLEncoder.encode(value, charset.name)
    }.mkString("&")
  )

  def addQueryParams(
    params: List[(String, String)]
  ): HTTPRequest[Meth, Accept, Content, ContentType] = withQuery(
    query.map(_ + "&").getOrElse("") + params.map {
      case (key, value) => URLEncoder.encode(key, charset.name) + "=" + URLEncoder.encode(value, charset.name)
    }.mkString("&")
  )

  def withQueryParams(
    params: (String, String)*
  ): HTTPRequest[Meth, Accept, Content, ContentType] = withQueryParams(params.toList)

  def addQueryParams(
    params: (String, String)*
  ): HTTPRequest[Meth, Accept, Content, ContentType] = addQueryParams(params.toList)


  def buildUrl: URL = {
    val uri = url.toURI
    query.map {
      q => new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, uri.getPath, q, uri.getFragment).toURL
    }.getOrElse(url)
  }

}

object HTTPRequest extends RequestSyntax[HTTPRequest] with RequestTypes[HTTPRequest] {
  def req[Meth <: String, Accept <: Coproduct](
    method: Meth, url: URL,
    filters: Filter[Request, Response, Request, Response]
  ): HTTPRequest[Meth, Accept, None.type, None.type] = HTTPRequest(method, url, NoContent)

  implicit class PostRequestOps[Accept <: Coproduct, Content, ContentType](
    val req: PostRequest[Accept, Content, ContentType]
  ) extends AnyVal {
    def withContent[Content, ContentType <: String](content: Content, contentType: ContentType)(implicit
      witness: Witness.Aux[contentType.type]
    ): PostRequest[Accept, Content, contentType.type] = req.copy[Method.Post, Accept, Content, contentType.type](
      content = MimeContent[Content, contentType.type](content)
    )

    def withParams(
      first: (String, String),
      rest: (String, String)*
    ): FormPostRequest[Accept, Form] = req.copy[Method.Post, Accept, Form, MimeContent.WebForm](
      content = MimeContent[Form, MimeContent.WebForm](
        Form(
          NonEmptyList(first, rest.toList)
            .map((SimpleElement.apply _).tupled)
        )
      )
    )

    def withFiles(
      first: (String, File),
      rest: (String, File)*
    ): MultipartFormRequest[Accept, MultipartForm] = {
      val newContent = req.content match {
        case MimeContent(Form(existing), _) => MimeContent[MultipartForm, MimeContent.MultipartForm](
          MultipartForm(
            NonEmptyList(first, rest.toList).map((ToFormParam.file.apply _).tupled).sequenceU map {
              validFiles => validFiles ++ existing.toList
            }
          )
        )
        case MimeContent(MultipartForm(existing), _) => MimeContent[MultipartForm, MimeContent.MultipartForm](
          MultipartForm(
            NonEmptyList(first, rest.toList).map((ToFormParam.file.apply _).tupled).sequenceU andThen {
              validFiles => existing map (validFiles ++ _.toList)
            }
          )
        )
        case _ => MimeContent[MultipartForm, MimeContent.MultipartForm](MultipartForm(
          NonEmptyList(first, rest.toList).map((ToFormParam.file.apply _).tupled).sequenceU
        ))
      }
      req.copy[Method.Post, Accept, MultipartForm, MimeContent.MultipartForm](
        content = newContent
      )
    }

    def toService[In, Out](contentType: String)(client: Client)(implicit
      canBuildRequest: CanBuildRequest[HTTPRequest[Method.Post, Accept, In, contentType.type]],
      decodeAll: DecodeAll[Out, Accept],
      witness: Witness.Aux[contentType.type]
    ): Service[In, Out] = Service.mk[In, Out] {
      in => ClientRequest(req, client).withContent[In, contentType.type](in, contentType).send[Out]()
    }
  }

  implicit class PutRequestOps[Accept <: Coproduct, Content, ContentType](
    val req: PutRequest[Accept, Content, ContentType]
  ) extends AnyVal {
    def withContent[Content, ContentType <: String](content: Content, contentType: ContentType)(implicit
      witness: Witness.Aux[contentType.type]
    ): PutRequest[Accept, Content, contentType.type] = req.copy[Method.Put, Accept, Content, contentType.type](
      content = MimeContent[Content, contentType.type](content)
    )
  }
}
