package chat.simplex.common.platform

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

actual class WaterGlassShaderHolder {
  private var cachedBlurRadiusPx: Float = -1f
  private var cachedBlurEffect: AndroidRenderEffect? = null

  private val waterShader: RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    try {
      RuntimeShader(WATER_GLASS_AGSL)
    } catch (_: Throwable) {
      null
    }
  } else {
    null
  }

  actual val supportsRealBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

  actual val supportsWaterShader: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && waterShader != null

  actual fun buildRenderEffect(
    widthPx: Float,
    heightPx: Float,
    blurRadiusPx: Float,
    cornerRadiusPx: Float,
    timeSec: Float
  ): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    if (widthPx <= 1f || heightPx <= 1f) return null

    val radius = blurRadiusPx.coerceAtLeast(1f)
    val blurEffect = if (cachedBlurEffect != null && cachedBlurRadiusPx == radius) {
      cachedBlurEffect!!
    } else {
      AndroidRenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR).also {
        cachedBlurEffect = it
        cachedBlurRadiusPx = radius
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && waterShader != null) {
      return try {
        waterShader.setFloatUniform("resolution", widthPx, heightPx)
        waterShader.setFloatUniform("time", timeSec)
        waterShader.setFloatUniform("cornerRadius", cornerRadiusPx)
        val refractionEffect = AndroidRenderEffect.createRuntimeShaderEffect(waterShader, "content")
        AndroidRenderEffect.createChainEffect(refractionEffect, blurEffect).asComposeRenderEffect()
      } catch (_: Throwable) {
        blurEffect.asComposeRenderEffect()
      }
    }

    return blurEffect.asComposeRenderEffect()
  }

  companion object {
    private const val WATER_GLASS_AGSL = """
      uniform shader content;
      uniform float2 resolution;
      uniform float time;
      uniform float cornerRadius;

      float sdRoundedBox(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
      }

      half4 main(float2 fragCoord) {
        float2 halfSize = max(resolution * 0.5, float2(1.0));
        float2 p = fragCoord - halfSize;
        float r = min(cornerRadius, min(halfSize.x, halfSize.y));

        // Расстояние до края капсулы для эффекта водного мениска
        float d = sdRoundedBox(p, halfSize, r);
        float edgeBand = min(24.0, min(halfSize.x, halfSize.y) * 0.7);
        float edgeFactor = smoothstep(-edgeBand, 0.0, d);

        // Нормаль к изогнутому краю
        float eps = 1.5;
        float dx = sdRoundedBox(p + float2(eps, 0.0), halfSize, r) - sdRoundedBox(p - float2(eps, 0.0), halfSize, r);
        float dy = sdRoundedBox(p + float2(0.0, eps), halfSize, r) - sdRoundedBox(p - float2(0.0, eps), halfSize, r);
        float2 normal = normalize(float2(dx, dy) + float2(0.0001));

        // Плавная органическая рябь воды внутри элемента
        float2 uv = fragCoord / max(resolution, float2(1.0));
        float waveX = sin(uv.y * 9.0 + time * 1.1) * cos(uv.x * 5.0 - time * 0.7);
        float waveY = cos(uv.x * 9.0 - time * 0.9) * sin(uv.y * 5.0 + time * 0.8);
        float2 waterRipple = float2(waveX, waveY) * (2.0 + edgeFactor * 3.0);

        // Оптическое преломление линзы по краям (как капля воды / жидкое стекло)
        float edgeSq = edgeFactor * edgeFactor;
        float2 lensOffset = -normal * (edgeSq * 15.0);
        float2 totalOffset = lensOffset + waterRipple;

        // Лёгкая хроматическая дисперсия у кромки
        float chroma = edgeFactor * 1.5;
        float2 maxCoord = max(resolution - float2(1.0), float2(0.0));
        float2 coordR = clamp(fragCoord + totalOffset + normal * chroma, float2(0.0), maxCoord);
        float2 coordG = clamp(fragCoord + totalOffset, float2(0.0), maxCoord);
        float2 coordB = clamp(fragCoord + totalOffset - normal * chroma, float2(0.0), maxCoord);

        half rCh = content.eval(coordR).r;
        half gCh = content.eval(coordG).g;
        half bCh = content.eval(coordB).b;
        half aCh = content.eval(coordG).a;

        // Мягкий световой блик и каустика воды
        float lightDir = dot(normal, normalize(float2(-0.6, -0.8)));
        float rimGlow = edgeSq * max(lightDir, 0.0) * 0.22;
        float waterCaustic = max(0.0, waveX * waveY) * 0.05;

        half3 rgb = clamp(half3(rCh, gCh, bCh) + half3(rimGlow + waterCaustic), half3(0.0), half3(1.0));
        return half4(rgb, aCh);
      }
    """
  }
}
