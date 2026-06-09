package `is`.xyz.mpv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

// B-68(52)/57: 일괄작업 진행 배너 — 화면 맨 아래 불투명 1줄. [텍스트(축약)] + [더블 진행바(전체/개별)] + [안전 중단].
//   전체바=전 항목 진행(N/총), 개별바=현 항목 진행(%). 버튼=graceful(현 항목 마치고 멈춤), 위쪽에 상세 툴팁.
//   임베드/복구/이동/썸네일 공용. MpvApplication onResume/Pause attach. 플레이어 제외.
object JobBanner {
    private const val TAG = "jav_job_banner"
    private const val BG = 0xF21A2230.toInt()
    private const val C_ALL = 0xFF42C2FF.toInt()    // 전체(전 항목) 진행 — 파랑
    private const val C_ITEM = 0xFF66BB6A.toInt()   // 개별(현 항목) 진행 — 초록
    private const val CANCEL_TXT = "안전 중단"
    private const val CANCEL_TIP = "진행 중인 항목까지 안전하게 마치고 멈춥니다.\n작업물 손상 없이 정상 종료(중간에 강제로 끊지 않음)."

    fun attach(act: Activity) {
        if (act is MPVActivity) return
        val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        var bar = root.findViewWithTag<LinearLayout>(TAG)
        if (bar == null) {
            bar = build(act)
            root.addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        val b = bar
        JobProgress.bind { render(b) }
    }

    fun detach(act: Activity) {
        if (act is MPVActivity) return
        JobProgress.unbind()
    }

    private fun build(ctx: Activity): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val label = TextView(ctx).apply {
            tag = "t"; setTextColor(Color.WHITE); textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { gravity = Gravity.CENTER_VERTICAL }
        }
        // 더블 진행바 — 전체(위, 파랑)/개별(아래, 초록) 세로 스택. 작은 캡션으로 구분.
        fun caption(t: String) = TextView(ctx).apply {
            text = t; setTextColor(0xFFB0BEC5.toInt()); textSize = 9f
            layoutParams = LinearLayout.LayoutParams(px(26f), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_VERTICAL }
        }
        fun bar(tg: String, color: Int) = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = tg; max = 100; progressTintList = ColorStateList.valueOf(color)
            scaleY = 0.75f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { gravity = Gravity.CENTER_VERTICAL }
        }
        val rowAll = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(caption("전체")); addView(bar("oa", C_ALL))
        }
        val rowItem = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(3f), 0, 0)
            addView(caption("항목")); addView(bar("oi", C_ITEM))
        }
        val bars = LinearLayout(ctx).apply {
            tag = "bars"; orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)   // 57: 길게(label:bars=1:3)
                .apply { marginStart = px(18f); gravity = Gravity.CENTER_VERTICAL }
            addView(rowAll); addView(rowItem)
        }
        val cancel = Button(ctx).apply {
            tag = "c"; text = CANCEL_TXT; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(0x33FFFFFF)
            minWidth = 0; minimumWidth = 0; setPadding(px(20f), px(6f), px(20f), px(6f))
            setOnClickListener { JobProgress.requestCancel(); isEnabled = false; text = "마치는 중…" }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(16f); gravity = Gravity.CENTER_VERTICAL }
        }
        Utils.tip(cancel, CANCEL_TIP, above = true)   // 57: 버튼 위(베너보다 약간 높게) 상세 툴팁
        return LinearLayout(ctx).apply {
            tag = TAG; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG); setPadding(px(36f), px(12f), px(36f), px(12f))
            visibility = View.GONE
            addView(label); addView(bars); addView(cancel)
        }
    }

    private fun render(bar: LinearLayout) {
        val show = JobProgress.active || JobProgress.doneMsg.isNotEmpty()
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val text = bar.findViewWithTag<TextView>("t")
        val bars = bar.findViewWithTag<LinearLayout>("bars")
        val pbAll = bar.findViewWithTag<ProgressBar>("oa")
        val pbItem = bar.findViewWithTag<ProgressBar>("oi")
        val cancel = bar.findViewWithTag<Button>("c")
        if (JobProgress.doneMsg.isNotEmpty()) {
            text.text = JobProgress.doneMsg; bars.visibility = View.GONE; cancel.visibility = View.GONE
            return
        }
        val tot = JobProgress.total.coerceAtLeast(1)
        // 텍스트 축약 — 진행은 바가 보여주므로 제목·항목명 위주(N/총·% 는 바로 대체).
        val parts = listOfNotNull(JobProgress.title.ifEmpty { null }, JobProgress.line.ifEmpty { null })
        text.text = parts.joinToString("  ·  ")
        bars.visibility = View.VISIBLE
        pbAll.progress = (JobProgress.idx + 1) * 100 / tot           // 전체: 항목 진행
        pbItem.progress = JobProgress.pct                            // 개별: 현 항목 진행
        cancel.visibility = View.VISIBLE
        if (!JobProgress.isCancelled()) { cancel.isEnabled = true; cancel.text = CANCEL_TXT }
    }
}
