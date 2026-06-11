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

    // 76: 단일 '만남' 진행바. 전체(완료 항목, 왼→오 파랑) + 현재 항목(미충진 영역을 오른쪽 끝→왼쪽 초록).
    //   항목이 100%면 빈칸까지 초록으로 차서 전체가 한 칸 전진(가운데서 만남).
    class MeetBar(ctx: android.content.Context) : View(ctx) {
        private var overall = 0f
        private var item = 0f
        private val pBg = android.graphics.Paint().apply { color = 0xFF2A3440.toInt() }
        private val pAll = android.graphics.Paint().apply { color = C_ALL }
        private val pItem = android.graphics.Paint().apply { color = C_ITEM }
        fun set(o: Float, i: Float) { overall = o.coerceIn(0f, 1f); item = i.coerceIn(0f, 1f); invalidate() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, pBg)              // 배경(미충진)
            val ow = w * overall
            if (ow > 0f) canvas.drawRect(0f, 0f, ow, h, pAll)            // 전체: 왼→오
            val itemW = (w - ow) * item
            if (itemW > 0f) canvas.drawRect(w - itemW, 0f, w, h, pItem)  // 항목: 오른쪽 끝→왼
        }
    }

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
        // 76: 단일 '만남' 진행바(2줄→1줄). 전체(왼→오 파랑) + 현재항목(미충진 영역 오른쪽끝→왼 초록)이 만남.
        val meet = MeetBar(ctx).apply {
            tag = "mb"
            layoutParams = LinearLayout.LayoutParams(0, px(9f), 2f)
                .apply { marginStart = px(18f); gravity = Gravity.CENTER_VERTICAL }
        }
        Utils.tip(meet, "전체 진행(파랑, 처리/총) + 현재 항목 %(초록, 오른쪽→왼쪽)", above = true)
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
            addView(label); addView(meet); addView(cancel)
        }
    }

    private fun render(bar: LinearLayout) {
        val show = JobProgress.active || JobProgress.doneMsg.isNotEmpty()
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val text = bar.findViewWithTag<TextView>("t")
        val meet = bar.findViewWithTag<MeetBar>("mb")
        val cancel = bar.findViewWithTag<Button>("c")
        if (JobProgress.doneMsg.isNotEmpty()) {
            text.text = JobProgress.doneMsg; meet.visibility = View.GONE; cancel.visibility = View.GONE
            return
        }
        val tot = JobProgress.total.coerceAtLeast(1)
        // 단순·컬러: 제목(밝은회색) · n/N(초록) · 현재항목(노랑). %는 아래 진행바가 보여주므로 텍스트에서 생략.
        //   제목 끝의 중복 "(20개)" 는 n/N 과 겹치므로 제거.
        val titleClean = JobProgress.title.replace(Regex("\\s*\\(\\d[\\d,]*개\\)\\s*$"), "")
        val sb = android.text.SpannableStringBuilder()
        fun part(s: String, color: Long) {
            val st = sb.length; sb.append(s)
            sb.setSpan(android.text.style.ForegroundColorSpan(color.toInt()), st, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        part(titleClean, 0xFFE0E0E0L)
        if (tot > 1) { sb.append("  "); part("${JobProgress.idx + 1}/$tot", 0xFF66BB6AL) }   // n/N(초록)
        sb.append("  "); part("${JobProgress.pct}%", 0xFF4FC3F7L)                              // 진행률(하늘색)
        if (JobProgress.line.isNotEmpty()) { sb.append("   "); part(JobProgress.line, 0xFFFFC107L) }  // 항목(노랑)
        text.text = sb
        meet.visibility = View.VISIBLE
        // 86: 전체바 = (완료항목×100 + 현재%)/(총×100) — *실시간*(이전 idx/tot 는 항목100% 전까지 안 움직이는 버그).
        //   항목 = 현재% 를 빈 공간(1-전체)의 비율로 오른쪽→왼쪽. 단일항목(tot=1)이면 전체가 곧 진행이라 항목 생략.
        meet.set((JobProgress.idx * 100 + JobProgress.pct) / (tot * 100f),
                 if (tot > 1) JobProgress.pct / 100f else 0f)
        cancel.visibility = View.VISIBLE
        if (!JobProgress.isCancelled()) { cancel.isEnabled = true; cancel.text = CANCEL_TXT }
    }
}
