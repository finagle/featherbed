package featherbed.content

import java.io.{File, FileInputStream}
import java.nio.channels.FileChannel
import scala.util.control.NonFatal

import cats.Show
import cats.data.{Validated, ValidatedNel}
import cats.data.Validated.{Invalid, Valid}
import cats.instances.list._
import cats.syntax.traverse._
import com.twitter.finagle.http.{FileElement, FormElement, SimpleElement}
import com.twitter.io.Buf
import shapeless._
import shapeless.labelled.FieldType


abstract class ToFormParams[T] {
  def apply(t: T): ValidatedNel[Throwable, List[FormElement]]
}

object ToFormParams {

  final case class Instance[T](fn: T => ValidatedNel[Throwable, List[FormElement]]) extends ToFormParams[T] {
    def apply(t: T): ValidatedNel[Throwable, List[FormElement]] = fn(t)
  }

  implicit val hnil: ToFormParams[HNil] = Instance(hnil => Valid(Nil))

  implicit def hcons[K <: Symbol, H, T <: HList](implicit
    name: Witness.Aux[K],
    toFormParamH: ToFormParam[H],
    toFormParamsT: ToFormParams[T]
  ): ToFormParams[FieldType[K, H] :: T] = Instance {
    t => toFormParamsT(t.tail) andThen {
      tail => toFormParamH(name.value.name, t.head).map(head => head :: tail)
    }
  }

  implicit def generic[P <: Product, L <: HList](implicit
    gen: LabelledGeneric.Aux[P, L],
    toFormParamsL: ToFormParams[L]
  ): ToFormParams[P] = Instance(p => toFormParamsL(gen.to(p)))

  implicit val form: ToFormParams[Form] = Instance(form => Validated.valid(form.params.toList))
  implicit val multipartForm: ToFormParams[MultipartForm] = Instance(form => form.params.map(_.toList))

}

abstract class ToFormParam[T] {
  def apply(name: String, t: T): ValidatedNel[Throwable, FormElement]
}

object ToFormParam extends ToFormParam0 {

  final case class Instance[T](fn: (String, T) => ValidatedNel[Throwable, FormElement]) extends ToFormParam[T] {
    def apply(name: String, t: T): ValidatedNel[Throwable, FormElement] = fn(name, t)
  }

  implicit val file: ToFormParam[File] = Instance {
    (name, f) =>
      try {
        val stream = new FileInputStream(f)
        val channel = stream.getChannel
        val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        channel.close()
        Valid(FileElement(name, Buf.ByteBuffer.Owned(buf), filename = Some(f.getName)))
      } catch {
        case NonFatal(err) => Invalid(err).toValidatedNel
      }
  }


  implicit val string: ToFormParam[String] = Instance {
    (name, str) => Valid(SimpleElement(name, str))
  }

}

trait ToFormParam0 extends ToFormParam1 {
  implicit def fromShow[T: Show]: ToFormParam[T] = ToFormParam.Instance {
    (name, t) => Valid(SimpleElement(name, Show[T].show(t)))
  }
}

trait ToFormParam1 {
  implicit def default[T](implicit lowPriority: LowPriority): ToFormParam[T] = ToFormParam.Instance {
    (name, t) => Valid(SimpleElement(name, t.toString))
  }
}
