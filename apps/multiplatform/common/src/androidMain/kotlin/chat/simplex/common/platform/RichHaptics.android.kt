package chat.simplex.common.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

actual object RichHaptics {

  actual var isAdvancedHapticsEnabled: () -> Boolean = { true }

  private val context: Context? by lazy {
    try {
      val at = Class.forName("android.app.ActivityThread")
      at.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Exception) {
      null
    }
  }

  private val vibrator: Vibrator? by lazy {
    val ctx = context ?: return@lazy null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      manager?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
  }

  private fun isHapticFeedbackEnabled(): Boolean {
    val ctx = context ?: return true
    return try {
      Settings.System.getInt(
        ctx.contentResolver,
        Settings.System.HAPTIC_FEEDBACK_ENABLED,
        1
      ) != 0
    } catch (_: Exception) {
      true
    }
  }

  actual fun perform(pattern: HapticPattern) {
    if (!isAdvancedHapticsEnabled()) return
    val vib = vibrator ?: return
    if (!vib.hasVibrator() || !isHapticFeedbackEnabled()) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val effect = when (pattern) {
        HapticPattern.TICK -> createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
        HapticPattern.CLICK -> createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
        HapticPattern.HEAVY_CLICK -> {
          createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else null
        }
        HapticPattern.DOUBLE_CLICK -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 60)
            .compose()
        }
        HapticPattern.TOGGLE_ON -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 35)
            .compose()
        }
        HapticPattern.TOGGLE_OFF -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 35)
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
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 70)
            .compose()
        }
        HapticPattern.SEND_MESSAGE -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 30)
            .compose()
        }
        HapticPattern.RECORD_START -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f, 0)
            .compose()
        }
        HapticPattern.RECORD_STOP -> {
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 40)
            .compose()
        }
        HapticPattern.SWIPE_ACTION -> {
          createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
        }
        HapticPattern.SELECTION_CHANGE -> {
          createPrimitive(vib, VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
        }
      }

      if (effect != null) {
        vib.vibrate(effect)
        return
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val predefined = when (pattern) {
        HapticPattern.TICK, HapticPattern.SELECTION_CHANGE, HapticPattern.SWIPE_ACTION -> VibrationEffect.EFFECT_TICK
        HapticPattern.CLICK, HapticPattern.TOGGLE_ON, HapticPattern.TOGGLE_OFF, HapticPattern.RECORD_STOP -> VibrationEffect.EFFECT_CLICK
        HapticPattern.HEAVY_CLICK, HapticPattern.SEND_MESSAGE, HapticPattern.RECORD_START -> VibrationEffect.EFFECT_HEAVY_CLICK
        HapticPattern.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
        else -> VibrationEffect.EFFECT_CLICK
      }
      vib.vibrate(VibrationEffect.createPredefined(predefined))
      return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val duration = when (pattern) {
        HapticPattern.TICK, HapticPattern.SELECTION_CHANGE -> 8L
        HapticPattern.SWIPE_ACTION, HapticPattern.CLICK -> 18L
        HapticPattern.SEND_MESSAGE, HapticPattern.RECORD_STOP -> 28L
        HapticPattern.HEAVY_CLICK, HapticPattern.RECORD_START -> 45L
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
