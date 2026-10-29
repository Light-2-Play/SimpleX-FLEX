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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import boofcv.alg.fiducial.qrcode.QrCode
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.common.platform.qrCodeBitmap

// 1. ОПРЕДЕЛИТЕЛЬ (ПЕРЕКЛЮЧАТЕЛЬ СТИЛЕЙ)
@Composable
fun ToggleableQRCodeView(
    matrix: Array<BooleanArray>?,
    classicBitmap: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    var isModernStyle by remember { mutableStateOf(true) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
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
                    qrColor = Color.Black
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
                    val isTopLeftMarker = x < 7 && y < 7
                    val isTopRightMarker = x >= size - 7 && y < 7
                    val isBottomLeftMarker = x < 7 && y >= size - 7

                    val isFinderPattern = isTopLeftMarker || isTopRightMarker || isBottomLeftMarker

                    if (isFinderPattern) {
                        drawRoundRect(
                            color = qrColor,
                            topLeft = Offset(x * moduleSize, y * moduleSize),
                            size = Size(moduleSize + 0.5f, moduleSize + 0.5f),
                            cornerRadius = CornerRadius(moduleSize * 0.2f) 
                        )
                    } else {
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

// 3. УНИВЕРСАЛЬНЫЙ КОМПОНЕНТ ДЛЯ ЛЮБЫХ ССЫЛОК (ПРОФИЛЬ / ИНВАЙТЫ)
@Composable
fun SmartLinkQRCode(
    uri: String,
    modifier: Modifier = Modifier
) {
    val classicQrBitmap = remember(uri) {
        qrCodeBitmap(uri, 1024, QrCode.ErrorLevel.M)
            .replaceColor(Color.Black.toArgb(), Color(0xFF191C1E).toArgb())
    }

    val qrMatrix = remember(uri) {
        try {
            val qrCode = boofcv.alg.fiducial.qrcode.QrCodeEncoder()
                .setError(QrCode.ErrorLevel.M)
                .addAutomatic(uri)
                .fixate()

            val renderer = boofcv.alg.fiducial.qrcode.QrCodeGeneratorImage(1)
            renderer.setBorderModule(0)
            renderer.render(qrCode)
            val grayImage = renderer.gray
            
            val size = grayImage.width
            Array(size) { y ->
                BooleanArray(size) { x ->
                    grayImage.get(x, y) < 128
                }
            }
        } catch (e: Exception) { null }
    }

    ToggleableQRCodeView(
        matrix = qrMatrix,
        classicBitmap = classicQrBitmap,
        modifier = modifier
    )
}
