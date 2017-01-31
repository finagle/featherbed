package featherbed

import java.nio.CharBuffer
import java.nio.charset.{Charset, CodingErrorAction}

import scala.util.Try

import cats.data.{Validated, ValidatedNel}
import com.twitter.finagle.http.Response
import com.twitter.io.Buf
import shapeless.{CNil, Witness}
import sun.nio.cs.ThreadLocalCoders

package object content {

  trait Decoder[ContentType] {
    type Out
    val contentType: String //widened version of ContentType
    def apply(buf: Response): ValidatedNel[Throwable, Out]
  }

  object Decoder extends LowPriorityDecoders {
    type Aux[CT, A1] = Decoder[CT] { type Out = A1 }

    def of[T <: String, A1](t: T)(fn: Response => ValidatedNel[Throwable, A1]): Decoder.Aux[t.type, A1] =
      new Decoder[t.type] {
        type Out = A1
        val contentType = t
        def apply(response: Response) = fn(response)
      }

    def decodeString(response: Response): ValidatedNel[Throwable, String] = {
      Validated.fromTry(Try {
        response.charset.map(Charset.forName).getOrElse(Charset.defaultCharset)
      }).andThen { charset: Charset =>
        val decoder = ThreadLocalCoders.decoderFor(charset)
        Validated.fromTry(
          Try(
            decoder
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(Buf.ByteBuffer.Owned.extract(response.content).asReadOnlyBuffer()))).map[String](_.toString)
      }.toValidatedNel
    }
  }

  private[featherbed] trait LowPriorityDecoders {
    implicit val plainTextDecoder: Decoder.Aux[Witness.`"text/plain"`.T, String] = Decoder.of("text/plain") {
      response => Decoder.decodeString(response)
    }

    implicit val anyResponseDecoder: Decoder.Aux[Nothing, Response] = new Decoder[Nothing] {
      type Out = Response
      final val contentType: String = "*/*"
      final def apply(rep: Response): ValidatedNel[Throwable, Response] = Validated.Valid(rep)
    }
  }

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
}
