import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

@Composable
fun Material3ExpressiveQRCode(
    matrix: Array<BooleanArray>, // Двумерный массив из вашего генератора (BoofCV / Zxing)
    modifier: Modifier = Modifier,
    qrColor: Color = MaterialTheme.colors.onSurface
) {
    val size = matrix.size

    Canvas(modifier = modifier.aspectRatio(1f).padding(8.dp)) {
        val moduleSize = this.size.width / size

        for (x in 0 until size) {
            for (y in 0 until size) {
                if (matrix[x][y]) {
                    // Проверяем, принадлежит ли пиксель к 3-м угловым квадратам (Finder Patterns)
                    val isTopLeftMarker = x < 7 && y < 7
                    val isTopRightMarker = x >= size - 7 && y < 7
                    val isBottomLeftMarker = x < 7 && y >= size - 7

                    val isFinderPattern = isTopLeftMarker || isTopRightMarker || isBottomLeftMarker

                    if (isFinderPattern) {
                        // Для сканера: Угловые маркеры рисуем квадратами с МИНИМАЛЬНЫМ скруглением (20%)
                        drawRoundRect(
                            color = qrColor,
                            topLeft = Offset(x * moduleSize, y * moduleSize),
                            size = Size(moduleSize + 0.5f, moduleSize + 0.5f), // +0.5f закрывает щели
                            cornerRadius = CornerRadius(moduleSize * 0.2f) 
                        )
                    } else {
                        // Для красоты (M3): Модули данных делаем сильными сквирклами/пилюлями (сферами 100%)
                        drawRoundRect(
                            color = qrColor,
                            topLeft = Offset(x * moduleSize + moduleSize * 0.1f, y * moduleSize + moduleSize * 0.1f),
                            size = Size(moduleSize * 0.8f, moduleSize * 0.8f),
                            cornerRadius = CornerRadius(moduleSize * 0.5f) // Делает их красивыми кружочками
                        )
                    }
                }
            }
        }
    }
}
