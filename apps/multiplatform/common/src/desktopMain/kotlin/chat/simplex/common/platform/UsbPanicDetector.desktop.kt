package chat.simplex.common.platform

actual object UsbPanicDetector {
  private var enabled = false

  actual fun isEnabled(): Boolean = enabled
  actual fun setEnabled(enabled: Boolean) {
    this.enabled = enabled
  }
}
