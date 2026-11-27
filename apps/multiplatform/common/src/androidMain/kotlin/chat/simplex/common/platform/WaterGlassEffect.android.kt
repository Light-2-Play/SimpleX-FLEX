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
      RuntimeShader(WATER_SURFACE_AGSL)
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
    private const val WATER_SURFACE_AGSL = """
      uniform shader content;
      uniform float2 resolution;
      uniform float time;
      uniform float cornerRadius;

      float sdRoundedBox(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
      }

      // Многослойная карта высот водной поверхности в физическом масштабе пикселей
      float waterHeight(float2 pos, float t) {
        float2 p = pos * 0.032;
        float h = 0.0;
        h += sin(dot(p, float2(1.0, 0.35)) + t * 1.0) * 0.42;
        h += sin(dot(p, float2(-0.65, 0.85)) - t * 0.8) * 0.32;
        h += sin(dot(p, float2(0.45, -1.15)) + t * 1.3) * 0.16;
        h += cos(dot(p, float2(-1.15, -0.55)) - t * 1.5) * 0.10;
        return h;
      }

      half4 main(float2 fragCoord) {
        float2 halfSize = max(resolution * 0.5, float2(1.0));
        float2 p = fragCoord - halfSize;
        float r = min(cornerRadius, min(halfSize.x, halfSize.y));

        // 1. Градиент волн (реальная нормаль водной ряби по всей площади элемента)
        float stepPx = 2.0;
        float hC = waterHeight(fragCoord, time);
        float hX = waterHeight(fragCoord + float2(stepPx, 0.0), time);
        float hY = waterHeight(fragCoord + float2(0.0, stepPx), time);
        float2 waveGrad = float2(hX - hC, hY - hC) / stepPx;

        // 2. Мягкое поверхностное натяжение (мениск) у краёв капсулы
        float d = sdRoundedBox(p, halfSize, r);
        float edgeBand = min(16.0, min(halfSize.x, halfSize.y) * 0.55);
        float edgeFactor = smoothstep(-edgeBand, 0.0, d);

        float edgeEps = 1.5;
        float ex = sdRoundedBox(p + float2(edgeEps, 0.0), halfSize, r) - sdRoundedBox(p - float2(edgeEps, 0.0), halfSize, r);
        float ey = sdRoundedBox(p + float2(0.0, edgeEps), halfSize, r) - sdRoundedBox(p - float2(0.0, edgeEps), halfSize, r);
        float2 edgeNormal = normalize(float2(ex, ey) + float2(0.0001));

        // 3. Преломление фона сквозь толщу воды (рябь + мягкий мениск)
        float2 rippleRefraction = waveGrad * 34.0;
        float2 meniscusRefraction = -edgeNormal * (edgeFactor * edgeFactor * 7.5);
        float2 maxCoord = max(resolution - float2(1.0), float2(0.0));
        float2 refractedCoord = clamp(fragCoord + rippleRefraction + meniscusRefraction, float2(0.0), maxCoord);

        half4 baseSample = content.eval(refractedCoord);

        // 4. Живые блики на гребнях волн и мягкая водная каустика
        float3 surfNormal = normalize(float3(-waveGrad.x * 2.6, -waveGrad.y * 2.6, 1.0));
        float3 lightDir = normalize(float3(-0.35, -0.75, 1.0));
        float specular = pow(max(0.0, dot(surfNormal, lightDir)), 12.0);

        float curvature = (hX + hY - 2.0 * hC);
        float caustic = clamp(-curvature * 2.2, 0.0, 1.0);

        // Лёгкая тень в ложбинах и свет на гребнях для объёма воды
        float waveShade = hC * 0.035;
        float lightBoost = specular * 0.14 + caustic * 0.08 + waveShade;

        // Деликатный влажный блик по кромке мениска
        float meniscusLight = max(0.0, dot(edgeNormal, normalize(float2(-0.45, -0.9))));
        float meniscusGlow = edgeFactor * edgeFactor * meniscusLight * 0.10;

        half3 rgb = clamp(
          baseSample.rgb + half3(lightBoost + meniscusGlow),
          half3(0.0),
          half3(1.0)
        );
        return half4(rgb, baseSample.a);
      }
    """
  }
}
