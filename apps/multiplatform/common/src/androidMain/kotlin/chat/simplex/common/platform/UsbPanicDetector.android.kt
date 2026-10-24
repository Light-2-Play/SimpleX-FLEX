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
    sp.edit().putBoolean(KEY_PANIC_ON_USB, enabled).commit()
  }

  /**
   * Проверка физического подключения кабеля к USB-порту
   */
  fun isUsbConnectedNow(context: Context): Boolean {
    // 1. Проверка питания через кабель USB (AC или USB, исключая WIRELESS)
    try {
      val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
      if (plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_AC) {
        return true
      }
    } catch (_: Exception) {}

    // 2. BatteryManager API
    try {
      val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
      if (bm != null && bm.isCharging) {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        if (plugged != BatteryManager.BATTERY_PLUGGED_WIRELESS) {
          return true
        }
      }
    } catch (_: Exception) {}

    // 3. Изолированная проверка USB-шины
    try {
      val usbIntent = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
      if (usbIntent?.getBooleanExtra("connected", false) == true) {
        return true
      }
    } catch (_: Exception) {}

    return false
  }

  fun triggerIfConnected(context: Context, model: ChatModel? = chatModelInstance) {
    init(context)
    if (isEnabled() && isUsbConnectedNow(context)) {
      EmergencyKill.purgeAndKill(model, wipeTempMedia = true, clearClipboard = true)
    }
  }

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
      if (!isEnabled()) return

      val action = intent?.action
      // Мгновенная реакция при аппаратном сигнале подключения питания по кабелю
      if (action == Intent.ACTION_POWER_CONNECTED) {
        try {
          val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
          val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
          if (plugged != BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            EmergencyKill.purgeAndKill(chatModelInstance, wipeTempMedia = true, clearClipboard = true)
            return
          }
        } catch (_: Exception) {}
      }

      triggerIfConnected(ctx, chatModelInstance)
    }
  }

  fun startMonitoring(context: Context, model: ChatModel) {
    init(context)
    chatModelInstance = model

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
