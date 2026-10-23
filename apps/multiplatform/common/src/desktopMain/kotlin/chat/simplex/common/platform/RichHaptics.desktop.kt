package chat.simplex.common.platform

actual object RichHaptics {
  actual fun perform(pattern: HapticPattern) {
    // На десктопе тактильная отдача отсутствует
  }
}
