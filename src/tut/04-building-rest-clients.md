# Building REST Clients

Previously, we looked at how featherbed's request specifications are evaluated lazily, and can be re-used to generate
multiple requests of the same specification.  In this segment, we'll see how this functionality can be leveraged
to build resource-oriented client APIs for a REST service.

As an example, we'll build a Scala client for a subset of the [JSONPlaceholder](http://jsonplaceholder.typicode.com/) API.
The JSONPlaceholder defines the following data types:

```tut
case class Post(userId: Int, id: Int, title: String, body: String)

case class Comment(postId: Int, id: Int, name: String, email: String, body: String)
```

We need the usual imports:

```tut
import featherbed.circe._
import io.circe.generic.auto._
import shapeless.Coproduct
import com.twitter.util.Await
import java.net.URL
```

And we can define a class for our API client:

```tut
class JSONPlaceholderAPI(baseUrl: URL) {

  private val client = new featherbed.Client(baseUrl)
  type JSON = Coproduct.`"application/json"`.T

  object posts {

    private val listRequest = client.get("posts").accept[JSON]
    private val getRequest = (id: Int) => client.get(s"posts/$id").accept[JSON]

    def list() = listRequest.send[Seq[Post]]()
    def get(id: Int) = getRequest(id).send[Post]()

  }

  object comments {
    private val listRequest = client.get("comments").accept[JSON]
    private val getRequest = (id: Int) => client.get(s"comments/$id").accept[JSON]

    def list() = listRequest.send[Seq[Comment]]()
    def get(id: Int) = getRequest(id).send[Comment]()
  }
}

val apiClient = new JSONPlaceholderAPI(new URL("http://jsonplaceholder.typicode.com/"))

Await.result(apiClient.posts.list())
```


