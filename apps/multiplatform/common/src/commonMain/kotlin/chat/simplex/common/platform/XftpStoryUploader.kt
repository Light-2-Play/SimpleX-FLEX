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

  suspend fun uploadEncryptedStory(file: File, durationHours: Int): EncryptedUploadResult? = withContext(Dispatchers.IO) {
    try {
      val rawBytes = file.readBytes()

      // 1. Генерация 256-битного ключа и 96-битного вектора инициализации (IV)
      val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

      // 2. Локальное шифрование AES-256-GCM
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
      val encryptedBytes = cipher.doFinal(rawBytes)

      // 3. Выгрузка зашифрованного блоба
      val uploadedUrl = uploadToTmpFiles(encryptedBytes) ?: uploadToLitterbox(encryptedBytes, durationHours)

      if (uploadedUrl != null) {
        EncryptedUploadResult(
          downloadUrl = uploadedUrl,
          keyBase64 = Base64.getEncoder().encodeToString(keyBytes),
          ivBase64 = Base64.getEncoder().encodeToString(iv)
        )
      } else {
        null
      }
    } catch (e: Exception) {
      Log.e("XftpUploader", "Encryption/upload failed: ${e.message}")
      null
    }
  }

  private fun uploadToTmpFiles(data: ByteArray): String? {
    return try {
      val boundary = "===TmpStory${System.currentTimeMillis()}==="
      val conn = (URL("https://tmpfiles.org/api/v1/upload").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("User-Agent", "SimpleX-FLEX/1.0")
        connectTimeout = 25_000
        readTimeout = 60_000
      }

      conn.outputStream.use { os ->
        val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)
        val fileId = UUID.randomUUID().toString()

        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"story_$fileId.bin\"\r\n")
        writer.append("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()

        os.write(data)
        os.flush()

        writer.append("\r\n--$boundary--\r\n")
        writer.flush()
      }

      if (conn.responseCode in 200..299) {
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(response).jsonObject
        val rawUrl = json["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        // Преобразуем страницу просмотра в прямую ссылку на скачивание
        rawUrl?.replace("tmpfiles.org/", "tmpfiles.org/dl/")
      } else null
    } catch (_: Exception) {
      null
    }
  }

  private fun uploadToLitterbox(data: ByteArray, durationHours: Int): String? {
    return try {
      val boundary = "===LitterStory${System.currentTimeMillis()}==="
      val conn = (URL("https://litterbox.catbox.moe/resources/internals/api.php").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("User-Agent", "Mozilla/5.0")
        connectTimeout = 25_000
        readTimeout = 60_000
      }

      val timeParam = if (durationHours <= 1) "1h" else if (durationHours <= 12) "12h" else "24h"

      conn.outputStream.use { os ->
        val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)

        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n")
        writer.append("fileupload\r\n")

        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"time\"\r\n\r\n")
        writer.append("$timeParam\r\n")

        writer.append("--$boundary\r\n")
        writer.append("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"story.bin\"\r\n")
        writer.append("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()

        os.write(data)
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

  suspend fun downloadAndDecryptStory(
    downloadUrl: String,
    keyBase64: String,
    ivBase64: String
  ): ByteArray? = withContext(Dispatchers.IO) {
    try {
      val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("User-Agent", "Mozilla/5.0")
        connectTimeout = 25_000
        readTimeout = 60_000
        instanceFollowRedirects = true
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
