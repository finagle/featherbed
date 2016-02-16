package featherbed

import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, Charset}

import cats.data.{Validated, ValidatedNel}
import com.twitter.finagle.http.Response
import com.twitter.io.Buf
import shapeless.Witness
import sun.nio.cs.ThreadLocalCoders

import scala.util.Try


package object content {

  type ContentType = String

  trait Decoder[ContentType] {
    type Out
    val contentType: String //widened version of Out
    def apply(buf: Response): ValidatedNel[Throwable, Out]
  }

  object Decoder extends LowPriorityDecoders {

    type Aux[CT, A1] = Decoder[CT] { type Out = A1 }

    def of[T <: ContentType, A1](t: T)(fn: Response => ValidatedNel[Throwable,A1]): Decoder.Aux[t.type, A1] = new Decoder[t.type] {
      type Out = A1
      val contentType = t
      def apply(response: Response) = fn(response)
    }

    def decodeString(response: Response) = {
      Validated.fromTry(Try {
        response.charset.map(Charset.forName).getOrElse(Charset.defaultCharset)
      }) andThen { charset =>
        val decoder = ThreadLocalCoders.decoderFor(charset)
        Validated.fromTry(
          Try(
            decoder
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(Buf.ByteBuffer.Owned.extract(response.content).asReadOnlyBuffer()))).map[String](_.toString)
      } toValidatedNel
    }

  }

  private[featherbed] trait LowPriorityDecoders {

    implicit val plainTextDecoder : Decoder.Aux[Witness.`"text/plain"`.T, String] = Decoder.of("text/plain") {
      response => Decoder.decodeString(response)
    }

    implicit val anyResponseDecoder : Decoder.Aux[Witness.`"*/*"`.T, Response] = Decoder.of("*/*") {
      response => Validated.Valid(response)
    }

  }


  trait Encoder[A, ForContentType] {
    def apply(value: A, charset: Charset): ValidatedNel[Throwable, Buf]
  }

  object Encoder extends LowPriorityEncoders {

    def of[A, T <: ContentType](t : T)(fn: (A, Charset) => ValidatedNel[Throwable, Buf]) = new Encoder[A, t.type] {
      def apply(value: A, charset: Charset) = fn(value, charset)
    }

    def encodeString(value: String, charset: Charset) = {
      val encoder = ThreadLocalCoders.encoderFor(charset)
      Validated.fromTry(Try(encoder
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value)))).toValidatedNel.map[Buf](Buf.ByteBuffer.Owned(_))
    }

  }

  private[featherbed] trait LowPriorityEncoders {

    implicit val plainTextEncoder : Encoder[String, Witness.`"text/plain"`.T] = Encoder.of("text/plain") {
      case (value, charset) => Encoder.encodeString(value, charset)
    }

  }

}
