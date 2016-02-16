package featherbed

import java.net.{InetSocketAddress, SocketAddress, URL}

import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle._
import com.twitter.finagle.http.{RequestBuilder, Method, Response, Request}
import com.twitter.io.Buf
import com.twitter.util.Future
import shapeless.Coproduct
import shapeless.union.Union


class Client private[featherbed] (private[featherbed] val backend: ClientBackend) {

  def this(baseUrl : URL) = this(ClientBackend(
    Client.forUrl(baseUrl), baseUrl))

  def get(relativePath : String) = GetRequest[Coproduct.`"*/*"`.T](
    this,
    Client.hostAndPort(backend.baseUrl),
    requestBuilder(relativePath))

  def post(relativePath : String) = PostRequest[Nothing, Nothing, None.type, Coproduct.`"*/*"`.T](
    this,
    Client.hostAndPort(backend.baseUrl),
    requestBuilder(relativePath),
    None)

  def put(relativePath : String) = PutRequest[Nothing, Nothing, None.type, Coproduct.`"*/*"`.T](
    this,
    Client.hostAndPort(backend.baseUrl),
    requestBuilder(relativePath),
    multipart = false,
    None)

  private def requestBuilder(relativePath: String) =
    RequestBuilder().url(new URL(backend.baseUrl, relativePath))

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
