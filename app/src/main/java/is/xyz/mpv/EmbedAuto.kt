package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

// B-68(52): 자동 일괄 임베드 — (일괄)복구처럼 후보만 자동 처리. 선택 불필요.
//   embedBatch(skipIfHasCover=true): 이미 커버 있는 mp4 는 skip(⊘), 커버 없거나 TS 인 것만 풀임베드(remux+메타+커버).
//   진행/중단은 하단 배너(JobProgress) 공용. SelectionController 무관(SAF 등 선택모드 없는 화면도 사용).
object EmbedAuto {
    fun confirm(ctx: Context, items: List<Pair<Uri, String>>, onDone: () -> Unit) {
        if (items.isEmpty()) { Toast.makeText(ctx, "영상 없음", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(ctx)
            .setTitle("임베드 (자동)")
            .setMessage("${items.size}개 중 메타·커버 없는 영상만 자동 임베드합니다.\n(이미 임베드된 것은 건너뜀)")
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .setPositiveButton("임베드") { _, _ -> run(ctx, items, onDone) }
            .show()
    }

    private fun run(ctx: Context, items: List<Pair<Uri, String>>, onDone: () -> Unit) {
        val app = ctx.applicationContext
        JobProgress.start("임베드(자동) (${items.size}개)", items.size)
        JEmbed.embedBatch(app, items,
            onProgress = { idx, _, code, stage, pct ->
                JobProgress.update(idx, "$code  $stage", if (stage == "remux") pct else if (stage == "embed") 100 else 0)
            },
            onDone = { ok, fail, _ ->
                JobProgress.done("임베드(자동) ${if (JobProgress.isCancelled()) "중단됨" else "완료"}: 성공 $ok, 실패 $fail")
                (ctx as? Activity)?.runOnUiThread { runCatching { onDone() } }
            },
            cancel = { JobProgress.isCancelled() },
            skipIfHasCover = true)
    }
}
