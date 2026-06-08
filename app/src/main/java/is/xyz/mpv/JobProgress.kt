package `is`.xyz.mpv

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

// B-68: 백그라운드 일괄작업(임베드/PNG복구) 진행상태 — 화면 전환에도 유지(싱글턴).
//   작업은 액티비티와 무관하게 백그라운드 스레드에서 진행하고, 여기에 상태만 갱신한다.
//   현재 resume 된 액티비티가 상단 배너 TextView 를 attach → 이 상태를 표시. (동시 resume=1개라 단일 슬롯 충분.)
//   비모달(배너라 다른 조작 가능) + 지속(전환해도 새 화면이 같은 상태를 다시 그림) 동시 달성.
object JobProgress {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var active = false; private set
    @Volatile var text = ""; private set
    @Volatile private var view: TextView? = null

    fun attach(tv: TextView) { view = tv; render() }     // 액티비티 onResume
    fun detach(tv: TextView) { if (view === tv) view = null }  // onPause

    fun start(t: String) { active = true; text = t; render() }
    fun update(t: String) { if (active) { text = t; render() } }
    fun done(t: String) {
        active = false; text = t; render()
        main.postDelayed({ if (!active) { text = ""; render() } }, 3500)   // 완료 메시지 잠깐 보여주고 사라짐
    }

    private fun render() = main.post {
        val v = view ?: return@post
        v.text = text
        v.visibility = if (active || text.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
