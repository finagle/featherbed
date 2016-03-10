package featherbed

import shapeless.labelled.FieldType
import shapeless._, labelled.field
import scala.annotation.implicitNotFound
import scala.language.higherKinds

package object support {

  @implicitNotFound("""In order to decode a request to ${A}, it must be known that a decoder exists to ${A} from all the content types that you Accept, which is currently ${ContentTypes}.
You may have forgotten to specify Accept types with the `accept[T <: Coproduct]` method, or you may be missing Decoder instances for some content types.
    """)
  sealed trait DecodeAll[A, ContentTypes <: Coproduct] {
    val instances: List[content.Decoder.Aux[_, A]]
  }

  object DecodeAll {
    implicit def cnil[A] : DecodeAll[A, CNil] = new DecodeAll[A, CNil] {
      val instances = Nil
    }

    implicit def ccons[H, A, T <: Coproduct](implicit
      headInstance: content.Decoder.Aux[H, A],
      tailInstances: DecodeAll[A, T]) : DecodeAll[A, H :+: T] = new DecodeAll[A, H :+: T] {

      val instances = headInstance :: tailInstances.instances
    }
  }


  
}
