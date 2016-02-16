package featherbed

import shapeless.labelled.FieldType
import shapeless._, labelled.field
import scala.annotation.implicitNotFound
import scala.language.higherKinds

package object support {

  sealed trait LiftAll2[F[_,_], In <: Coproduct] {
    type Out <: HList
    def instances: Out
  }

  object LiftAll2 {
    type Aux[F[_,_], In0 <: Coproduct, Out0 <: HList] = LiftAll2[F, In0] {type Out = Out0}
    class Curried[F[_,_]] {def apply[In <: Coproduct](in: In)(implicit ev: LiftAll2[F, In]) = ev}

    def apply[F[_,_]] = new Curried[F]
    def apply[F[_,_], In <: Coproduct](implicit ev: LiftAll2[F, In]) = ev

    implicit def hnil[F[_,_]]: LiftAll2.Aux[F, CNil, HNil] = new LiftAll2[F, CNil] {
      type Out = HNil
      def instances = HNil
    }

    implicit def hcons[F[_,_], K, V, T <: Coproduct]
    (implicit headInstance: F[K,V], tailInstances: LiftAll2[F, T]): Aux[F, FieldType[K, V] :+: T, FieldType[K, F[K,V]] :: tailInstances.Out] =
      new LiftAll2[F, FieldType[K,V] :+: T] {
        type Out = FieldType[K, F[K,V]] :: tailInstances.Out
        def instances = field[K](headInstance) :: tailInstances.instances
      }
  }

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
