package chat.simplex.common.platform

import androidx.compose.runtime.snapshotFlow
import chat.simplex.common.model.*
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object StorySyncManager {
  const val STORY_TAG_PREFIX = "[STORY:"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var observerJob: Job? = null

  private val handledItemIds = ConcurrentHashMap.newKeySet<Long>()
  private val requestedDownloadIds = ConcurrentHashMap.newKeySet<Long>()

  fun startBackgroundObserver(chatModel: ChatModel) {
    observerJob?.cancel()
    observerJob = scope.launch {
      snapshotFlow { chatModel.chats.value }.collect { chats: List<Chat> ->
        val user = chatModel.currentUser.value ?: return@collect

        chats.forEach { chat ->
          val directContact = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: return@forEach

          val storyItems = chat.chatItems.filter { item ->
            item.content.text.startsWith(STORY_TAG_PREFIX) && !handledItemIds.contains(item.id)
          }

          storyItems.forEach { item ->
            if (item.chatDir.sent) {
              handledItemIds.add(item.id)
              removeStoryItemFromChat(chatModel, chat, item)
              return@forEach
            }

            val ciFile = item.file ?: return@forEach

            // 1. Автоматический старт загрузки с официального XFTP-сервера SimpleX
            if (!ciFile.loaded) {
              if (ciFile.fileStatus is CIFileStatus.RcvInvitation && !requestedDownloadIds.contains(ciFile.fileId)) {
                requestedDownloadIds.add(ciFile.fileId)
                withBGApi {
                  try {
                    chatModel.controller.receiveFile(chat.remoteHostId, user, ciFile.fileId)
                  } catch (e: Exception) {
                    Log.e(TAG, "XFTP receive error: ${e.message}")
                  }
                }
              }
              return@forEach
            }

            // 2. Файл загружен с XFTP -> переносим в Temporary Content
            val loadedPath = getLoadedFilePath(ciFile)
            if (loadedPath != null) {
              val fileOnDisk = File(loadedPath)
              if (fileOnDisk.exists()) {
                handledItemIds.add(item.id)

                val rawText = item.content.text
                val durationHours = rawText
                  .substringAfter(STORY_TAG_PREFIX, "24]")
                  .substringBefore("]")
                  .toIntOrNull() ?: 24
                val caption = rawText.substringAfter("]", "").trim()

                val mediaType = if (item.content.msgContent is MsgContent.MCVideo) {
                  TemporaryMediaType.VIDEO
                } else {
                  TemporaryMediaType.PHOTO
                }

                try {
                  val bytes = fileOnDisk.readBytes()
                  TemporaryContentManager.receiveContactTemporaryContent(
                    contactId = directContact.contactId,
                    displayName = directContact.displayName,
                    bytes = bytes,
                    mediaType = mediaType,
                    durationHours = durationHours,
                    caption = caption,
                    createdAtMillis = item.meta.createdAt.toEpochMilliseconds()
                  )
                } catch (e: Exception) {
                  Log.e(TAG, "Failed importing story file: ${e.message}")
                }

                // 3. Удаляем сообщение из чата, чтобы переписка оставалась чистой
                removeStoryItemFromChat(chatModel, chat, item)
              }
            }
          }
        }
      }
    }
  }

  private fun removeStoryItemFromChat(chatModel: ChatModel, chat: Chat, item: ChatItem) {
    scope.launch {
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

  private fun getLoadedFilePath(ciFile: CIFile): String? {
    return try {
      val name = ciFile.fileName
      if (name.isNotBlank()) {
        val path = getAppFilePath(name)
        if (File(path).exists()) path else null
      } else null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Отправка через официальный протокол XFTP ядра SimpleX
   */
  suspend fun publishAndBroadcastStoryWithProgress(
    file: File,
    item: TemporaryContentItem,
    onProgress: (Int, String) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      onProgress(10, "Preparing...")

      // Жесткая фильтрация: берем только полностью готовые и активные контакты
      val recipients = chatModel.chats.value.filter { chat ->
        val ci = chat.chatInfo
        if (ci !is ChatInfo.Direct) return@filter false
        val contact = ci.contact

        contact.active &&
        contact.ready &&
        contact.sndReady &&
        !contact.chatDeleted &&
        !ci.chatDeleted &&
        ci.sendMsgEnabled
      }

      if (recipients.isEmpty()) {
        onProgress(100, "Saved locally")
        return@withContext true
      }

      onProgress(25, "Processing media...")
      val previewBase64 = generateSmallPreviewBase64(file, item.mediaType)
      val taggedCaption = "${STORY_TAG_PREFIX}${item.durationHours}] ${item.caption}".trim()

      val msgContent: MsgContent = if (item.mediaType == TemporaryMediaType.VIDEO) {
        MsgContent.MCVideo(
          text = taggedCaption,
          image = previewBase64,
          duration = 10
        )
      } else {
        MsgContent.MCImage(
          text = taggedCaption,
          image = previewBase64
        )
      }

      val total = recipients.size
      var successfulSends = 0

      recipients.forEachIndexed { index, chat ->
        val progressPct = 30 + ((index + 1) * 65 / total)
        onProgress(progressPct, "Uploading to XFTP (${index + 1}/$total)...")

        withBGApi {
          try {
            val ext = if (item.mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
            val xftpFileName = "story_${System.currentTimeMillis()}_${chat.chatInfo.apiId}.$ext"
            val appFile = File(getAppFilePath(xftpFileName))
            file.copyTo(appFile, overwrite = true)

            val cryptoFile = CryptoFile.plain(appFile.name)
            val cInfo = chat.chatInfo

            val sentItems = chatModel.controller.apiSendMessages(
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
                  fileSource = cryptoFile,
                  quotedItemId = null,
                  msgContent = msgContent,
                  mentions = emptyMap()
                )
              )
            )

            sentItems?.forEach { aItem ->
              handledItemIds.add(aItem.chatItem.id)
              removeStoryItemFromChat(chatModel, chat, aItem.chatItem)
            }

            if (!sentItems.isNullOrEmpty()) {
              successfulSends++
            }
          } catch (e: Exception) {
            Log.e(TAG, "Skip contact ${chat.chatInfo.displayName}: ${e.message}")
          }
        }
      }

      onProgress(100, "Done!")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Publish error: ${e.message}")
      false
    }
  }

  private fun generateSmallPreviewBase64(file: File, mediaType: TemporaryMediaType): String {
    return try {
      val sourceFile = if (mediaType == TemporaryMediaType.VIDEO) {
        val thumb = File("${file.absolutePath}_thumb.jpg")
        if (thumb.exists()) thumb else return ""
      } else {
        file
      }
      val b64 = java.util.Base64.getEncoder().encodeToString(sourceFile.readBytes())
      val bitmap = base64ToBitmap(b64) ?: return ""
      resizeImageToStrSize(bitmap, maxDataSize = 8500)
    } catch (_: Exception) {
      ""
    }
  }
}
