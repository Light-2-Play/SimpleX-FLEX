package chat.simplex.common.views.usersettings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.painterResource
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.common.platform.UsbPanicDetector
import chat.simplex.res.MR

@Composable
fun FlexSettingsSection() {
  var usbPanicEnabled by remember { mutableStateOf(UsbPanicDetector.isEnabled()) }
  var advancedHapticsEnabled by remember { mutableStateOf(RichHaptics.isEnabled()) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp)
  ) {
    Text(
      text = "FLEX SETTINGS",
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colors.primary,
      modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )

    Card(
      shape = RoundedCornerShape(16.dp),
      backgroundColor = MaterialTheme.colors.surface,
      elevation = 0.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(vertical = 4.dp)) {
        // 1. USB Panic
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Icon(
              painter = painterResource(MR.images.ic_security),
              contentDescription = null,
              tint = if (usbPanicEnabled) Color.Red else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
              Text(
                text = "USB Panic",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onSurface
              )
              Text(
                text = "Purge and kill app on USB connection",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
              )
            }
          }
          Switch(
            checked = usbPanicEnabled,
            onCheckedChange = { enabled ->
              usbPanicEnabled = enabled
              UsbPanicDetector.setEnabled(enabled)
              if (advancedHapticsEnabled) {
                RichHaptics.perform(if (enabled) HapticPattern.WARNING else HapticPattern.TOGGLE_OFF)
              }
            },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.Red,
              checkedTrackColor = Color.Red.copy(alpha = 0.5f)
            )
          )
        }

        Divider(
          modifier = Modifier.padding(horizontal = 16.dp),
          color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f)
        )

        // 2. Advanced Haptics
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Icon(
              painter = painterResource(MR.images.ic_notifications),
              contentDescription = null,
              tint = if (advancedHapticsEnabled) Color(0xFFFFA000) else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
              Text(
                text = "Advanced Haptics",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onSurface
              )
              Text(
                text = "Vibrations and tactile feedback",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
              )
            }
          }
          Switch(
            checked = advancedHapticsEnabled,
            onCheckedChange = { enabled ->
              advancedHapticsEnabled = enabled
              RichHaptics.setEnabled(enabled)
              if (enabled) {
                RichHaptics.perform(HapticPattern.HEAVY_CLICK)
              }
            },
            colors = SwitchDefaults.colors(
              checkedThumbColor = MaterialTheme.colors.primary,
              checkedTrackColor = MaterialTheme.colors.primary.copy(alpha = 0.5f)
            )
          )
        }
      }
    }
  }
}
