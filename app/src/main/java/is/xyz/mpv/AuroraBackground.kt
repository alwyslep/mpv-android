package `is`.xyz.mpv

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * B-57(UI): 제미나이 모바일 스타일 오로라/메시 그라데이션 — *동적 다색 순환* 앰비언트 배경.
 *
 * 여러 글로우 블롭의 색조(hue)가 색상환을 따라 아주 천천히 회전하며, 블롭마다 hue 가
 * 균등 오프셋돼 *항상 서로 다른 색 계열*(빨강·노랑·초록·청록·파랑·보라 …)을 동시에 띤다.
 * 위치도 sin/cos 로 부드럽게 표류 → 실시간으로 색·형태가 살아 움직인다.
 * 아래로 갈수록 near-black 으로 사라진다.
 *
 * 윈도우 배경으로 깔면 UI·영상이 불투명하게 칠하는 곳을 제외한 모든 빈/검은 영역에 비친다.
 * Choreographer 로 구동, [setVisible] false(액티비티 비가시) 시 정지해 배터리 보호.
 */
class AuroraDrawable : Drawable() {

    private val basePaint = Paint().apply { color = BASE }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)

    private var running = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidateSelf()
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_DELAY_MS)
        }
    }

    private fun start() {
        if (running) return
        running = true
        try { Choreographer.getInstance().postFrameCallback(frameCallback) } catch (_: Throwable) {}
    }

    private fun stop() {
        running = false
        try { Choreographer.getInstance().removeFrameCallback(frameCallback) } catch (_: Throwable) {}
    }

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        if (b.isEmpty) return
        if (!running && isVisible) start()   // lazy 시작

        canvas.drawRect(b, basePaint)
        val span = max(b.width(), b.height()).toFloat()
        val t = SystemClock.uptimeMillis()

        // 색조 회전 위상(0~360): HUE_PERIOD_MS 마다 한 바퀴.
        val hueBase = (t % HUE_PERIOD_MS) / HUE_PERIOD_MS.toFloat() * 360f
        // 위치 표류 위상.
        val posT = t / 1000f

        for (i in 0 until NBLOBS) {
            hsv[0] = (hueBase + i * (360f / NBLOBS)) % 360f
            hsv[1] = SAT
            hsv[2] = VAL
            val argb = Color.HSVToColor(ALPHA, hsv)

            val drift = 0.07f
            val fx = BASE_FX[i] + drift * sin(posT * SPEED_X[i] + i * 1.7f)
            val fy = BASE_FY[i] + (drift * 0.7f) * cos(posT * SPEED_Y[i] + i * 2.3f)
            val cx = b.left + fx * b.width()
            val cy = b.top + fy * b.height()
            val r = FR[i] * span
            if (r <= 0f) continue

            blobPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(argb, argb and 0x00FFFFFF), // 같은 색, alpha 0 으로 페이드
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, blobPaint)
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("deprecated in API", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        // 바탕(거의 검정, 살짝 푸른 기).
        private val BASE = Color.parseColor("#0A0B10")

        private const val NBLOBS = 5

        // 한 바퀴(전 색상 순환) 주기 — 길수록 느긋. 90초.
        private const val HUE_PERIOD_MS = 90_000L
        // 프레임 간격(ms) — 느린 앰비언트라 ~22fps 로 충분(배터리·GC 절약).
        private const val FRAME_DELAY_MS = 45L

        private const val SAT = 0.62f   // 채도(부드럽게)
        private const val VAL = 0.98f   // 명도
        private const val ALPHA = 0x60  // 글로우 불투명도(바탕 위 은은하게)

        // 블롭 기준 위치(0~1, fy 음수=화면 위) · 반지름(큰변 비율) · 표류 속도.
        private val BASE_FX = floatArrayOf(0.12f, 0.42f, 0.78f, 0.95f, 0.55f)
        private val BASE_FY = floatArrayOf(-0.06f, -0.14f, -0.08f, 0.05f, 0.34f)
        private val FR = floatArrayOf(0.92f, 1.02f, 0.96f, 0.88f, 0.80f)
        private val SPEED_X = floatArrayOf(0.13f, 0.17f, 0.11f, 0.19f, 0.15f)
        private val SPEED_Y = floatArrayOf(0.10f, 0.14f, 0.16f, 0.12f, 0.18f)

        /** 액티비티 윈도우 배경을 동적 오로라로. */
        fun apply(activity: Activity) {
            try {
                activity.window.setBackgroundDrawable(AuroraDrawable())
            } catch (_: Throwable) {
            }
        }
    }
}
