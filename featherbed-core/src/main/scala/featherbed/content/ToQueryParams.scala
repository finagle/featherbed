package featherbed.content


import scala.collection.generic.CanBuildFrom

import cats.syntax.either._
import cats.Show
import shapeless.{:: => /::, _}
import shapeless.labelled.FieldType

abstract class ToQueryParams[L] {
  def apply(l: L): List[(String, String)]
}

object ToQueryParams extends ToQueryParams0 {

  implicit val empty: ToQueryParams[HNil] = new ToQueryParams[HNil] {
    def apply(l: HNil) = Nil
  }

  implicit def consSeq[K <: Symbol, F[_], V, T <: HList](implicit
    label: Witness.Aux[K],
    toQueryParam: ToQueryParam[V],
    canBuildFrom: CanBuildFrom[F[V], V, List[V]],
    toQueryParamsT: ToQueryParams[T]
  ): ToQueryParams[FieldType[K, F[V]] /:: T] = new ToQueryParams[FieldType[K, F[V]] /:: T] {
    final def apply(l: FieldType[K, F[V]] /:: T): List[(String, String)] = {
      canBuildFrom.apply(l.head: F[V]).result.map {
        v => (label.value.name, toQueryParam(v))
      } ::: toQueryParamsT(l.tail)
    }
  }

}

trait ToQueryParams0 {
  implicit def cons[K <: Symbol, H, T <: HList](implicit
    label: Witness.Aux[K],
    toQueryParam: ToQueryParam[H],
    toQueryParamsT: ToQueryParams[T]
  ): ToQueryParams[FieldType[K, H] /:: T] = new ToQueryParams[FieldType[K, H] /:: T] {
    final def apply(l: FieldType[K, H] /:: T): List[(String, String)] = {
      (label.value.name, toQueryParam(l.head)) :: toQueryParamsT(l.tail)
    }
  }

  implicit def generic[P <: Product, L <: HList](implicit
    gen: LabelledGeneric.Aux[P, L],
    toQueryParamsL: ToQueryParams[L]
  ): ToQueryParams[P] = new ToQueryParams[P] {
    final def apply(p: P): List[(String, String)] = toQueryParamsL(gen.to(p))
  }
}

abstract class ToQueryParam[T] {
  def apply(t: T): String
}

object ToQueryParam extends ToQueryParam0 {
  implicit def fromShow[T: Show]: ToQueryParam[T] = new ToQueryParam[T] {
    def apply(t: T): String = Show[T].show(t)
  }
}

trait ToQueryParam0 {
  implicit def default[T](implicit lowPriority: LowPriority): ToQueryParam[T] = new ToQueryParam[T] {
    def apply(t: T): String = t.toString
  }
}
