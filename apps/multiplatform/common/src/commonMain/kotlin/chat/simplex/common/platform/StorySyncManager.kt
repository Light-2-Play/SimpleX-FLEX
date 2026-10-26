package chat.simplex.common.platform

import androidx.compose.runtime.snapshotFlow
import chat.simplex.common.model.*
import chat.simplex.common.views.helpers.withBGApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class StoryXftpDescriptor(
  val id: String,
  val downloadUrl: String? = null,
  val directBase64: String? = null,
  val encryptionKey: String = "",
  val iv: String = "",
  val mediaType: String,
  val durationHours: Int,
  val caption: String,
  val createdAtMillis: Long
)

object StorySyncManager {
  const val STORY_PUSH_PREFIX = "__SIMPLEX_STORY_PUSH__:"
  const val STORY_REQ_PREFIX = "__SIMPLEX_STORY_REQ__"

  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var observerJob: Job? = null

  // Защита от спама и повторной обработки одних и тех же сообщений
  private val handledMessageIds = ConcurrentHashMap.newKeySet<Long>()

  fun startBackgroundObserver(chatModel: ChatModel) {
    observerJob?.cancel()
    observerJob = scope.launch {
      snapshotFlow { chatModel.chats.value }.collect { chats: List<Chat> ->
        chats.forEach { chat ->
          val directContact = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: return@forEach

          val unhandledItems = chat.chatItems.filter { item ->
            !item.chatDir.sent &&
            !handledMessageIds.contains(item.id) && (
              item.content.text.contains(STORY_PUSH_PREFIX) ||
              item.content.text.contains(STORY_REQ_PREFIX)
            )
          }

          unhandledItems.forEach { item ->
            handledMessageIds.add(item.id)

            val text = item.content.text.trim()
            handleIncomingMessage(
              remoteHostId = chat.remoteHostId,
              chatId = chat.id,
              senderContactId = directContact.contactId,
              senderDisplayName = directContact.displayName,
              text = text
            )

            // Моментально удаляем служебную команду из памяти и БД чата
            withContext(Dispatchers.Main) {
              try {
                chatModel.chatsContext.removeChatItem(chat.remoteHostId, chat.chatInfo, item)
              } catch (_: Exception) {}
            }
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

  suspend fun publishAndBroadcastStoryWithProgress(
    file: File,
    item: TemporaryContentItem,
    targetChats: List<Chat>? = null,
    onProgress: (Int, String) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      val descriptor: StoryXftpDescriptor

      // ФОТО: Передаются напрямую через SMP без сторонних серверов
      if (item.mediaType == TemporaryMediaType.PHOTO) {
        onProgress(30, "Preparing photo...")
        val compressedBytes = preparePhotoBytes(file.readBytes())
        descriptor = StoryXftpDescriptor(
          id = item.id,
          downloadUrl = null,
          directBase64 = Base64.getEncoder().encodeToString(compressedBytes),
          mediaType = item.mediaType.name,
          durationHours = item.durationHours,
          caption = item.caption,
          createdAtMillis = item.createdAtMillis
        )
        onProgress(85, "Sending...")
      } else {
        // ВИДЕО: Шифрование AES-256 и загрузка в хранилище
        onProgress(5, "Encrypting video...")
        val uploadResult = XftpStoryUploader.uploadEncryptedStory(file, item.durationHours) { pct ->
          onProgress(pct, "Uploading: $pct%")
        } ?: run {
          onProgress(0, "Upload error")
          return@withContext false
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
          createdAtMillis = item.createdAtMillis
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

      onProgress(100, "Done!")
      true
    } catch (e: Exception) {
      Log.e(TAG, "publish error: ${e.message}")
      false
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

  private fun respondToStoryRequest(remoteHostId: Long?, chatId: String) {
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

          publishAndBroadcastStoryWithProgress(
            file = file,
            item = story,
            targetChats = listOf(targetChat),
            onProgress = { _, _ -> }
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
              // Мгновенная расшифровка прямо из пакета SMP
              Base64.getDecoder().decode(desc.directBase64)
            } else if (desc.downloadUrl != null) {
              // Скачивание и расшифровка видеофайла
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
            Log.e(TAG, "Error saving received story: ${e.message}")
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
        val compressed = resizeImageToStrSize(bitmap, maxDataSize = 8500)
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
