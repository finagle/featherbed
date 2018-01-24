package featherbed.content

import java.nio.CharBuffer
import java.nio.charset.{Charset, CodingErrorAction}
import scala.util.Try

import cats.data.{Validated, ValidatedNel}
import com.twitter.io.Buf
import shapeless.Witness
import sun.nio.cs.ThreadLocalCoders


trait Encoder[A, ForContentType] {
  def apply(value: A, charset: Charset): ValidatedNel[Throwable, Buf]
}

object Encoder extends LowPriorityEncoders {
  def of[A, T <: String](t: T)(fn: (A, Charset) => ValidatedNel[Throwable, Buf]): Encoder[A, t.type] =
    new Encoder[A, t.type] {
      def apply(value: A, charset: Charset) = fn(value, charset)
    }

  def encodeString(value: String, charset: Charset): ValidatedNel[Throwable, Buf] = {
    val encoder = ThreadLocalCoders.encoderFor(charset)
    Validated.fromTry(Try(encoder
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .encode(CharBuffer.wrap(value)))).toValidatedNel.map[Buf](Buf.ByteBuffer.Owned(_))
  }
}

private[featherbed] trait LowPriorityEncoders {
  implicit val plainTextEncoder: Encoder[String, Witness.`"text/plain"`.T] = Encoder.of("text/plain") {
    case (value, charset) => Encoder.encodeString(value, charset)
  }
}
