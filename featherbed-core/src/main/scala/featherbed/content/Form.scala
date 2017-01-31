package featherbed.content

import cats.data.{NonEmptyList, Validated}
import com.twitter.finagle.http.FormElement

case class Form(params: NonEmptyList[Validated[Throwable, FormElement]]) {
  def multipart: MultipartForm = MultipartForm(params)
}

case class MultipartForm(params: NonEmptyList[Validated[Throwable, FormElement]])
