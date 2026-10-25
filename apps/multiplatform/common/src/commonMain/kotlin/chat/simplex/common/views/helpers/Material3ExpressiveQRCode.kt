package chat.simplex.common.views.helpers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun Material3ExpressiveQRCode(
  content: String,
  modifier: Modifier = Modifier,
  size: Dp = 260.dp,
  primaryColor: Color = MaterialTheme.colors.primary,
  backgroundColor: Color = MaterialTheme.colors.surface,
  containerColor: Color = MaterialTheme.colors.background
) {
  val bitMatrix = remember(content) {
    if (content.isEmpty()) null
    else {
      val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
        EncodeHintType.MARGIN to 0
      )
      try {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
      } catch (_: Exception) {
        null
      }
    }
  }

  if (bitMatrix == null) return

  val matrixWidth = bitMatrix.width
  val matrixHeight = bitMatrix.height

  Box(
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(32.dp))
      .background(containerColor)
      .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
      .padding(20.dp),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cellSize = this.size.width / matrixWidth

      fun isFinderPattern(x: Int, y: Int): Boolean {
        val inTopLeft = (x in 0..6) && (y in 0..6)
        val inTopRight = (x in (matrixWidth - 7) until matrixWidth) && (y in 0..6)
        val inBottomLeft = (x in 0..6) && (y in (matrixHeight - 7) until matrixHeight)
        return inTopLeft || inTopRight || inBottomLeft
      }

      // 1. Точки данных (Data Dots) со скруглением
      for (x in 0 until matrixWidth) {
        for (y in 0 until matrixHeight) {
          if (bitMatrix.get(x, y) && !isFinderPattern(x, y)) {
            val left = x * cellSize
            val top = y * cellSize
            val dotPadding = cellSize * 0.08f
            val dotSize = cellSize - (dotPadding * 2)

            drawRoundRect(
              color = primaryColor,
              topLeft = Offset(left + dotPadding, top + dotPadding),
              size = Size(dotSize, dotSize),
              cornerRadius = CornerRadius(dotSize * 0.38f, dotSize * 0.38f)
            )
          }
        }
      }

      // 2. Угловые маркеры M3 Expressive (вложенные кольца со скруглением)
      fun drawExpressiveFinder(startX: Float, startY: Float) {
        val finderSize = 7 * cellSize
        val strokeWidth = cellSize * 1.05f

        drawRoundRect(
          color = primaryColor,
          topLeft = Offset(startX + strokeWidth / 2, startY + strokeWidth / 2),
          size = Size(finderSize - strokeWidth, finderSize - strokeWidth),
          cornerRadius = CornerRadius(cellSize * 1.8f, cellSize * 1.8f),
          style = Stroke(width = strokeWidth)
        )

        val coreOffset = 2 * cellSize
        val coreSize = 3 * cellSize
        drawRoundRect(
          color = primaryColor,
          topLeft = Offset(startX + coreOffset, startY + coreOffset),
          size = Size(coreSize, coreSize),
          cornerRadius = CornerRadius(cellSize * 0.9f, cellSize * 0.9f)
        )
      }

      drawExpressiveFinder(0f, 0f)
      drawExpressiveFinder((matrixWidth - 7) * cellSize, 0f)
      drawExpressiveFinder(0f, (matrixHeight - 7) * cellSize)
    }
  }
}
