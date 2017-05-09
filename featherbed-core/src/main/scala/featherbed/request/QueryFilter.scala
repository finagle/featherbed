package featherbed.request

import cats.data.ValidatedNel
import com.twitter.finagle.{Filter, Service}
import com.twitter.util.Future
import featherbed.content.ToQueryParams
import shapeless.Coproduct

class QueryFilter[Params, Req, Result](
  toReq: List[(String, String)] => Future[Req])(implicit
  toQueryParams: ToQueryParams[Params]
) extends Filter[Params, Result, Req, Result] {
  def apply(req: Params, service: Service[Req, Result]): Future[Result] = for {
    request  <- toReq(toQueryParams(req))
    response <- service(request)
  } yield response
}
