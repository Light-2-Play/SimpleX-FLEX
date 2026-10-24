package chat.simplex.common.platform

expect object UsbPanicDetector {
  fun isEnabled(): Boolean
  fun setEnabled(enabled: Boolean)
  fun checkStartupUsbBlock(activity: Any): Boolean
}
