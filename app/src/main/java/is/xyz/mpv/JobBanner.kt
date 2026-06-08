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

// B-68(52): 일괄작업 진행 배너 — 화면 맨 아래에 불투명 1줄(상단 줄처럼). 가로 한 행에 [텍스트(제목·N/총·항목·%)]
//   + [진행바] + [중단] 을 배치. 임베드/복구/이동/썸네일 공용. MpvApplication onResume/Pause attach. 플레이어 제외.
object JobBanner {
    private const val TAG = "jav_job_banner"
    private const val BG = 0xF21A2230.toInt()
    private const val C_BAR = 0xFF42C2FF.toInt()

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

    private fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()

    private fun build(ctx: Activity): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val label = TextView(ctx).apply {
            tag = "t"; setTextColor(Color.WHITE); textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { gravity = Gravity.CENTER_VERTICAL }
        }
        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "o"; max = 100; progressTintList = ColorStateList.valueOf(C_BAR)
            layoutParams = LinearLayout.LayoutParams((110 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = (22 * d).toInt(); gravity = Gravity.CENTER_VERTICAL }
        }
        val cancel = Button(ctx).apply {
            tag = "c"; text = "중단"; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(0x33FFFFFF)
            minWidth = 0; minimumWidth = 0; setPadding((24 * d).toInt(), (6 * d).toInt(), (24 * d).toInt(), (6 * d).toInt())
            setOnClickListener { JobProgress.requestCancel(); isEnabled = false; text = "중단중" }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = (18 * d).toInt(); gravity = Gravity.CENTER_VERTICAL }
        }
        return LinearLayout(ctx).apply {
            tag = TAG; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG); setPadding((36 * d).toInt(), (14 * d).toInt(), (36 * d).toInt(), (14 * d).toInt())
            visibility = View.GONE
            addView(label); addView(pb); addView(cancel)
        }
    }

    private fun render(bar: LinearLayout) {
        val show = JobProgress.active || JobProgress.doneMsg.isNotEmpty()
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val text = bar.findViewWithTag<TextView>("t")
        val pb = bar.findViewWithTag<ProgressBar>("o")
        val cancel = bar.findViewWithTag<Button>("c")
        if (JobProgress.doneMsg.isNotEmpty()) {
            text.text = JobProgress.doneMsg; pb.visibility = View.GONE; cancel.visibility = View.GONE
            return
        }
        val tot = JobProgress.total.coerceAtLeast(1)
        val parts = listOfNotNull(
            JobProgress.title.ifEmpty { null },
            if (tot > 1) "${JobProgress.idx + 1}/$tot" else null,
            JobProgress.line.ifEmpty { null },
            "${JobProgress.pct}%"
        )
        text.text = parts.joinToString("  ·  ")
        pb.visibility = View.VISIBLE
        pb.progress = if (tot > 1) (JobProgress.idx + 1) * 100 / tot else JobProgress.pct
        cancel.visibility = View.VISIBLE
        if (!JobProgress.isCancelled()) { cancel.isEnabled = true; cancel.text = "중단" }
    }
}
