package `is`.xyz.mpv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 동적 배경 효과 레지스트리. 코어 패턴들 — 각자 [AuroraEffect.draw] 구현.
 * 모두 gradient/shape 기반이라 ~22fps Choreographer 에서 가볍다(픽셀 루프 없음).
 * 색 회전·맥동은 [AuroraMath] 공통 수식 사용 → 효과는 "모양"만 책임.
 *
 * 50+ 선택지는 이 코어들에 파라미터(속도/팔레트/밀도)를 입힌 *프리셋*([AuroraPresets])으로 확장.
 */
object AuroraEffects {
    val ALL: List<AuroraEffect> = listOf(
        MeshEffect,
        WaveEffect,
        SpiralEffect,
        RippleEffect,
        OrbitEffect,
        CurtainEffect,
        BlobsEffect,
        SweepEffect,
        StarfieldEffect,
        KaleidoscopeEffect,
        GradientFlowEffect,
        PlasmaEffect,
    )

    private val map: Map<String, AuroraEffect> = ALL.associateBy { it.id }

    fun byId(id: String): AuroraEffect = map[id] ?: MeshEffect
}

/** 기존 오로라 — 5개 글로우 블롭이 색상환 회전 + sin/cos 표류. */
object MeshEffect : AuroraEffect {
    override val id = "mesh"
    private const val N = 5
    private val BASE_FX = floatArrayOf(0.12f, 0.42f, 0.78f, 0.95f, 0.55f)
    private val BASE_FY = floatArrayOf(-0.06f, -0.14f, -0.08f, 0.05f, 0.34f)
    private val FR = floatArrayOf(0.92f, 1.02f, 0.96f, 0.88f, 0.80f)
    private val SX = floatArrayOf(0.13f, 0.17f, 0.11f, 0.19f, 0.15f)
    private val SY = floatArrayOf(0.10f, 0.14f, 0.16f, 0.12f, 0.18f)

    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val span = max(b.width(), b.height()).toFloat()
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val posT = tMs / 1000f
        val a = AuroraMath.alpha(cfg, tMs)
        for (i in 0 until N) {
            val rgb = AuroraMath.hsv(s, hueBase + i * (360f / N), cfg.sat, cfg.value, a)
            val drift = 0.07f
            val fx = BASE_FX[i] + drift * sin(posT * SX[i] + i * 1.7f)
            val fy = BASE_FY[i] + (drift * 0.7f) * cos(posT * SY[i] + i * 2.3f)
            val cx = b.left + fx * b.width()
            val cy = b.top + fy * b.height()
            val r = FR[i] * span
            if (r <= 0f) continue
            s.paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    AuroraMath.withAlpha(rgb, a, 1f),
                    AuroraMath.withAlpha(rgb, a, 0.55f),
                    AuroraMath.withAlpha(rgb, a, 0.2f),
                    AuroraMath.withAlpha(rgb, a, 0f),
                ),
                floatArrayOf(0f, 0.45f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, s.paint)
        }
    }
}

/** 세로 무지개 띠가 색상환을 따라 흐르며 살짝 출렁(wave). */
object WaveEffect : AuroraEffect {
    override val id = "wave"
    private const val N = 7
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val flow = tMs / 1000f
        val colors = IntArray(N + 1)
        val pos = FloatArray(N + 1)
        for (k in 0..N) {
            val h = hueBase + k * (360f / N) + 18f * sin(flow * 0.5f + k * 0.9f)
            colors[k] = AuroraMath.hsv(s, h, cfg.sat, cfg.value, a)
            pos[k] = k / N.toFloat()
        }
        s.paint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(),
            colors, pos, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, s.paint)
    }
}

/** 중심에서 도는 색상환(SweepGradient) — 나선/소용돌이 느낌. */
object SpiralEffect : AuroraEffect {
    override val id = "spiral"
    private const val N = 12
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val colors = IntArray(N + 1)
        val pos = FloatArray(N + 1)
        for (k in 0..N) {
            colors[k] = AuroraMath.hsv(s, hueBase + k * (360f / N), cfg.sat, cfg.value, a)
            pos[k] = k / N.toFloat()
        }
        s.paint.shader = SweepGradient(cx, cy, colors, pos)
        val rot = if (cfg.huePeriodMs <= 0L) 0f
                  else (tMs % cfg.huePeriodMs) / cfg.huePeriodMs.toFloat() * 360f
        canvas.save()
        canvas.rotate(rot, cx, cy)
        canvas.drawRect(b, s.paint)
        canvas.restore()
    }
}

