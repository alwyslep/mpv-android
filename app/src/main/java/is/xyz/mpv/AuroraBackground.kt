package `is`.xyz.mpv

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import androidx.preference.PreferenceManager
import kotlin.math.cos

/**
 * B-57(UI) v3: 동적 앰비언트 배경 *엔진*. 단일 오로라 → 다중 효과(plug-in)로 일반화.
 *
 * 구조:
 *  - [AuroraEffect]      : 한 가지 패턴(mesh/wave/spiral/…)의 draw 구현. [AuroraEffects] 레지스트리.
 *  - [AuroraConfig]      : 설정에서 읽은 런타임 스냅샷(효과 id·색회전/맥동 주기·맥동 깊이·채도 등).
 *  - [AuroraScratch]     : 프레임마다 재사용하는 Paint/버퍼(GC 절약).
 *  - [AuroraDrawable]    : Choreographer 로 구동하는 window 배경. 선택된 효과에 위임.
 *  - [AuroraMath]        : hue 회전·은은한 검정 맥동·HSV 공통 수식.
 *
 * 설정값은 [apply] 시점(액티비티 생성/재진입)에 스냅샷 → 설정 변경은 다음 화면부터 반영.
 * [setVisible] false(비가시) 시 정지해 배터리 보호.
 */

/** 설정에서 읽은 오로라 런타임 스냅샷. */
data class AuroraConfig(
    val effectId: String,
    val huePeriodMs: Long,   // 색상환 한 바퀴(색 변화 속도)
    val pulsePeriodMs: Long, // 은은한 검정 맥동(호흡) 한 번
    val pulseDepth: Float,   // 0 = 맥동 없음 … 1 = 거의 검정까지
    val sat: Float,          // 채도
    val value: Float,        // 명도
    val baseAlpha: Int,      // 글로우 기본 불투명도
    val randomCycle: Boolean = false,  // 82: 랜덤 프리셋이면 한 화면 안에서도 몇 초마다 무작위 효과로 전환
)

/** 효과들이 공유하는 재사용 버퍼 — 프레임마다 새 객체 생성 방지. */
class AuroraScratch {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    val basePaint = Paint().apply { color = AuroraDrawable.BASE }
    val hsv = FloatArray(3)
}

/** 한 가지 동적 배경 패턴. */
interface AuroraEffect {
    val id: String
    fun draw(canvas: Canvas, b: Rect, tMs: Long, cfg: AuroraConfig, s: AuroraScratch)
}

/** hue 회전·맥동·HSV 공통 수식 — 모든 효과가 공유. */
object AuroraMath {
    fun hueBase(tMs: Long, periodMs: Long): Float =
        if (periodMs <= 0L) 0f else (tMs % periodMs) / periodMs.toFloat() * 360f

    /** 0..1..0 부드러운 맥동. depth 0 = 항상 1(맥동 없음). */
    fun pulse(tMs: Long, periodMs: Long, depth: Float): Float {
        if (depth <= 0f || periodMs <= 0L) return 1f
        val ph = (tMs % periodMs) / periodMs.toFloat()
        val min = (1f - depth).coerceIn(0f, 1f)
        val s = (0.5 - 0.5 * cos(ph * 2.0 * Math.PI)).toFloat()
        return min + (1f - min) * s
    }

    /** 글로우 기본 불투명도에 맥동을 곱한 현재 alpha(0..255). */
    fun alpha(cfg: AuroraConfig, tMs: Long): Int =
        (cfg.baseAlpha * pulse(tMs, cfg.pulsePeriodMs, cfg.pulseDepth)).toInt().coerceIn(0, 255)

    /** ARGB. h 는 자동 정규화. */
    fun hsv(s: AuroraScratch, h: Float, sat: Float, v: Float, a: Int): Int {
        s.hsv[0] = ((h % 360f) + 360f) % 360f
        s.hsv[1] = sat.coerceIn(0f, 1f)
        s.hsv[2] = v.coerceIn(0f, 1f)
        return Color.HSVToColor(a.coerceIn(0, 255), s.hsv)
    }

