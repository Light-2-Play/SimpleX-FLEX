package chat.simplex.common.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.UUID

enum class TemporaryDuration(val hours: Int, val label: String) {
  HOURS_1(1, "1h"),
  HOURS_3(3, "3h"),
  HOURS_6(6, "6h"),
  HOURS_12(12, "12h"),
  HOURS_16(16, "16h"),
  HOURS_24(24, "24h");

  val durationMillis: Long
    get() = hours * 3600_000L
}

enum class TemporaryMediaType {
  PHOTO, VIDEO
}

@Serializable
data class TemporaryContentItem(
  val id: String = UUID.randomUUID().toString(),
  val filePath: String,
  val mediaType: TemporaryMediaType,
  val createdAtMillis: Long,
  val expiresAtMillis: Long,
  val durationHours: Int,
  val caption: String = "",
  val authorContactId: Long? = null, // null — собственный контент, иначе contactId собеседника
  val authorDisplayName: String? = null
) {
  fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAtMillis

  fun remainingTimeText(now: Long = System.currentTimeMillis()): String {
    val diff = (expiresAtMillis - now).coerceAtLeast(0L)
    val totalMinutes = diff / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
      hours > 0 -> "${hours}h ${minutes}m"
      minutes > 0 -> "${minutes}m"
      else -> "< 1m"
    }
  }

  fun remainingProgress(now: Long = System.currentTimeMillis()): Float {
    val total = (expiresAtMillis - createdAtMillis).coerceAtLeast(1L)
    val left = (expiresAtMillis - now).coerceAtLeast(0L)
    return (left.toFloat() / total.toFloat()).coerceIn(0f, 1f)
  }
}

object TemporaryContentManager {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _items = MutableStateFlow<List<TemporaryContentItem>>(emptyList())
  val items: StateFlow<List<TemporaryContentItem>> = _items.asStateFlow()

  private var storageDir: File? = null
  private var metadataFile: File? = null
  private var cleanupJob: Job? = null

  init {
    initStorage()
    startAutoCleanupLoop()
  }

  private fun initStorage() {
    try {
      val baseDir = File(getAppFilePath(""))
      val dir = File(baseDir, "temporary_content")
      if (!dir.exists()) dir.mkdirs()
      storageDir = dir
      metadataFile = File(dir, "metadata.json")
      loadFromDisk()
      purgeExpired()
    } catch (e: Exception) {
      Log.e(TAG, "TemporaryContentManager init error: ${e.message}")
    }
  }

  private fun startAutoCleanupLoop() {
    cleanupJob?.cancel()
    cleanupJob = scope.launch {
      while (isActive) {
        purgeExpired()
        delay(30_000L)
      }
    }
  }

  /**
   * Проверка: есть ли у пользователя/контакта активный контент прямо сейчас
   */
  fun hasActiveContent(contactId: Long?): Boolean {
    val now = System.currentTimeMillis()
    return _items.value.any { it.authorContactId == contactId && !it.isExpired(now) }
  }

  /**
   * Получить список активных историй для конкретного контакта (или своих при contactId = null)
   */
  fun getActiveItemsFor(contactId: Long?): List<TemporaryContentItem> {
    val now = System.currentTimeMillis()
    return _items.value
      .filter { it.authorContactId == contactId && !it.isExpired(now) }
      .sortedBy { it.createdAtMillis }
  }

  /**
   * Добавление своего контента
   */
  fun addContentFromBytes(
    bytes: ByteArray,
    mediaType: TemporaryMediaType,
    duration: TemporaryDuration,
    caption: String = ""
  ): TemporaryContentItem? {
    return saveMediaItem(
      bytes = bytes,
      mediaType = mediaType,
      durationHours = duration.hours,
      caption = caption,
      authorContactId = null,
      authorDisplayName = null
    )
  }

  /**
   * Сохранение входящего контента от контакта
   */
  fun receiveContactTemporaryContent(
    contactId: Long,
    displayName: String,
    bytes: ByteArray,
    mediaType: TemporaryMediaType,
    durationHours: Int,
    caption: String = "",
    createdAtMillis: Long = System.currentTimeMillis()
  ): TemporaryContentItem? {
    return saveMediaItem(
      bytes = bytes,
      mediaType = mediaType,
      durationHours = durationHours,
      caption = caption,
      authorContactId = contactId,
      authorDisplayName = displayName,
      createdTimestamp = createdAtMillis
    )
  }

  private fun saveMediaItem(
    bytes: ByteArray,
    mediaType: TemporaryMediaType,
    durationHours: Int,
    caption: String,
    authorContactId: Long?,
    authorDisplayName: String?,
    createdTimestamp: Long = System.currentTimeMillis()
  ): TemporaryContentItem? {
    if (storageDir == null) initStorage()
    val dir = storageDir ?: return null

    return try {
      val ext = if (mediaType == TemporaryMediaType.VIDEO) "mp4" else "jpg"
      val id = UUID.randomUUID().toString()
      val destFile = File(dir, "temp_${id}.$ext")
      destFile.writeBytes(bytes)

      val expiresAt = createdTimestamp + (durationHours * 3600_000L)
      val newItem = TemporaryContentItem(
        id = id,
        filePath = destFile.absolutePath,
        mediaType = mediaType,
        createdAtMillis = createdTimestamp,
        expiresAtMillis = expiresAt,
        durationHours = durationHours,
        caption = caption,
        authorContactId = authorContactId,
        authorDisplayName = authorDisplayName
      )

      val updated = (_items.value + newItem).sortedByDescending { it.createdAtMillis }
      _items.value = updated
      saveToDisk(updated)
      newItem
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save temporary content: ${e.message}")
      null
    }
  }

  fun removeContent(id: String) {
    val current = _items.value
    val target = current.find { it.id == id }
    if (target != null) {
      try {
        File(target.filePath).delete()
      } catch (_: Exception) {}
    }
    val updated = current.filterNot { it.id == id }
    _items.value = updated
    saveToDisk(updated)
  }

  fun purgeExpired() {
    val now = System.currentTimeMillis()
    val current = _items.value
    val (expired, active) = current.partition { it.isExpired(now) }

    if (expired.isNotEmpty()) {
      expired.forEach { item ->
        try {
          val f = File(item.filePath)
          if (f.exists()) f.delete()
        } catch (_: Exception) {}
      }
      _items.value = active
      saveToDisk(active)
    }
  }

  private fun loadFromDisk() {
    val file = metadataFile ?: return
    if (!file.exists()) return
    try {
      val content = file.readText()
      val parsed = json.decodeFromString<List<TemporaryContentItem>>(content)
      _items.value = parsed.filter { File(it.filePath).exists() }
    } catch (e: Exception) {
      Log.e(TAG, "Error loading metadata: ${e.message}")
    }
  }

  private fun saveToDisk(list: List<TemporaryContentItem>) {
    val file = metadataFile ?: return
    try {
      file.writeText(json.encodeToString(list))
    } catch (e: Exception) {
      Log.e(TAG, "Error saving metadata: ${e.message}")
    }
  }
}
