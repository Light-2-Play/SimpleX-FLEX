package chat.simplex.common.views.usersettings

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import dev.icerock.moko.resources.compose.painterResource
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.common.platform.UsbPanicDetector
import chat.simplex.common.views.helpers.SectionView
import chat.simplex.res.MR

@Composable
fun FlexSettingsSection() {
  var usbPanicEnabled by remember { mutableStateOf(UsbPanicDetector.isEnabled()) }
  var advancedHapticsEnabled by remember { mutableStateOf(RichHaptics.isEnabled()) }

  SectionView("FLEX Settings") {
    // 1. Тумблер USB Panic
    PreferenceToggleWithIcon(
      text = "USB Panic",
      icon = painterResource(MR.images.ic_security),
      iconColor = if (usbPanicEnabled) Color.Red else Color.Gray,
      checked = usbPanicEnabled,
      onChange = { enabled ->
        usbPanicEnabled = enabled
        UsbPanicDetector.setEnabled(enabled)
        if (advancedHapticsEnabled) {
          RichHaptics.perform(if (enabled) HapticPattern.WARNING else HapticPattern.TOGGLE_OFF)
        }
      }
    )

    // 2. Тумблер Advanced Haptics
    PreferenceToggleWithIcon(
      text = "Advanced Haptics",
      icon = painterResource(MR.images.ic_bolt),
      iconColor = if (advancedHapticsEnabled) Color(0xFFFFA000) else Color.Gray,
      checked = advancedHapticsEnabled,
      onChange = { enabled ->
        advancedHapticsEnabled = enabled
        RichHaptics.setEnabled(enabled)
        if (enabled) {
          RichHaptics.perform(HapticPattern.HEAVY_CLICK)
        }
      }
    )
  }
}
