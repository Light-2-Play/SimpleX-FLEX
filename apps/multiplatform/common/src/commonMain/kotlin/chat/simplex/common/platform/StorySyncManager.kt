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

  fun startBackgroundObserver(chatModel: ChatModel) {
    observerJob?.cancel()
    observerJob = scope.launch {
      while (isActive) {
        try {
          val user = chatModel.currentUser.value
          if (user != null) {
            scanAndProcessStories(chatModel, user)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Observer loop error: ${e.message}")
        }
        delay(2000L) // Проверяем чаты каждые 2 секунды
      }
    }
  }

  suspend fun syncAllStoriesNow(chatModel: ChatModel) = withContext(Dispatchers.IO) {
    val user = chatModel.currentUser.value ?: return@withContext
    scanAndProcessStories(chatModel, user)
  }

  private suspend fun scanAndProcessStories(chatModel: ChatModel, user: User) {
    val chats = chatModel.chats.value.filter {
      val ci = it.chatInfo
      ci is ChatInfo.Direct && ci.contact.active
    }

    for (chat in chats) {
      val directContact = (chat.chatInfo as? ChatInfo.Direct)?.contact ?: continue

      // Запрашиваем актуальный список сообщений из базы данных
      val items = try {
        val pagination = ChatPagination.Initial(20)
        val chatData = chatModel.controller.apiGetChat(
          chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId, null, null, pagination
        )
        chatData?.first?.chatItems ?: chat.chatItems
      } catch (_: Exception) {
        chat.chatItems
      }

      val storyItems = items.filter { item ->
        item.content.text.startsWith(STORY_TAG_PREFIX) && !importedItemIds.contains(item.id)
      }

      for (item in storyItems) {
        // --- ДЛЯ ОТПРАВИТЕЛЯ ---
        if (item.chatDir.sent) {
          val ciFile = item.file
          val statusName = ciFile?.fileStatus?.let { it::class.simpleName } ?: ""
          
          // Отправитель ждет, пока файл полностью уйдет на сервер (Done)
          if (statusName.contains("Done", ignoreCase = true)) {
            importedItemIds.add(item.id)
            removeStoryItemFromChat(chatModel, chat, item)
          }
          continue
        }

        // --- ДЛЯ ПОЛУЧАТЕЛЯ ---
        val ciFile = item.file ?: continue
        val statusName = ciFile.fileStatus::class.simpleName ?: ""

        // 1. Инициируем скачивание (сообщение висит в чате и не удаляется!)
        if (!statusName.contains("Done", ignoreCase = true) && !ciFile.loaded) {
          if (!downloadingItemIds.contains(item.id)) {
            downloadingItemIds.add(item.id)
            withBGApi {
              try { chatModel.controller.receiveFile(chat.remoteHostId, user, ciFile.fileId) } catch (_: Exception) {}
            }
          }
          continue
        }

        // 2. Ядро SimpleX сказало "Done" - файл скачан на 100% и расшифрован
        if (statusName.contains("Done", ignoreCase = true) || ciFile.loaded) {
          
          // Получаем чистые расшифрованные байты/файл от ядра
          val fileSource = getLoadedFileSource(ciFile)
          var decryptedFile: File? = null
          var decryptedBytes: ByteArray? = null

          if (fileSource is String) {
            decryptedFile = File(fileSource)
          } else if (fileSource is ByteArray) {
            decryptedBytes = fileSource
          } else if (fileSource is File) {
            decryptedFile = fileSource
          } else {
            val fallbackPath = getLoadedFilePath(ciFile)
            if (fallbackPath != null) decryptedFile = File(fallbackPath)
          }

          // Убеждаемся, что файл не пустышка (больше 1 КБ)
          val isValid = (decryptedFile != null && decryptedFile.exists() && decryptedFile.length() > 1000) || 
                        (decryptedBytes != null && decryptedBytes.size > 1000)

          if (isValid) {
            importedItemIds.add(item.id)
            downloadingItemIds.remove(item.id)

            val rawText = item.content.text
            val durationHours = rawText.substringAfter(STORY_TAG_PREFIX, "24]").substringBefore("]").toIntOrNull() ?: 24
            val caption = rawText.substringAfter("]", "").trim()

            val isVideo = item.content.msgContent is MsgContent.MCVideo ||
                          ciFile.fileName.endsWith(".mp4", ignoreCase = true) ||
                          (decryptedFile != null && decryptedFile.name.endsWith(".mp4", ignoreCase = true))

            val mediaType = if (isVideo) TemporaryMediaType.VIDEO else TemporaryMediaType.PHOTO
            val previewBase64 = when (val content = item.content.msgContent) {
              is MsgContent.MCVideo -> content.image
              is MsgContent.MCImage -> content.image
              else -> null
            }

            try {
              // 3. БЕЗОПАСНО КОПИРУЕМ ФАЙЛ В TEMPORARY CONTENT
              TemporaryContentManager.importContactStory(
                storyId = "story_${directContact.contactId}_${item.id}",
                contactId = directContact.contactId,
                displayName = directContact.displayName,
                sourceFile = decryptedFile,
                sourceBytes = decryptedBytes,
                previewBase64 = previewBase64,
                mediaType = mediaType,
                durationHours = durationHours,
                caption = caption,
                createdAtMillis = item.meta.createdAt.toEpochMilliseconds()
              )

              // 4. И ТОЛЬКО ТЕПЕРЬ, КОГДА ФАЙЛ В БЕЗОПАСНОСТИ, УДАЛЯЕМ СООБЩЕНИЕ ИЗ ЧАТА
              removeStoryItemFromChat(chatModel, chat, item)
            } catch (e: Exception) {
              Log.e(TAG, "Error importing story: ${e.message}")
            }
          }
        }
      }
    }
  }

  private fun removeStoryItemFromChat(chatModel: ChatModel, chat: Chat, item: ChatItem) {
    scope.launch {
      withContext(Dispatchers.Main) {
        try { chatModel.chatsContext.removeChatItem(chat.remoteHostId, chat.chatInfo, item) } catch (_: Exception) {}
      }
      withBGApi {
        try {
          chatModel.controller.apiDeleteChatItems(
            chat.remoteHostId, chat.chatInfo.chatType, chat.chatInfo.apiId,
            chat.chatInfo.groupChatScope(), listOf(item.id), CIDeleteMode.cidmInternal
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
    } catch (_: Exception) { null }
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
        contact.active && contact.ready && contact.sndReady && !contact.chatDeleted && !ci.chatDeleted && ci.sendMsgEnabled
      }

      if (recipients.isEmpty()) {
        onProgress(100, "Saved locally")
        return@withContext true
      }

      onProgress(30, "Generating metadata...")
      val previewBase64 = generateSmallPreviewBase64(file, item.mediaType)
      val taggedCaption = "${STORY_TAG_PREFIX}${item.durationHours}] ${item.caption}".trim()

      val msgContent = if (item.mediaType == TemporaryMediaType.VIDEO) {
        MsgContent.MCVideo(text = taggedCaption, image = previewBase64, duration = 10)
      } else {
        MsgContent.MCImage(text = taggedCaption, image = previewBase64)
      }

      recipients.forEach { chat ->
        try {
          val ext = if (item.mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
          val xftpFileName = "story_${System.currentTimeMillis()}_${chat.chatInfo.apiId}.$ext"
          val appFile = File(getAppFilePath(xftpFileName))
          file.copyTo(appFile, overwrite = true)

          val cryptoFile = if (chatModel.controller.appPrefs.privacyEncryptLocalFiles.get()) {
            val encFile = File(getAppFilePath("enc_${appFile.name}"))
            val args = encryptCryptoFile(appFile.absolutePath, encFile.absolutePath)
            appFile.delete()
            CryptoFile(encFile.name, args)
          } else {
            CryptoFile.plain(appFile.name)
          }

          val cInfo = chat.chatInfo

          chatModel.controller.apiSendMessages(
            rh = chat.remoteHostId, type = cInfo.chatType, id = cInfo.apiId, scope = cInfo.groupChatScope(),
            sendAsGroup = cInfo.sendAsGroup, live = false, ttl = null, sign = false,
            composedMessages = listOf(ComposedMessage(fileSource = cryptoFile, quotedItemId = null, msgContent = msgContent, mentions = emptyMap()))
          )
        } catch (e: Exception) {
          Log.e(TAG, "Send error: ${e.message}")
        }
      }

      for (i in 1..10) {
        delay(150L)
        onProgress(40 + (i * 6), "Uploading...")
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
      val bytes = sourceFile.readBytes()
      val base64Str = java.util.Base64.getEncoder().encodeToString(bytes)
      val bitmap = base64ToBitmap(base64Str) ?: return ""
      resizeImageToStrSize(bitmap, maxDataSize = 8500)
    } catch (_: Exception) {
      ""
    }
  }
}
