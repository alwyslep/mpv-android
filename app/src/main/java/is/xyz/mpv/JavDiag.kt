package `is`.xyz.mpv

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 진단 로거(영구 인프라) — /sdcard/Download/mpv-jav-diag.log 에 append. cc 가 proot 서 직접 Read 해 분석.
//   원칙: 오류는 토스트/추측 아닌 로그 파일로 잡는다(사용자 못박음 2026-06-08). adb 는 proot daemon 불가.
//   MANAGE_EXTERNAL_STORAGE 보유 → 외부 Download 직접 쓰기. 무음 catch 금지 — 잡으면 JavDiag.ex 로 남길 것.
//   진단 끝나면 *호출부* 만 정리하고 이 로거 자체는 유지(다음 버그 때 재사용).
object JavDiag {
    private val FILE by lazy { File(Environment.getExternalStorageDirectory(), "Download/mpv-jav-diag.log") }
    private val fmt by lazy { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    @Synchronized
    fun log(tag: String, msg: String) {
        try { FILE.appendText("[${fmt.format(Date())}][$tag] $msg\n") } catch (_: Throwable) {}
    }

    fun ex(tag: String, e: Throwable) {
        log(tag, "EXCEPTION ${e.javaClass.name}: ${e.message}")
        try { log(tag, e.stackTraceToString().lineSequence().take(8).joinToString(" | ")) } catch (_: Throwable) {}
    }
}
