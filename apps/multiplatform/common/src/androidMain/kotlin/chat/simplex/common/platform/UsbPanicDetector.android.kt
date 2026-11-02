package chat.simplex.common.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.system.exitProcess

actual object UsbPanicDetector {
    private const val PREFS = "flex_security_prefs"
    private const val KEY = "panic_on_usb"
    private var isMonitoring = false

    actual fun isEnabled(): Boolean {
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    actual fun setEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        if (enabled) {
            checkAndKill()
            startMonitoring()
        }
    }

    private fun checkAndKill() {
        val ctx = appContext ?: return
        if (!isEnabled()) return
        
        // Считываем статус подключения на самом низком аппаратном уровне
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        
        // BATTERY_PLUGGED_USB означает именно data-кабель (ПК, отладчик или хакерский дамп)
        val isUsb = plugged == BatteryManager.BATTERY_PLUGGED_USB

        if (isUsb) {
            // Мгновенный SHUTDOWN процесса без возможности перехвата
            exitProcess(0) 
        }
    }

    actual fun startMonitoring() {
        val ctx = appContext ?: return
        if (isMonitoring) return
        isMonitoring = true
        
        // 1. Блокируем запуск, если USB УЖЕ подключен
        checkAndKill()

        // 2. Вешаем фоновый слушатель на подключение кабеля "на лету"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkAndKill()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction("android.hardware.usb.action.USB_STATE")
        }
        ctx.registerReceiver(receiver, filter)
    }
}
