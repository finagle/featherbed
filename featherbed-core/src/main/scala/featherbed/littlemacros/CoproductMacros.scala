package featherbed.littlemacros

import macrocompat.bundle
import shapeless.{:+:, CNil}

import scala.reflect.macros.whitebox

@bundle
class CoproductMacros(val c: whitebox.Context) {
  import c.universe._

  /**
    * Support the syntax `accept("foo/bar", "foz/baz"...)`
    *
    * This is done instead of using [[shapeless.SingletonProductArgs]] because the latter disrupts type inference in
    * most IDEs (besides ensime)
    *
    * @param types Content types
    * @return Passes through to accept[ContentTypes]
    */
  def callAcceptCoproduct(types: Tree*) : Tree = {
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
