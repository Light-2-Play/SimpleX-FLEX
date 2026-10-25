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
  val id: String,
  val mediaType: String,
  val durationHours: Int,
  val caption: String,
  val createdAtMillis: Long,
  val base64Data: String
)

object StorySyncManager {
  const val STORY_PUSH_PREFIX = "__SIMPLEX_STORY_PUSH__:"
  const val STORY_REQ_PREFIX = "__SIMPLEX_STORY_REQ__"

  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Рассылка опубликованной истории всем активным прямым контактам
   */
  fun broadcastStory(bytes: ByteArray, item: TemporaryContentItem) {
    scope.launch {
      try {
        val payload = StorySyncPayload(
          id = item.id,
          mediaType = item.mediaType.name,
          durationHours = item.durationHours,
          caption = item.caption,
          createdAtMillis = item.createdAtMillis,
          base64Data = Base64.getEncoder().encodeToString(bytes)
        )
        val encodedMsg = STORY_PUSH_PREFIX + json.encodeToString(payload)

        val directChats = chatModel.chats.value.filter {
          val ci = it.chatInfo
          ci is ChatInfo.Direct && ci.contact.active && ci.contact.ready
        }

        directChats.forEach { chat ->
          withBGApi {
            chatModel.controller.apiSendMessage(
              chat.remoteHostId,
              chat.id,
              MsgContent.MCText(encodedMsg)
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "broadcastStory error: ${e.message}")
      }
    }
  }

  /**
   * Запрос активных историй при открытии профиля контакта
   */
  fun requestContactStories(contactId: Long) {
    scope.launch {
      val targetChat = chatModel.chats.value.firstOrNull {
        (it.chatInfo as? ChatInfo.Direct)?.contact?.contactId == contactId
      } ?: return@launch

      withBGApi {
        chatModel.controller.apiSendMessage(
          targetChat.remoteHostId,
          targetChat.id,
          MsgContent.MCText(STORY_REQ_PREFIX)
        )
      }
    }
  }

  /**
   * Ответ на запрос историй от собеседника
   */
  fun respondToStoryRequest(remoteHostId: Long?, chatId: String) {
    scope.launch {
      val myStories = TemporaryContentManager.getActiveItemsFor(null)
      if (myStories.isEmpty()) return@launch

      myStories.forEach { story ->
        try {
          val file = File(story.filePath)
          if (!file.exists()) return@forEach
          val bytes = file.readBytes()
          val payload = StorySyncPayload(
            id = story.id,
            mediaType = story.mediaType.name,
            durationHours = story.durationHours,
            caption = story.caption,
            createdAtMillis = story.createdAtMillis,
            base64Data = Base64.getEncoder().encodeToString(bytes)
          )
          val encodedMsg = STORY_PUSH_PREFIX + json.encodeToString(payload)
          withBGApi {
            chatModel.controller.apiSendMessage(
              remoteHostId,
              chatId,
              MsgContent.MCText(encodedMsg)
            )
          }
        } catch (_: Exception) {}
      }
    }
  }

  /**
   * Обработка входящего служебного сообщения
   * @return true, если сообщение было служебным и его нужно скрыть из списка чата
   */
  fun handleIncomingMessage(
    remoteHostId: Long?,
    chatId: String,
    senderContactId: Long?,
    senderDisplayName: String?,
    text: String
  ): Boolean {
    when {
      text.startsWith(STORY_PUSH_PREFIX) -> {
        if (senderContactId == null) return true
        scope.launch {
          try {
            val rawJson = text.removePrefix(STORY_PUSH_PREFIX)
            val payload = json.decodeFromString<StorySyncPayload>(rawJson)
            val bytes = Base64.getDecoder().decode(payload.base64Data)
            val mediaType = TemporaryMediaType.valueOf(payload.mediaType)

            TemporaryContentManager.receiveContactTemporaryContent(
              contactId = senderContactId,
              displayName = senderDisplayName ?: "Contact",
              bytes = bytes,
              mediaType = mediaType,
              durationHours = payload.durationHours,
              caption = payload.caption,
              createdAtMillis = payload.createdAtMillis
            )
          } catch (e: Exception) {
            Log.e(TAG, "receive story error: ${e.message}")
          }
        }
        return true
      }
      text == STORY_REQ_PREFIX -> {
        respondToStoryRequest(remoteHostId, chatId)
        return true
      }
      else -> return false
    }
  }
}
