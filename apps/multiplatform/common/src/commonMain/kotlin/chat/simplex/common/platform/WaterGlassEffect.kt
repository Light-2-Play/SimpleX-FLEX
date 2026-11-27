package chat.simplex.common.platform

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

expect class WaterGlassShaderHolder() {
  val supportsRealBlur: Boolean
  val supportsWaterShader: Boolean
  fun buildRenderEffect(
    widthPx: Float,
    heightPx: Float,
    blurRadiusPx: Float,
    cornerRadiusPx: Float,
    timeSec: Float
  ): RenderEffect?
}

@Stable
class ChatBackdropState(val layer: GraphicsLayer) {
  var rootOffsetInWindow by mutableStateOf(Offset.Zero)
}

val LocalChatBackdrop = staticCompositionLocalOf<ChatBackdropState?> { null }

@Composable
fun rememberChatBackdropState(): ChatBackdropState {
  val layer = rememberGraphicsLayer()
  return remember(layer) { ChatBackdropState(layer) }
}

/**
 * Вешается на контейнер с обоями и списком сообщений (LazyColumn),
 * чтобы плавающие элементы поверх него могли размывать и преломлять реальный контент чата.
 */
fun Modifier.captureChatBackdrop(state: ChatBackdropState?): Modifier {
  if (state == null) return this
  return this
    .onGloballyPositioned { coords ->
      state.rootOffsetInWindow = coords.positionInWindow()
    }
    .drawWithContent {
      if (size.width > 0f && size.height > 0f) {
        state.layer.record {
          this@drawWithContent.drawContent()
        }
        drawLayer(state.layer)
      } else {
        drawContent()
      }
    }
}

/**
 * Плавающий контейнер с эффектом воды и живым размытием содержимого чата внутри элемента.
 */
@Composable
fun LiquidGlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(24.dp),
  cornerRadius: Dp = 24.dp,
  blurRadius: Dp = 18.dp,
  tintColor: Color? = null,
  contentColor: Color = MaterialTheme.colors.onSurface,
  contentAlignment: Alignment = Alignment.TopStart,
  content: @Composable BoxScope.() -> Unit
) {
  val backdropState = LocalChatBackdrop.current
  val shaderHolder = remember { WaterGlassShaderHolder() }
  val density = LocalDensity.current
  val isLight = MaterialTheme.colors.isLight
  val primary = MaterialTheme.colors.primary

  var elementOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
  var elementSize by remember { mutableStateOf(IntSize.Zero) }

  // Плавный цикл движения воды для AGSL-шейдера (на Android 13+)
  val timeSec = if (shaderHolder.supportsWaterShader) {
    val infiniteTransition = rememberInfiniteTransition(label = "WaterGlassTime")
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 62.83185f, // 20 * PI для бесшовной синусоиды
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 36_000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
      ),
      label = "WaterRipplePhase"
    ).value
  } else {
    0f
  }

  val blurRadiusPx = with(density) { blurRadius.toPx() }
  val cornerRadiusPx = with(density) { cornerRadius.toPx() }

  val hasLiveBackdrop = backdropState != null && shaderHolder.supportsRealBlur

  val resolvedTint = tintColor ?: if (isLight) {
    lerp(Color.White, primary, 0.08f).copy(alpha = if (hasLiveBackdrop) 0.42f else 0.86f)
  } else {
    lerp(MaterialTheme.colors.surface, primary, 0.14f).copy(alpha = if (hasLiveBackdrop) 0.44f else 0.88f)
  }

  val topSheenBrush = remember(isLight, primary) {
    Brush.verticalGradient(
      colors = if (isLight) {
        listOf(
          Color.White.copy(alpha = 0.42f),
          Color.White.copy(alpha = 0.06f),
          primary.copy(alpha = 0.06f)
        )
      } else {
        listOf(
          Color.White.copy(alpha = 0.16f),
          Color.Transparent,
          primary.copy(alpha = 0.10f)
        )
      }
    )
  }

  val rimBorderBrush = remember(isLight, primary) {
    Brush.linearGradient(
      colors = if (isLight) {
        listOf(
          Color.White.copy(alpha = 0.78f),
          primary.copy(alpha = 0.18f),
          Color.White.copy(alpha = 0.45f)
        )
      } else {
        listOf(
          Color.White.copy(alpha = 0.34f),
          primary.copy(alpha = 0.22f),
          Color.White.copy(alpha = 0.14f)
        )
      }
    )
  }

  Box(
    modifier = modifier
      .clip(shape)
      .onGloballyPositioned { coords ->
        elementOffsetInWindow = coords.positionInWindow()
        elementSize = coords.size
      },
    contentAlignment = contentAlignment
  ) {
    // 1. Живой слой размытия и водного преломления фона (строго внутри границ элемента)
    if (backdropState != null && shaderHolder.supportsRealBlur) {
      val renderEffect = if (elementSize.width > 1 && elementSize.height > 1) {
        shaderHolder.buildRenderEffect(
          widthPx = elementSize.width.toFloat(),
          heightPx = elementSize.height.toFloat(),
          blurRadiusPx = blurRadiusPx,
          cornerRadiusPx = cornerRadiusPx,
          timeSec = timeSec
        )
      } else {
        null
      }

      Box(
        modifier = Modifier
          .matchParentSize()
          .graphicsLayer {
            this.renderEffect = renderEffect
            clip = true
          }
          .drawBehind {
            val layer = backdropState.layer
            if (layer.size.width > 0 && layer.size.height > 0) {
              val dx = elementOffsetInWindow.x - backdropState.rootOffsetInWindow.x
              val dy = elementOffsetInWindow.y - backdropState.rootOffsetInWindow.y
              translate(left = -dx, top = -dy) {
                drawLayer(layer)
              }
            }
          }
      )
    }

    // 2. Водная тонировка, внутренний глянцевый блик и световая кромка
    Box(
      modifier = Modifier
        .matchParentSize()
        .background(resolvedTint)
        .background(topSheenBrush)
        .border(width = 1.dp, brush = rimBorderBrush, shape = shape)
    )

    // 3. Чёткий контент (текст, иконки, кнопки) поверх стекла
    CompositionLocalProvider(LocalContentColor provides contentColor) {
      content()
    }
  }
}
