package featherbed.support

import scala.annotation.implicitNotFound

import cats.data.Validated.Valid
import com.twitter.finagle.http.Response
import featherbed.content
import shapeless.{:+:, CNil, Coproduct}


@implicitNotFound("In order to decode a request to ${A}, it must be known that a decoder exists to ${A} from " +
"all the content types that you Accept, which is currently ${ContentTypes}. " +
"You may have forgotten to specify Accept types with the `accept(..)` method, " +
"or you may be missing Decoder instances for some content types.")
sealed trait DecodeAll[A, ContentTypes <: Coproduct] {
  val instances: List[content.Decoder.Aux[_, A]]
  def findInstance(ct: String): Option[content.Decoder.Aux[_, A]] =
    instances.find(_.contentType == ct) orElse instances.find(_.contentType == "*/*")
}

object DecodeAll {

  implicit def one[H, A](implicit
    headInstance: content.Decoder.Aux[H, A]
  ): DecodeAll[A, H :+: CNil] = new DecodeAll[A, H :+: CNil] {
    final val instances: List[content.Decoder.Aux[_, A]] = headInstance :: Nil
  }

  implicit def ccons[H, A, T <: Coproduct](implicit
    headInstance: content.Decoder.Aux[H, A],
    tailInstances: DecodeAll[A, T]): DecodeAll[A, H :+: T] = new DecodeAll[A, H :+: T] {

    final val instances: List[content.Decoder.Aux[_, A]] = headInstance :: tailInstances.instances
  }

  implicit val decodeResponse: DecodeAll[Response, CNil] = new DecodeAll[Response, CNil] {
    final val instances: List[content.Decoder.Aux[CNil, Response]] = new content.Decoder[CNil] {
      type Out = Response
      val contentType = "*/*"
      def apply(response: Response) = Valid(response)
    } :: Nil
  }

}
