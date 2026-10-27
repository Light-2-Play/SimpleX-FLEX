package chat.simplex.common.platform

import chat.simplex.common.model.*
import chat.simplex.common.views.helpers.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object StorySyncManager {
  const val STORY_TAG_PREFIX = "[STORY:"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var observerJob: Job? = null

  private val importedItemIds = ConcurrentHashMap.newKeySet<Long>()
  private val downloadingItemIds = ConcurrentHashMap.newKeySet<Long>()

  /**
   * Независимый фоновый демон.
   * Раз в 2 секунды сканирует базу на наличие новых сторис.
   */
  fun startBackgroundObserver(chatModel: ChatModel) {
    observerJob?.cancel()
    observerJob = scope.launch {
      while (isActive) {
        try {
          val user = chatModel.currentUser.value ?: continue
          val chats = chatModel.chats.value

          for (chat in chats) {
            val directContact = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: continue

            // Ищем сторис во всех сообщениях (включая последнее, если чат закрыт)
            val candidates = chat.chatItems.toMutableList()
            chat.lastMessage?.let { candidates.add(it) }

            val storyItems = candidates.distinctBy { it.id }.filter { item ->
              item.content.text.startsWith(STORY_TAG_PREFIX) && !importedItemIds.contains(item.id)
            }

            for (item in storyItems) {
              // ИСХОДЯЩИЕ (отправитель): мы больше не удаляем их скриптом, 
              // чтобы ядро SimpleX могло спокойно выгрузить их на XFTP.
              // Они просто скрыты в ChatItemView.
              if (item.chatDir.sent) {
                importedItemIds.add(item.id)
                continue
              }

              // ВХОДЯЩИЕ (получатель)
              val ciFile = item.file ?: continue

              // 1. Старт скачивания
              if (!ciFile.loaded) {
                if (!downloadingItemIds.contains(item.id)) {
                  downloadingItemIds.add(item.id)
                  withBGApi {
                    try {
                      chatModel.controller.receiveFile(chat.remoteHostId, user, ciFile.fileId)
                    } catch (e: Exception) {
                      Log.e(TAG, "receiveFile error: ${e.message}")
                    }
                  }
                }
                continue
              }

              // 2. Файл скачан
              val loadedPath = getLoadedFilePath(ciFile)
              if (loadedPath != null) {
                val fileOnDisk = File(loadedPath)
                
                // Проверяем, что файл действительно скачался и он не пустой
                if (fileOnDisk.exists() && fileOnDisk.length() > 1000) { 
                  importedItemIds.add(item.id)
                  downloadingItemIds.remove(item.id)

                  val rawText = item.content.text
                  val durationHours = rawText
                    .substringAfter(STORY_TAG_PREFIX, "24]")
                    .substringBefore("]")
                    .toIntOrNull() ?: 24
                  val caption = rawText.substringAfter("]", "").trim()

                  val isVideo = item.content.msgContent is MsgContent.MCVideo ||
                                ciFile.fileName.endsWith(".mp4", ignoreCase = true) ||
                                fileOnDisk.name.endsWith(".mp4", ignoreCase = true)

                  val mediaType = if (isVideo) TemporaryMediaType.VIDEO else TemporaryMediaType.PHOTO

                  try {
                    val bytes = fileOnDisk.readBytes()
                    // Мгновенное добавление в UI
                    TemporaryContentManager.receiveContactTemporaryContent(
                      contactId = directContact.contactId,
                      displayName = directContact.displayName,
                      bytes = bytes,
                      mediaType = mediaType,
                      durationHours = durationHours,
                      caption = caption,
                      createdAtMillis = item.meta.createdAt.toEpochMilliseconds()
                    )

                    // Стираем только после успешного импорта
                    withBGApi {
                      chatModel.controller.apiDeleteChatItems(
                        chat.remoteHostId,
                        chat.chatInfo.chatType,
                        chat.chatInfo.apiId,
                        chat.chatInfo.groupChatScope(),
                        listOf(item.id),
                        CIDeleteMode.cidmInternal
                      )
                    }
                  } catch (e: Exception) {
                    Log.e(TAG, "Error importing story: ${e.message}")
                  }
                }
              }
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Observer loop error: ${e.message}")
        }
        delay(2000L) // Пауза 2 секунды перед следующим сканированием
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

        contact.active && contact.ready && contact.sndReady &&
        !contact.chatDeleted && !ci.chatDeleted && ci.sendMsgEnabled
      }

      if (recipients.isEmpty()) {
        onProgress(100, "Saved locally")
        return@withContext true
      }

      onProgress(25, "Generating metadata...")
      val previewBase64 = generateSmallPreviewBase64(file, item.mediaType)
      val taggedCaption = "${STORY_TAG_PREFIX}${item.durationHours}] ${item.caption}".trim()

      val msgContent = if (item.mediaType == TemporaryMediaType.VIDEO) {
        MsgContent.MCVideo(text = taggedCaption, image = previewBase64, duration = 10)
      } else {
        MsgContent.MCImage(text = taggedCaption, image = previewBase64)
      }

      onProgress(40, "Sending to XFTP...")
      recipients.forEach { chat ->
        try {
          val ext = if (item.mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
          val xftpFileName = "story_${System.currentTimeMillis()}_${chat.chatInfo.apiId}.$ext"
          val appFile = File(getAppFilePath(xftpFileName))
          file.copyTo(appFile, overwrite = true)

          val cryptoFile = CryptoFile.plain(appFile.name)
          val cInfo = chat.chatInfo

          // Отправляем и ЗАБЫВАЕМ. Ядро само загрузит на сервер.
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
          Log.e(TAG, "Send error: ${e.message}")
        }
      }

      // Фейковая задержка для красивого UI (реальная загрузка идёт в фоне ядра)
      for (i in 1..10) {
        delay(200L)
        onProgress(40 + (i * 5), "Uploading...")
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
