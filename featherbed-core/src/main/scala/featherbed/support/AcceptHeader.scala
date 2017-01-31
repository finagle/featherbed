package featherbed.support

import shapeless.{:+:, CNil, Coproduct, Witness}

sealed trait AcceptHeader[Accept <: Coproduct] {
  def contentTypes: List[String]
  override def toString: String = contentTypes.mkString(", ") + ", */*; q=0"
}

object AcceptHeader {

  implicit val cnil = new AcceptHeader[CNil] {
    final val contentTypes = List()
    final override def toString: String = "*/*"
  }

  implicit def ccons[H <: String, T <: Coproduct](implicit
    witness: Witness.Aux[H],
    tailHeader: AcceptHeader[T]
  ): AcceptHeader[H :+: T] = new AcceptHeader[H :+: T] {
    final val contentTypes: List[String] = witness.value :: tailHeader.contentTypes
  }
}
