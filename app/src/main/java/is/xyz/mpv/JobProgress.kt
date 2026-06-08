package `is`.xyz.mpv

import android.os.Handler
import android.os.Looper

// B-68(52): 백그라운드 일괄작업(임베드/PNG복구) 진행상태 — 화면 전환에도 유지(싱글턴).
//   하단 리치 배너(JobBanner)가 이 상태를 그린다. 우아한 종료(중단)=cancel 플래그를 작업 루프가 체크.
//   동시 resume=1개라 단일 리스너 슬롯으로 충분.
object JobProgress {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var active = false; private set
    @Volatile var title = ""; private set      // "임베드 (25개)" / "PNG 복구 (7개)"
    @Volatile var line = ""; private set        // "NGHJ-059  remux  76%"
    @Volatile var idx = 0; private set          // 전체 진행 인덱스(0-base)
    @Volatile var total = 0; private set
    @Volatile var pct = 0; private set          // 현재 항목 %
    @Volatile var doneMsg = ""; private set     // 완료/중단 메시지(잠깐 표시)
    @Volatile private var cancelFlag = false
    @Volatile private var listener: (() -> Unit)? = null

    fun bind(l: () -> Unit) { listener = l; push() }            // 액티비티 onResume
    fun unbind() { listener = null }                             // onPause (다음 resume 가 다시 bind)

    fun start(title: String, total: Int) {
        active = true; cancelFlag = false; this.title = title; this.total = total
        idx = 0; pct = 0; line = ""; doneMsg = ""; push()
    }
    fun update(idx: Int, line: String, pct: Int) {
        this.idx = idx; this.line = line; this.pct = pct; push()
    }
    fun done(msg: String) {
        active = false; doneMsg = msg; line = ""; push()
        main.postDelayed({ if (!active) { doneMsg = ""; push() } }, 4000)   // 완료문구 잠깐 보여주고 사라짐
    }

    fun requestCancel() { cancelFlag = true; line = "중단 중… (현재 항목 완료 후)"; push() }
    fun isCancelled() = cancelFlag

    private fun push() = main.post { listener?.invoke() }
}
