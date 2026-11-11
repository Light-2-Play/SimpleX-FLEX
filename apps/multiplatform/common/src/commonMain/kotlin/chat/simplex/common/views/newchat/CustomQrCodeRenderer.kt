package chat.simplex.common.views.newchat

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

enum class QrDesignStyle {
  CLASSIC, // Оригинальный квадратный SimpleX
  MODERN   // Новый скругленный дизайн с сохранением читаемости
}

object CustomQrCodeRenderer {

  fun drawQr(
    drawScope: DrawScope,
    matrix: Array<BooleanArray>,
    size: Float,
    style: QrDesignStyle,
    foregroundColor: Color,
    backgroundColor: Color
  ) {
    val matrixSize = matrix.size
    if (matrixSize == 0) return

    val moduleSize = size / matrixSize

    with(drawScope) {
      // 1. Заливка подложки (Quiet Zone)
      drawRect(color = backgroundColor, size = Size(size, size))

      for (row in 0 until matrixSize) {
        for (col in 0 until matrixSize) {
          if (!matrix[row][col]) continue

          val x = col * moduleSize
          val y = row * moduleSize

          if (style == QrDesignStyle.CLASSIC) {
            // Классический квадратный дизайн SimpleX
            drawRect(
              color = foregroundColor,
              topLeft = Offset(x, y),
              size = Size(moduleSize + 0.5f, moduleSize + 0.5f)
            )
          } else {
            // Новый кастомный дизайн
            val isFinder = isFinderPattern(row, col, matrixSize)

            if (isFinder) {
              // Угловые маркеры сохраняют геометрию для мгновенного захвата камерой
              drawRoundRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(moduleSize, moduleSize),
                cornerRadius = CornerRadius(moduleSize * 0.25f, moduleSize * 0.25f)
              )
            } else {
              // Модули данных — выразительные скругленные точки
              drawRoundRect(
                color = foregroundColor,
                topLeft = Offset(x + moduleSize * 0.08f, y + moduleSize * 0.08f),
                size = Size(moduleSize * 0.84f, moduleSize * 0.84f),
                cornerRadius = CornerRadius(moduleSize * 0.38f, moduleSize * 0.38f)
              )
            }
          }
        }
      }
    }
  }

  // Проверка принадлежности к 3 ключевым поисковым квадратам (верх-лево, верх-право, низ-лево)
  private fun isFinderPattern(row: Int, col: Int, matrixSize: Int): Boolean {
    val inTopLeft = row < 7 && col < 7
    val inTopRight = row < 7 && col >= matrixSize - 7
    val inBottomLeft = row >= matrixSize - 7 && col < 7
    return inTopLeft || inTopRight || inBottomLeft
  }
}
