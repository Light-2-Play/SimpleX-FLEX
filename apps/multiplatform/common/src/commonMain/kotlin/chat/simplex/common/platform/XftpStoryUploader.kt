package chat.simplex.common.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
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

  suspend fun uploadEncryptedStory(
    file: File,
    durationHours: Int,
    onProgress: (Int) -> Unit
  ): EncryptedUploadResult? = withContext(Dispatchers.IO) {
    try {
      onProgress(5)
      val rawBytes = file.readBytes()

      // 1. Локальное шифрование файла (AES-256-GCM)
      val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
      val encryptedBytes = cipher.doFinal(rawBytes)
      onProgress(15)

      // 2. Загрузка чанками с расчетом процентов от 15% до 90%
      val uploadedUrl = uploadToTmpFiles(encryptedBytes) { pct ->
        val mapped = 15 + ((pct * 75) / 100)
        onProgress(mapped.coerceIn(15, 90))
      } ?: uploadToLitterbox(encryptedBytes, durationHours) { pct ->
        val mapped = 15 + ((pct * 75) / 100)
        onProgress(mapped.coerceIn(15, 90))
      }

      if (uploadedUrl != null) {
        onProgress(95)
        EncryptedUploadResult(
          downloadUrl = uploadedUrl,
          keyBase64 = Base64.getEncoder().encodeToString(keyBytes),
          ivBase64 = Base64.getEncoder().encodeToString(iv)
        )
      } else {
        null
      }
    } catch (e: Exception) {
      Log.e("XftpUploader", "Upload failed: ${e.message}")
      null
    }
  }

  private fun uploadToTmpFiles(data: ByteArray, onChunkProgress: (Int) -> Unit): String? {
    return try {
      val boundary = "===StoryUpload${System.currentTimeMillis()}==="
      val conn = (URL("https://tmpfiles.org/api/v1/upload").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("User-Agent", "SimpleX-FLEX/1.0")
        connectTimeout = 30_000
        readTimeout = 60_000
      }

      conn.outputStream.use { os ->
        val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)
        val fileId = UUID.randomUUID().toString()

        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"enc_$fileId.bin\"\r\n")
        writer.append("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()

        var offset = 0
        val total = data.size
        val chunkSize = 16384 // 16 KB

        while (offset < total) {
          val len = minOf(chunkSize, total - offset)
          os.write(data, offset, len)
          offset += len
          onChunkProgress((offset * 100L / total).toInt())
        }
        os.flush()

        writer.append("\r\n--$boundary--\r\n")
        writer.flush()
      }

      if (conn.responseCode in 200..299) {
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(response).jsonObject
        val rawUrl = json["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        rawUrl?.replace("tmpfiles.org/", "tmpfiles.org/dl/")
      } else null
    } catch (_: Exception) {
      null
    }
  }

  private fun uploadToLitterbox(data: ByteArray, durationHours: Int, onChunkProgress: (Int) -> Unit): String? {
    return try {
      val boundary = "===LitterStory${System.currentTimeMillis()}==="
      val conn = (URL("https://litterbox.catbox.moe/resources/internals/api.php").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("User-Agent", "Mozilla/5.0")
        connectTimeout = 30_000
        readTimeout = 60_000
      }

      val timeParam = if (durationHours <= 1) "1h" else if (durationHours <= 12) "12h" else "24h"

      conn.outputStream.use { os ->
        val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)
        writer.append("--$boundary\r\nContent-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n")
        writer.append("--$boundary\r\nContent-Disposition: form-data; name=\"time\"\r\n\r\n$timeParam\r\n")
        writer.append("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"enc.bin\"\r\n")
        writer.append("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()

        var offset = 0
        val total = data.size
        val chunkSize = 16384

        while (offset < total) {
          val len = minOf(chunkSize, total - offset)
          os.write(data, offset, len)
          offset += len
          onChunkProgress((offset * 100L / total).toInt())
        }
        os.flush()

        writer.append("\r\n--$boundary--\r\n")
        writer.flush()
      }

      if (conn.responseCode in 200..299) {
        val directUrl = conn.inputStream.bufferedReader().use { it.readText().trim() }
        if (directUrl.startsWith("http")) directUrl else null
      } else null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Скачивание файла с ручной обработкой редиректов (301, 302, 307) и расшифровка
   */
  suspend fun downloadAndDecryptStory(
    downloadUrl: String,
    keyBase64: String,
    ivBase64: String
  ): ByteArray? = withContext(Dispatchers.IO) {
    try {
      val encryptedBytes = downloadBytesWithRedirects(downloadUrl) ?: return@withContext null
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

  private fun downloadBytesWithRedirects(initialUrl: String): ByteArray? {
    var currentUrl = initialUrl
    var redirectCount = 0

    while (redirectCount < 6) {
      val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        connectTimeout = 25_000
        readTimeout = 60_000
        instanceFollowRedirects = false // Ручной контроль редиректов
      }

      val code = conn.responseCode
      if (code in 300..399) {
        val location = conn.getHeaderField("Location") ?: return null
        currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
        redirectCount++
        conn.disconnect()
        continue
      }

      if (code in 200..299) {
        return conn.inputStream.use { it.readBytes() }
      }
      return null
    }
    return null
  }
}
