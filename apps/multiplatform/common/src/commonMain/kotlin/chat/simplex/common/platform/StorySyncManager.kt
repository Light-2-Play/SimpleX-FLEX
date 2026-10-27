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

  // Помечаем только те сообщения, файлы которых УЖЕ импортированы в сторис
  private val importedItemIds = ConcurrentHashMap.newKeySet<Long>()
  private val downloadingFileIds = ConcurrentHashMap.newKeySet<Long>()

  /**
   * Фоновый наблюдатель:
   * 1. Сканирует входящие сообщения (включая последнее сообщение закрытых чатов).
   * 2. Автоматически запускает скачивание с XFTP.
   * 3. Как только файл скачался — МГНОВЕННО обновляет сторис на лету (БЕЗ перезапуска приложения).
   */
  fun startBackgroundObserver(chatModel: ChatModel) {
    observerJob?.cancel()
    observerJob = scope.launch {
      snapshotFlow { chatModel.chats.value }.collect { chats: List<Chat> ->
        val user = chatModel.currentUser.value ?: return@collect

        chats.forEach { chat ->
          val directContact = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: return@forEach

         val storyItems = chat.chatItems.filter { item ->
            item.content.text.startsWith(STORY_TAG_PREFIX) && !importedItemIds.contains(item.id)
          }

          storyItems.forEach { item ->
            // Исходящие сообщения автора: НЕ удаляем во время выгрузки на XFTP!
            if (item.chatDir.sent) return@forEach

            val ciFile = item.file ?: return@forEach

            // 1. Автоматический старт скачивания файла с XFTP
            if (!ciFile.loaded) {
              if (!downloadingFileIds.contains(ciFile.fileId)) {
                downloadingFileIds.add(ciFile.fileId)
                withBGApi {
                  try {
                    chatModel.controller.receiveFile(chat.remoteHostId, user, ciFile.fileId)
                  } catch (e: Exception) {
                    Log.e(TAG, "receiveFile error: ${e.message}")
                  }
                }
              }
              return@forEach
            }

            // 2. Файл полностью загружен с XFTP на устройство!
            val loadedPath = getLoadedFilePath(ciFile)
            if (loadedPath != null) {
              val fileOnDisk = File(loadedPath)
              if (fileOnDisk.exists() && fileOnDisk.length() > 0) {
                // Помечаем как готовый ТОЛЬКО сейчас, чтобы не блокировать докачку
                importedItemIds.add(item.id)
                downloadingFileIds.remove(ciFile.fileId)

                val rawText = item.content.text
                val durationHours = rawText
                  .substringAfter(STORY_TAG_PREFIX, "24]")
                  .substringBefore("]")
                  .toIntOrNull() ?: 24
                val caption = rawText.substringAfter("]", "").trim()

                // Определяем тип файла надежно (по заголовку и по расширению файла)
                val isVideo = item.content.msgContent is MsgContent.MCVideo ||
                              ciFile.fileName.endsWith(".mp4", ignoreCase = true) ||
                              fileOnDisk.name.endsWith(".mp4", ignoreCase = true)

                val mediaType = if (isVideo) TemporaryMediaType.VIDEO else TemporaryMediaType.PHOTO

                try {
                  val bytes = fileOnDisk.readBytes()
                  // Мгновенно пушит новый элемент в ленту сторис
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
                  Log.e(TAG, "Error importing story file: ${e.message}")
                }

                // Удаляем сервисную запись из обычного диалога
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
      if (f1.exists() && f1.length() > 0) return f1.absolutePath
      val f2 = File(getAppFilePath(name))
      if (f2.exists() && f2.length() > 0) return f2.absolutePath
      null
    } catch (_: Exception) {
      null
    }
  }

  suspend fun publishAndBroadcastStoryWithProgress(
    file: File,
    item: TemporaryContentItem,
    onProgress: (Int, String) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      onProgress(10, "Preparing media...")

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

      onProgress(20, "Generating preview...")
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

      recipients.forEach { chat ->
        try {
          val ext = if (item.mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
          val xftpFileName = "story_${System.currentTimeMillis()}_${chat.chatInfo.apiId}.$ext"
          val appFile = File(getAppFilePath(xftpFileName))
          file.copyTo(appFile, overwrite = true)

          val cryptoFile = CryptoFile.plain(appFile.name)
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
                fileSource = cryptoFile,
                quotedItemId = null,
                msgContent = msgContent,
                mentions = emptyMap()
              )
            )
          )
        } catch (e: Exception) {
          Log.e(TAG, "Error sending story to ${chat.chatInfo.displayName}: ${e.message}")
        }
      }

      // Даём SimpleX время связаться с XFTP-сервером
      var attempts = 0
      while (attempts < 10) {
        delay(800L)
        attempts++
        val pct = (25 + attempts * 7).coerceAtMost(95)
        onProgress(pct, "Uploading to XFTP: $pct%")
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
