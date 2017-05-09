package featherbed

import shapeless.Witness

// Finagle 6.43.0 made their Methods vals instead of case objects, eliminating their singleton types
sealed trait Method
object Method {
  final val Post: Post = implicitly[Witness.Aux[Post]].value
  final val Get: Get = implicitly[Witness.Aux[Get]].value
  final val Put: Put = implicitly[Witness.Aux[Put]].value
  final val Patch: Patch = implicitly[Witness.Aux[Patch]].value
  final val Delete: Delete = implicitly[Witness.Aux[Delete]].value
  final val Head: Head = implicitly[Witness.Aux[Head]].value

  type Post = Witness.`"POST"`.T
  type Get = Witness.`"GET"`.T
  type Put = Witness.`"PUT"`.T
  type Patch = Witness.`"PATCH"`.T
  type Delete = Witness.`"DELETE"`.T
  type Head = Witness.`"HEAD"`.T
}
