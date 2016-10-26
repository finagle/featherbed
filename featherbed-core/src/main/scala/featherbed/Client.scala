package featherbed

import java.net.URL
import java.nio.charset.{Charset, StandardCharsets}

import com.twitter.finagle._
import com.twitter.finagle.builder.ClientBuilder
import featherbed.auth.Authorizer
import http.{Request, RequestBuilder, Response}
import shapeless.Coproduct

/**
  * A REST client with a given base URL.
  */
case class Client(
  baseUrl: URL,
  charset: Charset = StandardCharsets.UTF_8,
  filters: Filter[Request, Response, Request, Response] = Filter.identity[Request, Response]
) extends request.RequestTypes with request.RequestBuilding {

  def addFilter(filter: Filter[Request, Response, Request, Response]): Client =
    copy(filters = filter andThen filters)

  def setFilter(filter: Filter[Request, Response, Request, Response]): Client =
    copy(filters = filter)

  def authorized(authorizer: Authorizer): Client = setFilter(filters andThen authorizer)

  /**
    * Specify a GET request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[GetRequest]] object, which can further specify and send the request
    */
  def get(relativePath: String): GetRequest[Coproduct.`"*/*"`.T] =
    GetRequest[Coproduct.`"*/*"`.T](
      baseUrl.toURI.resolve(relativePath).toURL,
      List.empty,
      charset,
      filters
    )

  /**
    * Specify a POST request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PostRequest]] object, which can further specify and send the request
    */
  def post(relativePath: String): PostRequest[None.type, Nothing, Coproduct.`"*/*"`.T] =
    PostRequest[None.type, Nothing, Coproduct.`"*/*"`.T](
      baseUrl.toURI.resolve(relativePath).toURL,
      None,
      List.empty,
      charset,
      filters
    )

  /**
    * Specify a PUT request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PutRequest]] object, which can further specify and send the request
    */
  def put(relativePath: String): PutRequest[None.type, Nothing, Coproduct.`"*/*"`.T] =
    PutRequest[None.type, Nothing, Coproduct.`"*/*"`.T](
      baseUrl.toURI.resolve(relativePath).toURL,
      None,
      List.empty,
      charset,
      filters
    )

  /**
    * Specify a HEAD request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[HeadRequest]] object, which can further specify and send the request
    */
  def head(relativePath: String): HeadRequest =
    HeadRequest(baseUrl.toURI.resolve(relativePath).toURL, List.empty, charset, filters)

  /**
    * Specify a DELETE request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[DeleteRequest]] object, which can further specify and send the request
    */
  def delete(relativePath: String): DeleteRequest[Coproduct.`"*/*"`.T] =
    DeleteRequest[Coproduct.`"*/*"`.T](baseUrl.toURI.resolve(relativePath).toURL, List.empty, charset, filters)

  /**
    *  Close this client releasing allocated resources.
    */
  def close (): Unit =
    httpClient.close()

  protected def clientTransform(client: Http.Client): Http.Client = client

  protected lazy val client =
    clientTransform(Client.forUrl(baseUrl))

  protected[featherbed] lazy val httpClient =
    client.newService(Client.hostAndPort(baseUrl))
}

object Client {
  private[featherbed] def forUrl(url: URL) = {
    val client =
      Http.Client()
    if(url.getProtocol == "https") client.withTls(url.getHost) else client
  }

  private[featherbed] def hostAndPort(url: URL) = url.getPort match {
    case -1 => s"${url.getHost}:${url.getDefaultPort}"
    case port => s"${url.getHost}:$port"
  }

  def apply(baseUrl: URL): Client = new Client(baseUrl)
}
