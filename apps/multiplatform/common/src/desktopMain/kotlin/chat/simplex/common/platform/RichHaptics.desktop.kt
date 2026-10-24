package chat.simplex.common.platform

actual object RichHaptics {
  actual var isAdvancedHapticsEnabled: () -> Boolean = { false }

  actual fun perform(pattern: HapticPattern) {}
}
