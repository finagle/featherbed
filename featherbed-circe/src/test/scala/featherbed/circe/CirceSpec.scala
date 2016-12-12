package featherbed.circe

import cats.implicits._
import com.twitter.util.Future
import io.circe._
import io.circe.generic.auto._
import io.circe.parser.parse
import io.circe.syntax._
import org.scalatest.FlatSpec
import shapeless.{Coproduct, Witness}
import shapeless.union.Union

case class Foo(someText: String, someInt: Int)

class CirceSpec extends FlatSpec {

  "post request of a case class" should "derive JSON encoder" in {

    import com.twitter.util.{Future, Await}
    import com.twitter.finagle.{Service, Http}
    import com.twitter.finagle.http.{Request, Response}
    import java.net.InetSocketAddress

    val server = Http.serve(new InetSocketAddress(8766), new Service[Request, Response] {
      def apply(request: Request): Future[Response] = Future {
        val rep = Response()
        rep.contentString = s"${request.contentString}"
        rep.setContentTypeJson()
        rep
      }
    })

    import java.net.URL
    val client = new featherbed.Client(new URL("http://localhost:8766/api/"))

    import io.circe.generic.auto._

    val req = client.post("foo/bar")
      .withContent(Foo("Hello world!", 42), "application/json")
        .accept[Coproduct.`"application/json"`.T]

    val result = Await.result {
       req.send[Foo]()
    }

    Foo("test", 42).asJson.toString

    parse("""{"someText": "test", "someInt": 42}""").toValidated.map(_.as[Foo])

    Await.result(server.close())

  }

  "API example" should "compile" in {
    import shapeless.Coproduct
    import java.net.URL
    import com.twitter.util.Await
    case class Post(userId: Int, id: Int, title: String, body: String)

    case class Comment(postId: Int, id: Int, name: String, email: String, body: String)
    class JSONPlaceholderAPI(baseUrl: URL) {

      private val client = new featherbed.Client(baseUrl)
      type JSON = Coproduct.`"application/json"`.T

      object posts {

        private val listRequest = client.get("posts").accept[JSON]
        private val getRequest = (id: Int) => client.get(s"posts/$id").accept[JSON]

        def list(): Future[Seq[Post]] = listRequest.send[Seq[Post]]()
        def get(id: Int): Future[Post] = getRequest(id).send[Post]()
      }

      object comments {
        private val listRequest = client.get("comments").accept[JSON]
        private val getRequest = (id: Int) => client.get(s"comments/$id").accept[JSON]

        def list(): Future[Seq[Comment]] = listRequest.send[Seq[Comment]]()
        def get(id: Int): Future[Comment] = getRequest(id).send[Comment]()
      }
    }

    val apiClient = new JSONPlaceholderAPI(new URL("http://jsonplaceholder.typicode.com/"))

    Await.result(apiClient.posts.list())
  }

}
