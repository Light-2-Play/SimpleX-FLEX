package chat.simplex.common.platform

import android.annotation.SuppressLint
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

    // Нативный способ получить Context в любом Android-модуле без импортов
    @SuppressLint("PrivateApi")
    private fun getCtx(): Context {
        return Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as Context
    }

    actual fun isEnabled(): Boolean {
        return getCtx().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    actual fun setEnabled(enabled: Boolean) {
        // Явное указание типа спасает от ошибки "Cannot infer type"
        val editor: android.content.SharedPreferences.Editor = getCtx().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor.putBoolean(KEY, enabled)
        editor.apply()
        
        if (enabled) {
            checkAndKill()
            startMonitoring()
        }
    }

    private fun checkAndKill() {
        if (!isEnabled()) return
        
        // Считываем статус подключения на самом низком аппаратном уровне
        val intent = getCtx().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        
        // BATTERY_PLUGGED_USB означает именно data-кабель (ПК, отладчик или хакерский дамп)
        val isUsb = plugged == BatteryManager.BATTERY_PLUGGED_USB

        if (isUsb) {
            // Мгновенный SHUTDOWN процесса без возможности перехвата
            exitProcess(0) 
        }
    }

    actual fun startMonitoring() {
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
        getCtx().registerReceiver(receiver, filter)
    }
}