/** 중심에서 퍼지는 동심원 펄스(ripple) — 색 밴드가 바깥으로 흐른다. */
object RippleEffect : AuroraEffect {
    override val id = "ripple"
    private const val N = 10
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val span = max(b.width(), b.height()).toFloat()
        val phase = if (cfg.pulsePeriodMs <= 0L) 0f
                    else (tMs % cfg.pulsePeriodMs) / cfg.pulsePeriodMs.toFloat()
        val colors = IntArray(N + 1)
        val pos = FloatArray(N + 1)
        val twoPi = (2.0 * Math.PI).toFloat()
        for (k in 0..N) {
            val h = hueBase + k * 36f
            // 동심 밴드: sin 으로 밝기 변조, phase 로 바깥 이동.
            val band = 0.5f + 0.5f * sin(k.toFloat() / N * 6f - phase * twoPi)
            colors[k] = AuroraMath.withAlpha(
                AuroraMath.hsv(s, h, cfg.sat, cfg.value, 255), a, band
            )
            pos[k] = k / N.toFloat()
        }
        s.paint.shader = RadialGradient(cx, cy, span, colors, pos, Shader.TileMode.CLAMP)
        canvas.drawRect(b, s.paint)
    }
}

/** 색 블롭들이 중심 궤도를 공전(orbit). */
object OrbitEffect : AuroraEffect {
    override val id = "orbit"
    private const val N = 5
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val span = max(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val orbitT = tMs / 1000f
        val twoPi = (2.0 * Math.PI).toFloat()
        for (i in 0 until N) {
            val ang = orbitT * 0.3f + i * (twoPi / N)
            val rad = 0.30f * span * (0.55f + 0.45f * i.toFloat() / N)
            val bx = cx + cos(ang) * rad
            val by = cy + sin(ang) * rad
            val rgb = AuroraMath.hsv(s, hueBase + i * (360f / N), cfg.sat, cfg.value, a)
            val br = 0.50f * span
            s.paint.shader = RadialGradient(
                bx, by, br,
                intArrayOf(
                    AuroraMath.withAlpha(rgb, a, 1f),
                    AuroraMath.withAlpha(rgb, a, 0.4f),
                    AuroraMath.withAlpha(rgb, a, 0f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, s.paint)
        }
    }
}

/** 세로 빛기둥(오로라 커튼)들이 좌우로 흔들린다. */
object CurtainEffect : AuroraEffect {
    override val id = "curtain"
    private const val N = 6
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val w = b.width().toFloat()
        val t = tMs / 1000f
        val halfW = 0.12f * w
        for (i in 0 until N) {
            val baseX = (i + 0.5f) / N
            val x = b.left + (baseX + 0.05f * sin(t * 0.4f + i)) * w
            val rgb = AuroraMath.hsv(s, hueBase + i * (360f / N), cfg.sat, cfg.value, a)
            s.paint.shader = LinearGradient(
                x - halfW, 0f, x + halfW, 0f,
                intArrayOf(
                    AuroraMath.withAlpha(rgb, a, 0f),
                    AuroraMath.withAlpha(rgb, a, 1f),
                    AuroraMath.withAlpha(rgb, a, 0f),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, s.paint)
        }
    }
}

/** 큰 메타볼 3개 — 느긋하게 떠다니는 둥근 글로우. */
object BlobsEffect : AuroraEffect {
    override val id = "blobs"
    private const val N = 3
    private val FX = floatArrayOf(0.28f, 0.72f, 0.50f)
    private val FY = floatArrayOf(0.30f, 0.40f, 0.72f)
    private val SX = floatArrayOf(0.09f, 0.12f, 0.07f)
    private val SY = floatArrayOf(0.11f, 0.08f, 0.13f)
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val span = max(b.width(), b.height()).toFloat()
        val posT = tMs / 1000f
        for (i in 0 until N) {
            val rgb = AuroraMath.hsv(s, hueBase + i * (360f / N), cfg.sat, cfg.value, a)
            val cx = b.left + (FX[i] + 0.10f * sin(posT * SX[i] + i)) * b.width()
            val cy = b.top + (FY[i] + 0.10f * cos(posT * SY[i] + i)) * b.height()
            val r = 0.62f * span
            s.paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    AuroraMath.withAlpha(rgb, a, 1f),
                    AuroraMath.withAlpha(rgb, a, 0.35f),
                    AuroraMath.withAlpha(rgb, a, 0f),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, s.paint)
        }
    }
}

/** 화면 전체를 도는 단일 색상환(conic) — 가장 단순/매끈. */
object SweepEffect : AuroraEffect {
    override val id = "sweep"
    private const val N = 12
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val colors = IntArray(N + 1)
        val pos = FloatArray(N + 1)
        for (k in 0..N) {
            colors[k] = AuroraMath.hsv(s, hueBase + k * (360f / N), cfg.sat, cfg.value, a)
            pos[k] = k / N.toFloat()
        }
        s.paint.shader = SweepGradient(cx, cy, colors, pos)
        canvas.drawRect(b, s.paint)
    }
}

