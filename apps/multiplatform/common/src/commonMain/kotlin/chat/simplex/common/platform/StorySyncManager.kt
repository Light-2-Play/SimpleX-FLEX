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
          if (ci !is ChatInfo.Direct || !ci
