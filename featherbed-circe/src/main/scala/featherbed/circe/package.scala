package featherbed

import java.nio.charset.Charset

import cats.data.ValidatedNel
import cats.implicits._
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import shapeless.Witness

package object circe {

  private val printer = Printer.noSpaces.copy(dropNullKeys = true)

  implicit def circeEncoder[A: Encoder]: content.Encoder[A, Witness.`"application/json"`.T] =
    content.Encoder.of("application/json") {
      (value: A, charset: Charset) => content.Encoder.encodeString(printer.pretty(value.asJson), charset)
    }

  implicit def circeDecoder[A: Decoder]: content.Decoder.Aux[Witness.`"application/json"`.T, A] =
    content.Decoder.of("application/json") {
      response =>
        content.Decoder.decodeString(response).andThen {
          str => (parse(str).toValidated.toValidatedNel: ValidatedNel[Throwable, Json]).andThen {
            json: Json => json.as[A].toValidated.toValidatedNel: ValidatedNel[Throwable, A]
          }
        }
    }

}
