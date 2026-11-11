package chat.simplex.common.platform

actual object RichHaptics {
  private var enabled: Boolean = true

  actual fun isEnabled(): Boolean = enabled
  actual fun setEnabled(enabled: Boolean) {
    this.enabled = enabled
  }
  actual fun perform(pattern: HapticPattern) {}
}
