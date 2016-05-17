package featherbed.support

case class RuntimeContentType(mediaType: String, params: Map[String, String])

object ContentTypeSupport {
  def contentTypePieces(ct: String): Option[RuntimeContentType] = ct.split(';').toList.map(_.trim) match {
    case head :: rest => Some(RuntimeContentType(head, rest.map {
      str => str.indexOf('=') match {
        case -1 => (str, "")
        case n => str.splitAt(n)
      }
    }.toMap))

    case Nil => None
  }
}
