package chat.simplex.common.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

actual object RichHaptics {
  private const val PREFS_NAME = "simplex_flex_security"
  private const val KEY_ADVANCED_HAPTICS = "advanced_haptics"

  private fun getContext(): Context? {
    return try {
      val at = Class.forName("android.app.ActivityThread")
      at.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Exception) {
      null
    }
  }

  actual fun isEnabled(): Boolean {
    val ctx = getContext() ?: return true
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getBoolean(KEY_ADVANCED_HAPTICS, true)
  }

  actual fun setEnabled(enabled: Boolean) {
    val ctx = getContext() ?: return
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putBoolean(KEY_ADVANCED_HAPTICS, enabled).apply()
  }

  actual fun perform(pattern: HapticPattern) {
    // Если тактильный отклик выключен пользователем — выходим сразу
    if (!isEnabled()) return

    val ctx = getContext() ?: return
    try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      } ?: return

      if (!vibrator.hasVibrator()) return

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effect = when (pattern) {
          HapticPattern.CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
          HapticPattern.HEAVY_CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
          HapticPattern.DOUBLE_CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
          HapticPattern.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
          HapticPattern.WARNING -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), intArrayOf(0, 180, 0, 255), -1)
          HapticPattern.TOGGLE_OFF -> VibrationEffect.createOneShot(20, 80)
        }
        vibrator.vibrate(effect)
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(30)
      }
    } catch (_: Exception) {}
  }
}
