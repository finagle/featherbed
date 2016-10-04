package featherbed.auth

import java.nio.charset.{Charset, StandardCharsets}
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, UUID}

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import javax.crypto.spec.SecretKeySpec

object OAuth2 {

  /**
    * RFC 6750 - OAuth2 Bearer Token
    * https://tools.ietf.org/html/rfc6750
    *
    * @param token The OAuth2 Bearer Token
    */
  case class Bearer(token: String) extends Authorizer {
    def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] = {
      request.authorization = s"Bearer $token"
      service(request)
    }
  }

  /**
    * IETF Draft for OAuth2 MAC Tokens
    * https://tools.ietf.org/html/draft-ietf-oauth-v2-http-mac-02
    *
    * @param keyIdentifier The MAC Key Identifier
    * @param macKey        The MAC Secret Key
    * @param algorithm     The MAC Algorithm (Mac.Sha1 or Mac.SHA256)
    * @param ext           A function which computes some "extension text" to be covered by the MAC signature
    */
  case class Mac(
    keyIdentifier: String,
    macKey: String,
    algorithm: Mac.Algorithm,
    ext: Request => Option[String] = (req) => None
  ) extends Authorizer {

    import Mac._

    def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] = {
      val keyBytes = macKey.getBytes(requestCharset(request))
      val timestamp = Instant.now()
      val nonce = UUID.randomUUID().toString
      val signature = sign(
        keyBytes, algorithm, request, timestamp, nonce, ext
      )
      val authFields = List(
        "id" -> keyIdentifier,
        "timestamp" -> timestamp.getEpochSecond.toString,
        "nonce" -> nonce,
        "mac" -> Base64.getEncoder.encodeToString(signature)
      ) ++ List(ext(request).map("ext" -> _)).flatten

      val auth = "MAC " + authFields.map {
        case (key, value) => s""""$key"="$value""""
      }.mkString(", ")
      request.authorization = auth
      service(request)
    }
  }

  object Mac {
    sealed trait Algorithm {
      def name: String
    }
    case object Sha1 extends Algorithm   { val name = "HmacSHA1" }
    case object Sha256 extends Algorithm { val name = "HmacSHA256" }

    private def requestCharset(request: Request) =
      request.charset.map(Charset.forName).getOrElse(StandardCharsets.UTF_8)

    private def sign(
      key: Array[Byte],
      algorithm: Mac.Algorithm,
      request: Request,
      timestamp: Instant,
      nonce: String,
      ext: Request => Option[String]
    ) = {
      val stringToSign = normalizedRequestString(request, timestamp, nonce, ext)
      val signingKey = new SecretKeySpec(key, algorithm.name)
      val mac = javax.crypto.Mac.getInstance(algorithm.name)
      mac.init(signingKey)
      mac.doFinal(stringToSign.getBytes(requestCharset(request)))
    }

    private def normalizedRequestString(
      request: Request,
      timestamp: Instant,
      nonce: String,
      ext: Request => Option[String]
    ) = {
      val hostAndPort = request.host.map(_.span(_ == ':')).map {
        case (h, p) => h -> Option(p.stripPrefix(":")).filter(_.nonEmpty)
      }
      val host = hostAndPort.map(_._1)
      val port = hostAndPort.flatMap(_._2)
      Seq(
        timestamp.getEpochSecond.toString,
        nonce,
        request.method.toString().toUpperCase,
        request.uri,
        host.getOrElse(""),
        port.getOrElse(request.remotePort.toString),
        ext(request).getOrElse(""),
        ""
      ).mkString("\n")
    }
  }

}
