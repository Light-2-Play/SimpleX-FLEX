package chat.simplex.common.platform

import chat.simplex.common.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

@Serializable
data class StorySyncPayload(
  val mediaType: String,
  val durationHours: Int,
  val caption: String,
  val createdAtMillis: Long,
  val base64Data: String
)

object StorySyncManager {
  const val STORY_MSG_PREFIX = "__SIMPLEX_FLEX_STORY__:"
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Рассылка созданной истории всем прямым контактам через скрытые E2E-сообщения
   */
  fun broadcastStory(
    chatModel: ChatModel,
    bytes: ByteArray,
    mediaType: TemporaryMediaType,
    duration: TemporaryDuration,
    caption: String
  ) {
    scope.launch {
      try {
        val payload = StorySyncPayload(
          mediaType = mediaType.name,
          durationHours = duration.hours,
          caption = caption,
          createdAtMillis = System.currentTimeMillis(),
          base64Data = Base64.getEncoder().encodeToString(bytes)
        )
        val encodedPayload = STORY_MSG_PREFIX + json.encodeToString(payload)

        val directChats = chatModel.chats.value.filter {
          val ci = it.chatInfo
          ci is ChatInfo.Direct && ci.contact.active && ci.contact.ready
        }

        directChats.forEach { chat ->
          withBGApi {
            chatModel.controller.apiSendMessage(
              chat.remoteHostId,
              chat.id,
              MsgContent.MCText(encodedPayload)
            )
          }
        }
      } catch (e: Exception) {
        Log.e("StorySync", "Error broadcasting story: ${e.message}")
      }
    }
  }

  /**
   * Обработка входящего служебного сообщения на устройстве собеседника
   * @return true если сообщение было служебным пакетом сторис (его нужно скрыть из чата)
   */
  fun handleIncomingStoryMessage(senderContactId: Long, displayName: String, text: String): Boolean {
    if (!text.startsWith(STORY_MSG_PREFIX)) return false

    scope.launch {
      try {
        val rawJson = text.removePrefix(STORY_MSG_PREFIX)
        val payload = json.decodeFromString<StorySyncPayload>(rawJson)
        val bytes = Base64.getDecoder().decode(payload.base64Data)
        val mediaType = TemporaryMediaType.valueOf(payload.mediaType)

        TemporaryContentManager.receiveContactTemporaryContent(
          contactId = senderContactId,
          displayName = displayName,
          bytes = bytes,
          mediaType = mediaType,
          durationHours = payload.durationHours,
          caption = payload.caption,
          createdAtMillis = payload.createdAtMillis
        )
      } catch (e: Exception) {
        Log.e("StorySync", "Error receiving story from contact: ${e.message}")
      }
    }
    return true
  }
}
