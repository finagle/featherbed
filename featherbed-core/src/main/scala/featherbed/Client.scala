package featherbed

import java.net.URL
import java.nio.charset.{Charset, StandardCharsets}

import com.twitter.finagle._
import com.twitter.finagle.builder.ClientBuilder
import featherbed.auth.Authorizer
import featherbed.request.{ClientRequest, HTTPRequest}
import featherbed.request.ClientRequest._
import http.{Request, RequestBuilder, Response}
import shapeless.{CNil, Coproduct}

/**
  * A REST client with a given base URL.
  */
case class Client(
  baseUrl: URL,
  charset: Charset = StandardCharsets.UTF_8,
  filters: Filter[Request, Response, Request, Response] = Filter.identity[Request, Response],
  maxFollows: Int = 5
) {

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
  def get(relativePath: String): GetRequest[CNil] =
    ClientRequest(this).get(
      baseUrl.toURI.resolve(relativePath).toURL,
      filters
    )

  /**
    * Specify a POST request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PostRequest]] object, which can further specify and send the request
    */
  def post(relativePath: String): PostRequest[CNil, None.type, None.type] =
    ClientRequest(this).post(
      baseUrl.toURI.resolve(relativePath).toURL,
      filters
    )

  /**
    * Specify a PUT request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PutRequest]] object, which can further specify and send the request
    */
  def put(relativePath: String): PutRequest[CNil, None.type, None.type] =
    ClientRequest(this).put(
      baseUrl.toURI.resolve(relativePath).toURL,
      filters
    )

  /**
    * Specify a PATCH request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PatchRequest]] object, which can further specify and send the request
    */
  def patch(relativePath: String): PatchRequest[CNil, None.type, None.type] =
    ClientRequest(this).patch(
      baseUrl.toURI.resolve(relativePath).toURL,
      filters
    )

  /**
    * Specify a HEAD request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[HeadRequest]] object, which can further specify and send the request
    */
  def head(relativePath: String): HeadRequest =
    ClientRequest(this).head(
      baseUrl.toURI.resolve(relativePath).toURL,
      filters
    )

  /**
    * Specify a DELETE request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[DeleteRequest]] object, which can further specify and send the request
    */
  def delete(relativePath: String): DeleteRequest[CNil] =
    ClientRequest(this).delete(
      baseUrl.toURI.resolve(relativePath).toURL,
      filters
    )

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