/** 위로 흐르는 색 별가루(starfield). 의사난수 위치, 점은 위→아래 표류. */
object StarfieldEffect : AuroraEffect {
    override val id = "starfield"
    private const val N = 60
    private fun frac(v: Float): Float { val f = v - v.toInt(); return if (f < 0f) f + 1f else f }
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val t = tMs / 1000f
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        s.paint.shader = null
        s.paint.style = Paint.Style.FILL
        for (i in 0 until N) {
            val sx = frac(sin(i * 12.9898f) * 43758.55f)
            val baseY = frac(sin(i * 78.233f) * 12543.63f)
            val speed = 0.02f + 0.05f * frac(sin(i * 3.17f) * 991.3f)
            val y = frac(baseY - t * speed)
            s.paint.color = AuroraMath.hsv(s, hueBase + i * 7f, cfg.sat * 0.7f, cfg.value, a)
            val rad = 1.5f + 3f * frac(sin(i * 5.5f) * 222.2f)
            canvas.drawCircle(b.left + sx * w, b.top + y * h, rad, s.paint)
        }
    }
}

/** 회전 대칭 만화경(kaleidoscope) — N개 섹터에 같은 글로우를 반복 배치. */
object KaleidoscopeEffect : AuroraEffect {
    override val id = "kaleidoscope"
    private const val SECTORS = 6
    private const val N = 3
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val span = max(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val posT = tMs / 1000f
        for (sct in 0 until SECTORS) {
            canvas.save()
            canvas.rotate(sct * (360f / SECTORS) + posT * 5f, cx, cy)
            for (i in 0 until N) {
                val rgb = AuroraMath.hsv(s, hueBase + i * 40f, cfg.sat, cfg.value, a)
                val bx = cx + (0.20f + 0.15f * i) * span
                val by = cy + 0.10f * span * sin(posT * 0.5f + i)
                val r = 0.30f * span
                s.paint.shader = RadialGradient(
                    bx, by, r,
                    intArrayOf(AuroraMath.withAlpha(rgb, a, 1f), AuroraMath.withAlpha(rgb, a, 0f)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(b, s.paint)
            }
            canvas.restore()
        }
    }
}

/** 대각선 무지개가 평행이동하며 흐른다(gradient flow). MIRROR 로 끊김 없이 순환. */
object GradientFlowEffect : AuroraEffect {
    override val id = "gradientflow"
    private const val N = 6
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val span = max(b.width(), b.height()).toFloat()
        val colors = IntArray(N + 1)
        val pos = FloatArray(N + 1)
        for (k in 0..N) {
            colors[k] = AuroraMath.hsv(s, hueBase + k * (360f / N), cfg.sat, cfg.value, a)
            pos[k] = k / N.toFloat()
        }
        val shift = if (cfg.huePeriodMs <= 0L) 0f
                    else (tMs % cfg.huePeriodMs) / cfg.huePeriodMs.toFloat() * span
        s.paint.shader = LinearGradient(
            b.left - span + shift, b.top.toFloat(),
            b.left + shift, b.bottom.toFloat(),
            colors, pos, Shader.TileMode.MIRROR,
        )
        canvas.drawRect(b, s.paint)
    }
}

/** 클래식 플라스마 — 저해상도(64×96) 픽셀 계산 후 bilinear 확대(가벼움). */
object PlasmaEffect : AuroraEffect {
    override val id = "plasma"
    private const val W = 64
    private const val H = 96
    private val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val px = IntArray(W * H)
    private val src = Rect(0, 0, W, H)
    override fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch) {
        val a = AuroraMath.alpha(cfg, tMs)
        val hueBase = AuroraMath.hueBase(tMs, cfg.huePeriodMs)
        val t = tMs / 1000f
        var idx = 0
        for (y in 0 until H) {
            for (x in 0 until W) {
                val v = sin(x * 0.15f + t) +
                        sin(y * 0.12f - t * 0.8f) +
                        sin((x + y) * 0.10f + t * 0.5f)
                // v ∈ -3..3 → hue 가산 0..120 (부드러운 색 띠).
                val h = hueBase + (v + 3f) / 6f * 120f
                px[idx++] = AuroraMath.hsv(s, h, cfg.sat, cfg.value, a)
            }
        }
        bmp.setPixels(px, 0, W, 0, 0, W, H)
        s.paint.shader = null
        s.paint.isFilterBitmap = true
        canvas.drawBitmap(bmp, src, b, s.paint)
    }
}
