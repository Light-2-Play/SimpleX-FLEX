package chat.simplex.common.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import chat.simplex.common.model.ChatModel

actual object UsbPanicDetector {
  private const val PREFS_NAME = "simplex_flex_security"
  private const val KEY_PANIC_ON_USB = "panic_on_usb"

  private var isRegistered = false
  private var wasConnected = false
  private var isFirstBroadcast = true
  private var appContext: Context? = null
  var chatModelInstance: ChatModel? = null

  private fun getContext(): Context? {
    if (appContext != null) return appContext
    return try {
      val at = Class.forName("android.app.ActivityThread")
      val app = at.getMethod("currentApplication").invoke(null) as? Context
      appContext = app?.applicationContext
      appContext
    } catch (_: Exception) {
      null
    }
  }

  actual fun isEnabled(): Boolean {
    val ctx = getContext() ?: return false
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getBoolean(KEY_PANIC_ON_USB, false)
  }

  actual fun setEnabled(enabled: Boolean) {
    val ctx = getContext() ?: return
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putBoolean(KEY_PANIC_ON_USB, enabled).apply()
  }

  /**
   * Синхронная проверка текущего физического состояния USB-порта.
   * Опрашивает системный sticky-интент без необходимости ожидания событий.
   */
  fun isUsbConnectedNow(context: Context): Boolean {
    return try {
      val stickyIntent = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
      stickyIntent?.extras?.getBoolean("connected") == true
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Проверка при запуске:
   * Если функция включена И кабель USB подключен — приложение мгновенно уничтожается.
   */
  fun checkStartupUsbBlock(activity: Activity): Boolean {
    if (isEnabled() && isUsbConnectedNow(activity)) {
      EmergencyKill.purgeAndKill(chatModel = null, wipeTempMedia = true, clearClipboard = true)
      return true
    }
    return false
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == "android.hardware.usb.action.USB_STATE") {
        val connected = intent.extras?.getBoolean("connected") ?: false

        if (isFirstBroadcast) {
          isFirstBroadcast = false
          wasConnected = connected
          return
        }

        if (connected && !wasConnected) {
          if (isEnabled()) {
            try {
              EmergencyKill.purgeAndKill(chatModelInstance, wipeTempMedia = true, clearClipboard = true)
            } catch (_: Exception) {
              Process.killProcess(Process.myPid())
            }
          }
        }
        wasConnected = connected
      }
    }
  }

  fun startMonitoring(context: Context, model: ChatModel) {
    appContext = context.applicationContext
    chatModelInstance = model
    if (isRegistered) return

    val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      context.registerReceiver(receiver, filter)
    }
    isRegistered = true
  }

  fun stopMonitoring(context: Context) {
    if (!isRegistered) return
    try {
      context.unregisterReceiver(receiver)
    } catch (_: Exception) {}
    isRegistered = false
  }
}
