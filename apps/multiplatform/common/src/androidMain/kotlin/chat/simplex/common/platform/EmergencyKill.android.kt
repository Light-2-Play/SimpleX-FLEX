package chat.simplex.common.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Process
import chat.simplex.common.model.ChatModel
import kotlin.system.exitProcess

actual object EmergencyKill {

  private val context: Context? by lazy {
    try {
      val at = Class.forName("android.app.ActivityThread")
      at.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Exception) {
      null
    }
  }

  actual fun purgeAndKill(
    chatModel: ChatModel?,
    wipeTempMedia: Boolean,
    clearClipboard: Boolean
  ) {
    val ctx = context

    // 1. Очистка системного буфера обмена (Clipboard)
    if (clearClipboard && ctx != null) {
      try {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
          } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
          }
        }
      } catch (_: Exception) {}
    }

    // 2. Стирание временного кэша и расшифрованных файлов
    if (wipeTempMedia && ctx != null) {
      try {
        ctx.cacheDir?.deleteRecursively()
        ctx.externalCacheDir?.deleteRecursively()
        ChatModel.filesToDelete.forEach { runCatching { it.delete() } }
        ChatModel.filesToDelete.clear()
      } catch (_: Exception) {}
    }

    // 3. Закрытие шифрованных сессий (если модель была инициализирована)
    if (chatModel != null) {
      try {
        chatModel.controller.stopRemoteHostAndReloadHosts(chatModel.currentRemoteHost.value ?: return, true)
      } catch (_: Exception) {}
    }

    // 4. Удаление задачи из списка недавних приложений (Recents)
    try {
      val atClass = Class.forName("android.app.ActivityThread")
      val currentActivityThread = atClass.getMethod("currentActivityThread").invoke(null)
      val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
      val activities = activitiesField.get(currentActivityThread) as? Map<*, *>

      activities?.values?.forEach { record ->
        val activityField = record?.javaClass?.getDeclaredField("activity")?.apply { isAccessible = true }
        (activityField?.get(record) as? Activity)?.let {
          it.finishAffinity()
          it.finishAndRemoveTask()
        }
      }
    } catch (_: Exception) {}

    // 5. Моментальное завершение процесса на уровне ядра Linux (SIGKILL)
    Process.killProcess(Process.myPid())
    exitProcess(0)
  }
}
