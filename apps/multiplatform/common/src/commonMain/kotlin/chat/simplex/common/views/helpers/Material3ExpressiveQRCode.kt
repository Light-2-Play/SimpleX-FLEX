package chat.simplex.common.views.helpers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import boofcv.alg.fiducial.qrcode.QrCode
import boofcv.alg.fiducial.qrcode.QrCodeEncoder
import boofcv.alg.fiducial.qrcode.QrCodeGeneratorImage

@Composable
fun Material3ExpressiveQRCode(
  content: String,
  modifier: Modifier = Modifier,
  size: Dp = 250.dp,
  primaryColor: Color = Color(0xFF191C1E),
  backgroundColor: Color = Color.White,
  containerColor: Color = Color.White
) {
  // Генерация битовой матрицы через встроенный в проект BoofCV
  val matrixData = remember(content) {
    if (content.isBlank()) return@remember null
    try {
      val qrCode = QrCodeEncoder().addAutomatic(content).setError(QrCode.ErrorLevel.M).fixate()
      val numModules = QrCode.totalModules(qrCode.version)
      val renderer = QrCodeGeneratorImage(1)
      renderer.borderModule = 0
      renderer.render(qrCode)
      val gray = renderer.gray

      val matrix = Array(numModules) { y ->
        BooleanArray(numModules) { x ->
          gray.get(x, y) < 128 // 0 — черный пиксель модуля, 255 — белый фон
        }
      }
      Pair(numModules, matrix)
    } catch (_: Exception) {
      null
    }
  }

  if (matrixData == null) {
    Box(modifier = modifier.size(size))
    return
  }

  val (numModules, matrix) = matrixData

  // Expressive-контейнер со скруглением 28.dp и безопасной зоной отступов (Quiet Zone)
  Box(
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(28.dp))
      .background(containerColor)
      .padding(16.dp),
    contentAlignment = Alignment.Center
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .aspectRatio(1f)
    ) {
      val cellSize = this.size.width / numModules.toFloat()

      // Проверка принадлежности к трем угловым поисковым маркерам (7x7)
      fun isFinderPattern(x: Int, y: Int): Boolean {
        val inTopLeft = (x in 0..6) && (y in 0..6)
        val inTopRight = (x in (numModules - 7) until numModules) && (y in 0..6)
        val inBottomLeft = (x in 0..6) && (y in (numModules - 7) until numModules)
        return inTopLeft || inTopRight || inBottomLeft
      }

      // 1. Отрисовка тела данных (мягкие скругленные сквирклы)
      for (y in 0 until numModules) {
        for (x in 0 until numModules) {
          if (matrix[y][x] && !isFinderPattern(x, y)) {
            val left = x * cellSize
            val top = y * cellSize
            val dotPadding = cellSize * 0.08f
            val dotSize = cellSize - (dotPadding * 2f)

            drawRoundRect(
              color = primaryColor,
              topLeft = Offset(left + dotPadding, top + dotPadding),
              size = Size(dotSize, dotSize),
              cornerRadius = CornerRadius(dotSize * 0.38f, dotSize * 0.38f)
            )
          }
        }
      }

      // 2. Отрисовка маркеров Finder Pattern в стиле Material 3 Expressive
      fun drawExpressiveFinder(startX: Float, startY: Float) {
        val finderSize = 7f * cellSize
        val strokeWidth = cellSize * 1.0f

        // Внешнее кольцо со скругленными углами
        drawRoundRect(
          color = primaryColor,
          topLeft = Offset(startX + strokeWidth / 2f, startY + strokeWidth / 2f),
          size = Size(finderSize - strokeWidth, finderSize - strokeWidth),
          cornerRadius = CornerRadius(cellSize * 1.7f, cellSize * 1.7f),
          style = Stroke(width = strokeWidth)
        )

        // Внутреннее скругленное ядро (3x3 модуля)
        val coreOffset = 2f * cellSize
        val coreSize = 3f * cellSize
        drawRoundRect(
          color = primaryColor,
          topLeft = Offset(startX + coreOffset, startY + coreOffset),
          size = Size(coreSize, coreSize),
          cornerRadius = CornerRadius(cellSize * 0.85f, cellSize * 0.85f)
        )
      }

      // Отрисовка трех угловых маркеров
      drawExpressiveFinder(0f, 0f)                                  // Верхний левый
      drawExpressiveFinder((numModules - 7) * cellSize, 0f)         // Верхний правый
      drawExpressiveFinder(0f, (numModules - 7) * cellSize)         // Нижний левый
    }
  }
}
