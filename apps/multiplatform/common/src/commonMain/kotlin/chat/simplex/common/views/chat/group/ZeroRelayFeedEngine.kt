package chat.simplex.common.views.chat.group

import chat.simplex.common.model.*
import chat.simplex.common.model.ChatModel.controller
import chat.simplex.common.platform.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

object ZeroRelayFeedEngine {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  fun syncFeed(
    chatModel: ChatModel,
    sub: FeedSubscription,
    onUpdated: (FeedSubscription) -> Unit
  ) {
    scope.launch {
      try {
        val rawBody = fetchUrl(sub.url) ?: return@launch
        val entries = when (sub.type) {
          FeedType.RSS_ATOM -> FeedParsers.parseXmlFeed(rawBody)
          FeedType.MASTODON_ACTIVITYPUB -> FeedParsers.parseActivityPubJson(rawBody)
        }

        val freshEntries = entries.filter { it.id !in sub.seenItemIds }.take(5)
        if (freshEntries.isEmpty()) return@launch

        val targetChat = chatModel.chats.value.firstOrNull { 
          (it.chatInfo as? ChatInfo.Group)?.groupInfo?.groupId == sub.targetGroupId 
        } ?: return@launch

        for (entry in freshEntries.reversed()) {
          val markdownText = buildString {
            if (entry.author != null) append("**${entry.author}**:\n")
            if (entry.title.isNotBlank() && entry.title != entry.contentText.take(entry.title.length)) {
              append("### ${entry.title}\n\n")
            }
            append(entry.contentText)
            if (!entry.link.isNullOrBlank()) {
              append("\n\n🔗 [Original post](${entry.link})")
            }
          }

          val mc = MsgContent.MCText(markdownText)
          val cInfo = targetChat.chatInfo

          controller.apiSendMessages(
            rh = targetChat.remoteHostId,
            type = cInfo.chatType,
            id = cInfo.apiId,
            scope = cInfo.groupChatScope(),
            sendAsGroup = cInfo.sendAsGroup,
            live = false,
            ttl = null,
            sign = false,
            composedMessages = listOf(ComposedMessage(null, null, mc, emptyMap()))
          )
          delay(800)
        }

        val updatedSeen = (sub.seenItemIds + freshEntries.map { it.id }).takeLast(200).toSet()
        onUpdated(
          sub.copy(
            lastFetchedTs = System.currentTimeMillis(),
            seenItemIds = updatedSeen
          )
        )
      } catch (e: Exception) {
        Log.e("ZeroRelayFeedEngine", "Error fetching ${sub.url}: ${e.message}")
      }
    }
  }

  private fun fetchUrl(urlString: String): String? {
    return try {
      val url = URL(urlString)
      val connection = url.openConnection() as HttpURLConnection
      connection.connectTimeout = 15000
      connection.readTimeout = 15000
      connection.setRequestProperty("User-Agent", "SimpleX-FLEX/1.0 ZeroRelayReader")
      connection.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
      null
    }
  }
}
