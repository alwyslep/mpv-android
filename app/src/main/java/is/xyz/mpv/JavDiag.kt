package `is`.xyz.mpv

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// B-68 진단 로거 — /sdcard/Download/mpv-jav-diag.log 에 append. cc 가 직접 읽어 분석.
//   (MANAGE_EXTERNAL_STORAGE 보유 → 외부 Download 직접 쓰기 가능). 수정 확정 후 호출부와 함께 제거.
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
