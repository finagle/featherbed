package featherbed

import java.net.URL

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Filter, Http}
import org.scalamock.matchers.Matcher
import org.scalamock.scalatest.MockFactory

package object fixture {
  private[fixture] class MockClient (
    baseUrl: URL,
    filter: Filter[Request, Response, Request, Response]
  ) extends Client(baseUrl) {
    override def clientTransform(c: Http.Client) = c.filtered(filter)
  }

  trait ClientTest { self: MockFactory =>
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

    def request(f: Request => Unit): TransportRequestMatcher = new TransportRequestMatcher(f)

    def mockClient(url: String, filter: Filter[Request, Response, Request, Response]): Client =
      new MockClient(new URL(url), filter)
  }
}
