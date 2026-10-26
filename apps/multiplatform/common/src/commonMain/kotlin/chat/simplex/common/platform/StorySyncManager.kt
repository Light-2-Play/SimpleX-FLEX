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
   * Подготовка и сохранение входящей истории
   */
  fun receiveStoryPayload(
    senderContactId: Long,
    senderDisplayName: String,
    rawJson: String
  ) {
    scope.launch {
      try {
        val payload = json.decodeFromString<StorySyncPayload>(rawJson)
        val bytes = Base64.getDecoder().decode(payload.base64Data)
        val mediaType = TemporaryMediaType.valueOf(payload.mediaType)

        TemporaryContentManager.receiveContactTemporaryContent(
          contactId = senderContactId,
          displayName = senderDisplayName,
          bytes = bytes,
          mediaType = mediaType,
          durationHours = payload.durationHours,
          caption = payload.caption,
          createdAtMillis = payload.createdAtMillis
        )
      } catch (e: Exception) {
        Log.e("StorySync", "Error parsing story payload: ${e.message}")
      }
    }
  }

  /**
   * Запрос историй контакта при открытии профиля
   */
  fun requestContactStories(contactId: Long) {
    // Триггер фонового обновления историй контакта
    TemporaryContentManager.purgeExpired()
  }

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

        // Фильтрация контактов по выбранной аудитории (Verified Users / Contacts / All Users)
        val directChats = chatModel.chats.value.filter {
          val ci = it.chatInfo
          if (ci !is ChatInfo.Direct || !ci.contact.active || !ci.contact.ready) return@filter false
          when (item.audience) {
            TemporaryAudience.VERIFIED_USERS -> ci.contact.verified
            TemporaryAudience.CONTACTS -> true
            TemporaryAudience.ALL_USERS -> true
          }
        }

        directChats.forEach { chat ->
          withBGApi {
            chatModel.controller.apiSendTextMessage(
              chat.remoteHostId,
              chat.chatInfo.chatType,
              chat.chatInfo.apiId,
              null,
              encodedMsg
            )
          }
        }
      } catch (e: Exception) {
        Log.e("StorySync", "Error broadcasting story: ${e.message}")
      }
    }
  }
