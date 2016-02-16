package featherbed

import shapeless.labelled.FieldType
import shapeless._, labelled.field
import scala.annotation.implicitNotFound
import scala.language.higherKinds

package object support {

  @implicitNotFound("""In order to decode a request to $A, it must be known that a decoder exists to $A from all the content types that you Accept, which is currently ${ContentTypes}.
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

  sealed trait LiftAllCoproduct[F[_], In <: Coproduct] {
    type Out <: HList
    def instances: Out
    def instanceList: List[F[_]]
  }

  object LiftAllCoproduct {
    type Aux[F[_], In0 <: Coproduct, Out0 <: HList] = LiftAllCoproduct[F, In0] {type Out = Out0}
    class Curried[F[_]] {def apply[In <: Coproduct](in: In)(implicit ev: LiftAllCoproduct[F, In]) = ev}

    def apply[F[_]] = new Curried[F]
    def apply[F[_], In <: Coproduct](implicit ev: LiftAllCoproduct[F, In]) = ev

    implicit def hnil[F[_]]: LiftAllCoproduct.Aux[F, CNil, HNil] = new LiftAllCoproduct[F, CNil] {
      type Out = HNil
      val instances = HNil
      val instanceList = Nil
    }

    implicit def hcons[F[_], K, T <: Coproduct]
    (implicit headInstance: F[K], tailInstances: LiftAllCoproduct[F, T]): Aux[F, K :+: T, FieldType[K, F[K]] :: tailInstances.Out] =
      new LiftAllCoproduct[F, K :+: T] {
        type Out = FieldType[K, F[K]] :: tailInstances.Out
        def instances = field[K](headInstance) :: tailInstances.instances
        val instanceList = headInstance :: tailInstances.instanceList
      }
  }

  
}
