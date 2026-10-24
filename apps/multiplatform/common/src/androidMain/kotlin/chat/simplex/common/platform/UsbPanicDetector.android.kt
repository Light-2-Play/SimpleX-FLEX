package chat.simplex.common.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import chat.simplex.common.model.ChatModel

actual object UsbPanicDetector {
  private var isRegistered = false
  private var wasConnected = false
  actual var isEnabled: () -> Boolean = { false }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == "android.hardware.usb.action.USB_STATE") {
        val connected = intent.extras?.getBoolean("connected") ?: false

        if (connected && !wasConnected) {
          if (isEnabled()) {
            try {
              val model = chatModelInstance
              if (model != null) {
                EmergencyKill.purgeAndKill(model, wipeTempMedia = true, clearClipboard = true)
              }
            } catch (_: Exception) {
              android.os.Process.killProcess(android.os.Process.myPid())
            }
          }
        }
        wasConnected = connected
      }
    }
  }

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
