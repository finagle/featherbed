package featherbed.auth

import com.twitter.finagle.Filter
import com.twitter.finagle.http.{Request, Response}

trait Authorizer extends Filter[Request, Response, Request, Response]
