package chat.simplex.common.platform

expect object UsbPanicDetector {
  var isEnabled: () -> Boolean
}
