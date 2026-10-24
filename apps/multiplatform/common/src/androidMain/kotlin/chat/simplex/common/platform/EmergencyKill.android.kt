actual fun purgeAndKill(
    chatModel: ChatModel?,
    wipeTempMedia: Boolean,
    clearClipboard: Boolean
  ) {
    val ctx = context

    // 1. Очистка буфера обмена
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

    // 2. Стирание кэша
    if (wipeTempMedia && ctx != null) {
      try {
        ctx.cacheDir?.deleteRecursively()
        ctx.externalCacheDir?.deleteRecursively()
        ChatModel.filesToDelete.forEach { runCatching { it.delete() } }
        ChatModel.filesToDelete.clear()
      } catch (_: Exception) {}
    }

    // 3. Удаление задач из Recents
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

    // 4. Принудительное завершение
    Process.killProcess(Process.myPid())
    exitProcess(0)
  }
