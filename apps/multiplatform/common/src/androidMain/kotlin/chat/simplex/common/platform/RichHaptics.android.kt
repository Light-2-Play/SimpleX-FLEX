package chat.simplex.common.platform

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

actual object RichHaptics {

  private val vibrator: Vibrator? by lazy {
    val ctx = appContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      manager?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
  }

  private fun isHapticFeedbackEnabled(): Boolean {
    return try {
      Settings.System.getInt(
        appContext.contentResolver,
        Settings.System.HAPTIC_FEEDBACK_ENABLED,
        1
      ) != 0
    } catch (_: Exception) {
      true
    }
  }

  actual fun perform(pattern: HapticPattern) {
    val vib = vibrator ?: return
    if (!vib.hasVibrator() || !isHapticFeedbackEnabled()) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Android 11+ (API 30+) — поддержка примитивов композиции
      val effect = when (pattern) {
        HapticPattern.TICK -> createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
        HapticPattern.CLICK -> createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
        HapticPattern.HEAVY_CLICK -> createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_HEAVY_CLICK, 1.0f)
        HapticPattern.DOUBLE_CLICK -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 60)
            .compose()
        }
        HapticPattern.TOGGLE_ON -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 40)
            .compose()
        }
        HapticPattern.TOGGLE_OFF -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 40)
            .compose()
        }
        HapticPattern.SUCCESS -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.9f, 50)
            .compose()
        }
        HapticPattern.WARNING -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.8f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 60)
            .compose()
        }
        HapticPattern.ERROR -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_HEAVY_CLICK, 1.0f, 70)
            .compose()
        }
      }

      if (effect != null) {
        vib.vibrate(effect)
        return
      }
    }

    // Fallback для Android 10 (API 29)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val predefined = when (pattern) {
        HapticPattern.TICK -> VibrationEffect.EFFECT_TICK
        HapticPattern.CLICK, HapticPattern.TOGGLE_ON, HapticPattern.TOGGLE_OFF -> VibrationEffect.EFFECT_CLICK
        HapticPattern.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
        HapticPattern.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
        else -> VibrationEffect.EFFECT_CLICK
      }
      vib.vibrate(VibrationEffect.createPredefined(predefined))
      return
    }

    // Fallback для старых версий (API 26+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val duration = when (pattern) {
        HapticPattern.TICK -> 10L
        HapticPattern.CLICK -> 20L
        HapticPattern.HEAVY_CLICK -> 45L
        HapticPattern.DOUBLE_CLICK -> 35L
        HapticPattern.SUCCESS -> 60L
        HapticPattern.ERROR -> 100L
        else -> 20L
      }
      vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
  private fun createPrimitive(vib: Vibrator, primitiveId: Int, scale: Float): VibrationEffect? {
    return if (vib.areAllPrimitivesSupported(primitiveId)) {
      VibrationEffect.startComposition().addPrimitive(primitiveId, scale).compose()
    } else {
      null
    }
  }
}
