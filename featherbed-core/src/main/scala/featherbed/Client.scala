package featherbed

import java.net.URL

import com.twitter.finagle._
import http.RequestBuilder
import shapeless.Coproduct

/**
  * A REST client with a given base URL.
  */
class Client private[featherbed] (private[featherbed] val backend: ClientBackend) {

  /**
    * Construct a [[Client]] with the given base URL. The URL will be used as the base for resolving resources, so
    * it usually needs to include the trailing slash "/":
    *
    * When "foo/bar" is resolved against "http://example.com/api/v1", the result is "http://example.com/api/foo/bar", because
    * "v1" is the final path segment; this usually isn't desired.  This can't be solved by resolving "/foo/bar", because
    * that is a host-relative URI, and will result in "http://example.com/foo/bar" (removing the entire path of the
    * base URL).
    *
    * When "foo/bar" is resolved against "http://example.com/api/v1/", on the other hand, the result is the desired
    * location of "http://example.com/api/v1/foo/bar".
    *
    * @param baseUrl The base URL that this client will resolve resources against
    */
  def this(baseUrl : URL) = this(ClientBackend(
    Client.forUrl(baseUrl), baseUrl))

  /**
    * Specify a GET request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[GetRequest]] object, which can further specify and send the request
    */
  def get(relativePath : String) = GetRequest[Coproduct.`"*/*"`.T](
    this,
    backend.baseUrl,
    relativePath,
    Map.empty,
    RequestBuilder())

  /**
    * Specify a POST request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PostRequest]] object, which can further specify and send the request
    */
  def post(relativePath : String) = PostRequest[Nothing, Nothing, None.type, Coproduct.`"*/*"`.T](
    this,
    backend.baseUrl,
    relativePath,
    Map.empty,
    RequestBuilder(),
    None)

  /**
    * Specify a PUT request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[PutRequest]] object, which can further specify and send the request
    */
  def put(relativePath : String) = PutRequest[Nothing, Nothing, None.type, Coproduct.`"*/*"`.T](
    this,
    backend.baseUrl,
    relativePath,
    Map.empty,
    RequestBuilder(),
    multipart = false,
    None)

  /**
    * Specify a HEAD request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[HeadRequest]] object, which can further specify and send the request
    */
  def head(relativePath : String) =
    HeadRequest(this, backend.baseUrl, relativePath, Map.empty, RequestBuilder())

  /**
    * Specify a DELETE request to be performed against the given resource
    * @param relativePath The path to the resource, relative to the baseUrl
    * @return A [[DeleteRequest]] object, which can further specify and send the request
    */
  def delete(relativePath : String) =
    DeleteRequest[Coproduct.`"*/*"`.T](this, backend.baseUrl, relativePath, Map.empty, RequestBuilder())

  protected def clientTransform(client: Http.Client): Http.Client = client

  val httpClient = clientTransform(backend.client).newClient(Client.hostAndPort(backend.baseUrl))

}

object Client {
  private[featherbed] def forUrl(url : URL) = {
    val client =
      Http.Client()
    if(url.getProtocol == "https") client.withTls(url.getHost) else client
  }

  private[featherbed] def hostAndPort(url : URL) = url.getPort match {
    case -1 => s"${url.getHost}:${url.getDefaultPort}"
    case port => s"${url.getHost}:$port"
  }

}

private[featherbed] case class ClientBackend(
  client: Http.Client,
  baseUrl: URL
)
