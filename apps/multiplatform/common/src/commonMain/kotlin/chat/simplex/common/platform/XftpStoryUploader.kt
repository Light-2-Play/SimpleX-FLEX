package chat.simplex.common.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
  private const val DEFAULT_XFTP_SERVER = "https://xftp.simplex.im/files"

  suspend fun uploadEncryptedStory(file: File): EncryptedUploadResult? = withContext(Dispatchers.IO) {
    try {
      val rawBytes = file.readBytes()
      val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val keySpec = SecretKeySpec(keyBytes, "AES")
      val gcmSpec = GCMParameterSpec(128, iv)
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
      val encryptedBytes = cipher.doFinal(rawBytes)

      val uploadId = UUID.randomUUID().toString()
      val targetUrl = "$DEFAULT_XFTP_SERVER/$uploadId"
      val url = URL(targetUrl)

      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/octet-stream")
        connectTimeout = 15_000
        readTimeout = 30_000
      }

      conn.outputStream.use { it.write(encryptedBytes) }

      if (conn.responseCode in 200..299) {
        EncryptedUploadResult(
          downloadUrl = targetUrl,
          keyBase64 = Base64.getEncoder().encodeToString(keyBytes),
          ivBase64 = Base64.getEncoder().encodeToString(iv)
        )
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
      val url = URL(downloadUrl)
      val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 30_000
      }

      val encryptedBytes = conn.inputStream.use { it.readBytes() }
      val keyBytes = Base64.getDecoder().decode(keyBase64)
      val iv = Base64.getDecoder().decode(ivBase64)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val keySpec = SecretKeySpec(keyBytes, "AES")
      val gcmSpec = GCMParameterSpec(128, iv)
      cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

      cipher.doFinal(encryptedBytes)
    } catch (e: Exception) {
      Log.e("XftpUploader", "Download/Decrypt failed: ${e.message}")
      null
    }
  }
}
