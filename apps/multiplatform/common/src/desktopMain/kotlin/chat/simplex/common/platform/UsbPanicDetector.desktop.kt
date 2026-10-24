package chat.simplex.common.platform

actual object UsbPanicDetector {
  actual var isEnabled: () -> Boolean = { false }
}
