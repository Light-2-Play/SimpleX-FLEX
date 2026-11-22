package chat.simplex.common.platform

import chat.simplex.common.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.*

enum class TemporaryMediaType {
  PHOTO, VIDEO
}

// Изменено на Float для поддержки 30 минут (0.5)
enum class TemporaryDuration(val hours: Float, val label: String) {
  MINS_30(0.5f, "30m"),
  HOURS_1(1f, "1h"),
  HOURS_6(6f, "6h"),
  HOURS_12(12f, "12h"),
  HOURS_16(16f, "16h"),
  HOURS_24(24f, "24h");
}

enum class TemporaryAudience(val label: String) {
  VERIFIED_USERS("Verified"),
  CONTACTS("Contacts")
}

@Serializable
data class StoryReaction(
  val emoji: String,
  val senderContactId: Long? = null, // null = текущая реакция пользователя, иначе contactId собеседника
  val senderName: String? = null
)

@Serializable
data class TemporaryContentItem(
  val id: String,
  val authorContactId: Long? = null,
  val authorDisplayName: String? = null,
  val filePath: String,
  val mediaType: TemporaryMediaType,
  val createdAtMillis: Long,
  val durationHours: Float,
  val expiresAtMillis: Long = createdAtMillis + (durationHours * 3600_000f).toLong(),
  val caption: String = "",
  val audience: TemporaryAudience = TemporaryAudience.CONTACTS,
  val allowReactions: Boolean = true,
  val reactions: List<StoryReaction> = emptyList()
) {
  @Transient
  var linkedChatItem: ChatItem? = null

  fun remainingTimeText(nowMillis: Long): String {
    val remainingMs = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
    val hours = remainingMs / 3600_000L
    val minutes = (remainingMs % 3600_000L) / 60_000L
    return when {
      hours > 0 -> "${hours}h ${minutes}m"
      minutes > 0 -> "${minutes}m"
      else -> "<1m"
    }
  }

  fun remainingProgress(nowMillis: Long): Float {
    val totalMs = (durationHours * 3600_000f)
    if (totalMs <= 0f) return 0f
    val remainingMs = (expiresAtMillis - nowMillis).coerceIn(0L, totalMs.toLong()).toFloat()
    return (remainingMs / totalMs).toFloat().coerceIn(0f, 1f)
  }
}

object TemporaryContentManager {
  private val _items = MutableStateFlow<List<TemporaryContentItem>>(emptyList())
  val items: StateFlow<List<TemporaryContentItem>> = _items.asStateFlow()

  private val json = Json { ignoreUnknownKeys = true }

