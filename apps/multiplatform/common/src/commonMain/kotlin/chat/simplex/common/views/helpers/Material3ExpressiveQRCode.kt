package chat.simplex.common.views.helpers

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import boofcv.alg.fiducial.qrcode.QrCode
import chat.simplex.common.views.newchat.qrCodeBitmap
import chat.simplex.common.views.newchat.replaceColor

@Composable
fun Material3ExpressiveQRCode(
  content: String,
  size: Dp,
  primaryColor: Color = Color(0xFF191C1E),
  containerColor: Color = Color.White,
  modifier: Modifier = Modifier
) {
  val qrImage = remember(content, primaryColor) {
    try {
      qrCodeBitmap(content, 1024, QrCode.ErrorLevel.M)
        .replaceColor(Color.Black.toArgb(), primaryColor.toArgb())
    } catch (_: Exception) {
      try {
        qrCodeBitmap(content, 1024, QrCode.ErrorLevel.L)
          .replaceColor(Color.Black.toArgb(), primaryColor.toArgb())
      } catch (_: Exception) {
        null
      }
    }
  }

  Surface(
    shape = RoundedCornerShape(28.dp),
    color = containerColor,
    elevation = 2.dp,
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(28.dp))
  ) {
    if (qrImage != null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp), // Гарантированная белая зона (Quiet Zone) для сканера
        contentAlignment = Alignment.Center
      ) {
        Image(
          bitmap = qrImage,
          contentDescription = null,
          modifier = Modifier.fillMaxSize()
        )
      }
    }
  }
}
