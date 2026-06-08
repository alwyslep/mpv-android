package `is`.xyz.mpv

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

// B-68: 모든 라이브러리 액티비티 상단에 일괄작업 진행 배너를 띄운다(플레이어 제외).
//   MpvApplication 의 onActivityResumed/Paused 에서 attach/detach → JobProgress 상태를 표시.
//   content 루트(ContentFrameLayout)에 프로그램적으로 얹어 레이아웃 수정 불필요.
object JobBanner {
    private const val TAG = "jav_job_banner"

    fun attach(act: Activity) {
        if (act is MPVActivity) return                       // 플레이어엔 영상 컨트롤과 겹쳐 제외
        val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        var tv = root.findViewWithTag<TextView>(TAG)
        if (tv == null) {
            tv = TextView(act).apply {
                tag = TAG
                setBackgroundColor(0xE0202830.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding(40, 22, 40, 22)
                visibility = android.view.View.GONE
            }
            root.addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        JobProgress.attach(tv)
    }

    fun detach(act: Activity) {
        if (act is MPVActivity) return
        val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        root.findViewWithTag<TextView>(TAG)?.let { JobProgress.detach(it) }
    }
}
