package featherbed.content

import java.nio.charset.{Charset, CodingErrorAction}
import scala.util.Try

import cats.data.{Validated, ValidatedNel}
import com.twitter.finagle.http.Response
import com.twitter.io.Buf
import shapeless.Witness
import sun.nio.cs.ThreadLocalCoders


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

  implicit def decodeEither[Error, Success, ContentType <: String](implicit
    decodeError: Aux[ContentType, Error],
    decodeSuccess: Aux[ContentType, Success],
    witness: Witness.Aux[ContentType]
  ): Aux[ContentType, Either[Error, Success]] = new Decoder[ContentType] {
    type Out = Either[Error, Success]
    val contentType: String = witness.value
    def apply(buf: Response): ValidatedNel[Throwable, Either[Error, Success]] =
      decodeSuccess(buf).map(Right(_)) orElse decodeError(buf).map(Left(_))
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
