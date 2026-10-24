package chat.simplex.common.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import chat.simplex.common.model.ChatModel

object UsbPanicDetector {
  private var isRegistered = false
  private var wasConnected = false
  var isEnabled: () -> Boolean = { false }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == "android.hardware.usb.action.USB_STATE") {
        val connected = intent.extras?.getBoolean("connected") ?: false

        // Срабатывает строго в момент перехода из отключенного состояния в подключенное
        if (connected && !wasConnected) {
          if (isEnabled()) {
            Log.d("UsbPanicDetector", "USB Connected! Triggering Emergency Purge & Kill...")
            try {
              val model = chatModelInstance
              if (model != null) {
                EmergencyKill.purgeAndKill(model, wipeTempMedia = true, clearClipboard = true)
              }
            } catch (e: Exception) {
              android.os.Process.killProcess(android.os.Process.myPid())
            }
          }
        }
        wasConnected = connected
      }
    }
  }

  // Ссылка на активную ChatModel
  var chatModelInstance: ChatModel? = null

  fun startMonitoring(context: Context, model: ChatModel) {
    chatModelInstance = model
    if (isRegistered) return

    val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
    context.registerReceiver(receiver, filter)
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
