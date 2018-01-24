package featherbed
package request

import java.io.File
import java.net.{URL, URLEncoder}
import java.nio.charset.{Charset, StandardCharsets}
import scala.language.experimental.macros
import scala.language.higherKinds

import cats.data._, Validated._
import cats.implicits._
import cats.instances.list._
import com.twitter.finagle.Filter
import com.twitter.finagle.http.{Request, Response}
import featherbed.content.{Form, MimeContent, MultipartForm}
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

trait RequestSyntax[Req[Meth <: String, Accept <: Coproduct, Content, ContentType]] { self: RequestTypes[Req] =>

  def req[Meth <: String, Accept <: Coproduct](
    method: Meth,
    url: URL,
    filters: Filter[Request, Response, Request, Response]
  ): Req[Meth, Accept, None.type, None.type]

  def get(
    url: URL,
    filters: Filter[Request, Response, Request, Response] = Filter.identity
  ): GetRequest[CNil] = req(Method.Get, url, filters = filters)

  def post(
    url: URL,
    filters: Filter[Request, Response, Request, Response] = Filter.identity
  ): PostRequest[CNil, None.type, None.type] = req(Method.Post, url, filters = filters)

  def put(
    url: URL,
    filters: Filter[Request, Response, Request, Response] = Filter.identity
  ): PutRequest[CNil, None.type, None.type] = req(Method.Put, url, filters = filters)

  def patch(
    url: URL,
    filters: Filter[Request, Response, Request, Response] = Filter.identity
  ): PatchRequest[CNil, None.type, None.type] = req(Method.Patch, url, filters = filters)

  def head(
    url: URL,
    filters: Filter[Request, Response, Request, Response] = Filter.identity
  ): HeadRequest = req[Method.Head, CNil](Method.Head, url, filters = filters)

  def delete(
    url: URL,
    filters: Filter[Request, Response, Request, Response] = Filter.identity
  ): DeleteRequest[CNil] = req(Method.Delete, url, filters = filters)

}

trait RequestTypes[Req[Meth <: String, Accept <: Coproduct, Content, ContentType]] {

  type GetRequest[Accept <: Coproduct] = Req[Method.Get, Accept, None.type, None.type]
  type PostRequest[Accept <: Coproduct, Content, ContentType] =
    Req[Method.Post, Accept, Content, ContentType]

  type FormPostRequest[Accept <: Coproduct, Content] = PostRequest[Accept, Content, MimeContent.WebForm]
  type MultipartFormRequest[Accept <: Coproduct, Content] = PostRequest[Accept, Content, MimeContent.MultipartForm]

  type PutRequest[Accept <: Coproduct, Content, ContentType] =
    Req[Method.Put, Accept, Content, ContentType]

  type HeadRequest = Req[Method.Head, CNil, None.type, None.type]
  type DeleteRequest[Accept <: Coproduct] = Req[Method.Delete, Accept, None.type, None.type]

  type PatchRequest[Accept <: Coproduct, Content, ContentType] =
    Req[Method.Patch, Accept, Content, ContentType]
}

