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
 * чтобы плавающие элементы поверх него могли преломлять реальный контент чата.
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
 * Плавающий контейнер с живым эффектом прозрачной воды и лёгким размытием внутри элемента.
 */
@Composable
fun LiquidGlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(24.dp),
  cornerRadius: Dp = 24.dp,
  blurRadius: Dp = 6.dp,
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

  // Бесшовный цикл живого движения воды (20 * PI)
  val timeSec = if (shaderHolder.supportsWaterShader) {
    val infiniteTransition = rememberInfiniteTransition(label = "WaterSurfaceTime")
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 62.831853f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 28_000, easing = LinearEasing),
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

  // Прозрачная водная тонировка вместо «молочного» матового стекла
  val resolvedTint = tintColor ?: if (isLight) {
    lerp(Color(0xFFEAF7FF), primary, 0.14f).copy(alpha = if (hasLiveBackdrop) 0.24f else 0.84f)
  } else {
    lerp(Color(0xFF0A1922), primary, 0.16f).copy(alpha = if (hasLiveBackdrop) 0.30f else 0.86f)
  }

  // Лёгкая водная глубина снизу без резкого белого пластикового глянца сверху
  val waterDepthBrush = remember(isLight, primary) {
    Brush.verticalGradient(
      colors = if (isLight) {
        listOf(
          Color.White.copy(alpha = 0.12f),
          Color.Transparent,
          lerp(Color(0xFF007EA7), primary, 0.45f).copy(alpha = 0.09f)
        )
      } else {
        listOf(
          Color(0xFF8BE0FF).copy(alpha = 0.06f),
          Color.Transparent,
          Color.Black.copy(alpha = 0.16f)
        )
      }
    )
  }

  // Тонкий «влажный» контур поверхностного натяжения
  val wetEdgeBrush = remember(isLight, primary) {
    Brush.linearGradient(
      colors = if (isLight) {
        listOf(
          Color.White.copy(alpha = 0.45f),
          primary.copy(alpha = 0.18f),
          Color(0xFF7AD7F0).copy(alpha = 0.28f)
        )
      } else {
        listOf(
          Color(0xFFBCEEFF).copy(alpha = 0.22f),
          primary.copy(alpha = 0.16f),
          Color.White.copy(alpha = 0.08f)
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
    // 1. Слой преломления волн и мягкого размытия фона строго внутри элемента
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

    // 2. Прозрачный слой воды, глубина и влажная кромка
    Box(
      modifier = Modifier
        .matchParentSize()
        .background(resolvedTint)
        .background(waterDepthBrush)
        .border(width = 0.85.dp, brush = wetEdgeBrush, shape = shape)
    )

    // 3. Чёткий контент поверх воды
    CompositionLocalProvider(LocalContentColor provides contentColor) {
      content()
    }
  }
}
