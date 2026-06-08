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

// B-68(52): 일괄작업 진행 배너 — 화면 *맨 아래*에 불투명 리치 바(제목 + 전체바 + 항목 + 항목바 + 중단버튼).
//   상단 7아이콘을 가리지 않도록 하단 배치. 임베드 중앙 팝업을 대체. MpvApplication onResume/Pause 에서 attach.
object JobBanner {
    private const val TAG = "jav_job_banner"
    private const val BG = 0xF21A2230.toInt()        // 불투명 다크
    private const val C_ALL = 0xFF42C2FF.toInt()     // 전체 진행 = 시안
    private const val C_ONE = 0xFF8BE04E.toInt()     // 항목 진행 = 그린

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
        fun bar(color: Int) = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val title = TextView(ctx).apply { tag = "t"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 0, 0, 8) }
        val pbAll = bar(C_ALL).apply { tag = "a" }
        val line = TextView(ctx).apply { tag = "l"; setTextColor(0xFFB8C4D0.toInt()); textSize = 12f; setPadding(0, 10, 0, 4) }
        val pbOne = bar(C_ONE).apply { tag = "o" }
        val cancel = Button(ctx).apply {
            tag = "c"; text = "중단"; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(0x33FFFFFF)
            setOnClickListener { JobProgress.requestCancel(); isEnabled = false; text = "중단 중…" }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 14 }
        }
        return LinearLayout(ctx).apply {
            tag = TAG; orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG); setPadding(44, 28, 44, 30); visibility = View.GONE
            addView(title); addView(pbAll); addView(line); addView(pbOne); addView(cancel)
        }
    }

    private fun render(bar: LinearLayout) {
        val show = JobProgress.active || JobProgress.doneMsg.isNotEmpty()
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val title = bar.findViewWithTag<TextView>("t")
        val pbAll = bar.findViewWithTag<ProgressBar>("a")
        val line = bar.findViewWithTag<TextView>("l")
        val pbOne = bar.findViewWithTag<ProgressBar>("o")
        val cancel = bar.findViewWithTag<Button>("c")
        if (JobProgress.doneMsg.isNotEmpty()) {          // 완료/중단 — 메시지만
            title.text = JobProgress.doneMsg
            pbAll.visibility = View.GONE; line.visibility = View.GONE; pbOne.visibility = View.GONE; cancel.visibility = View.GONE
            return
        }
        val tot = JobProgress.total.coerceAtLeast(1)
        title.text = "${JobProgress.title}    ${JobProgress.idx + 1}/$tot"
        pbAll.visibility = View.VISIBLE; pbAll.max = tot; pbAll.progress = (JobProgress.idx + 1).coerceAtMost(tot)
        line.visibility = View.VISIBLE; line.text = "${JobProgress.line}    ${JobProgress.pct}%"
        pbOne.visibility = View.VISIBLE; pbOne.progress = JobProgress.pct
        cancel.visibility = View.VISIBLE
        if (!JobProgress.isCancelled()) { cancel.isEnabled = true; cancel.text = "중단" }
    }
}
