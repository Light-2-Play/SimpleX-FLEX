package chat.simplex.common.platform

import androidx.compose.ui.graphics.RenderEffect

actual class WaterGlassShaderHolder {
  actual val supportsRealBlur: Boolean = false
  actual val supportsWaterShader: Boolean = false

  actual fun buildRenderEffect(
    widthPx: Float,
    heightPx: Float,
    blurRadiusPx: Float,
    cornerRadiusPx: Float,
    timeSec: Float
  ): RenderEffect? = null
}
