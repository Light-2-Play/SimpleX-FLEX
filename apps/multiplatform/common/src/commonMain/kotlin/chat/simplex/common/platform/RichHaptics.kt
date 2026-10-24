package chat.simplex.common.platform

enum class HapticPattern {
  CLICK,
  HEAVY_CLICK,
  DOUBLE_CLICK,
  TICK,
  WARNING,
  TOGGLE_OFF,
  TOGGLE_ON,
  SUCCESS,
  ERROR,
  SEND_MESSAGE,
  RECORD_START,
  RECORD_STOP,
  SWIPE_ACTION
}

expect object RichHaptics {
  fun isEnabled(): Boolean
  fun setEnabled(enabled: Boolean)
  fun perform(pattern: HapticPattern)
}
