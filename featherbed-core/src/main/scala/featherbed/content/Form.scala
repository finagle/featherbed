package featherbed.content

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import com.twitter.finagle.http.FormElement

case class Form(params: NonEmptyList[FormElement]) {
  def multipart: MultipartForm = MultipartForm(Validated.valid(params))
}

case class MultipartForm(params: ValidatedNel[Throwable, NonEmptyList[FormElement]])
