package chat.simplex.common.platform

import androidx.compose.runtime.snapshotFlow
import chat.simplex.common.model.*
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object StorySyncManager {
  // Компактная метка в поле текста медиафайла: "[STORY:24] Подпись"
  const val STORY_TAG_PREFIX = "[STORY:"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var observerJob: Job? = null

  private val handledItemIds = ConcurrentHashMap.newKeySet<Long>()
  private val requestedDownloadIds = ConcurrentHashMap.newKeySet<Long>()

  /**
   * Фоновый перехватчик:
   * 1. Видит входящий XFTP-файл с меткой [STORY:...]
   * 2. Дает команду ядру SimpleX автоматически скачать его с XFTP-сервера
   * 3. Как только файл скачан — переносит его в TemporaryContentManager и удаляет из чата
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
            // Если это наше исходящее сообщение со сторис — просто прячем его из локального чата
            if (item.chatDir.sent) {
              handledItemIds.add(item.id)
              removeStoryItemFromChat(chatModel, chat, item)
              return@forEach
            }

            val ciFile = item.file ?: return@forEach

            // 1. Если файл еще не скачан с XFTP-сервера — запускаем автоматическое скачивание через ядро SimpleX
            if (!ciFile.loaded) {
              if (ciFile.fileStatus is CIFileStatus.RcvInvitation && !requestedDownloadIds.contains(ciFile.fileId)) {
                requestedDownloadIds.add(ciFile.fileId)
                withBGApi {
                  try {
                    chatModel.controller.receiveFile(chat.remoteHostId, user, ciFile.fileId)
                  } catch (e: Exception) {
                    Log.e(TAG, "XFTP auto-receive error: ${e.message}")
                  }
                }
              }
              return@forEach
            }

            // 2. Файл полностью скачан с XFTP-сервера! Читаем его и переносим в Temporary Content
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
                  Log.e(TAG, "Failed to import XFTP story file: ${e.message}")
                }

                // 3. Удаляем технический элемент из переписки, чтобы чат оставался чистым
                removeStoryItemFromChat(chatModel, chat, item)
              }
            }
          }
        }
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

  /**
   * Отправка Фото и Видео через штатный XFTP-протокол SimpleX Chat
   */
  /**
   * Отправка Фото и Видео через штатный XFTP-протокол SimpleX Chat
   * с защитой от contactDisabled и изолированной отправкой
   */
  suspend fun publishAndBroadcastStoryWithProgress(
    file: File,
    item: TemporaryContentItem,
    onProgress: (Int, String) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      onProgress(10, "Preparing XFTP transfer...")

      // Жесткий фильтр: исключаем контакты в статусе disabled, удаленные, не готовые к отправке
      val recipients = chatModel.chats.value.filter { chat ->
        val ci = chat.chatInfo
        if (ci !is ChatInfo.Direct) return@filter false
        val contact = ci.contact

        // Проверяем полную готовность контакта к отправке
        val isReadyToSend = contact.active && 
                            contact.sndReady && 
                            !contact.chatDeleted && 
                            !ci.chatDeleted && 
                            !contact.nextConnect && 
                            !contact.nextConnectPrepared &&
                            ci.sendMsgEnabled

        if (!isReadyToSend) return@filter false

        when (item.audience) {
          TemporaryAudience.VERIFIED_USERS -> contact.verified
          TemporaryAudience.CONTACTS, TemporaryAudience.ALL_USERS -> true
        }
      }

      if (recipients.isEmpty()) {
        onProgress(100, "Saved locally")
        return@withContext true
      }

      // 1. Создаем миниатюру первого кадра/превью (до 10 КБ) для заголовка
      onProgress(25, "Generating preview...")
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

      // 2. Отправляем каждому контакту индивидуально в изолированном блоке
      val total = recipients.size
      var sentCount = 0

      recipients.forEachIndexed { index, chat ->
        val progressPct = 30 + ((index + 1) * 65 / total)
        onProgress(progressPct, "Sending (${index + 1}/$total)...")

        try {
          val ext = if (item.mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
          val xftpFileName = "xftp_story_${System.currentTimeMillis()}_${chat.chatInfo.apiId}.$ext"
          val targetPath = getAppFilePath(xftpFileName)
          val appFile = File(targetPath)
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

          // Скрываем отправленный элемент из обычного чата
          sentItems?.forEach { aItem ->
            handledItemIds.add(aItem.chatItem.id)
            removeStoryItemFromChat(chatModel, chat, aItem.chatItem)
          }

          if (!sentItems.isNullOrEmpty()) {
            sentCount++
          }
        } catch (e: Exception) {
          // Если конкретный контакт вернул contactDisabled или ошибку сети — 
          // логируем и продолжаем отправку остальным без краша всего процесса
          Log.e(TAG, "Failed sending story to ${chat.chatInfo.displayName}: ${e.message}")
        }
      }

      onProgress(100, if (sentCount > 0) "Done!" else "Saved locally")
      true
    } catch (e: Exception) {
      Log.e(TAG, "General XFTP Story publish error: ${e.message}")
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
      // Штатный метод SimpleX для превью картинок в MCImage / MCVideo
      resizeImageToStrSize(bitmap, maxDataSize = 10000)
    } catch (_: Exception) {
      ""
    }
  }
}
