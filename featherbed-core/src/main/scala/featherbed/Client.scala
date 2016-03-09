package featherbed

import java.net.URL
import com.twitter.finagle._, http.RequestBuilder
import shapeless.Coproduct

case class Dest(baseUrl:URL, relativePath:String)
class Client private[featherbed] (private[featherbed] val backend: ClientBackend) {

  def this(baseUrl : URL) = this(ClientBackend(
    Client.forUrl(baseUrl), baseUrl))

  def get(relativePath : String) = GetRequest[Coproduct.`"*/*"`.T](
    this,
    Dest(backend.baseUrl, relativePath),
    requestBuilder(relativePath))

  def post(relativePath : String) = PostRequest[Nothing, Nothing, None.type, Coproduct.`"*/*"`.T](
    this,
    Dest(backend.baseUrl, relativePath),
    requestBuilder(relativePath),
    None)

  def put(relativePath : String) = PutRequest[Nothing, Nothing, None.type, Coproduct.`"*/*"`.T](
    this,
    Dest(backend.baseUrl, relativePath),
    requestBuilder(relativePath),
    multipart = false,
    None)

  def head(relativePath : String) =
    HeadRequest(this, Dest(backend.baseUrl, relativePath), requestBuilder(relativePath))

  def delete(relativePath : String) =
    DeleteRequest[Coproduct.`"*/*"`.T](this, Dest(backend.baseUrl, relativePath), requestBuilder(relativePath))

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
