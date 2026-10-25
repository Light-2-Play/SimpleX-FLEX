package chat.simplex.common.views.helpers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import boofcv.alg.fiducial.qrcode.QrCode
import boofcv.alg.fiducial.qrcode.QrCodeEncoder

@Composable
fun Material3ExpressiveQRCode(
  content: String,
  size: Dp,
  primaryColor: Color = Color(0xFF191C1E),
  containerColor: Color = Color.White,
  modifier: Modifier = Modifier
) {
  val qrCode = remember(content) {
    try {
      QrCodeEncoder().addAutomatic(content).setError(QrCode.ErrorLevel.M).fixate()
    } catch (_: Exception) {
      try {
        QrCodeEncoder().addAutomatic(content).setError(QrCode.ErrorLevel.L).fixate()
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
    if (qrCode == null) return@Surface

    val numModules = QrCode.totalModules(qrCode.version)
    // Гарантированная Quiet Zone (4 модуля со всех сторон)
    val quietZone = 4
    val totalGrid = numModules + (quietZone * 2)

    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .padding(14.dp)
    ) {
      val canvasSize = this.size.minDimension
      val moduleSize = canvasSize / totalGrid
      val offsetQuiet = quietZone * moduleSize

      // 1. Отрисовка сетки данных БЕЗ зазоров (с мягким Expressive-скруглением)
      for (row in 0 until numModules) {
        for (col in 0 until numModules) {
          if (!qrCode.get(row, col)) continue

          val isFinder = (row < 7 && col < 7) ||
                         (row < 7 && col >= numModules - 7) ||
                         (row >= numModules - 7 && col < 7)

          if (isFinder) continue

          val x = offsetQuiet + (col * moduleSize)
          val y = offsetQuiet + (row * moduleSize)

          // Модули соединяются внахлест (+0.35f), исключая появление субпиксельных белых щелей
          drawRoundRect(
            color = primaryColor,
            topLeft = Offset(x, y),
            size = Size(moduleSize + 0.35f, moduleSize + 0.35f),
            cornerRadius = CornerRadius(moduleSize * 0.22f, moduleSize * 0.22f)
          )
        }
      }

      // 2. Отрисовка 3 поисковых маркеров со строгой геометрией 1:1:3:1:1 для мгновенного захвата BoofCV
      val finderOrigins = listOf(
        Offset(offsetQuiet, offsetQuiet),
        Offset(offsetQuiet + (numModules - 7) * moduleSize, offsetQuiet),
        Offset(offsetQuiet, offsetQuiet + (numModules - 7) * moduleSize)
      )

      val finderSize = moduleSize * 7f
      val finderCorner = CornerRadius(moduleSize * 0.55f, moduleSize * 0.55f)

      for (origin in finderOrigins) {
        // Внешний квадрат 7x7
        drawRoundRect(
          color = primaryColor,
          topLeft = origin,
          size = Size(finderSize, finderSize),
          cornerRadius = finderCorner
        )

        // Внутренний белый контур 5x5
        val innerWhiteOffset = origin + Offset(moduleSize, moduleSize)
        val innerWhiteSize = moduleSize * 5f
        drawRoundRect(
          color = containerColor,
          topLeft = innerWhiteOffset,
          size = Size(innerWhiteSize, innerWhiteSize),
          cornerRadius = CornerRadius(moduleSize * 0.38f, moduleSize * 0.38f)
        )

        // Центральный монолитный квадрат 3x3
        val centerBlackOffset = origin + Offset(moduleSize * 2f, moduleSize * 2f)
        val centerBlackSize = moduleSize * 3f
        drawRoundRect(
          color = primaryColor,
          topLeft = centerBlackOffset,
          size = Size(centerBlackSize, centerBlackSize),
          cornerRadius = CornerRadius(moduleSize * 0.25f, moduleSize * 0.25f)
        )
      }
    }
  }
}
