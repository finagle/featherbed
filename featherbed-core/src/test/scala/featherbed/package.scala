import java.net.{SocketAddress, URL}

import com.twitter.finagle.client.{StackClient, Transporter}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.ProtocolLibrary
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Stack, Service, SimpleFilter, Http, Filter}
import com.twitter.util.Future
import org.jboss.netty.handler.codec.http.DefaultHttpRequest
import org.scalamock.matchers.Matcher
import org.scalamock.scalatest.MockFactory


package object featherbed {

  class MockClient private[featherbed] (backend: ClientBackend, filter: Filter[Request, Response, Request, Response]) extends Client(backend) {

    override def clientTransform(c: Http.Client) = c.filtered(filter)

  }

  trait ClientTest {
    self: MockFactory =>

    class TransportRequestMatcher(f: Request => Unit) extends Matcher[Any] {
      override def canEqual(x: Any) = x match {
        case x: Request => true
        case _ => false
      }
      override def safeEquals(that: Any): Boolean = that match {
        case x: Request => f(x); true
        case _ => false
      }
    }

    def request(f: Request => Unit) = new TransportRequestMatcher(f)

    def mockClient(url: String, filter: Filter[Request, Response, Request, Response]) = {
      val a = new Client(ClientBackend(Http.Client().filtered(filter), new URL(url)))
      println(a.httpClient)
      a
    }
  }

}
