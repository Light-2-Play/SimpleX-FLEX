package chat.simplex.common.views.helpers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.platform.RichHaptics
import chat.simplex.common.platform.HapticPattern

// 1. ОПРЕДЕЛИТЕЛЬ (ПЕРЕКЛЮЧАТЕЛЬ СТИЛЕЙ)
@Composable
fun ToggleableQRCodeView(
    matrix: Array<BooleanArray>?, // Передайте сюда двумерный массив QR из генератора
    classicBitmap: ImageBitmap?,  // Передайте сюда обычный битмап (BoofCV) для фолбэка
    modifier: Modifier = Modifier
) {
    // Состояние переключателя: по умолчанию включен современный стиль
    var isModernStyle by remember { mutableStateOf(true) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        // UI Переключателя ("пилюля" в стиле Material 3)
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colors.primary.copy(alpha = 0.1f),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                val modernBg = if (isModernStyle) MaterialTheme.colors.primary else Color.Transparent
                val modernTextColor = if (isModernStyle) MaterialTheme.colors.onPrimary else MaterialTheme.colors.primary
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(modernBg)
                        .clickable {
                            if (!isModernStyle) {
                                RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
                                isModernStyle = true
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Modern (M3)", color = modernTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                val classicBg = if (!isModernStyle) MaterialTheme.colors.primary else Color.Transparent
                val classicTextColor = if (!isModernStyle) MaterialTheme.colors.onPrimary else MaterialTheme.colors.primary
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(classicBg)
                        .clickable {
                            if (isModernStyle) {
                                RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
                                isModernStyle = false
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Classic", color = classicTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Отрисовка самого QR-кода на белой подложке (обязательно для сканеров)
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(Color.White, RoundedCornerShape(24.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isModernStyle && matrix != null) {
                Material3ExpressiveQRCode(
                    matrix = matrix,
                    modifier = Modifier.fillMaxSize(),
                    qrColor = Color.Black // QR код всегда чёрный для максимального контраста
                )
            } else if (!isModernStyle && classicBitmap != null) {
                Image(
                    bitmap = classicBitmap,
                    contentDescription = "Classic QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                androidx.compose.material.CircularProgressIndicator(color = MaterialTheme.colors.primary)
            }
        }
    }
}

// 2. ОТРИСОВКА В СТИЛЕ MATERIAL 3 EXPRESSIVE
@Composable
fun Material3ExpressiveQRCode(
    matrix: Array<BooleanArray>,
    modifier: Modifier = Modifier,
    qrColor: Color = Color.Black
) {
    val size = matrix.size

    Canvas(modifier = modifier.aspectRatio(1f).padding(4.dp)) {
        val moduleSize = this.size.width / size

        for (x in 0 until size) {
            for (y in 0 until size) {
                if (matrix[x][y]) {
                    // Строгая изоляция 3-х угловых маркеров (Finder Patterns: зона 7x7)
                    val isTopLeftMarker = x < 7 && y < 7
                    val isTopRightMarker = x >= size - 7 && y < 7
                    val isBottomLeftMarker = x < 7 && y >= size - 7

                    val isFinderPattern = isTopLeftMarker || isTopRightMarker || isBottomLeftMarker

                    if (isFinderPattern) {
                        // Для сканера: Угловые маркеры рисуем строгими квадратами со слабым скруглением (20%)
                        drawRoundRect(
                            color = qrColor,
                            topLeft = Offset(x * moduleSize, y * moduleSize),
                            size = Size(moduleSize + 0.5f, moduleSize + 0.5f), // +0.5f закрывает возможные щели
                            cornerRadius = CornerRadius(moduleSize * 0.2f) 
                        )
                    } else {
                        // Дизайн M3: Информационные модули рисуем в виде "пилюль" (Squircle) со 100% скруглением
                        drawRoundRect(
                            color = qrColor,
                            topLeft = Offset(x * moduleSize + moduleSize * 0.1f, y * moduleSize + moduleSize * 0.1f),
                            size = Size(moduleSize * 0.8f, moduleSize * 0.8f),
                            cornerRadius = CornerRadius(moduleSize * 0.5f) 
                        )
                    }
                }
            }
        }
    }
}
