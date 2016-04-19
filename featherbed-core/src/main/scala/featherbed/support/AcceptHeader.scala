package featherbed.support

import shapeless.{:+:, CNil, Coproduct, HList, Witness}
import shapeless.ops.coproduct.ToHList
import shapeless.ops.hlist.{LiftAll, ToList}


sealed trait AcceptHeader[Accept <: Coproduct] {
  def contentTypes: List[String]
  override def toString = contentTypes.mkString(", ") + ", */*; q=0"
}

object AcceptHeader {

  implicit val cnil = new AcceptHeader[CNil] {
    val contentTypes = List()
  }

  implicit def ccons[H <: String, T <: Coproduct](implicit
    witness: Witness.Aux[H],
    tailHeader: AcceptHeader[T]
  ) : AcceptHeader[H :+: T] = new AcceptHeader[H :+: T] {
    val contentTypes = witness.value :: tailHeader.contentTypes
  }

}