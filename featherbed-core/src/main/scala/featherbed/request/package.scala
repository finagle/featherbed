package featherbed

import cats.data.{NonEmptyList, ValidatedNel}
import com.twitter.finagle.http.FormElement

package object request {

  type FormRight = Right[None.type, NonEmptyList[ValidatedNel[Throwable, FormElement]]]
}
