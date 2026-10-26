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

  /**
   * Фоновый наблюдатель:
   * 1. На стороне получателя: дожидается выгрузки файла отправителем, скачивает с XFTP и переносит в сторис.
   * 2. На стороне отправителя: как только XFTP-выгрузка завершена, тихо удаляет элемент из локального чата.
   */
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
            // --- 1. ЕСЛИ ЭТО ИСХОДЯЩАЯ ИСТОРИЯ (ОТПРАВИТЕЛЬ) ---
            if (item.chatDir.sent) {
              val ciFile = item.file
              val statusName = ciFile?.fileStatus?.let { it::class.simpleName } ?: ""
              val isStillUploading = statusName.contains("Upload", ignoreCase = true) ||
                                     statusName.contains("Wait", ignoreCase = true)

              // Удаляем сообщение из чата ТОЛЬКО когда выгрузка на XFTP реально завершилась
              if (!isStillUploading && statusName.isNotBlank()) {
                handledItemIds.add(item.id)
                removeStoryItemFromChat(chatModel, chat, item)
              }
              return@forEach
            }

            // --- 2. ЕСЛИ ЭТО ВХОДЯЩАЯ ИСТОРИЯ (ПОЛУЧАТЕЛЬ) ---
            val ciFile = item.file ?: return@forEach

            // Если файл ещё не скачан на устройство
            if (!ciFile.loaded) {
              val statusName = ciFile.fileStatus::class.simpleName ?: ""
              val isWaitingForSender = statusName.contains("Wait", ignoreCase = true)

              // Если отправитель всё ещё загружает файл на XFTP — ждём
              if (isWaitingForSender) {
                return@forEach
              }

              // Отправитель закончил загрузку на XFTP — запускаем скачивание к себе
              if (!requestedDownloadIds.contains(ciFile.fileId)) {
                requestedDownloadIds.add(ciFile.fileId)
                withBGApi {
                  try {
                    chatModel.controller.receiveFile(chat.remoteHostId, user, ciFile.fileId)
                  } catch (e: Exception) {
                    Log.e(TAG, "XFTP receiveFile error: ${e.message}")
                  }
                }
              }
              return@forEach
            }

            // Файл полностью скачан с XFTP на диск!
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

                // Стираем сервисное сообщение из переписки
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
      if (name.isBlank()) return null
      val f1 = File(name)
      if (f1.exists()) return f1.absolutePath
      val f2 = File(getAppFilePath(name))
      if (f2.exists()) return f2.absolutePath
      null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Отправка медиафайла через официальный XFTP-протокол SimpleX
   * с ожиданием окончания реальной выгрузки перед закрытием диалога
   */
  suspend fun publishAndBroadcastStoryWithProgress(
    file: File,
    item: TemporaryContentItem,
    onProgress: (Int, String) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      onProgress(10, "Preparing...")

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

      onProgress(20, "Processing media...")
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

      val sentItemIds = mutableListOf<Pair<Chat, Long>>()

      // 1. Ставим файлы в очередь отправки XFTP
      recipients.forEach { chat ->
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
              sentItemIds.add(chat to aItem.chatItem.id)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error queuing story to ${chat.chatInfo.displayName}: ${e.message}")
          }
        }
      }

      if (sentItemIds.isEmpty()) {
        onProgress(100, "Saved locally")
        return@withContext true
      }

      // 2. Ожидаем завершения реальной выгрузки на XFTP-сервер
      var attempts = 0
      while (attempts < 45) {
        delay(1000L)
        attempts++

        var stillUploading = false
        sentItemIds.forEach { (chat, itemId) ->
          val currentChat = chatModel.chats.value.firstOrNull { it.id == chat.id }
          val currentItem = currentChat?.chatItems?.firstOrNull { it.id == itemId }
          val statusName = currentItem?.file?.fileStatus?.let { it::class.simpleName } ?: ""
          if (statusName.contains("Upload", ignoreCase = true) || statusName.contains("Wait", ignoreCase = true)) {
            stillUploading = true
          }
        }

        val pct = (25 + attempts * 6).coerceAtMost(92)
        onProgress(pct, "Uploading to XFTP: $pct%")

        if (!stillUploading && attempts >= 2) {
          break
        }
      }

      onProgress(96, "Finishing...")
      delay(1200L)

      // 3. Выгрузка завершена — удаляем служебные сообщения из чата отправителя
      sentItemIds.forEach { (chat, itemId) ->
        val currentChat = chatModel.chats.value.firstOrNull { it.id == chat.id }
        val currentItem = currentChat?.chatItems?.firstOrNull { it.id == itemId }
        if (currentItem != null) {
          handledItemIds.add(currentItem.id)
          removeStoryItemFromChat(chatModel, chat, currentItem)
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
