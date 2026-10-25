package chat.simplex.common.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

actual object RichHaptics {
  private const val PREFS_NAME = "simplex_flex_security"
  private const val KEY_ADVANCED_HAPTICS = "advanced_haptics"

  private var appContext: Context? = null

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

      // 1. Приоритетный путь: Аппаратные примитивы на Android 11+ (API 30+)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (playPrimitiveComposition(vibrator, pattern)) {
          return
        }
      }

      // 2. Fallback: Точечные микро-волновые формы (API 26+)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = getWaveformEffect(pattern)
        vibrator.vibrate(effect)
      } else {
        // 3. Устаревший fallback для старых API
        @Suppress("DEPRECATION")
        vibrator.vibrate(25)
      }
    } catch (_: Exception) {}
  }

  /**
   * Аппаратный композитный движок с прецизионной физикой
   */
  private fun playPrimitiveComposition(vibrator: Vibrator, pattern: HapticPattern): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

    return try {
      val comp = VibrationEffect.startComposition()

      when (pattern) {
        HapticPattern.CLICK -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
        }
        HapticPattern.HEAVY_CLICK -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.4f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
        }
        HapticPattern.DOUBLE_CLICK -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 40)
        }
        HapticPattern.TICK -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
        }
        HapticPattern.SELECTION_CHANGE -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f)
        }
        HapticPattern.TOGGLE_ON -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 25)
        }
        HapticPattern.TOGGLE_OFF -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.3f, 25)
        }
        HapticPattern.SEND_MESSAGE -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.5f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
        }
        
        // Плотная выразительная волна без металлического щелчка
        HapticPattern.RECORD_START -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.78f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.62f)
        }
        
        HapticPattern.RECORD_STOP -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.45f)
        }

        HapticPattern.SUCCESS -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 35)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f, 35)
        }
        HapticPattern.ERROR -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 45)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 45)
        }
        HapticPattern.WARNING -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 50)
        }
        HapticPattern.SWIPE_ACTION -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
        }
      }

      vibrator.vibrate(comp.compose())
      true
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Гранулированные амплитудные профили миллисекундной точности (0..255)
   */
  private fun getWaveformEffect(pattern: HapticPattern): VibrationEffect {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      when (pattern) {
        HapticPattern.CLICK ->
          VibrationEffect.createWaveform(longArrayOf(0, 10), intArrayOf(0, 130), -1)

        HapticPattern.HEAVY_CLICK ->
          VibrationEffect.createWaveform(longArrayOf(0, 22), intArrayOf(0, 255), -1)

        HapticPattern.DOUBLE_CLICK ->
          VibrationEffect.createWaveform(longArrayOf(0, 10, 40, 14), intArrayOf(0, 160, 0, 220), -1)

        HapticPattern.TICK ->
          VibrationEffect.createWaveform(longArrayOf(0, 6), intArrayOf(0, 70), -1)

        HapticPattern.SELECTION_CHANGE ->
          VibrationEffect.createWaveform(longArrayOf(0, 4), intArrayOf(0, 45), -1)

        HapticPattern.TOGGLE_ON ->
          VibrationEffect.createWaveform(longArrayOf(0, 8, 25, 14), intArrayOf(0, 70, 0, 210), -1)

        HapticPattern.TOGGLE_OFF ->
          VibrationEffect.createWaveform(longArrayOf(0, 14, 25, 8), intArrayOf(0, 190, 0, 50), -1)

        HapticPattern.SEND_MESSAGE ->
          VibrationEffect.createWaveform(longArrayOf(0, 12, 10, 16), intArrayOf(0, 60, 150, 255), -1)

        // Мягкий синусоидальный холм: 0 -> 45 -> 110 -> 160 -> 95 -> 30 -> 0
       HapticPattern.RECORD_START ->
          VibrationEffect.createWaveform(
            longArrayOf(0, 18, 20, 24, 20, 16),
            intArrayOf(0, 65, 145, 205, 125, 40),
            -1
          )

        HapticPattern.RECORD_STOP ->
          VibrationEffect.createWaveform(
            longArrayOf(0, 14, 18),
            intArrayOf(0, 110, 45),
            -1
          )

        HapticPattern.SUCCESS ->
          VibrationEffect.createWaveform(longArrayOf(0, 8, 35, 12, 35, 20), intArrayOf(0, 80, 0, 180, 0, 255), -1)

        HapticPattern.ERROR ->
          VibrationEffect.createWaveform(longArrayOf(0, 18, 45, 18, 45, 22), intArrayOf(0, 230, 0, 230, 0, 255), -1)

        HapticPattern.WARNING ->
          VibrationEffect.createWaveform(longArrayOf(0, 35, 60, 45), intArrayOf(0, 200, 0, 255), -1)

        HapticPattern.SWIPE_ACTION ->
          VibrationEffect.createWaveform(longArrayOf(0, 8), intArrayOf(0, 120), -1)
      }
    } else {
      @Suppress("DEPRECATION")
      VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
    }
  }
}
