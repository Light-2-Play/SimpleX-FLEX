package chat.simplex.common.platform

enum class HapticPattern {
    CLICK, HEAVY_CLICK, DOUBLE_CLICK, TICK, SELECTION_CHANGE,
    TOGGLE_ON, TOGGLE_OFF, SEND_MESSAGE, RECORD_START, RECORD_STOP,
    SUCCESS, ERROR, WARNING, SWIPE_ACTION
}

expect object RichHaptics {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    
    // Новые функции для USB Panic
    fun isUsbPanicEnabled(): Boolean
    fun setUsbPanicEnabled(enabled: Boolean)
    
    fun perform(pattern: HapticPattern)
}
