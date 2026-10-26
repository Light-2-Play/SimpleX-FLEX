package chat.simplex.common.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedUploadResult(
  val downloadUrl: String,
  val keyBase64: String,
  val ivBase64: String
)

object XftpStoryUploader {
  // Публичный сервис временного размещения с авто-удалением через 24 часа
  private const val UPLOAD_API = "https://litterbox.catbox.moe/resources/internals/api.php"

  suspend fun uploadEncryptedStory(file: File, durationHours: Int): EncryptedUploadResult? = withContext(Dispatchers.IO) {
    try {
      val rawBytes = file.readBytes()

      // 1. Генерация ключа AES-256 и IV (96 бит)
      val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

      // 2. Шифрование файла на устройстве
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
      val encryptedBytes = cipher.doFinal(rawBytes)

      // 3. Загрузка зашифрованного шума через Multipart POST
      val boundary = "===StoryBoundary${System.currentTimeMillis()}==="
      val conn = (URL(UPLOAD_API).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("User-Agent", "SimpleX-Stories/1.0")
        connectTimeout = 30_000
        readTimeout = 60_000
      }

      val timeParam = when {
        durationHours <= 1 -> "1h"
        durationHours <= 12 -> "12h"
        else -> "24h"
      }

      conn.outputStream.use { os ->
        val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)

        // reqtype = fileupload
        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n")
        writer.append("fileupload\r\n")

        // time = 1h / 12h / 24h
        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"time\"\r\n\r\n")
        writer.append("$timeParam\r\n")

        // fileToUpload
        val fileId = UUID.randomUUID().toString()
        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"encrypted_$fileId.bin\"\r\n")
        writer.append("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()

        os.write(encryptedBytes)
        os.flush()

        writer.append("\r\n--$boundary--\r\n")
        writer.flush()
      }

      if (conn.responseCode in 200..299) {
        val resultUrl = conn.inputStream.bufferedReader().use { it.readText().trim() }
        if (resultUrl.startsWith("http")) {
          EncryptedUploadResult(
            downloadUrl = resultUrl,
            keyBase64 = Base64.getEncoder().encodeToString(keyBytes),
            ivBase64 = Base64.getEncoder().encodeToString(iv)
          )
        } else null
      } else null
    } catch (e: Exception) {
      Log.e("XftpUploader", "Upload failed: ${e.message}")
      null
    }
  }

  suspend fun downloadAndDecryptStory(
    downloadUrl: String,
    keyBase64: String,
    ivBase64: String
  ): ByteArray? = withContext(Dispatchers.IO) {
    try {
      val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("User-Agent", "SimpleX-Stories/1.0")
        connectTimeout = 20_000
        readTimeout = 60_000
      }

      val encryptedBytes = conn.inputStream.use { it.readBytes() }
      val keyBytes = Base64.getDecoder().decode(keyBase64)
      val iv = Base64.getDecoder().decode(ivBase64)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
      cipher.doFinal(encryptedBytes)
    } catch (e: Exception) {
      Log.e("XftpUploader", "Download/Decrypt failed: ${e.message}")
      null
    }
  }
}
