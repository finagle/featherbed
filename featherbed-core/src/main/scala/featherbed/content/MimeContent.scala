package featherbed.content

import shapeless.Witness


case class MimeContent[Content, ContentType](content: Content, contentType: ContentType)

object MimeContent {
  type WebForm = Witness.`"application/x-www-form-urlencoded"`.T
  type Json    = Witness.`"application/json"`.T
  val NoContent: MimeContent[None.type, None.type] = MimeContent(None, None)

  def apply[Content, ContentType](content: Content)(implicit
    witness: Witness.Aux[ContentType]
  ): MimeContent[Content, ContentType] = MimeContent(content, witness.value)
}
