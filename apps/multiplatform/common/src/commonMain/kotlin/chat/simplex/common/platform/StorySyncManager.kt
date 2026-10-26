package chat.simplex.common.platform

import chat.simplex.common.model.*
import chat.simplex.common.views.helpers.withBGApi
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
   * Рассылка созданной истории контактам с учётом выбранной аудитории (Verified / Contacts / All)
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
          if (ci !is ChatInfo.Direct || !ci.contact.active || !ci.contact.ready) return@filter false
          when (item.audience) {
            TemporaryAudience.VERIFIED_USERS -> ci.contact.verified
            TemporaryAudience.CONTACTS, TemporaryAudience.ALL_USERS -> true
          }
        }

        directChats.forEach { chat ->
          withBGApi {
            chatModel.controller.sendMessage(
              chat.remoteHostId,
              chat.chatInfo,
              encodedMsg,
              quoteItemId = null,
              timedMessagesTTL = null
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error broadcasting story: ${e.message}")
      }
    }
  }

  /**
   * Автоматический тихий запрос историй при входе в профиль контакта
   */
  fun requestContactStories(contactId: Long) {
    scope.launch {
      val targetChat = chatModel.chats.value.firstOrNull {
        (it.chatInfo as? ChatInfo.Direct)?.contact?.contactId == contactId
      } ?: return@launch

      withBGApi {
        chatModel.controller.sendMessage(
          targetChat.remoteHostId,
          targetChat.chatInfo,
          STORY_REQ_PREFIX,
          quoteItemId = null,
          timedMessagesTTL = null
        )
      }
    }
  }

  /**
   * Ответ собеседнику на его запрос историй
   */
  fun respondToStoryRequest(remoteHostId: Long?, chatId: String) {
    scope.launch {
      val targetChat = chatModel.chats.value.firstOrNull { it.id == chatId } ?: return@launch
      val myStories = TemporaryContentManager.getActiveItemsFor(null)
      if (myStories.isEmpty()) return@launch

      val contact = (targetChat.chatInfo as? ChatInfo.Direct)?.contact

      myStories.forEach { story ->
        val canSend = when (story.audience) {
          TemporaryAudience.VERIFIED_USERS -> contact?.verified == true
          TemporaryAudience.CONTACTS, TemporaryAudience.ALL_USERS -> true
        }
        if (!canSend) return@forEach

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
            chatModel.controller.sendMessage(
              remoteHostId,
              targetChat.chatInfo,
              encodedMsg,
              quoteItemId = null,
              timedMessagesTTL = null
            )
          }
        } catch (_: Exception) {}
      }
    }
  }

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
