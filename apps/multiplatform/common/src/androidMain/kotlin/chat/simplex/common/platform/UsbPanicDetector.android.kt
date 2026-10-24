package chat.simplex.common.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import chat.simplex.common.model.ChatModel

actual object UsbPanicDetector {
  private const val PREFS_NAME = "simplex_flex_security"
  private const val KEY_PANIC_ON_USB = "panic_on_usb"

  private var isRegistered = false
  private var appContext: Context? = null
  var chatModelInstance: ChatModel? = null

  fun init(context: Context) {
    appContext = context.applicationContext
  }

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
   * Проверка физического подключения кабеля к USB-порту:
   * 1. Режим USB Host / Data (подключение к ПК, ноутбуку или форензик-боксу)
   * 2. Статус питания BatteryManager (USB-кабель питает устройство)
   */
  fun isUsbConnectedNow(context: Context): Boolean {
    return try {
      // 1. Проверка системной USB-шины
      val usbFilter = IntentFilter("android.hardware.usb.action.USB_STATE")
      val usbIntent = context.registerReceiver(null, usbFilter)
      val isUsbDataConnected = usbIntent?.getBooleanExtra("connected", false) == true

      // 2. Проверка разъема питания USB (исключая беспроводную зарядку WIRELESS)
      val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
      val batteryIntent = context.registerReceiver(null, batteryFilter)
      val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
      val isCablePlugged = plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_AC

      isUsbDataConnected || isCablePlugged
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Принудительная проверка и моментальное уничтожение, если USB подключен
   */
  fun triggerIfConnected(context: Context, model: ChatModel? = chatModelInstance) {
    init(context)
    if (isEnabled() && isUsbConnectedNow(context)) {
      EmergencyKill.purgeAndKill(model, wipeTempMedia = true, clearClipboard = true)
    }
  }

  /**
   * Проверка при запуске:
   * Если функция включена И кабель USB уже подключен — уничтожаем процесс до старта.
   */
  actual fun checkStartupUsbBlock(activity: Any): Boolean {
    val act = activity as? Activity ?: return false
    init(act)

    if (isEnabled() && isUsbConnectedNow(act)) {
      EmergencyKill.purgeAndKill(chatModel = null, wipeTempMedia = true, clearClipboard = true)
      return true
    }
    return false
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val ctx = context ?: appContext ?: return
      triggerIfConnected(ctx, chatModelInstance)
    }
  }

  fun startMonitoring(context: Context, model: ChatModel) {
    init(context)
    chatModelInstance = model

    // Моментальное завершение, если кабель уже воткнут при старте
    triggerIfConnected(context, model)

    if (isRegistered) return

    val filter = IntentFilter().apply {
      addAction("android.hardware.usb.action.USB_STATE")
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
      addAction("android.hardware.usb.action.USB_ACCESSORY_ATTACHED")
    }

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