    /** rgb(하위 24bit)에 alpha 배율 f 를 입힌 ARGB. */
    fun withAlpha(rgb: Int, baseA: Int, f: Float): Int =
        ((baseA * f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
}

class AuroraDrawable(private val cfg: AuroraConfig) : Drawable() {

    private val scratch = AuroraScratch()
    private var effect: AuroraEffect = AuroraEffects.byId(cfg.effectId)
    // 82: 랜덤 프리셋이면 한 화면 안에서도 4~10초(랜덤) 간격으로 무작위 효과 전환.
    private var nextSwapMs = if (cfg.randomCycle) SystemClock.uptimeMillis() + randSwapDelay() else 0L

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

        val now = SystemClock.uptimeMillis()
        if (cfg.randomCycle && now >= nextSwapMs) {   // 82: 주기마다 무작위 효과 전환(현재와 다른 것)
            var e = AuroraEffects.ALL.random()
            if (AuroraEffects.ALL.size > 1) while (e === effect) e = AuroraEffects.ALL.random()
            effect = e
            nextSwapMs = now + randSwapDelay()
        }
        canvas.drawRect(b, scratch.basePaint)
        effect.draw(canvas, b, now, cfg, scratch)
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
        val BASE = Color.parseColor("#0A0B10")

        private const val FRAME_DELAY_MS = 33L   // ≈30fps (이전 45L≈22fps) — 더 부드러운 움직임

        // 82: 랜덤 사이클 효과 전환 간격(4~10초 무작위).
        private fun randSwapDelay(): Long = kotlin.random.Random.nextLong(4000L, 10000L)

        // ── SharedPreferences 키 ──
        const val KEY_ENABLED = "aurora_enabled"
        const val KEY_PRESET = "aurora_preset"          // AuroraPresets.id (효과+분위기 60종)
        const val KEY_SPEED = "aurora_speed"            // 25~400 (%) 전역 속도 배율
        const val KEY_PULSE_DEPTH = "aurora_pulse_depth" // 0~100(%) 검정 맥동 깊이 override
        const val KEY_SAT = "aurora_sat"     // 0~100 채도 (전역, preset 대체)
        const val KEY_VALUE = "aurora_value" // 0~100 명도 (전역)
        const val KEY_GLOW = "aurora_glow"   // 10~100 글로우 세기(%) → baseAlpha

        // ── 기본값 ──
        const val DEF_PRESET = AuroraPresets.DEFAULT_ID
        const val DEF_SPEED = 100

        fun configFromPrefs(prefs: SharedPreferences): AuroraConfig {
            // SeekBarPreference 는 Int 저장. 옛 String 저장분 호환 위해 안전 변환.
            fun intPref(key: String, def: Int): Int = try {
                prefs.getInt(key, def)
            } catch (_: ClassCastException) {
                prefs.getString(key, def.toString())?.toIntOrNull() ?: def
            }
            val presetId = prefs.getString(KEY_PRESET, DEF_PRESET)
            val preset = AuroraPresets.byId(presetId)   // 랜덤이면 초기 1개 무작위(이후 drawable 이 주기 전환)
            val scale = intPref(KEY_SPEED, DEF_SPEED).coerceIn(25, 400) / 100f
            // 맥동 깊이: 슬라이더가 설정돼 있으면 우선, 없으면 프리셋 기본값.
            val depthPct = intPref(KEY_PULSE_DEPTH, preset.depth).coerceIn(0, 100)
            return AuroraConfig(
                effectId = preset.effect,
                huePeriodMs = (preset.hueSec * 1000f / scale).toLong().coerceAtLeast(500L),
                pulsePeriodMs = (preset.pulseSec * 1000f / scale).toLong().coerceAtLeast(300L),
                pulseDepth = depthPct / 100f,
                // 채도·명도·글로우는 전역 슬라이더(없으면 preset 기본). preset 은 효과·색속도·맥동에 집중.
                sat = intPref(KEY_SAT, (preset.sat * 100).toInt()).coerceIn(0, 100) / 100f,
                value = intPref(KEY_VALUE, (preset.value * 100).toInt()).coerceIn(0, 100) / 100f,
                baseAlpha = (intPref(KEY_GLOW, 38).coerceIn(10, 100) * 255 / 100),
                randomCycle = AuroraPresets.isRandom(presetId),   // 82: 랜덤이면 화면 안에서도 주기 전환
            )
        }

        /** 액티비티 윈도우 배경을 동적 오로라로. (off 시 단색 바탕) */
        fun apply(activity: Activity) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                if (!prefs.getBoolean(KEY_ENABLED, true)) {
                    activity.window.setBackgroundDrawable(ColorDrawable(BASE))
                    return
                }
                activity.window.setBackgroundDrawable(AuroraDrawable(configFromPrefs(prefs)))
            } catch (_: Throwable) {
            }
        }
    }
}