  private fun getStorageDir(): File {
    val dir = File(getAppFilePath("temporary_content"))
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  private val metadataFile: File
    get() = File(getStorageDir(), "metadata.json")

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  init {
    loadMetadataFromDisk()
    purgeExpired()

    // Фоновый таймер: проверяет и удаляет истекший контент каждую минуту
    scope.launch {
      while (true) {
        delay(60_000L)
        purgeExpired()
      }
    }
  }

  fun addContentFromFilePath(
    sourcePath: String,
    mediaType: TemporaryMediaType,
    duration: TemporaryDuration,
    caption: String,
    audience: TemporaryAudience,
    allowReactions: Boolean = true
  ): TemporaryContentItem? {
    return try {
      val sourceFile = File(sourcePath)
      if (!sourceFile.exists()) return null

      val dir = getStorageDir()
      val ext = if (mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
      val id = "my_${System.currentTimeMillis()}"
      val destFile = File(dir, "$id.$ext")

      // Копируем файл из временного кэша в папку историй и удаляем исходник
      sourceFile.copyTo(destFile, overwrite = true)
      sourceFile.delete()

      if (mediaType == TemporaryMediaType.VIDEO) {
        val lastThumb = File(dir, "last_video_thumb.jpg")
        if (lastThumb.exists()) {
          val storyThumb = File("${destFile.absolutePath}_thumb.jpg")
          lastThumb.copyTo(storyThumb, overwrite = true)
          lastThumb.delete()
        }
      }

      val now = System.currentTimeMillis()
      val expiresAt = now + (duration.hours * 3600_000f).toLong()
      val item = TemporaryContentItem(
        id = id,
        authorContactId = null,
        authorDisplayName = null,
        filePath = destFile.absolutePath,
        mediaType = mediaType,
        createdAtMillis = now,
        durationHours = duration.hours,
        expiresAtMillis = expiresAt,
        caption = caption,
        audience = audience,
        allowReactions = allowReactions
      )

      val currentList = _items.value.toMutableList()
      currentList.add(0, item)
      _items.value = currentList.toList()
      saveMetadataToDisk()
      item
    } catch (e: Exception) {
      Log.e(TAG, "addContentFromFilePath error: ${e.message}")
      null
    }
  }

  fun getActiveItemsFor(contactId: Long?): List<TemporaryContentItem> {
    val now = System.currentTimeMillis()
    return _items.value.filter { it.authorContactId == contactId && it.expiresAtMillis > now }
  }

  fun addContentFromBytes(
    bytes: ByteArray,
    mediaType: TemporaryMediaType,
    duration: TemporaryDuration,
    caption: String,
    audience: TemporaryAudience,
    allowReactions: Boolean = true
  ): TemporaryContentItem? {
    return try {
      val dir = getStorageDir()
      val ext = if (mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
      val id = "my_${System.currentTimeMillis()}"
      val destFile = File(dir, "$id.$ext")
      destFile.writeBytes(bytes)

      if (mediaType == TemporaryMediaType.VIDEO) {
        val lastThumb = File(dir, "last_video_thumb.jpg")
        if (lastThumb.exists()) {
          val storyThumb = File("${destFile.absolutePath}_thumb.jpg")
          lastThumb.copyTo(storyThumb, overwrite = true)
          lastThumb.delete()
        }
      }

      val now = System.currentTimeMillis()
      val expiresAt = now + (duration.hours * 3600_000f).toLong()
      val item = TemporaryContentItem(
        id = id,
        authorContactId = null,
        authorDisplayName = null,
        filePath = destFile.absolutePath,
        mediaType = mediaType,
        createdAtMillis = now,
        durationHours = duration.hours,
        expiresAtMillis = expiresAt,
        caption = caption,
        audience = audience,
        allowReactions = allowReactions
      )

      val currentList = _items.value.toMutableList()
      currentList.add(0, item)
      _items.value = currentList.toList()
      saveMetadataToDisk()
      item
    } catch (e: Exception) {
      Log.e(TAG, "addContentFromBytes error: ${e.message}")
      null
    }
  }

  fun registerImportedStoryFile(
    storyId: String,
    contactId: Long,
    displayName: String,
    decryptedFile: File,
    previewBase64: String?,
    mediaType: TemporaryMediaType,
    durationHours: Float,
    caption: String,
    createdAtMillis: Long,
    allowReactions: Boolean = true
  ) {
    try {
      if (!decryptedFile.exists() || decryptedFile.length() <= 1000L) return

      if (!previewBase64.isNullOrBlank()) {
        try {
          val thumbFile = File("${decryptedFile.absolutePath}_thumb.jpg")
          val decoded = java.util.Base64.getDecoder().decode(previewBase64)
          thumbFile.writeBytes(decoded)
        } catch (_: Exception) {}
      }

      val existingReactions = _items.value.firstOrNull { it.id == storyId }?.reactions ?: emptyList()
      val expiresAt = createdAtMillis + (durationHours * 3600_000f).toLong()
      val newItem = TemporaryContentItem(
        id = storyId,
        authorContactId = contactId,
        authorDisplayName = displayName,
        filePath = decryptedFile.absolutePath,
        mediaType = mediaType,
        createdAtMillis = createdAtMillis,
        durationHours = durationHours,
        expiresAtMillis = expiresAt,
        caption = caption,
        audience = TemporaryAudience.CONTACTS,
        allowReactions = allowReactions,
        reactions = existingReactions
      )

      val currentList = _items.value.toMutableList()
      currentList.removeAll { it.id == newItem.id }
      currentList.add(0, newItem)
      _items.value = currentList.toList()
      saveMetadataToDisk()
    } catch (e: Exception) {
      Log.e(TAG, "registerImportedStoryFile error: ${e.message}")
    }
  }

  /**
   * Переключает реакцию текущего пользователя на сторис.
   * Возвращает true, если реакция добавлена, false — если снята, null — если реакции запрещены.
   */
  fun toggleLocalReaction(storyId: String, emoji: String): Boolean? {
    val currentList = _items.value.toMutableList()
    val index = currentList.indexOfFirst { it.id == storyId }
    if (index == -1) return null
    val item = currentList[index]
    if (!item.allowReactions) return null

    val updatedReactions = item.reactions.toMutableList()
    val existingIdx = updatedReactions.indexOfFirst { it.senderContactId == null && it.emoji == emoji }
    val added = if (existingIdx != -1) {
      updatedReactions.removeAt(existingIdx)
      false
    } else {
      updatedReactions.add(StoryReaction(emoji = emoji, senderContactId = null, senderName = null))
      true
    }
    currentList[index] = item.copy(reactions = updatedReactions)
    _items.value = currentList.toList()
    saveMetadataToDisk()
    return added
  }

  /**
   * Применяет входящую публичную реакцию от контакта.
   */
  fun applyRemoteReaction(
    storyId: String,
    senderContactId: Long,
    senderName: String?,
    emoji: String,
    added: Boolean
  ) {
    val currentList = _items.value.toMutableList()
    val index = currentList.indexOfFirst { it.id == storyId }
    if (index == -1) return
    val item = currentList[index]
    if (!item.allowReactions) return

    val updatedReactions = item.reactions.toMutableList()
    updatedReactions.removeAll { it.senderContactId == senderContactId && it.emoji == emoji }
    if (added) {
      updatedReactions.add(
        StoryReaction(
          emoji = emoji,
          senderContactId = senderContactId,
          senderName = senderName
        )
      )
    }
    currentList[index] = item.copy(reactions = updatedReactions)
    _items.value = currentList.toList()
    saveMetadataToDisk()
  }

  fun removeContent(id: String) {
    val current = _items.value
    val item = current.firstOrNull { it.id == id } ?: return
    try {
      val f = File(item.filePath)
      if (f.exists()) f.delete()
      val thumb = File("${item.filePath}_thumb.jpg")
      if (thumb.exists()) thumb.delete()
    } catch (_: Exception) {}
    _items.value = current.filter { it.id != id }.toList()
    saveMetadataToDisk()
  }

  fun purgeExpired() {
    val now = System.currentTimeMillis()
    val currentItems = _items.value.toMutableList()
    val expiredItems = currentItems.filter { now >= it.expiresAtMillis }

    if (expiredItems.isNotEmpty()) {
      expiredItems.forEach { item ->
        try {
          File(item.filePath).delete()
          File("${item.filePath}_thumb.jpg").delete()
        } catch (_: Exception) {}
      }

      currentItems.removeAll(expiredItems)
      _items.value = currentItems
      saveMetadataToDisk()
    }
  }

  private fun saveMetadataToDisk() {
    try {
      val text = json.encodeToString(_items.value)
      metadataFile.writeText(text)
    } catch (e: Exception) {
      Log.e(TAG, "saveMetadataToDisk error: ${e.message}")
    }
  }

  private fun loadMetadataFromDisk() {
    try {
      if (metadataFile.exists()) {
        val text = metadataFile.readText()
        val list = json.decodeFromString<List<TemporaryContentItem>>(text)
        _items.value = list.filter {
          val f = File(it.filePath)
          f.exists() && f.length() > 1000L
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "loadMetadataFromDisk error: ${e.message}")
    }
  }

  fun reload() {
    _items.value = emptyList()
    loadMetadataFromDisk()
    purgeExpired()
  }
}
