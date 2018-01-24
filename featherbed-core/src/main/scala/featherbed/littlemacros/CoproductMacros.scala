package featherbed.littlemacros

import scala.reflect.macros.whitebox

import shapeless.{:+:, CNil, Coproduct}

class CoproductMacros(val c: whitebox.Context) {
  import c.universe._

  /**
    * Support the syntax `accept("foo/bar", "foz/baz"...)`
    * @param types Content types
    * @return Passes through to accept[ContentTypes]
    */
  def callAcceptCoproduct[A <: Coproduct: WeakTypeTag](types: Tree*): Tree = {
    val lhs = c.prefix.tree
    val accepts = types map {
      case Literal(const @ Constant(str: String)) => c.internal.constantType(const)
      case other => c.abort(c.enclosingPosition, s"$other is not a string constant")
    }

    val cnil = weakTypeOf[CNil]
    val ccons = weakTypeOf[:+:[_, _]].typeConstructor

    val coproductType =
      accepts.foldRight(cnil) { case (elemTpe, acc) =>
        appliedType(ccons, List(elemTpe, acc))
      }

    q"$lhs.accept[$coproductType]"
  }
}
