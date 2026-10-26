package chat.simplex.common.platform

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import chat.simplex.common.model.*
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

@Serializable
data class StoryXftpDescriptor(
  val id: String,
  val downloadUrl: String? = null,
  val directBase64: String? = null,
  val encryptionKey: String,
  val iv: String,
  val mediaType: String,
  val durationHours: Int,
  val caption: String,
  val createdAtMillis: Long,
  val thumbBase64: String? = null
)

object StorySyncManager {
  const val STORY_PUSH_PREFIX = "__SIMPLEX_STORY_PUSH__:"
  const val STORY_REQ_PREFIX = "__SIMPLEX_STORY_REQ__"

  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var observerJob: Job? = null

  /**
   * Фоновый наблюдатель: перехватывает служебные пакеты сторис моментально,
   * даже если чат закрыт, и сразу бесследно стирает их из базы сообщений.
   */
  fun startBackgroundObserver(chatModel: ChatModel) {
    observerJob?.cancel()
    observerJob = scope.launch {
      snapshotFlow { chatModel.chats.value }.collect { chats: List<Chat> ->
        chats.forEach { chat ->
          val directContact = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: return@forEach

          val unhandledItems = chat.chatItems.filter { item ->
            !item.chatDir.sent && (
              item.content.text.contains(STORY_PUSH_PREFIX) ||
              item.content.text.contains(STORY_REQ_PREFIX)
            )
          }

          unhandledItems.forEach { item ->
            val text = item.content.text.trim()
            handleIncomingMessage(
              remoteHostId = chat.remoteHostId,
              chatId = chat.id,
              senderContactId = directContact.contactId,
              senderDisplayName = directContact.displayName,
              text = text
            )

            // Тихо удаляем служебную команду из локальной базы данных
            withBGApi {
              try {
                chatModel.controller.apiDeleteChatItems(
                  chat.remoteHostId,
                  type = chat.chatInfo.chatType,
                  id = chat.chatInfo.apiId,
                  scope = chat.chatInfo.groupChatScope(),
                  itemIds = listOf(item.id),
                  mode = CIDeleteMode.cidmInternal
                )
              } catch (_: Exception) {}
            }
          }
        }
      }
    }
  }

  fun publishAndBroadcastStory(
    file: File,
    item: TemporaryContentItem,
    thumbBytes: ByteArray?,
    targetChats: List<Chat>? = null
  ) {
    scope.launch {
      try {
        val rawBytes = file.readBytes()
        val descriptor: StoryXftpDescriptor

        // Фотографии сжимаем и передаем прямо через SMP без сторонних узлов
        if (item.mediaType == TemporaryMediaType.PHOTO) {
          val compressedBytes = preparePhotoBytes(rawBytes)
          descriptor = StoryXftpDescriptor(
            id = item.id,
            downloadUrl = null,
            directBase64 = Base64.getEncoder().encodeToString(compressedBytes),
            encryptionKey = "",
            iv = "",
            mediaType = item.mediaType.name,
            durationHours = item.durationHours,
            caption = item.caption,
            createdAtMillis = item.createdAtMillis,
            thumbBase64 = thumbBytes?.let { Base64.getEncoder().encodeToString(it) }
          )
        } else {
          // Видео шифруем AES-256 и загружаем на временное защищенное хранилище
          val uploadResult = XftpStoryUploader.uploadEncryptedStory(file, item.durationHours) ?: run {
            Log.e(TAG, "Failed to upload video story")
            return@launch
          }

          descriptor = StoryXftpDescriptor(
            id = item.id,
            downloadUrl = uploadResult.downloadUrl,
            directBase64 = null,
            encryptionKey = uploadResult.keyBase64,
            iv = uploadResult.ivBase64,
            mediaType = item.mediaType.name,
            durationHours = item.durationHours,
            caption = item.caption,
            createdAtMillis = item.createdAtMillis,
            thumbBase64 = thumbBytes?.let { Base64.getEncoder().encodeToString(it) }
          )
        }

        val encodedMsg = STORY_PUSH_PREFIX + json.encodeToString(descriptor)

        val recipients = targetChats ?: chatModel.chats.value.filter {
          val ci = it.chatInfo
          if (ci !is ChatInfo.Direct || !ci.contact.active || !ci.contact.ready) return@filter false
          when (item.audience) {
            TemporaryAudience.VERIFIED_USERS -> ci.contact.verified
            TemporaryAudience.CONTACTS, TemporaryAudience.ALL_USERS -> true
          }
        }

        recipients.forEach { chat ->
          sendHiddenServiceMessage(chat, encodedMsg)
        }
      } catch (e: Exception) {
        Log.e(TAG, "publishAndBroadcastStory error: ${e.message}")
      }
    }
  }

  fun requestContactStories(contactId: Long) {
    scope.launch {
      val targetChat = chatModel.chats.value.firstOrNull {
        (it.chatInfo as? ChatInfo.Direct)?.contact?.contactId == contactId
      } ?: return@launch

      sendHiddenServiceMessage(targetChat, STORY_REQ_PREFIX)
    }
  }

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
          val thumbFile = File("${story.filePath}_thumb.jpg")
          val thumbBytes = if (thumbFile.exists()) thumbFile.readBytes() else null

          publishAndBroadcastStory(
            file = file,
            item = story,
            thumbBytes = thumbBytes,
            targetChats = listOf(targetChat)
          )
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
    val cleanText = text.trim()
    when {
      cleanText.contains(STORY_PUSH_PREFIX) -> {
        if (senderContactId == null) return true
        scope.launch {
          try {
            val rawJson = cleanText.substringAfter(STORY_PUSH_PREFIX)
            val desc = json.decodeFromString<StoryXftpDescriptor>(rawJson)

            val decryptedFileBytes = if (desc.directBase64 != null) {
              Base64.getDecoder().decode(desc.directBase64)
            } else if (desc.downloadUrl != null) {
              XftpStoryUploader.downloadAndDecryptStory(
                downloadUrl = desc.downloadUrl,
                keyBase64 = desc.encryptionKey,
                ivBase64 = desc.iv
              )
            } else null

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
            Log.e(TAG, "Error receiving story: ${e.message}")
          }
        }
        return true
      }
      cleanText.contains(STORY_REQ_PREFIX) -> {
        respondToStoryRequest(remoteHostId, chatId)
        return true
      }
      else -> return false
    }
  }

  private fun preparePhotoBytes(rawBytes: ByteArray): ByteArray {
    return try {
      val b64 = Base64.getEncoder().encodeToString(rawBytes)
      val bitmap = base64ToBitmap(b64)
      if (bitmap != null) {
        val compressed = resizeImageToStrSize(bitmap, maxDataSize = 9500)
        Base64.getDecoder().decode(compressed)
      } else rawBytes
    } catch (_: Exception) {
      rawBytes
    }
  }

  private fun sendHiddenServiceMessage(chat: Chat, text: String) {
    withBGApi {
      try {
        val cInfo = chat.chatInfo
        chatModel.controller.apiSendMessages(
          rh = chat.remoteHostId,
          type = cInfo.chatType,
          id = cInfo.apiId,
          scope = cInfo.groupChatScope(),
          sendAsGroup = cInfo.sendAsGroup,
          live = false,
          ttl = null,
          sign = false,
          composedMessages = listOf(
            ComposedMessage(
              fileSource = null,
              quotedItemId = null,
              msgContent = MsgContent.MCText(text),
              mentions = emptyMap()
            )
          )
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to send story message: ${e.message}")
      }
    }
  }
}
