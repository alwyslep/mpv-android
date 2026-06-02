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
import kotlin.math.max

/**
 * B-57(UI): 제미나이 모바일 신규 배경을 본뜬 오로라/메시 그라데이션 앰비언트 배경.
 *
 * 상단에 초록→틸(청록)→파랑 소프트 글로우가 번지고 아래로 갈수록 near-black 으로
 * 사라진다. 윈도우 배경(window.setBackgroundDrawable)으로 깔면 UI·영상이 불투명하게
 * 칠하는 곳을 제외한 모든 빈/검은 영역(목록 여백·플레이어 레터박스 등)에 자동으로 비친다.
 *
 * 팔레트는 [BLOBS] 한 곳에서 교체 가능(예: JAV 보라 계열).
 */
class AuroraDrawable : Drawable() {

    private data class Blob(val fx: Float, val fy: Float, val fr: Float, val argb: Int)

    private val basePaint = Paint().apply { color = BASE }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        // 큰 변 기준 반지름 — 가로/세로 어느 쪽이든 부드럽게 덮이도록.
        val span = max(b.width(), b.height()).toFloat()
        for (blob in BLOBS) {
            val cx = b.left + blob.fx * b.width()
            val cy = b.top + blob.fy * b.height()
            val r = blob.fr * span
            if (r <= 0f) continue
            blobPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(blob.argb, blob.argb and 0x00FFFFFF), // 같은 RGB, alpha 0 → 투명으로 페이드
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, blobPaint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("deprecated in API", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        // 바탕(거의 검정, 살짝 푸른 기) — 영상 외 모든 영역의 기본색.
        private val BASE = Color.parseColor("#0A0B10")

        // 오로라 글로우 블롭 — fx/fy=중심 위치(0~1, fy 음수=화면 위쪽), fr=반지름(큰변 비율), argb=색(상단 alpha).
        //   제미나이 팔레트: 초록(좌)·틸(중)·파랑(우). 색만 바꾸면 톤 전환.
        private val BLOBS = listOf(
            Blob(0.10f, -0.08f, 0.95f, Color.parseColor("#6630D27E")), // green  top-left
            Blob(0.48f, -0.14f, 1.05f, Color.parseColor("#5E22C2B4")), // teal   top-center
            Blob(0.92f, -0.02f, 1.00f, Color.parseColor("#5E2E7CF6")), // blue   top-right
            Blob(0.70f, 0.30f, 0.80f, Color.parseColor("#3A1E66C8")), // 은은한 중단 파랑 보강
        )

        /** 액티비티 윈도우 배경을 오로라로. onCreate(super 이후, setContentView 전후 무관)에서 1회 호출. */
        fun apply(activity: Activity) {
            try {
                activity.window.setBackgroundDrawable(AuroraDrawable())
            } catch (_: Throwable) {
            }
        }
    }
}
