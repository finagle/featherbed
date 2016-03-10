---
title: Building REST Clients
layout: default
---

# Building REST Clients

Previously, we looked at how featherbed's request specifications are evaluated lazily, and can be re-used to generate
multiple requests of the same specification.  In this segment, we'll see how this functionality can be leveraged
to build resource-oriented client APIs for a REST service.

As an example, we'll build a Scala client for a subset of the [JSONPlaceholder](http://jsonplaceholder.typicode.com/) API.
The JSONPlaceholder defines the following data types:

```scala
case class Post(userId: Int, id: Int, title: String, body: String)
// defined class Post

case class Comment(postId: Int, id: Int, name: String, email: String, body: String)
// defined class Comment
```

We need the usual imports:

```scala
import featherbed.circe._
// import featherbed.circe._

import io.circe.generic.auto._
// import io.circe.generic.auto._

import shapeless.Coproduct
// import shapeless.Coproduct

import com.twitter.util.Await
// import com.twitter.util.Await

import java.net.URL
// import java.net.URL
```

And we can define a class for our API client:

```scala
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
// defined class JSONPlaceholderAPI

val apiClient = new JSONPlaceholderAPI(new URL("http://jsonplaceholder.typicode.com/"))
// apiClient: JSONPlaceholderAPI = JSONPlaceholderAPI@22c823fb

Await.result(apiClient.posts.list())
// res0: cats.data.Validated[featherbed.InvalidResponse,Seq[Post]] =
// Valid(Vector(Post(1,1,sunt aut facere repellat provident occaecati excepturi optio reprehenderit,quia et suscipit
// suscipit recusandae consequuntur expedita et cum
// reprehenderit molestiae ut ut quas totam
// nostrum rerum est autem sunt rem eveniet architecto), Post(1,2,qui est esse,est rerum tempore vitae
// sequi sint nihil reprehenderit dolor beatae ea dolores neque
// fugiat blanditiis voluptate porro vel nihil molestiae ut reiciendis
// qui aperiam non debitis possimus qui neque nisi nulla), Post(1,3,ea molestias quasi exercitationem repellat qui ipsa sit aut,et iusto sed quo iure
// voluptatem occaecati omnis eligendi aut ad
// voluptatem doloribus vel accusantium quis pariatur
// molestiae porro eius odio et labore et velit aut), Post(1...

Await.result(apiClient.posts.get(1))
// res1: cats.data.Validated[featherbed.InvalidResponse,Post] =
// Valid(Post(1,1,sunt aut facere repellat provident occaecati excepturi optio reprehenderit,quia et suscipit
// suscipit recusandae consequuntur expedita et cum
// reprehenderit molestiae ut ut quas totam
// nostrum rerum est autem sunt rem eveniet architecto))
```


