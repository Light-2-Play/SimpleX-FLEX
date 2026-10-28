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

enum class TemporaryMediaType {
  PHOTO, VIDEO
}

// Изменено на Float для поддержки 30 минут (0.5)
enum class TemporaryDuration(val hours: Float, val label: String) {
  MINS_30(0.5f, "30m"),
  HOURS_1(1f, "1h"),
  HOURS_3(3f, "3h"),
  HOURS_6(6f, "6h"),
  HOURS_12(12f, "12h"),
  HOURS_16(16f, "16h"),
  HOURS_24(24f, "24h");
}

enum class TemporaryAudience(val label: String) {
  VERIFIED_USERS("Verified"),
  CONTACTS("Contacts")
  // Пункт ALL_USERS удален по вашему запросу
}

@Serializable
data class TemporaryContentItem(
  val id: String,
  val authorContactId: Long? = null,
  val authorDisplayName: String? = null,
  val filePath: String,
  val mediaType: TemporaryMediaType,
  val createdAtMillis: Long,
  val durationHours: Float, // Теперь Float
  val expiresAtMillis: Long = createdAtMillis + (durationHours * 3600_000f).toLong(),
  val caption: String = "",
  val audience: TemporaryAudience = TemporaryAudience.CONTACTS
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
    return (remainingMs / totalMs).coerceIn(0f, 1f)
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

  init {
    loadMetadataFromDisk()
    purgeExpired()
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
    audience: TemporaryAudience
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
        audience = audience
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
    durationHours: Float, // Теперь Float
    caption: String,
    createdAtMillis: Long
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
        audience = TemporaryAudience.CONTACTS
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
    val current = _items.value
    val (active, expired) = current.partition {
      val f = File(it.filePath)
      it.expiresAtMillis > now && f.exists() && f.length() > 1000L
    }
    if (expired.isNotEmpty()) {
      expired.forEach { item ->
        try {
          val f = File(item.filePath)
          if (f.exists()) f.delete()
          val thumb = File("${item.filePath}_thumb.jpg")
          if (thumb.exists()) thumb.delete()
        } catch (_: Exception) {}
      }
      _items.value = active.toList()
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
}
