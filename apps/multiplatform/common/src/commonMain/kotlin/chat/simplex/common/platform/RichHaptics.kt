package chat.simplex.common.platform

enum class HapticPattern {
  TICK,
  CLICK,
  HEAVY_CLICK,
  DOUBLE_CLICK,
  TOGGLE_ON,
  TOGGLE_OFF,
  SUCCESS,
  WARNING,
  ERROR,
  SEND_MESSAGE,
  RECORD_START,
  RECORD_STOP,
  SWIPE_ACTION,
  SELECTION_CHANGE
}

expect object RichHaptics {
  fun isEnabled(): Boolean
  fun setEnabled(enabled: Boolean)
  fun perform(pattern: HapticPattern)
}

expect object RichHaptics {
  var isAdvancedHapticsEnabled: () -> Boolean
  
  fun isEnabled(): Boolean
  fun setEnabled(enabled: Boolean)
  fun perform(pattern: HapticPattern)
}
