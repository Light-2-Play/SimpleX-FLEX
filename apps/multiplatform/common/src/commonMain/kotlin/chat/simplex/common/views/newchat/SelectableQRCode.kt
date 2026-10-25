package chat.simplex.common.views.newchat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import boofcv.alg.fiducial.qrcode.QrCode
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics

@Composable
fun SelectableQRCode(
  content: String,
  size: Dp = 250.dp,
  tintColor: Color = Color(0xFF062D56),
  withLogo: Boolean = false,
  errorLevel: QrCode.ErrorLevel = QrCode.ErrorLevel.M,
  modifier: Modifier = Modifier
) {
  // Для Verify по умолчанию рекомендуется Classic, чтобы гарантировать мгновенный захват камерой
  var qrStyle by rememberSaveable { mutableStateOf(QrDesignStyle.CLASSIC) }

  val classicImage = remember(content, errorLevel, tintColor, withLogo) {
    qrCodeBitmap(content, 1024, errorLevel)
      .replaceColor(Color.Black.toArgb(), tintColor.toArgb())
      .let { if (withLogo) it.addLogo(0.18f) else it }
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier.fillMaxWidth()
  ) {
    // Белая карточка с отступом (Quiet Zone), критически важная для сканера
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = Color.White,
      elevation = 3.dp,
      modifier = Modifier
        .size(size)
        .clip(RoundedCornerShape(24.dp))
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(14.dp), // Гарантированная граница 4 модуля для камеры
        contentAlignment = Alignment.Center
      ) {
        if (qrStyle == QrDesignStyle.MODERN) {
          Material3ExpressiveQRCode(
            content = content,
            size = size - 28.dp,
            primaryColor = Color(0xFF191C1E),
            containerColor = Color.White
          )
        } else {
          Image(
            bitmap = classicImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }

    Spacer(Modifier.height(12.dp))

    // Переключатель Modern / Classic
    Surface(
      shape = RoundedCornerShape(percent = 50),
      color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
      border = BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.22f))
    ) {
      Row(
        modifier = Modifier.padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        QrStyleToggleChip(
          text = "Modern",
          selected = qrStyle == QrDesignStyle.MODERN,
          onClick = {
            if (qrStyle != QrDesignStyle.MODERN) {
              RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
              qrStyle = QrDesignStyle.MODERN
            }
          }
        )

        QrStyleToggleChip(
          text = "Classic",
          selected = qrStyle == QrDesignStyle.CLASSIC,
          onClick = {
            if (qrStyle != QrDesignStyle.CLASSIC) {
              RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
              qrStyle = QrDesignStyle.CLASSIC
            }
          }
        )
      }
    }
  }
}

@Composable
private fun QrStyleToggleChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit
) {
  val primaryColor = MaterialTheme.colors.primary
  val onSurfaceColor = MaterialTheme.colors.onSurface

  Surface(
    shape = CircleShape,
    color = if (selected) primaryColor else Color.Transparent,
    modifier = Modifier
      .clip(CircleShape)
      .clickable(onClick = onClick)
  ) {
    Text(
      text = text,
      fontSize = 12.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = if (selected) MaterialTheme.colors.onPrimary else onSurfaceColor.copy(alpha = 0.7f),
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
    )
  }
}
