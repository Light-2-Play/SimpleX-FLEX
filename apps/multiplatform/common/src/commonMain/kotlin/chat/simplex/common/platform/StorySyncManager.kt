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
data class StoryXftpDescriptor(
  val id: String,
  val downloadUrl: String,
  val encryptionKey: String, // Base64 AES-256 ключ
  val iv: String,            // Вектор инициализации
  val mediaType: String,
  val durationHours: Int,
  val caption: String,
  val createdAtMillis: Long,
  val thumbBase64: String? = null // Быстрое превью для кружка
)

object StorySyncManager {
  const val STORY_PUSH_PREFIX = "__SIMPLEX_STORY_PUSH__:"
  const val STORY_REQ_PREFIX = "__SIMPLEX_STORY_REQ__"

  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Публикация истории через XFTP:
   * 1. Шифрование и загрузка медиа на XFTP
   * 2. Рассылка лёгкого дескриптора выбранным контактам
   */
  fun publishAndBroadcastStory(
    file: File,
    item: TemporaryContentItem,
    thumbBytes: ByteArray?
  ) {
    scope.launch {
      try {
        // 1. Шифруем и загружаем на XFTP
        val uploadResult = XftpStoryUploader.uploadEncryptedStory(file) ?: run {
          Log.e(TAG, "Failed to upload story to XFTP")
          return@launch
        }

        // 2. Создаем компактный дескриптор (< 1 КБ)
        val descriptor = StoryXftpDescriptor(
          id = item.id,
          downloadUrl = uploadResult.downloadUrl,
          encryptionKey = uploadResult.keyBase64,
          iv = uploadResult.ivBase64,
          mediaType = item.mediaType.name,
          durationHours = item.durationHours,
          caption = item.caption,
          createdAtMillis = item.createdAtMillis,
          thumbBase64 = thumbBytes?.let { Base64.getEncoder().encodeToString(it) }
        )

        val encodedMsg = STORY_PUSH_PREFIX + json.encodeToString(descriptor)

        // 3. Фильтруем получателей по аудитории
        val directChats = chatModel.chats.value.filter {
          val ci = it.chatInfo
          if (ci !is ChatInfo.Direct || !ci.contact.active || !ci.contact.ready) return@filter false
          when (item.audience) {
            TemporaryAudience.VERIFIED_USERS -> ci.contact.verified
            TemporaryAudience.CONTACTS, TemporaryAudience.ALL_USERS -> true
          }
        }

        // 4. Отправляем скрытый дескриптор через SMP
        directChats.forEach { chat ->
          sendHiddenServiceMessage(chat.remoteHostId, chat.chatInfo, encodedMsg)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error in publishAndBroadcastStory: ${e.message}")
      }
    }
  }

  /**
   * Приём входящего служебного сообщения
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
            val desc = json.decodeFromString<StoryXftpDescriptor>(rawJson)

            // 1. Сразу сохраняем миниатюру превью (если передана)
            val thumbBytes = desc.thumbBase64?.let { Base64.getDecoder().decode(it) }

            // 2. Скачиваем и расшифровываем полный файл с XFTP в фоне
            val decryptedFileBytes = XftpStoryUploader.downloadAndDecryptStory(
              downloadUrl = desc.downloadUrl,
              keyBase64 = desc.encryptionKey,
              ivBase64 = desc.iv
            )

            if (decryptedFileBytes != null) {
              val mediaType = TemporaryMediaType.valueOf(desc.mediaType)
              TemporaryContentManager.receiveContactTemporaryContent(
                contactId = senderContactId,
                displayName = senderDisplayName ?: "Contact",
                bytes = decryptedFileBytes,
                mediaType = mediaType,
                durationHours = desc.durationHours,
                caption = desc.caption,
                createdAtMillis = desc.createdAtMillis
              )
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming XFTP story: ${e.message}")
          }
        }
        return true
      }
      text == STORY_REQ_PREFIX -> {
        // Ответ на запрос историй активными дескрипторами
        return true
      }
      else -> return false
    }
  }

  private fun sendHiddenServiceMessage(rhId: Long?, chatInfo: ChatInfo, text: String) {
    withBGApi {
      try {
        // Вызов отправки сообщения через контроллер SimpleX
        chatModel.controller.sendMessage(
          rhId,
          chatInfo,
          text,
          quoteItemId = null,
          timedMessagesTTL = null
        )
      } catch (_: Exception) {}
    }
  }
}
