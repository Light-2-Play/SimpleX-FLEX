package chat.simplex.common.views.chat.group

import kotlinx.serialization.json.*

object FeedParsers {

  fun parseActivityPubJson(jsonString: String): List<FeedEntry> {
    val entries = mutableListOf<FeedEntry>()
    val json = Json { ignoreUnknownKeys = true }
    val element = json.parseToJsonElement(jsonString)

    if (element is JsonArray) {
      element.forEach { item ->
        val obj = item.jsonObject
        val id = obj["id"]?.jsonPrimitive?.content ?: return@forEach
        val url = obj["url"]?.jsonPrimitive?.contentOrNull
        val contentHtml = obj["content"]?.jsonPrimitive?.content ?: ""
        val text = stripHtml(contentHtml)
        val author = obj["account"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull
          ?: obj["account"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull

        entries.add(
          FeedEntry(
            id = id,
            title = text.take(60).replace("\n", " "),
            contentText = text,
            link = url,
            author = author,
            publishedTs = System.currentTimeMillis()
          )
        )
      }
    } else if (element is JsonObject && element.containsKey("orderedItems")) {
      element["orderedItems"]?.jsonArray?.forEach { item ->
        val obj = item.jsonObject
        val actObj = obj["object"]?.jsonObject ?: obj
        val id = actObj["id"]?.jsonPrimitive?.content ?: return@forEach
        val text = stripHtml(actObj["content"]?.jsonPrimitive?.content ?: "")
        val url = actObj["url"]?.jsonPrimitive?.contentOrNull

        entries.add(
          FeedEntry(
            id = id,
            title = text.take(60),
            contentText = text,
            link = url,
            author = actObj["attributedTo"]?.jsonPrimitive?.contentOrNull,
            publishedTs = System.currentTimeMillis()
          )
        )
      }
    }
    return entries
  }

  fun parseXmlFeed(xml: String): List<FeedEntry> {
    val entries = mutableListOf<FeedEntry>()
    val isAtom = xml.contains("<feed", ignoreCase = true)

    if (isAtom) {
      val entryBlocks = xml.split("<entry>").drop(1)
      for (block in entryBlocks) {
        val entryXml = block.substringBefore("</entry>")
        val id = extractTag(entryXml, "id") ?: extractTag(entryXml, "link") ?: continue
        val title = extractTag(entryXml, "title") ?: ""
        val summary = extractTag(entryXml, "summary") ?: extractTag(entryXml, "content") ?: ""
        val link = extractAttr(entryXml, "link", "href") ?: extractTag(entryXml, "link")

        entries.add(
          FeedEntry(
            id = id,
            title = stripHtml(title),
            contentText = stripHtml(summary),
            link = link,
            author = extractTag(entryXml, "name"),
            publishedTs = System.currentTimeMillis()
          )
        )
      }
    } else {
      val itemBlocks = xml.split("<item>").drop(1)
      for (block in itemBlocks) {
        val itemXml = block.substringBefore("</item>")
        val id = extractTag(itemXml, "guid") ?: extractTag(itemXml, "link") ?: continue
        val title = extractTag(itemXml, "title") ?: ""
        val description = extractTag(itemXml, "description") ?: ""
        val link = extractTag(itemXml, "link")

        entries.add(
          FeedEntry(
            id = id,
            title = stripHtml(title),
            contentText = stripHtml(description),
            link = link,
            author = extractTag(itemXml, "author") ?: extractTag(itemXml, "dc:creator"),
            publishedTs = System.currentTimeMillis()
          )
        )
      }
    }
    return entries
  }

  private fun extractTag(xml: String, tag: String): String? {
    val pattern = Regex("<$tag(?:\\s+[^>]*)?>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?<\\/$tag>", RegexOption.DOT_MATCHES_ALL)
    return pattern.find(xml)?.groupValues?.get(1)?.trim()
  }

  private fun extractAttr(xml: String, tag: String, attr: String): String? {
    val pattern = Regex("<$tag[^>]*$attr=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
    return pattern.find(xml)?.groupValues?.get(1)?.trim()
  }

  private fun stripHtml(html: String): String {
    return html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
      .replace(Regex("<p/?>", RegexOption.IGNORE_CASE), "\n\n")
      .replace(Regex("<[^>]*>"), "")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .trim()
  }
}
