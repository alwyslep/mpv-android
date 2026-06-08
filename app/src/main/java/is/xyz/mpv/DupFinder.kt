package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

// B-68(52): 품번 중복 찾기 — JavCode.extract 로 뽑은 품번(정규화)이 같은 파일이 2개 이상이면 중복으로 묶어 표시.
//   해상도 다른 같은 작품(예: ABP-388 / ABP-388_720p)·여러 폴더 중복 등을 한눈에. 삭제는 기존 롱프레스로.
object DupFinder {
    private fun norm(code: String) = code.uppercase().replace("-", "").replace("_", "").replace(" ", "")

    fun show(ctx: Context, items: List<Pair<Uri, String>>) {
        Thread {
            val groups = HashMap<String, MutableList<String>>()
            for ((_, name) in items) {
                val code = JavCode.extract(name)?.let { norm(it) } ?: continue
                groups.getOrPut(code) { ArrayList() }.add(name)
            }
            val dups = groups.filter { it.value.size > 1 }.toList().sortedByDescending { it.second.size }
            (ctx as? Activity)?.runOnUiThread {
                if (dups.isEmpty()) { Toast.makeText(ctx, "품번 중복 없음 (${items.size}개 검사)", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val sb = StringBuilder()
                var totalFiles = 0
                for ((code, names) in dups) {
                    sb.append("● $code  (${names.size})\n")
                    names.sorted().forEach { sb.append("    $it\n") }
                    sb.append('\n'); totalFiles += names.size
                }
                AlertDialog.Builder(ctx)
                    .setTitle("품번 중복 ${dups.size}건 · ${totalFiles}개 파일")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("닫기", null)
                    .show()
            }
        }.start()
    }
}
