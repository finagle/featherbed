package featherbed

import scala.annotation.implicitNotFound
import scala.language.higherKinds

import cats.data.Validated.Valid
import com.twitter.finagle.http.Response
import shapeless._

package object support {

  @implicitNotFound("""In order to decode a request to ${A}, it must be known that a decoder exists to ${A} from
all the content types that you Accept, which is currently ${ContentTypes}.
You may have forgotten to specify Accept types with the `accept(..)` method,
or you may be missing Decoder instances for some content types.
""")
  sealed trait DecodeAll[A, ContentTypes <: Coproduct] {
    val instances: List[content.Decoder.Aux[_, A]]
    def findInstance(ct: String): Option[content.Decoder.Aux[_, A]] =
      instances.find(_.contentType == ct) orElse instances.find(_.contentType == "*/*")
  }

  object DecodeAll {
    implicit def one[H, A](implicit
      headInstance: content.Decoder.Aux[H, A]
    ): DecodeAll[A, H :+: CNil] = new DecodeAll[A, H :+: CNil] {
      val instances = headInstance :: Nil
    }

    implicit def ccons[H, A, T <: Coproduct](implicit
      headInstance: content.Decoder.Aux[H, A],
      tailInstances: DecodeAll[A, T]): DecodeAll[A, H :+: T] = new DecodeAll[A, H :+: T] {

      val instances = headInstance :: tailInstances.instances
    }

    implicit val decodeResponse = new DecodeAll[Response, Nothing] {
      val instances = new content.Decoder[Response] {
        type Out = Response
        val contentType = "*/*"
        def apply(response: Response) = Valid(response)
      } :: Nil
    }
  }
}
