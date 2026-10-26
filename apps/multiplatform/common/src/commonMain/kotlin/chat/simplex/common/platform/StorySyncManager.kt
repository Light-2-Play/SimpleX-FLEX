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

@Serializable
data class StoryXftpDescriptor(
  val id: String,
  val downloadUrl: String,
  val encryptionKey: String,
  val iv: String,
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

  /**
   * Опрос всех активных контактов при входе в раздел Temporary Content из списка чатов
   */
  fun requestStoriesFromAllContacts() {
    scope.launch {
      val directChats = chatModel.chats.value.filter {
        val ci = it.chatInfo
        ci is ChatInfo.Direct && ci.contact.active && ci.contact.ready
      }
      directChats.forEach { chat ->
        sendHiddenServiceMessage(chat, STORY_REQ_PREFIX)
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
      onProgress(0, "Encrypting...")

      val uploadResult = XftpStoryUploader.uploadEncryptedStory(file, item.durationHours) { pct ->
        onProgress(pct, "Uploading: $pct%")
      } ?: run {
        onProgress(0, "Upload error")
        return@withContext false
      }

      onProgress(95, "Sending descriptor...")

      val descriptor = StoryXftpDescriptor(
        id = item.id,
        downloadUrl = uploadResult.downloadUrl,
        encryptionKey = uploadResult.keyBase64,
        iv = uploadResult.ivBase64,
        mediaType = item.mediaType.name,
        durationHours = item.durationHours,
        caption = item.caption,
        createdAtMillis = item.createdAtMillis
      )

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
            Log.e(TAG, "Error auto-downloading story: ${e.message}")
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
