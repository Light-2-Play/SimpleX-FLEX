package chat.simplex.common.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

actual object RichHaptics {
  private const val PREFS_NAME = "simplex_flex_security"
  private const val KEY_ADVANCED_HAPTICS = "advanced_haptics"
  private const val KEY_USB_PANIC = "usb_panic_enabled"

  private var appContext: Context? = null
  @Volatile
  private var cachedSupportedPrimitives: Set<Int>? = null

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

  // --- Настройки Advanced Haptics ---
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

  // --- Настройки USB Panic ---
  actual fun isUsbPanicEnabled(): Boolean {
    val ctx = getContext() ?: return false
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getBoolean(KEY_USB_PANIC, false)
  }

  actual fun setUsbPanicEnabled(enabled: Boolean) {
    val ctx = getContext() ?: return
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putBoolean(KEY_USB_PANIC, enabled).apply()
  }

  // --- Основная точка входа ---
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

      // Уровень 1: Аппаратные примитивы (Android 11+) с проверкой поддержки HAL и авто-заменой
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (playAdaptivePrimitiveComposition(vibrator, pattern)) {
          return
        }
      }

      // Уровень 2: Откалиброванные OEM-пресеты (Samsung OneUI, Xiaomi HyperOS, OnePlus — Android 10+)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val predefined = getPredefinedEffectIfBest(vibrator, pattern)
        if (predefined != null) {
          vibrateWithTouchAttributes(vibrator, predefined)
          return
        }
      }

      // Уровень 3 и 4: Амплитудные кривые (для LRA) или адаптированные импульсы (для базовых ERM)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = if (vibrator.hasAmplitudeControl()) {
          getAmplitudeWaveformEffect(pattern)
        } else {
          getErmWaveformEffect(pattern)
        }
        vibrateWithTouchAttributes(vibrator, effect)
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(25L)
      }
    } catch (_: Exception) {}
  }

  /**
   * Запускает вибрацию с атрибутом USAGE_TOUCH, чтобы отклик работал в беззвучном режиме
   * и не глушился настройками громкости уведомлений на Samsung/Xiaomi.
   */
  @RequiresApi(Build.VERSION_CODES.O)
  private fun vibrateWithTouchAttributes(vibrator: Vibrator, effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val attrs = VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_TOUCH)
        .build()
      vibrator.vibrate(effect, attrs)
    } else {
      val audioAttrs = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .build()
      @Suppress("DEPRECATION")
      vibrator.vibrate(effect, audioAttrs)
    }
  }

  /**
   * Кэширует список примитивов, реально поддерживаемых вибромотором текущего устройства.
   */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun getSupportedPrimitives(vibrator: Vibrator): Set<Int> {
    cachedSupportedPrimitives?.let { return it }

    val candidates = buildList {
      add(VibrationEffect.Composition.PRIMITIVE_CLICK)
      add(VibrationEffect.Composition.PRIMITIVE_TICK)
      add(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
      add(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
      add(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
      }
    }

    val supported = candidates.filter { primitiveId ->
      try {
        vibrator.areAllPrimitivesSupported(primitiveId)
      } catch (_: Exception) {
        false
      }
    }.toSet()

    cachedSupportedPrimitives = supported
    return supported
  }

  /**
   * Уровень 1: Композитный движок.
   * Если аппарат поддерживает только CLICK/TICK (частый случай на Xiaomi/OnePlus/Samsung),
   * автоматически заменяет RISE/FALL/LOW_TICK на эквивалентную комбинацию, вместо того чтобы молчать.
   */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun playAdaptivePrimitiveComposition(vibrator: Vibrator, pattern: HapticPattern): Boolean {
    val supported = getSupportedPrimitives(vibrator)
    // Если мотор вообще не поддерживает базовый CLICK — уходим на OEM Predefined / Waveforms
    if (VibrationEffect.Composition.PRIMITIVE_CLICK !in supported) return false

    val hasTick = VibrationEffect.Composition.PRIMITIVE_TICK in supported
    val hasLowTick = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      VibrationEffect.Composition.PRIMITIVE_LOW_TICK in supported
    val hasQuickRise = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE in supported
    val hasSlowRise = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE in supported
    val hasQuickFall = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL in supported

    val tickId = if (hasTick) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
    val lowTickId = when {
      hasLowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
      hasTick -> VibrationEffect.Composition.PRIMITIVE_TICK
      else -> VibrationEffect.Composition.PRIMITIVE_CLICK
    }

    return try {
      val comp = VibrationEffect.startComposition()

      when (pattern) {
        HapticPattern.CLICK -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.72f)
        }
        HapticPattern.HEAVY_CLICK -> {
          if (hasQuickRise) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.45f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
          } else {
            comp.addPrimitive(tickId, 0.45f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 12)
          }
        }
        HapticPattern.DOUBLE_CLICK -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 45)
        }
        HapticPattern.TICK -> {
          comp.addPrimitive(tickId, if (hasTick) 0.5f else 0.35f)
        }
        HapticPattern.SELECTION_CHANGE -> {
          comp.addPrimitive(lowTickId, if (hasLowTick) 0.4f else 0.3f)
        }
        HapticPattern.TOGGLE_ON -> {
          comp.addPrimitive(tickId, 0.45f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 28)
        }
        HapticPattern.TOGGLE_OFF -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
          comp.addPrimitive(lowTickId, 0.35f, 28)
        }
        HapticPattern.SEND_MESSAGE -> {
          if (hasQuickRise) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.55f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f)
          } else {
            comp.addPrimitive(tickId, 0.45f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 18)
          }
        }
        HapticPattern.RECORD_START -> {
          if (hasSlowRise && hasQuickFall) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.8f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.65f)
          } else {
            comp.addPrimitive(tickId, 0.4f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 22)
            comp.addPrimitive(tickId, 0.45f, 22)
          }
        }
        HapticPattern.RECORD_STOP -> {
          if (hasQuickFall) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f)
          } else {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
            comp.addPrimitive(tickId, 0.3f, 18)
          }
        }
        HapticPattern.SUCCESS -> {
          comp.addPrimitive(tickId, 0.45f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 35)
          if (hasQuickRise) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f, 35)
          } else {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 35)
          }
        }
        HapticPattern.ERROR -> {
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 45)
          comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 45)
        }
        HapticPattern.WARNING -> {
          if (hasQuickRise) {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 50)
          } else {
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f)
            comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 55)
          }
        }
        HapticPattern.SWIPE_ACTION -> {
          comp.addPrimitive(tickId, if (hasTick) 0.65f else 0.45f)
        }
      }

      vibrateWithTouchAttributes(vibrator, comp.compose())
      true
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Уровень 2: Системные пресеты прошивки (Android 10+).
   * На Samsung OneUI / Xiaomi HyperOS / OnePlus одиночные щелчки через createPredefined
   * используют фирменные калиброванные профили линейного мотора.
   * Сложные многофазные паттерны (SUCCESS, ERROR, RECORD_START) передаются дальше на уровень 3.
   */
  @RequiresApi(Build.VERSION_CODES.Q)
  private fun getPredefinedEffectIfBest(vibrator: Vibrator, pattern: HapticPattern): VibrationEffect? {
    val hasAmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.hasAmplitudeControl() else false

    return when (pattern) {
      HapticPattern.CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
      HapticPattern.HEAVY_CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
      HapticPattern.DOUBLE_CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
      HapticPattern.TICK,
      HapticPattern.SELECTION_CHANGE,
      HapticPattern.SWIPE_ACTION -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

      // Если у мотора нет контроля амплитуды (ERM), используем системные пресеты и для остальных событий
      HapticPattern.TOGGLE_ON,
      HapticPattern.SEND_MESSAGE -> if (!hasAmp) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else null
      HapticPattern.TOGGLE_OFF,
      HapticPattern.RECORD_STOP -> if (!hasAmp) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else null
      HapticPattern.RECORD_START -> if (!hasAmp) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else null
      HapticPattern.SUCCESS -> if (!hasAmp) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK) else null
      HapticPattern.ERROR,
      HapticPattern.WARNING -> null
    }
  }

  /**
   * Уровень 3: Гранулированные амплитудные профили для LRA-моторов с контролем амплитуды (0..255).
   */
  @RequiresApi(Build.VERSION_CODES.O)
  private fun getAmplitudeWaveformEffect(pattern: HapticPattern): VibrationEffect {
    return when (pattern) {
      HapticPattern.CLICK ->
        VibrationEffect.createWaveform(longArrayOf(0, 12), intArrayOf(0, 160), -1)

      HapticPattern.HEAVY_CLICK ->
        VibrationEffect.createWaveform(longArrayOf(0, 24), intArrayOf(0, 255), -1)

      HapticPattern.DOUBLE_CLICK ->
        VibrationEffect.createWaveform(longArrayOf(0, 12, 45, 16), intArrayOf(0, 175, 0, 235), -1)

      HapticPattern.TICK ->
        VibrationEffect.createWaveform(longArrayOf(0, 8), intArrayOf(0, 95), -1)

      HapticPattern.SELECTION_CHANGE ->
        VibrationEffect.createWaveform(longArrayOf(0, 6), intArrayOf(0, 75), -1)

      HapticPattern.TOGGLE_ON ->
        VibrationEffect.createWaveform(longArrayOf(0, 10, 28, 16), intArrayOf(0, 90, 0, 220), -1)

      HapticPattern.TOGGLE_OFF ->
        VibrationEffect.createWaveform(longArrayOf(0, 16, 28, 10), intArrayOf(0, 200, 0, 75), -1)

      HapticPattern.SEND_MESSAGE ->
        VibrationEffect.createWaveform(longArrayOf(0, 14, 12, 18), intArrayOf(0, 80, 165, 255), -1)

      HapticPattern.RECORD_START ->
        VibrationEffect.createWaveform(
          longArrayOf(0, 18, 22, 26, 22, 16),
          intArrayOf(0, 75, 155, 220, 140, 55),
          -1
        )

      HapticPattern.RECORD_STOP ->
        VibrationEffect.createWaveform(
          longArrayOf(0, 16, 18),
          intArrayOf(0, 130, 55),
          -1
        )

      HapticPattern.SUCCESS ->
        VibrationEffect.createWaveform(longArrayOf(0, 10, 35, 14, 35, 22), intArrayOf(0, 95, 0, 185, 0, 255), -1)

      HapticPattern.ERROR ->
        VibrationEffect.createWaveform(longArrayOf(0, 20, 45, 20, 45, 24), intArrayOf(0, 235, 0, 235, 0, 255), -1)

      HapticPattern.WARNING ->
        VibrationEffect.createWaveform(longArrayOf(0, 32, 55, 42), intArrayOf(0, 205, 0, 255), -1)

      HapticPattern.SWIPE_ACTION ->
        VibrationEffect.createWaveform(longArrayOf(0, 10), intArrayOf(0, 140), -1)
    }
  }

  /**
   * Уровень 4: Профили для базовых ERM-моторов (без контроля амплитуды),
   * где импульсы короче 15 мс не успевают раскрутить эксцентрик, а паузы должны быть от 50 мс.
   */
  @RequiresApi(Build.VERSION_CODES.O)
  private fun getErmWaveformEffect(pattern: HapticPattern): VibrationEffect {
    return when (pattern) {
      HapticPattern.CLICK ->
        VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.HEAVY_CLICK ->
        VibrationEffect.createOneShot(32, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.DOUBLE_CLICK ->
        VibrationEffect.createWaveform(longArrayOf(0, 20, 55, 24), -1)

      HapticPattern.TICK,
      HapticPattern.SELECTION_CHANGE ->
        VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.SWIPE_ACTION ->
        VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.TOGGLE_ON ->
        VibrationEffect.createWaveform(longArrayOf(0, 16, 50, 25), -1)

      HapticPattern.TOGGLE_OFF ->
        VibrationEffect.createWaveform(longArrayOf(0, 24, 50, 16), -1)

      HapticPattern.SEND_MESSAGE ->
        VibrationEffect.createOneShot(24, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.RECORD_START ->
        VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.RECORD_STOP ->
        VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)

      HapticPattern.SUCCESS ->
        VibrationEffect.createWaveform(longArrayOf(0, 18, 55, 28), -1)

      HapticPattern.ERROR ->
        VibrationEffect.createWaveform(longArrayOf(0, 24, 55, 24, 55, 28), -1)

      HapticPattern.WARNING ->
        VibrationEffect.createWaveform(longArrayOf(0, 32, 65, 40), -1)
    }
  }
}
